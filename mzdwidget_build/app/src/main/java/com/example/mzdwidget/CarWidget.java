package com.example.mzdwidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/** 桌面小组件：左侧车况 + 右侧 一键启动+解锁 / 解锁 / 上锁。 */
public class CarWidget extends AppWidgetProvider {

    public static final String ACTION_CMD = "com.example.mzdwidget.CMD";
    public static final String ACTION_REFRESH = "com.example.mzdwidget.REFRESH";
    public static final String ACTION_SMART_START = "com.example.mzdwidget.SMART_START";
    /** 一键启动流程的解锁兜底：由系统闹钟在 18 秒后拉起，进程被回收也能补发 */
    static final String ACTION_SMART_UNLOCK = "com.example.mzdwidget.SMART_UNLOCK";
    /** 外部（BLE 自动控车）请求：立即刷新一次 + 之后 10 秒内每 2 秒连续刷新 */
    public static final String ACTION_FAST_REFRESH = "com.example.mzdwidget.FAST_REFRESH";
    /** 由 AlarmManager 驱动的快刷 tick：每次都是独立广播，App 进程被回收也能继续 */
    static final String ACTION_FAST_TICK = "com.example.mzdwidget.FAST_TICK";
    /** 5 分钟正常刷新：链式精确闹钟自续，刷完自己排下一次，确保桌面组件同步更新 */
    static final String ACTION_PERIODIC = "com.example.mzdwidget.PERIODIC";
    public static final String EXTRA_CMD = "cmd";
    static final String EXTRA_FAST = "fast";
    static final String EXTRA_TICKS_LEFT = "ticks_left";
    private static final String KEY_STATUS_JSON = "widget_status_json";
    private static final String KEY_STATUS_ERR = "widget_status_err";

    private static final long PERIOD_MS = 5 * 60 * 1000L;      // 每 5 分钟自动刷新
    private static final long FAST_INTERVAL_MS = 2000L;         // 快速刷新间隔：2 秒
    private static final int FAST_TICKS = 5;                    // 10 秒 / 2 秒 = 5 次
    private static final int FAST_REQ_CODE = 21;                // 快刷闹钟专用 PendingIntent 编号
    private static final int PERIOD_REQ_CODE = 22;              // 5 分钟周期闹钟专用编号
    private static final int SMART_UNLOCK_REQ_CODE = 23;        // 一键启动解锁兜底闹钟编号
    private static final long SMART_FALLBACK_MS = 18000L;       // 旧版兜底延时（v1.8 起已停用，仅为兼容残留覆盖安装的闹钟）
    private static final String SMART_PREFS = "mzd_smart";
    private static final String KEY_SMART_UNLOCK_AT = "last_smart_unlock_at";
    private static final long SMART_DONE_WINDOW_MS = 60000L;    // 该窗口内视为本次流程已解锁

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger LOCAL_LEFT = new AtomicInteger(0);
    private static volatile Context APP; // 仅用于进程存活时的补充定时

    /**
     * 进程内的补充定时：进程恰好存活时按 2 秒立即刷新，用来对抗 Doze 对精确闹钟的限流。
     * 真正的可靠性由 AlarmManager 链式广播保证——进程被杀后，这个 Runnable 自然失效，
     * 但已排好的闹钟仍会由系统拉起本接收器。
     */
    private static final Runnable FAST_TICK_LOCAL = new Runnable() {
        @Override public void run() {
            Context app = APP;
            if (app == null || LOCAL_LEFT.get() <= 0) return;
            new Thread(() -> refreshAndUpdate(app), "mzd-fast-local").start();
            if (LOCAL_LEFT.decrementAndGet() > 0) MAIN.postDelayed(this, FAST_INTERVAL_MS);
        }
    };

    @Override public void onEnabled(Context context) {
        super.onEnabled(context);
        schedulePeriodic(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // 安全：本接收器 exported=true 只用于接收系统 APPWIDGET_* 广播。
        // 所有自定义 action（按钮/闹钟/快刷）已移到 exported=false 的 WidgetActions，
        // 外部 App 无法再发显式广播越权控车。
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] appWidgetIds) {
        schedulePeriodic(context);
        for (int id : appWidgetIds) {
            try { mgr.updateAppWidget(id, buildViews(context)); } catch (Throwable ignored) { }
        }
        if (Prefs.isLoggedIn(context)) {
            final Context app = context.getApplicationContext();
            new Thread(() -> refreshAndUpdate(app), "mzd-status").start();
        }
    }

    /**
     * 排下一次 5 分钟刷新。用「精确闹钟 + 收到后自续」的链式方式，而不是 setRepeating：
     * setRepeating 在 Android 4.4+ 是非精确的，容易被系统合并甚至在部分 ROM 上被冻结，
     * 导致桌面组件长时间不更新。链式闹钟每次只排一拍，触发后立刻排下一拍，
     * 且同一 PendingIntent 重复 set 会覆盖旧闹钟，不会叠加多组周期。
     */
    static void schedulePeriodic(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            // 清掉旧版本用 setRepeating 排的重复闹钟（若覆盖安装前残留）
            am.cancel(refreshIntent(c));
            long at = SystemClock.elapsedRealtime() + PERIOD_MS;
            PendingIntent pi = periodicIntent(c);
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception ignored) { }
    }

    private static PendingIntent periodicIntent(Context c) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(ACTION_PERIODIC);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, PERIOD_REQ_CODE, i, flags);
    }

    /** 触发一次 10 秒快刷：重复触发会重置计时，不会叠加多组定时器 */
    static void startFastRefresh(Context app) {
        Context a = app.getApplicationContext();
        APP = a;
        try {
            MAIN.removeCallbacks(FAST_TICK_LOCAL);
            LOCAL_LEFT.set(FAST_TICKS);
            MAIN.postDelayed(FAST_TICK_LOCAL, FAST_INTERVAL_MS);
        } catch (Exception ignored) { }
        // 关键：用系统闹钟排下一拍。同一 PendingIntent 再 set 一次会覆盖旧闹钟，
        // 所以重复点击天然变成「重新计时」，且 App 进程被杀也不会丢。
        scheduleFastTick(a, FAST_TICKS);
    }

    /** 排下一次快刷 tick（精确闹钟；权限被关时退化为非精确闹钟，仍会送达） */
    static void scheduleFastTick(Context c, int ticksLeft) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = fastTickIntent(c, ticksLeft);
            long at = SystemClock.elapsedRealtime() + FAST_INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception ignored) { }
    }

    static void cancelFastAlarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(fastTickIntent(c, 0));
        } catch (Exception ignored) { }
    }

    /* ---------- 一键启动：解锁去重与 5 秒兜底闹钟 ---------- */

    private static android.content.SharedPreferences smartPrefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(SMART_PREFS, Context.MODE_PRIVATE);
    }

    /** 主流程或兜底流程成功解锁后打标记，避免重复解锁 */
    public static void markSmartUnlockDone(Context c) {
        smartPrefs(c).edit().putLong(KEY_SMART_UNLOCK_AT, SystemClock.elapsedRealtime()).commit();
    }

    static void clearSmartUnlockDone(Context c) {
        smartPrefs(c).edit().remove(KEY_SMART_UNLOCK_AT).commit();
    }

    static boolean smartUnlockDoneRecently(Context c) {
        long t = smartPrefs(c).getLong(KEY_SMART_UNLOCK_AT, 0);
        return t != 0 && SystemClock.elapsedRealtime() - t < SMART_DONE_WINDOW_MS;
    }

    /** 启动指令都发失败时取消兜底，避免无谓解锁 */
    public static void cancelSmartUnlockFallback(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(smartUnlockIntent(c));
        } catch (Exception ignored) { }
    }

    static void scheduleSmartUnlockFallback(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = smartUnlockIntent(c);
            long at = SystemClock.elapsedRealtime() + SMART_FALLBACK_MS;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception ignored) { }
    }

    static PendingIntent smartUnlockIntent(Context c) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(ACTION_SMART_UNLOCK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, SMART_UNLOCK_REQ_CODE, i, flags);
    }

    private static PendingIntent fastTickIntent(Context c, int ticksLeft) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(ACTION_FAST_TICK);
        i.putExtra(EXTRA_TICKS_LEFT, ticksLeft);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, FAST_REQ_CODE, i, flags);
    }

    /**
     * v1.9：桌面刷新入口改为读全局缓存快照，不再自己查网。
     * 缓存为空时触发一次后台查询，结果由 onGlobalStatus 回调写盘重绘。
     */
    /** v1.9.23：供 CarStateCache 在锁内回调写盘的钩子（见 publishStatusAtomically）。 */
    public interface Publisher {
        void publish(Api.CarState s);
    }

    static void refreshAndUpdate(Context c) {
        if (!Prefs.isLoggedIn(c)) {
            updateAll(c);
            return;
        }
        CarStateCache cache = CarStateCache.get(c);
        // v1.9.6：车况与错误标记必须原子同取。原来 snapshot()/lastError() 两次独立加锁，
        // 中间若发生切号 invalidate（清空两者），会出现「已取到旧账号 s + 读到清空后的 lastError==null」
        // → 把旧车况写盘。改一次加锁同取，消除窗口。
        CarStateCache.Snapshot snap = cache.snapshotWithError();
        Api.CarState s = snap.state;
        boolean hasError = snap.error != null;
        // v1.9.3：最近一轮查询失败时 lastError!=null，cached 是失败前遗留的旧值。
        // 此时绝不能 publishStatus——它会清空 KEY_STATUS_ERR，把失败提示抹掉、用旧车况冒充实时。
        // 只重绘保留失败提示；缓存工作线程会在下一轮成功后由 onGlobalStatus 自动恢复正常显示。
        if (s != null && !hasError) {
            // v1.9.23：写盘必须在锁内与 invalidate 串行。snapshotWithError 之后到 publishStatus
            // 之前仍可能插入切号，导致旧车况写回已清空的桌面。改走原子版：取缓存+校验+写盘同一临界区。
            boolean written = cache.publishStatusAtomically(state -> publishStatus(c, state));
            if (!written) updateAll(c); // 期间切号/查询失败：不写盘，仅重绘当前（已清空/有错）状态
        } else if (s == null) {
            // 从未成功过（冷启动）：触发一次后台查询，重绘显示「正在获取」
            cache.trigger(c);
            updateAll(c);
        } else {
            // 有旧缓存但最近一轮失败：保留失败提示，仅重绘，不清错误标记
            updateAll(c);
        }
    }

    /** 全局缓存每轮查询完成后的回调：把本轮结果写盘并重绘桌面 */
    public static void onGlobalStatus(Context c, Api.CarState s, String err) {
        if (s != null) {
            publishStatus(c, s);
        } else if (err != null && !err.isEmpty()) {
            writeStatusError(c, "车况获取失败：" + (err.length() > 24 ? err.substring(0, 24) : err));
            updateAll(c);
        }
    }

    /** 供 BLE / 按钮触发全局快刷（取代旧的发广播方式） */
    public static void requestFastRefresh(Context c) {
        try {
            CarStateCache.get(c).trigger(c);
        } catch (Exception ignored) { }
    }

    /**
     * 凭证/车辆变更时清掉桌面已写盘的车况与错误标记，
     * 避免切换账号/车辆后，桌面在下一轮查询成功前仍显示上一账号的旧车况。
     */
    public static void clearStatusOnCredentialChange(Context c) {
        try {
            Prefs.statusPrefs(c).edit()
                    .remove(KEY_STATUS_JSON)
                    .remove(KEY_STATUS_ERR)
                    .commit();
        } catch (Throwable ignored) { }
    }

    /** 复用已查到的车况写入缓存并重绘，全程不再发起 /car/status 请求 */
    public static void publishStatus(Context c, Api.CarState s) {
        try {
            if (s == null) return;
            JSONObject o = new JSONObject();
            if (s.started != null) o.put("started", s.started);
            if (s.unlocked != null) o.put("unlocked", s.unlocked);
            // 同步落盘：回调可能在全新进程执行，apply() 异步会让新进程读到旧车况。
            Prefs.statusPrefs(c).edit()
                    .putString(KEY_STATUS_JSON, o.toString())
                    .putString(KEY_STATUS_ERR, "")
                    .commit();
        } catch (Throwable ignored) { }
        updateAll(c);
    }

    /** 只重绘桌面组件，绝不发起车况查询（供流程收尾用） */
    public static void redraw(Context c) {
        updateAll(c);
    }

    private static void writeStatusError(Context app, String err) {
        try {
            Prefs.statusPrefs(app).edit().putString(KEY_STATUS_ERR, err).commit();
        } catch (Exception ignored) { }
    }

    private static RemoteViews buildViews(Context c) {
        // 最后一道防线：任何异常都不允许从 buildViews 抛出。
        // 旧版就是在这里抛出 NPE，导致每次刷新广播都崩溃、桌面组件永远停在旧状态。
        try {
            return buildViewsInner(c);
        } catch (Throwable t) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_layout);
            rv.setTextViewText(R.id.tv_status, "组件刷新异常");
            rv.setTextColor(R.id.tv_status, 0xFF607080);
            return rv;
        }
    }

    private static RemoteViews buildViewsInner(Context c) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_layout);

        // 第一行品牌：自定义文字 + 自定义颜色
        rv.setTextViewText(R.id.tv_brand, Prefs.brandText(c));
        rv.setTextColor(R.id.tv_brand, Prefs.brandColor(c));

        String status;
        int statusColor = Prefs.statusNormalColor(c);
        String err = Prefs.statusPrefs(c).getString(KEY_STATUS_ERR, "");
        if (!Prefs.isLoggedIn(c)) {
            status = "未登录";
            statusColor = 0xFF607080;
        } else if (err != null && !err.isEmpty()) {
            // 拉取失败时显示原因，绝不再拿旧缓存冒充实时状态
            status = err;
            statusColor = 0xFF607080;
        } else {
            JSONObject o = readStatus(c);
            if (o == null) {
                status = "正在获取车况…";
                statusColor = 0xFF607080;
            } else {
                // 先取出对象再判空：字段缺失/类型不对时一律当「未知」，
                // 绝不对可能为 null 的 Boolean 做自动拆箱（旧版就是这里 NPE 闪退）。
                Object sv = o.opt("started");
                Object uv = o.opt("unlocked");
                boolean started = Boolean.TRUE.equals(sv);
                boolean unlocked = Boolean.TRUE.equals(uv);
                String engine = (sv instanceof Boolean) ? (started ? "已启动" : "未启动") : "—";
                String door = (uv instanceof Boolean) ? (unlocked ? "车门未锁" : "车门已锁") : "—";
                status = engine + "  " + door;
                boolean alert = started || unlocked;
                statusColor = alert ? Prefs.statusAlertColor(c) : Prefs.statusNormalColor(c);
            }
        }

        rv.setTextViewText(R.id.tv_status, status);
        rv.setTextColor(R.id.tv_status, statusColor);

        rv.setOnClickPendingIntent(R.id.btn_smart_start, actionIntent(c, ACTION_SMART_START, 1));
        rv.setOnClickPendingIntent(R.id.btn_unlock, cmdIntent(c, Api.CMD_UNLOCK, 2));
        rv.setOnClickPendingIntent(R.id.btn_lock, cmdIntent(c, Api.CMD_LOCK, 3));
        rv.setOnClickPendingIntent(R.id.btn_stall, cmdIntent(c, Api.CMD_STALL, 4));
        rv.setOnClickPendingIntent(R.id.btn_find, cmdIntent(c, Api.CMD_SEARCH, 5));
        PendingIntent clickRefresh = clickRefreshIntent(c);
        rv.setOnClickPendingIntent(R.id.status_area, clickRefresh);
        rv.setOnClickPendingIntent(R.id.tv_status, clickRefresh);
        return rv;
    }

    private static JSONObject readStatus(Context c) {
        String s = Prefs.statusPrefs(c).getString(KEY_STATUS_JSON, "");
        if (s == null || s.isEmpty()) return null;
        try { return new JSONObject(s); } catch (Exception e) { return null; }
    }

    private static PendingIntent actionIntent(Context c, String action, int req) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    private static PendingIntent cmdIntent(Context c, String cmd, int req) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(ACTION_CMD);
        i.putExtra(EXTRA_CMD, cmd);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, req, i, flags);
    }

    /** 5 分钟定时 / App 内部刷新：单次 */
    private static PendingIntent refreshIntent(Context c) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(ACTION_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 9, i, flags);
    }

    /** 桌面左侧点击：单次刷新 + 启动 10 秒 / 每 2 秒 的连续刷新 */
    private static PendingIntent clickRefreshIntent(Context c) {
        Intent i = new Intent(c, WidgetActions.class);
        i.setAction(ACTION_REFRESH);
        i.putExtra(EXTRA_FAST, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 10, i, flags);
    }

    private static void updateAll(Context c) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(c);
            ComponentName cn = new ComponentName(c, CarWidget.class);
            RemoteViews rv = buildViews(c);
            for (int id : mgr.getAppWidgetIds(cn)) mgr.updateAppWidget(id, rv);
        } catch (Throwable ignored) {
            // 重绘失败不能反向把刷新广播搞崩（否则点击后既不变也不报错）
        }
    }

    private static String label(String cmd) {
        if (Api.CMD_UNLOCK.equals(cmd)) return "解锁";
        if (Api.CMD_LOCK.equals(cmd)) return "上锁";
        if (Api.CMD_STALL.equals(cmd)) return "熄火";
        if (Api.CMD_SEARCH.equals(cmd)) return "寻车";
        return cmd;
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.toString();
        return m.length() > 40 ? m.substring(0, 40) : m;
    }
}
