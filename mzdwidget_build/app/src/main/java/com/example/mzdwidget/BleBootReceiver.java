package com.example.mzdwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机/应用更新后，若 BLE 自动控车开关仍打开且已选设备，自动恢复前台扫描服务。 */
public class BleBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;

        Context app = context.getApplicationContext();
        // v1.9.25：BLE 与手环两个开关互相独立，各自按自身开关恢复，不能因一个关就 return 掉另一个。
        if (Prefs.btEnabled(app)) {
            String mac = Prefs.btMac(app);
            if (mac != null && !mac.isEmpty()) {
                try {
                    Intent i = new Intent(app, BleScanService.class);
                    i.setAction(BleScanService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                    else app.startService(i);
                } catch (Exception ignored) { }
            }
        }

        // v1.9.25：手环遥控开关独立，开机按自身开关恢复
        if (Prefs.wearEnabled(app)) {
            try {
                Intent w = new Intent(app, WearRelayService.class);
                w.setAction(WearRelayService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(w);
                else app.startService(w);
            } catch (Exception ignored) { }
        }
    }
}
