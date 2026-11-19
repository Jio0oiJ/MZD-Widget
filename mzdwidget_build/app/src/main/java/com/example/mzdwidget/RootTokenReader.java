package com.example.mzdwidget;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 通过 Root 权限，从官方《MZD手机控车》App 的私有目录读取腾讯 MMKV 凭证文件，
 * 顺序解析出 token / carid / userid / 车牌。兼容 Magisk / KernelSU 的 su。
 *
 * 官方 App 包名：com.cxx.a3300kt.jinlan
 * MMKV 默认实例文件：files/mmkv/mmkv.default
 *
 * 【已用官方 App 的真实 data 包验证的解析格式】(protobuf 风格 varint 顺序记录)：
 *   <klen:varint><key:bytes><marker:1byte><vlen:varint><value:bytes>
 *
 * 【KernelSU 路径不可见兜底】
 * 部分 KernelSU/定制 ROM 的 su 会话在 SELinux enforcing 下看不到其他 App 的
 * /data/data 目录，此时 read() 会借官方 App 进程 /proc/<pid>/root/... 访问其数据。
 */
public final class RootTokenReader {

    private static final String PKG = "com.cxx.a3300kt.jinlan";
    public  static final String DEFAULT_PATH = "/data/data/" + PKG + "/files/mmkv/mmkv.default";
    private static final String SRC1 = DEFAULT_PATH;
    private static final String SRC2 = "/data/user/0/" + PKG + "/files/mmkv/mmkv.default";

    private RootTokenReader() {}

    public static class Result {
        public final String token, carid, userid, carno, diag;
        Result(String token, String carid, String userid, String carno, String diag) {
            this.token = token == null ? "" : token;
            this.carid = carid == null ? "" : carid;
            this.userid = userid == null ? "" : userid;
            this.carno = carno == null ? "" : carno;
            this.diag = diag == null ? "" : diag;
        }
        public boolean ok() { return !token.isEmpty() && !carid.isEmpty(); }
        public static Result fail(String diag) { return new Result("", "", "", "", diag); }
    }

    /** 检测 root：su 进程里执行 id，看是否 uid=0 */
    public static boolean hasRoot() {
        try {
            String out = runSu(null, "id\nexit\n");
            return out.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 常规读取：诊断 → 定位并 cat(base64) 出 mmkv.default → 严格顺序解析。
     */
    public static Result read() throws Exception {
        StringBuilder diag = new StringBuilder();

        String id = runSu(null, "id\nexit\n");
        diag.append("【id】").append(id.trim()).append('\n');
        diag.append("【su】").append(runSu(null, "su --version 2>&1; echo ksu=$KSU\nexit\n").trim()).append('\n');

        String ls = runSu(null, "ls -la /data/data/" + PKG + "/files/mmkv/ 2>&1\n"
                + "ls -la /data/user/0/" + PKG + "/files/mmkv/ 2>&1\nexit\n");
        diag.append("【mmkv目录】\n").append(ls.trim()).append('\n');

        Set<String> paths = new LinkedHashSet<>();
        paths.add(SRC1);
        paths.add(SRC2);
        String found = runSu(null,
                "find /data/data/" + PKG + "/files -name 'mmkv*' -type f 2>/dev/null\n"
                + "find /data/user/0/" + PKG + "/files -name 'mmkv*' -type f 2>/dev/null\nexit\n");
        for (String line : found.split("\\r?\\n")) {
            String p = line.trim();
            if (p.startsWith("/") && p.contains("mmkv")) paths.add(p);
        }

        for (String p : paths) {
            Result r = readOnePath(p, diag);
            if (r.ok()) return r;
        }

        // 兜底 1：借官方 App 进程的 /proc/<pid>/root 视图
        Result viaProc = readViaProc();
        diag.append(viaProc.diag);
        if (viaProc.ok()) return viaProc;

        return Result.fail(diag.toString());
    }

    /** 借官方 App 进程 root 视图读取（绕过 su 自身命名空间/SELinux 限制） */
    public static Result readViaProc() {
        StringBuilder diag = new StringBuilder();
        List<String> pids = pids();
        diag.append("【官方App进程pid】").append(pids.isEmpty() ? "(空)" : pids).append('\n');
        for (String pid : pids) {
            for (String suffix : new String[]{
                    SRC1, SRC2,
                    "/data/data/" + PKG + "/files/mmkv/mmkv.default.crc"}) {
                String p = "/proc/" + pid + "/root" + suffix;
                Result r = readOnePath(p, diag);
                if (r.ok()) return r;
            }
        }
        return Result.fail(diag.toString());
    }

    /** 单路径读取：cat | base64 经 stdout 回传（避免二进制失真），再解析 */
    private static Result readOnePath(String path, StringBuilder diag) {
        // v1.9.4：path 会拼进以 root 执行的 su 命令，必须白名单校验，
        // 防止含单引号/$/反引号等 shell 元字符的路径造成命令注入。
        if (path == null || !path.matches("[/A-Za-z0-9_.-]+")) {
            diag.append("【路径不安全，跳过】").append(path).append('\n');
            return Result.fail("");
        }
        try {
            String b64 = runSu(null, "base64 '" + path + "' 2>/dev/null\nexit\n");
            String clean = b64.replaceAll("\\s", "");
            if (clean.length() < 32) {
                String chk = runSu(null, "ls -l '" + path + "' 2>&1\nexit\n").trim();
                diag.append("【读取失败】").append(path).append(' ').append(chk).append('\n');
                return Result.fail("");
            }
            byte[] data = Base64.decode(clean, Base64.DEFAULT);
            Map<String, String> kv = parseMmkv2(data);
            String t = kv.get("smart_car_token"), c = kv.get("car_id");
            String u = kv.get("user_id"), cn = kv.get("car_no");
            diag.append("【解析 ").append(path).append("】bytes=").append(data.length)
                    .append(" keys=").append(kv.size())
                    .append(" token=").append(mask(t))
                    .append(" carid=").append(nullToEmpty(c)).append('\n');
            if (notEmpty(t) && notEmpty(c))
                return new Result(t, c, u, cn, diag.toString());
        } catch (Exception e) {
            diag.append("【异常 ").append(path).append("】").append(e.getMessage()).append('\n');
        }
        return Result.fail("");
    }

    private static List<String> pids() {
        List<String> out = new ArrayList<>();
        try {
            String s = runSu(null, "pidof " + PKG + "\nexit\n");
            for (String x : s.trim().split("\\s+")) if (x.matches("\\d+")) out.add(x);
        } catch (Exception ignored) { }
        return out;
    }

    /**
     * 严格顺序解析 MMKV（已用官方 App 真实 data 包验证）。
     * 格式：<klen:varint><key><marker:1byte><vlen:varint><value>
     * 同一 key 多次出现取最后一次写入（最新）。
     */
    public static Map<String, String> parseMmkv2(byte[] data) {
        Map<String, String> kv = new LinkedHashMap<>();
        int[] pos = {8};
        while (pos[0] < data.length - 1) {
            int start = pos[0];
            try {
                long klen = readVarint(data, pos);
                if (klen <= 0 || klen > 200) { pos[0] = start + 1; continue; }
                byte[] kb = new byte[(int) klen];
                if (pos[0] + klen > data.length) break;
                System.arraycopy(data, pos[0], kb, 0, (int) klen);
                pos[0] += (int) klen;
                String key = new String(kb, StandardCharsets.US_ASCII);
                if (!isAsciiPrintable(key)) { pos[0] = start + 1; continue; }

                pos[0] += 1; // 跳过 1 字节 marker

                long vlen = readVarint(data, pos);
                if (vlen < 0 || vlen > 100000 || pos[0] + vlen > data.length) {
                    pos[0] = start + 1; continue;
                }
                byte[] vb = new byte[(int) vlen];
                System.arraycopy(data, pos[0], vb, 0, (int) vlen);
                pos[0] += (int) vlen;

                String val = new String(vb, StandardCharsets.UTF_8);
                if (isUtf8Printable(val)) kv.put(key, val);
            } catch (Exception e) {
                pos[0] = start + 1;
            }
        }
        return kv;
    }

    private static long readVarint(byte[] data, int[] pos) {
        long result = 0;
        int shift = 0;
        while (pos[0] < data.length && shift < 35) {
            int b = data[pos[0]++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        return result;
    }

    private static boolean isAsciiPrintable(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 32 || ch > 126) return false;
        }
        return true;
    }

    private static boolean isUtf8Printable(String s) {
        if (s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 32 && ch != '\n' && ch != '\r' && ch != '\t') return false;
        }
        return true;
    }

    private static String mask(String s) {
        if (!notEmpty(s)) return "(空)";
        if (s.length() <= 8) return s.charAt(0) + "***";
        return s.substring(0, 4) + "***" + s.substring(s.length() - 4);
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** 启动 su（可带 -mm 等参数），通过 stdin 写命令，读取 stdout */
    private static String runSu(String flag, String script) throws Exception {
        ProcessBuilder pb = (flag == null) ? new ProcessBuilder("su") : new ProcessBuilder("su", flag);
        Process p = pb.start();
        // v1.9.12：并发读取 stdout/stderr，避免"先读 stdout 到 EOF 再读 stderr"的双流死锁
        // （su 若把诊断写到 stderr 并填满管道缓冲区，进程阻塞在写 stderr → stdout 永不 EOF → 永久卡住）。
        final String[] outHolder = {""};
        Thread outThread = new Thread(() -> {
            try { outHolder[0] = readAll(p.getInputStream()); } catch (Exception ignored) { }
        }, "mzd-su-out");
        Thread errThread = new Thread(() -> {
            try { readAll(p.getErrorStream()); } catch (Exception ignored) { }
        }, "mzd-su-err");
        outThread.setDaemon(true);
        errThread.setDaemon(true);
        outThread.start();
        errThread.start();
        try {
            OutputStream os = p.getOutputStream();
            os.write(script.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            // v1.9.12：超时判断必须先于对读取结果的等待——原实现把阻塞的 readAll 放在
            // waitFor 之前，su 卡在授权弹窗时 readAll 永久阻塞，30 秒超时形同虚设。
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new Exception("su 命令超时（30 秒），已终止");
            }
            // 进程已退出，有限等待读取线程收尾（正常会立即结束）；绝不无限 join。
            try { outThread.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return outHolder[0];
        } finally {
            // 兜底：任何异常路径都不留下存活的 su 进程
            if (p.isAlive()) p.destroyForcibly();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] c = new byte[4096];
        int n;
        while ((n = in.read(c)) != -1) b.write(c, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
