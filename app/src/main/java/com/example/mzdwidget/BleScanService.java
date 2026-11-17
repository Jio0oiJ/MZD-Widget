package com.example.mzdwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * BLE 自动控车前台服务 —— 【实验分支：系统扫描全权】
 *
 * 与 master(v1.8) 的根本差异：
 * - master：前台服务用 ScanCallback 连续扫描（判 RSSI + 屏幕阈值判离开），
 *   另开一条系统 PendingIntent 扫描只负责靠近唤醒。
 * - 本实验版：**彻底移除连续 ScanCallback 扫描**。靠近(FIRST_MATCH)与离开
 *   (MATCH_LOST)都由系统持有注册的 PendingIntent 扫描驱动，命中时系统解冻进程
 *   投递给 BlePresenceReceiver。本服务退化为三件事：
 *     1) 注册/重建/关闭系统级扫描（含蓝牙关→开恢复、5 分钟防降级重建）；
 *     2) 维持前台通知，实时显示系统扫描判定的在场/信号/命中时间；
 *     3) 屏幕亮灭只用于通知文案，不再参与离开判定。
 *
 * 业务语义与 master 完全一致：10 秒 BLE 内部窗口（解锁/上锁共用，落盘）、
 * 解锁闩锁、上锁幂等都在 BlePresenceReceiver + CarActions 中实现。
 *
 * 为什么值得做这个实验：如果系统扫描在亮屏/熄屏都足够可靠，就能砍掉前台
 * 连续扫描那一套（省电、且完全不受进程冻结影响）。代价是本服务不再掌握
 * 连续的 RSSI 与「连续 N 秒扫不到」的精细判定，离开时机改由系统 MATCH_LOST
 * 迟滞决定——这正是本分支要实测的核心问题。
 */
public class BleScanService extends Service {

    public static final String ACTION_START = "com.example.mzdwidget.BLE_SCAN_START";
    public static final String ACTION_STOP = "com.example.mzdwidget.BLE_SCAN_STOP";

    private static final String CHANNEL_ID = "mzd_ble_scan";
    private static final int NOTIF_ID = 8801;
    private static final long WATCHDOG_MS = 2000L;
    /** 扫描失败后的退避重启，>=10 秒可避开「30 秒内 5 次」注册限制 */
    private static final long FAIL_RETRY_MS = 10000L;
    /** 系统扫描重建周期：对抗系统约 30 分钟后的 opportunistic 降级 */
    private static final long SYS_SCAN_REBUILD_MS = 5 * 60 * 1000L;
    private static final int SYS_SCAN_REQ = 8802;
    /** 通知刷新最小间隔 */
    private static final long NOTIF_MIN_MS = 3000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private NotificationManager nm;
    private PendingIntent sysScanPi = null;
    private volatile boolean sysScanOn = false;
    private volatile long sysScanAt = 0L;
    private volatile long restartAt = 0L;
    private volatile long lastNotifAt = 0L;
    private volatile String lastFilterKey = "";
    private volatile String scanNote = "准备系统扫描";

    private volatile boolean screenOn = true;
    private volatile boolean lastBtOn = true;
    private SharedPreferences sysPrefs;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) screenOn = false;
            else if (Intent.ACTION_SCREEN_ON.equals(a)) screenOn = true;
            postNotify(true);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        adapter = BluetoothAdapter.getDefaultAdapter();
        sysPrefs = getSharedPreferences("mzd_widget_bt", MODE_PRIVATE);
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            screenOn = pm == null || pm.isInteractive();
        } catch (Exception e) { screenOn = true; }
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        try { registerReceiver(screenReceiver, sf); } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSystemScan();
            main.removeCallbacks(watchdogLoop);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();

        if (!Prefs.btEnabled(this) || !hasPermission()) {
            stopSystemScan();
            CarActions.toast(this, "BLE 自动控车未开启或缺少蓝牙权限");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (adapter == null || !adapter.isEnabled()) {
            CarActions.toast(this, "请先打开手机蓝牙，BLE 自动控车才能工作");
            scanNote = "蓝牙未开启";
        }

        String key = filterKey();
        if (sysScanOn && !key.equals(lastFilterKey)) {
            restartSystemScan();   // 设备换了，重建系统扫描过滤器
        } else {
            startSystemScan();
        }
        watchdogSet();
        return START_STICKY; // 被系统回收后自动重建，并重新读取开关/权限
    }

    private void watchdogSet() {
        main.removeCallbacks(watchdogLoop);
        main.postDelayed(watchdogLoop, WATCHDOG_MS);
    }

    /** 看门狗：只管系统扫描的生命体征 + 通知刷新，不做任何离开判定 */
    private final Runnable watchdogLoop = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            boolean btOn = adapter != null && adapter.isEnabled();
            if (btOn != lastBtOn) {
                lastBtOn = btOn;
                restartAt = now + FAIL_RETRY_MS; // 蓝牙重开给一次退避，避免立刻注册撞配额
            }
            if (!btOn) {
                if (sysScanOn) {
                    stopSystemScan();
                    scanNote = "蓝牙已关闭，等待开启";
                }
            } else {
                maybeRebuildSystemScan(now);
            }
            // 补发被 10 秒窗口挡下的系统扫描边沿动作（落盘，跨进程可靠）。
            // 系统扫描只投递一次，没有 master 连续轮询的「下一轮重试」，
            // 所以这里必须兜住，否则靠近不解锁 / 离开不上锁。
            String pending = Prefs.sysPending(getApplicationContext());
            if (pending != null && Prefs.sysActionReady(getApplicationContext(), 10000L)) {
                Prefs.setSysPending(getApplicationContext(), null);
                Prefs.noteSysAction(getApplicationContext());
                final boolean isPresent = "present".equals(pending);
                new Thread(() -> {
                    if (isPresent) {
                        Prefs.setSysPresent(getApplicationContext(), true);
                        CarActions.onBlePresent(getApplicationContext(), (ok, msg) ->
                                CarActions.toast(getApplicationContext(), "系统扫描·靠近(补发)：" + (msg == null ? "" : msg)));
                    } else {
                        Prefs.setSysPresent(getApplicationContext(), false);
                        CarActions.onBleAbsent(getApplicationContext(), (ok, msg) ->
                                CarActions.toast(getApplicationContext(), "系统扫描·离开(补发)：" + (msg == null ? "" : msg)));
                    }
                }, "mzd-sys-ble-deferred").start();
            }
            postNotify(false); // 每 2 秒刷新相对时间，内部按 3 秒节流
            main.postDelayed(this, WATCHDOG_MS);
        }
    };

    private void startAsForeground() {
        Notification n = buildNotification(System.currentTimeMillis());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            try { startForeground(NOTIF_ID, n); } catch (Exception ignored) { }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        if (nm == null) nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "BLE 自动控车", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("系统级扫描：靠近解锁、离开上锁");
        nm.createNotificationChannel(ch);
    }

    /* ==================== 系统级 PendingIntent 扫描（唯一的扫描通道） ==================== */

    private void startSystemScan() {
        if (sysScanOn || !hasPermission()) return;
        if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) return;
        long now = System.currentTimeMillis();
        if (now < restartAt) return;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            Intent br = new Intent(this, BlePresenceReceiver.class);
            br.setAction(BlePresenceReceiver.ACTION_PRESENCE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            sysScanPi = PendingIntent.getBroadcast(this, SYS_SCAN_REQ, br, flags);

            ScanSettings.Builder sb = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    // FIRST_MATCH + MATCH_LOST 必须同时注册：
                    // 系统靠 MATCH_LOST 判定「已离开」，下次靠近才会再次投递 FIRST_MATCH。
                    .setCallbackType(Build.VERSION.SDK_INT >= 26
                            ? (ScanSettings.CALLBACK_TYPE_FIRST_MATCH
                                | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
                            : ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE);
            scanner.startScan(buildFilters(), sb.build(), sysScanPi);
            sysScanOn = true;
            sysScanAt = now;
            lastFilterKey = filterKey();
            scanNote = "系统扫描全权（亮屏/熄屏同一通道）";
            postNotify(true);
        } catch (Exception e) {
            sysScanOn = false;
            scanNote = "系统扫描启动失败：" + e.getClass().getSimpleName();
            restartAt = now + FAIL_RETRY_MS;
            postNotify(true);
        }
    }

    private void stopSystemScan() {
        if (sysScanPi != null && scanner != null) {
            try { scanner.stopScan(sysScanPi); } catch (Exception ignored) { }
        }
        sysScanOn = false;
        sysScanPi = null;
    }

    private void restartSystemScan() {
        stopSystemScan();
        restartAt = 0L; // 换设备/重建是主动行为，不套用失败退避
        startSystemScan();
    }

    private void maybeRebuildSystemScan(long now) {
        if (!sysScanOn) {
            startSystemScan(); // 失败退避由 startSystemScan 内部的 restartAt 把关
        } else if (now - sysScanAt >= SYS_SCAN_REBUILD_MS) {
            restartSystemScan();
        }
    }

    /** 定向过滤：优先 MAC（实验前提：固定 MAC），名称兜底 */
    private List<ScanFilter> buildFilters() {
        List<ScanFilter> fs = new ArrayList<>();
        String mac = Prefs.btMac(getApplicationContext());
        String name = Prefs.btName(getApplicationContext());
        boolean byMac = mac != null && BluetoothAdapter.checkBluetoothAddress(mac);
        boolean byName = name != null && !name.isEmpty() && !"未知设备".equals(name);
        if (byMac) {
            try { fs.add(new ScanFilter.Builder().setDeviceAddress(mac).build()); }
            catch (Exception ignored) { }
        }
        if (byName) {
            try { fs.add(new ScanFilter.Builder().setDeviceName(name).build()); }
            catch (Exception ignored) { }
        }
        if (fs.isEmpty()) fs.add(new ScanFilter.Builder().build());
        return fs;
    }

    private String filterKey() {
        return Prefs.btMac(getApplicationContext()) + "|" + Prefs.btName(getApplicationContext());
    }

    private String filterText() {
        String mac = Prefs.btMac(getApplicationContext());
        String name = Prefs.btName(getApplicationContext());
        boolean byMac = mac != null && BluetoothAdapter.checkBluetoothAddress(mac);
        boolean byName = name != null && !name.isEmpty() && !"未知设备".equals(name);
        if (byMac && byName) return "MAC + 名称";
        if (byMac) return "MAC";
        if (byName) return "名称";
        return "未选设备（宽扫）";
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /* ==================== 动态通知栏 ==================== */

    private void postNotify(boolean force) {
        main.post(() -> updateNotification(force));
    }

    private void updateNotification(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifAt < NOTIF_MIN_MS) return;
        lastNotifAt = now;
        if (nm == null) nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try { nm.notify(NOTIF_ID, buildNotification(now)); } catch (Exception ignored) { }
    }

    private Notification buildNotification(long now) {
        Context c = getApplicationContext();
        String mac = Prefs.btMac(c);
        String label = deviceLabel();
        boolean present = Prefs.sysPresent(c);
        String state = present ? "在附近" : "已离开";
        String title = "BLE · " + label + " · " + state;

        long seenAt = Prefs.lastSysSeenAt(c);
        int rssi = Prefs.lastSysRssi(c);
        String rssiText = seenAt > 0 ? (rssi + " dBm · " + signalWord(rssi)) : "暂无信号数据";
        String seen = seenAt > 0 ? ago(now - seenAt) + "前命中" : "尚未命中";
        String content = rssiText + " · " + seen + " · " + (screenOn ? "亮屏" : "熄屏");

        StringBuilder big = new StringBuilder();
        big.append("设备：").append(label);
        if (mac != null && !mac.isEmpty()) big.append("  (").append(mac).append(")");
        big.append("\n状态：").append(state).append("  ").append(rssiText).append("  ").append(seen);
        big.append("\n模式：系统扫描全权（亮屏/熄屏同一通道）");
        big.append("\n扫描：").append(scanNote).append("  [").append(filterText()).append("]");
        long wake = Prefs.lastSystemWakeAt(c);
        big.append("\n系统唤醒：").append(sysScanOn ? "已注册" : "未注册")
                .append(wake > 0 ? " · 最近 " + ago(now - wake) + "前靠近命中" : "");
        big.append("\n离开判定：系统 MATCH_LOST（系统迟滞，无进程内阈值）");
        big.append("\n车况：").append(carStatusText());
        big.append("\n规则：10 秒窗口（落盘） · 解锁闩锁 ")
                .append(Prefs.bleUnlockLatched(c) ? "已置位" : "未置位");

        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_ble)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(big.toString()))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private String deviceLabel() {
        String name = Prefs.btName(getApplicationContext());
        String mac = Prefs.btMac(getApplicationContext());
        if (name != null && !name.isEmpty() && !"未知设备".equals(name)) return name;
        return (mac == null || mac.isEmpty()) ? "未选择设备" : mac;
    }

    private static String signalWord(int rssi) {
        if (rssi >= -60) return "信号强";
        if (rssi >= -75) return "信号中";
        return "信号弱";
    }

    private static String ago(long ms) {
        if (ms < 1000L) return "刚刚";
        if (ms < 60000L) return (ms / 1000L) + " 秒";
        return (ms / 60000L) + " 分钟";
    }

    private String carStatusText() {
        String s = Prefs.statusPrefs(this).getString("widget_status_json", "");
        if (s == null || s.isEmpty()) return "未知";
        try {
            JSONObject o = new JSONObject(s);
            String engine = !o.has("started") ? "—" : (o.optBoolean("started") ? "已启动" : "未启动");
            String door = !o.has("unlocked") ? "—" : (o.optBoolean("unlocked") ? "车门未锁" : "车门已锁");
            return engine + "  " + door;
        } catch (Exception e) {
            return "未知";
        }
    }

    @Override
    public void onDestroy() {
        // 系统扫描不在 onDestroy 主动取消：进程被回收后由系统代为持有，
        // 命中仍可唤醒 App；只在用户关闭开关（ACTION_STOP）时 stopSystemScan。
        main.removeCallbacks(watchdogLoop);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
