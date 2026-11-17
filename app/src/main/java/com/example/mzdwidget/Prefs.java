package com.example.mzdwidget;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地保存登录凭证（仅本机，不上传）。 */
public final class Prefs {
    private static final String NAME = "mzd_widget_prefs";
    private static final String K_TOKEN = "token";
    private static final String K_CARID = "carid";
    private static final String K_USERID = "userid";
    private static final String K_PHONE = "phone";
    private static final String K_PASSWORD = "password"; // 保存明文密码以便自动重登；仅本地
    private static final String K_BT_MAC = "bt_mac";         // 蓝牙触发设备 MAC
    private static final String K_BT_NAME = "bt_name";       // 蓝牙触发设备名称（仅显示）
    private static final String K_BT_ENABLED = "bt_enabled"; // 蓝牙自动控车开关
    private static final String K_BT_AWAY_SEC = "bt_away_sec"; // BLE 扫描：离开判定秒数
    private static final String K_BT_UNLOCK_LATCH = "bt_unlock_latch"; // 本次靠近是否已解锁过
    private static final String K_SYS_WAKE_AT = "sys_wake_at";   // 最近一次系统级扫描唤醒时间
    private static final String K_SYS_ACTION_AT = "sys_action_at"; // 系统扫描自动指令落盘去重（解锁/上锁共用）
    private static final String K_SYS_PRESENT = "sys_present";   // 系统扫描判定的在场状态
    private static final String K_SYS_RSSI = "sys_rssi";         // 最近一次命中的信号强度
    private static final String K_SYS_SEEN_AT = "sys_seen_at";   // 最近一次命中时间
    private static final String K_SYS_PENDING = "sys_pending";   // 撞上 10 秒窗口、待看门狗补发的动作（present/absent）

    // 外观设置（独立 prefs，清凭证时不影响）
    private static final String K_BRAND_TEXT = "brand_text";
    private static final String K_BRAND_COLOR = "brand_color";
    private static final String K_STATUS_NORMAL_COLOR = "status_normal_color";
    private static final String K_STATUS_ALERT_COLOR = "status_alert_color";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** widget 状态存独立 prefs，避免清凭证时被一并清掉 */
    public static SharedPreferences statusPrefs(Context c) {
        return c.getSharedPreferences("mzd_widget_status", Context.MODE_PRIVATE);
    }

    /** 兼容旧调用 */
    public static SharedPreferences getPrefsForStatus(Context c) {
        return statusPrefs(c);
    }

    public static void save(Context c, String token, String carid, String userid, String phone, String password) {
        sp(c).edit()
                .putString(K_TOKEN, token)
                .putString(K_CARID, carid)
                .putString(K_USERID, userid)
                .putString(K_PHONE, phone)
                .putString(K_PASSWORD, password)
                .apply();
    }

    /** 方式二：手动填入 token + carid */
    public static void saveToken(Context c, String token, String carid) {
        sp(c).edit().putString(K_TOKEN, token).putString(K_CARID, carid).apply();
    }

    public static String get(Context c, String key) {
        return sp(c).getString(key, "");
    }

    public static String token(Context c) { return get(c, K_TOKEN); }
    public static String carid(Context c) { return get(c, K_CARID); }
    public static String userid(Context c) { return get(c, K_USERID); }
    public static String phone(Context c) { return get(c, K_PHONE); }
    public static String password(Context c) { return get(c, K_PASSWORD); }

    public static boolean isLoggedIn(Context c) {
        String t = token(c);
        return t != null && !t.isEmpty();
    }

    /** 蓝牙自动控车：分离存储，清凭证时不影响设置 */
    private static SharedPreferences btPrefs(Context c) {
        return c.getSharedPreferences("mzd_widget_bt", Context.MODE_PRIVATE);
    }

    /**
     * BLE 解锁闩锁：本次「靠近」已成功解锁（或确认车已解锁）后为 true，
     * 直到下次「离开」真正发出锁车命令 / 确认车已锁才复位。
     * 落盘保存，BLE 前台服务被系统重建也不会丢状态。
     */
    public static boolean bleUnlockLatched(Context c) {
        return btPrefs(c).getBoolean(K_BT_UNLOCK_LATCH, false);
    }
    public static void setBleUnlockLatched(Context c, boolean latched) {
        btPrefs(c).edit().putBoolean(K_BT_UNLOCK_LATCH, latched).commit();
    }

    /** 系统级 PendingIntent 扫描最近一次命中时间（用于通知栏显示） */
    public static void noteSystemWake(Context c) {
        btPrefs(c).edit().putLong(K_SYS_WAKE_AT, System.currentTimeMillis()).apply();
    }
    public static long lastSystemWakeAt(Context c) { return btPrefs(c).getLong(K_SYS_WAKE_AT, 0L); }

    /* ===== 系统扫描全权实验分支：落盘的去重/在场/信号状态 =====
     * 系统扫描命中时 App 进程可能是被系统刚解冻的全新进程，
     * 内存变量不可靠，10 秒窗口与在场状态必须落盘。 */
    /** 记录一次系统扫描自动指令（解锁或上锁共用），落盘保证跨进程生效 */
    public static void noteSysAction(Context c) {
        btPrefs(c).edit().putLong(K_SYS_ACTION_AT, System.currentTimeMillis()).apply();
    }
    public static long lastSysActionAt(Context c) { return btPrefs(c).getLong(K_SYS_ACTION_AT, 0L); }
    /** 距离上次系统扫描自动指令是否已达窗口（默认 10 秒） */
    public static boolean sysActionReady(Context c, long windowMs) {
        return System.currentTimeMillis() - lastSysActionAt(c) >= windowMs;
    }
    /** 撞上 10 秒窗口时挂起的动作，交给看门狗在窗口结束后补发（落盘，跨进程可靠） */
    public static void setSysPending(Context c, String action) {
        if (action == null) btPrefs(c).edit().remove(K_SYS_PENDING).apply();
        else btPrefs(c).edit().putString(K_SYS_PENDING, action).apply();
    }
    public static String sysPending(Context c) { return btPrefs(c).getString(K_SYS_PENDING, null); }
    public static void setSysPresent(Context c, boolean present) {
        btPrefs(c).edit().putBoolean(K_SYS_PRESENT, present).apply();
    }
    public static boolean sysPresent(Context c) { return btPrefs(c).getBoolean(K_SYS_PRESENT, false); }
    public static void noteSysRssi(Context c, int rssi) {
        btPrefs(c).edit().putInt(K_SYS_RSSI, rssi)
                .putLong(K_SYS_SEEN_AT, System.currentTimeMillis()).apply();
    }
    public static int lastSysRssi(Context c) { return btPrefs(c).getInt(K_SYS_RSSI, 0); }
    public static long lastSysSeenAt(Context c) { return btPrefs(c).getLong(K_SYS_SEEN_AT, 0L); }
    /** 用户关闭 BLE 或重新选设备时，把系统扫描的临时状态一次性清掉 */
    public static void clearSysScanState(Context c) {
        btPrefs(c).edit()
                .remove(K_SYS_PRESENT).remove(K_SYS_RSSI).remove(K_SYS_SEEN_AT)
                .remove(K_SYS_ACTION_AT).remove(K_SYS_WAKE_AT).remove(K_SYS_PENDING)
                .apply();
    }

    public static void saveBtDevice(Context c, String mac, String name) {
        btPrefs(c).edit().putString(K_BT_MAC, mac).putString(K_BT_NAME, name).apply();
    }
    public static String btMac(Context c) { return btPrefs(c).getString(K_BT_MAC, ""); }
    public static String btName(Context c) { return btPrefs(c).getString(K_BT_NAME, ""); }
    public static void setBtEnabled(Context c, boolean enabled) {
        btPrefs(c).edit().putBoolean(K_BT_ENABLED, enabled).apply();
    }
    public static boolean btEnabled(Context c) { return btPrefs(c).getBoolean(K_BT_ENABLED, false); }
    /** BLE 扫描：连续多少秒扫不到目标设备即判定离开，默认 30 秒 */
    public static int btAwaySec(Context c) { return btPrefs(c).getInt(K_BT_AWAY_SEC, 30); }
    public static void setBtAwaySec(Context c, int sec) {
        btPrefs(c).edit().putInt(K_BT_AWAY_SEC, Math.max(10, Math.min(600, sec))).apply();
    }

    /* ===== 外观：品牌文字/颜色、车况文字颜色（独立存储，清凭证不重置） ===== */
    private static SharedPreferences appearancePrefs(Context c) {
        return c.getSharedPreferences("mzd_widget_appearance", Context.MODE_PRIVATE);
    }
    public static String brandText(Context c) {
        String s = appearancePrefs(c).getString(K_BRAND_TEXT, "MAZDA 昂克赛拉");
        return (s == null || s.isEmpty()) ? "MAZDA 昂克赛拉" : s;
    }
    /** 默认魂动红 */
    public static int brandColor(Context c) { return appearancePrefs(c).getInt(K_BRAND_COLOR, 0xFF8A1A2B); }
    /** 未启动且已锁时的车况颜色，默认黑 */
    public static int statusNormalColor(Context c) { return appearancePrefs(c).getInt(K_STATUS_NORMAL_COLOR, 0xFF212121); }
    /** 已启动或未锁时的车况颜色，默认红 */
    public static int statusAlertColor(Context c) { return appearancePrefs(c).getInt(K_STATUS_ALERT_COLOR, 0xFFC41230); }
    public static void saveAppearance(Context c, String brandText, int brandColor,
                                      int statusNormalColor, int statusAlertColor) {
        appearancePrefs(c).edit()
                .putString(K_BRAND_TEXT, brandText == null ? "" : brandText)
                .putInt(K_BRAND_COLOR, brandColor)
                .putInt(K_STATUS_NORMAL_COLOR, statusNormalColor)
                .putInt(K_STATUS_ALERT_COLOR, statusAlertColor)
                .apply();
    }

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }
}
