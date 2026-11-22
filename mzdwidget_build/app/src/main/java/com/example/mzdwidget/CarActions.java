package com.example.mzdwidget;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 控车动作封装，供桌面小组件与 BLE 前台服务共用。
 * 所有方法都在调用线程执行网络请求，请勿在主线程直接调用。
 *
 * 指令间隔规则：10 秒窗口只由 BleScanService 内部维护，仅约束 BLE 自动指令；
 * 桌面按钮与一键启动属于显式用户操作，不受该窗口限制。
 */
public final class CarActions {

    private static final String TAG = "MZDCtrlLog";
    private static final AtomicBoolean SMART_RUNNING = new AtomicBoolean(false);

    /* ---------- 一键启动：状态确认式三步流程（v1.8） ---------- */
    /** 一键启动轮询节拍：3 秒（真实请求间隔由全局缓存硬节流保证） */
    private static final long SMART_POLL_MS = 3000L;
    /** 步骤1：预锁车后每轮等待 20 秒，最多重发 2 次（用户未指定，取保守值防死循环） */
    private static final long SMART_LOCK_RETRY_MS = 20000L;
    private static final int SMART_LOCK_MAX_RETRY = 2;
    /** 步骤2：30 秒未确认已启动即重发启动（用户规则），最多重发 3 次 */
    private static final long SMART_START_RETRY_MS = 30000L;
    private static final int SMART_START_MAX_RETRY = 3;
    /**
     * 步骤3：20 秒未确认已解锁即重发解锁，最多重发 3 次。
     * v1.9.7 实测校准：解锁指令发出后，车况 lock 字段平均 7.25s、峰值 8.4s 才反映变化
     * （车况约 10s 台阶上报 + 服务器回源约 1.17s），叠加每轮查询开销后原 15s 偏紧、
     * 易在车其实已解锁时误判并重发。放宽到 20s 与步骤1/桌面快刷窗口一致，留足余量。
     */
    private static final long SMART_UNLOCK_RETRY_MS = 20000L;
    private static final int SMART_UNLOCK_MAX_RETRY = 3;

    /** 车况命中判定：注意 Boolean 可能为 null（查询失败/字段缺失），null 一律视为「未确认」继续轮询 */
    private interface StateMatch { boolean ok(Api.CarState s); }
    /** 超时重发回调：由调用方发指令，异常直接向上抛 */
    private interface Resend { void send(int attempt) throws Exception; }

    private CarActions() {}

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    /**
     * 统一发送入口。10 秒间隔只约束 BLE 服务内部的自动指令，这里不做任何全局节流。
     * 每一次指令发出都写 log：记录具体指令、boot_time、触发原因、车号、时间；
     * 成功/失败各一条。token 不写日志（只记 carid 便于核对）。
     */
    public static void send(Context c, String cmd, int bootTime, String reason) throws Exception {
        String carid = Prefs.carid(c);
        long at = System.currentTimeMillis();
        CtrlLog.i(TAG,"【指令发出】cmd=" + cmd + " boot_time=" + bootTime
                + " 触发=" + (reason == null ? "未标注" : reason)
                + " carid=" + carid + " at=" + at);
        try {
            Api.control(Prefs.token(c), carid, cmd, bootTime);
            CtrlLog.i(TAG,"【指令成功】cmd=" + cmd + " 触发=" + reason);
            // v1.9.17：仅解锁/上锁登记提示音期望，由 CarStateCache 快刷确认目标门态后响一次。
            // 指令真正被服务器接受才登记，失败不登记；start/stall/search 不参与提示音。
            CarStateCache.get(c).expectCommand(cmd);
        } catch (Exception e) {
            CtrlLog.e(TAG,"【指令失败】cmd=" + cmd + " 触发=" + reason + " 原因=" + shortErr(e));
            throw e;
        }
    }

    /** 兼容旧调用：未标注触发原因 */
    public static void send(Context c, String cmd, int bootTime) throws Exception {
        send(c, cmd, bootTime, "手动/未标注");
    }

    /** 发送启动指令（boot_time=15） */
    public static void start(Context c, String reason) throws Exception {
        send(c, Api.CMD_START, 15, reason);
    }

    public static void start(Context c) throws Exception { start(c, "手动启动"); }

    /** 发送解锁指令 */
    public static void unlock(Context c, String reason) throws Exception {
        send(c, Api.CMD_UNLOCK, -1, reason);
    }

    public static void unlock(Context c) throws Exception { unlock(c, "手动解锁"); }

    /** 发送上锁指令 */
    public static void lock(Context c, String reason) throws Exception {
        send(c, Api.CMD_LOCK, -1, reason);
    }

    public static void lock(Context c) throws Exception { lock(c, "手动上锁"); }

    /**
     * 一键启动：状态确认式三步流程（v1.8，取代旧的固定 15 秒盲等）。
     *
     * 步骤1 预锁车：查车况，已锁→直接下一步；未锁→发 lock→每 3 秒查直到确认已锁。
     * 步骤2 启动：发 start(boot_time=15)→每 3 秒查直到确认已启动；
     *            30 秒未确认则重发 start，再查，直到确认（重发有上限防死循环）。
     * 步骤3 解锁：发 unlock→每 3 秒查直到确认已解锁；
     *            20 秒未确认则重发 unlock，再查，直到确认（重发有上限防死循环）。
     *
     * 全程每 3 秒轮询车况（真实请求间隔由全局缓存 3 秒硬节流保证），实时反映车况变化。
     * 车况字段 null（查询失败/业务码异常）
     * 一律视为「未确认」，继续轮询，绝不把 null 当成目标状态达成。
     */
    public static void smartStartAndUnlock(Context c, Callback cb) {
        if (!Prefs.isLoggedIn(c)) {
            if (cb != null) cb.onResult(false, "请先在 App 登录或填入 Token");
            return;
        }
        if (!SMART_RUNNING.compareAndSet(false, true)) {
            if (cb != null) cb.onResult(false, "启动流程进行中，请稍候");
            return;
        }
        try {
            // v1.9.21：前置清理移到 CAS 成功之后。原先在 ActionService/WidgetActions 里于 CAS 之前
            // 就 clear/cancel，重复点击时 T2 虽被 CAS 拒绝，却已清掉在途 T1 的解锁去重标记与兜底闹钟。
            // 现在只有真正抢到执行的这一次才清理，杜绝干扰在途流程。
            CarWidget.clearSmartUnlockDone(c);
            CarWidget.cancelSmartUnlockFallback(c);
            // v1.8.5：不再启动快刷 tick（它会额外查网）。桌面更新由 query() 复用结果完成。
            // v1.9.2：首查失败先重试一次，第二轮仍失败才终止（绝不盲目发锁/启动/解锁）
            Api.CarState s0 = queryWithRetry(c, "步骤1-查询锁车状态");
            if (s0 == null) {
                CarWidget.cancelSmartUnlockFallback(c);
                if (cb != null) cb.onResult(false, "车况连续两轮查询失败，一键启动已终止（未执行任何指令）");
                return;
            }
            // v1.9.12：门态未知(unlocked==null)绝不能当成"未锁"去下发 lock。
            // statusUnavailable 只拦"started/unlocked 全缺"，若 started 有值而门态缺失，
            // isLocked(null) 会返回 false 误判为未锁 → 误发预锁车指令，违反 null 不下发底线。
            if (s0.unlocked == null) {
                CarWidget.cancelSmartUnlockFallback(c);
                CtrlLog.e(TAG,"【一键启动】步骤1：门状态未知，终止（未执行任何指令）");
                if (cb != null) cb.onResult(false, "车门状态未知，一键启动已终止（未执行任何指令）");
                return;
            }
            if (isLocked(s0)) {
                CtrlLog.i(TAG,"【一键启动】步骤1：车已锁，直接进入启动");
            } else {
                CtrlLog.i(TAG,"【一键启动】步骤1：车未锁，先发锁车指令");
                send(c, Api.CMD_LOCK, -1, "一键启动步骤1：预锁车");
                Api.CarState locked = poll(c, "步骤1-等待已锁",
                        SMART_LOCK_RETRY_MS, SMART_LOCK_MAX_RETRY, CarActions::isLocked,
                        a -> send(c, Api.CMD_LOCK, -1, "一键启动步骤1：重发锁车(第" + a + "次)"));
                if (locked == null) {
                    CarWidget.cancelSmartUnlockFallback(c); // 清理可能残留的旧兜底闹钟
                    if (cb != null) cb.onResult(false, "预锁车未确认成功，已终止");
                    return;
                }
            }

            // 步骤2：发送启动 → 轮询到「已启动」，30 秒未确认重发
            send(c, Api.CMD_START, 15, "一键启动步骤2：发送启动");
            Api.CarState started = poll(c, "步骤2-等待已启动",
                    SMART_START_RETRY_MS, SMART_START_MAX_RETRY, CarActions::isStarted,
                    a -> send(c, Api.CMD_START, 15, "一键启动步骤2：重发启动(第" + a + "次)"));
            if (started == null) {
                CarWidget.cancelSmartUnlockFallback(c); // 未确认启动，绝不能让兜底补发解锁
                if (cb != null) cb.onResult(false, "启动后未确认发动机已启动，已终止（未解锁）");
                return;
            }

            // 步骤3：发送解锁 → 轮询到「已解锁」，20 秒未确认重发（实测状态回显峰值约 8.4s）
            send(c, Api.CMD_UNLOCK, -1, "一键启动步骤3：发送解锁");
            Api.CarState unlocked = poll(c, "步骤3-等待已解锁",
                    SMART_UNLOCK_RETRY_MS, SMART_UNLOCK_MAX_RETRY, CarActions::isUnlocked,
                    a -> send(c, Api.CMD_UNLOCK, -1, "一键启动步骤3：重发解锁(第" + a + "次)"));
            if (unlocked == null) {
                CarWidget.cancelSmartUnlockFallback(c);
                if (cb != null) cb.onResult(false, "解锁后未确认车门已解锁，流程结束");
                return;
            }
            CarWidget.markSmartUnlockDone(c); // 通知兜底闹钟：本次已确认解锁，无需补发
            if (cb != null) cb.onResult(true, "一键启动完成：已锁→已启动→已解锁");
        } catch (Exception e) {
            CarWidget.cancelSmartUnlockFallback(c); // 指令本身失败，取消兜底解锁
            if (cb != null) cb.onResult(false, "一键启动失败：" + shortErr(e));
        } finally {
            SMART_RUNNING.set(false);
        }
    }

    /**
     * 通过全局缓存查询一次车况：触发快刷并等待本轮结果，绝不自己发 /car/status。
     * 任何异常/超时都返回 null 视为「未确认」，由调用方继续轮询。
     * 桌面刷新由缓存回调 onGlobalStatus 负责，这里不再单独重绘。
     */
    private static Api.CarState query(Context c, String phase) {
        Api.CarState s = CarStateCache.get(c).refreshAndWait(c);
        if (s != null) {
            CtrlLog.i(TAG,"【车况查询】" + phase + " started=" + (s.started == null ? "未知" : s.started)
                    + " unlocked=" + (s.unlocked == null ? "未知" : s.unlocked));
        } else {
            CtrlLog.w(TAG,"【车况查询】" + phase + " 未确认（失败或超时）");
        }
        return s;
    }

    /**
     * v1.9.2：一键启动判定专用——首查失败再查一次，第二轮仍失败才返回 null。
     * 应对单次网络抖动，不因一次查询失败就终止整个流程。
     * 两次查询经全局缓存 refreshAndWait，自动受 MIN_GAP_MS=3 秒最小间隔约束，无需额外 sleep。
     * 返回非 null 即代表车况可用（已排除字段全缺），调用方可直接判定。
     */
    private static Api.CarState queryWithRetry(Context c, String phase) {
        Api.CarState s = query(c, phase);
        if (!statusUnavailable(s)) return s;
        CtrlLog.w(TAG,"【一键启动】" + phase + " 首查失败，进入第二轮查询");
        s = query(c, phase + "[第二轮]");
        if (statusUnavailable(s)) {
            CtrlLog.e(TAG,"【一键启动】" + phase + " 第二轮查询仍失败，终止后续执行");
            return null;
        }
        return s;
    }

    /**
     * 每 SMART_POLL_MS（3 秒）查一次车况直到 match 命中；单轮超时则调 onTimeout 重发指令并重新计时。
     * 返回命中时的车况；超过 maxRetry 次重发仍未命中返回 null（防死循环）。
     */
    private static Api.CarState poll(Context c, String phase, long retryMs, int maxRetry,
                                     StateMatch match, Resend onTimeout) throws Exception {
        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            long roundStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - roundStart < retryMs) {
                // v1.9.2：每轮首查失败先重试一次，第二轮仍失败才终止（不重发指令、不进下一步）
                Api.CarState s = queryWithRetry(c, phase + "[第" + (attempt + 1) + "轮]");
                if (s == null) {
                    CtrlLog.e(TAG,"【一键启动】" + phase + " 连续两轮车况查询失败，终止后续执行");
                    return null;
                }
                if (match.ok(s)) {
                    CtrlLog.i(TAG,"【一键启动】" + phase + " 已确认（第" + (attempt + 1) + "轮）");
                    return s;
                }
                try {
                    Thread.sleep(SMART_POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new Exception("流程被中断");
                }
            }
            if (attempt < maxRetry) {
                CtrlLog.w(TAG,"【一键启动】" + phase + " 超过 " + (retryMs / 1000)
                        + " 秒未确认，重发指令（第" + (attempt + 1) + "次）");
                onTimeout.send(attempt + 1);
            }
        }
        CtrlLog.e(TAG,"【一键启动】" + phase + " 达重发上限仍未确认，放弃");
        return null;
    }

    private static boolean isLocked(Api.CarState s) {
        return s != null && Boolean.FALSE.equals(s.unlocked);
    }

    private static boolean isUnlocked(Api.CarState s) {
        return s != null && Boolean.TRUE.equals(s.unlocked);
    }

    private static boolean isStarted(Api.CarState s) {
        return s != null && Boolean.TRUE.equals(s.started);
    }

    /**
     * v1.9.1：车况在线查询不可用的统一判定。
     * true = 网络失败/超时返回 null，或 HTTP 200 但 started/unlocked 字段全缺（业务异常）。
     * 手动按钮不依赖此判定（照常发指令）；BLE 判定与一键启动判定遇到不可用一律终止后续执行。
     */
    private static boolean statusUnavailable(Api.CarState s) {
        return s == null || (s.started == null && s.unlocked == null);
    }

    /**
     * BLE 扫描到目标设备（无需连接）。
     *
     * 解锁闩锁：本次「靠近」一旦成功解锁（或确认车已解锁），在下次「离开」
     * 真正发出锁车命令 / 确认车已锁之前，不再发任何解锁指令——避免开车时
     * 蓝牙一直被扫到而反复下发解锁。闩锁落盘，服务被重建也不会丢。
     */
    public static void onBlePresent(Context c, Callback cb) {
        if (!Prefs.isLoggedIn(c)) {
            // v1.9.6：必须回调 false 让 BleScanService 复位 present。否则 BLE 开关仍开但凭证已清时，
            // 服务侧已 setPresent(true) 却等不到回调，present 卡在 true 直到看门狗判离开，
            // 期间占用 10 秒指令窗口且无任何日志。
            if (cb != null) cb.onResult(false, "未登录，跳过靠近解锁");
            return;
        }
        boolean needRefresh = false;
        try {
            // 经全局缓存触发快刷并等待本轮车况（失败/超时返回 null）
            Api.CarState s = CarStateCache.get(c).refreshAndWait(c);
            // v1.9.1：BLE 状态变更是判定类调用——车况在线查询失败/字段全缺一律忽略后续执行，
            // 不发解锁、不置闩锁；等下次看门狗用有效车况再判（绝不凭「靠近务必开」盲开）。
            if (statusUnavailable(s)) {
                // v1.9.3：返回 ok=false，让 BleScanService 复位 present——否则 present 卡在 true，
                // 设备一直在旁边时看门狗只判离开、不重判靠近，本次跳过后将永不再尝试解锁。
                // 这与「确定性跳过」（已解锁/发动机启动，仍返回 ok=true）语义不同：此处需下次重试。
                CtrlLog.w(TAG,"【BLE靠近】车况在线查询失败，本次跳过并复位present待重试");
                if (cb != null) cb.onResult(false, "车况查询失败，本次跳过待重试");
                return;
            }

            // 【最高优先级·安全】发动机状态未知(started==null)绝不下发解锁。
            // v1.9.6：Api.getStatus 在响应缺 engine 字段时 started 保持 null，
            // 而车辆实际可能正在行驶（很多车启动后自动落锁 → unlocked=false）。
            // 旧逻辑只拦 started==true，null 会穿透到下面的「已锁→解锁」分支，
            // 造成行驶中开门。故要求 started 明确为 false 才允许继续；null 按不可用跳过待重试。
            if (s == null || s.started == null) {
                CtrlLog.w(TAG,"【BLE靠近】发动机状态未知(started为空)，跳过靠近解锁待重试");
                if (cb != null) cb.onResult(false, "发动机状态未知，本次跳过待重试");
                return;
            }
            // 【最高优先级·安全】发动机已启动 = 行驶/怠速中，绝不自动解锁。
            // 场景：行驶中 BLE 瞬时漏扫 → 看门狗判离开(present=false) → onBleAbsent
            // 查到 started=true 保持现状 → 下一秒又扫到目标(present 翻转 true) → 触发本方法。
            // 此时若车是落锁状态（很多车启动后自动落锁），v1.8.3 的「车已锁就无条件解锁」
            // 会在行驶中开门，非常危险。故先判 started，启动态一律跳过且不置闩锁。
            if (Boolean.TRUE.equals(s.started)) {
                if (cb != null) cb.onResult(true, "发动机已启动，跳过靠近解锁（行驶中不开门）");
                return;
            }

            // 车已明确解锁：置闩锁并跳过，避免一直在旁边时反复下发解锁。
            if (Boolean.TRUE.equals(s.unlocked)) {
                Prefs.setBleUnlockLatched(c, true);
                if (cb != null) cb.onResult(true, "车辆已解锁，本次靠近不再重复解锁");
                return;
            }
            // 车明确已锁且明确未启动（走到这里 started 必为 false）：正常靠近解锁场景，无视旧闩锁。
            // 保留 v1.8.3 对「行驶中离开→熄火回家闩锁卡死」的修复——上面已保证 started==false，
            // 可安全解锁。
            if (Boolean.FALSE.equals(s.unlocked)) {
                Prefs.setBleUnlockLatched(c, false); // 先清掉可能残留的旧闩锁
                unlock(c, "BLE 靠近：扫到目标自动解锁");
                Prefs.setBleUnlockLatched(c, true);  // 解锁指令发送成功才上闩
                needRefresh = true;
                if (cb != null) cb.onResult(true, "扫描到蓝牙设备，已自动解锁（本次靠近仅一次）");
                return;
            }
            // v1.9.3：此处 s 可用但 started/unlocked 组合无法判定（理论上被 statusUnavailable 拦截）。
            // 同样返回 ok=false 复位 present 待重试，避免 present 卡死不再解锁。
            CtrlLog.w(TAG,"【BLE靠近】车况字段不足以判定，跳过并复位present待重试");
            if (cb != null) cb.onResult(false, "车况字段不足，跳过待重试");
        } catch (Exception e) {
            // 解锁失败不置闩锁，后续扫描会重试
            if (cb != null) cb.onResult(false, "BLE 扫描到设备但解锁失败：" + shortErr(e));
        } finally {
            if (needRefresh) CarWidget.requestFastRefresh(c);
        }
    }

    /**
     * BLE 连续扫不到目标设备（绝不盲锁）：
     * 车况不可用/unlocked!=true → 跳过上锁（明确已锁时复位闩锁）；
     * unlocked==true 且已启动 → 保持现状（闩锁不动，车还在开）；
     * unlocked==true 且未启动 → 发上锁并复位闩锁。
     *
     * 只有「明确车门未锁」才允许发上锁；只有「发出锁车命令」或「确认车辆已锁」才解锁闩锁，
     * 下一次靠近才允许再解锁。
     */
    public static void onBleAbsent(Context c, Callback cb) {
        if (!Prefs.isLoggedIn(c)) {
            if (cb != null) cb.onResult(false, "未登录，跳过自动上锁");
            return;
        }
        boolean needRefresh = false;
        try {
            // 经全局缓存触发快刷并等待本轮车况；失败/超时返回 null，下面按「绝不盲锁」跳过
            Api.CarState s = CarStateCache.get(c).refreshAndWait(c);
            // 【安全·绝不盲锁】只有拿到明确的「车门未锁(unlocked==true)」才允许发上锁。
            // v1.9.3 收紧：原来仅拦 started/unlocked 全 null，若出现 started=false 但 unlocked=null
            // （字段部分缺失）会一路走到 blind lock。现改为任何 unlocked!=true 的情况一律跳过——
            // 无法确认门是开着的，就不能排除有人在车里，绝不自动落锁。
            if (s == null || !Boolean.TRUE.equals(s.unlocked)) {
                // 明确已锁(unlocked==false)：复位闩锁，允许下次靠近再解锁（保留 v1.8.3 闩锁卡死修复）。
                // 其余情况(null/查询失败)：不动闩锁，保持现状等下次有效车况。
                if (s != null && Boolean.FALSE.equals(s.unlocked)) {
                    Prefs.setBleUnlockLatched(c, false);
                    if (cb != null) cb.onResult(true, "蓝牙设备已离开，车辆已锁，跳过上锁指令");
                } else if (cb != null) {
                    cb.onResult(true, "车况未明确车门未锁，跳过自动上锁（绝不盲锁，等下次判定）");
                }
                return;
            }
            if (Boolean.TRUE.equals(s.started)) {
                if (cb != null) cb.onResult(true, "蓝牙设备已离开，但发动机已启动，保持现状");
                return; // 没发锁车命令 → 闩锁保持，避免行驶中重复解锁
            }
            // v1.9.6：started==null（响应缺 engine 字段）时发动机状态未知，不能假定已熄火。
            // 若此时 unlocked==true（门开着、可能有人在车内/刚熄火未落锁），盲发上锁会把人锁在车里。
            // 与 onBlePresent 对称：上锁同样要求「明确未启动」，状态未知一律跳过保持现状。
            if (s.started == null) {
                if (cb != null) cb.onResult(true, "发动机状态未知，跳过自动上锁（保持现状）");
                return; // 没发锁车命令 → 闩锁保持
            }
            // 走到这里必然是 unlocked==true 且 started==false：车门开着、发动机明确未启动，安全上锁。
            lock(c, "BLE 离开：判定离开自动上锁");
            Prefs.setBleUnlockLatched(c, false); // 成功发出锁车命令，复位闩锁
            needRefresh = true;
            if (cb != null) cb.onResult(true, "蓝牙设备已离开且车辆未启动，已自动上锁");
        } catch (Exception e) {
            if (cb != null) cb.onResult(false, "BLE 离开处理失败：" + shortErr(e));
        } finally {
            if (needRefresh) CarWidget.requestFastRefresh(c);
        }
    }

    /** 原 toast 提示改为写 log（本项目已移除所有 Toast） */
    public static void logMsg(final String msg) {
        CtrlLog.i(TAG,msg);
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }
}
