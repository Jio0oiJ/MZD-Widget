package com.example.mzdwidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 三种方式获取凭证：Root 一键读取 / 账号密码 / 手动 Token + 车况。 */
public class MainActivity extends Activity {

    private static final String TAG = "MZDMain";
    private static final int REQ_BT_PERMISSION = 4401;
    private static final long BLE_PICK_MS = 12000L; // 选设备时扫描时长

    /** 常用预设色，点击即写入对应颜色输入框 */
    private static final int[] PRESET_COLORS = {
            0xFF8A1A2B, 0xFFC41230, 0xFF212121, 0xFFFFFFFF,
            0xFF1565C0, 0xFF1B8A5A, 0xFFE8871A, 0xFF607080
    };

    private EditText etPhone, etPass, etToken, etCarid;
    private TextView tvStatus, tvBtDevice, tvBleDiag, tvTitleBrand, tvPreviewBrand, tvPreviewStatus;
    private EditText etBrandText, etBrandColor, etStatusNormalColor, etStatusAlertColor, etBtAway, etBtRssi;
    private Button btnLogin, btnSaveToken, btnRefresh, btnLogout, btnRootImport, btnPickBt, btnSaveAppearance, btnViewLog;
    private CheckBox cbBtEnable;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // 全盘审核修复：凭证操作代次。登录/Root 导入是异步的，期间若用户点「清除凭证」，
    // 旧线程返回仍会写回 token/carid 导致「已登出但凭证复活」。每次发起凭证写入或清除都递增，
    // 回调时比对代次，不一致即丢弃本次结果。
    private final java.util.concurrent.atomic.AtomicInteger credOpGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 权限未授予时把开关回弹到 false，避免 setChecked(false) 递归触发监听器误停服务 */
    private boolean suppressBtToggle = false;

    private BluetoothLeScanner pickScanner;
    private final ScanCallback pickCb = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            BluetoothDevice d = result.getDevice();
            String mac = d.getAddress();
            if (mac == null) return;
            // 优先取广播包内的设备名：dev.getName() 读的是缓存，很多 BLE 设备为空，
            // 而后台定向扫描的名称过滤依赖广播里确实带这个名字。
            String name = "";
            try {
                if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            } catch (Exception ignored) { }
            if (name == null || name.isEmpty()) {
                try { name = d.getName(); } catch (SecurityException ignored) { }
            }
            final String n = (name == null ? "" : name);
            String old = picked.get(mac);
            if (old == null || !n.isEmpty()) picked.put(mac, n); // 有效名称不被后续空包覆盖
        }
        @Override public void onScanFailed(int errorCode) {
            ui.post(() -> logMsg("BLE 扫描失败（code=" + errorCode + "）"));
        }
    };
    // v1.9.5：BLE 扫描回调在 binder 线程写、主线程读/clear，改用并发容器杜绝
    // ConcurrentModificationException / 可见性问题（原 LinkedHashMap 跨线程无同步）。
    private final ConcurrentHashMap<String, String> picked = new ConcurrentHashMap<>();
    private ProgressDialog pickDialog;

    /** 全盘审核修复：选设备结果回调抽成字段，重复点击时可 removeCallbacks 取消旧任务 */
    private final Runnable pickResultRunnable = new Runnable() {
        @Override public void run() {
            // 12 秒内用户可能已返回/旋转屏幕：Activity 已销毁时绝不能弹 Dialog，
            // 否则以已销毁 Activity 为 Context show() 会抛 BadTokenException 崩溃。
            if (isFinishing() || isDestroyed()) { btnPickBt.setEnabled(true); return; }
            stopPickScan();
            final List<String> macs = new ArrayList<>(picked.keySet());
            if (macs.isEmpty()) {
                logMsg("附近没扫到 BLE 设备，让设备保持广播/亮屏后再试");
                btnPickBt.setEnabled(true);
                return;
            }
            CharSequence[] items = new CharSequence[macs.size()];
            for (int i = 0; i < macs.size(); i++) {
                String mac = macs.get(i);
                String n = picked.get(mac);
                items[i] = (n == null || n.isEmpty() ? "未知设备" : n) + "\n" + mac;
            }
            new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("选择触发设备（BLE 扫描，无需连接）")
                    .setItems(items, (DialogInterface dialog, int which) -> {
                        String mac = macs.get(which);
                        String n = picked.get(mac);
                        Prefs.saveBtDevice(MainActivity.this, mac, n == null ? "" : n);
                        refreshBtDeviceText();
                        logMsg("已选择：" + (n == null || n.isEmpty() ? mac : n));
                        if (Prefs.btEnabled(MainActivity.this)) {
                            Prefs.setBleUnlockLatched(MainActivity.this, false);
                            stopBleScanService();
                            startBleScanService();
                        }
                        btnPickBt.setEnabled(true);
                    })
                    .setOnCancelListener(d -> btnPickBt.setEnabled(true))
                    .setNegativeButton("取消", (d, w) -> btnPickBt.setEnabled(true))
                    .show();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 已登录用户：每次进主界面刷新一次加密凭证文件（供静默解锁/上锁 APK 读取）
        if (Prefs.isLoggedIn(this)) {
            new Thread(() -> CredFile.export(getApplicationContext(),
                    Prefs.token(getApplicationContext()), Prefs.carid(getApplicationContext())),
                    "mzd-export-cred").start();
        }

        // 10 秒快刷由系统精确闹钟驱动；若「闹钟和提醒」被系统关闭则提示用户开启
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(android.content.Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                logMsg("系统未允许「闹钟和提醒」，自动快刷可能被延迟，请在系统设置里开启");
            }
        }

        etPhone = findViewById(R.id.et_phone);
        etPass = findViewById(R.id.et_pass);
        etToken = findViewById(R.id.et_token);
        etCarid = findViewById(R.id.et_carid);
        tvStatus = findViewById(R.id.tv_status);
        TextView tvVersion = findViewById(R.id.tv_version);
        try {
            tvVersion.setText("v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) { }
        btnLogin = findViewById(R.id.btn_login);
        btnSaveToken = findViewById(R.id.btn_save_token);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnLogout = findViewById(R.id.btn_logout);
        btnRootImport = findViewById(R.id.btn_root_import);
        cbBtEnable = findViewById(R.id.cb_bt_enable);
        btnPickBt = findViewById(R.id.btn_pick_bt);
        tvBtDevice = findViewById(R.id.tv_bt_device);
        tvBleDiag = findViewById(R.id.tv_ble_diag);
        refreshBleDiagText();
        tvBleDiag.setOnLongClickListener(v -> {
            Prefs.clearBleDiag(this);
            refreshBleDiagText();
            logMsg("BLE 诊断计数已清空");
            return true;
        });

        tvTitleBrand = findViewById(R.id.tv_title_brand);
        tvPreviewBrand = findViewById(R.id.tv_preview_brand);
        tvPreviewStatus = findViewById(R.id.tv_preview_status);
        etBrandText = findViewById(R.id.et_brand_text);
        etBrandColor = findViewById(R.id.et_brand_color);
        etStatusNormalColor = findViewById(R.id.et_status_normal_color);
        etStatusAlertColor = findViewById(R.id.et_status_alert_color);
        btnSaveAppearance = findViewById(R.id.btn_save_appearance);
        btnViewLog = findViewById(R.id.btn_view_log);

        setupAppearance();

        etPhone.setText(Prefs.phone(this));
        // token 用星号占位显示，不回显明文；真实 token 仅保存在本地 Prefs
        etToken.setText(Prefs.token(this).isEmpty() ? "" : "****************");
        etCarid.setText(Prefs.carid(this));
        refreshStatusText();

        btnRootImport.setOnClickListener(v -> doRootImport());
        btnLogin.setOnClickListener(v -> doLogin());
        btnSaveToken.setOnClickListener(v -> doSaveToken());
        btnRefresh.setOnClickListener(v -> doRefresh());
        btnViewLog.setOnClickListener(v -> showCtrlLogDialog());
        btnLogout.setOnClickListener(v -> {
            // 全盘审核修复：先递增代次，作废任何在途的登录/Root 导入写入，
            // 防止它们稍后返回把刚清掉的凭证重新写回。
            credOpGen.incrementAndGet();
            Prefs.clear(this);
            onCredentialChanged();
            etToken.setText(""); etCarid.setText(""); etPass.setText("");
            refreshStatusText();
            refreshWidget();
            logMsg("已清除凭证");
        });

        // BLE 扫描自动控车设置
        etBtAway = findViewById(R.id.et_bt_away);
        etBtAway.setText(String.valueOf(Prefs.btAwaySec(this)));
        etBtAway.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                // v1.9.5：只在输入值已落在合法范围时才落盘。原实现每次击键都解析+clamp，
                // 输入 "9" 的瞬间会被 clamp 成下限 10 落盘，阈值随击键跳变。
                // 超范围/中间态一律不写，保留旧值，输完合法值再生效。
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    if (v >= 10 && v <= 600) Prefs.setBtAwaySec(MainActivity.this, v);
                } catch (NumberFormatException ignored) { }
            }
        });

        // BLE 解锁信号阈值：RSSI >= 该值才算「靠近」（越接近0越强），实时生效无需重启服务
        etBtRssi = findViewById(R.id.et_bt_rssi);
        etBtRssi.setText(String.valueOf(Prefs.btRssiThreshold(this)));
        etBtRssi.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                // v1.9.5：同离开秒数——只在合法范围 -100~-30 内才落盘。
                // 输入 "-7" 的瞬间不再被 clamp 成 -30（极近）落盘，避免阈值随击键跳变、
                // 中间态误触发 BLE 靠近判定。非法/中间态保留旧值。
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    if (v >= -100 && v <= -30) Prefs.setBtRssiThreshold(MainActivity.this, v);
                } catch (NumberFormatException ignored) { }
            }
        });

        cbBtEnable.setChecked(Prefs.btEnabled(this));
        refreshBtDeviceText();
        cbBtEnable.setOnCheckedChangeListener((v, checked) -> {
            if (suppressBtToggle) return; // 权限回弹造成的伪事件，不处理
            if (checked && !hasBtPermission()) {
                requestBtPermission();
                // 只回弹 UI 开关，不动 Prefs、不停正在运行的服务：
                // setChecked(false) 会再进本监听器，用标志位屏蔽那次伪事件。
                suppressBtToggle = true;
                cbBtEnable.setChecked(false);
                suppressBtToggle = false;
                return;
            }
            if (checked && (Prefs.btMac(this) == null || Prefs.btMac(this).isEmpty())) {
                cbBtEnable.setChecked(false);
                logMsg("请先扫描并选择一个 BLE 设备");
                return;
            }
            Prefs.setBtEnabled(this, checked);
            if (checked) {
                Prefs.setBleUnlockLatched(this, false); // 用户主动开启：重置解锁闩锁
                startBleScanService();
            } else {
                stopBleScanService();
            }
            logMsg(checked ? "BLE 自动控车已开启（无需连接）" : "BLE 自动控车已关闭");
        });
        btnPickBt.setOnClickListener(v -> {
            if (!hasBtPermission()) { requestBtPermission(); return; }
            // 全盘审核修复：12 秒扫描窗口内重复点击会新建多个 ProgressDialog、
            // 注册多个 postDelayed 回调，导致弹窗无法全部关闭、设备选择结果错乱。
            // 进入前先清掉上一轮的扫描/弹窗/延迟任务，并在结果回调里恢复按钮。
            stopPickScan();
            ui.removeCallbacks(pickResultRunnable);
            btnPickBt.setEnabled(false);
            startBleDevicePicker();
        });
        // 若开关此前已打开（例如开机恢复），进入界面时确保服务在运行
        if (Prefs.btEnabled(this) && hasBtPermission()
                && Prefs.btMac(this) != null && !Prefs.btMac(this).isEmpty()) {
            startBleScanService();
        }
    }

    /* ==================== 桌面组件外观设置 ==================== */

    private void setupAppearance() {
        // 载入已保存的外观
        etBrandText.setText(Prefs.brandText(this));
        etBrandColor.setText(hex(Prefs.brandColor(this)));
        etStatusNormalColor.setText(hex(Prefs.statusNormalColor(this)));
        etStatusAlertColor.setText(hex(Prefs.statusAlertColor(this)));

        // 预设色块
        buildColorRow(R.id.row_brand_color, etBrandColor);
        buildColorRow(R.id.row_status_normal_color, etStatusNormalColor);
        buildColorRow(R.id.row_status_alert_color, etStatusAlertColor);

        // 输入时即时预览
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { updateAppearancePreview(); }
        };
        etBrandText.addTextChangedListener(watcher);
        etBrandColor.addTextChangedListener(watcher);
        etStatusNormalColor.addTextChangedListener(watcher);
        etStatusAlertColor.addTextChangedListener(watcher);

        btnSaveAppearance.setOnClickListener(v -> saveAppearance());
        updateAppearancePreview();
        refreshTitleBrand();
    }

    /** 生成一排可点击的预设色块，点击写入目标输入框 */
    private void buildColorRow(int rowId, final EditText target) {
        LinearLayout row = findViewById(rowId);
        if (row == null) return;
        int size = dp(30), gap = dp(10);
        for (final int color : PRESET_COLORS) {
            android.widget.ImageView sw = new android.widget.ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, gap, 0);
            sw.setLayoutParams(lp);
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(color);
            gd.setCornerRadius(dp(6));
            gd.setStroke(dp(1), 0xFFB0B0B0);
            sw.setImageDrawable(gd);
            sw.setContentDescription(hex(color));
            sw.setOnClickListener(v -> {
                target.setText(hex(color));
                updateAppearancePreview();
            });
            row.addView(sw);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void updateAppearancePreview() {
        String brand = etBrandText.getText().toString().trim();
        tvPreviewBrand.setText(brand.isEmpty() ? "MAZDA 昂克赛拉" : brand);
        tvPreviewBrand.setTextColor(parseColor(etBrandColor.getText().toString(), Prefs.brandColor(this)));
        tvPreviewStatus.setTextColor(parseColor(etStatusNormalColor.getText().toString(), Prefs.statusNormalColor(this)));
    }

    private void refreshTitleBrand() {
        String brand = Prefs.brandText(this);
        tvTitleBrand.setText(brand.isEmpty() ? "MAZDA 昂克赛拉" : brand);
        tvTitleBrand.setTextColor(Prefs.brandColor(this));
    }

    private void saveAppearance() {
        String brand = etBrandText.getText().toString().trim();
        if (brand.isEmpty()) brand = "MAZDA 昂克赛拉";
        String bc = etBrandColor.getText().toString().trim();
        String nc = etStatusNormalColor.getText().toString().trim();
        String ac = etStatusAlertColor.getText().toString().trim();
        int brandColor = parseColor(bc, Prefs.brandColor(this));
        int normalColor = parseColor(nc, Prefs.statusNormalColor(this));
        int alertColor = parseColor(ac, Prefs.statusAlertColor(this));
        // 回写规范化色值，便于核对
        etBrandColor.setText(hex(brandColor));
        etStatusNormalColor.setText(hex(normalColor));
        etStatusAlertColor.setText(hex(alertColor));
        Prefs.saveAppearance(this, brand, brandColor, normalColor, alertColor);
        refreshTitleBrand();
        updateAppearancePreview();
        refreshWidget();
        logMsg("外观已保存，桌面组件已更新");
    }

    /** 解析 #RRGGBB / RRGGBB，失败返回默认值 */
    private static int parseColor(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        String v = s.trim();
        if (!v.startsWith("#")) v = "#" + v;
        try { return Color.parseColor(v); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    private void refreshBtDeviceText() {
        String name = Prefs.btName(this);
        String mac = Prefs.btMac(this);
        if (mac == null || mac.isEmpty()) tvBtDevice.setText("当前设备：未选择");
        else tvBtDevice.setText("当前设备：" + (name == null || name.isEmpty() ? mac : name) + "\n" + mac);
    }

    /** 显示 BLE 扫描诊断摘要（长按下方文本可清空） */
    private void refreshBleDiagText() {
        if (tvBleDiag == null) return;
        String d = Prefs.bleDiagText(this);
        tvBleDiag.setText(d.isEmpty() ? "" : "BLE 诊断（长按清空）：\n" + d);
    }

    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBtPermission() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(android.Manifest.permission.BLUETOOTH_SCAN);
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT);
        } else if (Build.VERSION.SDK_INT >= 23) {
            perms.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), REQ_BT_PERMISSION);
    }

    /** 扫描周边正在广播的 BLE 设备，点击选中作为触发设备（无需配对/连接） */
    private void startBleDevicePicker() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null) { logMsg("本机没有蓝牙"); btnPickBt.setEnabled(true); return; }
        if (!ba.isEnabled()) { logMsg("请先打开手机蓝牙再扫描"); btnPickBt.setEnabled(true); return; }
        pickScanner = ba.getBluetoothLeScanner();
        if (pickScanner == null) { logMsg("本机不支持 BLE 扫描"); btnPickBt.setEnabled(true); return; }

        picked.clear();
        pickDialog = new ProgressDialog(this);
        pickDialog.setTitle("扫描 BLE 设备");
        pickDialog.setMessage("正在扫描周边设备，约 12 秒…");
        pickDialog.setCancelable(false);
        pickDialog.show();

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            pickScanner.startScan(null, settings, pickCb);
        } catch (SecurityException e) { dismissPick(); logMsg("无扫描权限，请授予后重试"); btnPickBt.setEnabled(true); return; }
        catch (Exception e) { dismissPick(); logMsg("启动扫描失败：" + e.getMessage()); btnPickBt.setEnabled(true); return; }

        // 全盘审核修复：复用同一 Runnable 字段，重复点击时 onClick 里可 removeCallbacks 取消旧任务
        ui.postDelayed(pickResultRunnable, BLE_PICK_MS);
    }

    private void stopPickScan() {
        if (pickScanner != null) {
            try { pickScanner.stopScan(pickCb); } catch (Exception ignored) { }
        }
        dismissPick();
    }

    private void dismissPick() {
        if (pickDialog != null && pickDialog.isShowing()) {
            try { pickDialog.dismiss(); } catch (Exception ignored) { }
        }
    }

    private void startBleScanService() {
        try {
            Intent i = new Intent(this, BleScanService.class);
            i.setAction(BleScanService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception e) { logMsg("无法启动 BLE 扫描服务：" + e.getMessage()); }
    }

    private void stopBleScanService() {
        try {
            Intent i = new Intent(this, BleScanService.class);
            i.setAction(BleScanService.ACTION_STOP);
            startService(i);
        } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        stopPickScan();
        // 清掉选设备延迟任务及其它待执行 UI 回调，避免用已销毁 Activity 弹窗/更新导致崩溃
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(req, permissions, grantResults);
        if (req != REQ_BT_PERMISSION) return;
        // 逐个核对本次请求的全部权限：只看 grantResults[0] 会把
        // 「给了 SCAN 但拒了 CONNECT」误判成全部成功，而 hasBtPermission() 要求两者都给。
        boolean notifDenied = false;
        boolean coreDenied = false;
        for (int i = 0; i < permissions.length; i++) {
            boolean ok = i < grantResults.length && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            String p = permissions[i];
            if (android.Manifest.permission.POST_NOTIFICATIONS.equals(p)) {
                if (!ok) notifDenied = true; // 通知权限只影响状态栏，不影响扫描
            } else if (!ok) {
                coreDenied = true;           // 蓝牙/定位是核心权限，缺一项即不可用
            }
        }
        // 用户取消授权对话框时 grantResults 为空：按全部未授予处理，但同样不动开关
        if (grantResults.length == 0) coreDenied = true;

        if (coreDenied) {
            // 只提示，不 setChecked(false)、不改 Prefs：
            // 用户此前已开启的自动控车服务必须保持原状。
            logMsg("蓝牙/定位权限未完整授予（扫描、连接需同时允许），BLE 自动控车不可用");
        } else {
            logMsg(notifDenied
                    ? "蓝牙权限已授予；通知被拒，状态栏不显示扫描状态"
                    : "权限已授予，可扫描 BLE 设备");
        }
    }

    private void refreshStatusText() {
        tvStatus.setTextColor(0xFF607080); // 登录信息用默认灰，避免残留车况警示红
        if (Prefs.isLoggedIn(this)) {
            String src;
            String pw = Prefs.password(this);
            String uid = Prefs.userid(this);
            if (!pw.isEmpty()) src = "账号登录";
            else if (!uid.isEmpty()) src = "Root 读取";
            else src = "手动 Token";
            tvStatus.setText("已登录（" + src + "）\n车号：" + Prefs.carid(this)
                    + "\n在桌面添加“MZD 控车”小组件即可使用");
        } else {
            tvStatus.setText("未登录 · 选择下方任一方式");
        }
    }

    /** 方式一：Root 一键读取官方 App 凭证 */
    private void doRootImport() {
        final int myGen = credOpGen.get();
        setBusy(true);
        tvStatus.setText("正在申请 Root 权限并读取凭证…");
        new Thread(() -> {
            String msg = "";
            boolean ok = false;
            try {
                if (!RootTokenReader.hasRoot()) {
                    msg = "未获得 Root 权限：请在 KernelSU/Magisk 管理器里允许本应用的 Root 请求，"
                            + "并确认授权模式为「超级用户」而非「Shell」。";
                } else {
                    RootTokenReader.Result r = RootTokenReader.read();
                    // 全盘审核修复：Root 读取期间若用户点了「清除凭证」，代次已变——丢弃结果，
                    // 不写 Prefs、不拉车况，避免已登出后凭证复活。
                    if (myGen != credOpGen.get()) return;
                    if (r.ok()) {
                        applyResult(r);
                        msg = "读取成功！车号：" + r.carid + "\n\n" + r.diag;
                        ok = true;
                    } else {
                        msg = "已取得 Root，但未解析出凭证。诊断：\n" + r.diag
                                + "\n请确认官方 App 已登录过一次。";
                    }
                }
            } catch (Exception e) {
                msg = "读取失败：" + shortErr(e);
            }
            final String m = msg;
            final boolean success = ok;
            ui.post(() -> {
                setBusy(false);
                if (myGen != credOpGen.get()) return; // 已被登出作废
                if (success) {
                    refreshStatusText();
                    refreshWidget();
                    logMsg("凭证已导入，正在获取车况");
                    doRefresh();
                } else {
                    tvStatus.setText(m);
                    logMsg("Root 读取未成功（详见页面提示）");
                }
            });
        }, "mzd-root").start();
    }

    /** 保存解析结果到 Prefs */
    private void applyResult(RootTokenReader.Result r) {
        Prefs.saveToken(this, r.token, r.carid);
        onCredentialChanged();
        getSharedPreferences("mzd_widget_prefs", MODE_PRIVATE).edit()
                .putString("userid", r.userid).apply();
        ui.post(() -> {
            etToken.setText(r.token == null || r.token.isEmpty() ? "" : "****************");
            etCarid.setText(r.carid);
        });
    }

    /** 方式二：账号密码登录 */
    private void doLogin() {
        String phone = etPhone.getText().toString().trim();
        String pass = etPass.getText().toString();
        if (phone.isEmpty() || pass.isEmpty()) {
            logMsg("请输入手机号和密码"); return;
        }
        final int myGen = credOpGen.get();
        setBusy(true);
        tvStatus.setText("登录中…");
        new Thread(() -> {
            String msg = "";
            String loginCarid = null;
            boolean cancelled = false;
            try {
                Api.LoginResult r = Api.login(phone, pass);
                // 全盘审核修复：网络请求期间若用户点了「清除凭证」，代次已变——
                // 丢弃本次结果，绝不写回 Prefs，否则会出现「已登出但凭证复活」。
                if (myGen != credOpGen.get()) {
                    cancelled = true;
                } else {
                    Prefs.save(this, r.token, r.carid, r.userid, phone, pass);
                    onCredentialChanged();
                    loginCarid = r.carid;
                    msg = "登录成功，车辆ID：" + r.carid;
                }
            } catch (Exception e) {
                msg = "登录失败：" + shortErr(e);
            }
            final String m = msg;
            final String finalCarid = loginCarid;
            final boolean wasCancelled = cancelled;
            ui.post(() -> {
                setBusy(false);
                if (wasCancelled) return; // 已被登出作废，不写输入框、不刷新
                // 全盘审核修复：账号登录成功后必须同步回写输入框，与 Root 路径 applyResult 一致。
                // 否则「账号 A 登录→账号 B 登录→手动点保存 Token」会把 B 的 token 配上
                // etCarid 里残留的 A 车号，写成错配凭证（401/查错车）。成功时同步车号与 token 占位符。
                if (finalCarid != null) {
                    etCarid.setText(finalCarid);
                    etToken.setText("****************");
                }
                logMsg(m);
                refreshStatusText();
                refreshWidget();
            });
        }, "mzd-login").start();
    }

    /** 方式三：手动填 token + carid */
    private void doSaveToken() {
        String token = etToken.getText().toString().trim();
        String carid = etCarid.getText().toString().trim();
        // 若用户未改动 star 占位符，则沿用已保存的真实 token，不把星号写进去
        if (isStars(token)) token = Prefs.token(this);
        if (token.isEmpty() || carid.isEmpty()) {
            logMsg("请填入 token 和 carid"); return;
        }
        Prefs.saveToken(this, token, carid);
        onCredentialChanged();
        refreshStatusText();
        refreshWidget();
        logMsg("已保存 Token，正在获取车况");
        doRefresh();
    }

    private static boolean isStars(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '*') return false;
        return true;
    }

    /** 刷新车况 */
    private void doRefresh() {
        if (!Prefs.isLoggedIn(this)) { logMsg("请先获取凭证"); return; }
        new Thread(() -> {
            String msg;
            try {
                Api.CarState s = CarStateCache.get(this).refreshAndWait(this);
                if (s == null) throw new Exception("车况未确认");
                String engine = "发动机" + (s.started == null ? "—" : (s.started ? "已启动" : "未启动"));
                String door = "车辆" + (s.unlocked == null ? "—" : (s.unlocked ? "未锁" : "已锁"));
                // 发动机状态与车门状态同一行；油量已去除
                StringBuilder b = new StringBuilder(engine).append("  ").append(door);
                if (s.mileage != null && !s.mileage.isEmpty()) b.append("\n里程 ").append(s.mileage).append(" km");
                msg = b.toString();
                // 未启动且已锁=黑色；已启动或车门未锁=红色（警示）
                final boolean alert = (s.started != null && s.started) || (s.unlocked != null && s.unlocked);
                final int normalC = Prefs.statusNormalColor(this);
                final int alertC = Prefs.statusAlertColor(this);
                ui.post(() -> tvStatus.setTextColor(alert ? alertC : normalC));
            } catch (Exception e) {
                msg = "获取车况失败：" + shortErr(e);
                ui.post(() -> tvStatus.setTextColor(0xFF607080));
            }
            final String m = msg;
            ui.post(() -> {
                tvStatus.setText(m);
                refreshBleDiagText();
                refreshWidget();
            });
        }, "mzd-status").start();
    }

    private void setBusy(boolean b) {
        btnRootImport.setEnabled(!b);
        btnLogin.setEnabled(!b);
        btnSaveToken.setEnabled(!b);
        // 全盘审核修复：耗时登录/Root 导入期间禁止点「清除凭证」，
        // 否则用户清完后旧线程返回仍会写回凭证（复活）。代次校验为第二道防线。
        btnLogout.setEnabled(!b);
    }

    /** 原 toast 提示改为写 log（本项目已移除所有 Toast） */
    private void logMsg(String s) { CtrlLog.i(TAG, s); }

    /** 底部按钮：弹窗查看已落盘的指令/判定日志，可清空 */
    private void showCtrlLogDialog() {
        final TextView tv = new TextView(this);
        tv.setText(CtrlLog.text());
        tv.setTextSize(11f);
        tv.setTextColor(0xFF212121);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFFFFFFFF);
        sv.addView(tv, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("指令日志（最新在前，最多 200 条）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> {
                    CtrlLog.clear();
                    tv.setText("暂无日志");
                })
                .show();
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    private void refreshWidget() {
        Intent i = new Intent(this, WidgetActions.class);
        i.setAction(CarWidget.ACTION_REFRESH);
        sendBroadcast(i);
    }

    /**
     * 凭证/车辆发生变更（登录、登出、换 Token、Root 导入）后调用：
     * 同时清掉全局车况缓存与桌面已写盘的车况，防止上一个账号/车辆的旧车况
     * 被当作新账号的实时状态显示（v1.9.4）。
     */
    private void onCredentialChanged() {
        CarStateCache.get(this).invalidate();
        CarWidget.clearStatusOnCredentialChange(this);
        // 全盘审核修复：credential 变更时同步清空指令日志——日志里含上一次账号的 carid
        // （如「登录成功，车辆ID：xxx」），不清会导致切号后日志混着旧账号信息。
        CtrlLog.clear();
    }
}
