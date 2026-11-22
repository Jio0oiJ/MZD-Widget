package com.example.mzdwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 桌面小组件长/同步网络任务的承载服务（一键启动三步状态机 + 手动按钮指令）。
 *
 * 原先在 WidgetActions.goAsync() 里跑：广播的 ANR 时限只有几十秒，
 * 而三步状态机最长可达 70 秒以上、按钮指令底层 Api.control 超时可达 15s+15s，
 * 任务虽能跑完但系统仍弹「应用无响应」。改由前台服务承载后，广播立即结束，
 * 任务期间进程也被保活。
 */
public class ActionService extends Service {
    private static final String TAG = "MZDCtrlLog";
    private static final String CHANNEL_ID = "mzd_action";
    private static final int NOTIF_ID = 1002;
    public static final String ACTION_SMART_START = "com.example.mzdwidget.action.SMART_START";
    /** v1.9.21：手动按钮指令也走本服务，避免 goAsync 承载长网络 ANR */
    public static final String ACTION_CMD = "com.example.mzdwidget.action.CMD";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent == null ? null : intent.getAction();
        final boolean cmd = ACTION_CMD.equals(action);
        // v1.9.21：先校验 action，非法/空 intent 不弹一瞬的前台通知。
        if (!ACTION_SMART_START.equals(action) && !cmd) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        startFgs(cmd ? "MZD 控车指令执行中" : "MZD 一键启动中");

        final Context app = getApplicationContext();
        if (cmd) {
            final String command = intent.getStringExtra(CarWidget.EXTRA_CMD);
            new Thread(() -> {
                try {
                    CarStateCache.get(app).trigger(app);
                    if (!Prefs.isLoggedIn(app)) {
                        Log.w(TAG, "桌面按钮未执行：请先登录或填入 Token");
                    } else {
                        String reason = "桌面按钮：" + WidgetActions.label(command);
                        if (Api.CMD_UNLOCK.equals(command)) CarActions.unlock(app, reason);
                        else if (Api.CMD_LOCK.equals(command)) CarActions.lock(app, reason);
                        else CarActions.send(app, command,
                                Api.CMD_START.equals(command) ? 15 : -1, reason);
                    }
                    CarWidget.refreshAndUpdate(app);
                } catch (Exception e) {
                    String m = e.getMessage();
                    Log.e(TAG, "桌面按钮指令异常：" + (m == null || m.isEmpty() ? e.toString() : m));
                } finally {
                    stopSelf(startId);
                }
            }, "mzd-cmd-fgs").start();
            return START_NOT_STICKY;
        }

        // 一键启动三步状态机
        new Thread(() -> {
            try {
                // 前置清理放在 CAS 之前由 CarActions 内部保证：重复点击时只有抢到 SMART_RUNNING
                // 的那次才真正清理，避免 T2 清掉在途 T1 的状态（见 CarActions.smartStartAndUnlock）。
                CarActions.smartStartAndUnlock(app,
                        (ok, msg) -> Log.i(TAG, "一键启动结果=" + msg));
                CarWidget.redraw(app);
            } catch (Exception e) {
                String m = e.getMessage();
                Log.e(TAG, "一键启动异常：" + (m == null || m.isEmpty() ? e.toString() : m));
            } finally {
                stopSelf(startId);
            }
        }, "mzd-smart-fgs").start();
        return START_NOT_STICKY;
    }

    private void startFgs(String title) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "控车任务", NotificationManager.IMPORTANCE_LOW));
            }
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_ble)
                .setContentTitle(title)
                .setContentText("正在确认车辆状态，请勿强制停止应用")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
