package com.example.mzdwidget;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 逆向得到的控车协议实现。纯 JDK/Android 平台 API，无第三方依赖。
 */
public final class Api {

    // 从 APK 逆向得到的服务端配置
    private static final String BASE = "https://iov.edaoduo.com/aoduo/prod/api/v2";
    private static final String LOGIN_PATH = "/user/login";
    private static final String CTRL_PATH = "/car/ctrl";
    private static final String STATUS_PATH = "/car/status";

    // 登录密码加密
    private static final String PWD_KEY = "kSTWuwVyH9YCnNnR";
    private static final String PWD_IV = "3138995984284342";

    // 公共请求头 / 签名
    private static final String NONCE_PREFIX = "aod12346";
    private static final String APP_ID_PREFIX = "00010800020004";
    private static final String APP_VERSION_CODE = "26082001"; // 原 App versionCode，服务端可能校验
    private static final String APIVER = "v3";

    public static final String CMD_START = "start";
    public static final String CMD_STALL = "stall";
    public static final String CMD_UNLOCK = "unlock";
    public static final String CMD_LOCK = "lock";
    public static final String CMD_SEARCH = "search"; // 寻车（闪灯/鸣笛），逆向自原 App CarCtrlActivity

    private Api() {}

    /** 登录结果 */
    public static class LoginResult {
        public String token;
        public String carid;
        public String userid;
        public String mobile;
    }

    /**
     * 登录。返回 token/carid。失败抛异常。
     */
    public static LoginResult login(String phone, String password) throws Exception {
        String encPwd = encryptPassword(password);
        String url = BASE + LOGIN_PATH;
        StringBuilder body = new StringBuilder();
        body.append("phone=").append(urlEncode(phone));
        body.append("&passwd=").append(urlEncode(encPwd));

        String json = post(url, body.toString(), null /* 登录时无 token */);
        JSONObject o = new JSONObject(json);
        // 兼容常见的响应包装：直接对象，或 {data:{...}} / {result:{...}}
        JSONObject d = o;
        if (o.has("data") && o.opt("data") instanceof JSONObject) d = o.getJSONObject("data");
        else if (o.has("result") && o.opt("result") instanceof JSONObject) d = o.getJSONObject("result");

        LoginResult r = new LoginResult();
        r.token = pick(d, o, "token");
        r.carid = pick(d, o, "carid");
        r.userid = pick(d, o, "userid");
        r.mobile = pick(d, o, "mobile");
        if (r.token == null || r.token.isEmpty()) {
            // 不把完整响应体拼进异常消息（可能含手机号/userid 等敏感信息，
            // 且会被上层 shortErr 截断后写入日志）。只取状态/提示字段便于排查（v1.9.4）。
            String status = o.has("status") ? String.valueOf(o.opt("status")) : "?";
            String message = o.optString("message", "");
            throw new Exception("登录响应中没有 token（status=" + status
                    + (message.isEmpty() ? "" : "，" + message) + "）");
        }
        return r;
    }

    private static String pick(JSONObject a, JSONObject b, String key) {
        String v = a.optString(key, null);
        if (v == null || v.isEmpty()) v = b.optString(key, null);
        return v;
    }

    /**
     * 发送控车指令。cmd ∈ {start, stall, unlock, lock}。
     * bootTime: 仅当 >=0 时作为 boot_time 发送（原 App 发动时传 15）。
     */
    public static String control(String token, String carid, String cmd, int bootTime) throws Exception {
        String url = BASE + CTRL_PATH;
        StringBuilder body = new StringBuilder();
        body.append("carid=").append(urlEncode(carid == null ? "" : carid));
        body.append("&ctrl=").append(urlEncode(cmd));
        if (bootTime >= 0) body.append("&boot_time=").append(bootTime);
        return post(url, body.toString(), token);
    }

    /** 车辆状态（用于小组件左侧显示） */
    public static class CarState {
        public Boolean started;      // 发动机是否启动
        public Boolean unlocked;     // 是否处于解锁状态
        public String mileage;       // 里程（主界面车况详情显示）
    }

    /**
     * 查询车辆状态：GET {baseUrl}/car/status?carid=xxx
     * 返回结构兼容 {data:{...}} 或直接对象。
     */
    public static CarState getStatus(String token, String carid) throws Exception {
        String url = BASE + STATUS_PATH + "?carid=" + urlEncode(carid == null ? "" : carid);
        String json = get(url, token);
        JSONObject root = new JSONObject(json);
        JSONObject d = root;
        if (root.has("data") && root.opt("data") instanceof JSONObject) d = root.getJSONObject("data");
        else if (root.has("result") && root.opt("result") instanceof JSONObject) d = root.getJSONObject("result");

        CarState s = new CarState();
        // engine == 3 表示已启动（逆向自原 App VehicleStatusWorker）
        // v1.9.6：JSONObject.has() 对值为 NULL 的键也返回 true，optInt 解析 "null" 会取默认 0
        // → 把「engine:null（未知）」误判成「明确未启动(false)」。改为先排除 null/NULL：
        // 缺失或显式 null 时保持 started=null（未知）交给上层按不可用处理；
        // 真值仍用 optInt 解析，保留对字符串数字（如 "3"）的兼容。
        Object eng = d.opt("engine");
        if (eng != null && eng != JSONObject.NULL) s.started = (d.optInt("engine", 0) == 3);
        s.mileage = d.optString("mileage", "");
        // lock: lf/lr/rf/rr 任一 ==1 表示未上锁
        // v1.9.5：仅当至少有任一门字段存在时才写 unlocked。若 lock 对象存在但子字段全缺
        // （服务端降级/部分响应），anyEqualsOne 会把四个 optInt 全取默认 0 → false（误判「已锁」），
        // 从而绕过 CarActions.statusUnavailable 的「字段全缺=车况不可用」保护，导致凭不可靠状态下发解锁。
        JSONObject lock = d.optJSONObject("lock");
        if (lock != null) {
            // v1.9.6：has() 对值为 NULL 的键也返回 true，需排除——四个门字段全为 null 时
            // 不能算「有效门数据」，否则 unlocked 会被算成 false（已锁）绕过不可用保护。
            boolean hasDoorField = false;
            for (String k : new String[]{"lf", "lr", "rf", "rr"}) {
                Object v = lock.opt(k);
                if (v != null && v != JSONObject.NULL) { hasDoorField = true; break; }
            }
            if (hasDoorField) s.unlocked = anyEqualsOne(lock, "lf", "lr", "rf", "rr");
        }
        // v1.9.12：HTTP 200 但业务降级/异常（如 status:500、result:null、字段全缺）会解析出
        // started/unlocked/mileage 全空的对象。若当作成功返回，CarStateCache.queryOnce 的
        // ok=(s!=null) 恒为 true → 污染缓存、清空 lastError、推进 successSeq，破坏"本轮成功"语义。
        // 与 CarActions.statusUnavailable 口径一致：三个字段全缺视为车况不可用，抛异常走失败分支。
        if (s.started == null && s.unlocked == null && (s.mileage == null || s.mileage.isEmpty())) {
            throw new Exception("车况响应无有效字段（业务降级或异常）");
        }
        return s;
    }

    private static boolean anyEqualsOne(JSONObject o, String... keys) {
        for (String k : keys) if (o.optInt(k, 0) == 1) return true;
        return false;
    }

    /** 带公共头 + 签名的 GET */
    private static String get(String url, String token) throws Exception {
        long tsSec = System.currentTimeMillis() / 1000L;
        int rand = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
        String timestamp = Long.toString(tsSec);
        String nonce = NONCE_PREFIX + rand;
        // URL query 参数参与签名：把 carid=xxx 加入签名项
        String sig;
        {
            java.util.List<String> signs = new java.util.ArrayList<>();
            signs.add("timestamp=" + timestamp);
            signs.add("nonce=" + nonce);
            int q = url.indexOf('?');
            if (q >= 0 && q + 1 < url.length()) {
                for (String p : url.substring(q + 1).split("&")) if (!p.isEmpty()) signs.add(p);
            }
            java.util.Collections.sort(signs);
            StringBuilder sb = new StringBuilder();
            for (String x : signs) sb.append(x).append('&');
            sb.append(token == null ? "" : token);
            sig = md5Hex(sb.toString()).toLowerCase();
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("timestamp", timestamp);
            conn.setRequestProperty("nonce", nonce);
            conn.setRequestProperty("signature", sig);
            conn.setRequestProperty("apiver", APIVER);
            conn.setRequestProperty("X-App-Identifier", APP_ID_PREFIX + APP_VERSION_CODE);
            if (token != null && !token.isEmpty()) conn.setRequestProperty("token", token);

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is); // readAll 内部会关闭流
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code + "：" + resp);
            checkBusinessError(resp);
            return resp;
        } finally {
            // 异常路径也要释放连接，避免每 3 秒轮询累积半开连接/FD（v1.9.4）
            conn.disconnect();
        }
    }

    /**
     * 业务码检查：实测服务端会返回 HTTP 200 但 body 为
     * {"status":401,"message":"无权访问或登录失效","result":null}。
     * 只拦截明确的 401（鉴权失效），避免把 token 过期误判为指令成功——
     * 否则会导致解锁闩锁错误置位、离开时误发上锁。0/200/其他值不拦截。
     */
    private static void checkBusinessError(String json) throws Exception {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("status") && o.optInt("status", -1) == 401) {
                String msg = o.optString("message", "");
                throw new Exception("登录失效（业务码 401）"
                        + (msg.isEmpty() ? "" : "：" + msg) + "，请重新获取 Token");
            }
        } catch (org.json.JSONException ignored) {
            // 非 JSON body：交给上层解析，不在此拦截
        }
    }

    /** 带公共头 + 签名的 POST */
    private static String post(String url, String body, String token) throws Exception {
        long tsSec = System.currentTimeMillis() / 1000L;
        int rand = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
        String timestamp = Long.toString(tsSec);
        String nonce = NONCE_PREFIX + rand;
        String signature = signature(timestamp, nonce, token == null ? "" : token);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("timestamp", timestamp);
            conn.setRequestProperty("nonce", nonce);
            conn.setRequestProperty("signature", signature);
            conn.setRequestProperty("apiver", APIVER);
            conn.setRequestProperty("X-App-Identifier", APP_ID_PREFIX + APP_VERSION_CODE);
            if (token != null && !token.isEmpty()) conn.setRequestProperty("token", token);

            // try-with-resources：write 抛异常时也能关闭输出流（v1.9.4）
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is); // readAll 内部会关闭流
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + "：" + resp);
            }
            checkBusinessError(resp);
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 复刻 RxHttpManager.getmySignature：
     * 对 ["timestamp="+ts, "nonce=aod12346"+rand] 字典序排序，
     * 逐项拼接 + "&"，末尾追加 token，MD5 后转小写。
     */
    private static String signature(String timestamp, String nonce, String token) throws Exception {
        List<String> signs = new ArrayList<>();
        signs.add("timestamp=" + timestamp);
        signs.add("nonce=" + nonce);
        Collections.sort(signs);
        StringBuilder sb = new StringBuilder();
        for (String s : signs) sb.append(s).append('&');
        sb.append(token == null ? "" : token);
        return md5Hex(sb.toString()).toLowerCase();
    }

    /** 登录密码：AES/CBC/PKCS7Padding(key,iv) 后 Base64 NO_WRAP */
    private static String encryptPassword(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS7Padding");
        SecretKeySpec key = new SecretKeySpec(PWD_KEY.getBytes(StandardCharsets.UTF_8), "AES");
        IvParameterSpec iv = new IvParameterSpec(PWD_IV.getBytes(StandardCharsets.UTF_8));
        c.init(Cipher.ENCRYPT_MODE, key, iv);
        byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(enc, Base64.NO_WRAP);
    }

    private static String md5Hex(String s) throws Exception {
        byte[] md5 = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[md5.length * 2];
        for (int i = 0; i < md5.length; i++) {
            int v = md5[i] & 0xFF;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0F];
        }
        return new String(out);
    }

    private static String urlEncode(String s) throws Exception {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
