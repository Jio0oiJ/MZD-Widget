package com.example.mzdwidget;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * 全局车况缓存（v1.9）：所有 /car/status 网络请求统一由本类的单一工作线程发出，
 * 其它任何模块都只读缓存，不再各自查网。
 *
 * 两种模式：
 *   标准 —— 每 10 分钟查一次（维持缓存鲜活，最省请求）
 *   快刷 —— 任意触发后 20 秒窗口内每 3 秒查一次，窗口静默后自动退回标准
 * 硬约束：任意两次真实请求间隔 >= 3 秒（工作线程串行 + MIN_GAP 双保险）。
 * v1.9.18 起：慢刷读到电瓶电压(bat) < 11.8V 时自动触发同一套 20 秒快刷窗口做复核，
 * 窗口末仍明确低于阈值才两声报警并弹警示窗；快刷没拿到有效电压则保守取消。
 *
 * 接口分工：
 *   refreshAndWait(c) —— 判定类用：触发快刷并等待本轮结果，返回本轮车况（失败返回 null）
 *   trigger(c)        —— 手动/显示类用：触发快刷、不等待，结果出来自动刷新桌面
 *   snapshot()        —— 纯读缓存，绝不查网、绝不阻塞
 */
public final class CarStateCache {

    private static final String TAG = "MZDCtrlLog";
    private static final long STANDARD_MS = 10 * 60 * 1000L;   // 标准：10 分钟
    private static final long FAST_MS = 3000L;                  // 快刷：3 秒
    private static final long FAST_WINDOW_MS = 20000L;         // 快刷窗口：20 秒无触发退回
    private static final long MIN_GAP_MS = 3000L;              // 任意两次请求最小间隔
    private static final long WAIT_TIMEOUT_MS = 20000L;        // 判定等待本轮结果的上限
    // v1.9.18：电瓶电压低电压监控。慢刷读到 bat < 11.8V 触发 20 秒快刷复核，
    // 快刷窗口末仍有明确低于阈值的电压才报警；快刷没拿到有效电压则保守取消。
    private static final double LOW_VOLTAGE_THRESHOLD = 11.8;

    private static volatile CarStateCache INSTANCE;

    private final Object lock = new Object();
    private Context app;
    private Thread worker;
    private volatile boolean started;

    private Api.CarState cached;    // 最近一次成功查询的车况（显示用）
    private String lastError;       // 最近一次失败原因（显示用）
    private long lastQueryAt;       // 上次请求完成时刻（elapsedRealtime）
    private long lastTriggerAt;     // 上次触发快刷时刻
    private long reqSeq;            // 已提交请求序号
    private long doneSeq;           // 已完成请求序号
    private long successSeq;        // 最近一次成功完成的请求序号
    private volatile long epoch;    // 凭证/车辆代次：invalidate 时递增，在途旧请求据此丢弃结果

    // v1.9.17：提示音改为「指令期望确认」驱动。
    // 旧版只比较前后两轮缓存门态，冷启动缓存为空、切号、或指令前后门态恰好相同都不响；
    // 发动机启动态下这些问题更明显。现在由指令入口登记期望，快刷一旦确认目标门态即响，
    // 且一条指令只响一次；超时未确认则不响并写日志说明原因。
    private static final long BEEP_EXPECT_TIMEOUT_MS = 60000L;
    private String beepExpectCmd;   // Api.CMD_UNLOCK / Api.CMD_LOCK，null=无待确认提示音
    private long beepExpectAt;      // elapsedRealtime 登记时刻
    private long beepExpectEpoch;   // 登记时代次，切号后作废

    // v1.9.18：低电压快刷复核状态（仅工作线程改，锁内读写）
    private boolean lowVoltagePending;      // 是否正等待快刷复核
    private long lowVoltageTriggerAt;       // 触发复核时刻，用于判断 20 秒窗口结束
    private Double lowVoltageLast;          // 复核窗口内最新一次有效电压
    private boolean lowVoltageHasFastResult;// 快刷窗口内至少拿到一次有效电压

    private CarStateCache() {}

    public static CarStateCache get(Context c) {
        CarStateCache x = INSTANCE;
        if (x == null) {
            synchronized (CarStateCache.class) {
                x = INSTANCE;
                if (x == null) {
                    x = new CarStateCache();
                    INSTANCE = x;
                }
            }
        }
        x.ensureStarted(c);
        return x;
    }

    private void ensureStarted(Context c) {
        if (c != null && app == null) app = c.getApplicationContext();
        if (started) return;
        synchronized (lock) {
            if (started) return;
            started = true;
            worker = new Thread(this::run, "mzd-state-cache");
            worker.setDaemon(true);
            worker.start();
            Log.i(TAG, "【全局车况】缓存线程启动：标准 10 分钟 / 快刷 3 秒 / 最小间隔 3 秒");
        }
    }

    /** 判定类：触发快刷并等待本轮结果。成功返回本轮车况，失败/超时返回 null（按未确认处理）。 */
    public Api.CarState refreshAndWait(Context c) {
        ensureStarted(c);
        long target;
        synchronized (lock) {
            lastTriggerAt = SystemClock.elapsedRealtime();
            target = ++reqSeq;
            lock.notifyAll();
        }
        long deadline = SystemClock.elapsedRealtime() + WAIT_TIMEOUT_MS;
        synchronized (lock) {
            while (doneSeq < target) {
                long remain = deadline - SystemClock.elapsedRealtime();
                if (remain <= 0) {
                    Log.w(TAG, "【全局车况】等待本轮结果超时（" + WAIT_TIMEOUT_MS / 1000 + " 秒）");
                    return null;
                }
                try {
                    lock.wait(remain);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            // 本轮必须成功才算数：绝不用上一次的旧缓存冒充本轮结果
            if (successSeq < target) return null;
            return cached == null ? null : copy(cached);
        }
    }

    /** 手动/显示类：触发快刷，不等待；结果出来后工作线程自动刷新桌面。 */
    public void trigger(Context c) {
        ensureStarted(c);
        synchronized (lock) {
            lastTriggerAt = SystemClock.elapsedRealtime();
            reqSeq++;
            lock.notifyAll();
        }
    }

    /** 纯读缓存，绝不查网、绝不阻塞。供桌面重绘/主界面显示使用。 */
    public Api.CarState snapshot() {
        synchronized (lock) {
            return cached == null ? null : copy(cached);
        }
    }

    public String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /**
     * v1.9.6：原子地同时取「缓存车况 + 最近一次错误」，供桌面刷新判定用。
     * cached 与 lastError 属同一状态机，分开两次加锁调用（snapshot()+lastError()）之间存在
     * 切号 invalidate 的窗口：取到旧账号 s 后 lastError 被清空，会把旧车况写盘冒充实时。
     * 一次加锁同取可消除该窗口。
     */
    public Snapshot snapshotWithError() {
        synchronized (lock) {
            return new Snapshot(cached == null ? null : copy(cached), lastError);
        }
    }

    public static final class Snapshot {
        public final Api.CarState state;
        public final String error;
        Snapshot(Api.CarState state, String error) {
            this.state = state;
            this.error = error;
        }
    }

    /**
     * 凭证/车辆变更（登录、登出、换 Token、Root 导入）时清空缓存，
     * 防止上一个账号/上一辆车的车况被 snapshot() 当作新账号的实时状态显示。
     * 清完后请求序号不动（仍保证任意两次请求 >=3 秒），下一轮查询成功后自动回填。
     */
    public void invalidate() {
        synchronized (lock) {
            cached = null;
            lastError = null;
            // v1.9.17：切号/换车时把待确认的提示音期望一并作废，防止上账号的指令在新账号车上响。
            beepExpectCmd = null;
            // v1.9.18：低电压复核状态同样作废，避免上一辆车的待复核电压弹到新账号上。
            lowVoltagePending = false;
            lowVoltageLast = null;
            lowVoltageHasFastResult = false;
            // v1.9.5：递增代次。已发出但仍在飞行中的旧凭证查询回来后，
            // 发现 epoch 已变，会把结果丢弃——绝不把上一个账号/车的车况写回来冒充实时。
            epoch++;
        }
    }

    /**
     * v1.9.17：指令成功发出后登记提示音期望。
     * 解锁期望 unlocked==true，上锁期望 unlocked==false；快刷首次确认到目标门态即响一次。
     * 仅对解锁/上锁登记，start/stall/search 等不响。重复登记以最后一次为准（符合"最后一次指令"直觉）。
     */
    public void expectCommand(String cmd) {
        if (cmd == null || (!Api.CMD_UNLOCK.equals(cmd) && !Api.CMD_LOCK.equals(cmd))) return;
        synchronized (lock) {
            beepExpectCmd = cmd;
            beepExpectAt = SystemClock.elapsedRealtime();
            beepExpectEpoch = epoch;
        }
        CtrlLog.i(TAG, "【提示音】已登记指令期望：" + (Api.CMD_UNLOCK.equals(cmd) ? "解锁" : "上锁")
                + "，等待快刷确认目标门态");
    }

    /** 工作线程主循环：按当前模式计算下一次查询时机，串行发请求。 */
    private void run() {
        while (started) {
            long myTarget;
            Double alarmVoltage = null;
            String windowLog = null;
            boolean fireAlarm = false;
            synchronized (lock) {
                while (true) {
                    long now = SystemClock.elapsedRealtime();
                    boolean fast = lastTriggerAt != 0L && (now - lastTriggerAt) < FAST_WINDOW_MS;

                    // v1.9.18：低电压快刷窗口结束的那一刻做复核判定。
                    // 只在「慢刷发现 <11.8V → 触发 20 秒快刷 → 窗口末仍有明确低电压」时报警。
                    // 快刷期间没有任何有效电压则保守取消，避免用单次异常值误报。
                    if (!fast && lowVoltagePending
                            && (now - lowVoltageTriggerAt) >= FAST_WINDOW_MS) {
                        Double v = lowVoltageLast;
                        boolean hasFast = lowVoltageHasFastResult;
                        lowVoltagePending = false;
                        lowVoltageLast = null;
                        lowVoltageHasFastResult = false;
                        if (hasFast && v != null && v < LOW_VOLTAGE_THRESHOLD) {
                            fireAlarm = true;
                            alarmVoltage = v;
                        } else {
                            windowLog = "【低电压】快刷复核结束，"
                                    + (hasFast && v != null
                                        ? "电压=" + v + "V（不低于 " + LOW_VOLTAGE_THRESHOLD + "V），取消报警"
                                        : "未取得有效电压，保守取消报警");
                        }
                        fast = false;
                    }

                    long period = fast ? FAST_MS : STANDARD_MS;
                    boolean pending = reqSeq > doneSeq;
                    // 有pending时最快也不能突破 3 秒；无pending时按当前模式周期
                    long base = lastQueryAt == 0L ? 0L : lastQueryAt + (pending ? MIN_GAP_MS : period);
                    long wait = base - now;
                    if (wait <= 0) break;
                    try {
                        lock.wait(Math.min(wait, 1000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!started) return;
                }
                myTarget = reqSeq; // 快照本轮要服务的最大请求序号
            }
            // 弹窗/播放声音放在锁外，避免音频与 Activity 启动阻塞车况工作线程。
            if (windowLog != null) CtrlLog.i(TAG, windowLog);
            if (fireAlarm && alarmVoltage != null) {
                CtrlLog.w(TAG, "【低电压】快刷复核确认电瓶电压 " + alarmVoltage + "V < "
                        + LOW_VOLTAGE_THRESHOLD + "V：两声报警 + 弹出警示窗");
                BeepHelper.playLock();
                LowVoltageAlerter.show(alarmVoltage);
            }
            queryOnce(myTarget);
        }
    }

    /** 真正发起一次 /car/status（仅本工作线程调用），完成后唤醒等待者并刷新桌面。 */
    private void queryOnce(long target) {
        Context c = app;
        // v1.9.6：代次与本次实际使用的 token/carid 必须在同一临界区快照，保证三者属于同一时刻。
        // 否则切号（写新 token → invalidate 递增 epoch）恰好夹在「读 epoch」与「读 token」之间时，
        // 会用新 token 查询却记着旧代次，回来后误判 stale 丢弃，白等一轮。
        final long myEpoch;
        final String usedToken, usedCarid;
        final boolean fastNow;
        synchronized (lock) {
            myEpoch = epoch;
            usedToken = (c != null && Prefs.isLoggedIn(c)) ? Prefs.token(c) : null;
            usedCarid = (c != null) ? Prefs.carid(c) : null;
            long now = SystemClock.elapsedRealtime();
            fastNow = lastTriggerAt != 0L && (now - lastTriggerAt) < FAST_WINDOW_MS;
        }
        Api.CarState s = null;
        String err = null;
        boolean ok = false;
        try {
            if (usedToken == null) {
                err = "未登录";
            } else {
                s = Api.getStatus(usedToken, usedCarid);
                ok = s != null;
            }
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m == null || m.isEmpty()) m = t.toString();
            err = m.length() > 40 ? m.substring(0, 40) : m;
        }

        boolean stale = false;
        synchronized (lock) {
            lastQueryAt = SystemClock.elapsedRealtime();
            stale = (myEpoch != epoch);
            if (stale) {
                // 凭证已切换：丢弃本轮结果。successSeq 不推进 → refreshAndWait 的等待者按「未确认」处理；
                // 但仍推进 doneSeq，避免等待者白等到超时。lastError 也不覆盖（切号已清空）。
                Log.w(TAG, "【全局车况】凭证已变更，丢弃在途旧结果（序号=" + target + "）");
                // v1.9.18：在途旧结果的电压不能参与新账号的低电压复核。
                lowVoltagePending = false;
                lowVoltageLast = null;
                lowVoltageHasFastResult = false;
            } else if (ok) {
                // v1.9.18：低电压实时监控。只在慢刷（非快刷窗口）发现 bat<11.8V 时发起复核，
                // 快刷窗口内只记录最新电压，等到窗口末由 run() 统一判定，避免单次抖动误报。
                Double volt = s.batteryVoltage;
                if (lowVoltagePending) {
                    if (fastNow && volt != null) {
                        lowVoltageLast = volt;
                        lowVoltageHasFastResult = true;
                    }
                } else if (!fastNow && volt != null && volt < LOW_VOLTAGE_THRESHOLD) {
                    lowVoltagePending = true;
                    lowVoltageTriggerAt = SystemClock.elapsedRealtime();
                    lowVoltageLast = volt;
                    lowVoltageHasFastResult = false;
                    // 复用既有快刷窗口：20 秒内每 3 秒一查，与其它触发源并存不冲突。
                    lastTriggerAt = lowVoltageTriggerAt;
                    CtrlLog.i(TAG, "【低电压】慢刷读到电瓶电压 " + volt + "V < "
                            + LOW_VOLTAGE_THRESHOLD + "V，触发 20 秒快刷复核");
                }
                // v1.9.17：提示音由「指令期望」驱动——指令成功发出后 expectCommand() 登记目标门态，
                // 快刷首次读到目标门态即响一次。修复 v1.9.16 只比较前后两轮缓存的缺陷：
                // 冷启动/切号后 cached=null、指令前后门态恰好相同（例如启动态下门态未即时回显）、
                // 或中间有其它查询先更新了缓存，都会让旧逻辑静默不响。当前口径覆盖：
                //   手动按钮 / BLE 靠近自动解锁 / 离开自动上锁 / 一键启动各步（都经 CarActions.send）。
                // 只在期望存在、代次未变、门态明确命中目标且未超时（60 秒）时响；响完立即清空期望。
                // 门态仍为旧值或未知时保留期望继续等下一轮快刷，直到命中或超时。
                if (beepExpectCmd != null && beepExpectEpoch == myEpoch && s.unlocked != null) {
                    String cmd = beepExpectCmd;
                    boolean wantUnlock = Api.CMD_UNLOCK.equals(cmd);
                    boolean hit = wantUnlock ? Boolean.TRUE.equals(s.unlocked)
                            : Boolean.FALSE.equals(s.unlocked);
                    if (hit) {
                        beepExpectCmd = null;
                        CtrlLog.i(TAG, "【提示音】指令快刷确认成功：" + (wantUnlock ? "解锁→响一声" : "上锁→响两声")
                                + " 门态=" + s.unlocked);
                        if (wantUnlock) BeepHelper.playUnlock();
                        else BeepHelper.playLock();
                    } else if (SystemClock.elapsedRealtime() - beepExpectAt > BEEP_EXPECT_TIMEOUT_MS) {
                        CtrlLog.w(TAG, "【提示音】指令期望超时 60 秒未确认门态：" + cmd
                                + "，不响（当前门态=" + s.unlocked + "）");
                        beepExpectCmd = null;
                    }
                }
                cached = copy(s);
                successSeq = Math.max(successSeq, target);
                lastError = null;
            } else {
                lastError = err;
            }
            if (doneSeq < target) doneSeq = target;
            lock.notifyAll();
        }
        if (stale) return; // 绝不 onGlobalStatus 写盘旧车况
        Log.i(TAG, "【全局车况】本轮完成 started="
                + (ok && s.started != null ? s.started : "未知")
                + " unlocked=" + (ok && s.unlocked != null ? s.unlocked : "未知")
                + " bat=" + (ok && s.batteryVoltage != null ? s.batteryVoltage + "V" : "未知")
                + (ok ? "" : " 失败=" + err) + " 序号=" + target);
        if (c != null) CarWidget.onGlobalStatus(c, ok ? copy(s) : null, ok ? null : err);
    }

    private static Api.CarState copy(Api.CarState src) {
        Api.CarState s = new Api.CarState();
        s.started = src.started;
        s.unlocked = src.unlocked;
        s.mileage = src.mileage;
        s.batteryVoltage = src.batteryVoltage;
        return s;
    }
}
