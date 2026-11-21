package com.example.mzdwidget;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/**
 * 解锁/上锁成功提示音（v1.9.16）。
 *
 * 用 AudioTrack 合成短促正弦波"哔"声，精确控制次数：
 *   解锁 = 一声；上锁 = 两声。
 *
 * 不依赖系统预置提示音语义，无需任何权限与音频文件；带 5ms 渐入渐出防爆音。
 * 全程异常保护——声音只是反馈，任何失败都静默忽略，绝不影响控车主流程。
 * 播放在调用线程发第一声、主线程 Handler 发第二声并负责释放，不阻塞缓存工作线程。
 */
public final class BeepHelper {

    private static final String TAG = "MZDCtrlLog";
    private static final int SAMPLE_RATE = 44100;
    private static final int FREQ_HZ = 880;      // 哔声频率
    private static final int BEEP_MS = 180;      // 单声时长
    private static final int GAP_MS = 200;       // 两声之间的间隔
    private static final int VOLUME = 24000;     // 峰值幅度（约 73% 满幅，车内也能听见）

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 选择当前有音量的音频流：通知音量被调 0 时退到媒体音量，避免"触发了但静音"。 */
    private static int pickStream() {
        try {
            Context c = MzdApp.get();
            if (c == null) return AudioManager.STREAM_NOTIFICATION;
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return AudioManager.STREAM_NOTIFICATION;
            if (am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
                return AudioManager.STREAM_NOTIFICATION;
            }
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) return AudioManager.STREAM_MUSIC;
            return AudioManager.STREAM_NOTIFICATION; // 全为 0 时仍走通知流，记录失败原因
        } catch (Throwable t) {
            return AudioManager.STREAM_NOTIFICATION;
        }
    }

    private BeepHelper() {}

    /** 解锁成功：一声 */
    public static void playUnlock() {
        play(1);
    }

    /** 上锁成功：两声 */
    public static void playLock() {
        play(2);
    }

    private static void play(final int count) {
        try {
            playOne();
            if (count >= 2) {
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() { playOne(); }
                }, BEEP_MS + GAP_MS);
            }
        } catch (Throwable t) {
            CtrlLog.w(TAG, "【提示音】播放调度失败（已忽略）：" + t.getMessage());
        }
    }

    /** 生成并播放单声，播放完立即释放 AudioTrack，避免长期占用音频资源。 */
    private static void playOne() {
        AudioTrack track = null;
        int stream = pickStream();
        try {
            int samples = SAMPLE_RATE * BEEP_MS / 1000;
            short[] pcm = new short[samples];
            int ramp = SAMPLE_RATE * 5 / 1000; // 5ms 渐入渐出，避免爆音
            if (ramp < 1) ramp = 1;
            for (int i = 0; i < samples; i++) {
                double env;
                if (i < ramp) env = (double) i / ramp;
                else if (i > samples - ramp) env = (double) (samples - 1 - i) / ramp;
                else env = 1.0;
                pcm[i] = (short) (Math.sin(2 * Math.PI * FREQ_HZ * i / SAMPLE_RATE) * env * VOLUME);
            }

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
            CtrlLog.i(TAG, "【提示音】已调用 AudioTrack.play() 流=" + stream
                    + " 状态=" + track.getState() + " 时长=" + BEEP_MS + "ms");

            final AudioTrack played = track;
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    try { played.stop(); } catch (Throwable ignored) {}
                    try { played.release(); } catch (Throwable ignored) {}
                }
            }, BEEP_MS + 120);
        } catch (Throwable t) {
            if (track != null) {
                try { track.release(); } catch (Throwable ignored) {}
            }
            CtrlLog.w(TAG, "【提示音】AudioTrack 播放失败（已忽略）流=" + stream
                    + " 原因=" + (t.getMessage() == null ? t.toString() : t.getMessage()));
        }
    }
}
