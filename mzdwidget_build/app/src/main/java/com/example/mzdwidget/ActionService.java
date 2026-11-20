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
 * 桌面小组件长任务（一键启动三步状态机）的承载服务。
 *
 * 原先在 WidgetActions.goAsync() 里跑：广播的 ANR 时限只有几十秒，
 * 而三步状态机最长可达 70 秒以上，任务虽能跑完但系统仍弹「应用无响应」。
 * 改由前台服务承载后，广播立即结束，任务期间进程也被保活。
 */
public class ActionService extends Service {
    private static final String TAG = "MZDCtrlLog";
    private static final String CHANNEL_ID = "mzd_action";
    private static final int NOTIF_ID = 1002;
    public static final String ACTION_SMART_START = "com.example.mzdwidget.action.SMART_START";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFgs();
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_SMART_START.equals(action)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                // 原 WidgetActions 里的前置清理一并搬过来，保证只有真正执行时才清
                CarWidget.clearSmartUnlockDone(app);
                CarWidget.cancelSmartUnlockFallback(app);
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

    private void startFgs() {
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
                .setContentTitle("MZD 一键启动中")
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
