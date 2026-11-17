package com.example.mzdwidget;

import android.annotation.SuppressLint;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 系统级 BLE 唤醒器 —— 【实验分支：系统扫描全权】
 *
 * 与 master(v1.8) 的关键差异：
 * - master 把前台连续扫描作为主力（判 RSSI/离开），系统扫描只负责「靠近唤醒」；
 * - 本实验版**取消前台连续扫描**，靠近与离开都交给系统扫描：
 *     FIRST_MATCH → 解锁；MATCH_LOST → 上锁。
 *   目的：实测「系统扫描是否可靠到能独自承担亮屏+熄屏的全部 BLE 控车」。
 *
 * 扫描注册由系统蓝牙栈持有（startScan(..., PendingIntent)），命中/丢失时系统
 * 投递广播并解冻进程，因此不依赖本 App 进程是否存活/被 MIUI 冻结。
 *
 * 可靠性的三道护栏（与 master 语义一致，只是改为落盘）：
 * - 10 秒窗口：解锁/上锁共用一个落盘时间戳，任意两条自动指令间隔 >= 10 秒；
 * - 解锁闩锁：CarActions.onBlePresent 内部闩锁，本次靠近只解锁一次，重复 FIRST_MATCH 无副作用；
 * - 上锁幂等：CarActions.onBleAbsent 内部先查车况，已启动保持现状、已锁跳过。
 *
 * 已知风险（实验要验证的点）：MATCH_LOST 由系统自身的迟滞算法决定，
 * 可能比「实际离开」延迟或在某些 ROM 上误报；通知栏会如实显示命中/丢失时间。
 */
public class BlePresenceReceiver extends BroadcastReceiver {

    public static final String ACTION_PRESENCE = "com.example.mzdwidget.BLE_PRESENCE";
    /** 系统扫描自动指令的 10 秒窗口，与 master 的 BleScanService.DEDUP_MS 一致 */
    private static final long DEDUP_MS = 10000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_PRESENCE.equals(intent.getAction())) return;
        if (!Prefs.btEnabled(context) || !Prefs.isLoggedIn(context)) return;

        int callbackType = intent.getIntExtra(BluetoothLeScanner.EXTRA_CALLBACK_TYPE, -1);
        ScanResult sr = extractResult(intent);
        if (sr == null) return;
        if (!isTarget(context, sr)) return; // 硬件已过滤，软件再确认一次防同名误触发

        final Context c = context.getApplicationContext();
        boolean firstMatch = Build.VERSION.SDK_INT < 26
                || callbackType == ScanSettings.CALLBACK_TYPE_FIRST_MATCH;
        boolean matchLost = Build.VERSION.SDK_INT >= 26
                && callbackType == ScanSettings.CALLBACK_TYPE_MATCH_LOST;
        if (!firstMatch && !matchLost) return;

        // ===== 靠近：清零「疑似离开」确认计数，走正常解锁 =====
        if (firstMatch) {
            Prefs.clearSysLostConfirm(c); // 设备又出现了 → 之前的 MATCH_LOST 是误报
            Prefs.noteSystemWake(c);
            Prefs.noteSysRssi(c, sr.getRssi());

            // 10 秒窗口（落盘）：撞窗口时挂起，交看门狗补发，绝不丢动作
            if (!Prefs.sysActionReady(c, DEDUP_MS)) {
                Prefs.setSysPending(c, "present");
                return;
            }
            Prefs.setSysPending(c, null);
            Prefs.noteSysAction(c);
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    Prefs.setSysPresent(c, true);
                    CarActions.onBlePresent(c, (ok, msg) ->
                            CarActions.toast(c, "系统扫描·靠近：" + (msg == null ? "" : msg)));
                } finally {
                    pr.finish();
                }
            }, "mzd-sys-ble").start();
            return;
        }

        // ===== 离开：MATCH_LOST 是边沿事件，只投递一次，不能直接上锁 =====
        // 记一次「疑似离开」。真正的上锁交给看门狗：每个确认窗口复核一次，
        // 只要期间设备再次 FIRST_MATCH（说明是密集环境漏扫误报）就清零取消；
        // 连续 LOST_CONFIRM_N 个窗口都没再发现设备，才判定真离开并上锁。
        // 这样「接受延迟锁车，但不能接受误锁车」。
        int n = Prefs.markSysLost(c);
        CarActions.toast(c, "系统扫描·疑似离开（" + n + "/"
                + BleScanService.LOST_CONFIRM_N + " 次确认，暂不上锁）");
        // 不占 10 秒窗口、不发上锁指令；看门狗每 2 秒会刷新通知显示确认进度
    }

    @SuppressLint("WrongConstant")
    private static ScanResult extractResult(Intent intent) {
        try {
            // SDK 34 里常量名是 EXTRA_LIST_SCAN_RESULT，值是 ArrayList<ScanResult>
            java.util.ArrayList<ScanResult> list =
                    intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT);
            return (list == null || list.isEmpty()) ? null : list.get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 与 master 同一口径：固定 MAC 为主，广播名兜底 */
    private static boolean isTarget(Context c, ScanResult sr) {
        String targetMac = Prefs.btMac(c);
        String mac = sr.getDevice() == null ? null : sr.getDevice().getAddress();
        if (targetMac != null && !targetMac.isEmpty() && mac != null
                && mac.equalsIgnoreCase(targetMac)) return true;

        String targetName = Prefs.btName(c);
        if (targetName == null || targetName.isEmpty() || "未知设备".equals(targetName)) return false;
        String name = null;
        try {
            if (sr.getScanRecord() != null) name = sr.getScanRecord().getDeviceName();
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) {
            try { name = sr.getDevice().getName(); }
            catch (Exception e) { return false; }
        }
        return name != null && name.equalsIgnoreCase(targetName);
    }
}
