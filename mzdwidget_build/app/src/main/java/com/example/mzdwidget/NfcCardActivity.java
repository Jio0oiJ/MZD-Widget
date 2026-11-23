package com.example.mzdwidget;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

/**
 * v1.9.27：手机端 NFC 刷卡切换锁车。
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
 * 只在 Activity 前台启用 NFC 前台分发，不会全局抢 NFC，避免影响公交/门禁卡。
 */
public final class NfcCardActivity extends Activity {

    private static final String TAG = "MZDNfc";

    private NfcAdapter adapter;
    private PendingIntent pendingIntent;
    private IntentFilterWrapper filters;
    private String[][] techLists;

    private TextView tvUid;
    private TextView tvStatus;
    private Button btnEnroll;
    private Button btnSwitchMode;

    /** true=录入模式，false=刷卡切换模式（默认） */
    private volatile boolean enrollMode = false;
    private volatile boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_card);
        tvUid = findViewById(R.id.tv_nfc_uid);
        tvStatus = findViewById(R.id.tv_nfc_status);
        btnEnroll = findViewById(R.id.btn_nfc_enroll);
        btnSwitchMode = findViewById(R.id.btn_nfc_switch_mode);

        adapter = NfcAdapter.getDefaultAdapter(this);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        pendingIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, NfcCardActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags);
        filters = new IntentFilterWrapper();
        techLists = new String[][] { new String[] { NfcA.class.getName() } };

        btnEnroll.setOnClickListener(v -> {
            enrollMode = true;
            setStatus("录入模式：请把目标卡贴近手机背面 NFC 感应区", false);
        });
        btnSwitchMode.setOnClickListener(v -> {
            enrollMode = false;
            setStatus("刷卡模式：请把已录入的卡贴近手机背面", false);
        });
        refreshUid();
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("本机 NFC 不可用或未开启，无法使用刷卡切换", true);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null && adapter.isEnabled()) {
            try {
                adapter.enableForegroundDispatch(this, pendingIntent,
                        filters.all(), techLists);
            } catch (Throwable t) {
                Log.w(TAG, "启用 NFC 前台分发失败：" + t);
            }
        }
    }

    @Override
    protected void onPause() {
        if (adapter != null) {
            try { adapter.disableForegroundDispatch(this); } catch (Throwable ignored) { }
        }
        super.onPause();
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
        if (!NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) return;
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null || tag.getId() == null || tag.getId().length == 0) {
            setStatus("卡片没有可读 UID，忽略", true);
            return;
        }
        String uid = hex(tag.getId());
        if (enrollMode) {
            Prefs.setNfcCardUid(getApplicationContext(), uid);
            refreshUid();
            setStatus("已录入目标卡 UID：" + uid + "\n下次在刷卡模式贴同卡即可切换锁车", false);
            CtrlLog.i(TAG, "【NFC】录入目标卡 UID=" + uid);
            return;
        }
        String target = Prefs.nfcCardUid(getApplicationContext());
        if (target == null || target.isEmpty()) {
            setStatus("尚未录入目标卡，请先点「录入当前卡片」", true);
            return;
        }
        if (!target.equalsIgnoreCase(uid)) {
            setStatus("非目标卡（读到 " + uid + "），忽略", true);
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
            runOnUiThread(() -> setStatus(finalMsg, !finalOk));
        }, "mzd-nfc-toggle").start();
    }

    private void refreshUid() {
        String uid = Prefs.nfcCardUid(getApplicationContext());
        tvUid.setText("目标卡：" + (uid == null || uid.isEmpty() ? "尚未录入" : uid));
    }

    private void setStatus(String text, boolean warn) {
        if (tvStatus != null) tvStatus.setText(text);
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

    /** 小包装：避免直接持有 android.content.IntentFilter 造成阅读混乱 */
    private static final class IntentFilterWrapper {
        private final android.content.IntentFilter[] arr;
        IntentFilterWrapper() {
            android.content.IntentFilter f = new android.content.IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            arr = new android.content.IntentFilter[] { f };
        }
        android.content.IntentFilter[] all() { return arr; }
    }
}
