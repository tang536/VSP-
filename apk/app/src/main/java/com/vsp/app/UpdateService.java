package com.vsp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 新版本下载服务。
 *
 * DownloadManager 的通知由系统绘制，只能显示百分比，既不能显示下载速度，
 * 也无法把「已下载 / 总大小」写进通知栏。这里自己做流式下载 + 前台服务通知，
 * 每 400ms 刷新一次：进度条 + 「已下载 / 总大小」+「速度」，并可点通知里的
 * 「取消下载」立刻中断。
 *
 * 下载状态会写进 {@link UpdateStore}，.part 文件也保留：
 * - 切网 / 息屏 / 退到后台时连接断了，网络恢复后自动带 Range 续传；
 * - 进程被回收后 START_STICKY 重新拉起服务，继续下载；
 * - 关闭更新弹窗后再点更新，接回原来的下载而不是从 0 开始。
 */
public class UpdateService extends Service {

    public static final String ACTION_START = "com.vsp.app.UPDATE_START";
    public static final String ACTION_RESUME = "com.vsp.app.UPDATE_RESUME";
    public static final String ACTION_CANCEL = "com.vsp.app.UPDATE_CANCEL";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_VERSION = "version";
    public static final String EXTRA_SIZE = "size";

    /** 跳转最多跟这么多层：Gitee 的 releases/download 要跳两次才到 CDN */
    private static final int MAX_HOPS = 8;

    /** 下载用的 UA：Gitee 的 CDN / GitHub 的 release-assets 对自造 UA 不太友好 */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Mobile Safari/537.36 VSPParse";

    private static final String CHANNEL_ID = "vsp_update_channel";
    private static final int NOTI_ID = 0x5A11;

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT = 25000;
    /** 网络正常但服务器一直失败时最多重试这么多次；断网等待不计数 */
    private static final int MAX_RETRIES = 20;

    // 供界面读取的实时状态（进程内单例，弹窗与通知共用同一份）
    public static volatile boolean running = false;
    public static volatile String state = UpdateStore.STATE_IDLE; // idle / downloading / done / failed
    public static volatile int percent = 0;
    public static volatile long downloaded = 0L;
    public static volatile long totalSize = 0L;
    public static volatile String speedText = "";
    public static volatile String savedPath = "";
    public static volatile String version = "";
    public static volatile String errorText = "";

    private static final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    /** 每次真正开始新一轮下载 / 取消时递增；旧线程看到序号变了就安静退出，避免抢写同一个 .part */
    private static final AtomicLong downloadGeneration = new AtomicLong(0L);
    private final Object downloadLock = new Object();
    private volatile Thread activeDownloadThread;
    private volatile long activeGeneration = -1L;
    private volatile HttpURLConnection conn;
    /** 检查更新时报文里给出的安装包大小，CDN 不给 Content-Length 时用它兜底 */
    private static volatile long expectedSize = 0L;

    private volatile boolean destroyed = false;
    private volatile long lastDataAt = 0L;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Object networkLock = new Object();

    // ------------------------------------------------------------ 静态入口

    public static void start(Context ctx, String url, String ver, long size) {
        if (ctx == null) return;
        cancelFlag.set(false);

        String oldUrl = UpdateStore.url(ctx);
        String oldVer = UpdateStore.version(ctx);
        String oldState = UpdateStore.state(ctx);
        boolean same = !UpdateStore.STATE_IDLE.equals(oldState)
                && url != null && url.equals(oldUrl)
                && ver != null && ver.equals(oldVer);
        // 同一份下载正在跑时重复点「下载并安装」不再开新线程；其余情况都开启新序号
        if (!(same && running)) downloadGeneration.incrementAndGet();
        if (!same) {
            UpdateStore.begin(ctx, url, ver, size);
            resetRuntimeState();
        } else {
            restoreFromStore(ctx);
        }

        expectedSize = size > 0 ? size : UpdateStore.size(ctx);
        running = true;
        state = UpdateStore.STATE_DOWNLOADING;
        errorText = "";
        speedText = "";
        version = ver == null ? "" : ver;
        if (totalSize <= 0) totalSize = expectedSize;
        downloaded = UpdateStore.downloaded(ctx);
        percent = totalSize > 0 ? (int) Math.min(100L, downloaded * 100L / totalSize) : 0;

        Intent i = new Intent(ctx, UpdateService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_VERSION, ver);
        i.putExtra(EXTRA_SIZE, size);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            Log.w("VSP", "启动更新服务失败", t);
            running = false;
            state = UpdateStore.STATE_FAILED;
            errorText = "无法启动下载服务";
            UpdateStore.failed(ctx, errorText);
        }
    }

    /** 断网 / 进程重启 / 关闭弹窗后继续上一次的下载。没有可续传的 .part 时返回 false。 */
    public static boolean resume(Context ctx) {
        if (ctx == null || !UpdateStore.hasResumable(ctx)) return false;
        if (running) return true;
        downloadGeneration.incrementAndGet();
        cancelFlag.set(false);
        restoreFromStore(ctx);
        running = true;
        state = UpdateStore.STATE_DOWNLOADING;
        errorText = "";
        speedText = "";
        expectedSize = totalSize;

        Intent i = new Intent(ctx, UpdateService.class);
        i.setAction(ACTION_RESUME);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            return true;
        } catch (Throwable t) {
            Log.w("VSP", "恢复更新服务失败", t);
            running = false;
            state = UpdateStore.STATE_FAILED;
            errorText = "无法恢复下载服务";
            UpdateStore.failed(ctx, errorText);
            return false;
        }
    }

    public static void cancel(Context ctx) {
        cancelFlag.set(true);
        downloadGeneration.incrementAndGet();
        try {
            if (ctx != null) {
                Intent i = new Intent(ctx, UpdateService.class);
                i.setAction(ACTION_CANCEL);
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 进程被杀后由界面/服务重启时，把持久化状态搬回内存 */
    static void restoreFromStore(Context ctx) {
        state = UpdateStore.state(ctx);
        version = UpdateStore.version(ctx);
        downloaded = UpdateStore.downloaded(ctx);
        totalSize = UpdateStore.size(ctx);
        savedPath = UpdateStore.path(ctx);
        errorText = UpdateStore.error(ctx);
        expectedSize = totalSize;
        speedText = "";
        running = UpdateStore.STATE_DOWNLOADING.equals(state);
        percent = totalSize > 0
                ? (int) Math.min(100L, Math.max(0L, downloaded) * 100L / totalSize) : 0;
    }

    private static void resetRuntimeState() {
        running = false;
        state = UpdateStore.STATE_IDLE;
        percent = 0;
        downloaded = 0L;
        totalSize = 0L;
        speedText = "";
        savedPath = "";
        errorText = "";
        expectedSize = 0L;
        version = "";
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        registerNetworkMonitor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());

        if (ACTION_CANCEL.equals(action)) {
            cancelFlag.set(true);
            downloadGeneration.incrementAndGet();
            closeQuietly();
            clearDownload();
            cancelNotification();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        String ver = intent == null ? "" : String.valueOf(intent.getStringExtra(EXTRA_VERSION));
        long want = intent == null ? 0L : intent.getLongExtra(EXTRA_SIZE, 0L);

        if (url != null && !url.isEmpty()) {
            // 显式 start：只在地址/版本变了时重置状态，重复点「下载」不会清空 .part
            String oldUrl = UpdateStore.url(this);
            String oldVer = UpdateStore.version(this);
            String oldState = UpdateStore.state(this);
            boolean same = !UpdateStore.STATE_IDLE.equals(oldState)
                    && url.equals(oldUrl) && ver.equals(oldVer);
            if (!same) {
                UpdateStore.begin(this, url, ver, want);
                resetRuntimeState();
            } else {
                restoreFromStore(this);
            }
            version = ver;
            if (want > 0) expectedSize = want;
            if (totalSize <= 0) totalSize = expectedSize;
            state = UpdateStore.STATE_DOWNLOADING;
            running = true;
            errorText = "";
        } else {
            // START_STICKY / ACTION_RESUME：没有 extras，按持久化状态续传
            cancelFlag.set(false);
            restoreFromStore(this);
            if (!UpdateStore.hasResumable(this)) {
                // 用 startForegroundService 启动后必须调过 startForeground 才能停
                startForegroundCompat(buildNotification(percent,
                        sizeLine(downloaded, totalSize), "下载已结束", null, false));
                cancelNotification();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            state = UpdateStore.STATE_DOWNLOADING;
            running = true;
            errorText = "";
            url = UpdateStore.url(this);
            ver = UpdateStore.version(this);
            expectedSize = totalSize;
        }

        startForegroundCompat(buildNotification(percent,
                sizeLine(downloaded, totalSize),
                "正在连接…", null, false));

        long gen = downloadGeneration.get();
        Thread alive = activeDownloadThread;
        if (alive != null && alive.isAlive() && activeGeneration == gen) {
            // 同一轮下载已经在跑，只刷新通知
            notifyProgress(buildNotification(percent,
                    sizeLine(downloaded, totalSize), "正在下载…", null, false));
        } else {
            destroyed = false;
            final String dlUrl = url;
            final String dlVer = ver;
            final long myGen = gen;
            Thread t = new Thread(() -> {
                try {
                    synchronized (downloadLock) {
                        if (destroyed || myGen != downloadGeneration.get()) return;
                        download(dlUrl, dlVer, myGen);
                    }
                } finally {
                    if (activeGeneration == myGen) activeDownloadThread = null;
                }
            }, "vsp-update-dl");
            activeGeneration = myGen;
            activeDownloadThread = t;
            t.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        running = false;
        closeQuietly();
        unregisterNetworkMonitor();
        releaseWakeLock();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 下载

    private void download(String url, String ver, long gen) {
        File out = targetFile(ver);
        File part = new File(out.getAbsolutePath() + ".part");
        acquireWakeLock();
        lastDataAt = System.currentTimeMillis();
        try {
            // 上一次其实已经下完、只差改名：直接完成，不再联网
            if (out.isFile() && out.length() > 200L * 1024L
                    && (expectedSize <= 0 || Math.abs(out.length() - expectedSize) <= tolerance(expectedSize))) {
                complete(out, null, gen);
                return;
            }

            long have = part.isFile() ? part.length() : 0L;
            if (totalSize > 0 && have > totalSize) {
                // .part 比目标还大，肯定是坏的
                deleteQuietly(part);
                have = 0L;
            }
            downloaded = have;
            if (totalSize <= 0) totalSize = expectedSize;
            if (totalSize > 0) percent = (int) Math.min(100L, have * 100L / totalSize);

            int failures = 0;
            long lastFailureSize = have;
            long lastProgressSave = 0L;

            while (!cancelFlag.get() && !destroyed && gen == downloadGeneration.get()) {
                try {
                    boolean ok = downloadOnce(url, out, part, gen);
                    if (ok) return;
                } catch (InterruptedException ie) {
                    throw ie;
                } catch (FatalDownloadException fe) {
                    closeQuietly();
                    if (gen == downloadGeneration.get() && !destroyed) fail(fe.getMessage());
                    return;
                } catch (Exception e) {
                    closeQuietly();
                    if (gen != downloadGeneration.get()) return;
                    if (cancelFlag.get()) throw new InterruptedException("cancel");
                    if (destroyed) return;

                    long nowLength = part.isFile() ? part.length() : 0L;
                    downloaded = nowLength;
                    if (totalSize > 0) {
                        percent = (int) Math.min(100L, nowLength * 100L / totalSize);
                    }
                    UpdateStore.progress(this, downloaded, totalSize);

                    // 断网：不急着失败，前台服务一直等到网络恢复
                    if (!isNetworkAvailable()) {
                        failures = 0;
                        speedText = "等待网络恢复…";
                        notifyProgress(buildNotification(percent,
                                sizeLine(downloaded, totalSize), speedText, null, false));
                        waitForNetwork();
                        continue;
                    }

                    // 这一轮其实又往前下了一截，说明网络只是抖，重新计数
                    if (nowLength > lastFailureSize + 256L * 1024L) failures = 0;
                    lastFailureSize = nowLength;
                    failures++;

                    String msg = shortMsg(e);
                    if (failures >= MAX_RETRIES) {
                        fail(msg);
                        return;
                    }

                    long delay = Math.min(30000L, 1000L << Math.min(failures - 1, 5));
                    speedText = "网络不稳定，正在重试…";
                    notifyProgress(buildNotification(percent,
                            sizeLine(downloaded, totalSize), speedText, null, false));
                    if (!sleepInterruptible(delay)) throw new InterruptedException("cancel");

                    long now = System.currentTimeMillis();
                    if (now - lastProgressSave > 1000L) {
                        UpdateStore.progress(this, downloaded, totalSize);
                        lastProgressSave = now;
                    }
                }
            }
            if (gen != downloadGeneration.get()) return;
            if (cancelFlag.get()) throw new InterruptedException("cancel");
        } catch (InterruptedException ie) {
            closeQuietly();
            if (gen == downloadGeneration.get() && cancelFlag.get()) {
                // 用户主动取消：删除 .part，状态归零
                cancelDownloadState(part);
            }
            // destroyed（系统回收）时保留 .part 与 downloading 状态，等 START_STICKY 续传
        } catch (Exception e) {
            closeQuietly();
            if (gen == downloadGeneration.get() && !destroyed) fail(shortMsg(e));
        } finally {
            closeQuietly();
            releaseWakeLock();
        }
    }

    /**
     * 尝试一轮下载。返回 true 表示已经完成并改名成功；
     * 抛出的普通异常代表可续传的网络错误，FatalDownloadException 表示重试也没用。
     */
    private boolean downloadOnce(String url, File out, File part, long gen) throws Exception {
        long have = part.isFile() ? part.length() : 0L;
        downloaded = have;
        if (totalSize > 0) percent = (int) Math.min(100L, have * 100L / totalSize);
        if (totalSize > 0 && have >= totalSize) {
            complete(out, part, gen);
            return true;
        }

        // Gitee：releases/download → attach_files/…/download → foruda.gitee.com 的 CDN，
        // 两跳才拿得到 200；GitHub：releases/download → release-assets.githubusercontent.com，
        // 也要一跳。
        String cur = url;
        int code;
        conn = open(cur, have);
        code = conn.getResponseCode();
        for (int hops = 0; conn != null; hops++) {
            if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) break;
            if (hops >= MAX_HOPS) throw new FatalDownloadException("下载地址跳转次数过多");
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc == null || loc.trim().isEmpty()) {
                throw new FatalDownloadException("下载地址跳转失败（缺少跳转地址）");
            }
            loc = loc.trim();
            if (!loc.startsWith("http://") && !loc.startsWith("https://")) {
                loc = new URL(new URL(cur), loc).toString();
            }
            cur = loc;
            conn = open(cur, have);
            code = conn.getResponseCode();
        }

        if (code == 416) {
            // Range 超出：服务器认为已经下完了，或者 .part 是坏的
            long total = parseContentRangeTotal(conn.getHeaderField("Content-Range"));
            conn.disconnect();
            if (total > 0) totalSize = total;
            if (totalSize > 0 && have >= totalSize) {
                complete(out, part, gen);
                return true;
            }
            deleteQuietly(part);
            downloaded = 0L;
            percent = 0;
            throw new Exception("服务器要求重新下载");
        }

        if (code != 200 && code != 206) {
            conn.disconnect();
            if (code == 408 || code == 429) throw new Exception("服务器返回 " + code);
            if (code >= 400 && code < 500) {
                throw new FatalDownloadException("服务器返回 " + code);
            }
            throw new Exception("服务器返回 " + code);
        }

        // 跳到 CDN 后拿到的可能是「链接已过期」的网页，按 HTML 直接判失败，
        // 免得把一个几百 KB 的错误页当安装包存下来
        String ctype = conn.getContentType();
        if (ctype != null && ctype.toLowerCase(Locale.ROOT).contains("text/html")) {
            conn.disconnect();
            throw new FatalDownloadException("下载地址已失效，请再点一次检查更新");
        }

        long start = -1L;
        long total = -1L;
        if (code == 206) {
            String cr = conn.getHeaderField("Content-Range");
            start = parseContentRangeStart(cr);
            total = parseContentRangeTotal(cr);
            if (start >= 0 && start != have) {
                // 服务器给的起点和本地 .part 对不上，只能从头来
                conn.disconnect();
                deleteQuietly(part);
                downloaded = 0L;
                percent = 0;
                throw new Exception("断点位置不一致，重新下载");
            }
        } else if (have > 0) {
            // 服务器忽略了 Range，只能从头下载
            deleteQuietly(part);
            have = 0L;
            downloaded = 0L;
            percent = 0;
        }

        long len = conn.getContentLengthLong();
        if (total <= 0) {
            if (code == 206) {
                // Content-Length 是剩余字节数，加上本地已有部分才是总大小
                if (len > 0) total = have + len;
            } else if (len > 0) {
                total = len;
            }
        }
        // CDN 偶尔用 chunked 不给长度，退回检查更新时拿到的大小，进度条才有百分比
        if (total <= 0 && expectedSize > 0) total = expectedSize;
        if (total > 0) totalSize = total;
        if (totalSize > 0) percent = (int) Math.min(100L, have * 100L / totalSize);

        InputStream in = conn.getInputStream();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(part, have > 0);
            byte[] buf = new byte[128 * 1024];
            long done = have;
            long lastNotify = 0L, lastBytes = done, lastTime = System.currentTimeMillis();
            long lastPersist = 0L;
            while (true) {
                if (cancelFlag.get() || destroyed || gen != downloadGeneration.get()) {
                    throw new InterruptedException("cancel");
                }
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                fos.write(buf, 0, n);
                done += n;
                downloaded = done;
                lastDataAt = System.currentTimeMillis();
                if (totalSize > 0) percent = (int) Math.min(100L, done * 100L / totalSize);
                long now = System.currentTimeMillis();
                if (now - lastNotify >= 400) {
                    long dt = Math.max(1L, now - lastTime);
                    long db = done - lastBytes;
                    long bps = db * 1000L / dt;
                    speedText = human(bps) + "/s";
                    lastNotify = now;
                    lastBytes = done;
                    lastTime = now;
                    notifyProgress(buildNotification(percent,
                            sizeLine(done, totalSize), speedText, null, false));
                    if (now - lastPersist >= 1000L) {
                        UpdateStore.progress(this, done, totalSize);
                        lastPersist = now;
                    }
                }
            }
        } finally {
            try {
                if (fos != null) fos.flush();
            } catch (Throwable ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            closeQuietly();
        }

        long finalLen = part.length();
        downloaded = finalLen;
        if (gen != downloadGeneration.get() || destroyed) {
            throw new InterruptedException("cancel");
        }
        if (totalSize > 0 && finalLen < totalSize - tolerance(totalSize)) {
            // 连接提前结束，.part 留着，下一轮 Range 接着下
            UpdateStore.progress(this, finalLen, totalSize);
            throw new IOException("连接提前结束（" + human(finalLen) + " / " + human(totalSize) + "）");
        }
        complete(out, part, gen);
        return true;
    }

    private void complete(File out, File part, long gen) throws Exception {
        if (gen != downloadGeneration.get() || destroyed) {
            throw new InterruptedException("cancel");
        }
        long len;
        if (part != null && part.isFile()) {
            len = part.length();
            if (len < 200L * 1024L) throw new Exception("下载内容不完整");
            if (expectedSize > 0
                    && Math.abs(len - expectedSize) > tolerance(expectedSize)) {
                deleteQuietly(part);
                throw new Exception("安装包大小不符（" + human(len) + " / "
                        + human(expectedSize) + "），请重试");
            }
            if (out.exists()) deleteQuietly(out);
            if (!part.renameTo(out)) throw new Exception("保存安装包失败");
        } else {
            len = out.length();
        }

        savedPath = out.getAbsolutePath();
        downloaded = len;
        totalSize = len;
        percent = 100;
        speedText = "";
        errorText = "";
        state = UpdateStore.STATE_DONE;
        running = false;
        UpdateStore.done(this, savedPath, len);
        notifyProgress(buildNotification(100, human(len) + " / " + human(len),
                "下载完成，点击安装", out, true));
        stopForeground(false);
        stopSelf();
    }

    private void fail(String msg) {
        state = UpdateStore.STATE_FAILED;
        running = false;
        errorText = msg == null || msg.isEmpty() ? "下载失败，请稍后重试" : msg;
        speedText = "";
        UpdateStore.failed(this, errorText);
        notifyProgress(buildNotification(percent,
                sizeLine(downloaded, totalSize), "下载中断，点开应用可继续", null, false));
        stopForeground(false);
        stopSelf();
    }

    private void cancelDownloadState(File part) {
        deleteQuietly(part);
        UpdateStore.clear(this);
        resetRuntimeState();
        cancelNotification();
        stopForeground(true);
        stopSelf();
    }

    private void clearDownload() {
        File out = targetFile(version);
        deleteQuietly(new File(out.getAbsolutePath() + ".part"));
        UpdateStore.clear(this);
        resetRuntimeState();
    }

    private HttpURLConnection open(String url, long resumeFrom) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        // 每跳都要 identity：跳到 CDN 那一步若被 gzip，Content-Length 与实际字节数不符
        c.setRequestProperty("Accept-Encoding", "identity");
        if (resumeFrom > 0) c.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        return c;
    }

    private File targetFile(String ver) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = getCacheDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "VSP解析-v" + ver + ".apk");
    }

    private static long tolerance(long size) {
        return Math.max(64L * 1024L, Math.max(0L, size) / 50L);
    }

    private static long parseContentRangeStart(String cr) {
        if (cr == null) return -1L;
        try {
            String s = cr.trim();
            if (!s.startsWith("bytes ")) return -1L;
            s = s.substring(6).trim();
            int dash = s.indexOf('-');
            if (dash <= 0) return -1L;
            return Long.parseLong(s.substring(0, dash).trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    private static long parseContentRangeTotal(String cr) {
        if (cr == null) return -1L;
        try {
            int slash = cr.lastIndexOf('/');
            if (slash < 0) return -1L;
            String s = cr.substring(slash + 1).trim();
            if (s.isEmpty() || "*".equals(s)) return -1L;
            return Long.parseLong(s);
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String sizeLine(long done, long total) {
        return total > 0 ? human(done) + " / " + human(total) : "已下载 " + human(done);
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }

    private void closeQuietly() {
        try {
            if (conn != null) conn.disconnect();
        } catch (Throwable ignored) {
        }
        conn = null;
    }

    private String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    public static String human(long n) {
        if (n <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB"};
        double v = n;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.US, i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }

    // ------------------------------------------------------------ 网络 / 唤醒锁

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    private void registerNetworkMonitor() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    synchronized (networkLock) {
                        networkLock.notifyAll();
                    }
                    // 切网后旧 socket 可能一直不返回数据，主动断开让循环带 Range 重连
                    if (System.currentTimeMillis() - lastDataAt > 6000L) closeQuietly();
                }

                @Override
                public void onLost(Network network) {
                    synchronized (networkLock) {
                        networkLock.notifyAll();
                    }
                    closeQuietly();
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                  NetworkCapabilities capabilities) {
                    synchronized (networkLock) {
                        networkLock.notifyAll();
                    }
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable t) {
            networkCallback = null;
        }
    }

    private void unregisterNetworkMonitor() {
        try {
            if (networkCallback != null) {
                ConnectivityManager cm =
                        (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {
        }
        networkCallback = null;
    }

    /** 断网时一直等，网络恢复自动继续；取消下载会立刻返回 */
    private void waitForNetwork() {
        synchronized (networkLock) {
            while (!cancelFlag.get() && !destroyed && !isNetworkAvailable()) {
                try {
                    networkLock.wait(2000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cancelFlag.get() || destroyed) return false;
            try {
                Thread.sleep(Math.min(500L, end - System.currentTimeMillis()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !cancelFlag.get() && !destroyed;
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VSP:update-download");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(2L * 60L * 60L * 1000L);
        } catch (Throwable ignored) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    // ------------------------------------------------------------ 通知

    private void startForegroundCompat(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTI_ID, n);
            }
        } catch (Throwable t) {
            // 个别 ROM 不认 dataSync 类型，退化成普通前台服务
            try {
                startForeground(NOTI_ID, n);
            } catch (Throwable ignored) {
            }
        }
    }

    private Notification buildNotification(int pct, String sizeLine, String subLine,
                                           File apk, boolean done) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "版本更新",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 10, open, flags);

        boolean failed = UpdateStore.STATE_FAILED.equals(state);
        String title;
        if (done) title = "VSP 解析 v" + version + " 下载完成";
        else if (failed) title = "VSP 解析 v" + version + " 下载中断";
        else title = "正在下载 VSP 解析 v" + version;

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(title)
                .setContentText(sizeLine)
                .setSubText(subLine)
                .setOnlyAlertOnce(true)
                .setOngoing(!done && !failed)
                .setAutoCancel(done)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, Math.max(0, Math.min(100, pct)), !done && !failed);

        if (done && apk != null && apk.exists()) {
            b.setContentIntent(installIntent(apk));
            b.setContentText("下载完成 · " + sizeLine + "，点击安装");
        } else {
            b.setContentIntent(content);
            if (failed) {
                Intent ri = new Intent(this, UpdateService.class);
                ri.setAction(ACTION_RESUME);
                PendingIntent resumePi = PendingIntent.getService(this, 13, ri, flags);
                b.addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_update, "继续下载", resumePi).build());
            }
            Intent ci = new Intent(this, UpdateService.class);
            ci.setAction(ACTION_CANCEL);
            PendingIntent cancelPi = PendingIntent.getService(this, 11, ci, flags);
            b.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_close, "取消下载", cancelPi).build());
        }
        return b.build();
    }

    private PendingIntent installIntent(File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 12, i, flags);
    }

    private void notifyProgress(Notification n) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, n);
        } catch (Throwable ignored) {
        }
    }

    private void cancelNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTI_ID);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 重试也没用的错误（地址失效 / 4xx / 跳转异常），直接停在失败状态 */
    private static class FatalDownloadException extends Exception {
        FatalDownloadException(String msg) {
            super(msg);
        }
    }
}
