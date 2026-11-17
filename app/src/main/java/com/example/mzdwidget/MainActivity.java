package com.example.mzdwidget;

import android.app.Activity;
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
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三种方式获取凭证：Root 一键读取 / 账号密码 / 手动 Token + 车况。 */
public class MainActivity extends Activity {

    private static final int REQ_BT_PERMISSION = 4401;
    private static final long BLE_PICK_MS = 12000L; // 选设备时扫描时长

    /** 常用预设色，点击即写入对应颜色输入框 */
    private static final int[] PRESET_COLORS = {
            0xFF8A1A2B, 0xFFC41230, 0xFF212121, 0xFFFFFFFF,
            0xFF1565C0, 0xFF1B8A5A, 0xFFE8871A, 0xFF607080
    };

    private EditText etPhone, etPass, etToken, etCarid;
    private TextView tvStatus, tvBtDevice, tvTitleBrand, tvPreviewBrand, tvPreviewStatus;
    private EditText etBrandText, etBrandColor, etStatusNormalColor, etStatusAlertColor, etBtAway;
    private Button btnLogin, btnSaveToken, btnRefresh, btnLogout, btnRootImport, btnPickBt, btnSaveAppearance;
    private CheckBox cbBtEnable;
    private final Handler ui = new Handler(Looper.getMainLooper());

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
            ui.post(() -> toast("BLE 扫描失败（code=" + errorCode + "）"));
        }
    };
    private final LinkedHashMap<String, String> picked = new LinkedHashMap<>();
    private ProgressDialog pickDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 10 秒快刷由系统精确闹钟驱动；若「闹钟和提醒」被系统关闭则提示用户开启
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(android.content.Context.ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                toast("系统未允许「闹钟和提醒」，自动快刷可能被延迟，请在系统设置里开启");
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

        tvTitleBrand = findViewById(R.id.tv_title_brand);
        tvPreviewBrand = findViewById(R.id.tv_preview_brand);
        tvPreviewStatus = findViewById(R.id.tv_preview_status);
        etBrandText = findViewById(R.id.et_brand_text);
        etBrandColor = findViewById(R.id.et_brand_color);
        etStatusNormalColor = findViewById(R.id.et_status_normal_color);
        etStatusAlertColor = findViewById(R.id.et_status_alert_color);
        btnSaveAppearance = findViewById(R.id.btn_save_appearance);

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
        btnLogout.setOnClickListener(v -> {
            Prefs.clear(this);
            etToken.setText(""); etCarid.setText(""); etPass.setText("");
            refreshStatusText();
            refreshWidget();
            Toast.makeText(this, "已清除凭证", Toast.LENGTH_SHORT).show();
        });

        // BLE 扫描自动控车设置
        etBtAway = findViewById(R.id.et_bt_away);
        etBtAway.setText(String.valueOf(Prefs.btAwaySec(this)));
        etBtAway.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                try { Prefs.setBtAwaySec(MainActivity.this, Integer.parseInt(s.toString().trim())); }
                catch (NumberFormatException ignored) { }
            }
        });

        cbBtEnable.setChecked(Prefs.btEnabled(this));
        refreshBtDeviceText();
        cbBtEnable.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !hasBtPermission()) {
                requestBtPermission();
                cbBtEnable.setChecked(false);
                return;
            }
            if (checked && (Prefs.btMac(this) == null || Prefs.btMac(this).isEmpty())) {
                cbBtEnable.setChecked(false);
                toast("请先扫描并选择一个 BLE 设备");
                return;
            }
            Prefs.setBtEnabled(this, checked);
            // 实验分支：开关切换即清空系统扫描的落盘临时状态（在场/去重/命中时间）
            Prefs.clearSysScanState(this);
            if (checked) {
                Prefs.setBleUnlockLatched(this, false); // 用户主动开启：重置解锁闩锁
                startBleScanService();
            } else {
                stopBleScanService();
            }
            toast(checked ? "BLE 自动控车已开启（无需连接）" : "BLE 自动控车已关闭");
        });
        btnPickBt.setOnClickListener(v -> {
            if (!hasBtPermission()) { requestBtPermission(); return; }
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
        toast("外观已保存，桌面组件已更新");
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
        if (ba == null) { toast("本机没有蓝牙"); return; }
        if (!ba.isEnabled()) { toast("请先打开手机蓝牙再扫描"); return; }
        pickScanner = ba.getBluetoothLeScanner();
        if (pickScanner == null) { toast("本机不支持 BLE 扫描"); return; }

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
        } catch (SecurityException e) { dismissPick(); toast("无扫描权限，请授予后重试"); return; }
        catch (Exception e) { dismissPick(); toast("启动扫描失败：" + e.getMessage()); return; }

        ui.postDelayed(() -> {
            stopPickScan();
            final List<String> macs = new ArrayList<>(picked.keySet());
            if (macs.isEmpty()) {
                toast("附近没扫到 BLE 设备，让设备保持广播/亮屏后再试");
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
                        toast("已选择：" + (n == null || n.isEmpty() ? mac : n));
                        if (Prefs.btEnabled(MainActivity.this)) {
                            Prefs.setBleUnlockLatched(MainActivity.this, false);
                            Prefs.clearSysScanState(MainActivity.this); // 实验分支：换设备清系统扫描状态
                            stopBleScanService();
                            startBleScanService();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }, BLE_PICK_MS);
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
        } catch (Exception e) { toast("无法启动 BLE 扫描服务：" + e.getMessage()); }
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
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(req, permissions, grantResults);
        if (req == REQ_BT_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "权限已授予，可扫描 BLE 设备" : "未授予蓝牙/定位权限，BLE 自动控车不可用");
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
        setBusy(true);
        tvStatus.setText("正在申请 Root 权限并读取凭证…");
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                if (!RootTokenReader.hasRoot()) {
                    msg = "未获得 Root 权限：请在 KernelSU/Magisk 管理器里允许本应用的 Root 请求，"
                            + "并确认授权模式为「超级用户」而非「Shell」。";
                } else {
                    RootTokenReader.Result r = RootTokenReader.read();
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
                if (success) {
                    refreshStatusText();
                    refreshWidget();
                    toast("凭证已导入，正在获取车况");
                    doRefresh();
                } else {
                    tvStatus.setText(m);
                    Toast.makeText(this, "Root 读取未成功（详见页面提示）", Toast.LENGTH_LONG).show();
                }
            });
        }, "mzd-root").start();
    }

    /** 保存解析结果到 Prefs */
    private void applyResult(RootTokenReader.Result r) {
        Prefs.saveToken(this, r.token, r.carid);
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
            toast("请输入手机号和密码"); return;
        }
        setBusy(true);
        tvStatus.setText("登录中…");
        new Thread(() -> {
            String msg;
            try {
                Api.LoginResult r = Api.login(phone, pass);
                Prefs.save(this, r.token, r.carid, r.userid, phone, pass);
                msg = "登录成功，车辆ID：" + r.carid;
            } catch (Exception e) {
                msg = "登录失败：" + shortErr(e);
            }
            final String m = msg;
            ui.post(() -> {
                setBusy(false);
                toast(m);
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
            toast("请填入 token 和 carid"); return;
        }
        Prefs.saveToken(this, token, carid);
        refreshStatusText();
        refreshWidget();
        toast("已保存 Token，正在获取车况");
        doRefresh();
    }

    private static boolean isStars(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '*') return false;
        return true;
    }

    /** 刷新车况 */
    private void doRefresh() {
        if (!Prefs.isLoggedIn(this)) { toast("请先获取凭证"); return; }
        new Thread(() -> {
            String msg;
            try {
                Api.CarState s = Api.getStatus(Prefs.token(this), Prefs.carid(this));
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
                refreshWidget();
            });
        }, "mzd-status").start();
    }

    private void setBusy(boolean b) {
        btnRootImport.setEnabled(!b);
        btnLogin.setEnabled(!b);
        btnSaveToken.setEnabled(!b);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    private void refreshWidget() {
        Intent i = new Intent(this, CarWidget.class);
        i.setAction(CarWidget.ACTION_REFRESH);
        sendBroadcast(i);
    }
}
