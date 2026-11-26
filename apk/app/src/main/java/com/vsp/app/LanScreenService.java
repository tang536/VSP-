package com.vsp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import java.nio.ByteBuffer;

/**
 * 局域网远控「被控端」的屏幕共享前台服务。
 *
 * Android 14（API 34）起，MediaProjection.createVirtualDisplay() 只允许在
 * foregroundServiceType="mediaProjection" 的前台服务中调用，否则直接抛
 * SecurityException: MediaProjection requires a foreground service of type mediaProjection。
 * 因此把投影捕获整体放进本服务：先 startForeground，再 getMediaProjection + 建虚拟屏。
 *
 * 捕获到的 JPEG 帧写入静态 latestFrame，LanCore 的 /screen 接口从这里取。
 */
public final class LanScreenService extends Service {

    private static final String CHANNEL_ID = "vsp_lan_screen";
    private static final int NOTI_ID = 0x5A17;
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static volatile LanScreenService instance;
    private static volatile byte[] latestFrame;
    /** 系统/用户在通知栏停止共享时通知 LanCore 清理远控会话。 */
    public static volatile Runnable onStopped;

    /** LanCore 的 /screen 取最新帧；isProjecting 判断授权是否仍有效。 */
    public static byte[] getLatestFrame() { return latestFrame; }
    public static boolean isProjecting() { return instance != null; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        startForegroundCompat();
        if (intent != null) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
            else data = intent.getParcelableExtra(EXTRA_DATA);
            if (data != null) setupProjection(code, data);
        }
        return START_STICKY;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "局域网屏幕共享",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("全能工具箱局域网远控正在共享屏幕");
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= 31
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("全能工具箱")
                .setContentText("正在共享屏幕给局域网设备 · 点此返回")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else
                startForeground(NOTI_ID, n);
        } catch (Throwable ignored) { }
    }

    private void setupProjection(int code, Intent data) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            // 用户在系统弹窗里点「停止共享」时，安静收尾，不留僵尸虚拟屏
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> { release(); stopSelf(); }); }
            }, main);

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
            int h = dm.heightPixels > 0 ? dm.heightPixels : 1920;

            imageReader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image img = reader.acquireLatestImage();
                if (img == null) return;
                try {
                    ByteBuffer bb = img.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[bb.remaining()];
                    bb.get(jpeg);
                    latestFrame = jpeg;
                } catch (Throwable ignored) { }
                finally { img.close(); }
            }, main);

            virtualDisplay = projection.createVirtualDisplay("Toolbox-LAN", w, h, dm.densityDpi,
                    0, imageReader.getSurface(), null, main);
        } catch (Throwable ignored) {
            release();
            stopSelf();
        }
    }

    private void release() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Throwable ignored) { }
        try { if (imageReader != null) imageReader.close(); } catch (Throwable ignored) { }
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        virtualDisplay = null; imageReader = null; projection = null;
        latestFrame = null;
    }

    @Override
    public void onDestroy() {
        release();
        instance = null;
        Runnable cb = onStopped;
        if (cb != null) {
            try { cb.run(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
