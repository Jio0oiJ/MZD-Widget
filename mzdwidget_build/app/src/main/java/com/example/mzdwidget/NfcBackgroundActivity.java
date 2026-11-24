package com.example.mzdwidget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * v1.9.48：后台/锁屏贴卡切换锁车（无需打开 App）。
 *
 * 背景：v1.9.47 的前台读卡用 enableReaderMode，独占 NFC 控制器，只在
 * NfcCardActivity 处于前台时有效；退到后台 onPause 必须释放，因此后台贴卡无反应。
 * 本 Activity 通过 Manifest 静态注册 ACTION_TECH_DISCOVERED，系统贴卡时直接唤起
 * 这个透明页面处理，与前台 ReaderMode 互补（前台 ReaderMode 独占时系统不会发 intent）。
 *
 * 安全底线与 NfcCardActivity 完全一致：
 * - 只接受已录入的 UID；未录入/非目标卡静默忽略；
 * - 每次命中都拉实时车况（refreshAndWait），不用旧缓存；
 * - started==null / unlocked==null 一律跳过；started==true 绝不解锁（行驶中不切换）；
 * - 解锁/上锁走 CarActions.send 统一入口，写 CtrlLog；
 * - 手机本地震动 + 手环震动反馈：解锁 1 下、上锁 2 下、失败长振（后台无 UI，靠震动感知）。
 *
 * 去重：同一张卡 3 秒内只处理一次，避免贴卡停留期间系统重复派发导致连续切换。
 */
public final class NfcBackgroundActivity extends Activity {

    private static final String TAG = "MZDNfcBg";
    private static final long DEDUP_MS = 3000;
    private static volatile String lastUid = "";
    private static volatile long lastMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        try {
            if (intent == null) return;
            String action = intent.getAction();
            if (action == null
                    || !(NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                    || NfcAdapter.ACTION_TAG_DISCOVERED.equals(action))) return;

            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag == null || tag.getId() == null || tag.getId().length == 0) return;
            String uid = hex(tag.getId());
            Log.i(TAG, "后台贴卡 uid=" + uid);

            long now = System.currentTimeMillis();
            if (uid.equalsIgnoreCase(lastUid) && now - lastMs < DEDUP_MS) {
                Log.d(TAG, "重复贴卡，去重忽略 uid=" + uid);
                return;
            }
            lastUid = uid;
            lastMs = now;

            String target = Prefs.nfcCardUid(getApplicationContext());
            if (target == null || target.isEmpty()) {
                Log.i(TAG, "尚未录入目标卡，忽略");
                return;
            }
            if (!target.equalsIgnoreCase(uid)) {
                Log.i(TAG, "非目标卡 uid=" + uid + " target=" + target + "，忽略");
                return;
            }
            performToggle(getApplicationContext(), uid);
        } catch (Throwable t) {
            Log.w(TAG, "后台贴卡处理异常：" + t);
        } finally {
            // 透明页不展示，处理完立即结束
            finish();
        }
    }

    /** 工作线程：拉实时车况 → 按 started/unlocked 决定动作 → 手机本地 + 手环震动反馈 */
    private static void performToggle(final Context app, final String uid) {
        new Thread(() -> {
            boolean ok = false;
            int pulses = 0;
            try {
                if (!Prefs.isLoggedIn(app)) {
                    Log.i(TAG, "未登录车辆账号，未发指令");
                } else {
                    Api.CarState s = CarStateCache.get(app).refreshAndWait(app);
                    if (s == null) {
                        Log.i(TAG, "实时车况查询失败，未发指令");
                    } else if (s.started == null) {
                        Log.i(TAG, "发动机状态未知，未发指令");
                    } else if (Boolean.TRUE.equals(s.started)) {
                        // 红线：行驶中绝不自动解锁，也不做状态切换
                        Log.i(TAG, "车辆已启动，不切换（防行驶中解锁）");
                    } else if (s.unlocked == null) {
                        Log.i(TAG, "车门状态未知，未发指令");
                    } else {
                        boolean wasUnlocked = Boolean.TRUE.equals(s.unlocked);
                        String cmd = wasUnlocked ? Api.CMD_LOCK : Api.CMD_UNLOCK;
                        String name = wasUnlocked ? "上锁" : "解锁";
                        CarActions.send(app, cmd, -1, "NFC后台刷卡切换：" + name);
                        pulses = wasUnlocked ? 2 : 1;
                        WearRelayService.vibrateBand(app, pulses);
                        ok = true;
                        CtrlLog.i(TAG, "【NFC后台】目标卡 " + uid + " 切换为" + name
                                + "，手机+手环振动 " + pulses + " 下");
                    }
                }
            } catch (Exception e) {
                String m = e.getMessage();
                Log.w(TAG, "后台切换失败：" + m);
                CtrlLog.e(TAG, "【NFC后台】失败：" + (m == null ? e.getClass().getSimpleName() : m));
            }
            // 后台无 UI，靠手机本地震动反馈成败
            if (ok) {
                for (int i = 0; i < pulses; i++) {
                    buzz(app, 120);
                    try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                }
            } else {
                buzz(app, 600);
            }
        }, "mzd-nfc-bg").start();
    }

    private static void buzz(Context c, long ms) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) { }
    }

    private static String hex(byte[] id) {
        StringBuilder sb = new StringBuilder(id.length * 2);
        for (byte b : id) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }
}
