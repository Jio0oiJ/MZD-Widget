package com.example.mzdwidget;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * 解锁/上锁提示音（v1.9.16 引入，v1.9.50 支持自定义选择）。
 *
 * 音效来源两种：
 *   1) 内置合成音（AudioTrack 生成正弦波，无需文件/权限）：见 TONES；
 *   2) 用户自选本地音频文件（content URI，经 ACTION_OPEN_DOCUMENT 授权并持久化），MediaPlayer 播放。
 * 解锁/上锁各自独立配置（Prefs.unlockSound / lockSound），总开关 Prefs.soundEnabled。
 * 低电压报警固定用内置两声、不受选择和开关影响——它是安全报警，不能被用户静音掉。
 *
 * 全程异常保护——声音只是反馈，任何失败都静默忽略，绝不影响控车主流程。
 * 播放在调用线程调度，主线程 Handler 负责释放/延迟，不阻塞缓存工作线程。
 */
public final class BeepHelper {

    private static final String TAG = "MZDCtrlLog";
    private static final int SAMPLE_RATE = 44100;
    private static final int VOLUME = 24000;     // 峰值幅度（约 73% 满幅，车内也能听见）
    private static final int RAMP_MS = 5;        // 渐入渐出，避免爆音

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 内置音效配方。每个音效是一个音符序列 {频率Hz, 时长ms}；逐音播放。
     * 下标即 Prefs 里存的 sound id（内置为 "0".."5"）。
     */
    private static final int[][][] TONES = {
            {{880, 180}},                                   // 0 经典一声（默认解锁）
            {{880, 180}, {880, 180}},                       // 1 经典两声（默认上锁 / 低电压报警）
            {{659, 150}, {880, 260}},                       // 2 叮咚（上行两音）
            {{523, 120}, {659, 120}, {784, 220}},           // 3 上行三音
            {{784, 120}, {659, 120}, {523, 220}},           // 4 下行三音
            {{523, 320}, {659, 320}, {784, 320}}            // 5 舒缓三连
    };
    private static final int GAP_MS = 90;                   // 音符间隔
    private static final String[] TONE_NAMES = {
            "经典一声", "经典两声", "叮咚", "上行三音", "下行三音", "舒缓三连"
    };

    public static int builtinCount() { return TONES.length; }
    public static String builtinName(int i) {
        return (i >= 0 && i < TONE_NAMES.length) ? TONE_NAMES[i] : "内置音效";
    }
    /** 供界面显示当前配置文字：内置显示名字，自定义显示文件名。 */
    public static String soundLabel(String v) {
        if (v == null || v.isEmpty()) v = "0";
        try {
            int id = Integer.parseInt(v.trim());
            if (id >= 0 && id < TONE_NAMES.length) return "内置 · " + TONE_NAMES[id];
        } catch (NumberFormatException ignored) { }
        try {
            String last = Uri.parse(v).getLastPathSegment();
            return "自定义 · " + (last == null || last.isEmpty() ? "音频文件" : last);
        } catch (Throwable ignored) { }
        return "自定义音频";
    }

    private BeepHelper() {}

    /** 解锁成功：播「解锁音效」。 */
    public static void playUnlock() {
        Context c = MzdApp.get();
        if (c == null || !Prefs.soundEnabled(c)) return;
        playSetting(c, Prefs.unlockSound(c));
    }

    /** 上锁成功：播「上锁音效」。 */
    public static void playLock() {
        Context c = MzdApp.get();
        if (c == null || !Prefs.soundEnabled(c)) return;
        playSetting(c, Prefs.lockSound(c));
    }

    /** 低电压报警：固定内置两声，不受音效选择与总开关影响（安全报警不可被静音）。 */
    public static void playAlarm() {
        Context c = MzdApp.get();
        if (c == null) return;
        playTone(c, 1);
    }

    /** 试听：忽略总开关，按给定配置播一次，供设置页确认音效。 */
    public static void preview(Context c, String setting) {
        if (c == null) return;
        playSetting(c, setting);
    }

    private static void playSetting(Context c, String setting) {
        if (setting == null || setting.isEmpty()) setting = "0";
        try {
            int id = Integer.parseInt(setting.trim());
            if (id >= 0 && id < TONES.length) { playTone(c, id); return; }
        } catch (NumberFormatException ignored) { }
        // 非数字即视为自定义音频 URI
        playUri(c, setting);
    }

    /* ==================== 内置合成音 ==================== */

    private static void playTone(final Context c, final int id) {
        try {
            if (id < 0 || id >= TONES.length) return;
            final int[][] notes = TONES[id];
            long delay = 0;
            for (int[] note : notes) {
                final short[] pcm = buildPcm(note[0], note[1]);
                MAIN.postDelayed(() -> playPcm(c, pcm), delay);
                delay += note[1] + GAP_MS;
            }
        } catch (Throwable t) {
            CtrlLog.w(TAG, "【提示音】内置音效调度失败（已忽略）：" + t.getMessage());
        }
    }

    private static short[] buildPcm(int freq, int durMs) {
        int samples = SAMPLE_RATE * durMs / 1000;
        short[] pcm = new short[samples];
        int ramp = SAMPLE_RATE * RAMP_MS / 1000;
        if (ramp < 1) ramp = 1;
        for (int i = 0; i < samples; i++) {
            double env;
            if (i < ramp) env = (double) i / ramp;
            else if (i > samples - ramp) env = (double) (samples - 1 - i) / ramp;
            else env = 1.0;
            pcm[i] = (short) (Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE) * env * VOLUME);
        }
        return pcm;
    }

    private static void playPcm(Context c, short[] pcm) {
        AudioTrack track = null;
        int stream = pickStream(c);
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(stream == AudioManager.STREAM_MUSIC
                            ? AudioAttributes.USAGE_MEDIA : AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(attrs, fmt, Math.max(minBuf, pcm.length * 2),
                    AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.write(pcm, 0, pcm.length);
            track.play();
            CtrlLog.i(TAG, "【提示音】内置音播放 流=" + stream + " 状态=" + track.getState());
            final AudioTrack played = track;
            MAIN.postDelayed(() -> {
                try { played.stop(); } catch (Throwable ignored) {}
                try { played.release(); } catch (Throwable ignored) {}
            }, pcm.length * 1000L / SAMPLE_RATE + 120);
        } catch (Throwable t) {
            if (track != null) {
                try { track.release(); } catch (Throwable ignored) {}
            }
            CtrlLog.w(TAG, "【提示音】AudioTrack 播放失败（已忽略）流=" + stream
                    + " 原因=" + (t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }

    /* ==================== 自定义音频文件 ==================== */

    private static void playUri(final Context c, final String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(c, uri);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(mediaPlayer -> {
                try { mediaPlayer.release(); } catch (Throwable ignored) {}
            });
            mp.setOnErrorListener((mediaPlayer, what, extra) -> {
                CtrlLog.w(TAG, "【提示音】自定义音频播放失败 code=" + what + "/" + extra);
                try { mediaPlayer.release(); } catch (Throwable ignored) {}
                return true;
            });
            mp.prepareAsync();
            CtrlLog.i(TAG, "【提示音】自定义音频异步准备：" + uriStr);
        } catch (Throwable t) {
            CtrlLog.w(TAG, "【提示音】自定义音频加载失败（已忽略）：" + t.getMessage());
        }
    }

    /** 选择当前有音量的音频流：通知音量被调 0 时退到媒体音量，避免"触发了但静音"。 */
    private static int pickStream(Context c) {
        try {
            if (c == null) return AudioManager.STREAM_NOTIFICATION;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return AudioManager.STREAM_NOTIFICATION;
            if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
                return AudioManager.STREAM_NOTIFICATION;
            }
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return AudioManager.STREAM_MUSIC;
            return AudioManager.STREAM_NOTIFICATION;
        } catch (Throwable t) {
            return AudioManager.STREAM_NOTIFICATION;
        }
    }
}
