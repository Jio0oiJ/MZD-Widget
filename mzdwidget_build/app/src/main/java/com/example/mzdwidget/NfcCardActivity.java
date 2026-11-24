package com.example.mzdwidget;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.NfcA;
import android.nfc.tech.NfcB;
import android.nfc.tech.NfcF;
import android.nfc.tech.NfcV;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/**
 * v1.9.46：手机端 NFC 刷卡切换锁车。
 *
 * 小米手环 9 Pro 的 Vela 快应用没有开放 NFC 读卡 API（手环 NFC 只做卡模拟），
 * 所以改为手机贴卡读取 UID。只做 UID 识别，不解密扇区、不复制卡片。
 *
 * 安全底线：
 * - 只接受已录入的 UID；未录入/非目标卡一律忽略；
 * - 每次命中都拉一次实时车况（CarStateCache.refreshAndWait），不用旧缓存；
 * - started==null / unlocked==null 一律跳过；started==true 绝不解锁（行驶中不切换）；
 * - 解锁/上锁走 CarActions.send 统一入口，写 CtrlLog、登记提示音；
 * - 指令被服务器接受后，通过小米互联让手环振动：解锁 1 下、上锁 2 下。
 *
 * v1.9.46 修复「录入读不到卡」：
 * - 前台分发同时注册 NDEF/TECH/TAG 三种 action（原来只注册 TECH）；
 * - techList 覆盖 NfcA/B/F/V、IsoDep、Ndef、MifareClassic/Ultralight（原来只有 NfcA）；
 * - 页面保持屏幕常亮，避免贴卡前息屏导致前台分发失效（最常见原因）；
 * - 每次贴卡显示 UID + 卡片技术列表，并在日志里留痕，便于定位卡类型；
 * - 分发启用失败时把原因直接显示在页面上；
 * - 录入落盘改 commit()，贴卡震动反馈；增加「重新启用监听」按钮。
 *
 * 只在 Activity 前台启用 NFC 前台分发，不会全局抢 NFC，避免影响公交/门禁卡。
 */
public final class NfcCardActivity extends Activity {

    private static final String TAG = "MZDNfc";

    private NfcAdapter adapter;
    private PendingIntent pendingIntent;
    private android.content.IntentFilter[] filters;
    private String[][] techLists;

    private TextView tvUid;
    private TextView tvStatus;
    private TextView tvNfcState;
    private Button btnEnroll;
    private Button btnSwitchMode;
    private Button btnRetry;

    /** true=录入模式，false=刷卡切换模式（默认） */
    private volatile boolean enrollMode = false;
    private volatile boolean busy = false;
    private String dispatchError = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_card);
        // 关键：NFC 前台分发要求屏幕点亮且已解锁。不常亮时用户点完录入按钮，
        // 还没把卡贴上来屏幕就熄了，分发随之失效，表现为「完全读不到」。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvUid = findViewById(R.id.tv_nfc_uid);
        tvStatus = findViewById(R.id.tv_nfc_status);
        tvNfcState = findViewById(R.id.tv_nfc_state);
        btnEnroll = findViewById(R.id.btn_nfc_enroll);
        btnSwitchMode = findViewById(R.id.btn_nfc_switch_mode);
        btnRetry = findViewById(R.id.btn_nfc_retry);

        adapter = NfcAdapter.getDefaultAdapter(this);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, NfcCardActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags);
        filters = buildFilters();
        // 覆盖常见卡：Mifare Classic/Ultralight/NTAG=NfcA；CPU 卡=IsoDep；
        // 公交卡可能是 NfcB/NfcF/NfcV；部分卡带 Ndef。
        techLists = new String[][] { new String[] {
                NfcA.class.getName(), NfcB.class.getName(), NfcF.class.getName(),
                NfcV.class.getName(), IsoDep.class.getName(), Ndef.class.getName(),
                NdefFormatable.class.getName(), MifareClassic.class.getName(),
                MifareUltralight.class.getName()
        } };

        btnEnroll.setOnClickListener(v -> {
            enrollMode = true;
            setStatus("录入模式：请把目标卡贴近手机背面 NFC 感应区（保持亮屏）", false);
            refreshNfcState();
        });
        btnSwitchMode.setOnClickListener(v -> {
            enrollMode = false;
            setStatus("刷卡模式：请把已录入的卡贴近手机背面（保持亮屏）", false);
            refreshNfcState();
        });
        btnRetry.setOnClickListener(v -> {
            enableDispatch();
            refreshNfcState();
            setStatus("已重新启用 NFC 监听，请再贴一次卡", false);
        });

        refreshUid();
        refreshNfcState();
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("本机 NFC 不可用或未开启，请到系统设置 > 连接 > NFC 打开后再试", true);
        } else {
            setStatus("请把卡片贴近手机背面 NFC 感应区；录入请先点「录入当前卡片」", false);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableDispatch();
        refreshNfcState();
    }

    @Override
    protected void onPause() {
        if (adapter != null) {
            try { adapter.disableForegroundDispatch(this); } catch (Throwable ignored) { }
        }
        super.onPause();
    }

    private void enableDispatch() {
        if (adapter == null || !adapter.isEnabled()) return;
        try {
            adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists);
            dispatchError = "";
            Log.i(TAG, "NFC 前台分发已启用");
        } catch (Throwable t) {
            dispatchError = shortErr(t);
            Log.w(TAG, "启用 NFC 前台分发失败：" + t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "handleIntent action=" + action);
        if (action == null
                || !(NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action))) return;

        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            setStatus("读到了 NFC 事件但拿不到卡片对象，请换个位置重贴", true);
            return;
        }
        String techs = describeTechs(tag);
        byte[] id = tag.getId();
        if (id == null || id.length == 0) {
            setStatus("卡片没有可读 UID（" + techs + "），忽略", true);
            Log.w(TAG, "卡片无 UID techs=" + techs);
            return;
        }
        String uid = hex(id);
        // 每次贴卡都可见：即使模式不对，用户也能确认「卡已经被读到」
        tvUid.setText("目标卡：" + safeUid(Prefs.nfcCardUid(getApplicationContext()))
                + "\n刚读到：" + uid + "（" + techs + "）");
        Log.i(TAG, "读卡 uid=" + uid + " techs=" + techs + " action=" + action + " enroll=" + enrollMode);
        buzz(60);

        if (enrollMode) {
            Prefs.setNfcCardUid(getApplicationContext(), uid);
            refreshUid();
            buzz(120);
            setStatus("已录入目标卡 UID：" + uid + "\n（" + techs + "）\n下次在刷卡模式贴同卡即可切换锁车", false);
            CtrlLog.i(TAG, "【NFC】录入目标卡 UID=" + uid + " techs=" + techs);
            enrollMode = false;
            return;
        }
        String target = Prefs.nfcCardUid(getApplicationContext());
        if (target == null || target.isEmpty()) {
            setStatus("尚未录入目标卡（刚读到 " + uid + "），请先点「录入当前卡片」", true);
            return;
        }
        if (!target.equalsIgnoreCase(uid)) {
            setStatus("非目标卡（读到 " + uid + "，目标 " + target + "），忽略", true);
            return;
        }
        if (busy) {
            setStatus("上一张卡还在处理中，请稍后再贴", true);
            return;
        }
        busy = true;
        setStatus("识别目标卡，正在查询实时车况…", false);
        toggleByRealtimeState(getApplicationContext(), uid);
    }

    /** 工作线程：拉实时车况 → 按 started/unlocked 决定动作 → 反馈手环振动 */
    private void toggleByRealtimeState(final Context app, final String uid) {
        new Thread(() -> {
            String msg = "";
            boolean ok = false;
            try {
                if (!Prefs.isLoggedIn(app)) {
                    msg = "未登录车辆账号，未发指令";
                } else {
                    Api.CarState s = CarStateCache.get(app).refreshAndWait(app);
                    if (s == null) {
                        msg = "实时车况查询失败，未发指令";
                    } else if (s.started == null) {
                        msg = "发动机状态未知，未发指令";
                    } else if (Boolean.TRUE.equals(s.started)) {
                        // 红线：行驶中绝不自动解锁，也不做状态切换
                        msg = "车辆已启动，不切换（防行驶中解锁）";
                    } else if (s.unlocked == null) {
                        msg = "车门状态未知，未发指令";
                    } else {
                        boolean wasUnlocked = Boolean.TRUE.equals(s.unlocked);
                        String cmd = wasUnlocked ? Api.CMD_LOCK : Api.CMD_UNLOCK;
                        String name = wasUnlocked ? "上锁" : "解锁";
                        CarActions.send(app, cmd, -1, "NFC刷卡切换：" + name);
                        int pulses = wasUnlocked ? 2 : 1;
                        WearRelayService.vibrateBand(app, pulses);
                        ok = true;
                        msg = name + "指令已发送，手环振动 " + pulses + " 下\n（指令日志已记录）";
                        CtrlLog.i(TAG, "【NFC】目标卡 " + uid + " 切换为" + name);
                    }
                }
            } catch (Exception e) {
                msg = "切换失败：" + shortErr(e);
                CtrlLog.e(TAG, "【NFC】切换失败：" + shortErr(e));
            } finally {
                busy = false;
            }
            final boolean finalOk = ok;
            final String finalMsg = msg;
            runOnUiThread(() -> {
                setStatus(finalMsg, !finalOk);
                buzz(finalOk ? 200 : 400);
            });
        }, "mzd-nfc-toggle").start();
    }

    private void refreshUid() {
        String uid = Prefs.nfcCardUid(getApplicationContext());
        tvUid.setText("目标卡：" + safeUid(uid));
    }

    private void refreshNfcState() {
        if (tvNfcState == null) return;
        if (adapter == null) {
            tvNfcState.setText("NFC 状态：本机不支持 NFC");
        } else if (!adapter.isEnabled()) {
            tvNfcState.setText("NFC 状态：未开启（系统设置 > 连接 > NFC）");
        } else if (dispatchError.isEmpty()) {
            tvNfcState.setText("NFC 状态：已开启，监听正常（保持亮屏）");
        } else {
            tvNfcState.setText("NFC 状态：监听失败 — " + dispatchError);
        }
    }

    private void setStatus(String text, boolean warn) {
        if (tvStatus == null) return;
        tvStatus.setText(text);
        tvStatus.setTextColor(warn ? 0xFFB3261E : 0xFF1B5E20);
    }

    private void buzz(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) { }
    }

    private static String safeUid(String uid) {
        return (uid == null || uid.isEmpty()) ? "尚未录入" : uid;
    }

    private static String describeTechs(Tag tag) {
        String[] list = tag.getTechList();
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s.substring(s.lastIndexOf('.') + 1));
        }
        return sb.length() == 0 ? "未知技术" : sb.toString();
    }

    private static android.content.IntentFilter[] buildFilters() {
        android.content.IntentFilter ndef =
                new android.content.IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        try { ndef.addDataType("*/*"); } catch (Throwable ignored) { }
        ndef.addCategory(Intent.CATEGORY_DEFAULT);
        android.content.IntentFilter tech =
                new android.content.IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        tech.addCategory(Intent.CATEGORY_DEFAULT);
        android.content.IntentFilter tag =
                new android.content.IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
        tag.addCategory(Intent.CATEGORY_DEFAULT);
        return new android.content.IntentFilter[] { ndef, tech, tag };
    }

    private static String hex(byte[] id) {
        StringBuilder sb = new StringBuilder(id.length * 2);
        for (byte b : id) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    private static String shortErr(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }
}
