package com.example.mzdwidget;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 控车日志：写 logcat 的同时落盘，主界面底部按钮可弹窗查看。
 * 最新在前，最多 200 条，防止无限增长。不含 token 等凭证。
 */
public final class CtrlLog {
    private static final String PREF = "mzd_ctrl_log";
    private static final String KEY_LINES = "lines";
    private static final int MAX = 200;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    private CtrlLog() {}

    public static void i(String tag, String msg) { write("i", tag, msg); }
    public static void w(String tag, String msg) { write("w", tag, msg); }
    public static void e(String tag, String msg) { write("e", tag, msg); }

    private static void write(String level, String tag, String msg) {
        if ("e".equals(level)) Log.e(tag, msg);
        else if ("w".equals(level)) Log.w(tag, msg);
        else Log.i(tag, msg);

        Context c = MzdApp.get();
        if (c == null) return;
        String stamp;
        synchronized (FMT) { stamp = FMT.format(new Date()); }
        final String line = stamp + "  " + msg;
        synchronized (LOCK) {
            try {
                SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
                String raw = sp.getString(KEY_LINES, "");
                List<String> lines = new ArrayList<>(MAX + 1);
                lines.add(line);
                if (raw != null && !raw.isEmpty()) {
                    for (String s : raw.split("\n")) {
                        if (lines.size() >= MAX) break;
                        lines.add(s);
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (String s : lines) sb.append(s).append('\n');
                sp.edit().putString(KEY_LINES, sb.toString()).apply();
            } catch (Throwable ignored) {
                // 日志写失败绝不影响控车主流程
            }
        }
    }

    /** 弹窗显示用，最新在前 */
    public static String text() {
        Context c = MzdApp.get();
        if (c == null) return "暂无日志";
        synchronized (LOCK) {
            String raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LINES, "");
            return (raw == null || raw.isEmpty()) ? "暂无日志" : raw;
        }
    }

    public static void clear() {
        Context c = MzdApp.get();
        if (c == null) return;
        synchronized (LOCK) {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_LINES).apply();
        }
    }
}
