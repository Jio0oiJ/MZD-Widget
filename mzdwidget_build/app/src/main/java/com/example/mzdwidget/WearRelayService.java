package com.example.mzdwidget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手环遥控转发服务（v1.9.25）。
 *
 * 小米手环9 Pro 无网络，Vela 快应用经 system.interconnect 把「解锁/上锁」指令透传给
 * 本手机 App；本服务收到后复用 CarActions.send()（与桌面按钮同一条指令入口，
 * 写指令日志、登记提示音期望、token/carid 全部沿用），再把结果回传手环显示。
 *
 * 硬性前提（小米互联要求）：
 *   · 手环 rpk 的 package 与本 App 的 applicationId 相同（com.example.mzdwidget）；
 *   · 手环 rpk 与本 App 用同一签名证书（手环 PEM 由本 App 的 debug.keystore 导出）。
 *   否则 node 鉴权会报 fingerprint 不一致，通信被拒。
 *
 * 安全：默认只接受 unlock/lock；start 仅在手环明确长按 3 秒（消息带 holdMs>=3000）
 * 且 reqId 去重 + 3 秒节流通过后才受理，并复用 CarActions.smartStartAndUnlock()
 * 的完整三步流程（预锁车→确认启动→解锁）。stall/search 一律拒绝。
 * 指令走工作线程串行执行；不做任何绕过 CarActions 的直连网络调用。
 */
public final class WearRelayService extends Service {

    private static final String TAG = "MZDWearRelay";
    private static final String CHANNEL_ID = "mzd_wear_relay";
    private static final int NOTIF_ID = 8902;

    public static final String ACTION_START = "com.example.mzdwidget.WEAR_RELAY_START";
    public static final String ACTION_STOP = "com.example.mzdwidget.WEAR_RELAY_STOP";

    /** 与手环快应用约定的消息 type */
    private static final String MSG_CMD = "mzd_cmd";
    private static final String MSG_RESULT = "mzd_result";

    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager nm;

    private NodeApi nodeApi;
    private MessageApi messageApi;
    private final AtomicReference<String> nodeIdRef = new AtomicReference<>(null);
    private String listeningNodeId = null;
    private String registeringNodeId = null; // 审核 M2：注册中标志，防异步窗口重复注册
    private OnMessageReceivedListener messageListener;

    private volatile boolean stopped = false;
    /** 串行执行指令的工作线程（Looper），避免并发下发 */
    private Handler worker;
    private volatile boolean workerReady = false;

    /** 指令去重与节流的临界区：SDK 可能把消息派发到不同线程，check-then-set 必须原子 */
    private final Object cmdGuard = new Object();
    private long lastCmdAt = 0L; // 仅在 cmdGuard 内访问
    /** 近期 reqId 集合（有上限），替代单个 lastReqId，防更早的重投漏网 */
    private final java.util.ArrayDeque<String> recentReqIds = new java.util.ArrayDeque<>();
    private static final int MAX_RECENT_REQ = 32;

    private final Runnable retryLoop = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            try { refreshNode(); } catch (Throwable t) { Log.w(TAG, "刷新节点异常：" + t); }
            main.postDelayed(this, 10_000L); // 每 10 秒重查节点/补注册监听
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannel();
        new Thread(() -> {
            Looper.prepare();
            worker = new Handler(Looper.myLooper());
            workerReady = true;
            Looper.loop();
        }, "mzd-wear-relay").start();
        try {
            nodeApi = Wearable.getNodeApi(getApplicationContext());
            messageApi = Wearable.getMessageApi(getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, "初始化互联 SDK 失败：" + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopped = true;
            unregisterListener();
            main.removeCallbacks(retryLoop);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        stopped = false;
        startAsForeground();
        main.removeCallbacks(retryLoop);
        main.post(retryLoop);
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("手环遥控已就绪")
                    .setContentText("等待手环指令（启动需长按3秒）")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            try {
                startForeground(NOTIF_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch (Exception e) {
                startForeground(NOTIF_ID, n);
            }
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("手环遥控已就绪")
                    .setContentText("等待手环指令（启动需长按3秒）")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "手环遥控",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("手环控车转发服务常驻通知");
        try { nm.createNotificationChannel(ch); } catch (Exception ignored) { }
    }

    /** 查询已连接穿戴节点 → 鉴权 → 注册消息监听（幂等） */
    private void refreshNode() {
        if (stopped || nodeApi == null || messageApi == null) return;
        nodeApi.getConnectedNodes().addOnSuccessListener(nodes -> {
            if (stopped) return;
            if (nodes == null || nodes.isEmpty()) {
                nodeIdRef.set(null);
                unregisterListener();
                return;
            }
            Node node = nodes.get(0);
            String id = node.id; // SDK 的 Node.id 是 public 字段，非 getter
            nodeIdRef.set(id);
            ensurePermission(id);
            ensureListen(id);
        }).addOnFailureListener(e -> Log.w(TAG, "查询穿戴节点失败：" + e.getMessage()));
    }

    private void ensurePermission(String id) {
        try {
            Wearable.getAuthApi(getApplicationContext())
                    .checkPermission(id, Permission.DEVICE_MANAGER)
                    .addOnSuccessListener(granted -> {
                        if (!granted) {
                            Wearable.getAuthApi(getApplicationContext())
                                    .requestPermission(id, Permission.DEVICE_MANAGER)
                                    .addOnFailureListener(e ->
                                            Log.w(TAG, "穿戴权限申请失败：" + e.getMessage()));
                        }
                    })
                    .addOnFailureListener(e -> Log.w(TAG, "穿戴权限检查失败：" + e.getMessage()));
        } catch (Throwable t) {
            Log.w(TAG, "鉴权异常：" + t);
        }
    }

    private void ensureListen(String id) {
        // 幂等：已监听或正在注册同一节点时直接返回，防 10 秒重试重复 addListener
        if (id.equals(listeningNodeId) && messageListener != null) return;
        if (id.equals(registeringNodeId)) return;
        unregisterListener();
        registeringNodeId = id;
        try {
            OnMessageReceivedListener listener = (fromNodeId, data) -> {
                if (stopped || data == null) return;
                String text = new String(data, StandardCharsets.UTF_8);
                onWearMessage(text);
            };
            messageApi.addListener(id, listener)
                    .addOnSuccessListener(v -> {
                        // 审核 M2：异步成功回调可能迟到——节点已切换则立即反注册，
                        // 避免 nodeIdRef=B 却监听 A 的错配
                        if (stopped || !id.equals(nodeIdRef.get())) {
                            try { messageApi.removeListener(id); } catch (Throwable ignored) { }
                            return;
                        }
                        registeringNodeId = null;
                        listeningNodeId = id;
                        messageListener = listener;
                        CtrlLog.i(TAG, "【手环】已连接并监听穿戴节点");
                    })
                    .addOnFailureListener(e -> {
                        if (id.equals(registeringNodeId)) registeringNodeId = null;
                        Log.w(TAG, "注册手环监听失败：" + e.getMessage());
                    });
        } catch (Throwable t) {
            registeringNodeId = null;
            Log.w(TAG, "注册监听异常：" + t);
        }
    }

    private void unregisterListener() {
        if (messageApi != null && listeningNodeId != null && messageListener != null) {
            try { messageApi.removeListener(listeningNodeId); } catch (Throwable ignored) { }
        }
        listeningNodeId = null;
        registeringNodeId = null;
        messageListener = null;
    }

    /** 解析手环消息；只处理白名单指令，其余忽略 */
    private void onWearMessage(String text) {
        if (stopped) return; // v1.9.25 审核：STOP 后不执行任何在途指令
        final String action;
        final String reqId;
        try {
            JSONObject o = new JSONObject(text);
            if (!MSG_CMD.equals(o.optString("type"))) return;
            String a = o.optString("action");
            boolean isStart = Api.CMD_START.equals(a);
            if (!Api.CMD_UNLOCK.equals(a) && !Api.CMD_LOCK.equals(a) && !isStart) {
                CtrlLog.w(TAG, "【手环】拒绝非白名单指令 action=" + a);
                return;
            }
            // v1.9.26：启动需手环端真实长按 3 秒（本地计时后带 holdMs=3000 上报）。
            // 短按/伪造消息不带该字段一律拒绝，避免误碰着车。
            if (isStart && o.optInt("holdMs", 0) < 3000) {
                CtrlLog.w(TAG, "【手环】拒绝未确认长按的启动指令");
                return;
            }
            action = a;
            reqId = o.optString("reqId", "");
        } catch (Exception e) {
            Log.w(TAG, "手环消息解析失败：" + e.getMessage());
            return;
        }

        // 未登录直接回失败，不发指令
        if (!Prefs.isLoggedIn(getApplicationContext())) {
            reply(reqId, action, false, "未登录");
            return;
        }
        // reqId 去重 + 3 秒节流，check-then-set 放同一临界区，防并发消息绕过
        final String finalAction = action;
        final String finalReqId = reqId == null ? "" : reqId;
        synchronized (cmdGuard) {
            long now = System.currentTimeMillis();
            if (!finalReqId.isEmpty() && recentReqIds.contains(finalReqId)) {
                // 重投：回执让手环立即刷新，不再等 8 秒超时
                reply(finalReqId, finalAction, true, "重复请求已忽略");
                return;
            }
            if (now - lastCmdAt < 3000L) {
                reply(finalReqId, finalAction, false, "操作过快");
                return;
            }
            lastCmdAt = now;
            if (!finalReqId.isEmpty()) {
                recentReqIds.addLast(finalReqId);
                while (recentReqIds.size() > MAX_RECENT_REQ) recentReqIds.removeFirst();
            }
        }
        postToWorker(() -> executeCommand(finalAction, finalReqId), finalReqId, finalAction);
    }

    private void postToWorker(Runnable r, String reqId, String action) {
        // worker 未就绪（服务刚起）时回执，避免手环干等 8 秒超时
        if (workerReady && worker != null) {
            if (!worker.post(r)) reply(reqId, action, false, "服务繁忙");
        } else {
            Log.w(TAG, "指令丢弃：转发线程未就绪");
            reply(reqId, action, false, "服务启动中，请重试");
        }
    }

    /** 在工作线程执行真实指令，复用 CarActions 统一入口 */
    private void executeCommand(String action, String reqId) {
        if (Api.CMD_START.equals(action)) {
            // 长按启动流程可能持续数分钟（状态确认式轮询），先回「已开始」避免手环 8 秒超时；
            // 流程结束后再以同一 reqId 回最终结果，手环端仍认这个 reqId。
            CtrlLog.i(TAG, "【手环】长按3秒触发一键启动，复用 smartStartAndUnlock");
            reply(reqId, action, true, "启动流程已开始");
            CarActions.smartStartAndUnlock(getApplicationContext(), (ok, message) -> {
                String m = message == null ? "" : message;
                if (m.length() > 24) m = m.substring(0, 24);
                CtrlLog.i(TAG, "【手环】一键启动结束 ok=" + ok + " " + m);
                reply(reqId, action, ok, m);
            });
            return;
        }
        boolean ok = false;
        String err = "";
        try {
            if (Api.CMD_UNLOCK.equals(action)) {
                CarActions.send(getApplicationContext(), Api.CMD_UNLOCK, -1, "手环遥控解锁");
            } else {
                CarActions.send(getApplicationContext(), Api.CMD_LOCK, -1, "手环遥控上锁");
            }
            ok = true;
        } catch (Exception e) {
            err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (err.length() > 80) err = err.substring(0, 80);
            CtrlLog.e(TAG, "【手环】指令失败 action=" + action + "：" + err);
        }
        reply(reqId, action, ok, ok ? "已发送" : err);
    }

    /** 把结果回传手环（JSON），在主线程调用 SDK 避免线程问题 */
    private void reply(String reqId, String action, boolean ok, String msg) {
        String id = nodeIdRef.get();
        if (id == null || messageApi == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("type", MSG_RESULT);
            o.put("reqId", reqId == null ? "" : reqId);
            o.put("action", action);
            o.put("ok", ok);
            o.put("msg", msg);
            final byte[] bytes = o.toString().getBytes(StandardCharsets.UTF_8);
            main.post(() -> messageApi.sendMessage(id, bytes)
                    .addOnFailureListener(e -> Log.w(TAG, "回传手环失败：" + e.getMessage())));
        } catch (Exception e) {
            Log.w(TAG, "构造回传消息失败：" + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        stopped = true;
        unregisterListener();
        main.removeCallbacks(retryLoop);
        if (worker != null) worker.post(() -> {
            try { Looper.myLooper().quit(); } catch (Throwable ignored) { }
        });
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
