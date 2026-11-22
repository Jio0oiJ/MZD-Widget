package com.example.mzdwidget;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 把登录凭证加密后写到公共目录，供「静默解锁」「静默上锁」两个 APK 读取。
 * 密钥与那两个 APK 内写死的完全一致；文件内容是密文，不是真的图片。
 *
 * 落点：Download/MZD/mzdcred.jpg（MediaStore，API29+；旧版直接写文件）。
 * 扩展名伪装为 .jpg 是为了在 Android 13+ 也能被 READ_MEDIA_IMAGES / 文件管理器读取。
 */
public final class CredFile {
    private static final String TAG = "MZDCredFile";
    // ==== 三个 APK 必须一致的写死密钥 ====
    private static final String KEY = "MZD2026CredKey01"; // 16 bytes AES-128
    private static final String IV = "MZD2026CredIV001";  // 16 bytes
    public static final String FILE_NAME = "mzdcred.jpg";
    private static final String REL_DIR = "Download/MZD";

    private CredFile() {}

    /** 登录/凭证变化后调用：导出当前 token+carid */
    public static void export(Context c, String token, String carid) {
        if (token == null || token.isEmpty()) return;
        String plain = "MZD1\n" + token + "\n" + (carid == null ? "" : carid);
        byte[] enc;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(KEY.getBytes("UTF-8"), "AES"),
                    new IvParameterSpec(IV.getBytes("UTF-8")));
            enc = Base64.encode(cipher.doFinal(plain.getBytes("UTF-8")), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "加密凭证失败", e);
            return;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                deleteOld(c);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, REL_DIR);
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert 返回 null");
                // v1.9.5：try-with-resources 保证 write 抛异常时流也关闭，不泄漏 fd；
                // 且 IS_PENDING 复位放进 finally，避免写失败后留下永久不可见的 IS_PENDING=1 脏记录。
                try {
                    try (OutputStream os = c.getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new Exception("openOutputStream 返回 null");
                        os.write(enc);
                    }
                    Log.i(TAG, "凭证已导出（MediaStore）：" + uri);
                } finally {
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    c.getContentResolver().update(uri, cv, null, null);
                }
                return;
            } catch (Exception e) {
                Log.e(TAG, "MediaStore 写入失败，尝试直写", e);
            }
        }

        // API28- 或 MediaStore 失败兜底：直接写公共目录
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), REL_DIR);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录 " + dir);
            File f = new File(dir, FILE_NAME);
            // v1.9.23：try-with-resources，write 抛异常时也关闭 fd（与 MediaStore 路径一致）。
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(enc);
            }
            Log.i(TAG, "凭证已导出（直写）：" + f.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "直写凭证失败", e);
        }
    }

    /**
     * 删除 MediaStore 里本应用此前导出的旧凭证记录，避免重复记录。
     * v1.9.4：selection 必须同时限定 DISPLAY_NAME 与 RELATIVE_PATH，
     * 否则会误删设备上其它目录/其它 App 创建的同名 mzdcred.jpg（MediaStore.delete 会连物理文件一起删）。
     */
    private static void deleteOld(Context c) {
        try {
            c.getContentResolver().delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Images.Media.DISPLAY_NAME + "=? AND "
                            + MediaStore.Images.Media.RELATIVE_PATH + "=?",
                    new String[]{FILE_NAME, REL_DIR + "/"});
        } catch (Exception ignored) { }
    }
}
