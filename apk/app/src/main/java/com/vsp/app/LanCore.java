package com.vsp.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.net.Uri;

/**
 * 全能工具箱 —— 局域网直连核心。
 * UDP 广播发现 + 内嵌 HTTP 服务（文件互传 / 屏幕查看 / 输入注入）+ HTTP 客户端。
 * 两端协议完全一致：全部走局域网 IP，不经公网服务器、不消耗移动流量。
 * 仅接受私网地址，且所有请求必须带配对令牌 TOKEN。
 */
public final class LanCore {

    public static final int UDP_PORT = 45320;
    public static final int HTTP_PORT = 45321;
    public static final String TOKEN = "TBOX2026";
    private static final String TAG = "LanCore";

    public static final class Device {
        public final String id, name, kind, ip;
        public final int port;
        public long lastSeen;
        public Device(String id, String name, String kind, String ip, int port) {
            this.id = id; this.name = name; this.kind = kind; this.ip = ip; this.port = port;
            this.lastSeen = System.currentTimeMillis();
        }
        public boolean isAndroid() { return "android".equalsIgnoreCase(kind); }
    }

    public static final class FileEntry {
        public final String name, path;
        public final boolean dir;
        public final long size;
        public FileEntry(String name, String path, boolean dir, long size) {
            this.name = name; this.path = path; this.dir = dir; this.size = size;
        }
    }

    public static final class PairRequest {
        public final String id, name, kind, ip;
        public final int port;
        public PairRequest(String id, String name, String kind, String ip, int port) {
            this.id = id; this.name = name; this.kind = kind; this.ip = ip; this.port = port;
        }
    }

    public interface Callback {
        void onDevicesChanged();
        void onStatus(String text);
        /** 对方发来连接申请，UI 应弹窗让用户选择同意/拒绝 */
        default void onPairRequest(PairRequest req) { }
    }

    private final Context ctx;
    private final File shareDir;
    private final String deviceId;
    private final String deviceName;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final java.util.Set<String> localIps = new java.util.HashSet<>();
    private final Map<String, PairRequest> pairRequests = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pairAnswers = new ConcurrentHashMap<>();
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    private volatile boolean running = false;
    private DatagramSocket udp;
    private ServerSocket http;
    private String localIp = "";

    // 被控：屏幕捕获由 LanScreenService（mediaProjection 前台服务）承载，
    // 这里只通过静态方法取帧，不再直接持有 MediaProjection。

    public LanCore(Context ctx, File shareDir, String deviceName, Callback callback) {
        this.ctx = ctx.getApplicationContext();
        this.shareDir = shareDir;
        this.deviceName = deviceName;
        this.callback = callback;
        this.deviceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try { shareDir.mkdirs(); } catch (Throwable ignored) { }
    }

    public String getLocalIp() { return localIp; }
    public List<Device> getDevices() {
        List<Device> list = new ArrayList<>(devices.values());
        list.sort(Comparator.comparing((Device d) -> d.isAndroid()).thenComparing(d -> d.name));
        return list;
    }

    public void start() {
        if (running) return;
        running = true;
        localIp = findPrivateIp();
        collectLocalIps();
        // Android 必须持有 MulticastLock 才能收发 UDP 广播/组播，
        // 否则多数机型（尤其息屏后）静默丢弃广播包，导致两端互相发现不了。
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("toolbox-lan");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Throwable ignored) { }
        new Thread(this::discoverLoop, "lan-discover").start();
        new Thread(this::httpLoop, "lan-http").start();
        status("正在发现附近设备…");
    }

    public void stop() {
        running = false;
        try { if (udp != null) udp.close(); } catch (Throwable ignored) { }
        try { if (http != null) http.close(); } catch (Throwable ignored) { }
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) { }
        stopProjectionService();
        pool.shutdownNow();
    }

    // ---------------- 设备发现 ----------------

    private void discoverLoop() {
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.setBroadcast(true);
            udp.bind(new InetSocketAddress(UDP_PORT));
        } catch (Throwable t) { status("发现服务启动失败：" + t.getMessage()); return; }

        final byte[] hello = helloJson().getBytes(StandardCharsets.UTF_8);
        // Android 10+ 对受限广播 255.255.255.255 有系统限制，很多机型直接丢弃，
        // 导致 PC 永远收不到 hello。改用每个网卡的子网定向广播地址（如 192.168.1.255），
        // 这是 Android 上可靠发送 UDP 广播的标准做法；全局广播仅作为兜底保留。
        final java.util.List<InetAddress> broadcastTargets = broadcastAddresses();
        pool.execute(() -> {
            while (running) {
                for (InetAddress target : broadcastTargets) {
                    try {
                        udp.send(new DatagramPacket(hello, hello.length, target, UDP_PORT));
                    } catch (Throwable ignored) { }
                }
                sleep(2000);
            }
        });

        // 超时剔除线程（必须在阻塞的 receive 循环之前启动，否则永远执行不到）
        pool.execute(() -> {
            while (running) {
                sleep(3000);
                long cutoff = System.currentTimeMillis() - 9000;
                boolean removed = false;
                Iterator<Map.Entry<String, Device>> it = devices.entrySet().iterator();
                while (it.hasNext()) {
                    if (it.next().getValue().lastSeen < cutoff) { it.remove(); removed = true; }
                }
                if (removed) changed();
            }
        });

        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                String ip = p.getAddress().getHostAddress();
                if (!isPrivate(ip) || localIps.contains(ip)) continue;
                JSONObject o = new JSONObject(new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8));
                if (!TOKEN.equals(o.optString("k"))) continue;
                String id = o.optString("id");
                if (id.isEmpty() || id.equals(deviceId)) continue;
                Device d = new Device(id, o.optString("n", "设备"), o.optString("t", "pc"),
                        ip, o.optInt("p", HTTP_PORT));
                devices.put(id, d);
                changed();
            } catch (Throwable ignored) { }
        }
    }

    /** 收集所有私网网卡的子网定向广播地址；拿不到时用全局广播兜底。 */
    private java.util.List<InetAddress> broadcastAddresses() {
        java.util.List<InetAddress> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof java.net.Inet4Address)) continue;
                    if (!isPrivate(addr.getHostAddress())) continue;
                    InetAddress bc = ia.getBroadcast();
                    if (bc != null && seen.add(bc.getHostAddress())) list.add(bc);
                }
            }
        } catch (Throwable ignored) { }
        // 兜底：全局广播 + 从已发现的私网 IP 推出 /24 广播地址
        try {
            InetAddress all = InetAddress.getByName("255.255.255.255");
            if (seen.add(all.getHostAddress())) list.add(all);
        } catch (Throwable ignored) { }
        if (list.isEmpty()) {
            String ip = localIp;
            if (isPrivate(ip)) {
                String[] b = ip.split("\\.");
                try {
                    InetAddress bc = InetAddress.getByName(b[0] + "." + b[1] + "." + b[2] + ".255");
                    if (seen.add(bc.getHostAddress())) list.add(bc);
                } catch (Throwable ignored) { }
            }
        }
        return list;
    }

    private String helloJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", deviceId); o.put("n", deviceName); o.put("t", "android");
            o.put("p", HTTP_PORT); o.put("k", TOKEN);
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }

    // ---------------- HTTP 服务（被控 / 被传） ----------------

    private void httpLoop() {
        try {
            http = new ServerSocket(HTTP_PORT);
        } catch (Throwable t) { status("HTTP 服务启动失败：" + t.getMessage()); return; }
        while (running) {
            try {
                Socket s = http.accept();
                pool.execute(() -> handle(s));
            } catch (Throwable ignored) { }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) { s.close(); return; }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { s.close(); return; }
            String method = parts[0];
            String target = parts[1];

            Uri uri = Uri.parse("http://x" + target);
            String k = uri.getQueryParameter("k");
            if (!TOKEN.equals(k)) { respond(out, 403, "text/plain", "forbidden".getBytes()); s.close(); return; }

            int len = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    try {
                        len = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    } catch (Throwable ignored) { }
                }
            }
            if (len < 0 || len > 64 * 1024 * 1024) {
                respond(out, 413, "text/plain", "too large".getBytes());
                return;
            }
            byte[] body = new byte[len];
            if (len > 0) {
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off < len) { respond(out, 400, "text/plain", "bad request".getBytes()); return; }
            }

            String path = uri.getPath();
            if ("/hello".equals(path)) {
                respond(out, 200, "text/plain; charset=utf-8", (deviceName + "|android").getBytes(StandardCharsets.UTF_8));
            } else if ("/files".equals(path)) {
                String dir = decode(uri.getQueryParameter("dir"));
                respond(out, 200, "application/json; charset=utf-8", listFiles(dir).toString().getBytes(StandardCharsets.UTF_8));
            } else if ("/dl".equals(path)) {
                serveDownload(out, decode(uri.getQueryParameter("path")));
            } else if ("/ul".equals(path)) {
                receiveUpload(decode(uri.getQueryParameter("name")), body);
                respond(out, 200, "text/plain", "ok".getBytes());
            } else if ("/screen".equals(path)) {
                byte[] shot = captureScreen();
                if (shot == null) respond(out, 503, "text/plain", "no projection".getBytes());
                else respond(out, 200, "image/jpeg", shot);
            } else if ("/pair".equals(path)) {
                // 对方请求连接：记录申请并通知 UI 弹窗
                String rid = nullToEmpty(uri.getQueryParameter("id"));
                String rip = nullToEmpty(uri.getQueryParameter("ip"));
                if (!rid.isEmpty() && isPrivate(rip)) {
                    PairRequest req = new PairRequest(rid, nullToEmpty(uri.getQueryParameter("n")),
                            nullToEmpty(uri.getQueryParameter("t")), rip,
                            parseInt(uri.getQueryParameter("p"), HTTP_PORT));
                    pairRequests.put(rid, req);
                    pairAnswers.remove(rid);
                    try { callback.onPairRequest(req); } catch (Throwable ignored) { }
                    respond(out, 200, "text/plain", "ok".getBytes());
                } else {
                    respond(out, 400, "text/plain", "bad request".getBytes());
                }
            } else if ("/pair-result".equals(path)) {
                // 发起方轮询：我是否已同意/拒绝它的申请
                String rid = nullToEmpty(uri.getQueryParameter("id"));
                Boolean ans = pairAnswers.get(rid);
                String result = ans == null ? "wait" : (ans ? "ok" : "no");
                respond(out, 200, "text/plain", result.getBytes(StandardCharsets.UTF_8));
                if (!"wait".equals(result)) { pairAnswers.remove(rid); pairRequests.remove(rid); }
            } else if ("/input".equals(path)) {
                boolean ok = applyInput(body);
                respond(out, ok ? 200 : 503, "text/plain", (ok ? "ok" : "no accessibility").getBytes());
            } else {
                respond(out, 404, "text/plain", "not found".getBytes());
            }
        } catch (Throwable t) {
            Log.w(TAG, "handle: " + t.getMessage());
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private JSONArray listFiles(String dir) {
        JSONArray arr = new JSONArray();
        try {
            File base = canonical(shareDir);
            File target = (dir == null || dir.isEmpty()) ? base : canonical(new File(shareDir, dir));
            if (!isUnder(target, base)) return arr;
            File[] files = target.listFiles();
            if (files == null) return arr;
            List<File> dirs = new ArrayList<>(), regular = new ArrayList<>();
            for (File f : files) (f.isDirectory() ? dirs : regular).add(f);
            dirs.sort(Comparator.comparing(File::getName));
            regular.sort(Comparator.comparing(File::getName));
            for (File f : dirs) {
                JSONObject o = new JSONObject();
                o.put("name", f.getName()); o.put("path", encode(rel(f))); o.put("dir", true); o.put("size", 0);
                arr.put(o);
            }
            for (File f : regular) {
                JSONObject o = new JSONObject();
                o.put("name", f.getName()); o.put("path", encode(rel(f))); o.put("dir", false); o.put("size", f.length());
                arr.put(o);
            }
        } catch (Throwable ignored) { }
        return arr;
    }

    private void serveDownload(OutputStream out, String rel) throws java.io.IOException {
        // 按块流式写出，不读完整个文件再申请内存，几百 MB 的大文件传输安全。
        File f = serveFile(rel);
        long len = f.length();
        try (InputStream in = java.nio.file.Files.newInputStream(f.toPath())) {
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                    + len + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable ignored) { }
    }

    /** 顺手校验路径，返回共享目录内的文件；非法路径抛异常让调用方回 404。 */
    private File serveFile(String rel) throws java.io.IOException {
        if (rel == null) throw new IllegalArgumentException("missing");
        File f = canonical(new File(shareDir, rel));
        if (!isUnder(f, canonical(shareDir)) || !f.exists() || !f.isFile()) {
            throw new IllegalArgumentException("missing");
        }
        return f;
    }

    private void receiveUpload(String name, byte[] body) {
        try {
            if (name == null || name.isEmpty()) return;
            File f = new File(shareDir, new File(name).getName());
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(body); }
            main.post(() -> status("已接收：" + f.getName()));
        } catch (Throwable ignored) { }
    }

    // ---------------- 被控：截屏与输入 ----------------

    /** 由 Activity 在拿到 MediaProjection 授权回调后调用：转交 mediaProjection 前台服务。 */
    public void startProjection(int resultCode, Intent data) {
        try {
            Intent i = new Intent(ctx, LanScreenService.class);
            i.putExtra(LanScreenService.EXTRA_CODE, resultCode);
            i.putExtra(LanScreenService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
            status("屏幕共享已开启，电脑可查看并控制本手机");
        } catch (Throwable t) { status("屏幕共享开启失败：" + t.getMessage()); }
    }

    public void stopProjectionService() {
        try { ctx.stopService(new Intent(ctx, LanScreenService.class)); } catch (Throwable ignored) { }
    }

    public boolean isProjecting() { return LanScreenService.isProjecting(); }

    private byte[] captureScreen() {
        return LanScreenService.isProjecting() ? LanScreenService.getLatestFrame() : null;
    }

    private boolean applyInput(byte[] body) {
        LanAccessibilityService acc = LanAccessibilityService.getInstance();
        if (acc == null) return false;
        try {
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String a = o.optString("a");
            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            if ("tap".equals(a)) {
                float x = (float) (o.getDouble("x") * dm.widthPixels);
                float y = (float) (o.getDouble("y") * dm.heightPixels);
                return acc.tap(x, y);
            } else if ("swipe".equals(a)) {
                float x1 = (float) (o.getDouble("x1") * dm.widthPixels);
                float y1 = (float) (o.getDouble("y1") * dm.heightPixels);
                float x2 = (float) (o.getDouble("x2") * dm.widthPixels);
                float y2 = (float) (o.getDouble("y2") * dm.heightPixels);
                return acc.swipe(x1, y1, x2, y2);
            } else if ("key".equals(a)) {
                return acc.key(o.optString("k"));
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ---------------- 客户端（主控 / 传文件） ----------------

    public String hello(Device d) { return httpGet(d, "/hello?k=" + TOKEN); }

    // ---------------- 连接握手 + 手动直连 ----------------

    /** 按 IP 手动添加设备（发现不可用时兜底），会校验 hello。 */
    public Device addManualDevice(String ipText) {
        String ip = nullToEmpty(ipText).trim();
        if (!isPrivate(ip)) return null;
        Device probe = new Device("manual-" + ip, ip, "pc", ip, HTTP_PORT);
        String h = hello(probe);
        if (h == null) return null;
        String[] parts = h.split("\\|");
        String kind = parts.length > 1 ? parts[1] : "pc";
        String name = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : ip;
        Device d = new Device("manual-" + ip, name, kind, ip, HTTP_PORT);
        devices.put(d.id, d);
        changed();
        return d;
    }

    /** 向对方发起连接申请，携带本机信息；对方 UI 会弹窗确认。 */
    public boolean requestPair(Device d) {
        try {
            String myIp = nullToEmpty(localIp).isEmpty() ? findPrivateIp() : localIp;
            String url = base(d) + "/pair?k=" + TOKEN + "&id=" + urlEnc(deviceId)
                    + "&n=" + urlEnc(deviceName) + "&t=android&p=" + HTTP_PORT + "&ip=" + urlEnc(myIp);
            return "ok".equals(nullToEmpty(httpGetRaw(url)).trim());
        } catch (Throwable t) { return false; }
    }

    /** 轮询对方是否已同意我的申请：wait / ok / no */
    public String pollPair(Device d) {
        try {
            String r = httpGet(d, "/pair-result?k=" + TOKEN + "&id=" + urlEnc(deviceId));
            return nullToEmpty(r).trim();
        } catch (Throwable t) { return "wait"; }
    }

    /** 本地审批对方发来的申请；同意则把对方登记为设备。 */
    public void decidePair(String reqId, boolean accept) {
        PairRequest req = pairRequests.get(reqId);
        if (req == null) return;
        // 决定后不再保留申请，避免 map 长期挂死；限速/暴力把设备注册需要用户再点同意，但对方的 poll 应当回 no。
        pairRequests.remove(reqId);
        pairAnswers.put(reqId, accept);
        if (accept && !req.name.isEmpty()) {
            Device d = new Device(req.id, req.name, req.kind, req.ip, req.port);
            devices.put(req.id, d);
            changed();
        }
    }

    public List<FileEntry> listRemote(Device d, String dirB64) {
        List<FileEntry> list = new ArrayList<>();
        try {
            String url = base(d) + "/files?k=" + TOKEN + "&dir=" + urlEnc(nullToEmpty(dirB64));
            String json = httpGetRaw(url);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new FileEntry(o.getString("name"), o.getString("path"),
                        o.getBoolean("dir"), o.optLong("size")));
            }
        } catch (Throwable ignored) { }
        return list;
    }

    public boolean download(Device d, FileEntry e, File local, ProgressListener progress) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(base(d) + "/dl?k=" + TOKEN
                    + "&path=" + urlEnc(e.path)).openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            c.connect();
            if (c.getResponseCode() != 200) return false;
            long total = c.getContentLengthLong();
            try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(local)) {
                byte[] buf = new byte[16384];
                long read = 0; int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n); read += n;
                    if (progress != null && total > 0) progress.onProgress((double) read / total);
                }
            }
            return true;
        } catch (Throwable ignored) { return false; }
        finally { if (c != null) c.disconnect(); }
    }

    public boolean upload(Device d, File local) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(base(d) + "/ul?k=" + TOKEN
                    + "&name=" + urlEnc(encode(local.getName()))).openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(15000); c.setReadTimeout(30000);
            byte[] bytes = readAll(local);
            c.setFixedLengthStreamingMode(bytes.length);
            c.connect();
            try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            return c.getResponseCode() == 200;
        } catch (Throwable ignored) { return false; }
        finally { if (c != null) c.disconnect(); }
    }

    public byte[] screen(Device d) {
        try { return httpGetBytes(base(d) + "/screen?k=" + TOKEN); } catch (Throwable t) { return null; }
    }

    public boolean input(Device d, JSONObject payload) {
        return httpPost(d, "/input?k=" + TOKEN, payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    public interface ProgressListener { void onProgress(double p); }

    private String base(Device d) { return "http://" + d.ip + ":" + d.port; }

    private String httpGet(Device d, String path) { return httpGetRaw(base(d) + path); }

    private String httpGetRaw(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) { return ""; }
        finally { if (c != null) c.disconnect(); }
    }

    private byte[] httpGetBytes(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            return readAll(c.getInputStream());
        } catch (Throwable ignored) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    private boolean httpPost(Device d, String path, byte[] body) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(base(d) + path).openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setFixedLengthStreamingMode(body.length);
            c.connect();
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            return c.getResponseCode() == 200;
        } catch (Throwable ignored) { return false; }
        finally { if (c != null) c.disconnect(); }
    }

    // ---------------- 工具 ----------------

    private void status(String s) { if (callback != null) main.post(() -> callback.onStatus(s)); }
    private void changed() { if (callback != null) main.post(callback::onDevicesChanged); }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (Throwable ignored) { } }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static byte[] readAll(File f) throws java.io.IOException {
        try (InputStream in = new java.io.FileInputStream(f)) { return readAll(in); }
    }

    private static void respond(OutputStream out, int code, String type, byte[] body) throws java.io.IOException {
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 503 ? "Service Unavailable" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body); out.flush();
    }

    private static boolean isPrivate(String ip) {
        String[] b = ip.split("\\.");
        if (b.length != 4) return false;
        try {
            int a = Integer.parseInt(b[0]), c = Integer.parseInt(b[1]);
            if (a == 192 && c == 168) return true;
            if (a == 10) return true;
            if (a == 172 && c >= 16 && c <= 31) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private void collectLocalIps() {
        localIps.clear();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.util.Enumeration<InetAddress> addrs = nis.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && isPrivate(a.getHostAddress()))
                        localIps.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) { }
        if (!"0.0.0.0".equals(localIp)) localIps.add(localIp);
    }

    private static String findPrivateIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                java.util.Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && isPrivate(a.getHostAddress()))
                        return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) { }
        return "0.0.0.0";
    }

    private static File canonical(File f) throws java.io.IOException { return f.getCanonicalFile(); }
    private static boolean isUnder(File f, File root) {
        String fp = f.getAbsolutePath(), rp = root.getAbsolutePath();
        return fp.equals(rp) || fp.startsWith(rp + File.separator);
    }
    private String rel(File f) {
        String base = canonicalQuiet(shareDir).getAbsolutePath();
        String full = f.getAbsolutePath();
        return full.startsWith(base + File.separator) ? full.substring(base.length() + 1) : f.getName();
    }
    private static File canonicalQuiet(File f) { try { return f.getCanonicalFile(); } catch (Throwable t) { return f; } }

    private static String encode(String s) { return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP); }
    private static String decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8); } catch (Throwable t) { return null; }
    }
    private static String urlEnc(String s) throws java.io.IOException { return URLEncoder.encode(s, "UTF-8"); }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return def; }
    }
}
