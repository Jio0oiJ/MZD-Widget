package com.example.mzdwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 桌面组件的所有自定义动作接收器（按钮 / 闹钟 / BLE 快刷）。
 *
 * 安全原因从 CarWidget 拆出：CarWidget 必须 exported=true 才能收到系统的
 * APPWIDGET_UPDATE，若同时在此接收 CMD/SMART_START 等 action，设备上任意第三方
 * App 都能发显式广播直接控车。本接收器 exported=false，只接受本应用（含
 * PendingIntent / AlarmManager 唤醒）发出的广播，外部 App 无法投递。
 */
public class WidgetActions extends BroadcastReceiver {
    private static final String TAG = "MZDCtrlLog";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        final Context app = context.getApplicationContext();

        if (CarWidget.ACTION_SMART_START.equals(action)) {
            // v1.8.5 状态确认式三步流程：确认已锁 → 发启动并确认 → 发解锁并确认。
            // 每步 3 秒轮询车况、相邻请求硬节流 ≥3 秒，桌面由 query() 复用结果刷新；
            // 不再启动快刷 tick、不再重复查网。
            CarWidget.clearSmartUnlockDone(app);
            // 旧版 18 秒兜底闹钟会在步骤2等待期间误补发解锁，必须先清掉；
            // 新的确认+重发机制已在 CarActions 内部保证，无需兜底调度。
            CarWidget.cancelSmartUnlockFallback(app);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    CarActions.smartStartAndUnlock(app, (ok, msg) -> Log.i(TAG, "一键启动结果=" + msg));
                    CarWidget.redraw(app);
                } finally {
                    pending.finish();
                }
            }, "mzd-smart").start();
            return;
        }
        if (CarWidget.ACTION_SMART_UNLOCK.equals(action)) {
            // v1.8 起一键启动改为状态确认式三步流程，自带车况确认 + 超时重发，
            // 旧的固定 18 秒兜底闹钟已停用。但覆盖安装后系统里可能残留旧闹钟，
            // 若在这里补发解锁，会在「步骤2等待启动确认」期间错误开门，
            // 直接破坏「未确认启动绝不解锁」的保证。因此此分支只记日志、绝不发指令。
            Log.i(TAG, "收到旧版一键启动兜底闹钟，已忽略（v1.8 起由状态确认流程保证解锁）");
            return;
        }
        if (CarWidget.ACTION_CMD.equals(action)) {
            // v1.9：手动按钮触发全局快刷，车况由统一缓存提供
            CarStateCache.get(app).trigger(app);
            final String cmd = intent.getStringExtra(CarWidget.EXTRA_CMD);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    String msg;
                    try {
                        if (!Prefs.isLoggedIn(app)) {
                            msg = "请先在 App 登录或填入 Token";
                            Log.w(TAG, "桌面按钮 " + label(cmd) + " 未执行：" + msg);
                        } else {
                            String reason = "桌面按钮：" + label(cmd);
                            if (Api.CMD_UNLOCK.equals(cmd)) CarActions.unlock(app, reason);
                            else if (Api.CMD_LOCK.equals(cmd)) CarActions.lock(app, reason);
                            // 手动点击不参与 BLE 服务内部的 10 秒窗口
                            else CarActions.send(app, cmd, Api.CMD_START.equals(cmd) ? 15 : -1, reason);
                            msg = label(cmd) + "指令已发送";
                        }
                    } catch (Exception e) {
                        msg = label(cmd) + "失败：" + shortErr(e);
                        Log.e(TAG, msg);
                    }
                    CarWidget.refreshAndUpdate(app);
                } finally {
                    pending.finish();
                }
            }, "mzd-cmd").start();
            return;
        }
        if (CarWidget.ACTION_PERIODIC.equals(action)) {
            // 5 分钟正常刷新：先自续下一拍，再拉取车况并同步重绘。
            CarWidget.schedulePeriodic(app);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    CarWidget.refreshAndUpdate(app);
                } finally {
                    pending.finish();
                }
            }, "mzd-periodic").start();
            return;
        }
        if (CarWidget.ACTION_FAST_TICK.equals(action)) {
            final int left = intent.getIntExtra(CarWidget.EXTRA_TICKS_LEFT, 1);
            if (left > 1) CarWidget.scheduleFastTick(app, left - 1);
            else CarWidget.cancelFastAlarm(app);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    CarWidget.refreshAndUpdate(app);
                } finally {
                    pending.finish();
                }
            }, "mzd-fast-tick").start();
            return;
        }
        if (CarWidget.ACTION_FAST_REFRESH.equals(action)) {
            CarStateCache.get(app).trigger(app);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    CarWidget.refreshAndUpdate(app);
                } finally {
                    pending.finish();
                }
            }, "mzd-fast-start").start();
            return;
        }
        if (CarWidget.ACTION_REFRESH.equals(action)) {
            final boolean fast = intent.getBooleanExtra(CarWidget.EXTRA_FAST, false);
            if (fast) CarStateCache.get(app).trigger(app);
            final PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    CarWidget.refreshAndUpdate(app);
                } finally {
                    pending.finish();
                }
            }, "mzd-refresh").start();
            return;
        }
    }

    private static String label(String cmd) {
        if (Api.CMD_UNLOCK.equals(cmd)) return "解锁";
        if (Api.CMD_LOCK.equals(cmd)) return "上锁";
        if (Api.CMD_STALL.equals(cmd)) return "熄火";
        if (Api.CMD_SEARCH.equals(cmd)) return "寻车";
        return cmd;
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }
}
