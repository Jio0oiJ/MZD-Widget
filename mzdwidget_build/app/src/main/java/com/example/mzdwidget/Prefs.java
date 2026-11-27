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
    private static final String K_WEAR_ENABLED = "wear_enabled"; // v1.9.25 手环遥控开关（默认关）
    private static final String K_BT_AWAY_SEC = "bt_away_sec"; // BLE 扫描：离开判定秒数
    private static final String K_BT_RSSI = "bt_rssi_threshold"; // BLE 解锁信号阈值（dBm，负整数）
    private static final String K_BT_LOCK_RSSI = "bt_lock_rssi_threshold"; // v1.9.22 BLE 上锁信号阈值（dBm，负整数，独立滞回）
    private static final String K_BT_UNLOCK_LATCH = "bt_unlock_latch"; // 本次靠近是否已解锁过
    private static final String K_SOUND_ENABLED = "sound_enabled";     // v1.9.50：提示音总开关（默认开）
    private static final String K_UNLOCK_SOUND = "unlock_sound";       // v1.9.50：解锁音效（"0".."5" 内置 / content URI 自定义）
    private static final String K_LOCK_SOUND = "lock_sound";           // v1.9.50：上锁音效

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

    public static void save(Context c, String token, String carid, String userid, String phone, String password) {
        sp(c).edit()
                .putString(K_TOKEN, token)
                .putString(K_CARID, carid)
                .putString(K_USERID, userid)
                .putString(K_PHONE, phone)
                .putString(K_PASSWORD, password)
                .apply();
        exportCred(c, token, carid);
    }

    /**
     * 方式二/三：手动填入 token + carid，或 Root 导入后写入。
     * v1.9.5：必须清掉上一种登录方式残留的 password/phone/userid。
     * 否则「账号登录→切手动/Root Token」后 password 仍在，refreshStatusText 会永远显示「账号登录」，
     * 凭证来源展示失真。注意 Root 导入会在本方法之后单独补写 userid，故这里清空 userid 不影响其展示。
     */
    public static void saveToken(Context c, String token, String carid) {
        sp(c).edit()
                .putString(K_TOKEN, token)
                .putString(K_CARID, carid)
                .putString(K_PASSWORD, "")
                .putString(K_PHONE, "")
                .putString(K_USERID, "")
                .apply();
        exportCred(c, token, carid);
    }

    /** 凭证变化时同步导出加密文件给静默 APK（子线程，避免阻塞 UI） */
    private static void exportCred(final Context c, final String token, final String carid) {
        new Thread(() -> CredFile.export(c.getApplicationContext(), token, carid),
                "mzd-export-cred").start();
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

    public static void saveBtDevice(Context c, String mac, String name) {
        btPrefs(c).edit().putString(K_BT_MAC, mac).putString(K_BT_NAME, name).apply();
    }
    public static String btMac(Context c) { return btPrefs(c).getString(K_BT_MAC, ""); }
    public static String btName(Context c) { return btPrefs(c).getString(K_BT_NAME, ""); }
    public static void setBtEnabled(Context c, boolean enabled) {
        btPrefs(c).edit().putBoolean(K_BT_ENABLED, enabled).apply();
    }
    public static boolean btEnabled(Context c) { return btPrefs(c).getBoolean(K_BT_ENABLED, false); }
    /** v1.9.25：手环遥控开关，默认关闭，需用户主动开启 */
    public static void setWearEnabled(Context c, boolean enabled) {
        btPrefs(c).edit().putBoolean(K_WEAR_ENABLED, enabled).apply();
    }
    public static boolean wearEnabled(Context c) { return btPrefs(c).getBoolean(K_WEAR_ENABLED, false); }

    /* ===== v1.9.50：解锁/上锁提示音设置（独立存储，清凭证不重置；低电压报警不受影响） ===== */
    /** 总开关默认开，保持 v1.9.49 之前"解锁一声/上锁两声"的既有行为。 */
    public static boolean soundEnabled(Context c) { return btPrefs(c).getBoolean(K_SOUND_ENABLED, true); }
    public static void setSoundEnabled(Context c, boolean enabled) {
        btPrefs(c).edit().putBoolean(K_SOUND_ENABLED, enabled).apply();
    }
    /** 默认解锁=内置经典一声("0")、上锁=内置经典两声("1")。自定义值为 content URI 字符串。 */
    public static String unlockSound(Context c) {
        String s = btPrefs(c).getString(K_UNLOCK_SOUND, "0");
        return (s == null || s.isEmpty()) ? "0" : s;
    }
    public static String lockSound(Context c) {
        String s = btPrefs(c).getString(K_LOCK_SOUND, "1");
        return (s == null || s.isEmpty()) ? "1" : s;
    }
    public static void setUnlockSound(Context c, String v) {
        btPrefs(c).edit().putString(K_UNLOCK_SOUND, v == null ? "0" : v).apply();
    }
    public static void setLockSound(Context c, String v) {
        btPrefs(c).edit().putString(K_LOCK_SOUND, v == null ? "1" : v).apply();
    }

    /** BLE 扫描：连续多少秒扫不到目标设备即判定离开，默认 30 秒 */
    public static int btAwaySec(Context c) { return btPrefs(c).getInt(K_BT_AWAY_SEC, 30); }
    public static void setBtAwaySec(Context c, int sec) {
        btPrefs(c).edit().putInt(K_BT_AWAY_SEC, Math.max(10, Math.min(600, sec))).apply();
    }

    /**
     * BLE 解锁信号阈值：RSSI >= 该值（越接近 0 越强）才判定「靠近」并解锁。
     * 默认 -70 dBm，可调范围 -100（极远）~ -30（极近）。走远后信号跌破阈值，
     * lastSeen 不再刷新，离开判定据此上锁。
     */
    public static int btRssiThreshold(Context c) { return btPrefs(c).getInt(K_BT_RSSI, -70); }
    public static void setBtRssiThreshold(Context c, int dbm) {
        // 夹在 -100~-30：上限防误填 0/正数导致 rssi>=阈值 恒假（永远不解锁），
        // 下限防误填极小负数导致恒真（永远解锁）
        int u = Math.max(-100, Math.min(-30, dbm));
        // v1.9.23：归一化——解锁阈值调低时，若当前上锁阈值比它还高（更接近 0），
        // 同步把存储的上锁阈值降下来，保证「存储值=生效值」，上锁输入框显示与判定一致。
        android.content.SharedPreferences p = btPrefs(c);
        int storedLock = p.getInt(K_BT_LOCK_RSSI, -78);
        android.content.SharedPreferences.Editor e = p.edit().putInt(K_BT_RSSI, u);
        if (storedLock > u) e.putInt(K_BT_LOCK_RSSI, u);
        e.apply();
    }

    /**
     * v1.9.22：BLE 上锁信号阈值，与解锁阈值分离形成真正的双阈值滞回。
     * 均值跌破该值且趋势走低才上锁；默认 -78 dBm（比解锁默认 -70 低 8 dB）。
     * 这样靠近要在 -70 以上才解锁、离开要跌到 -78 以下才上锁，中间 -78~-70 是滞回区，
     * 避免临界点信号小幅抖动导致反复解锁/上锁。
     */
    /**
     * v1.9.23：读取时把上锁阈值钳制为「不高于解锁阈值」。
     * 用户若经 UI 把上锁阈值填得比解锁阈值更接近 0（如 lock=-50、unlock=-70），
     * 判定会出现重叠区（均值在 [-70,-50) 且斜率在 -0.5~-0.2 时解锁/上锁条件同时成立，
     * 因先判解锁而在信号走弱时误解锁）。这里统一钳制，使 lock<=unlock 恒成立：
     * 相等时退化为单阈值（靠斜率死区近似滞回），无重叠区，安全。
     * 钳制放在 getter，判定/日志/通知栏/UI 全部自动取到同一有效值，不会各处口径不一致。
     */
    public static int btLockRssiThreshold(Context c) {
        return Math.min(btPrefs(c).getInt(K_BT_LOCK_RSSI, -78), btRssiThreshold(c));
    }
    public static void setBtLockRssiThreshold(Context c, int dbm) {
        // v1.9.23：写入时就按当前解锁阈值归一化（存有效值），getter 再钳一次作双保险。
        // 这样 sharPrefs 里存的永远=生效值，UI 输入框显示与判定口径一致，不会「显示-50/实际-70」。
        int lock = Math.max(-100, Math.min(-30, dbm));
        btPrefs(c).edit().putInt(K_BT_LOCK_RSSI, Math.min(lock, btRssiThreshold(c))).apply();
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

    /* ===== BLE 单轨扫描诊断（独立 prefs，进程被杀后仍可读，供实机排查） ===== */
    private static SharedPreferences diagPrefs(Context c) {
        return c.getSharedPreferences("mzd_ble_diag", Context.MODE_PRIVATE);
    }

    /**
     * 写入一次诊断快照：调用方（BleScanService.persistDiag）传入的是自服务启动以来的
     * 内存累计值，这里必须「覆盖」而非「累加」——旧实现做 prefs + 累计值，导致每次
     * 落盘都把累计数再加一遍，数字指数虚增、主界面显示假数据。
     * 用 commit 同步落盘——BLE 前台服务被 ROM 杀掉后，主界面仍要能读到计数。
     */
    public static void noteBleDiag(Context c, long cbTotal, long hitTotal, long unlockTotal,
                                   long lockTotal, long failTotal, String event) {
        SharedPreferences p = diagPrefs(c);
        p.edit()
                .putLong("cb", cbTotal)
                .putLong("hit", hitTotal)
                .putLong("unlock", unlockTotal)
                .putLong("lock", lockTotal)
                .putLong("fail", failTotal)
                .putString("event", event == null ? "" : event)
                .putLong("event_at", System.currentTimeMillis())
                .commit();
    }

    /** 诊断摘要，供主界面显示；从未记录时返回空串 */
    public static String bleDiagText(Context c) {
        SharedPreferences p = diagPrefs(c);
        long at = p.getLong("event_at", 0);
        if (at == 0) return "";
        String event = p.getString("event", "");
        String ago;
        long d = System.currentTimeMillis() - at;
        if (d < 60000L) ago = (d / 1000L) + " 秒前";
        else if (d < 3600000L) ago = (d / 60000L) + " 分钟前";
        else ago = (d / 3600000L) + " 小时前";
        return "回调 " + p.getLong("cb", 0) + " · 命中 " + p.getLong("hit", 0)
                + " · 解锁 " + p.getLong("unlock", 0) + " · 上锁 " + p.getLong("lock", 0)
                + " · 失败 " + p.getLong("fail", 0)
                + "\n最近：" + (event == null || event.isEmpty() ? "—" : event) + "（" + ago + "）";
    }

    public static void clearBleDiag(Context c) {
        diagPrefs(c).edit().clear().commit();
    }

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }
}
