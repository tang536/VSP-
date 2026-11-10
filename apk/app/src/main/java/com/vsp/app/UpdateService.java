package com.vsp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 新版本下载服务。
 *
 * DownloadManager 的通知由系统绘制，只能显示百分比，既不能显示下载速度，
 * 也无法把「已下载 / 总大小」写进通知栏。这里自己做流式下载 + 前台服务通知，
 * 每 400ms 刷新一次：进度条 + 「已下载 / 总大小」+「速度」，并可点通知里的
 * 「取消下载」立刻中断。
 */
public class UpdateService extends Service {

    public static final String ACTION_START = "com.vsp.app.UPDATE_START";
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

    // 供界面读取的实时状态（进程内单例，弹窗与通知共用同一份）
    public static volatile boolean running = false;
    public static volatile String state = "idle";   // idle / downloading / done / failed
    public static volatile int percent = 0;
    public static volatile long downloaded = 0L;
    public static volatile long totalSize = 0L;
    public static volatile String speedText = "";
    public static volatile String savedPath = "";
    public static volatile String version = "";
    public static volatile String errorText = "";

    private static final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private volatile HttpURLConnection conn;
    /** 检查更新时报文里给出的安装包大小，CDN 不给 Content-Length 时用它兜底 */
    private static volatile long expectedSize = 0L;

    public static void start(Context ctx, String url, String ver, long size) {
        cancelFlag.set(false);
        state = "downloading";
        running = true;
        percent = 0;
        downloaded = 0L;
        totalSize = size > 0 ? size : 0L;
        speedText = "";
        savedPath = "";
        errorText = "";
        expectedSize = size > 0 ? size : 0L;
        version = ver;
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
            state = "failed";
            errorText = "无法启动下载服务";
        }
    }

    public static void cancel(Context ctx) {
        cancelFlag.set(true);
        try {
            if (ctx != null) {
                Intent i = new Intent(ctx, UpdateService.class);
                i.setAction(ACTION_CANCEL);
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (ACTION_CANCEL.equals(action)) {
            cancelFlag.set(true);
            closeQuietly();
            return START_NOT_STICKY;
        }
        String url = intent == null ? null : intent.getStringExtra(EXTRA_URL);
        String ver = intent == null ? "" : String.valueOf(intent.getStringExtra(EXTRA_VERSION));
        long want = intent == null ? 0L : intent.getLongExtra(EXTRA_SIZE, 0L);
        if (want > 0) expectedSize = want;
        if (url == null || url.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        version = ver;
        startForegroundCompat(buildNotification(0, "0 B / 0 B", "正在连接…", null, false));
        new Thread(() -> download(url, ver), "vsp-update-dl").start();
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------ 下载

    private void download(String url, String ver) {
        File out = targetFile(ver);
        File part = new File(out.getAbsolutePath() + ".part");
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            if (out.exists()) out.delete();
            if (part.exists()) part.delete();

            // Gitee：releases/download → attach_files/…/download → foruda.gitee.com 的 CDN，
            // 两跳才拿得到 200；GitHub：releases/download → release-assets.githubusercontent.com，
            // 也要一跳。以前只跟一次，第二次的 302 就被当成失败报「服务器返回 302」。
            String cur = url;
            int code = 0;
            conn = open(cur);
            code = conn.getResponseCode();
            for (int hops = 0; conn != null; hops++) {
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) break;
                if (hops >= MAX_HOPS) throw new Exception("下载地址跳转次数过多");
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) throw new Exception("下载地址跳转失败（缺少跳转地址）");
                loc = loc.trim();
                if (!loc.startsWith("http://") && !loc.startsWith("https://")) {
                    loc = new URL(new URL(cur), loc).toString();
                }
                cur = loc;
                conn = open(cur);
                code = conn.getResponseCode();
            }
            if (code != 200 && code != 206) throw new Exception("服务器返回 " + code);

            // 跳到 CDN 后拿到的可能是「链接已过期」的网页，按 HTML 直接判失败，
            // 免得把一个几百 KB 的错误页当安装包存下来
            String ctype = conn.getContentType();
            if (ctype != null && ctype.toLowerCase(Locale.ROOT).contains("text/html")) {
                throw new Exception("下载地址已失效，请再点一次检查更新");
            }

            long len = conn.getContentLengthLong();
            if (len <= 0) {
                String cr = conn.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    try {
                        len = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
            // CDN 偶尔用 chunked 不给长度，退回检查更新时拿到的大小，进度条才有百分比
            if (len <= 0 && expectedSize > 0) len = expectedSize;
            totalSize = len > 0 ? len : 0L;

            in = conn.getInputStream();
            fos = new FileOutputStream(part);
            byte[] buf = new byte[128 * 1024];
            long done = 0L;
            long lastNotify = 0L, lastBytes = 0L, lastTime = System.currentTimeMillis();
            while (true) {
                if (cancelFlag.get()) throw new InterruptedException("cancel");
                int n = in.read(buf);
                if (n <= 0) break;
                fos.write(buf, 0, n);
                done += n;
                downloaded = done;
                long now = System.currentTimeMillis();
                if (totalSize > 0) percent = (int) Math.min(100L, done * 100L / totalSize);
                if (now - lastNotify >= 400) {
                    long dt = Math.max(1L, now - lastTime);
                    long db = done - lastBytes;
                    long bps = db * 1000L / dt;
                    speedText = human(bps) + "/s";
                    lastNotify = now;
                    lastBytes = done;
                    lastTime = now;
                    notifyProgress(buildNotification(percent,
                            human(done) + " / " + (totalSize > 0 ? human(totalSize) : "未知大小"),
                            speedText, null, false));
                }
            }
            fos.flush();
            fos.close();
            fos = null;
            if (part.length() < 1024 * 200) throw new Exception("下载内容不完整");
            long tol = totalSize > 0 ? Math.max(64L * 1024, totalSize / 50) : 0L;
            if (totalSize > 0 && Math.abs(part.length() - totalSize) > tol) {
                throw new Exception("安装包大小不符（" + human(part.length())
                        + " / " + human(totalSize) + "），请重试");
            }
            if (out.exists()) out.delete();
            if (!part.renameTo(out)) throw new Exception("保存安装包失败");

            savedPath = out.getAbsolutePath();
            downloaded = out.length();
            totalSize = downloaded;
            percent = 100;
            state = "done";
            running = false;
            notifyProgress(buildNotification(100, human(downloaded) + " / " + human(totalSize),
                    "下载完成，点击安装", out, true));
            stopForeground(false);
            stopSelf();
        } catch (InterruptedException ie) {
            cleanup(part);
            state = "idle";
            running = false;
            speedText = "";
            cancelNotification();
            stopForeground(true);
            stopSelf();
        } catch (Exception e) {
            cleanup(part);
            state = "failed";
            running = false;
            errorText = shortMsg(e);
            notifyProgress(buildNotification(percent,
                    human(downloaded) + " / " + (totalSize > 0 ? human(totalSize) : "未知大小"),
                    "下载失败：" + errorText, null, false));
            stopForeground(false);
            stopSelf();
        } finally {
            closeQuietly();
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {
            }
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        // 每跳都要 identity：跳到 CDN 那一步若被 gzip，Content-Length 与实际字节数不符
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    private File targetFile(String ver) {
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = getCacheDir();
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "VSP解析-v" + ver + ".apk");
    }

    private void cleanup(File part) {
        try {
            if (part != null && part.exists()) part.delete();
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

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_update)
                .setContentTitle(done ? "VSP 解析 v" + version + " 下载完成" : "正在下载 VSP 解析 v" + version)
                .setContentText(sizeLine)
                .setSubText(subLine)
                .setOnlyAlertOnce(true)
                .setOngoing(!done)
                .setAutoCancel(done)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, Math.max(0, Math.min(100, pct)), !done);

        if (done && apk != null && apk.exists()) {
            b.setContentIntent(installIntent(apk));
            b.setContentText("下载完成 · " + sizeLine + "，点击安装");
        } else {
            b.setContentIntent(content);
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

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly();
        super.onDestroy();
    }
}
