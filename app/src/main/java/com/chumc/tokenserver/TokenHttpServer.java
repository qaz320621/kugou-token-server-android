package com.chumc.tokenserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

/**
 * 酷狗插件 Token 管理服务（Android 版 v2）—— 支持扫码登录（中间人）
 * - GET /login/qr      生成酷狗官方登录二维码（PNG），并启动后台轮询
 * - GET /login/status  轮询状态: waiting / scanned / success / fail
 * - GET /token         返回配置 JSON（插件拉取）
 * - POST /save         手动保存配置（备用）
 * - GET /              管理网页（扫码登录 + 手动配置）
 */
public class TokenHttpServer extends NanoHTTPD {

    public static final int PORT = 8765;
    private static final String PREFS = "token_config";
    private static final String KEY = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String QRCODE_H5 = "https://h5.kugou.com/apps/loginQRCode/html/index.html";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

    private final Context context;
    // 当前登录会话
    private volatile String loginMid = "";
    private volatile String loginDfid = "";
    private volatile String loginCode = "";
    private volatile String loginState = "idle"; // idle/waiting/scanned/success/fail
    private volatile String loginUserid = "";
    private volatile String loginToken = "";
    private volatile String loginNickname = "";
    private volatile String loginError = "";

    public TokenHttpServer(Context context) {
        super(PORT);
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- 酷狗扫码登录（中间人） ----------

    private String kgSign(Map<String, String> params) {
        try {
            java.util.List<String> keys = new java.util.ArrayList<>(params.keySet());
            java.util.Collections.sort(keys);
            StringBuilder joined = new StringBuilder();
            for (String k : keys) {
                joined.append(k).append('=').append(params.get(k));
            }
            return md5(KEY + joined + KEY);
        } catch (Exception e) {
            return "";
        }
    }

    private String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private JSONObject kgGet(String base, java.util.Map<String, String> extra) {
        try {
            java.util.Map<String, String> p = new java.util.HashMap<>();
            p.put("appid", "1014");
            p.put("srcappid", "2919");
            p.put("clientver", "20000");
            p.put("clienttime", String.valueOf(System.currentTimeMillis()));
            p.put("mid", loginMid);
            p.put("uuid", loginMid);
            p.put("dfid", loginDfid);
            p.put("plat", "4");
            if (extra != null) {
                p.putAll(extra);
            }
            if (p.containsKey("qrcode_txt")) {
                p.put("qrcode_txt", URLEncoder.encode(p.get("qrcode_txt"), "UTF-8"));
            }
            p.put("signature", kgSign(p));
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, String> e : p.entrySet()) {
                if (qs.length() > 0) qs.append('&');
                qs.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "?" + qs).openConnection();
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.kugou.com/");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            InputStream is = conn.getInputStream();
            String resp = new String(readAll(is), StandardCharsets.UTF_8);
            return new JSONObject(resp);
        } catch (Exception e) {
            try {
                return new JSONObject().put("status", 0).put("error", String.valueOf(e));
            } catch (Exception e2) {
                return new JSONObject();
            }
        }
    }

    private byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private String genMid() {
        try {
            return md5(UUID.randomUUID().toString());
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private String genDfid() {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /** 生成官方二维码，返回二维码内容（URL），失败返回 null */
    private String startLogin() {
        loginMid = genMid();
        loginDfid = genDfid();
        loginState = "idle";
        loginError = "";
        java.util.Map<String, String> extra = new java.util.HashMap<>();
        extra.put("type", "1");
        extra.put("qrcode_txt", QRCODE_H5 + "?appid=1014&");
        JSONObject j = kgGet("https://login-user.kugou.com/v2/qrcode", extra);
        if (j.optInt("status") != 1) {
            loginState = "fail";
            loginError = "获取二维码失败: " + j.optString("data");
            return null;
        }
        loginCode = j.optJSONObject("data").optString("qrcode");
        loginState = "waiting";
        Thread t = new Thread(this::pollLogin);
        t.setDaemon(true);
        t.start();
        try {
            return QRCODE_H5 + "?appid=1014&qrcode=" + loginCode
                    + "&name=" + URLEncoder.encode("酷狗登录确认", "UTF-8");
        } catch (Exception e) {
            return QRCODE_H5 + "?appid=1014&qrcode=" + loginCode;
        }
    }

    private void pollLogin() {
        for (int i = 0; i < 150; i++) { // 5 分钟
            java.util.Map<String, String> extra = new java.util.HashMap<>();
            extra.put("qrcode", loginCode);
            JSONObject j = kgGet("https://login-user.kugou.com/v2/get_userinfo_qrcode", extra);
            int st = j.optJSONObject("data") == null ? 0 : j.optJSONObject("data").optInt("status", 0);
            if (st == 2) {
                JSONObject d = j.optJSONObject("data");
                loginState = "scanned";
                loginNickname = d.optString("nickname", "");
                loginUserid = String.valueOf(d.optLong("userid", 0));
            } else if (st == 4) {
                JSONObject d = j.optJSONObject("data");
                loginState = "success";
                loginUserid = String.valueOf(d.optLong("userid", 0));
                loginToken = d.optString("token", "");
                loginNickname = d.optString("nickname", "");
                prefs().edit()
                        .putString("userid", loginUserid)
                        .putString("token", loginToken)
                        .putString("mid", loginMid)
                        .putString("dfid", loginDfid)
                        .putLong("updated_at", System.currentTimeMillis())
                        .apply();
                return;
            } else if (st == 0) {
                loginState = "fail";
                loginError = "二维码已过期或已取消";
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
        loginState = "fail";
        loginError = "登录超时";
    }

    /** ZXing 生成二维码 PNG 字节 */
    private byte[] makeQrPng(String text) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, 300, 300);
            Bitmap bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 300; x++) {
                for (int y = 0; y < 300; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- HTTP 路由 ----------

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        String method = session.getMethod().name();

        if ("/token".equals(uri) && "GET".equals(method)) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("userid", prefs().getString("userid", ""))
                        .put("token", prefs().getString("token", ""))
                        .put("mid", prefs().getString("mid", ""))
                        .put("dfid", prefs().getString("dfid", ""));
            } catch (Exception ignored) {
            }
            return jsonResponse(Response.Status.OK, obj.toString());
        }

        if ("/save".equals(uri) && "POST".equals(method)) {
            try {
                Map<String, String> files = new java.util.HashMap<>();
                session.parseBody(files);
                String body = files.get("postData");
                if (body == null || body.isEmpty()) {
                    return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"empty body\"}");
                }
                JSONObject data = new JSONObject(body);
                prefs().edit()
                        .putString("userid", data.optString("userid", ""))
                        .putString("token", data.optString("token", ""))
                        .putString("mid", data.optString("mid", ""))
                        .putString("dfid", data.optString("dfid", ""))
                        .putLong("updated_at", System.currentTimeMillis())
                        .apply();
                return jsonResponse(Response.Status.OK, "{\"status\":\"saved\"}");
            } catch (Exception e) {
                return jsonResponse(Response.Status.BAD_REQUEST,
                        "{\"error\":\"" + e.getClass().getSimpleName() + "\"}");
            }
        }

        if ("/login/qr".equals(uri) && "GET".equals(method)) {
            String qrContent = startLogin();
            if (qrContent == null) {
                return jsonResponse(Response.Status.INTERNAL_ERROR,
                        "{\"error\":\"" + loginError + "\"}");
            }
            byte[] png = makeQrPng(qrContent);
            if (png == null) {
                return jsonResponse(Response.Status.INTERNAL_ERROR, "{\"error\":\"qr render\"}");
            }
            Response resp = newFixedLengthResponse(Response.Status.OK, "image/png",
                    new java.io.ByteArrayInputStream(png), png.length);
            resp.addHeader("Access-Control-Allow-Origin", "*");
            return resp;
        }

        if ("/login/status".equals(uri) && "GET".equals(method)) {
            JSONObject obj = new JSONObject();
            try {
                obj.put("state", loginState)
                        .put("userid", loginUserid)
                        .put("nickname", loginNickname)
                        .put("token", loginToken)
                        .put("mid", loginMid)
                        .put("dfid", loginDfid)
                        .put("error", loginError);
            } catch (Exception ignored) {
            }
            return jsonResponse(Response.Status.OK, obj.toString());
        }

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE_HTML);
    }

    private Response jsonResponse(Response.Status status, String json) {
        Response resp = newFixedLengthResponse(status, "application/json; charset=utf-8", json);
        resp.addHeader("Access-Control-Allow-Origin", "*");
        resp.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return resp;
    }

    private static final String PAGE_HTML = "<!DOCTYPE html>\n"
            + "<html lang=\"zh\"><head><meta charset=\"utf-8\"><title>酷狗 Token 管理</title>\n"
            + "<style>body{font-family:system-ui;max-width:560px;margin:24px auto;padding:0 16px;background:#f5f6f8}"
            + "h1{font-size:20px}label{display:block;margin:12px 0 4px;font-size:13px;color:#444}"
            + "input{width:100%;padding:8px;border:1px solid #ccc;border-radius:6px;box-sizing:border-box;font-family:monospace}"
            + "button{margin-top:10px;width:100%;padding:10px;background:#2f6fed;color:#fff;border:none;border-radius:6px;font-size:15px;cursor:pointer}"
            + ".card{background:#fff;border-radius:10px;padding:16px;margin-bottom:16px;box-shadow:0 1px 4px rgba(0,0,0,.08)}"
            + "#qrArea{text-align:center;min-height:120px;padding:12px}"
            + "#qrImg{width:220px;height:220px;display:none;margin:0 auto}"
            + "#state{font-size:14px;margin-top:10px;color:#666}"
            + "#msg{margin-top:10px;font-size:13px;color:#2e7d32}"
            + ".hint{background:#fff8e1;border:1px solid #ffe082;padding:10px;border-radius:6px;font-size:12px;line-height:1.6;margin-top:12px}</style>\n"
            + "</head><body>\n"
            + "<h1>酷狗 Token 管理（扫码登录）</h1>\n"
            + "<div class=\"card\">"
            + "<button onclick=\"startLogin()\">📱 扫码登录</button>"
            + "<div id=\"qrArea\"><div id=\"state\">点击上方按钮生成二维码</div><img id=\"qrImg\"></div>"
            + "</div>\n"
            + "<div class=\"card\"><h3>手动配置（备用）</h3>\n"
            + "<form id=\"f\">"
            + "<label>userid</label><input name=\"userid\" required>"
            + "<label>token</label><input name=\"token\" required>"
            + "<label>mid</label><input name=\"mid\" required>"
            + "<label>dfid</label><input name=\"dfid\" required>"
            + "<button type=\"submit\">保存 Token</button></form>"
            + "<div id=\"msg\"></div>"
            + "<div class=\"hint\"><b>备用获取方式：</b>浏览器登录 www.kugou.com → F12 → Network → 找 wwwapi/gateway 请求，复制 userid/token/mid/dfid</div>"
            + "</div>\n"
            + "<script>\n"
            + "let pollTimer=null;\n"
            + "async function startLogin(){"
            + "document.getElementById('state').textContent='正在生成二维码...';"
            + "const r=await fetch('/login/qr');"
            + "if(r.ok){"
            + "document.getElementById('qrImg').src='/login/qr?t='+Date.now();"
            + "document.getElementById('qrImg').style.display='block';"
            + "document.getElementById('state').textContent='请用手机酷狗App扫码，然后在手机上确认登录';"
            + "pollTimer=setInterval(pollStatus,2000);"
            + "}else{document.getElementById('state').textContent='生成失败';}"
            + "}\n"
            + "async function pollStatus(){"
            + "const j=await (await fetch('/login/status')).json();"
            + "if(j.state==='scanned'){document.getElementById('state').textContent='已扫码，请在手机/确认页点【确认登录】...';}"
            + "else if(j.state==='success'){"
            + "document.getElementById('state').textContent='✅ 登录成功：'+j.nickname+' (userid='+j.userid+')，token 已保存';"
            + "clearInterval(pollTimer);"
            + "['userid','token','mid','dfid'].forEach(k=>{const el=document.querySelector('[name='+k+']');if(el&&j[k])el.value=j[k];});"
            + "}else if(j.state==='fail'){document.getElementById('state').textContent='❌ '+j.error;clearInterval(pollTimer);}"
            + "}\n"
            + "fetch('/token').then(r=>r.json()).then(c=>{['userid','token','mid','dfid'].forEach(k=>{const el=document.querySelector('[name='+k+']');if(el)el.value=c[k]||'';});});\n"
            + "document.getElementById('f').onsubmit=async e=>{e.preventDefault();"
            + "const d=Object.fromEntries(new FormData(e.target));"
            + "const r=await fetch('/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});"
            + "document.getElementById('msg').textContent=await r.text();};\n"
            + "</script></body></html>";
}
