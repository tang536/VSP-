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
import android.hardware.display.DisplayManager;
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
import android.util.Log;
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

    private static final String TAG = "LanScreenService";

    private static volatile LanScreenService instance;
    private static volatile byte[] latestFrame;
    /** 系统/用户在通知栏停止共享时通知 LanCore 清理远控会话。 */
    public static volatile Runnable onStopped;
    /** 系统授权/前台服务失败时把原因回传给 LanCore，避免静默停掉后 UI 一直等。 */
    public static volatile java.util.function.Consumer<String> onError;

    /** LanCore 的 /screen 取最新帧；只有真正拿到授权并建起虚拟屏才算在共享。 */
    public static byte[] getLatestFrame() { return latestFrame; }
    public static boolean isProjecting() {
        LanScreenService s = instance;
        return s != null && s.projection != null && s.virtualDisplay != null;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        // onStartCommand 里的任何异常都会直接杀进程：这里统一兜住并转成状态提示。
        try {
            if (!startForegroundCompat()) {
                reportError("无法启动屏幕共享前台服务，请检查通知权限");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (intent == null) return START_STICKY; // 服务被系统重建，等新的授权 Intent。
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data;
            if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
            else data = intent.getParcelableExtra(EXTRA_DATA);
            if (data == null) {
                reportError("屏幕共享授权数据已失效，请重新授权");
                stopSelf();
                return START_NOT_STICKY;
            }
            setupProjection(code, data);
        } catch (Throwable t) {
            Log.e(TAG, "onStartCommand failed", t);
            reportError(t.getMessage() == null ? "屏幕共享启动失败" : t.getMessage());
            release();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** 整个构建过程都要兜住：NotificationCompat 类初始化失败也属于可恢复故障，不能崩掉进程。 */
    private boolean startForegroundCompat() {
        try {
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
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else
                startForeground(NOTI_ID, n);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
            return false;
        }
    }

    private static void reportError(String message) {
        java.util.function.Consumer<String> cb = onError;
        if (cb == null) return;
        try { cb.accept(message); } catch (Throwable ignored) { }
    }

    private void setupProjection(int code, Intent data) {
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) throw new IllegalStateException("系统不支持屏幕共享");
            MediaProjection granted = mpm.getMediaProjection(code, data);
            // 授权过期/被系统拒绝时返回 null，不能直接 registerCallback。
            if (granted == null) throw new IllegalStateException("屏幕共享授权已失效，请重新授权");
            projection = granted;
            // Android 14+ 要求在 createVirtualDisplay 之前注册回调，否则系统会拒绝捕获。
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { main.post(() -> { release(); stopSelf(); }); }
            }, main);

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
            int h = dm.heightPixels > 0 ? dm.heightPixels : 1920;
            int dpi = dm.densityDpi > 0 ? dm.densityDpi : 320;

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

            virtualDisplay = projection.createVirtualDisplay("Toolbox-LAN", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, main);
            if (virtualDisplay == null) throw new IllegalStateException("系统未能建立屏幕捕获");
        } catch (Throwable t) {
            Log.e(TAG, "setupProjection failed", t);
            reportError(t.getMessage() == null ? "屏幕共享开启失败" : t.getMessage());
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
