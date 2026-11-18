package com.example.mzdwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
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
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * BLE 扫描自动控车（前台服务，无需配对/连接）。
 *
 * v1.5：选定设备后只扫这一台。
 *   - 用非空 ScanFilter（MAC + 设备名）交给蓝牙控制器做硬件过滤，
 *     回调只剩目标设备，既避开系统对「无过滤器扫描」的息屏挂起，
 *     也不再被密集环境的广播风暴淹没。
 *   - SCAN_MODE_LOW_LATENCY + MATCH_MODE_AGGRESSIVE + MAX_ADVERTISEMENT，
 *     目标的每一次广播都立即上报，配合 2 秒看门狗基本不会漏扫。
 *   - onScanFailed / 静默限流 / 长时间零回调 / 蓝牙重开都能自愈重启，
 *     且重启间隔远大于「30 秒 5 次」配额窗口。
 *
 * 业务逻辑保持不变：
 *   - 扫到目标 → 自动解锁；连续 N 秒（默认 30 秒）扫不到 → 判定离开并上锁。
 *   - 10 秒窗口由本服务内部维护：解锁与上锁共用，任意两条 BLE 自动指令之间
 *     至少间隔 10 秒；桌面按钮与一键启动不受此窗口限制。
 *   - 「解锁闩锁」：本次靠近成功解锁后，直到下次离开发出锁车命令之前，
 *     不再下发解锁指令（逻辑在 CarActions/Prefs 中，本服务不改动）。
 *
 * 前台通知实时反映目标设备状态：在附近/已离开、RSSI、最近可见时间、
 * 扫描状态、最近一次动作与当前车况；每次广播都回调，故通知更新有节流。
 */
public class BleScanService extends Service {

    public static final String ACTION_START = "com.example.mzdwidget.BLE_SCAN_START";
    public static final String ACTION_STOP = "com.example.mzdwidget.BLE_SCAN_STOP";

    private static final String CHANNEL_ID = "mzd_ble_scan";
    private static final String TAG = "MZDBleDiag";
    private static final int NOTIF_ID = 8801;
    private static final long WATCHDOG_MS = 2000L;
    /** 本服务内部窗口：解锁与上锁共用，任意两条 BLE 自动指令之间至少间隔 10 秒 */
    private static final long DEDUP_MS = 10000L;
    /** 通知刷新最小间隔：定向连续扫描每秒可能回调数十次，不能每次都 notify */
    private static final long NOTIF_MIN_MS = 3000L;
    /** 周期性重建扫描：对抗系统把长时间扫描降级为 opportunistic */
    private static final long RESTART_SCAN_MS = 5 * 60 * 1000L;
    /** 连续零回调时的自愈重启（设备真的离开时也会触发，无害且远低于频次配额） */
    private static final long NO_RESULT_RESTART_MS = 60 * 1000L;
    /** 扫描失败后的退避重启，>=10 秒可避开「30 秒内 5 次」注册限制 */
    private static final long FAIL_RETRY_MS = 10000L;
    /**
     * 亮屏后的「重新确认」宽限期。熄屏期间冻结离开判定，亮屏后先给扫描 10 秒
     * 重新发现目标；若目标其实一直在旁边，present 不会被翻转，也就不会误发解锁。
     */
    private static final long SCREEN_ON_GRACE_MS = 10000L;
    /**
     * 熄屏时离开判定固定放宽到 90 秒（不随用户设置的阈值缩放）。
     * 熄屏/Doze 会压低 BLE 扫描占空比，几十秒无回调属正常漏扫，
     * 不足以证明目标已离开；亮屏后立即恢复用户设置的阈值。
     */
    private static final long AWAY_SCREEN_OFF_MS = 90 * 1000L;
    /** 在场状态持久化文件：进程被 MIUI 杀掉重建后，不把「恢复扫描」当成重新靠近 */
    private static final String PRESENCE_PREFS = "mzd_ble_presence";

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 最近一条 BLE 自动指令（解锁或上锁，不区分）的发出时间 */
    private final AtomicLong lastBleAction = new AtomicLong(0);

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private NotificationManager nm;
    private volatile boolean scanning = false;
    private volatile boolean present = false;   // 当前是否在附近（扫到过目标）
    private volatile long lastSeen = 0L;
    private volatile long lastResultAt = 0L;    // 最近一次扫描回调（用于自愈判定）
    private volatile long scanStartedAt = 0L;
    private volatile long restartAt = 0L;       // 失败退避：早于此时间不重启
    private volatile long lastNotifAt = 0L;
    private volatile String activeFilterKey = "";

    /** 屏幕状态：熄屏时冻结「离开」判定，防止 BLE 漏扫被误认成设备离开 */
    private volatile boolean screenOn = true;
    private volatile long screenOnAt = 0L;
    /** 蓝牙关→开等同于一次「重新发现」，同样需要宽限期，否则旧 lastSeen 会立刻误判离开 */
    private volatile boolean lastBtOn = true;
    private SharedPreferences presencePrefs;

    // ===== 通知栏状态（全部在主线程更新） =====
    private volatile int lastRssi = 0;
    private volatile boolean hasRssi = false;
    private volatile String scanNote = "准备扫描";
    private volatile String filterNote = "";
    private volatile String lastActionText = "尚未触发";
    private volatile long lastActionAt = 0L;

    // ===== 诊断计数（内存实时值，通知栏显示；关键节点落盘到 Prefs） =====
    // 用 AtomicLong：扫描回调/onScanFailed/看门狗/动作结果分属不同线程，
    // volatile long++ 非原子会丢失计数（v1.9.4 修复）。
    private final AtomicLong diagCb = new AtomicLong();     // 扫描回调总次数
    private final AtomicLong diagHit = new AtomicLong();    // 命中目标设备次数（触发动作判定的有效回调）
    private final AtomicLong diagWeak = new AtomicLong();   // 命中目标但 RSSI 未达阈值（信号太弱，不算靠近）
    private final AtomicLong diagUnlock = new AtomicLong(); // 已发出的解锁动作次数
    private final AtomicLong diagLock = new AtomicLong();   // 已发出的上锁动作次数
    private final AtomicLong diagFail = new AtomicLong();   // 动作失败次数
    private volatile String diagLastEvent = "服务启动";
    private volatile long diagLastPersist = 0L; // 上次落盘时间，避免高频写盘

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result == null) return;
            long now = System.currentTimeMillis();
            lastResultAt = now;
            diagCb.incrementAndGet();
            BluetoothDevice dev = result.getDevice();
            if (dev == null || !isTarget(result)) return;
            diagHit.incrementAndGet();
            int rssi = result.getRssi();
            lastRssi = rssi;
            hasRssi = true;
            int threshold = Prefs.btRssiThreshold(getApplicationContext());
            // RSSI 是负数，越接近 0 越强：rssi >= 阈值 才算「靠得够近」。
            // 127 是 Android 的无效 RSSI 哨兵值，必须排除，否则走远时反而恒达标。
            boolean strongEnough = rssi != 127 && rssi >= threshold;
            if (!strongEnough) {
                // 信号未达标：不计为「可见」，lastSeen 不刷新 → 看门狗据此判定离开并上锁。
                // 否则人走远后弱信号仍不断刷新 lastSeen，永远无法判离开、车不会锁。
                diagWeak.incrementAndGet();
                if (now - lastNotifAt >= NOTIF_MIN_MS) postNotify(false);
                return;
            }
            // 信号达标才算真正「靠近」：刷新可见时间 + 触发解锁
            lastSeen = now;
            if (!present) {
                if (tryClaimActionWindow(now)) {
                    setPresent(true);
                    diagUnlock.incrementAndGet();
                    diagLastEvent = "RSSI达标(" + rssi + "≥" + threshold + ")→解锁";
                    Log.i(TAG, "命中目标且信号达标：RSSI=" + rssi + " dBm ≥ 阈值 " + threshold
                            + " dBm，触发解锁；回调累计=" + diagCb.get() + "，弱信号次数=" + diagWeak.get());
                    persistDiag(); // 解锁指令发出是关键时刻，立即落盘
                    new Thread(() -> CarActions.onBlePresent(getApplicationContext(),
                            (ok, msg) -> {
                                // ok=false = 动作失败或车况不可用待重试：复位 present，让后续扫描重试。
                                // 否则 present 恒为 true，设备一直在旁边时看门狗只判离开、不重判靠近，
                                // 本次跳过后将永不再尝试解锁（v1.9.3 修复）。
                                // onBlePresent 的确定性跳过（已解锁/发动机启动）仍返回 ok=true，不误复位。
                                if (!ok) setPresent(false);
                                onActionResult(ok, msg);
                            }), "mzd-ble-present").start();
                }
                // 10 秒窗口未过：保持 present=false，后续扫描结果会继续重试，避免动作被吞掉
            }
            // 定向连续扫描下每个广播包都会回调，先按节流判断再投递，避免主线程堆积消息
            if (now - lastNotifAt >= NOTIF_MIN_MS) postNotify(false);
        }

        @Override
        public void onScanFailed(int errorCode) {
            // startScan() 返回成功但注册失败是异步的；必须把 scanning 置 false，
            // 否则看门狗永远认为「扫描中」，服务会静默失效。
            scanning = false;
            scanNote = "扫描失败 code=" + errorCode + "，等待重试";
            restartAt = System.currentTimeMillis() + FAIL_RETRY_MS;
            diagFail.incrementAndGet();
            diagLastEvent = "扫描失败 code=" + errorCode;
            Log.w(TAG, diagLastEvent + "，10 秒后重试");
            persistDiag();
            CarActions.logMsg("BLE 扫描失败（code=" + errorCode + "），将自动重试");
            postNotify(true);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        adapter = BluetoothAdapter.getDefaultAdapter();
        presencePrefs = getSharedPreferences(PRESENCE_PREFS, MODE_PRIVATE);
        // 进程重建时恢复「上次是否靠近」，lastSeen 未知则按当前时间算，
        // 避免刚恢复就被判为离开，或亮屏后扫到目标误当成重新靠近。
        present = presencePrefs.getBoolean("present", false);
        lastSeen = present ? System.currentTimeMillis() : 0L;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            screenOn = pm == null || pm.isInteractive();
        } catch (Exception e) { screenOn = true; }
        screenOnAt = System.currentTimeMillis();
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        try { registerReceiver(screenReceiver, sf); } catch (Exception ignored) { }
    }

    /** 亮/熄屏广播：熄屏后通话、传感器等会让系统把 BLE 扫描降到极低占空比 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            long now = System.currentTimeMillis();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOn = false;
            } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                screenOn = true;
                screenOnAt = now;
                // 亮屏时把扫描自愈计时重置，给重新发现留足窗口
                lastResultAt = now;
            }
            postNotify(true);
        }
    };

    /** 写盘只在状态翻转时发生，不在每次广播回调时写 */
    private void setPresent(boolean value) {
        present = value;
        if (presencePrefs != null) {
            try { presencePrefs.edit().putBoolean("present", value).apply(); }
            catch (Exception ignored) { }
        }
    }

    /**
     * 原子抢占 10 秒指令窗口：解锁（扫描回调线程）与上锁（看门狗主线程）并发时，
     * 用 CAS 保证只有一个能占窗，杜绝 check-then-set 竞态导致两条指令间隔不足 10 秒。
     * 抢到返回 true 并把窗口起点推进到 now；窗口未过返回 false。
     */
    private boolean tryClaimActionWindow(long now) {
        while (true) {
            long cur = lastBleAction.get();
            if (now - cur < DEDUP_MS) return false;
            if (lastBleAction.compareAndSet(cur, now)) return true;
            // 被其它线程抢先更新，重试一次判断
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopScanInternal();
            setPresent(false);
            main.removeCallbacks(watchdogLoop);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();

        if (!Prefs.btEnabled(this) || !hasPermission()) {
            CarActions.logMsg("BLE 自动控车未开启或缺少蓝牙权限");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!isBtOn()) {
            CarActions.logMsg("请先打开手机蓝牙，BLE 自动控车才能工作");
            scanNote = "蓝牙未开启";
        }
        // 设备被重新选择后服务可能被复用：MAC/名称变了才重置在场状态。
        // 进程被杀重建、蓝牙关→开、服务重启都不能重置 present，
        // 否则亮屏后第一次扫到目标会被误判成「重新靠近」而重复解锁。
        String key = filterKey();
        if (scanning && !key.equals(activeFilterKey)) {
            restartScan(true);
        } else {
            startScanInternal(false);
        }
        watchdogSet();
        return START_STICKY; // 被系统回收后自动重建，并重新读取开关/权限
    }

    /** 看门狗入口：无论 scanning 为何都要持续跑，才能处理蓝牙关闭/失败恢复 */
    private void watchdogSet() {
        main.removeCallbacks(watchdogLoop);
        main.postDelayed(watchdogLoop, WATCHDOG_MS);
    }

    private final Runnable watchdogLoop = new Runnable() {
        @Override
        public void run() {
            // v1.9.5：整个循环体包 try/finally，保证末尾的 postDelayed 必定执行。
            // 任何未捕获异常（权限被撤销、ROM 定制 API 抛异常等）都不能杀死唯一驱动循环，
            // 否则判离开/上锁、扫描自愈、诊断落盘、通知刷新全部静默停摆且不自愈。
            try {
                watchdogTick();
            } catch (Throwable t) {
                Log.w(TAG, "看门狗本轮异常（已跳过，循环继续）：" + t);
            } finally {
                main.postDelayed(this, WATCHDOG_MS);
            }
        }
    };

    /** 看门狗单轮逻辑（从原 run() 抽出，便于 try/finally 包裹自调度） */
    private void watchdogTick() {
            long now = System.currentTimeMillis();
            boolean btOn = isBtOn();
            if (btOn != lastBtOn) {
                lastBtOn = btOn;
                if (btOn) {
                    lastSeen = now;
                    screenOnAt = now; // 复用亮屏宽限逻辑，给扫描重新发现目标的时间
                }
            }
            if (!btOn) {
                if (scanning) {
                    stopScanInternal();
                    scanNote = "蓝牙已关闭，等待开启";
                }
            } else if (!scanning) {
                if (now >= restartAt && hasPermission()) {
                    stopScanInternal();   // 清掉可能残留的旧注册
                    startScanInternal(true);
                }
            } else {
                if (now - scanStartedAt >= RESTART_SCAN_MS
                        || now - lastResultAt >= NO_RESULT_RESTART_MS) {
                    restartScan(false);
                }
            }
            // 定期快照：即使长时间只回调不触发动作，主界面也能看到计数在增长
            if (diagCb.get() > 0) persistDiag();
            if (present && lastSeen > 0 && btOn) {
                long awayMs = (long) Prefs.btAwaySec(getApplicationContext()) * 1000L;
                long silent = now - lastSeen;
                boolean gone;
                if (screenOn) {
                    // 亮屏后先给 SCREEN_ON_GRACE_MS 重新发现目标；
                    // 设备其实一直在旁边时 present 不会被翻转，也就不会误发解锁。
                    gone = now - screenOnAt >= SCREEN_ON_GRACE_MS && silent >= awayMs;
                } else {
                    // 熄屏期间 Doze/省电策略会压低扫描占空比：改用固定 90 秒阈值，
                    // 从最后一次真正扫到目标开始算，避开 BLE 扫描间隔造成的漏扫误判。
                    gone = now - lastSeen >= AWAY_SCREEN_OFF_MS;
                }
                if (gone && tryClaimActionWindow(now)) {
                    setPresent(false);
                    diagLock.incrementAndGet();
                    diagLastEvent = "判定离开(" + (screenOn ? "亮屏" : "熄屏") + ")→上锁";
                    Log.i(TAG, "判定设备离开（" + (screenOn ? "亮屏" : "熄屏")
                            + "，静默 " + (now - lastSeen) / 1000L + " 秒），触发上锁");
                    persistDiag();
                    new Thread(() -> CarActions.onBleAbsent(getApplicationContext(),
                            (ok, msg) -> onActionResult(ok, msg)), "mzd-ble-absent").start();
                }
            }
            postNotify(false);
    }

    private void startAsForeground() {
        Notification n = buildNotification(System.currentTimeMillis());
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // 某些定制 ROM 对前台服务类型校验严格，退回普通前台通知
            try { startForeground(NOTIF_ID, n); } catch (Exception ignored) { }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        if (nm == null) nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "BLE 自动控车", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("只扫描已选设备，通知栏显示实时连接状态");
        nm.createNotificationChannel(ch);
    }

    /** 定向过滤：优先 MAC，设备名兜底（应对随机 MAC / 广播带名称的设备） */
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
        // 极端情况下（没选设备或名称无效）用一个「无约束但非空」的 filter：
        // 非空 filter 可避免系统把扫描判定为「无过滤器扫描」而息屏挂起。
        if (fs.isEmpty()) fs.add(new ScanFilter.Builder().build());
        return fs;
    }

    private String filterKey() {
        return Prefs.btMac(getApplicationContext()) + "|" + Prefs.btName(getApplicationContext());
    }

    private String describeFilters() {
        String mac = Prefs.btMac(getApplicationContext());
        String name = Prefs.btName(getApplicationContext());
        boolean byMac = mac != null && BluetoothAdapter.checkBluetoothAddress(mac);
        boolean byName = name != null && !name.isEmpty() && !"未知设备".equals(name);
        if (byMac && byName) return "MAC + 名称";
        if (byMac) return "MAC";
        if (byName) return "名称";
        return "未选设备（宽扫）";
    }

    private void startScanInternal(boolean resetState) {
        if (scanning) return;
        if (!hasPermission()) return;
        if (adapter == null) adapter = BluetoothAdapter.getDefaultAdapter();
        if (!isBtOn()) return;
        try {
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            List<ScanFilter> filters = buildFilters();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .build();
            scanner.startScan(filters, settings, scanCb);
            scanning = true;
            long now = System.currentTimeMillis();
            scanStartedAt = now;
            lastResultAt = now;
            activeFilterKey = filterKey();
            filterNote = describeFilters();
            scanNote = "定向连续扫描中";
            if (resetState) {
                setPresent(false); // 同步写 PRESENCE_PREFS，避免进程重建后读回旧 true 误判在场
                lastSeen = 0L;
                hasRssi = false;
            }
            postNotify(true);
        } catch (Exception e) {
            scanning = false;
            scanNote = "启动扫描失败：" + e.getClass().getSimpleName();
            restartAt = System.currentTimeMillis() + FAIL_RETRY_MS;
            postNotify(true);
        }
    }

    private void restartScan(boolean resetState) {
        stopScanInternal();
        startScanInternal(resetState);
    }

    private void stopScanInternal() {
        if (scanner != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        }
        scanning = false;
    }

    /**
     * 硬件过滤后仍做一次软件确认，避免同名设备误触发。
     * 名称优先取广播包内的 Local Name（与 setDeviceName 过滤器同一来源）：
     * dev.getName() 读的是系统缓存，可能为 null 或与广播名不一致，
     * 会导致「名称过滤器已命中，却被软件二次确认丢掉」的漏扫。
     */
    private boolean isTarget(ScanResult result) {
        BluetoothDevice dev = result.getDevice();
        if (dev == null) return false;
        String targetMac = Prefs.btMac(getApplicationContext());
        String mac = dev.getAddress();
        if (targetMac != null && !targetMac.isEmpty() && mac != null
                && mac.equalsIgnoreCase(targetMac)) return true;

        String targetName = Prefs.btName(getApplicationContext());
        if (targetName == null || targetName.isEmpty() || "未知设备".equals(targetName)) return false;
        String name = null;
        try {
            if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) {
            try { name = dev.getName(); } catch (SecurityException e) { return false; }
        }
        return name != null && name.equalsIgnoreCase(targetName);
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            // v1.9.5：SCAN 与 CONNECT 必须同时授予。MainActivity.hasBtPermission() 已同时校验两者，
            // 这里若只查 SCAN，会在「给了 SCAN、拒了 CONNECT」的部分授权状态下误判为有权限，
            // 随后 isEnabled()/getBluetoothLeScanner() 抛 SecurityException 杀死看门狗。
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * v1.9.5：读取蓝牙开关状态的唯一入口。API 31+ 的 isEnabled() 受 BLUETOOTH_CONNECT 保护，
     * 部分授权/运行中权限被撤销时会抛 SecurityException——绝不能让它从看门狗 run() 逃逸，
     * 否则 :末尾的 postDelayed 不执行，整个驱动循环永久停摆且不自愈。
     */
    private boolean isBtOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException se) {
            Log.w(TAG, "读取蓝牙状态被拒绝（缺少 BLUETOOTH_CONNECT）：" + se.getMessage());
            return false;
        }
    }

    /* ==================== 动态通知栏 ==================== */

    private void onActionResult(boolean ok, String msg) {
        lastActionText = (ok ? "" : "[失败] ") + (msg == null ? "" : msg);
        lastActionAt = System.currentTimeMillis();
        if (!ok) {
            diagFail.incrementAndGet();
            diagLastEvent = "动作失败：" + (msg == null ? "未知原因" : msg);
            Log.w(TAG, diagLastEvent);
            persistDiag();
        } else {
            Log.i(TAG, "动作结果：" + (msg == null ? "" : msg));
        }
        postNotify(true);
    }

    /** 关键节点落盘诊断快照；高频扫描回调不逐次写盘，至少间隔 5 秒 */
    private void persistDiag() {
        long now = System.currentTimeMillis();
        if (now - diagLastPersist < 5000L) return;
        diagLastPersist = now;
        Prefs.noteBleDiag(getApplicationContext(), diagCb.get(), diagHit.get(), diagUnlock.get(),
                diagLock.get(), diagFail.get(), diagLastEvent);
    }

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
        String state = present ? "在附近" : "已离开";
        String title = "BLE · " + label + " · " + state;
        int threshold = Prefs.btRssiThreshold(c);
        boolean strongEnough = hasRssi && lastRssi != 127 && lastRssi >= threshold;
        String rssi = hasRssi ? (lastRssi + " dBm · " + signalWord(lastRssi)
                + (strongEnough ? " · 达标" : " · 未达标")) : "暂无信号数据";
        String seen = lastSeen > 0 ? ago(now - lastSeen) + "前可见" : "尚未发现";
        String content = rssi + " · " + seen + (screenOn ? "" : " · 熄屏暂停判定");

        StringBuilder big = new StringBuilder();
        big.append("设备：").append(label);
        if (mac != null && !mac.isEmpty()) big.append("  (").append(mac).append(")");
        big.append("\n状态：").append(state).append("  ").append(rssi).append("  ").append(seen);
        big.append("\n扫描：").append(scanNote);
        if (filterNote != null && !filterNote.isEmpty()) big.append("  [").append(filterNote).append("]");
        big.append("\n判定：").append(guardText(now));
        big.append("\n最近动作：").append(lastActionText);
        if (lastActionAt > 0) big.append("  (").append(ago(now - lastActionAt)).append("前)");
        big.append("\n车况：").append(carStatusText());
        big.append("\n规则：RSSI≥").append(threshold).append(" dBm 才解锁 · 10 秒窗口 · 解锁闩锁 ")
                .append(Prefs.bleUnlockLatched(c) ? "已置位" : "未置位");
        big.append("\n诊断：回调 ").append(diagCb.get()).append(" · 命中 ").append(diagHit.get())
                .append(" · 弱信号 ").append(diagWeak.get())
                .append(" · 解锁 ").append(diagUnlock.get()).append(" · 上锁 ").append(diagLock.get())
                .append(" · 失败 ").append(diagFail.get());
        big.append("\n最近事件：").append(diagLastEvent);

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

    /** 通知栏里直接说明当前离开判定为何被暂停/宽限，方便排查误锁误解锁 */
    private String guardText(long now) {
        long awayMs = (long) Prefs.btAwaySec(getApplicationContext()) * 1000L;
        if (lastSeen <= 0) return "尚未发现目标 · 暂不判定离开";
        if (!screenOn) {
            long left = AWAY_SCREEN_OFF_MS - (now - lastSeen);
            return "熄屏中 · 阈值放宽至 90 秒" + (left > 0
                    ? "（剩 " + (left / 1000L) + " 秒）" : "（即将判离开）");
        }
        long sinceOn = now - screenOnAt;
        if (sinceOn < SCREEN_ON_GRACE_MS) {
            return "亮屏重新确认中 · " + ((SCREEN_ON_GRACE_MS - sinceOn) / 1000L + 1) + " 秒后恢复判定";
        }
        return "正常 · 离开阈值 " + (awayMs / 1000L) + " 秒";
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

    /** 读桌面组件缓存的车况（由 5 分钟周期刷新 / 快刷写入） */
    private String carStatusText() {
        // v1.9.6：先判错误标记——查询失败后桌面已正确显示「获取失败」，但本方法若只读 JSON
        // 会把失败前遗留的旧车况当实时状态显示在常驻通知里，误导用户。与桌面口径保持一致。
        String err = Prefs.statusPrefs(this).getString("widget_status_err", "");
        if (err != null && !err.isEmpty()) return "车况获取失败";
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
        stopScanInternal();
        main.removeCallbacks(watchdogLoop);
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
