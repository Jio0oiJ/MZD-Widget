package com.example.mzdwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
            // v1.9.14：三步状态机最长 70 秒以上，放 goAsync 里会超广播 ANR 时限，
            // 系统弹「应用无响应」（任务本身仍能跑完）。改由前台服务承载，广播立即结束。
            // 前置清理一并移到 ActionService 内，只有真正开始执行时才清。
            try {
                Intent svc = new Intent(app, ActionService.class);
                svc.setAction(ActionService.ACTION_SMART_START);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc);
                else app.startService(svc);
            } catch (Exception e) {
                // 个别机型拒绝从后台启动前台服务：退化为普通线程，广播仍立即结束，不弹 ANR
                Log.w(TAG, "前台服务启动失败，退化为后台线程：" + e.getMessage());
                new Thread(() -> {
                    try {
                        // v1.9.21：前置清理已移入 smartStartAndUnlock 的 CAS 之后，这里不再重复清。
                        CarActions.smartStartAndUnlock(app,
                                (ok, msg) -> Log.i(TAG, "一键启动结果=" + msg));
                        CarWidget.redraw(app);
                    } catch (Exception ex) {
                        Log.e(TAG, "一键启动异常：" + ex.getMessage());
                    }
                }, "mzd-smart-fallback").start();
            }
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
            final String cmd = intent.getStringExtra(CarWidget.EXTRA_CMD);
            // v1.9.21：按钮指令的 Api.control 超时可达 15s+15s，放在 goAsync 里有 ANR 风险。
            // 改由 ActionService 前台服务承载，广播立即结束；个别机型拒绝后台拉起前台服务时
            // 退化为普通线程（不 goAsync，广播同样立即结束，不弹 ANR）。
            try {
                Intent svc = new Intent(app, ActionService.class);
                svc.setAction(ActionService.ACTION_CMD);
                svc.putExtra(CarWidget.EXTRA_CMD, cmd);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(svc);
                else app.startService(svc);
            } catch (Exception e) {
                Log.w(TAG, "按钮指令前台服务启动失败，退化为后台线程：" + e.getMessage());
                new Thread(() -> {
                    try {
                        CarStateCache.get(app).trigger(app);
                        if (!Prefs.isLoggedIn(app)) {
                            Log.w(TAG, "桌面按钮 " + label(cmd) + " 未执行：请先登录或填入 Token");
                        } else {
                            String reason = "桌面按钮：" + label(cmd);
                            if (Api.CMD_UNLOCK.equals(cmd)) CarActions.unlock(app, reason);
                            else if (Api.CMD_LOCK.equals(cmd)) CarActions.lock(app, reason);
                            else CarActions.send(app, cmd, Api.CMD_START.equals(cmd) ? 15 : -1, reason);
                        }
                        CarWidget.refreshAndUpdate(app);
                    } catch (Exception ex) {
                        Log.e(TAG, "按钮指令异常：" + shortErr(ex));
                    }
                }, "mzd-cmd-fallback").start();
            }
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

    static String label(String cmd) {
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
