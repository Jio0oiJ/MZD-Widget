package com.example.mzdwidget;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍保活（可选，用户需手动到系统设置授权）。
 *
 * 只做两件事，绝不做任何可能被误认为「外挂/自动点击」的行为：
 *  1. 作为系统长期绑定的无障碍服务存在，提升本 App 进程优先级，降低被系统回收的概率；
 *  2. 每 10 分钟检查一次：若 BLE 自动控车仍开启但扫描服务已被回收，补启一次。
 *
 * 不读取窗口内容（canRetrieveWindowContent=false）、不接收事件回调、不模拟点击、
 * 不参与任何控车判定。BLE 扫描本身仍是前台服务 + START_STICKY，这里是额外兜底。
 */
public class MzdAccessibilityService extends AccessibilityService {
    private static final String TAG = "MZDBleDiag";
    private static final long RECHECK_MS = 10 * 60 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable recheck = new Runnable() {
        @Override public void run() {
            try {
                if (Prefs.btEnabled(getApplicationContext())) {
                    Intent i = new Intent(getApplicationContext(), BleScanService.class);
                    i.setAction(BleScanService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                }
            } catch (Exception ignored) {
                // 个别机型后台拉起受限，忽略；前台服务本身就是主保活路径
            }
            main.postDelayed(this, RECHECK_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "无障碍保活已连接（仅保活，不读屏幕/不点击）");
        main.removeCallbacks(recheck);
        main.postDelayed(recheck, RECHECK_MS);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* 不处理任何事件 */ }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        main.removeCallbacks(recheck);
        super.onDestroy();
    }

    /** 检测本服务的无障碍授权是否处于开启状态 */
    public static boolean isEnabled(Context c) {
        try {
            if (Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) return false;
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || list.isEmpty()) return false;
            String self = c.getPackageName() + "/" + MzdAccessibilityService.class.getName();
            TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
            sp.setString(list);
            while (sp.hasNext()) {
                if (self.equalsIgnoreCase(sp.next())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
