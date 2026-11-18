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
            // v1.9.5：递增代次。已发出但仍在飞行中的旧凭证查询回来后，
            // 发现 epoch 已变，会把结果丢弃——绝不把上一个账号/车的车况写回来冒充实时。
            epoch++;
        }
    }

    /** 工作线程主循环：按当前模式计算下一次查询时机，串行发请求。 */
    private void run() {
        while (started) {
            long myTarget;
            synchronized (lock) {
                while (true) {
                    long now = SystemClock.elapsedRealtime();
                    boolean fast = lastTriggerAt != 0L && (now - lastTriggerAt) < FAST_WINDOW_MS;
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
        synchronized (lock) {
            myEpoch = epoch;
            usedToken = (c != null && Prefs.isLoggedIn(c)) ? Prefs.token(c) : null;
            usedCarid = (c != null) ? Prefs.carid(c) : null;
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
            } else if (ok) {
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
                + (ok ? "" : " 失败=" + err) + " 序号=" + target);
        if (c != null) CarWidget.onGlobalStatus(c, ok ? copy(s) : null, ok ? null : err);
    }

    private static Api.CarState copy(Api.CarState src) {
        Api.CarState s = new Api.CarState();
        s.started = src.started;
        s.unlocked = src.unlocked;
        s.mileage = src.mileage;
        return s;
    }
}
