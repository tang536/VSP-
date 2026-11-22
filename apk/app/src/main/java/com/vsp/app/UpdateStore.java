package com.vsp.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 更新下载的持久化状态。
 *
 * 下载过程中进程被系统回收、用户退出应用或息屏后服务重启时，内存里的静态变量都会丢失；
 * 把地址 / 版本 / 已下载字节 / 状态写进 SharedPreferences，重新打开更新弹窗或服务被
 * START_STICKY 拉起时就能接着原来的 .part 文件继续下载，而不是从头再来。
 */
final class UpdateStore {

    static final String PREF = "vsp_update_download";

    static final String STATE_IDLE = "idle";
    static final String STATE_DOWNLOADING = "downloading";
    static final String STATE_DONE = "done";
    static final String STATE_FAILED = "failed";

    private static final String K_STATE = "state";
    private static final String K_URL = "url";
    /** 多个下载源，用换行拼接；为空时回退到 K_URL（兼容旧版本） */
    private static final String K_URLS = "urls";
    private static final String K_VERSION = "version";
    private static final String K_SIZE = "size";
    private static final String K_DOWNLOADED = "downloaded";
    private static final String K_PATH = "path";
    private static final String K_ERROR = "error";
    private static final String K_TIME = "time";

    private UpdateStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    static void begin(Context ctx, String url, String version, long size) {
        begin(ctx, url, null, version, size);
    }

    /** 带多下载源的开始：urls 为 null/空时等价于单 url。首元素同时写进 K_URL 便于兼容旧逻辑。 */
    static void begin(Context ctx, String url, java.util.List<String> urls, String version, long size) {
        String first = url == null ? "" : url;
        StringBuilder joined = new StringBuilder();
        if (urls != null) {
            for (String u : urls) {
                if (u == null || u.trim().isEmpty()) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(u.trim());
            }
        }
        if (joined.length() == 0 && !first.isEmpty()) joined.append(first);
        prefs(ctx).edit()
                .putString(K_STATE, STATE_DOWNLOADING)
                .putString(K_URL, first)
                .putString(K_URLS, joined.toString())
                .putString(K_VERSION, version == null ? "" : version)
                .putLong(K_SIZE, Math.max(0L, size))
                .putLong(K_DOWNLOADED, 0L)
                .remove(K_PATH)
                .remove(K_ERROR)
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    static void progress(Context ctx, long downloaded, long total) {
        prefs(ctx).edit()
                .putString(K_STATE, STATE_DOWNLOADING)
                .putLong(K_DOWNLOADED, Math.max(0L, downloaded))
                .putLong(K_SIZE, Math.max(0L, total))
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    static void done(Context ctx, String path, long size) {
        prefs(ctx).edit()
                .putString(K_STATE, STATE_DONE)
                .putString(K_PATH, path == null ? "" : path)
                .putLong(K_DOWNLOADED, Math.max(0L, size))
                .putLong(K_SIZE, Math.max(0L, size))
                .remove(K_ERROR)
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    static void failed(Context ctx, String error) {
        prefs(ctx).edit()
                .putString(K_STATE, STATE_FAILED)
                .putString(K_ERROR, error == null ? "" : error)
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    static String state(Context ctx) {
        return prefs(ctx).getString(K_STATE, STATE_IDLE);
    }

    static String url(Context ctx) {
        return prefs(ctx).getString(K_URL, "");
    }

    /** 读取全部下载源；旧数据没有 K_URLS 时回退到单个 K_URL。返回列表至少有一个元素。 */
    static java.util.List<String> urls(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String joined = prefs(ctx).getString(K_URLS, "");
        if (joined != null && !joined.isEmpty()) {
            for (String u : joined.split("\\n")) {
                if (u != null && !u.trim().isEmpty() && !out.contains(u.trim())) out.add(u.trim());
            }
        }
        if (out.isEmpty()) {
            String one = url(ctx);
            if (!one.isEmpty()) out.add(one);
        }
        return out;
    }

    static String version(Context ctx) {
        return prefs(ctx).getString(K_VERSION, "");
    }

    static long size(Context ctx) {
        return prefs(ctx).getLong(K_SIZE, 0L);
    }

    static long downloaded(Context ctx) {
        return prefs(ctx).getLong(K_DOWNLOADED, 0L);
    }

    static String path(Context ctx) {
        return prefs(ctx).getString(K_PATH, "");
    }

    static String error(Context ctx) {
        return prefs(ctx).getString(K_ERROR, "");
    }

    /** 有可继续的下载（正在下载，或上一次因网络问题中断但 .part 还在） */
    static boolean hasResumable(Context ctx) {
        String st = state(ctx);
        if (!STATE_DOWNLOADING.equals(st) && !STATE_FAILED.equals(st)) return false;
        return !url(ctx).isEmpty() && !version(ctx).isEmpty();
    }

    /** 已下载完成且安装包仍然存在 */
    static boolean hasCompleted(Context ctx) {
        if (!STATE_DONE.equals(state(ctx))) return false;
        String p = path(ctx);
        if (p.isEmpty()) return false;
        File f = new File(p);
        return f.isFile() && f.length() > 200L * 1024L;
    }
}
