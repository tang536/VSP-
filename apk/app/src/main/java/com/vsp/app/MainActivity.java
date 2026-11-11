package com.vsp.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final int PAGE_HOME = 0;
    private static final int PAGE_ME = 1;
    private static final int PAGE_QUEUE = 2;
    private static final int PAGE_LOCAL = 3;
    private static final int PAGE_STORAGE = 4;
    private static final int PAGE_FILES = 5;
    private static final int PAGE_VIDEOS = 6;
    private static final int PAGE_SCAN = 7;
    private static final int PAGE_COUNT = 8;

    // 多选归属：首页=批量下载；下载中 / 已完成两段队列各自独立选择
    private static final int SEL_ACTIVE = 10;
    private static final int SEL_FINISHED = 11;

    private static final int TYPE_VIDEO = 0;
    private static final int TYPE_AUDIO = 1;
    private static final int TYPE_IMAGE = 2;

    private static final int REQ_AVATAR = 1001;
    private static final int REQ_SCAN_TREE = 1002;
    private static final int REQ_MEDIA_PERM = 1003;
    private static final int REQ_NOTIF_PERM = 1004;
    private static final int SCAN_MAX = 1500;
    private static final int RECENT_MAX = 8;
    private static final String PREF = "vsp_prefs";
    private static final String RECENT_KEY = "vsp_recent";

    /** 真·检查更新：GitHub + Gitee 双源，取版本更高的那个 */
    private static final String UPDATE_REPO = "tang536/VSP-";
    private static final String GITEE_REPO = "tang-rongqiang/VSP";
    private static final String UPDATE_API =
            "https://api.github.com/repos/" + UPDATE_REPO + "/releases/latest";
    private static final String UPDATE_ATOM =
            "https://github.com/" + UPDATE_REPO + "/releases.atom";
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/" + GITEE_REPO + "/releases/latest";
    /** Gitee 还没建 Release 时，读仓库里的 apk/dist/latest.json（随源码一起推上去） */
    private static final String GITEE_INDEX =
            "https://gitee.com/" + GITEE_REPO + "/raw/main/apk/dist/latest.json";
    private static final String UPDATE_PAGE = "https://github.com/" + UPDATE_REPO + "/releases";
    private static final String GITEE_PAGE = "https://gitee.com/" + GITEE_REPO + "/releases";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    /** 下载链路用的桌面 UA；网络视频预览也用它，保证和「下载能成」那条路一致 */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String KIND_HLS = "hls";
    private static final String KIND_DASH = "dash";
    private static final String KIND_PROGRESSIVE = "progressive";

    private Python py;
    private volatile boolean pyReady = false;
    private volatile String lastTasksJson = "";
    private volatile String downloadDir = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService taskPoller;

    private SharedPreferences prefs, recentPrefs;
    private boolean darkMode = true;
    private boolean suppressThemeSwitch = false;
    private int currentPage = PAGE_HOME;
    private Palette P;

    private View root, pageHome, pageMe, pageQueue, pageLocal, pageStorage, pageFiles, pageVideos, pageScan;
    private View topBar, btnHistory, btnMore, bottomTab, selectionBar, playerPanel;
    /** 每个页面各有一个 player_slot，全 app 唯一的播放器在打开预览时挂到当前页。 */
    private final FrameLayout[] playerSlots = new FrameLayout[PAGE_COUNT];

    // 首页
    private EditText urlInput;
    private Button btnParse;
    private ProgressBar parseProgress;
    private TextView errorText, resultTitle, resultUploader;
    private TextView tabVideos, tabAudios, tabImages;
    private View resultMeta, tabs;
    private TextView formatHint;
    private ImageView resultThumb;
    private RecyclerView formatList;
    private String currentUrl = "", currentTab = "videos", currentThumb = "";
    private JSONObject parsedData;
    private List<JSONObject> currentFormats = new ArrayList<>();

    // 我的
    private ImageView avatar;
    private File avatarFile;
    private TextView dirPath, queueSummary, updateVersion;
    private ProgressBar updateProgress;
    private Switch themeSwitch;
    private volatile boolean checkingUpdate = false;
    private String latestApkUrl = "", latestVersion = "", latestNotes = "", latestDate = "",
            latestSource = "github";
    private long latestSize = 0L;
    private boolean repoEmpty = false;

    // 下载队列：同页展示「下载中」与「已完成」两段
    private RecyclerView downloadingList, taskList;
    private TextView dlCount, noDownloading, noTask;

    // 本地 / 视频 / 扫描 / 文件
    private RecyclerView localGrid, videoGrid, filesList;
    private TextView localEmpty, localSummary, videoEmpty, filesEmpty, filesTotal;
    private TextView scanStatus, scanCount, scanScope, btnScanStart;
    private ProgressBar scanProgress, scanBtnProgress;
    private volatile boolean scanning = false;
    private List<MediaItem> localFiles = new ArrayList<>();
    private List<MediaItem> videoFiles = new ArrayList<>();
    private List<File> storageFiles = new ArrayList<>();
    private List<File> downloadFiles = new ArrayList<>();

    // 存储
    private TextView stCount, stSize, stCache;
    private ProgressBar clearCacheProgress, clearAllProgress;
    private TextView btnClearCache, btnClearAll;

    // 观看层（视频 / 音频 / 图片共用：全屏、进度、倍速、音量）
    private VideoView playerVideo;
    /** 网络视频：ExoPlayer + PlayerView；本地 / content 视频仍走 VideoView */
    private PlayerView playerExo;
    private ExoPlayer exoPlayer;
    private boolean exoActive = false, exoPrepared = false;
    private String exoSource = "", exoKind = KIND_PROGRESSIVE, exoAudioUrl = "";
    private String previewPageUrl = "", previewFormatId = "";
    private boolean exoTriedDropAudio = false, exoTriedRefresh = false;
    private View playerAudio, playerHeader, playerStage, playerOverlay;
    private int playerType = TYPE_IMAGE;
    private ImageView playerImage, playerAudioCover, playerAudioToggle;
    private TextView playerAudioTitle, playerAudioTime, playerTime, playerDuration, btnSpeed,
            playerVolumeVal, playerTitle, playerKind, playerSub;
    private SeekBar playerAudioSeek, playerVolume;
    // 图片模式的缩略图条与底部操作
    private View playerImgBar, playerSeekWrap, playerImgActions;
    private View playerCtrlRow, playerBottom;
    private ImageView playerOrient;
    // 视频预览态：时长 / 分辨率 + 全屏播放按钮；音量浮层（竖排）
    private View playerInlineBar, playerVolumePop;
    private TextView playerInlineMeta, playerFullBtn;
    /** 视频是否已进入「全屏播放」。false = 竖屏预览态（画面照常播，底部给全屏按钮） */
    private boolean playerFull = false;
    private LinearLayout playerThumbs;
    private HorizontalScrollView playerThumbsScroll;
    private View playerImgShare, playerImgInfo, playerImgDelete;
    /** 顶部标题栏（返回 + 文件名）当前是否可见，视频播放时跟随控制条一起自动隐藏 */
    private boolean headerShown = true;
    /** 观看层实际让开的状态栏 / 导航栏 / 刘海尺寸 */
    private int watchInsetTop, watchInsetBottom, watchInsetLeft, watchInsetRight;
    /** 当前观看所在的图片列表与下标（缩略图条、左右滑动切换用） */
    private List<MediaItem> watchSiblings;
    private int watchIndex = -1;
    /** 从「我的」头像进来的观看：底部换成更换头像 / 恢复默认 */
    private boolean watchingAvatar = false;
    private String watchSource = "", watchTitle = "";
    private boolean watchLocal = false;
    private MediaPlayer audioPlayer, currentMp;
    private boolean audioPrepared = false, audioPlaying = false;
    private float playVolume = 1.0f;
    // 图片缩放（双指缩放 / 双击放大 / 放大后拖动）
    private final Matrix imageMatrix = new Matrix();
    private ScaleGestureDetector imageScaleDetector;
    private android.view.GestureDetector imageGestureDetector;
    private float imageScale = 1f, imageTx = 0f, imageTy = 0f;
    /**
     * 当前正在显示的图片位图。切图时先换上新图再释放旧图，
     * 否则连划十几张大图会一路吃满堆内存直接闪退。
     */
    private Bitmap watchImageBitmap;
    /** 当前主图是否由我们自己解码（可以安全 recycle）；网络图可能来自缓存，不能回收 */
    private boolean watchImageRecyclable;
    /** 切图序号：后台解码回来时若序号已变，说明用户又划走了，结果直接丢弃 */
    private int watchImageToken = 0;
    /** 跟手翻页用的相邻图：和主图并排，拖动时从侧边顶进来 */
    private ImageView watchSwipeImage;
    private Bitmap watchSwipeBitmap;
    private int watchSwipeIndex = -1;
    private int watchSwipeToken = 0;
    /**
     * 相邻图解码线程池。以前每次滑动都 new Thread：连划十下就是十个线程同时解 1600px 大图，
     * 峰值内存直接顶穿堆，这就是「划一会儿就闪退」的另一半原因。单线程串行最稳。
     */
    private final java.util.concurrent.ExecutorService swipePool =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "vsp-swipe-image");
                t.setDaemon(true);
                return t;
            });
    /** 已启动落定动画、但 endAction 还没执行的方向（0 = 无）。中途再按下要先把它结掉 */
    private int swipePending = 0;
    private boolean swipeLocked;
    private int swipeDirection = 0;
    private float swipeDownX = 0f, swipeDownY = 0f, swipeDx = 0f;
    private static final float[] SPEEDS = {0.5f, 1.0f, 1.25f, 1.5f, 2.0f};
    private int speedIndex = 1;

    // 多选
    private boolean selectionMode = false;
    private int selectionOwner = -1;
    private final Set<String> selectedIds = new HashSet<>();
    private List<JSONObject> activeTasks = new ArrayList<>();
    private List<JSONObject> finishedTasks = new ArrayList<>();
    private TextView selCount;

    private final Map<String, Bitmap> thumbCache = new LinkedHashMap<>();
    /** 缩略图长边上限（像素）：46dp 的格子用 360 已经很清，再大纯粹是浪费内存和磁盘 */
    private static final int THUMB_PX = 360;
    /** 写盘计数，每 32 张检查一次缓存总量 */
    private int thumbWriteCount = 0;
    /**
     * 缩略图解码线程池。以前每张缩略图各开一个线程，进一次缩略图条能瞬间起上百个线程，
     * 解码又都是大图，内存和 CPU 一起炸；固定两个线程排队做，稳得多。
     */
    private final java.util.concurrent.ExecutorService thumbPool =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    private AlertDialog historyDialog;

    // 全屏
    private FrameLayout fullscreenHost;
    private boolean fullscreen = false;

    // 更新下载
    // 正在下载 / 安装更新：期间不允许重复触发检查更新与下载
    private Dialog updateDialog;
    private int updateDialogMode = -1;
    private TextView updateDialogTitle, updateDialogSubtitle, updateDialogNotes,
            updateDialogPercent, updateDialogLine, updateDialogError, updateDialogBrowser;
    private View updateDialogNotesWrap, updateDialogProgressWrap;
    private LinearLayout updateDialogActionsAvailable, updateDialogActionsProgress,
            updateDialogActionsDone, updateDialogActionsFailed;
    private ProgressBar updateDialogBar;
    private boolean updatePolling = false;
    private boolean updateResumeTried = false;
    private final Runnable updatePollTick = new Runnable() {
        @Override
        public void run() {
            pollUpdateProgress();
        }
    };

    /** 控制条 3 秒无操作自动收起（顶部标题栏与返回键一起收，不挡画面） */
    private final Runnable hideOverlay = () -> hidePlayerOverlay();

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            int pos = 0, dur = 0;
            try {
                if (playerType == TYPE_VIDEO && exoActive && exoPlayer != null) {
                    pos = (int) Math.max(0, exoPlayer.getCurrentPosition());
                    long d = exoPlayer.getDuration();
                    dur = (d == C.TIME_UNSET || d < 0) ? 0 : (int) d;
                } else {
                    MediaPlayer mp = currentMp;
                    if (mp == null) return;
                    pos = mp.getCurrentPosition();
                    dur = mp.getDuration();
                }
                if (dur > 0) {
                    playerAudioSeek.setMax(dur);
                    playerAudioSeek.setProgress(pos);
                    playerTime.setText(fmtTime(pos));
                    playerDuration.setText(fmtTime(dur));
                }
            } catch (Throwable ignored) {
            }
            ui.postDelayed(this, 500);
        }
    };

    /** ExoPlayer 18 秒还没 READY 就当失败：部分 CDN 会一直连但不出数据 */
    private final Runnable exoLoadTimeout = () -> {
        if (exoActive && !exoPrepared) {
            handleExoFailure("加载超时（18 秒）");
        }
    };

    private final Player.Listener exoListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (!exoActive || playerExo == null) return;
            if (state == Player.STATE_READY) {
                exoPrepared = true;
                ui.removeCallbacks(exoLoadTimeout);
                applyPlaybackSettings(null);
                playerAudioToggle.setImageResource(R.drawable.ic_pause);
                updateVideoInlineMeta(null, exoSource, false);
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
            } else if (state == Player.STATE_ENDED) {
                playerAudioToggle.setImageResource(R.drawable.ic_resume);
                ui.removeCallbacks(progressTick);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            if (!exoActive) return;
            playerAudioToggle.setImageResource(isPlaying
                    ? R.drawable.ic_pause : R.drawable.ic_resume);
        }

        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            if (exoActive && exoPrepared) updateVideoInlineMeta(null, exoSource, false);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (!exoActive) return;
            String code = error == null ? "未知错误"
                    : "ExoPlayer 错误 " + error.errorCode;
            String msg = error == null || error.getMessage() == null
                    ? "" : error.getMessage();
            if (msg.length() > 180) msg = msg.substring(0, 180) + "…";
            handleExoFailure(code + (msg.isEmpty() ? "" : " · " + msg));
        }
    };

    /** 深色 / 浅色两套调色板；布局里的 android:tag 决定控件取哪一档颜色。 */
    static class Palette {
        int bg, card, text, dim, accent, input, soft, divider, icon, danger, errorBg, errorText;

        static Palette dark() {
            Palette p = new Palette();
            p.bg = Color.parseColor("#0f1115");
            p.card = Color.parseColor("#171a21");
            p.text = Color.parseColor("#e8eaed");
            p.dim = Color.parseColor("#8b909a");
            p.accent = Color.parseColor("#f97316");
            p.input = Color.parseColor("#171a21");
            p.soft = Color.parseColor("#1f232c");
            p.divider = Color.parseColor("#262b34");
            p.icon = Color.parseColor("#a2a8b3");
            p.danger = Color.parseColor("#ef4444");
            p.errorBg = Color.parseColor("#22ef4444");
            p.errorText = Color.parseColor("#fca5a5");
            return p;
        }

        static Palette light() {
            Palette p = new Palette();
            p.bg = Color.parseColor("#f4f5f7");
            p.card = Color.parseColor("#ffffff");
            p.text = Color.parseColor("#1b1e25");
            p.dim = Color.parseColor("#6b7280");
            p.accent = Color.parseColor("#d95f0a");
            p.input = Color.parseColor("#eceef2");
            p.soft = Color.parseColor("#e8eaee");
            p.divider = Color.parseColor("#dfe2e8");
            p.icon = Color.parseColor("#5b6270");
            p.danger = Color.parseColor("#dc2626");
            p.errorBg = Color.parseColor("#1eef4444");
            p.errorText = Color.parseColor("#b91c1c");
            return p;
        }
    }

    /** 本地媒体条目：source 可能是文件路径、SAF content:// 或 MediaStore content:// */
    private static class MediaItem {
        final String source;
        final String name;
        final long size;
        final long time;
        final int type;
        final long id;
        final long duration;

        MediaItem(String source, String name, long size, long time) {
            this(source, name, size, time, guessType(name), -1, 0);
        }

        MediaItem(String source, String name, long size, long time, int type, long id, long duration) {
            this.source = source;
            this.name = name;
            this.size = size;
            this.time = time;
            this.type = type;
            this.id = id;
            this.duration = duration;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            prefs = getSharedPreferences(PREF, MODE_PRIVATE);
            recentPrefs = getSharedPreferences(RECENT_KEY, MODE_PRIVATE);
            darkMode = prefs.getBoolean("dark_mode", true);
            P = darkMode ? Palette.dark() : Palette.light();
            // 启动时清一次缩略图缓存：旧版本按 1000px 存过一批，堆到几十上百 MB
            new Thread(this::trimThumbDir, "vsp-trim-thumbs").start();
            avatarFile = new File(getFilesDir(), "avatar.png");

            setContentView(R.layout.activity_main);
            root = findViewById(R.id.root);
            pageHome = getLayoutInflater().inflate(R.layout.page_home, null);
            pageMe = getLayoutInflater().inflate(R.layout.page_me, null);
            pageQueue = getLayoutInflater().inflate(R.layout.page_queue, null);
            pageLocal = getLayoutInflater().inflate(R.layout.page_local, null);
            pageStorage = getLayoutInflater().inflate(R.layout.page_storage, null);
            pageFiles = getLayoutInflater().inflate(R.layout.page_files, null);
            pageVideos = getLayoutInflater().inflate(R.layout.page_videos, null);
            pageScan = getLayoutInflater().inflate(R.layout.page_scan, null);
            FrameLayout content = findViewById(R.id.content);
            content.addView(pageHome);
            content.addView(pageMe);
            content.addView(pageQueue);
            content.addView(pageLocal);
            content.addView(pageStorage);
            content.addView(pageFiles);
            content.addView(pageVideos);
            content.addView(pageScan);
            topBar = findViewById(R.id.top_bar);
            btnHistory = findViewById(R.id.btn_history);
            btnMore = findViewById(R.id.btn_more);
            bottomTab = findViewById(R.id.bottom_tab);
            selectionBar = findViewById(R.id.selection_bar);

            // 播放器只 inflate 一次，按需挂载到当前页面的 slot
            playerPanel = getLayoutInflater().inflate(R.layout.player_panel, null);
            playerSlots[PAGE_HOME] = pageHome.findViewById(R.id.player_slot);
            playerSlots[PAGE_ME] = pageMe.findViewById(R.id.player_slot);
            playerSlots[PAGE_QUEUE] = pageQueue.findViewById(R.id.player_slot);
            playerSlots[PAGE_LOCAL] = pageLocal.findViewById(R.id.player_slot);
            playerSlots[PAGE_FILES] = pageFiles.findViewById(R.id.player_slot);
            playerSlots[PAGE_VIDEOS] = pageVideos.findViewById(R.id.player_slot);

            bindHome();
            bindMe();
            bindQueue();
            bindLocal();
            bindVideos();
            bindScan();
            bindFiles();
            bindStorage();
            bindPlayer();
            bindGlobal();

            applyTheme();
            switchPage(PAGE_HOME);
            loadAvatar();
            refreshStorage();
            // 恢复上次的扫描结果与解析结果，退出重开 / 覆盖升级后不用重新扫、重新解析
            loadScanCache();
            loadLastParse();
            resumePendingUpdate();

            new Thread(() -> {
                try {
                    if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                    py = Python.getInstance();
                    File external = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File base = (external != null) ? external : getFilesDir();
                    File privateDir = new File(base, "VSP解析");
                    privateDir.mkdirs();
                    py.getModule("vsp").callAttr("set_private_dir", privateDir.getAbsolutePath());
                    downloadDir = py.getModule("vsp").callAttr("get_save_dir").toString();
                    pyReady = true;
                    // 后台把预览转发服务先拉起来，别等用户点预览时在 UI 线程里启动
                    try {
                        py.getModule("vsp").callAttr("warm_proxy");
                    } catch (Throwable ignored) {
                    }
                    startTaskPolling();
                    ui.post(this::refreshStorage);
                } catch (Throwable t) {
                    ui.post(() -> Toast.makeText(this, "Python 初始化失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
                }
            }, "vsp-python-init").start();
        } catch (Throwable t) {
            Log.e("VSP", "启动失败", t);
            String msg = t.toString();
            if (t.getCause() != null) msg += "\nCause: " + t.getCause();
            Toast.makeText(this, "启动失败: " + msg, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 绑定 ----------------

    private void bindHome() {
        urlInput = pageHome.findViewById(R.id.url_input);
        btnParse = pageHome.findViewById(R.id.btn_parse);
        parseProgress = pageHome.findViewById(R.id.parse_progress);
        errorText = pageHome.findViewById(R.id.error_text);
        resultTitle = pageHome.findViewById(R.id.result_title);
        resultUploader = pageHome.findViewById(R.id.result_uploader);
        resultMeta = pageHome.findViewById(R.id.result_meta);
        resultThumb = pageHome.findViewById(R.id.result_thumb);
        tabVideos = pageHome.findViewById(R.id.tab_videos);
        tabAudios = pageHome.findViewById(R.id.tab_audios);
        tabImages = pageHome.findViewById(R.id.tab_images);
        tabs = pageHome.findViewById(R.id.tabs);
        formatHint = pageHome.findViewById(R.id.format_hint);
        formatList = pageHome.findViewById(R.id.format_list);
        formatList.setLayoutManager(new LinearLayoutManager(this));

        btnParse.setOnClickListener(v -> doParse());
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            doParse();
            return true;
        });
        tabVideos.setOnClickListener(v -> switchTab("videos"));
        tabAudios.setOnClickListener(v -> switchTab("audios"));
        tabImages.setOnClickListener(v -> switchTab("images"));
    }

    private void bindMe() {
        avatar = pageMe.findViewById(R.id.avatar);
        dirPath = pageMe.findViewById(R.id.dir_path);
        dirPath.setText("打开 ›");
        queueSummary = pageMe.findViewById(R.id.queue_summary);
        updateVersion = pageMe.findViewById(R.id.update_version);
        updateProgress = pageMe.findViewById(R.id.update_progress);
        themeSwitch = pageMe.findViewById(R.id.theme_switch);
        updateVersion.setText("v" + versionName());

        pageMe.findViewById(R.id.row_queue).setOnClickListener(v -> switchPage(PAGE_QUEUE));
        View rowDir = pageMe.findViewById(R.id.row_dir);
        rowDir.setOnClickListener(v -> openDir());
        rowDir.setOnLongClickListener(v -> {
            Toast.makeText(this, "应用下载目录: " + (downloadDir.isEmpty() ? "未初始化" : downloadDir), Toast.LENGTH_LONG).show();
            return true;
        });
        pageMe.findViewById(R.id.row_theme).setOnClickListener(v -> setDarkMode(!darkMode));
        themeSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (!suppressThemeSwitch) setDarkMode(checked);
        });
        pageMe.findViewById(R.id.row_storage).setOnClickListener(v -> switchPage(PAGE_STORAGE));
        pageMe.findViewById(R.id.row_update).setOnClickListener(v -> checkUpdate());
        avatar.setOnClickListener(v -> showAvatarViewer());
        // 长按头像：更换 / 恢复默认（不在看图界面里摆一个「更多」按钮）
        avatar.setOnLongClickListener(v -> {
            showAvatarActions();
            return true;
        });
    }

    private void bindQueue() {
        downloadingList = pageQueue.findViewById(R.id.downloading_list);
        noDownloading = pageQueue.findViewById(R.id.no_downloading);
        dlCount = pageQueue.findViewById(R.id.dl_count);
        taskList = pageQueue.findViewById(R.id.task_list);
        noTask = pageQueue.findViewById(R.id.no_task);
        downloadingList.setLayoutManager(new LinearLayoutManager(this));
        taskList.setLayoutManager(new LinearLayoutManager(this));
        pageQueue.findViewById(R.id.dl_back).setOnClickListener(v -> switchPage(PAGE_ME));
    }

    private void bindLocal() {
        localGrid = pageLocal.findViewById(R.id.local_grid);
        localEmpty = pageLocal.findViewById(R.id.local_empty);
        localSummary = pageLocal.findViewById(R.id.local_summary);
        localGrid.setLayoutManager(new GridLayoutManager(this, 3));
        localEmpty.setVisibility(View.VISIBLE);
    }

    private void bindVideos() {
        videoGrid = pageVideos.findViewById(R.id.video_grid);
        videoEmpty = pageVideos.findViewById(R.id.video_empty);
        videoGrid.setLayoutManager(new GridLayoutManager(this, 3));
        pageVideos.findViewById(R.id.video_back).setOnClickListener(v -> switchPage(PAGE_LOCAL));
    }

    private void bindScan() {
        scanStatus = pageScan.findViewById(R.id.scan_status);
        scanCount = pageScan.findViewById(R.id.scan_count);
        scanScope = pageScan.findViewById(R.id.scan_scope);
        scanProgress = pageScan.findViewById(R.id.scan_progress);
        scanBtnProgress = pageScan.findViewById(R.id.scan_btn_progress);
        btnScanStart = pageScan.findViewById(R.id.btn_scan_start);
        pageScan.findViewById(R.id.scan_close).setOnClickListener(v -> switchPage(PAGE_LOCAL));
        btnScanStart.setOnClickListener(v -> startScan());
        pageScan.findViewById(R.id.btn_scan_custom).setOnClickListener(v -> showScanDirPicker());
        pageScan.findViewById(R.id.btn_scan_filter).setOnClickListener(v -> showScanFilter());
        updateScanScope();
    }

    private void bindFiles() {
        filesList = pageFiles.findViewById(R.id.files_list);
        filesEmpty = pageFiles.findViewById(R.id.files_empty);
        filesTotal = pageFiles.findViewById(R.id.files_total);
        filesList.setLayoutManager(new LinearLayoutManager(this));
        pageFiles.findViewById(R.id.files_back).setOnClickListener(v -> switchPage(PAGE_STORAGE));
    }

    private void bindStorage() {
        stCount = pageStorage.findViewById(R.id.st_count);
        stSize = pageStorage.findViewById(R.id.st_size);
        stCache = pageStorage.findViewById(R.id.st_cache);
        pageStorage.findViewById(R.id.storage_back).setOnClickListener(v -> switchPage(PAGE_ME));
        btnClearCache = pageStorage.findViewById(R.id.btn_clear_cache);
        btnClearAll = pageStorage.findViewById(R.id.btn_clear_all);
        clearCacheProgress = pageStorage.findViewById(R.id.clear_cache_progress);
        clearAllProgress = pageStorage.findViewById(R.id.clear_all_progress);
        btnClearCache.setOnClickListener(v -> clearCache());
        btnClearAll.setOnClickListener(v -> clearAllDownloads());
        pageStorage.findViewById(R.id.row_files).setOnClickListener(v -> switchPage(PAGE_FILES));
    }

    private void bindPlayer() {
        playerVideo = playerPanel.findViewById(R.id.player_video);
        playerExo = playerPanel.findViewById(R.id.player_exo);
        try {
            playerExo.setUseController(false);
            playerExo.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        } catch (Throwable ignored) {
        }
        playerAudio = playerPanel.findViewById(R.id.player_audio);
        playerImage = playerPanel.findViewById(R.id.player_image);
        playerAudioCover = playerPanel.findViewById(R.id.player_audio_cover);
        playerAudioTitle = playerPanel.findViewById(R.id.player_audio_title);
        playerAudioTime = playerPanel.findViewById(R.id.player_audio_time);
        playerAudioSeek = playerPanel.findViewById(R.id.player_audio_seek);
        playerAudioToggle = playerPanel.findViewById(R.id.player_audio_toggle);
        playerHeader = playerPanel.findViewById(R.id.player_header);
        playerStage = playerPanel.findViewById(R.id.player_stage);
        playerOverlay = playerPanel.findViewById(R.id.player_overlay);
        playerBottom = playerPanel.findViewById(R.id.player_bottom);
        playerCtrlRow = playerPanel.findViewById(R.id.player_ctrl_row);
        playerOrient = playerPanel.findViewById(R.id.player_orient);
        playerTime = playerPanel.findViewById(R.id.player_time);
        playerDuration = playerPanel.findViewById(R.id.player_duration);
        btnSpeed = playerPanel.findViewById(R.id.btn_speed);
        playerVolume = playerPanel.findViewById(R.id.player_volume);
        playerVolumeVal = playerPanel.findViewById(R.id.player_volume_val);
        playerVolumePop = playerPanel.findViewById(R.id.player_volume_pop);
        playerInlineBar = playerPanel.findViewById(R.id.player_inline_bar);
        playerInlineMeta = playerPanel.findViewById(R.id.player_inline_meta);
        playerFullBtn = playerPanel.findViewById(R.id.player_full_btn);
        playerTitle = playerPanel.findViewById(R.id.player_title);
        playerKind = playerPanel.findViewById(R.id.player_kind);
        try {
            playerHeader.setBackgroundResource(R.drawable.scrim_top);
            if (playerBottom != null) playerBottom.setBackgroundResource(R.drawable.scrim_bottom);
        } catch (Throwable ignored) {
        }
        // 顶部返回键、底部控制行要避开状态栏 / 导航栏 / 刘海
        setupWatchInsets();
        if (playerOrient != null) playerOrient.setOnClickListener(v -> {
            toggleOrientation();
            showPlayerOverlay();
        });

        // 点击画面唤出 / 收起浮层控制条
        View.OnClickListener toggle = v -> {
            if (playerType == TYPE_IMAGE) return;
            if (playerOverlay.getVisibility() == View.VISIBLE) hidePlayerOverlay();
            else showPlayerOverlay();
        };
        playerStage.setOnClickListener(toggle);
        playerVideo.setOnClickListener(toggle);
        if (playerExo != null) {
            playerExo.setOnClickListener(toggle);
            playerExo.setKeepScreenOn(true);
        }
        playerAudio.setOnClickListener(toggle);
        playerImage.setOnClickListener(toggle);
        // 长按画面 = 更多（头像在这里改头像 / 恢复默认）
        playerStage.setOnLongClickListener(v -> {
            showWatchMoreMenu();
            return true;
        });
        playerVideo.setOnLongClickListener(v -> {
            showWatchMoreMenu();
            return true;
        });
        if (playerExo != null) {
            playerExo.setOnLongClickListener(v -> {
                showWatchMoreMenu();
                return true;
            });
        }
        playerAudio.setOnLongClickListener(v -> {
            showWatchMoreMenu();
            return true;
        });
        playerOverlay.setOnLongClickListener(v -> {
            showWatchMoreMenu();
            return true;
        });
        // overlay 铺满全屏，点空白处相当于点画面：显示中则收起，隐藏时无法点击（GONE）
        playerOverlay.setOnClickListener(v -> {
            if (playerOverlay.getVisibility() == View.VISIBLE) hidePlayerOverlay();
            else showPlayerOverlay();
        });

        // 左上角 backspace：退出观看
        playerPanel.findViewById(R.id.player_close).setOnClickListener(v -> exitWatch());
        playerAudioToggle.setOnClickListener(v -> {
            togglePlay();
            showPlayerOverlay();
        });
        btnSpeed.setOnClickListener(v -> {
            cycleSpeed();
            showPlayerOverlay();
        });
        btnSpeed.setText(speedLabel());
        playerVolume.setProgress(100);
        playerVolumeVal.setText("100%");
        // 音量浮层默认收起，点音量图标才在图标上方展开（滑杆是竖排的）
        if (playerVolumePop != null) playerVolumePop.setVisibility(View.GONE);
        playerPanel.findViewById(R.id.player_volume_icon).setOnClickListener(v -> {
            boolean show = playerVolumePop != null
                    && playerVolumePop.getVisibility() != View.VISIBLE;
            if (playerVolumePop != null) {
                playerVolumePop.setVisibility(show ? View.VISIBLE : View.GONE);
            }
            // 展开时先别让控制条自己收起来，否则浮层会跟着消失
            ui.removeCallbacks(hideOverlay);
            if (show) ui.postDelayed(hideOverlay, 6000);
            else showPlayerOverlay();
        });
        if (playerFullBtn != null) {
            playerFullBtn.setOnClickListener(v -> enterFullPlay());
        }
        // 图片模式：缩略图条 + 底部操作（对照系统相册的看图界面）
        playerSub = playerPanel.findViewById(R.id.player_sub);
        playerImgBar = playerPanel.findViewById(R.id.player_img_bar);
        playerThumbs = playerPanel.findViewById(R.id.player_thumbs);
        playerThumbsScroll = playerPanel.findViewById(R.id.player_thumbs_scroll);
        playerSeekWrap = playerPanel.findViewById(R.id.player_seek_wrap);
        playerImgActions = playerPanel.findViewById(R.id.player_img_actions);
        playerImgShare = playerPanel.findViewById(R.id.player_img_share);
        playerImgInfo = playerPanel.findViewById(R.id.player_img_info);
        playerImgDelete = playerPanel.findViewById(R.id.player_img_delete);
        playerImgShare.setOnClickListener(v -> shareCurrentMedia());
        playerImgInfo.setOnClickListener(v -> showCurrentMediaInfo());
        playerImgDelete.setOnClickListener(v -> deleteCurrentMedia());

        setupImageZoom();

        playerAudioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    showPlayerOverlay();
                    if (exoActive && exoPlayer != null) {
                        try {
                            exoPlayer.seekTo(progress);
                        } catch (Throwable ignored) {
                        }
                    } else {
                        MediaPlayer mp = currentMp;
                        if (mp != null) {
                            try {
                                mp.seekTo(progress);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                showPlayerOverlay();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                showPlayerOverlay();
            }
        });
        playerVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                showPlayerOverlay();
                playVolume = progress / 100f;
                playerVolumeVal.setText(progress + "%");
                if (exoActive && exoPlayer != null) {
                    try {
                        exoPlayer.setVolume(playVolume);
                    } catch (Throwable ignored) {
                    }
                } else if (currentMp != null) {
                    try {
                        currentMp.setVolume(playVolume, playVolume);
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                showPlayerOverlay();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    private void bindGlobal() {
        findViewById(R.id.tab_home).setOnClickListener(v -> switchPage(PAGE_HOME));
        findViewById(R.id.tab_local).setOnClickListener(v -> switchPage(PAGE_LOCAL));
        findViewById(R.id.tab_me).setOnClickListener(v -> switchPage(PAGE_ME));
        btnHistory.setOnClickListener(v -> showHistory());
        btnMore.setOnClickListener(v -> showLocalMenu(v));
        selCount = findViewById(R.id.sel_count);
        findViewById(R.id.sel_all).setOnClickListener(v -> selectAll());
        findViewById(R.id.sel_delete).setOnClickListener(v -> confirmSelected());
        findViewById(R.id.sel_cancel).setOnClickListener(v -> exitSelection());
    }

    /** 本地页右上角两个点：提取视频配乐 / 扫描 */
    private void showLocalMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "提取视频配乐");
        menu.getMenu().add(0, 2, 1, "扫描本地媒体");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                openVideosPage();
            } else {
                switchPage(PAGE_SCAN);
            }
            return true;
        });
        menu.show();
    }

    // ---------------- 主题 ----------------

    private void setDarkMode(boolean dark) {
        if (darkMode == dark) return;
        darkMode = dark;
        prefs.edit().putBoolean("dark_mode", dark).apply();
        P = dark ? Palette.dark() : Palette.light();
        applyTheme();
    }

    private void applyTheme() {
        if (P == null || root == null) return;
        getWindow().setStatusBarColor(P.card);
        getWindow().setNavigationBarColor(P.card);
        applyStatusBarIcons();
        applyThemeToTree(root);
        if (playerPanel != null) applyThemeToTree(playerPanel);

        suppressThemeSwitch = true;
        themeSwitch.setChecked(darkMode);
        suppressThemeSwitch = false;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                themeSwitch.setThumbTintList(ColorStateList.valueOf(P.accent));
                themeSwitch.setTrackTintList(ColorStateList.valueOf(P.divider));
            } catch (Throwable ignored) {
            }
        }
        refreshTabColors();
        refreshFormatTabColors();
        loadAvatar();
        updateSelectionBar();
    }

    private void applyStatusBarIcons() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (fullscreen) return;
        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (darkMode) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);
    }

    private void applyThemeToTree(View v) {
        if (v == null) return;
        Object tag = v.getTag();
        if (tag instanceof String) applyTag(v, (String) tag);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyThemeToTree(g.getChildAt(i));
        }
    }

    private void applyTag(View v, String tag) {
        switch (tag) {
            case "bg":
                v.setBackgroundColor(P.bg);
                break;
            case "card":
                v.setBackgroundColor(P.card);
                break;
            case "divider":
                v.setBackgroundColor(P.divider);
                break;
            case "soft":
                v.setBackgroundColor(P.soft);
                if (v instanceof TextView) ((TextView) v).setTextColor(P.text);
                break;
            case "text":
                if (v instanceof TextView) ((TextView) v).setTextColor(P.text);
                break;
            case "dim":
                if (v instanceof TextView) ((TextView) v).setTextColor(P.dim);
                break;
            case "accent":
                if (v instanceof TextView) ((TextView) v).setTextColor(P.accent);
                break;
            case "danger":
                if (v instanceof TextView) ((TextView) v).setTextColor(P.danger);
                break;
            case "accent_bg":
                v.setBackgroundColor(P.accent);
                if (v instanceof TextView) ((TextView) v).setTextColor(Color.WHITE);
                break;
            case "input":
                v.setBackgroundColor(P.input);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(P.text);
                    if (v instanceof EditText) ((EditText) v).setHintTextColor(P.dim);
                }
                break;
            case "error":
                v.setBackgroundColor(P.errorBg);
                if (v instanceof TextView) ((TextView) v).setTextColor(P.errorText);
                break;
            case "icon":
                if (v instanceof ImageView) ((ImageView) v).setColorFilter(P.icon);
                break;
            case "overlay_text":
                // 控制条永远叠在半透明黑底上，与主题无关，强制白字
                if (v instanceof TextView) ((TextView) v).setTextColor(Color.WHITE);
                break;
            case "overlay_icon":
                if (v instanceof ImageView) ((ImageView) v).setColorFilter(Color.WHITE);
                break;
            case "accent_icon":
                if (v instanceof ImageView) ((ImageView) v).setColorFilter(P.accent);
                break;
            case "danger_icon":
                if (v instanceof ImageView) ((ImageView) v).setColorFilter(P.danger);
                break;
            case "thumb":
                v.setBackgroundColor(P.divider);
                if (v instanceof ImageView) {
                    ImageView iv = (ImageView) v;
                    if (iv.getDrawable() instanceof BitmapDrawable) iv.clearColorFilter();
                    else iv.setColorFilter(P.icon);
                }
                break;
            case "tab_home":
                setActiveColor(v, currentPage == PAGE_HOME);
                break;
            case "tab_local":
                setActiveColor(v, currentPage == PAGE_LOCAL);
                break;
            case "tab_me":
                setActiveColor(v, currentPage == PAGE_ME);
                break;
            case "fmt_tab":
                boolean active = (v.getId() == R.id.tab_videos && "videos".equals(currentTab))
                        || (v.getId() == R.id.tab_audios && "audios".equals(currentTab))
                        || (v.getId() == R.id.tab_images && "images".equals(currentTab));
                if (v instanceof TextView) ((TextView) v).setTextColor(active ? P.accent : P.dim);
                break;
            default:
                break;
        }
    }

    private void setActiveColor(View v, boolean active) {
        if (v == null) return;
        int c = active ? P.accent : P.dim;
        if (v instanceof ImageView) ((ImageView) v).setColorFilter(c);
        else if (v instanceof TextView) ((TextView) v).setTextColor(c);
    }

    private void refreshTabColors() {
        setActiveColor(findViewById(R.id.tab_home_icon), currentPage == PAGE_HOME);
        setActiveColor(findViewById(R.id.tab_home_text), currentPage == PAGE_HOME);
        setActiveColor(findViewById(R.id.tab_local_icon), currentPage == PAGE_LOCAL);
        setActiveColor(findViewById(R.id.tab_local_text), currentPage == PAGE_LOCAL);
        setActiveColor(findViewById(R.id.tab_me_icon), currentPage == PAGE_ME);
        setActiveColor(findViewById(R.id.tab_me_text), currentPage == PAGE_ME);
    }

    private void refreshFormatTabColors() {
        if (tabVideos == null) return;
        tabVideos.setTextColor("videos".equals(currentTab) ? P.accent : P.dim);
        tabAudios.setTextColor("audios".equals(currentTab) ? P.accent : P.dim);
        tabImages.setTextColor("images".equals(currentTab) ? P.accent : P.dim);
    }

    // ---------------- 页面 ----------------

    private void switchPage(int page) {
        if (selectionMode && page != selectionOwner) exitSelection();
        if (fullscreen && page != currentPage) exitWatch();
        // 切页时收起播放器，避免看不到面板却还在播放
        if (playerPanel != null && playerPanel.getVisibility() == View.VISIBLE) {
            ui.removeCallbacks(hideOverlay);
            stopPlayer();
            playerPanel.setVisibility(View.GONE);
        }
        currentPage = page;
        pageHome.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        pageMe.setVisibility(page == PAGE_ME ? View.VISIBLE : View.GONE);
        pageQueue.setVisibility(page == PAGE_QUEUE ? View.VISIBLE : View.GONE);
        pageLocal.setVisibility(page == PAGE_LOCAL ? View.VISIBLE : View.GONE);
        pageStorage.setVisibility(page == PAGE_STORAGE ? View.VISIBLE : View.GONE);
        pageFiles.setVisibility(page == PAGE_FILES ? View.VISIBLE : View.GONE);
        pageVideos.setVisibility(page == PAGE_VIDEOS ? View.VISIBLE : View.GONE);
        pageScan.setVisibility(page == PAGE_SCAN ? View.VISIBLE : View.GONE);

        // 最高级页面（首页 / 本地 / 我的）才显示 VSP 解析标题栏与底部标签；
        // 历史图标只出现在首页，⋯ 只出现在本地；子页面只保留自己的返回栏。
        boolean topLevel = (page == PAGE_HOME || page == PAGE_LOCAL || page == PAGE_ME);
        topBar.setVisibility(topLevel ? View.VISIBLE : View.GONE);
        btnHistory.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        btnMore.setVisibility(page == PAGE_LOCAL ? View.VISIBLE : View.GONE);
        bottomTab.setVisibility(topLevel ? View.VISIBLE : View.GONE);
        refreshTabColors();
        // 扫描只由用户主动触发（扫描页 / 切换扫描区域），切页不重扫，结果一直复用
        if (page == PAGE_STORAGE) refreshStorage();
        if (page == PAGE_FILES) refreshFilesPage();
        if (page == PAGE_VIDEOS) renderVideos();
        if (page == PAGE_SCAN && !scanning && localFiles.isEmpty()) {
            scanStatus.setText("尚未扫描");
            scanCount.setText("点下方「开始扫描」扫描手机里的视频、音频和图片");
        }
    }

    private void switchTab(String tab) {
        currentTab = tab;
        refreshFormatTabColors();
        showFormatsFromCache();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            exitWatch();
            return;
        }
        if (selectionMode) {
            exitSelection();
            return;
        }
        if (currentPage == PAGE_QUEUE || currentPage == PAGE_STORAGE) {
            switchPage(PAGE_ME);
            return;
        }
        if (currentPage == PAGE_FILES) {
            switchPage(PAGE_STORAGE);
            return;
        }
        if (currentPage == PAGE_VIDEOS || currentPage == PAGE_SCAN) {
            switchPage(PAGE_LOCAL);
            return;
        }
        if (currentPage == PAGE_ME || currentPage == PAGE_LOCAL) {
            switchPage(PAGE_HOME);
            return;
        }
        super.onBackPressed();
    }

    // ---------------- 头像 ----------------

    private void loadAvatar() {
        if (avatar == null) return;
        if (avatarFile != null && avatarFile.exists()) {
            try {
                Bitmap b = decodeSampled(avatarFile, 512);
                if (b != null) {
                    avatar.setImageBitmap(b);
                    avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    applyTag(avatar, "thumb");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        avatar.setImageResource(R.drawable.ic_avatar_default);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        applyTag(avatar, "thumb");
    }

    /** 长按头像：设置 / 更换 / 恢复默认（原来看图界面里那个「更多」按钮的位置） */
    private void showAvatarActions() {
        final boolean has = avatarFile != null && avatarFile.exists();
        CharSequence[] items = has
                ? new CharSequence[]{"更换头像", "查看头像", "恢复默认头像"}
                : new CharSequence[]{"设置头像"};
        new AlertDialog.Builder(this)
                .setTitle("头像")
                .setItems(items, (d, which) -> {
                    if (!has) {
                        pickAvatar();
                    } else if (which == 0) {
                        pickAvatar();
                    } else if (which == 1) {
                        showAvatarViewer();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("恢复默认头像")
                                .setMessage("当前头像会被清除，确定吗？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("恢复", (dd, ww) -> clearAvatar())
                                .show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 点击头像：直接全屏观看（可缩放），长按换头像 */
    private void showAvatarViewer() {
        if (avatarFile == null || !avatarFile.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("头像")
                    .setMessage("还没有设置头像")
                    .setPositiveButton("去设置", (d, w) -> pickAvatar())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        List<MediaItem> sib = new ArrayList<>();
        sib.add(new MediaItem(avatarFile.getAbsolutePath(), "我的头像",
                avatarFile.length(), avatarFile.lastModified(), TYPE_IMAGE, -1, 0));
        openPlayer(avatarFile.getAbsolutePath(), TYPE_IMAGE, "我的头像", true, sib, 0);
        watchingAvatar = true;
        renderWatchThumbs();
    }

    private void pickAvatar() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_AVATAR);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearAvatar() {
        if (avatarFile != null && avatarFile.exists()) avatarFile.delete();
        loadAvatar();
        Toast.makeText(this, "已恢复默认头像", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCAN_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(
                            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {
                }
                prefs.edit()
                        .putString("scan_mode", "tree")
                        .putString("scan_tree", tree.toString())
                        .putString("scan_label", "自定义文件夹")
                        .apply();
                updateScanScope();
                scanStatus.setText("已切换到自定义文件夹");
                promptScanManual();
            }
            return;
        }
        if (requestCode != REQ_AVATAR || resultCode != RESULT_OK || data == null || data.getData() == null)
            return;
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                // 原图原样落盘：不裁剪、不缩放、不重新编码，避免用户头像被压花
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new Exception("无法读取图片");
                File tmp = new File(avatarFile.getAbsolutePath() + ".tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                fos.flush();
                fos.close();
                in.close();
                if (total <= 0) throw new Exception("图片是空的");
                if (avatarFile.exists()) avatarFile.delete();
                if (!tmp.renameTo(avatarFile)) throw new Exception("保存失败");
                // 解码一次确认能用，避免存了坏图进来一片空白
                if (decodeSampled(avatarFile, 512) == null) throw new Exception("图片解码失败");
                ui.post(() -> {
                    loadAvatar();
                    Toast.makeText(this, "头像已更新", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "头像设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "vsp-avatar").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MEDIA_PERM) return;
        if (hasMediaPermission()) {
            startScan();
        } else {
            Toast.makeText(this, "没有媒体读取权限，无法扫描手机里的文件", Toast.LENGTH_LONG).show();
            scanStatus.setText("缺少媒体读取权限");
            scanCount.setText("请在系统设置里允许读取视频、音频和图片，或改用「自定义扫描」指定文件夹");
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取图片");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 24 * 1024 * 1024) throw new Exception("图片过大");
        }
        in.close();
        return out.toByteArray();
    }

    /** 居中裁成正方形并补不透明底，不再做圆形遮罩 */
    /** 按目标像素采样解码，原图多大都不会把内存撑爆 */
    private Bitmap decodeSampled(File f, int targetPx) {
        if (f == null || !f.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max > 0 && max / sample > targetPx) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 缩略图 ----------------

    private void loadThumb(ImageView iv, String url) {
        loadThumb(iv, url, 2);
    }

    // ---------------- 缩略图磁盘缓存 ----------------
    //
    // 之前缩略图只存在内存 Map 里，退出 app 就没了，所以「缓存」永远是 0 字节。
    // 现在网络图与本地首帧都落盘到 cacheDir/thumbs，下次进来直接读文件。

    private File thumbDir() {
        File d = new File(getCacheDir(), "thumbs");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File thumbDiskFile(String key) {
        return new File(thumbDir(), hashKey(key) + ".img");
    }

    private static String hashKey(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }

    private Bitmap readDiskThumb(String key) {
        try {
            File f = thumbDiskFile(key);
            if (!f.exists() || f.length() == 0) return null;
            // 旧版本写过 1000px 级的图，这里也按 360 采样，别再整张读进内存
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleFor(bounds, THUMB_PX);
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeDiskThumb(String key, Bitmap bmp) {
        if (bmp == null) return;
        FileOutputStream fos = null;
        try {
            File f = thumbDiskFile(key);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            fos = new FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, fos);
            fos.flush();
            if (f.exists()) f.delete();
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignored) {
            }
        }
        // 每写 32 张检查一次总量，超了就从最旧的开始删，避免缓存目录无限膨胀
        if (++thumbWriteCount % 32 == 0) trimThumbDir();
    }

    /** 缩略图缓存上限；超过就按最后修改时间删掉最旧的一部分 */
    private void trimThumbDir() {
        final long limit = 32L * 1024 * 1024;
        try {
            File dir = thumbDir();
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= limit) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : fs) {
                if (total <= limit) break;
                long len = f.length();
                if (f.delete()) total -= len;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 按目标像素算采样率：长边不超过 targetPx */
    private static int sampleFor(BitmapFactory.Options bounds, int targetPx) {
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max > 0 && max / sample > targetPx) sample *= 2;
        return Math.max(1, sample);
    }

    /** 本地图片生成缩略图：先量尺寸再按 360 采样，内存与磁盘占用都被按住 */
    private Bitmap decodeLocalThumb(String source) {
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            if (source.startsWith("content://")) {
                in = getContentResolver().openInputStream(Uri.parse(source));
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                in = null;
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inSampleSize = sampleFor(bounds, THUMB_PX);
                in = getContentResolver().openInputStream(Uri.parse(source));
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, opt);
            }
            BitmapFactory.decodeFile(source, bounds);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleFor(bounds, THUMB_PX);
            return BitmapFactory.decodeFile(source, opt);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void loadThumb(ImageView iv, String url, int sample) {
        if (iv == null) return;
        final String key = url == null ? "" : url;
        iv.setTag(R.id.thumb_url, key);
        iv.setBackgroundColor(P.divider);
        if (key.isEmpty()) {
            iv.setImageResource(R.drawable.ic_image);
            iv.setColorFilter(P.icon);
            return;
        }
        Bitmap cached;
        synchronized (thumbCache) {
            cached = thumbCache.get(key);
        }
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.clearColorFilter();
            return;
        }
        iv.setImageResource(R.drawable.ic_image);
        iv.setColorFilter(P.icon);
        thumbPool.execute(() -> {
            Bitmap bmp = readDiskThumb(key);
            if (bmp == null) {
                bmp = fetchBitmap(key, sample);
                if (bmp != null) writeDiskThumb(key, bmp);
            }
            if (bmp != null) {
                synchronized (thumbCache) {
                    thumbCache.put(key, bmp);
                    // 360px 缩略图单张约 400KB，60 张封顶约 24MB；再多就是白白占堆
                    if (thumbCache.size() > 60) {
                        Iterator<String> it = thumbCache.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                    }
                }
            }
            final Bitmap got = bmp;
            ui.post(() -> {
                if (got != null && key.equals(iv.getTag(R.id.thumb_url))) {
                    iv.setImageBitmap(got);
                    iv.clearColorFilter();
                }
            });
        });
    }

    private Bitmap fetchBitmap(String url, int sample) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            // 图床（hdslb / douyinpic / kwaicdn）都要求原站 Referer，
            // 拿图床自己的域名当 Referer 会被判盗链
            String ref = refererFor(url);
            if (ref != null) c.setRequestProperty("Referer", ref);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeStream(c.getInputStream(), null, opt);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 本地媒体缩略图：视频取首帧，图片取样解码，音频用音符图标 */
    private void loadMediaThumb(ImageView iv, MediaItem m) {
        if (iv == null) return;
        if (m.type == TYPE_AUDIO) {
            iv.setImageResource(R.drawable.ic_music);
            iv.setColorFilter(P.icon);
            return;
        }
        final String key = "media:" + m.source;
        Bitmap cached;
        synchronized (thumbCache) {
            cached = thumbCache.get(key);
        }
        if (cached != null) {
            iv.setImageBitmap(cached);
            iv.clearColorFilter();
            return;
        }
        iv.setImageResource(m.type == TYPE_IMAGE ? R.drawable.ic_image : R.drawable.ic_video);
        iv.setColorFilter(P.icon);
        thumbPool.execute(() -> {
            Bitmap bmp = readDiskThumb(key);
            try {
                if (bmp != null) {
                    // 命中磁盘缓存
                } else if (m.type == TYPE_IMAGE) {
                    bmp = decodeLocalThumb(m.source);
                } else {
                    bmp = videoThumb(m);
                }
            } catch (Throwable ignored) {
            }
            if (bmp != null) {
                writeDiskThumb(key, bmp);
                synchronized (thumbCache) {
                    thumbCache.put(key, bmp);
                    // 360px 缩略图单张约 400KB，60 张封顶约 24MB；再多就是白白占堆
                    if (thumbCache.size() > 60) {
                        Iterator<String> it = thumbCache.keySet().iterator();
                        if (it.hasNext()) {
                            it.next();
                            it.remove();
                        }
                    }
                }
            }
            final Bitmap fb = bmp;
            ui.post(() -> {
                if (fb != null) {
                    iv.setImageBitmap(fb);
                    iv.clearColorFilter();
                }
            });
        });
    }

    private Bitmap videoThumb(MediaItem m) {
        try {
            Uri uri = Uri.parse(m.source);
            if (m.source.startsWith("content://")) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return getContentResolver().loadThumbnail(uri, new Size(360, 200), null);
                }
                if (m.id > 0) {
                    return MediaStore.Video.Thumbnails.getThumbnail(
                            getContentResolver(), m.id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                }
                return null;
            }
            return ThumbnailUtils.createVideoThumbnail(m.source,
                    MediaStore.Video.Thumbnails.MINI_KIND);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 最近解析 ----------------

    private void saveRecent(String url, String title, String thumb) {
        try {
            JSONArray arr = getRecent();
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("url", url).put("title", title).put("thumb", thumb));
            for (int i = 0; i < arr.length() && out.length() < RECENT_MAX; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !url.equals(o.optString("url", ""))) out.put(o);
            }
            recentPrefs.edit().putString("items", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private JSONArray getRecent() {
        try {
            return new JSONArray(recentPrefs.getString("items", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void showHistory() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(P.card);
        box.setPadding(dp(18), dp(14), dp(14), dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("最近解析");
        title.setTextColor(P.text);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = new TextView(this);
        clear.setText("清空");
        clear.setTextColor(P.dim);
        clear.setTextSize(12);
        clear.setPadding(dp(12), dp(8), dp(8), dp(8));
        head.addView(clear);
        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextColor(P.accent);
        close.setTextSize(12);
        close.setPadding(dp(10), dp(8), dp(4), dp(8));
        head.addView(close);
        box.addView(head);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        JSONArray arr = getRecent();
        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无解析记录");
            empty.setTextColor(P.dim);
            empty.setTextSize(13);
            empty.setGravity(android.view.Gravity.CENTER);
            empty.setPadding(0, dp(36), 0, dp(36));
            list.addView(empty);
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String u = o.optString("url", "");
            String label = o.optString("title", "").trim();
            if (label.isEmpty()) label = u;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(P.divider);
            row.addView(iv, new LinearLayout.LayoutParams(dp(50), dp(50)));
            loadThumb(iv, o.optString("thumb", ""));

            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(12), 0, 0, 0);
            TextView t = new TextView(this);
            t.setText(label);
            t.setTextColor(P.text);
            t.setTextSize(13);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(t);
            TextView s = new TextView(this);
            s.setText(u);
            s.setTextColor(P.dim);
            s.setTextSize(11);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(s);
            row.addView(txt, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(v -> {
                urlInput.setText(u);
                switchPage(PAGE_HOME);
                if (historyDialog != null) historyDialog.dismiss();
                doParse();
            });
            list.addView(row);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        box.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)));

        historyDialog = new AlertDialog.Builder(this).setView(box).create();
        clear.setOnClickListener(v -> {
            recentPrefs.edit().remove("items").apply();
            historyDialog.dismiss();
            Toast.makeText(this, "已清空最近解析", Toast.LENGTH_SHORT).show();
        });
        close.setOnClickListener(v -> historyDialog.dismiss());
        historyDialog.show();
        try {
            historyDialog.getWindow().setBackgroundDrawable(new ColorDrawable(P.card));
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 解析 / 下载 / 预览 ----------------

    private void setParsing(boolean on) {
        btnParse.setEnabled(!on);
        btnParse.setText(on ? "" : "解析");
        parseProgress.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void doParse() {
        String u = urlInput.getText().toString().trim();
        if (u.isEmpty()) {
            Toast.makeText(this, "先粘贴一个链接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!u.startsWith("http")) u = "https://" + u;
        if (!pyReady) {
            Toast.makeText(this, "正在初始化，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        currentUrl = u;
        urlInput.setText(u);
        errorText.setVisibility(View.GONE);
        setParsing(true);

        final String target = u;
        new Thread(() -> {
            try {
                String result = py.getModule("vsp").callAttr("parse", target).toString();
                JSONObject data = new JSONObject(result);
                ui.post(() -> {
                    setParsing(false);
                    parsedData = data;
                    currentThumb = data.optString("thumbnail", "");
                    resultMeta.setVisibility(View.VISIBLE);
                    tabs.setVisibility(View.VISIBLE);
                    formatHint.setVisibility(View.VISIBLE);
                    resultTitle.setText(data.optString("title", ""));
                    resultUploader.setText(data.optString("uploader", ""));
                    loadThumb(resultThumb, currentThumb);
                    saveRecent(target, data.optString("title", ""), currentThumb);
                    // 优先停在有内容的分类上，避免「没有视频」的空列表
                    int v = data.optJSONArray("videos") == null ? 0 : data.optJSONArray("videos").length();
                    int a = data.optJSONArray("audios") == null ? 0 : data.optJSONArray("audios").length();
                    int i = data.optJSONArray("images") == null ? 0 : data.optJSONArray("images").length();
                    if (v == 0 && a > 0) currentTab = "audios";
                    else if (v == 0 && a == 0 && i > 0) currentTab = "images";
                    else currentTab = "videos";
                    refreshFormatTabColors();
                    showFormatsFromCache();
                    saveLastParse(target, data);
                    Toast.makeText(this, "解析成功 · 视频 " + v + " / 音频 " + a + " / 图片 " + i,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    setParsing(false);
                    errorText.setText("解析失败：" + friendlyError(e));
                    errorText.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    /** Chaquopy 把 Python 异常包了一层，取最后一段真正的原因给用户看 */
    private String friendlyError(Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        int idx = msg.lastIndexOf("Exception: ");
        if (idx >= 0) msg = msg.substring(idx + 11);
        if (msg.length() > 220) msg = msg.substring(0, 220) + "…";
        return msg.trim();
    }

    private void showFormatsFromCache() {
        if (parsedData == null) return;
        JSONArray arr = parsedData.optJSONArray(currentTab);
        if (arr == null) arr = new JSONArray();
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) items.add(o);
        }
        currentFormats = items;
        formatList.setAdapter(new FormatAdapter(items));
        formatHint.setText(items.isEmpty()
                ? "这个分类下没有可用格式，换个分类看看"
                : "点击条目在上方预览 · 长按可批量下载");
    }

    private void startDownload(JSONObject fmt, boolean quiet) {
        if (!pyReady) {
            Toast.makeText(this, "正在初始化，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        final String formatId = fmt.optString("formatId", "");
        final String title = resultTitle.getText().toString();
        final String url = currentUrl;
        final String thumb = currentThumb;
        final boolean needsMerge = fmt.optBoolean("needsMerge", false);
        new Thread(() -> {
            try {
                py.getModule("vsp").callAttr("start_download", url, formatId, title, thumb, needsMerge);
                lastTasksJson = "";
                refreshTasks();
                if (!quiet) {
                    ui.post(() -> Toast.makeText(this, "已加入下载队列 · 我的 → 下载队列", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "下载失败: " + friendlyError(e), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void doPreview(JSONObject fmt) {
        final String formatId = fmt.optString("formatId", "");
        final boolean image = "thumb".equals(formatId) || "images".equals(currentTab);
        final int type = image ? TYPE_IMAGE : ("audios".equals(currentTab) ? TYPE_AUDIO : TYPE_VIDEO);
        final String title = resultTitle.getText().toString();
        previewPageUrl = currentUrl;
        previewFormatId = formatId;

        if (type == TYPE_IMAGE) {
            String url = currentThumb.isEmpty() ? fmt.optString("url", "") : currentThumb;
            if (url.isEmpty()) {
                Toast.makeText(this, "无法获取图片预览", Toast.LENGTH_SHORT).show();
                return;
            }
            openPlayer(url, TYPE_IMAGE, title, false);
            return;
        }

        // 解析结果里已经带了直链（yt-dlp 的 format 也带 url）。直接用它：
        // 以前所有预览都重新跑一次 get_preview_url，部分站点的 format_id 两次解析
        // 并不稳定，链接也会在这期间过期，表现就是「同一个视频有时能预览、有时不能」。
        String direct = fmt.optString("url", "").trim();
        if (direct.startsWith("http://") || direct.startsWith("https://")) {
            openPlayer(direct, type, title, false, null, -1, fmt);
            return;
        }

        if (!pyReady) {
            Toast.makeText(this, "正在初始化，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在准备预览…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String out = "";
            String fail = "";
            try {
                out = py.getModule("vsp")
                        .callAttr("get_preview_info", currentUrl, formatId, "", false)
                        .toString();
            } catch (Exception e) {
                fail = friendlyError(e);
            }
            final String json = out == null ? "" : out;
            final String error = fail;
            ui.post(() -> {
                JSONObject info = null;
                try {
                    info = new JSONObject(json);
                } catch (Throwable ignored) {
                }
                String previewUrl = info == null ? "" : info.optString("url", "");
                if (previewUrl.isEmpty()) {
                    // 解析层没给出直链，也把原因摊出来（别只丢一句「无法获取预览」）
                    String why = info == null ? "" : info.optString("error", "");
                    if (why.isEmpty()) why = error;
                    showPreviewDiagnosis(null, -1, -1,
                            why.isEmpty()
                                    ? "解析没有返回可播放的直链（该条目可能只有分离的音视频轨）"
                                    : "解析没有返回可播放的直链：" + why);
                } else {
                    // 把这一轮的容器类型 / 音轨一起交给播放层，避免只凭 URL 后缀猜
                    JSONObject built = new JSONObject();
                    String audio = info.optString("audioUrl", "");
                    try {
                        built.put("formatId", formatId);
                        built.put("url", previewUrl);
                        built.put("kind", info.optString("kind", ""));
                        built.put("audioUrl", audio);
                        built.put("needsMerge", !audio.isEmpty());
                        built.put("ext", info.optString("ext", ""));
                    } catch (Throwable ignored) {
                    }
                    openPlayer(previewUrl, type, title, false, null, -1, built);
                }
            });
        }).start();
    }

    // ---------------- 观看层（全屏） ----------------

    /** 把观看层挂到 decor 顶层：点条目直接全屏观看，不再在页面里嵌小窗 */
    private void attachToWatchHost() {
        if (playerPanel == null) return;
        try {
            if (fullscreenHost == null) {
                fullscreenHost = new FrameLayout(this);
                fullscreenHost.setBackgroundColor(Color.BLACK);
                fullscreenHost.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                ViewGroup decor = (ViewGroup) getWindow().getDecorView();
                // 挂在 DecorView 上而不是 android.R.id.content：
                // content 会被软键盘 / adjustResize 压缩，观看层跟着被压扁就只剩一条缝
                decor.addView(fullscreenHost, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            fullscreenHost.setVisibility(View.VISIBLE);
            if (playerPanel.getParent() != fullscreenHost) {
                if (playerPanel.getParent() instanceof ViewGroup) {
                    ((ViewGroup) playerPanel.getParent()).removeView(playerPanel);
                }
                fullscreenHost.addView(playerPanel, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
        } catch (Throwable t) {
            Log.w("VSP", "挂载观看层失败", t);
        }
    }

    /**
     * 加载观看层主图。解码全部放后台线程，并用递增序号丢弃过期结果：
     * 连续左右滑动时，前一张的解码回来晚了也不会盖掉当前这张。
     */
    private void loadWatchImage(final String source, final boolean local, final boolean isContent) {
        loadWatchImage(source, local, isContent, false);
    }

    /**
     * @param keepOld true 时先保留当前画面，等新图解码完再替换。
     *                跟手翻页落定后要用它：相邻图已经顶到正中，这时再清空会黑一下。
     */
    private void loadWatchImage(final String source, final boolean local, final boolean isContent,
                                final boolean keepOld) {
        final int token = ++watchImageToken;
        if (!keepOld) {
            playerImage.setBackgroundColor(Color.BLACK);
            playerImage.setImageDrawable(null);
        }
        new Thread(() -> {
            Bitmap b;
            boolean recyclable = true;
            if (isContent) {
                b = decodeContentImage(source, 2400);
            } else if (local) {
                b = decodeSampled(new File(source), 2400);
            } else {
                // 网络图可能来自 thumbCache，不能随手 recycle
                b = fetchBitmap(source, 1);
                recyclable = false;
            }
            final Bitmap got = b;
            final boolean canRecycle = recyclable;
            ui.post(() -> {
                if (token != watchImageToken) {
                    if (got != null && canRecycle) recycleQuiet(got);
                    return;
                }
                if (got == null) {
                    Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                Bitmap old = watchImageBitmap;
                boolean oldRecyclable = watchImageRecyclable;
                watchImageBitmap = got;
                watchImageRecyclable = canRecycle;
                playerImage.setImageBitmap(got);
                // 同步算矩阵：以前是 post 出去的，换图与重算之间会有一帧
                // 拿新位图套旧 scale 画出来，看着就是「闪一下、图跳一下」
                applyImageFit();
                if (old != null && old != got && oldRecyclable) recycleQuiet(old);
            });
        }, "vsp-view-image").start();
    }

    /** 释放当前主图（退出观看、切到视频时调用） */
    private void releaseWatchImage() {
        watchImageToken++;
        releaseSwipeImage();
        if (playerImage != null) playerImage.setImageDrawable(null);
        if (watchImageBitmap != null) {
            if (watchImageRecyclable) recycleQuiet(watchImageBitmap);
            watchImageBitmap = null;
            watchImageRecyclable = false;
        }
    }

    /** content:// 图片按目标尺寸采样解码：先量边界再定 inSampleSize，避免整张大图进内存 */
    private Bitmap decodeContentImage(String source, int targetPx) {
        InputStream in = null;
        try {
            Uri u = Uri.parse(source);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(u);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max > 0 && max / sample > targetPx) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = Math.max(1, sample);
            in = getContentResolver().openInputStream(u);
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, opt);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void openPlayer(String source, int type, String title, boolean local) {
        openPlayer(source, type, title, local, null, -1, null);
    }

    private void openPlayer(String source, int type, String title, boolean local,
                            List<MediaItem> siblings, int index) {
        openPlayer(source, type, title, local, siblings, index, null);
    }

    private void openPlayer(String source, int type, String title, boolean local,
                            List<MediaItem> siblings, int index, JSONObject fmt) {
        // 解析输入框还带着焦点时直接进观看层，软键盘会把窗口顶起来：
        // 观看层被压扁、底部进度条跑到屏幕外，只能退出 app 重进才恢复。先进来先收键盘。
        dismissKeyboard();
        stopPlayer();
        attachToWatchHost();
        playerPanel.setBackgroundColor(Color.BLACK);
        playerPanel.setVisibility(View.VISIBLE);
        boolean media = type != TYPE_IMAGE;
        boolean keepFull = playerFull;
        playerType = type;
        fullscreen = true;
        watchingAvatar = false;
        watchSource = source;
        watchTitle = title;
        watchLocal = local;
        watchSiblings = siblings;
        watchIndex = index;
        final boolean isContent = source != null && source.startsWith("content://");
        // 网络视频统一交给 ExoPlayer：HLS 相对分片、DASH、FLV / MKV、
        // 以及 yt-dlp 常见的「画面流 + 独立音轨」都能正确播。
        final boolean isNetworkVideo = type == TYPE_VIDEO && !local && !isContent
                && source != null && source.startsWith("http");
        exoActive = isNetworkVideo;
        if (fmt != null) {
            previewFormatId = fmt.optString("formatId", previewFormatId);
        }
        // 视频沿用上一次是否全屏；图片 / 音频不进全屏播放态
        playerFull = keepFull && type == TYPE_VIDEO;
        // 切换文件时不触发顶部标题的淡出动画，末尾统一按类型亮一次
        ui.removeCallbacks(hideOverlay);
        ui.removeCallbacks(hideImageChrome);
        if (playerOverlay != null) playerOverlay.setVisibility(View.GONE);
        if (playerImgBar != null) {
            playerImgBar.setVisibility(media ? View.GONE : View.VISIBLE);
        }
        // 进度条必须在这里显式恢复：曾经只写了「非媒体就 GONE」，
        // 结果看过一次图片之后，之后所有视频的进度条都再也不出现。
        if (playerSeekWrap != null) playerSeekWrap.setVisibility(View.VISIBLE);
        if (playerVolumePop != null) playerVolumePop.setVisibility(View.GONE);
        // 从图片切到视频 / 音频：先放掉主图占的内存
        if (type != TYPE_IMAGE) releaseWatchImage();
        playerVideo.setVisibility(type == TYPE_VIDEO && !isNetworkVideo ? View.VISIBLE : View.GONE);
        if (playerExo != null) {
            playerExo.setVisibility(isNetworkVideo ? View.VISIBLE : View.GONE);
        }
        playerAudio.setVisibility(type == TYPE_AUDIO ? View.VISIBLE : View.GONE);
        playerImage.setVisibility(type == TYPE_IMAGE ? View.VISIBLE : View.GONE);
        playerAudioSeek.setProgress(0);
        playerTime.setText("00:00");
        playerDuration.setText("00:00");
        playerAudioToggle.setImageResource(R.drawable.ic_resume);
        btnSpeed.setText(speedLabel());
        // 顶部：文件名 + 类型
        playerTitle.setText(title == null || title.trim().isEmpty() ? "未命名" : title.trim());
        playerKind.setText(type == TYPE_VIDEO ? "视频" : type == TYPE_AUDIO ? "音频" : "图片");
        // 视频默认竖屏「预览态」（和图片一样先看内容），点全屏播放按钮才转横屏
        try {
            setRequestedOrientation(type == TYPE_VIDEO && playerFull
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Throwable ignored) {
        }
        applyImmersive(true);
        resetImageZoom();

        if (type == TYPE_VIDEO) {
            if (isNetworkVideo) {
                startExoVideo(source, fmt);
            } else {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", UA);
                String ref = refererFor(source);
                if (ref != null) headers.put("Referer", ref);
                if (local && !isContent) playerVideo.setVideoPath(source);
                else playerVideo.setVideoURI(Uri.parse(source), headers);
                playerVideo.setOnPreparedListener(mp -> {
                    currentMp = mp;
                    applyPlaybackSettings(mp);
                    playerAudioToggle.setImageResource(R.drawable.ic_pause);
                    updateVideoInlineMeta(mp, source, local);
                    ui.removeCallbacks(progressTick);
                    ui.post(progressTick);
                });
                playerVideo.setOnErrorListener((mp, what, extra) -> {
                    Toast.makeText(this, "无法播放该视频（格式可能不受支持）",
                            Toast.LENGTH_SHORT).show();
                    return true;
                });
                playerVideo.setOnCompletionListener(mp -> {
                    playerAudioToggle.setImageResource(R.drawable.ic_resume);
                    ui.removeCallbacks(progressTick);
                });
                playerVideo.start();
            }
        } else if (type == TYPE_AUDIO) {
            playerAudioTitle.setText(title);
            playerAudioTime.setText("音频播放");
            prepareAudio(source, local);
        } else {
            // 解码统一放后台：以前在主线程 decode 一张几千万像素的原图要几百毫秒到 1 秒，
            // 表现就是切图卡住、单击唤出操作条也慢半拍。顺带用序号丢弃过期结果。
            loadWatchImage(source, local, isContent);
        }
        if (!media) {
            // 图片：副标题显示尺寸与体积，底部铺缩略图条
            renderWatchThumbs();
            fillImageMeta(source, local);
        } else {
            updateVideoInlineMeta(null, source, local);
        }
        // 视频预览态只亮顶部标题（下方常驻「全屏播放」按钮）；
        // 全屏播放与音频照旧唤出完整控制条，图片走相册式 chrome
        if (type == TYPE_VIDEO && !playerFull) {
            setInlineBarVisible(true);
            setHeaderVisible(true);
            ui.removeCallbacks(hideOverlay);
            ui.postDelayed(hideOverlay, 3200);
        } else if (media) {
            setInlineBarVisible(false);
            showPlayerOverlay();
        } else {
            setInlineBarVisible(false);
            showImageChrome();
        }
    }

    // ---------------- 网络视频预览（ExoPlayer） ----------------

    /**
     * 为什么网络视频不再用系统 MediaPlayer / VideoView：
     * 下载走 yt-dlp 或 urllib，能处理 HLS 分片、重定向、独立音轨；
     * 系统播放器遇到 m3u8 相对分片（经本机代理后相对地址全失效）、
     * FLV / MKV、DASH 分离流时就会「能下载但播不了」。
     * ExoPlayer 原生支持这些容器，MergingMediaSource 还能把画面流和音轨合起来播。
     */
    private void startExoVideo(String source, JSONObject fmt) {
        exoSource = source;
        exoKind = previewKindOf(source, fmt);
        // 优先用本轮预览信息自带的音轨；解析结果里没有时才回退到全局音频列表。
        // （重新解析拿直链时，音频和画面必须来自同一次提取，时长才对得上）
        String ownAudio = fmt == null ? "" : fmt.optString("audioUrl", "");
        exoAudioUrl = !ownAudio.isEmpty()
                ? ownAudio
                : (formatNeedsAudio(fmt) ? bestAudioUrl() : "");
        exoTriedDropAudio = false;
        exoTriedRefresh = false;
        exoPrepared = false;
        if (playerInlineMeta != null) {
            playerInlineMeta.setText("正在加载…");
            playerInlineMeta.setVisibility(View.VISIBLE);
        }
        buildExoPlayer(source, exoKind, exoAudioUrl);
    }

    private void buildExoPlayer(String videoUrl, String kind, String audioUrl) {
        releaseExoPlayer();
        if (playerExo == null || videoUrl == null || videoUrl.isEmpty()) return;
        try {
            exoPlayer = new ExoPlayer.Builder(this).build();
            playerExo.setPlayer(exoPlayer);
            playerExo.setVisibility(View.VISIBLE);
            playerVideo.setVisibility(View.GONE);
            try {
                exoPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true);
            } catch (Throwable ignored) {
            }
            MediaSource video = buildMediaSource(
                    Uri.parse(videoUrl), kind, previewHeaders(videoUrl));
            MediaSource playSource = video;
            if (audioUrl != null && !audioUrl.isEmpty()) {
                MediaSource audio = buildMediaSource(
                        Uri.parse(audioUrl), previewKindOf(audioUrl, null),
                        previewHeaders(audioUrl));
                playSource = new MergingMediaSource(video, audio);
            }
            exoPrepared = false;
            exoPlayer.addListener(exoListener);
            exoPlayer.setMediaSource(playSource);
            exoPlayer.setPlayWhenReady(true);
            exoPlayer.setVolume(playVolume);
            try {
                exoPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
            } catch (Throwable ignored) {
            }
            exoPlayer.prepare();
            ui.removeCallbacks(exoLoadTimeout);
            ui.postDelayed(exoLoadTimeout, 18000);
        } catch (Throwable t) {
            Log.w("VSP", "ExoPlayer 启动失败", t);
            handleExoFailure("播放器初始化失败：" + shortMsg(new Exception(t)));
        }
    }

    private MediaSource buildMediaSource(Uri uri, String kind, Map<String, String> headers) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(DESKTOP_UA)
                .setDefaultRequestProperties(headers == null ? new HashMap<>() : headers)
                // 手动跟随 302，每一跳都重新带上 UA / Referer；
                // 这正是以前 MediaPlayer 跳 CDN 后丢 Referer、403 的病根
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000);
        DataSource.Factory ds = new DefaultDataSource.Factory(this, http);
        androidx.media3.common.MediaItem item =
                androidx.media3.common.MediaItem.fromUri(uri);
        if (KIND_HLS.equals(kind)) {
            return new HlsMediaSource.Factory(ds).createMediaSource(item);
        }
        if (KIND_DASH.equals(kind)) {
            return new DashMediaSource.Factory(ds).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(ds).createMediaSource(item);
    }

    private String previewKindOf(String url, JSONObject fmt) {
        String kind = fmt == null ? "" : fmt.optString("kind", "");
        if ("hls".equalsIgnoreCase(kind)) return KIND_HLS;
        if ("dash".equalsIgnoreCase(kind)) return KIND_DASH;
        String proto = fmt == null ? "" : fmt.optString("protocol", "");
        String ext = fmt == null ? "" : fmt.optString("ext", "");
        String quality = fmt == null ? "" : fmt.optString("quality", "");
        String low = (url == null ? "" : url) + " " + proto + " " + ext + " " + quality;
        low = low.toLowerCase(Locale.ROOT);
        String path = "";
        try {
            path = Uri.parse(url == null ? "" : url).getPath();
            if (path == null) path = "";
        } catch (Throwable ignored) {
        }
        path = path.toLowerCase(Locale.ROOT);
        if (path.endsWith(".m3u8") || low.contains(".m3u8") || low.contains("m3u8")
                || low.contains("hls")) {
            return KIND_HLS;
        }
        if (path.endsWith(".mpd") || low.contains(".mpd") || proto.toLowerCase(Locale.ROOT).contains("dash")) {
            return KIND_DASH;
        }
        return KIND_PROGRESSIVE;
    }

    /** 只有画面没有音轨的格式：预览时补一条音轨，和下载时的合并逻辑对齐 */
    private boolean formatNeedsAudio(JSONObject fmt) {
        if (fmt == null) return false;
        if (fmt.optBoolean("needsMerge", false)) return true;
        String ac = fmt.optString("acodec", "");
        return ac.isEmpty() || "none".equalsIgnoreCase(ac) || "null".equalsIgnoreCase(ac);
    }

    /** 解析结果里码率最高的音频轨（音频列表已在 Python 侧按 bitrate 排好） */
    private String bestAudioUrl() {
        if (parsedData == null) return "";
        JSONArray arr = parsedData.optJSONArray("audios");
        if (arr == null) return "";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            String u = a.optString("url", "");
            if (u.startsWith("http://") || u.startsWith("https://")) return u;
        }
        return "";
    }

    /** 预览请求头：和下载链路一致，桌面 UA + 原页面 Referer */
    private Map<String, String> previewHeaders(String mediaUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DESKTOP_UA);
        String ref = previewRefererFor(mediaUrl);
        if (ref != null && !ref.isEmpty()) headers.put("Referer", ref);
        headers.put("Accept", "*/*");
        return headers;
    }

    private void handleExoFailure(String note) {
        if (!exoActive) return;
        ui.removeCallbacks(exoLoadTimeout);
        ui.removeCallbacks(progressTick);
        Log.w("VSP", "网络视频预览失败：" + note);
        if (exoPlayer != null) {
            try {
                exoPlayer.stop();
            } catch (Throwable ignored) {
            }
        }
        // 1) 先去掉音轨：部分站点的音轨地址与画面流时长对不上，合并会直接失败
        if (exoAudioUrl != null && !exoAudioUrl.isEmpty() && !exoTriedDropAudio) {
            exoTriedDropAudio = true;
            exoAudioUrl = "";
            Toast.makeText(this, "音轨加载失败，先播放画面", Toast.LENGTH_SHORT).show();
            buildExoPlayer(exoSource, exoKind, "");
            return;
        }
        // 2) 再重新解析一次：签名直链可能已过期，或原格式是 DASH 单独一段
        if (!exoTriedRefresh) {
            exoTriedRefresh = true;
            refreshPreviewAndRetry(note);
            return;
        }
        // 3) 都失败，弹诊断
        showPreviewDiagnosis(exoSource, -1, 0, "播放器：" + note);
    }

    private void refreshPreviewAndRetry(final String note) {
        final String page = previewPageUrl == null || previewPageUrl.isEmpty()
                ? currentUrl : previewPageUrl;
        final String fid = previewFormatId == null ? "" : previewFormatId;
        if (!pyReady || page == null || page.isEmpty()) {
            showPreviewDiagnosis(exoSource, -1, 0,
                    "播放器：" + note + "\n重新解析：解析引擎未就绪");
            return;
        }
        if (playerInlineMeta != null) {
            playerInlineMeta.setText("正在重新获取直链…");
            playerInlineMeta.setVisibility(View.VISIBLE);
        }
        new Thread(() -> {
            String json = "";
            String err = "";
            try {
                json = py.getModule("vsp")
                        .callAttr("get_preview_info", page, fid, "", true)
                        .toString();
            } catch (Throwable t) {
                err = shortMsg(new Exception(t));
            }
            final String out = json == null ? "" : json;
            final String failure = err;
            ui.post(() -> {
                if (!exoActive) return;
                JSONObject o = null;
                try {
                    o = new JSONObject(out);
                } catch (Throwable ignored) {
                }
                String url = o == null ? "" : o.optString("url", "");
                String infoErr = o == null ? "" : o.optString("error", "");
                if (!url.isEmpty() && !url.equals(exoSource)) {
                    exoSource = url;
                    exoKind = o.optString("kind", previewKindOf(url, null));
                    exoAudioUrl = o.optString("audioUrl", "");
                    exoTriedDropAudio = false;
                    buildExoPlayer(exoSource, exoKind, exoAudioUrl);
                    return;
                }
                String why = note;
                if (!failure.isEmpty()) why += "；" + failure;
                if (!infoErr.isEmpty()) why += "；" + infoErr;
                showPreviewDiagnosis(exoSource, -1, 0, "播放器：" + why);
            });
        }, "vsp-preview-refresh").start();
    }

    private void releaseExoPlayer() {
        ui.removeCallbacks(exoLoadTimeout);
        ExoPlayer p = exoPlayer;
        exoPlayer = null;
        if (p != null) {
            try {
                p.removeListener(exoListener);
            } catch (Throwable ignored) {
            }
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (playerExo != null) {
            try {
                playerExo.setPlayer(null);
            } catch (Throwable ignored) {
            }
            playerExo.setVisibility(View.GONE);
        }
        exoPrepared = false;
    }

    /** 收起软键盘并把焦点从输入框上摘掉（否则进观看层时窗口会被键盘压缩） */
    private void dismissKeyboard() {
        try {
            View focus = getCurrentFocus();
            if (focus != null) focus.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 视频预览态底部那一行（时长 · 分辨率 · 体积）：只放媒体信息，不放路径 */
    private void setInlineBarVisible(boolean visible) {
        if (playerInlineBar == null) return;
        playerInlineBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateVideoInlineMeta(MediaPlayer mp, String source, boolean local) {
        if (playerInlineMeta == null) return;
        if (exoActive && !exoPrepared) {
            playerInlineMeta.setText("正在加载…");
            playerInlineMeta.setVisibility(View.VISIBLE);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int dur = 0, vw = 0, vh = 0;
        if (exoActive && exoPlayer != null) {
            try {
                long d = exoPlayer.getDuration();
                if (d != C.TIME_UNSET && d > 0) dur = (int) d;
            } catch (Throwable ignored) {
            }
            try {
                VideoSize vs = exoPlayer.getVideoSize();
                vw = vs.width;
                vh = vs.height;
            } catch (Throwable ignored) {
            }
        } else if (mp != null) {
            try {
                dur = mp.getDuration();
            } catch (Throwable ignored) {
            }
            try {
                vw = mp.getVideoWidth();
                vh = mp.getVideoHeight();
            } catch (Throwable ignored) {
            }
        }
        MediaItem m = currentWatchItem();
        if (dur <= 0 && m != null && m.duration > 0) dur = (int) m.duration;
        if (dur > 0) sb.append(fmtTime(dur));
        if (vw > 0 && vh > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(vw).append("×").append(vh);
        }
        long bytes = 0;
        if (m != null) bytes = m.size;
        if (bytes <= 0 && local && source != null && !source.startsWith("content://")) {
            try {
                bytes = new File(source).length();
            } catch (Throwable ignored) {
            }
        }
        if (bytes > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(humanSize(bytes));
        }
        playerInlineMeta.setText(sb.toString());
        playerInlineMeta.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** 预览态 → 全屏播放：转横屏、收掉按钮、展开完整控制条 */
    private void enterFullPlay() {
        if (playerType != TYPE_VIDEO) return;
        playerFull = true;
        setInlineBarVisible(false);
        try {
            setRequestedOrientation(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Throwable ignored) {
        }
        applyImmersive(true);
        showPlayerOverlay();
        showTopHint("已全屏 · 点画面唤出控制条，左上角退出");
    }

    /** 图片副标题：分辨率 · 体积（系统相册头部那两行信息） */
    private void fillImageMeta(String source, boolean local) {
        if (playerSub == null) return;
        playerSub.setText("");
        new Thread(() -> {
            String meta = "";
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                long bytes = 0;
                if (source != null && source.startsWith("content://")) {
                    InputStream in = getContentResolver().openInputStream(Uri.parse(source));
                    BitmapFactory.decodeStream(in, null, o);
                    if (in != null) in.close();
                    try (android.content.res.AssetFileDescriptor fd =
                                 getContentResolver().openAssetFileDescriptor(Uri.parse(source), "r")) {
                        if (fd != null) bytes = fd.getLength();
                    } catch (Throwable ignored) {
                    }
                } else if (local && source != null) {
                    File f = new File(source);
                    BitmapFactory.decodeFile(source, o);
                    bytes = f.length();
                }
                if (o.outWidth > 0 && o.outHeight > 0) {
                    meta = o.outWidth + " × " + o.outHeight;
                }
                if (bytes > 0) meta += (meta.isEmpty() ? "" : " · ") + humanSize(bytes);
            } catch (Throwable ignored) {
            }
            final String text = meta;
            ui.post(() -> {
                if (playerSub != null) playerSub.setText(text.isEmpty() ? "图片" : text);
            });
        }, "vsp-img-meta").start();
    }

    /** 底部缩略图条：同一批图片里点哪张就切哪张 */
    private void renderWatchThumbs() {
        if (playerThumbs == null || playerImgBar == null) return;
        playerThumbs.removeAllViews();
        if (watchingAvatar) {
            // 头像只有一张，底部整块收起；更换 / 恢复默认改由长按触发
            playerImgBar.setVisibility(View.GONE);
            playerThumbsScroll.setVisibility(View.GONE);
            if (playerImgActions != null) playerImgActions.setVisibility(View.GONE);
            return;
        }
        playerThumbsScroll.setVisibility(View.VISIBLE);
        if (playerImgActions != null) playerImgActions.setVisibility(View.VISIBLE);
        if (watchSiblings == null || watchSiblings.isEmpty()) {
            playerThumbsScroll.setVisibility(View.GONE);
            return;
        }
        int size = dp(46);
        int gap = dp(4);
        // 以前写死只渲染前 80 张，图片多的用户会觉得「缩略图不全」，这里铺满整批
        for (int i = 0; i < watchSiblings.size(); i++) {
            final int pos = i;
            MediaItem m = watchSiblings.get(i);
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = gap;
            cell.setLayoutParams(lp);
            if (i == watchIndex) cell.setBackgroundResource(R.drawable.thumb_ring);

            ImageView iv = new ImageView(this);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            if (i == watchIndex) ilp.setMargins(dp(2), dp(2), dp(2), dp(2));
            iv.setLayoutParams(ilp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF1A1A1A);
            loadMediaThumb(iv, m);
            cell.addView(iv);

            // 缩略图只叠时长，不显示文件名 / 路径这类文件信息；照片没有时长就不加
            if (m.duration > 0) {
                TextView d = new TextView(this);
                d.setText(fmtTime((int) m.duration));
                d.setTextSize(9);
                d.setTextColor(0xFFFFFFFF);
                d.setBackgroundColor(0x99000000);
                d.setPadding(dp(3), 0, dp(3), 0);
                FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                dlp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
                dlp.setMargins(0, 0, dp(2), dp(2));
                d.setLayoutParams(dlp);
                cell.addView(d);
            }

            cell.setOnClickListener(v -> {
                if (pos == watchIndex) return;
                switchWatchImage(pos);
            });
            playerThumbs.addView(cell);
        }
        if (watchIndex > 0) {
            final int idx = watchIndex;
            playerThumbsScroll.post(() -> {
                try {
                    View child = playerThumbs.getChildAt(Math.min(idx, playerThumbs.getChildCount() - 1));
                    if (child != null) {
                        playerThumbsScroll.smoothScrollTo(
                                Math.max(0, child.getLeft() - dp(30)), 0);
                    }
                } catch (Throwable ignored) {
                }
            });
        }
    }

    // ---------------- 观看层里的分享 / 信息 / 删除 / 更多 ----------------

    private MediaItem currentWatchItem() {
        if (watchSiblings == null || watchIndex < 0 || watchIndex >= watchSiblings.size()) return null;
        return watchSiblings.get(watchIndex);
    }

    private void shareCurrentMedia() {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            String src = watchSource == null ? "" : watchSource;
            if (src.startsWith("content://")) {
                send.setType(playerType == TYPE_VIDEO ? "video/*"
                        : playerType == TYPE_AUDIO ? "audio/*" : "image/*");
                send.putExtra(Intent.EXTRA_STREAM, Uri.parse(src));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (watchLocal && new File(src).exists()) {
                send.setType(playerType == TYPE_VIDEO ? "video/*"
                        : playerType == TYPE_AUDIO ? "audio/*" : "image/*");
                send.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(src)));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, watchTitle + "\n" + src);
            }
            startActivity(Intent.createChooser(send, "分享到"));
        } catch (Throwable t) {
            Toast.makeText(this, "分享失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showCurrentMediaInfo() {
        MediaItem m = currentWatchItem();
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(watchTitle == null ? "未命名" : watchTitle).append("\n");
        String kind = playerType == TYPE_VIDEO ? "视频" : playerType == TYPE_AUDIO ? "音频" : "图片";
        sb.append("类型：").append(kind).append("\n");
        if (m != null && m.size > 0) sb.append("大小：").append(humanSize(m.size)).append("\n");
        if (m != null && m.duration > 0) sb.append("时长：").append(fmtTime((int) m.duration)).append("\n");
        String src = watchSource == null ? "" : watchSource;
        sb.append(watchLocal || src.startsWith("content://") ? "位置：" : "链接：");
        sb.append(src.startsWith("content://") ? "手机媒体库" : src);
        if (playerSub != null && playerSub.getText().length() > 0
                && !playerSub.getText().toString().contains("×") == false) {
            sb.append("\n尺寸：").append(playerSub.getText());
        }
        new AlertDialog.Builder(this)
                .setTitle("信息")
                .setMessage(sb.toString())
                .setPositiveButton("好", null)
                .show();
    }

    private void deleteCurrentMedia() {
        MediaItem m = currentWatchItem();
        if (m == null || !(m.source.startsWith("content://") || new File(m.source).exists())) {
            Toast.makeText(this, "远程文件无法删除，请先下载到本地", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage("确定删除「" + m.name + "」？删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok;
                    try {
                        if (m.source.startsWith("content://")) {
                            ok = getContentResolver().delete(Uri.parse(m.source), null, null) > 0;
                        } else {
                            ok = new File(m.source).delete();
                        }
                    } catch (Throwable t) {
                        ok = false;
                    }
                    Toast.makeText(this, ok ? "已删除" : "删除失败（可能没有权限）",
                            Toast.LENGTH_SHORT).show();
                    if (ok) {
                        exitWatch();
                        localFiles.remove(m);
                        videoFiles.remove(m);
                        saveScanCache(localFiles);
                        renderLocal();
                    }
                })
                .show();
    }

    private void showWatchMoreMenu() {
        if (watchingAvatar) {
            new AlertDialog.Builder(this)
                    .setTitle("头像")
                    .setItems(new CharSequence[]{"更换头像", "恢复默认头像"}, (d, which) -> {
                        exitWatch();
                        if (which == 0) pickAvatar();
                        else clearAvatar();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        CharSequence[] items = {"用其他应用打开", "重新加载", "退出观看"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        try {
                            MediaItem m = currentWatchItem();
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            String src = watchSource == null ? "" : watchSource;
                            if (src.startsWith("content://")) {
                                i.setDataAndType(Uri.parse(src), m != null && m.type == TYPE_VIDEO
                                        ? "video/*" : m != null && m.type == TYPE_AUDIO ? "audio/*" : "image/*");
                            } else {
                                i.setDataAndType(Uri.fromFile(new File(src)), "*/*");
                            }
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(i);
                        } catch (Throwable t) {
                            Toast.makeText(this, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        String src = watchSource;
                        int type = playerType;
                        String title = watchTitle;
                        boolean local = watchLocal;
                        List<MediaItem> sib = watchSiblings;
                        int idx = watchIndex;
                        openPlayer(src, type, title, local, sib, idx);
                    } else {
                        exitWatch();
                    }
                })
                .show();
    }

    // ---------------- 图片缩放 ----------------

    private void setupImageZoom() {
        imageScaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        setImageScale(imageScale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                        return true;
                    }
                });
        imageGestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        // 单击画面：唤出 / 收起顶部标题与底部缩略图条
                        toggleImageChrome();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        // 长按画面：更多（头像在这里改头像 / 恢复默认）
                        showWatchMoreMenu();
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        float base = imageBaseScale();
                        if (imageScale > base + 0.02f) resetImageZoom();
                        else setImageScale(base * 2.6f, e.getX(), e.getY());
                        return true;
                    }

                    @Override
                    public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (imageScale > imageBaseScale() + 0.02f) {
                            imageTx -= dx;
                            imageTy -= dy;
                            clampImageTranslation();
                            applyImageMatrix();
                        }
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        // 翻页已经不在这里做了：左右滑动改成跟手拖动（见 handleWatchTouch），
                        // 两处都处理会一次划动翻两页
                        return false;
                    }
                });
        playerImage.setOnTouchListener((v, ev) -> {
            if (playerType != TYPE_IMAGE) return false;
            if (imageScaleDetector != null) imageScaleDetector.onTouchEvent(ev);
            if (imageGestureDetector != null) imageGestureDetector.onTouchEvent(ev);
            return handleWatchTouch(ev);
        });
    }

    // ---------------- 图片左右滑动（跟手） ----------------

    /**
     * 跟手翻页：手指往哪拖，当前图就往哪走，下一张（或上一张）从另一侧跟进来，
     * 松手按位移决定落定还是回弹。以前是 fling 一甩就瞬间换图，看不到过程。
     *
     * @return true 表示本手势已被处理
     */
    private boolean handleWatchTouch(MotionEvent ev) {
        boolean zoomed = imageScale > imageBaseScale() + 0.02f;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 上一次落定的 190ms 动画还没跑完就又按下：立刻结账。
                // 否则两次 commitSwipe 会前后脚改 watchIndex，索引错位后位图跟着错，直接崩。
                if (swipePending != 0) settleSwipeNow();
                swipeDx = 0f;
                swipeLocked = false;
                swipeDirection = 0;
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (zoomed || watchSiblings == null || watchSiblings.size() < 2) return true;
                float dx = ev.getX() - swipeDownX;
                float dy = ev.getY() - swipeDownY;
                if (!swipeLocked) {
                    if (Math.abs(dx) < dp(10)) return true;
                    // 竖向为主的滑动不算翻页，避免和下拉/上滑冲突
                    if (Math.abs(dx) < Math.abs(dy) * 1.2f) return true;
                    swipeLocked = true;
                    swipeDirection = dx < 0 ? 1 : -1;
                    beginSwipe(swipeDirection);
                }
                swipeDx = dx;
                applySwipeOffset(dx);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (swipeLocked) {
                    float w = Math.max(1f, playerImage.getWidth());
                    boolean atEdge = !canStep(swipeDirection);
                    boolean commit = !atEdge && Math.abs(swipeDx) > w * 0.24f;
                    // 拖到尽头给一句提示，不然只有回弹会让人以为卡住了
                    if (atEdge && Math.abs(swipeDx) > w * 0.12f) hintEdge(swipeDirection < 0);
                    finishSwipe(commit);
                    swipeLocked = false;
                    swipeDx = 0f;
                }
                return true;
            default:
                return true;
        }
    }

    private boolean canStep(int dir) {
        if (watchSiblings == null || watchIndex < 0) return false;
        int next = watchIndex + dir;
        return next >= 0 && next < watchSiblings.size();
    }

    /** 相邻图的那一层：加在主图下面，拖动时两张图并排出现 */
    private ImageView ensureSwipeView() {
        if (watchSwipeImage != null) return watchSwipeImage;
        if (playerImage == null || !(playerImage.getParent() instanceof ViewGroup)) return null;
        ViewGroup parent = (ViewGroup) playerImage.getParent();
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(Color.BLACK);
        iv.setVisibility(View.GONE);
        iv.setClickable(false);
        iv.setFocusable(false);
        int idx = parent.indexOfChild(playerImage);
        parent.addView(iv, idx < 0 ? 0 : idx, iv.getLayoutParams());
        watchSwipeImage = iv;
        return iv;
    }

    /** 开始拖动：把相邻图放到对应一侧并开始解码 */
    private void beginSwipe(int dir) {
        ImageView edge = ensureSwipeView();
        if (edge == null) return;
        int target = watchIndex + dir;
        float w = Math.max(1f, playerImage.getWidth());
        // 先无条件断开上一张的引用。上一张的位图可能已经在落定后被主图接管并 recycle 了，
        // 这时候只要 View 还拿着它、还被显示出来，绘制线程就会抛
        // "Cannot draw a recycled bitmap" —— 这是本次闪退的直接原因。
        edge.setImageDrawable(null);
        edge.setTranslationX(dir > 0 ? w : -w);
        if (target < 0 || target >= watchSiblings.size()) {
            // 到头了：这一侧没有图，只让主图跟着走一点做反馈
            edge.setVisibility(View.VISIBLE);
            watchSwipeIndex = -1;
            return;
        }
        if (watchSwipeIndex == target && watchSwipeBitmap != null
                && !watchSwipeBitmap.isRecycled()) {
            // 上一轮回弹时解出来的还留着，直接贴上去，省一次解码
            edge.setImageBitmap(watchSwipeBitmap);
            edge.setVisibility(View.VISIBLE);
            return;
        }
        // 还没解码：先别显形（黑底闪一下也是「闪烁」），等位图回来自动滑进来
        edge.setVisibility(View.INVISIBLE);
        loadSwipeImage(target);
    }

    /** 后台解码相邻图（1600px 够拖动时看清内容，比主图的 2400 更快） */
    private void loadSwipeImage(final int pos) {
        final int token = ++watchSwipeToken;
        watchSwipeIndex = pos;
        MediaItem m = watchSiblings.get(pos);
        final String src = m.source;
        final boolean isContent = src != null && src.startsWith("content://");
        swipePool.execute(() -> {
            Bitmap b;
            if (isContent) b = decodeContentImage(src, 1600);
            else b = decodeSampled(new File(src), 1600);
            final Bitmap got = b;
            ui.post(() -> {
                if (token != watchSwipeToken || watchSwipeImage == null) {
                    if (got != null) recycleQuiet(got);
                    return;
                }
                Bitmap old = watchSwipeBitmap;
                watchSwipeBitmap = got;
                if (got != null && !got.isRecycled()) {
                    watchSwipeImage.setImageBitmap(got);
                    // 手指还按着才显形：松手了就别再让一张图突然冒出来
                    if (swipeLocked && swipeDirection != 0) {
                        watchSwipeImage.setVisibility(View.VISIBLE);
                        applySwipeOffset(swipeDx);
                    }
                }
                if (old != null && old != got) recycleQuiet(old);
            });
        });
    }

    /** 拖动中：主图跟着手指，相邻图贴着它一起走 */
    private void applySwipeOffset(float dx) {
        float w = Math.max(1f, playerImage.getWidth());
        // 这里不再看 watchSwipeImage 是否 VISIBLE：解码还没回来时那一层是 INVISIBLE 的，
        // 但主图必须照样跟手，否则前几十毫秒手指是拖不动的。
        if (!canStep(swipeDirection)) {
            // 边界外没有图：加阻力，拖不远，松手自动弹回
            playerImage.setTranslationX(dx * 0.32f);
            if (watchSwipeImage != null) {
                watchSwipeImage.setTranslationX(swipeDirection > 0 ? w : -w);
            }
            return;
        }
        playerImage.setTranslationX(dx);
        if (watchSwipeImage != null) {
            watchSwipeImage.setTranslationX((swipeDirection > 0 ? w : -w) + dx);
        }
    }

    private void finishSwipe(boolean commit) {
        float w = Math.max(1f, playerImage.getWidth());
        boolean hasEdge = watchSwipeImage != null && watchSwipeImage.getVisibility() == View.VISIBLE;
        playerImage.animate().cancel();
        if (watchSwipeImage != null) watchSwipeImage.animate().cancel();
        if (!commit) {
            swipePending = 0;
            playerImage.animate().translationX(0f).setDuration(160).start();
            if (hasEdge) {
                watchSwipeImage.animate().translationX(swipeDirection > 0 ? w : -w)
                        .setDuration(160)
                        .withEndAction(this::hideSwipeView)
                        .start();
            } else {
                hideSwipeView();
            }
            return;
        }
        swipePending = swipeDirection;
        playerImage.animate().translationX(-swipeDirection * w).setDuration(190)
                .withEndAction(this::commitSwipe)
                .start();
        if (hasEdge) {
            watchSwipeImage.animate().translationX(0f).setDuration(190).start();
        }
    }

    /** 动画还没结束就又按下了：别等 endAction，立刻把待落定的那一步做完 */
    private void settleSwipeNow() {
        if (swipePending == 0) return;
        playerImage.animate().cancel();
        if (watchSwipeImage != null) watchSwipeImage.animate().cancel();
        if (swipeDirection == 0) swipeDirection = swipePending;
        commitSwipe();
    }

    /** 落定：把相邻图的内容挪到主图，再切索引，视觉上不会闪一下黑 */
    private void commitSwipe() {
        swipePending = 0;
        int dir = swipeDirection;
        int target = watchIndex + dir;
        if (watchSiblings == null || target < 0 || target >= watchSiblings.size()) {
            playerImage.animate().cancel();
            playerImage.setTranslationX(0f);
            hideSwipeView();
            return;
        }
        Bitmap edge = watchSwipeBitmap;
        watchSwipeBitmap = null;
        playerImage.animate().cancel();
        if (edge != null && !edge.isRecycled()) {
            // 顺序很要紧：先换图、再同步重算矩阵、最后才把相邻层藏掉。
            // 原来是先藏再换，藏起来到换完之间那一帧主图还是上一张，
            // 肉眼看到的就是一次「闪回旧图」。
            Bitmap old = watchImageBitmap;
            boolean oldRecyclable = watchImageRecyclable;
            watchImageBitmap = edge;
            watchImageRecyclable = true;
            playerImage.setImageBitmap(edge);
            applyImageFit();
            if (old != null && old != edge) {
                if (oldRecyclable) recycleQuiet(old);
            }
        }
        playerImage.setTranslationX(0f);
        hideSwipeView();
        swipeDirection = 0;
        switchWatchImage(target);
    }

    private void hideSwipeView() {
        watchSwipeToken++;   // 在途解码的结果直接作废
        if (watchSwipeImage == null) return;
        watchSwipeImage.animate().cancel();
        // 关键：必须把 ImageView 上的位图引用断掉。相邻图落定后会被主图接管，
        // 主图换原尺寸时又会把这张位图 recycle；如果 View 还拿着它，
        // 下次一显示就是 "Cannot draw a recycled bitmap"，直接闪退。
        watchSwipeImage.setImageDrawable(null);
        watchSwipeImage.setVisibility(View.GONE);
        watchSwipeImage.setTranslationX(0f);
    }

    /** 丢掉相邻图与位图（退出观看、切到视频时调用） */
    private void releaseSwipeImage() {
        hideSwipeView();
        if (watchSwipeBitmap != null) {
            recycleQuiet(watchSwipeBitmap);
            watchSwipeBitmap = null;
        }
        watchSwipeIndex = -1;
        swipeLocked = false;
        swipeDirection = 0;
        swipePending = 0;
    }

    /** 回收位图，任何异常都吞掉：宁可漏回收一点内存，也不能因为一次 recycle 把界面搞崩 */
    private void recycleQuiet(Bitmap b) {
        if (b == null) return;
        try {
            if (!b.isRecycled()) b.recycle();
        } catch (Throwable ignored) {
        }
    }

    /** 左右滑动到边界时给一句提示（拖到尽头只回弹，不静默） */
    private void hintEdge(boolean first) {
        showTopHint(first ? "已经是第一张" : "已经是最后一张");
    }

    /**
     * 在同一批媒体里切到第 pos 项。
     *
     * 图片只是换一张图，没必要把整个观看层推倒重建：以前这一步走的是 openPlayer，
     * 每次都要重新排版缩略图条（几十上百个 ImageView，每个还各开一个解码线程），
     * 所以切图慢、缓存暴涨，连划几张还会因为内存打满闪退。现在只换主图 + 高亮。
     */
    private void switchWatchImage(int pos) {
        if (watchSiblings == null || pos < 0 || pos >= watchSiblings.size()) return;
        MediaItem m = watchSiblings.get(pos);
        int type = m.type >= 0 ? m.type : guessType(m.name);
        if (type != TYPE_IMAGE) {
            // 视频 / 音频要换播放器与整块控制条，仍然走完整打开
            openPlayer(m.source, type, m.name, true, watchSiblings, pos);
            return;
        }
        watchIndex = pos;
        watchSource = m.source;
        watchTitle = m.name;
        watchLocal = true;
        playerTitle.setText(m.name == null || m.name.trim().isEmpty() ? "未命名" : m.name.trim());
        playerKind.setText("图片");
        updateThumbSelection();
        fillImageMeta(m.source, true);
        loadWatchImage(m.source, true, m.source != null && m.source.startsWith("content://"), true);
        resetImageZoom();
    }

    /** 只更新缩略图条的高亮与滚动位置，不重建整条 */
    private void updateThumbSelection() {
        if (playerThumbs == null || watchSiblings == null) return;
        int n = Math.min(playerThumbs.getChildCount(), watchSiblings.size());
        for (int i = 0; i < n; i++) {
            View child = playerThumbs.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup cell = (ViewGroup) child;
            View inner = cell.getChildAt(0);
            boolean on = i == watchIndex;
            if (on) cell.setBackgroundResource(R.drawable.thumb_ring);
            else cell.setBackgroundColor(0x00000000);
            if (inner != null) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                if (on) lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                inner.setLayoutParams(lp);
            }
        }
        if (watchIndex >= 0 && watchIndex < playerThumbs.getChildCount()
                && playerThumbsScroll != null) {
            View target = playerThumbs.getChildAt(watchIndex);
            if (target != null) {
                playerThumbsScroll.smoothScrollTo(Math.max(0, target.getLeft() - dp(60)), 0);
            }
        }
    }

    /** 铺满可用区域且不裁切的基准缩放 */
    private float imageBaseScale() {
        Drawable d = playerImage.getDrawable();
        int vw = playerImage.getWidth();
        int vh = playerImage.getHeight();
        if (d == null || vw <= 0 || vh <= 0) return 1f;
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return 1f;
        return Math.min(vw / dw, vh / dh);
    }

    private void applyImageMatrix() {
        imageMatrix.reset();
        imageMatrix.setScale(imageScale, imageScale);
        imageMatrix.postTranslate(imageTx, imageTy);
        playerImage.setImageMatrix(imageMatrix);
    }

    /** 放大后限制平移范围，避免把图拖出屏幕 */
    private void clampImageTranslation() {
        Drawable d = playerImage.getDrawable();
        if (d == null) return;
        float vw = playerImage.getWidth();
        float vh = playerImage.getHeight();
        float w = d.getIntrinsicWidth() * imageScale;
        float h = d.getIntrinsicHeight() * imageScale;
        if (w <= vw) imageTx = (vw - w) / 2f;
        else imageTx = Math.max(vw - w, Math.min(0f, imageTx));
        if (h <= vh) imageTy = (vh - h) / 2f;
        else imageTy = Math.max(vh - h, Math.min(0f, imageTy));
    }

    /** 以某个点为中心缩放，范围 1x ~ 5x 基准 */
    private void setImageScale(float target, float focusX, float focusY) {
        float base = imageBaseScale();
        if (base <= 0f) return;
        float t = Math.max(base, Math.min(base * 5f, target));
        if (imageScale <= 0f) imageScale = base;
        float k = t / imageScale;
        imageTx = focusX - (focusX - imageTx) * k;
        imageTy = focusY - (focusY - imageTy) * k;
        imageScale = t;
        clampImageTranslation();
        applyImageMatrix();
    }

    private void applyImageFit() {
        if (playerImage.getWidth() <= 0 || playerImage.getHeight() <= 0) {
            // 还没量过尺寸，等下一帧再算；直接算会把 scale 定成 1，图像先放大再缩回去，很晃
            playerImage.post(this::applyImageFit);
            return;
        }
        float base = imageBaseScale();
        imageScale = base;
        imageTx = 0f;
        imageTy = 0f;
        clampImageTranslation();
        applyImageMatrix();
    }

    private void resetImageZoom() {
        imageScale = 1f;
        imageTx = 0f;
        imageTy = 0f;
        if (playerImage.getWidth() > 0) applyImageFit();
        else playerImage.post(this::applyImageFit);
    }

    /** 图片模式：顶部标题与底部缩略图条一起显示，3.5 秒后自动收起，点画面可再次唤出 */
    private final Runnable hideImageChrome = () -> hideImageChrome();

    private void showPlayerOverlay() {
        if (playerOverlay == null) return;
        if (playerType == TYPE_IMAGE) {
            showImageChrome();
            return;
        }
        playerOverlay.setVisibility(View.VISIBLE);
        setHeaderVisible(true);
        // 控制条展开时让出下方的「全屏播放」那一行，避免两块叠在一起
        setInlineBarVisible(false);
        ui.removeCallbacks(hideOverlay);
        ui.postDelayed(hideOverlay, 3000);
    }

    private void hidePlayerOverlay() {
        ui.removeCallbacks(hideOverlay);
        if (playerOverlay != null) playerOverlay.setVisibility(View.GONE);
        if (playerVolumePop != null) playerVolumePop.setVisibility(View.GONE);
        setHeaderVisible(false);
        // 预览态收起控制条后，把全屏播放按钮放回来
        setInlineBarVisible(playerType == TYPE_VIDEO && !playerFull);
    }

    /** 顶部返回键 + 文件名：淡入淡出，收起后不占点击区域 */
    private void setHeaderVisible(boolean visible) {
        if (playerHeader == null) return;
        headerShown = visible;
        playerHeader.animate().cancel();
        if (visible) {
            playerHeader.setVisibility(View.VISIBLE);
            playerHeader.animate().alpha(1f).setDuration(160).start();
        } else {
            playerHeader.animate().alpha(0f).setDuration(160).withEndAction(() -> {
                if (!headerShown && playerHeader != null) playerHeader.setVisibility(View.GONE);
            }).start();
        }
    }

    private void showImageChrome() {
        if (playerType != TYPE_IMAGE) return;
        setHeaderVisible(true);
        if (playerImgBar != null && !watchingAvatar) playerImgBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideImageChrome);
        ui.postDelayed(hideImageChrome, 3500);
    }

    private void hideImageChrome() {
        if (playerType != TYPE_IMAGE) return;
        ui.removeCallbacks(hideImageChrome);
        setHeaderVisible(false);
        if (playerImgBar != null) playerImgBar.setVisibility(View.GONE);
    }

    /** 点图片：切换「看干净的图」与「带标题 / 缩略图条」两种状态 */
    private void toggleImageChrome() {
        if (headerShown) hideImageChrome();
        else showImageChrome();
    }

    /** 横屏观看 / 竖屏观看切换（视频与图片都能用） */
    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean video = playerType == TYPE_VIDEO;
        // 转回竖屏即退出「全屏播放」，全屏播放按钮跟着回来
        if (video) playerFull = !landscape;
        try {
            setRequestedOrientation(landscape
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Throwable ignored) {
        }
        setInlineBarVisible(video && landscape);
        showTopHint(landscape ? "竖屏观看" : "横屏观看");
    }

    /**
     * 观看层的安全区。手机顶部的状态栏（时间、电量）、底部手势条、刘海都会盖住内容，
     * 顶部返回键与底部控制行必须按实际 inset 让开，否则按钮会贴到状态栏上。
     */
    private void setupWatchInsets() {
        if (playerPanel == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(playerPanel, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            watchInsetTop = Math.max(bars.top, cut.top);
            watchInsetBottom = Math.max(bars.bottom, cut.bottom);
            watchInsetLeft = Math.max(bars.left, cut.left);
            watchInsetRight = Math.max(bars.right, cut.right);
            applyWatchPadding();
            return insets;
        });
        ViewCompat.requestApplyInsets(playerPanel);
    }

    private void applyWatchPadding() {
        int headerTop = Math.max(dp(10), watchInsetTop + dp(6));
        int bottomGap = Math.max(dp(10), watchInsetBottom + dp(6));
        if (playerHeader != null) {
            playerHeader.setPadding(dp(4) + watchInsetLeft, headerTop,
                    dp(16) + watchInsetRight, dp(10));
        }
        if (playerBottom != null) {
            playerBottom.setPadding(0, dp(26), 0, bottomGap);
        }
        if (playerSeekWrap != null) {
            playerSeekWrap.setPadding(dp(14) + watchInsetLeft, 0, dp(14) + watchInsetRight, 0);
        }
        if (playerCtrlRow != null) {
            playerCtrlRow.setPadding(dp(10) + watchInsetLeft, dp(4), dp(10) + watchInsetRight, dp(8));
        }
        if (playerThumbsScroll != null) {
            playerThumbsScroll.setPadding(dp(10) + watchInsetLeft, 0, dp(10) + watchInsetRight, 0);
        }
        if (playerImgActions != null) {
            playerImgActions.setPadding(watchInsetLeft, dp(8), watchInsetRight, bottomGap + dp(6));
        }
        // 视频预览态的「全屏播放」按钮：贴在底部手势条上方
        if (playerInlineBar != null) {
            playerInlineBar.setPadding(0, dp(10), 0, bottomGap + dp(18));
        }
        // 音量浮层要抬到控制行上方，别被手势条盖住
        if (playerVolumePop != null) {
            ViewGroup.LayoutParams lp = playerVolumePop.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mp = (ViewGroup.MarginLayoutParams) lp;
                mp.bottomMargin = dp(104) + watchInsetBottom;
                playerVolumePop.setLayoutParams(mp);
            }
        }
    }

    // ---------------- 观看 / 退出 ----------------

    /** 退出观看：停播、还原方向与系统栏、收起观看层 */
    private void exitWatch() {
        if (playerPanel == null) return;
        try {
            stopPlayer();
            hidePlayerOverlay();
            fullscreen = false;
            try {
                setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            } catch (Throwable ignored) {
            }
            applyImmersive(false);
            if (fullscreenHost != null) {
                fullscreenHost.removeAllViews();
                fullscreenHost.setVisibility(View.GONE);
            }
            playerPanel.setVisibility(View.GONE);
            playerPanel.setBackgroundColor(Color.BLACK);
            playerType = TYPE_IMAGE;
            watchingAvatar = false;
            watchSiblings = null;
            watchIndex = -1;
            playerFull = false;
            setInlineBarVisible(false);
            if (playerVolumePop != null) playerVolumePop.setVisibility(View.GONE);
            // 退出时把主图位图交还给系统：一张 2400px 的图占十几 MB，留着纯属浪费
            releaseWatchImage();
            resetImageZoom();
        } catch (Throwable t) {
            Log.w("VSP", "退出观看失败", t);
        }
    }

    private int dpHeight(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void applyImmersive(boolean on) {
        View decor = getWindow().getDecorView();
        // targetSdk 36 上 setSystemUiVisibility 已失效，必须走 WindowInsetsController，
        // 否则状态栏不会隐藏，顶部返回键会压在时间上
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), !on);
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), decor);
            if (c != null) {
                if (on) {
                    c.hide(WindowInsetsCompat.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsetsCompat.Type.systemBars());
                }
            }
        } catch (Throwable ignored) {
        }
        if (on) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            applyStatusBarIcons();
        }
        // 系统栏隐藏 / 恢复后 inset 会变，重新排一次顶部与底部的安全边距
        if (playerPanel != null) ViewCompat.requestApplyInsets(playerPanel);
    }

    private void prepareAudio(String source, boolean local) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            if (source != null && source.startsWith("content://")) {
                // SAF / MediaStore 的音频，用 content Uri 交给系统解析
                mp.setDataSource(this, Uri.parse(source));
            } else if (local) {
                mp.setDataSource(source);
            } else {
                String playSrc = playUrlFor(source, false, false);
                if (playSrc != null && playSrc.contains("127.0.0.1")) {
                    mp.setDataSource(playSrc);
                } else {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("User-Agent", DESKTOP_UA);
                    String ref = previewRefererFor(source);
                    if (ref != null) headers.put("Referer", ref);
                    mp.setDataSource(this, Uri.parse(source), headers);
                }
            }
            mp.setOnPreparedListener(m -> {
                audioPrepared = true;
                audioPlaying = true;
                currentMp = m;
                applyPlaybackSettings(m);
                m.start();
                playerAudioToggle.setImageResource(R.drawable.ic_pause);
                playerAudioSeek.setMax(m.getDuration());
                playerDuration.setText(fmtTime(m.getDuration()));
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
            });
            mp.setOnCompletionListener(m -> {
                audioPlaying = false;
                playerAudioToggle.setImageResource(R.drawable.ic_resume);
                ui.removeCallbacks(progressTick);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (local) {
                    Toast.makeText(this, "无法播放该音频", Toast.LENGTH_SHORT).show();
                } else {
                    showPreviewDiagnosis(source, what, extra, null);
                }
                return true;
            });
            audioPlayer = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "音频加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 播放 / 暂停：视频与音频都作用于同一个 MediaPlayer 引用。 */
    private void togglePlay() {
        if (playerType == TYPE_VIDEO && exoActive && exoPlayer != null) {
            try {
                if (exoPlayer.isPlaying()) {
                    exoPlayer.pause();
                    audioPlaying = false;
                    playerAudioToggle.setImageResource(R.drawable.ic_resume);
                    ui.removeCallbacks(progressTick);
                } else {
                    if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
                        exoPlayer.seekTo(0);
                    }
                    exoPlayer.play();
                    audioPlaying = true;
                    applyPlaybackSettings(null);
                    playerAudioToggle.setImageResource(R.drawable.ic_pause);
                    ui.removeCallbacks(progressTick);
                    ui.post(progressTick);
                }
            } catch (Throwable t) {
                Toast.makeText(this, "播放控制失败", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        MediaPlayer mp = currentMp;
        if (mp == null) {
            Toast.makeText(this, "尚未开始播放", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                audioPlaying = false;
                playerAudioToggle.setImageResource(R.drawable.ic_resume);
                ui.removeCallbacks(progressTick);
            } else {
                mp.start();
                audioPlaying = true;
                applyPlaybackSettings(mp);
                playerAudioToggle.setImageResource(R.drawable.ic_pause);
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
            }
        } catch (Exception e) {
            Toast.makeText(this, "播放控制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /** 音量作用于播放器自身，不影响系统媒体音量；倍速从 API 23 起支持。 */
    /**
     * 播放 / 请求该地址时站点要求的 Referer。
     * B 站 CDN 最挑：缺 Referer 或给成 CDN 自己的域名都会 403，播放器就只剩黑屏。
     */
    /**
     * 网络流统一走本地转发代理：App 只连 127.0.0.1，由 Python 带齐 UA / Referer 去拉真实地址，
     * 再把状态码、类型、长度和 Range 原样转发回来。
     *
     * 这么改的理由：下载走的就是 Python 拉流，下载能成；预览却交给 MediaPlayer 直连，
     * 一遇到 302 跳 CDN 就丢 Referer、或者干脆不报错一直缓冲。让预览走和下载同一条路，
     * 只要下载能成，预览就能成。
     */
    private String playUrlFor(String source, boolean local, boolean isContent) {
        if (source == null || local || isContent) return source;
        if (!source.startsWith("http")) return source;
        if (source.contains("127.0.0.1")) return source;
        if (!pyReady) return source;
        try {
            String ref = previewRefererFor(source);
            String got = py.getModule("vsp")
                    .callAttr("preview_proxy_url", source, ref == null ? "" : ref)
                    .toString();
            if (got != null && got.startsWith("http://127.0.0.1")) return got;
        } catch (Throwable t) {
            Log.w("VSP", "起预览代理失败，退回直链", t);
        }
        return source;
    }

    /**
     * 预览失败时把能查的东西全摊出来：播放器错误码、目标站返回的状态码 / 类型 / 长度、
     * 文件头对应的容器格式、代理地址。一键复制就能贴出来，不用再靠猜。
     */
    private void showPreviewDiagnosis(String source, int what, int extra, String note) {
        final String src = source == null ? "" : source;
        new Thread(() -> {
            String probe = "";
            if (pyReady) {
                try {
                    String ref = previewRefererFor(src);
                    probe = py.getModule("vsp")
                            .callAttr("preview_probe", src, ref == null ? "" : ref)
                            .toString();
                } catch (Throwable ignored) {
                }
            }
            final String json = probe == null ? "" : probe;
            ui.post(() -> {
                StringBuilder sb = new StringBuilder();
                if (what >= 0) {
                    sb.append("播放器：what=").append(what).append(" extra=").append(extra)
                            .append("（").append(mediaErrorHint(extra)).append("）\n");
                }
                if (note != null && !note.isEmpty()) sb.append(note).append('\n');
                JSONObject o = null;
                try {
                    o = new JSONObject(json);
                } catch (Throwable ignored) {
                }
                if (o == null) {
                    sb.append("拉流探测：不可用").append(json.isEmpty() ? "" : "（" + json + "）");
                } else {
                    String host = o.optString("host", "");
                    sb.append("目标：").append(host.isEmpty() ? "（无法解析）" : host).append('\n');
                    sb.append("HTTP：").append(o.optInt("status", 0));
                    String err = o.optString("error", "");
                    if (!err.isEmpty()) sb.append("（").append(err).append("）");
                    sb.append('\n');
                    String type = o.optString("type", "");
                    if (!type.isEmpty()) sb.append("类型：").append(type).append('\n');
                    long len = o.optLong("len", 0);
                    if (len > 0) sb.append("大小：").append(humanSize(len)).append('\n');
                    String magic = o.optString("magic", "");
                    sb.append("文件头：").append(magic.isEmpty() ? "（空）" : magic)
                            .append(" → ").append(guessContainer(magic)).append('\n');
                    String proxy = o.optString("proxy", "");
                    sb.append("代理：").append(proxy.isEmpty() ? "未启动" : "已就绪");
                }
                final String text = sb.toString();
                new AlertDialog.Builder(this)
                        .setTitle("预览失败诊断")
                        .setMessage(text)
                        .setPositiveButton("复制", (d, w) -> {
                            try {
                                android.content.ClipboardManager cm =
                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("vsp", text));
                                Toast.makeText(this, "已复制，可以直接发出来", Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                        })
                        .setNegativeButton("关闭", null)
                        .show();
            });
        }, "vsp-preview-probe").start();
    }

    /** MediaPlayer 的 extra 码翻成人话 */
    private String mediaErrorHint(int extra) {
        switch (extra) {
            case -1004:
                return "IO 错误，多半是请求被拒或连接被断开";
            case -1005:
                return "数据格式错误";
            case -1010:
                return "编码 / 容器不支持";
            case -110:
                return "超时";
            case -2147483648:
                return "未知";
            default:
                return extra == 0 ? "无附加信息" : "参考 MediaPlayer 错误码";
        }
    }

    /** 文件头前 4~16 字节 → 容器类型，用来判断是不是播放器支持不了的格式 */
    private String guessContainer(String hexMagic) {
        if (hexMagic == null || hexMagic.length() < 8) return "没读到数据";
        String h = hexMagic.toLowerCase();
        if (h.contains("66747970")) return "MP4 / MOV（ftyp）";
        if (h.startsWith("1a45dfa3")) return "WebM / MKV";
        if (h.startsWith("234558544d33")) return "HLS 分片播放列表（m3u8）";
        if (h.startsWith("ffd8ff")) return "JPEG 图片";
        if (h.startsWith("89504e47")) return "PNG 图片";
        if (h.startsWith("474946")) return "GIF";
        if (h.startsWith("3026b275")) return "ASF / WMV";
        if (h.startsWith("52494646")) return "RIFF（AVI / WAV）";
        if (h.startsWith("3c6874") || h.startsWith("3c2144")) return "HTML 页面（多半是拦截页或链接过期）";
        return "未识别的容器";
    }

    /** 已知站点返回固定 Referer；普通 CDN 返回 null（不拿 CDN 域名冒充站点） */
    private String siteRefererFor(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return null;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.contains("bilibili") || h.contains("bilivideo") || h.contains("b23.tv")
                    || h.contains("hdslb"))
                return "https://www.bilibili.com/";
            if (h.contains("douyin") || h.contains("douyinvod") || h.contains("zjcdn")
                    || h.contains("snssdk") || h.contains("amemv"))
                return "https://www.douyin.com/";
            if (h.contains("kuaishou") || h.contains("gifshow") || h.contains("kwaicdn"))
                return "https://www.kuaishou.com/";
            if (h.contains("xiaohongshu") || h.contains("xhscdn"))
                return "https://www.xiaohongshu.com/";
            if (h.contains("weibo") || h.contains("sinaimg") || h.contains("wbcdn"))
                return "https://weibo.com/";
            if (h.contains("zhihu") || h.contains("zhimg"))
                return "https://www.zhihu.com/";
            if (h.contains("ixigua") || h.contains("ixiguavideo"))
                return "https://www.ixigua.com/";
            if (h.contains("gtimg") || h.contains("qq.com"))
                return "https://v.qq.com/";
            if (h.contains("youku") || h.contains("ykimg"))
                return "https://v.youku.com/";
            if (h.contains("acfun") || h.contains("aixifan"))
                return "https://www.acfun.cn/";
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String refererFor(String url) {
        String site = siteRefererFor(url);
        if (site != null) return site;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return null;
            int port = u.getPort();
            return u.getScheme() + "://" + host + (port > 0 ? ":" + port : "") + "/";
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 预览 / 下载共用的 Referer。
     * 普通站点的直链常落在另一个 CDN 域名上，CDN 会校验「原页面」Referer；
     * 以前拿直链域名拼 Referer，下载（按原页面取）能成、预览却 403。
     */
    private String previewRefererFor(String mediaUrl) {
        String site = siteRefererFor(mediaUrl);
        if (site != null) return site;
        if (previewPageUrl != null && previewPageUrl.startsWith("http")) return previewPageUrl;
        return refererFor(mediaUrl);
    }

    private void applyPlaybackSettings(MediaPlayer mp) {
        float speed = SPEEDS[speedIndex];
        if (exoActive && exoPlayer != null) {
            try {
                exoPlayer.setVolume(playVolume);
            } catch (Throwable ignored) {
            }
            try {
                exoPlayer.setPlaybackParameters(new PlaybackParameters(speed));
            } catch (Throwable ignored) {
            }
            return;
        }
        if (mp == null) return;
        try {
            mp.setVolume(playVolume, playVolume);
        } catch (Throwable ignored) {
        }
        // 1.0x 时不要碰 PlaybackParams：部分机型只要调过一次
        // 就会把音频输出切走，表现正好是「有画面、没声音」。
        if (Build.VERSION.SDK_INT >= 23 && Math.abs(speed - 1f) > 0.001f) {
            applySpeed(mp);
        }
    }

    /** 设置播放倍速；重设后补一次音量，避免被系统重置成 0 */
    private void applySpeed(MediaPlayer mp) {
        if (mp == null || Build.VERSION.SDK_INT < 23) return;
        try {
            android.media.PlaybackParams params = new android.media.PlaybackParams();
            params.setSpeed(SPEEDS[speedIndex]);
            mp.setPlaybackParams(params);
            mp.setVolume(playVolume, playVolume);
        } catch (Throwable ignored) {
        }
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        btnSpeed.setText(speedLabel());
        if (exoActive && exoPlayer != null) {
            try {
                exoPlayer.setPlaybackParameters(new PlaybackParameters(SPEEDS[speedIndex]));
            } catch (Throwable ignored) {
            }
        } else {
            MediaPlayer mp = currentMp;
            if (mp != null) {
                try {
                    // 切回 1.0x 也要设一次，把之前调过的倍速恢复掉
                    applySpeed(mp);
                } catch (Throwable ignored) {
                }
            }
        }
        Toast.makeText(this, "倍速 " + speedLabel(), Toast.LENGTH_SHORT).show();
    }

    private String speedLabel() {
        float s = SPEEDS[speedIndex];
        String v = (s == (long) s)
                ? String.format(Locale.getDefault(), "%.1f", s)
                : String.format(Locale.getDefault(), "%.2f", s);
        return v + "x";
    }

    private void stopPlayer() {
        releaseExoPlayer();
        exoActive = false;
        exoSource = "";
        exoAudioUrl = "";
        exoTriedDropAudio = false;
        exoTriedRefresh = false;
        try {
            if (playerVideo != null) playerVideo.stopPlayback();
        } catch (Throwable ignored) {
        }
        if (audioPlayer != null) {
            try {
                audioPlayer.reset();
            } catch (Throwable ignored) {
            }
            try {
                audioPlayer.release();
            } catch (Throwable ignored) {
            }
            audioPlayer = null;
        }
        currentMp = null;
        ui.removeCallbacks(progressTick);
        audioPrepared = false;
        audioPlaying = false;
        if (playerAudioToggle != null) playerAudioToggle.setImageResource(R.drawable.ic_resume);
        if (playerTime != null) playerTime.setText("00:00");
        if (playerDuration != null) playerDuration.setText("00:00");
        if (playerAudioSeek != null) playerAudioSeek.setProgress(0);
    }

    private String fmtTime(int ms) {
        int s = Math.max(0, ms) / 1000;
        int h = s / 3600;
        if (h > 0) return String.format(Locale.getDefault(), "%d:%02d:%02d", h, (s % 3600) / 60, s % 60);
        return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60);
    }

    // ---------------- 本地扫描 ----------------

    private String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean hasMediaPermission() {
        for (String p : mediaPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void startScan() {
        if (scanning) return;
        if (!hasMediaPermission()) {
            scanStatus.setText("需要读取媒体权限");
            scanCount.setText("点击下方「重新扫描」授权后即可扫描手机里的视频、音频和图片");
            requestPermissions(mediaPermissions(), REQ_MEDIA_PERM);
            return;
        }
        scanning = true;
        scanProgress.setVisibility(View.VISIBLE);
        scanBtnProgress.setVisibility(View.VISIBLE);
        btnScanStart.setText("");
        btnScanStart.setEnabled(false);
        scanStatus.setText("正在扫描…");
        scanCount.setText("正在读取媒体库，请稍候");

        final String mode = prefs.getString("scan_mode", "media");
        final String tree = prefs.getString("scan_tree", "");
        final String path = prefs.getString("scan_path", "");
        final boolean wantVideo = prefs.getBoolean("filter_video", true);
        final boolean wantAudio = prefs.getBoolean("filter_audio", true);
        final boolean wantImage = prefs.getBoolean("filter_image", true);
        // 设置项：只保留本次结果，还是与上次结果合并
        final boolean replaceOld = prefs.getBoolean("scan_replace", true);
        final List<MediaItem> base = replaceOld
                ? new ArrayList<>() : new ArrayList<>(localFiles);

        new Thread(() -> {
            List<MediaItem> found = new ArrayList<>(base);
            Set<String> seen = new HashSet<>();
            for (MediaItem m : found) seen.add(m.source);
            boolean failed = false;
            try {
                if ("tree".equals(mode) && !tree.isEmpty()) {
                    Uri rootUri = Uri.parse(tree);
                    collectFromTree(rootUri, DocumentsContract.getTreeDocumentId(rootUri),
                            found, seen, 0, wantVideo, wantAudio, wantImage);
                } else if ("path".equals(mode) && !path.isEmpty()) {
                    collectMedia(new File(path), found, seen, 0, wantVideo, wantAudio, wantImage);
                } else {
                    collectMediaStore(found, wantVideo, wantAudio, wantImage);
                }
            } catch (Throwable t) {
                Log.w("VSP", "扫描失败", t);
                failed = true;
            }
            Collections.sort(found, (a, b) -> Long.compare(b.time, a.time));
            if (found.size() > SCAN_MAX) found = new ArrayList<>(found.subList(0, SCAN_MAX));
            final List<MediaItem> result = found;
            final boolean err = failed;
            ui.post(() -> {
                scanning = false;
                localFiles = result;
                saveScanCache(result);
                scanProgress.setVisibility(View.GONE);
                scanBtnProgress.setVisibility(View.GONE);
                btnScanStart.setText("重新扫描");
                btnScanStart.setEnabled(true);
                renderLocal();
                renderVideos();
                if (err) {
                    scanStatus.setText("扫描失败");
                    scanCount.setText("读取媒体库出错，可以改用「自定义扫描」指定文件夹");
                    Toast.makeText(this, "扫描失败，请改用自定义扫描", Toast.LENGTH_LONG).show();
                } else {
                    scanStatus.setText("扫描完成");
                    scanCount.setText("共找到 " + result.size() + " 个文件 · 视频 "
                            + countType(result, TYPE_VIDEO) + " / 音频 "
                            + countType(result, TYPE_AUDIO) + " / 图片 "
                            + countType(result, TYPE_IMAGE));
                    Toast.makeText(this, "扫描完成，共 " + result.size() + " 个文件",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "vsp-scan").start();
    }

    private int countType(List<MediaItem> list, int type) {
        int n = 0;
        for (MediaItem m : list) if (m.type == type) n++;
        return n;
    }

    // ---------------- 扫描结果 / 解析结果持久化 ----------------

    private File scanCacheFile() {
        return new File(getFilesDir(), "scan_cache.json");
    }

    private File lastParseFile() {
        return new File(getFilesDir(), "last_parse.json");
    }

    /** 写入临时文件再改名，避免写一半被杀导致缓存损坏 */
    private boolean writeFileAtomic(File target, String content) {
        try {
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                fo.write(content.getBytes("UTF-8"));
                fo.flush();
            } finally {
                fo.close();
            }
            if (target.exists() && !target.delete()) return false;
            return tmp.renameTo(target);
        } catch (Throwable t) {
            Log.w("VSP", "写缓存失败 " + target.getName(), t);
            return false;
        }
    }

    private String readFileText(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                return readStream(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private void saveScanCache(List<MediaItem> list) {
        if (list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (MediaItem m : list) {
                JSONObject o = new JSONObject();
                o.put("s", m.source);
                o.put("n", m.name);
                o.put("z", m.size);
                o.put("t", m.time);
                o.put("y", m.type);
                o.put("i", m.id);
                o.put("d", m.duration);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("ts", System.currentTimeMillis());
            root.put("items", arr);
            final String json = root.toString();
            new Thread(() -> writeFileAtomic(scanCacheFile(), json), "vsp-save-scan").start();
        } catch (Throwable t) {
            Log.w("VSP", "序列化扫描结果失败", t);
        }
    }

    private void loadScanCache() {
        try {
            File f = scanCacheFile();
            if (!f.exists()) return;
            String json = readFileText(f);
            if (json.isEmpty()) return;
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return;
            List<MediaItem> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String src = o.optString("s", "");
                if (src.isEmpty()) continue;
                list.add(new MediaItem(src, o.optString("n", "未知文件"),
                        o.optLong("z", 0), o.optLong("t", 0), o.optInt("y", TYPE_VIDEO),
                        o.optLong("i", -1), o.optLong("d", 0)));
            }
            if (list.isEmpty()) return;
            localFiles = list;
            renderLocal();
            renderVideos();
            long ts = root.optLong("ts", 0);
            if (scanStatus != null) scanStatus.setText("已恢复上次扫描结果");
            if (scanCount != null) {
                scanCount.setText("共 " + list.size() + " 个文件 · 视频 "
                        + countType(list, TYPE_VIDEO) + " / 音频 "
                        + countType(list, TYPE_AUDIO) + " / 图片 "
                        + countType(list, TYPE_IMAGE)
                        + (ts > 0 ? " · " + relativeTime(ts) : ""));
            }
            if (btnScanStart != null) btnScanStart.setText("重新扫描");
        } catch (Throwable t) {
            Log.w("VSP", "恢复扫描结果失败", t);
        }
    }

    private String relativeTime(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < 60_000L) return "刚刚扫描";
        if (d < 3_600_000L) return (d / 60_000L) + " 分钟前扫描";
        if (d < 86_400_000L) return (d / 3_600_000L) + " 小时前扫描";
        return (d / 86_400_000L) + " 天前扫描";
    }

    private void saveLastParse(String url, JSONObject data) {
        if (data == null) return;
        try {
            JSONObject root = new JSONObject();
            root.put("url", url == null ? "" : url);
            root.put("tab", currentTab);
            root.put("ts", System.currentTimeMillis());
            root.put("data", data);
            final String json = root.toString();
            new Thread(() -> writeFileAtomic(lastParseFile(), json), "vsp-save-parse").start();
        } catch (Throwable t) {
            Log.w("VSP", "序列化解析结果失败", t);
        }
    }

    private void loadLastParse() {
        try {
            File f = lastParseFile();
            if (!f.exists()) return;
            String json = readFileText(f);
            if (json.isEmpty()) return;
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return;
            parsedData = data;
            currentUrl = root.optString("url", "");
            currentThumb = data.optString("thumbnail", "");
            String tab = root.optString("tab", "videos");
            if (!tab.isEmpty()) currentTab = tab;
            if (!currentUrl.isEmpty() && urlInput != null) urlInput.setText(currentUrl);
            resultMeta.setVisibility(View.VISIBLE);
            tabs.setVisibility(View.VISIBLE);
            formatHint.setVisibility(View.VISIBLE);
            resultTitle.setText(data.optString("title", ""));
            resultUploader.setText(data.optString("uploader", ""));
            loadThumb(resultThumb, currentThumb);
            refreshFormatTabColors();
            showFormatsFromCache();
        } catch (Throwable t) {
            Log.w("VSP", "恢复解析结果失败", t);
        }
    }

    /** 扫描手机媒体库：视频 / 音频 / 图片，按修改时间倒序 */
    private void collectMediaStore(List<MediaItem> out, boolean video, boolean audio, boolean image) {
        if (video) queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, TYPE_VIDEO, out, true);
        if (audio) queryMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, TYPE_AUDIO, out, true);
        if (image) queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, TYPE_IMAGE, out, false);
    }

    private void queryMedia(Uri base, int type, List<MediaItem> out, boolean hasDuration) {
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.MediaColumns._ID);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        cols.add(MediaStore.MediaColumns.SIZE);
        cols.add(MediaStore.MediaColumns.DATE_MODIFIED);
        if (hasDuration) cols.add(MediaStore.MediaColumns.DURATION);
        Cursor c = null;
        try {
            c = getContentResolver().query(base, cols.toArray(new String[0]),
                    null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c == null) return;
            while (c.moveToNext() && out.size() < SCAN_MAX) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.isNull(2) ? 0 : c.getLong(2);
                long sec = c.isNull(3) ? 0 : c.getLong(3);
                long dur = 0;
                if (hasDuration && c.getColumnCount() > 4 && !c.isNull(4)) dur = c.getLong(4);
                if (name == null) name = "未知文件";
                Uri item = ContentUris.withAppendedId(base, id);
                out.add(new MediaItem(item.toString(), name, size, sec * 1000, type, id, dur));
            }
        } catch (Throwable t) {
            Log.w("VSP", "扫描媒体库失败 " + base, t);
        } finally {
            if (c != null) c.close();
        }
    }

    /** 通过 SAF 目录树递归遍历，无需额外存储权限；Android 11+ 也能扫到真实目录 */
    private void collectFromTree(Uri tree, String parentId, List<MediaItem> out,
                                 Set<String> seen, int depth,
                                 boolean wantVideo, boolean wantAudio, boolean wantImage) {
        if (depth > 3 || out.size() >= SCAN_MAX) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            }, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                long time = c.isNull(4) ? 0 : c.getLong(4);
                if (id == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectFromTree(tree, id, out, seen, depth + 1, wantVideo, wantAudio, wantImage);
                } else if (name != null && out.size() < SCAN_MAX) {
                    int t = guessType(name);
                    if ((t == TYPE_VIDEO && !wantVideo)
                            || (t == TYPE_AUDIO && !wantAudio)
                            || (t == TYPE_IMAGE && !wantImage)) continue;
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    if (seen.add(doc.toString())) {
                        out.add(new MediaItem(doc.toString(), name, size, time, t, -1, 0));
                    }
                }
            }
        } finally {
            if (c != null) c.close();
        }
    }

    private void collectMedia(File dir, List<MediaItem> out, Set<String> seen, int depth,
                              boolean wantVideo, boolean wantAudio, boolean wantImage) {
        if (dir == null || !dir.isDirectory() || depth > 3 || out.size() >= SCAN_MAX) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= SCAN_MAX) break;
            if (f.isDirectory()) {
                collectMedia(f, out, seen, depth + 1, wantVideo, wantAudio, wantImage);
                continue;
            }
            String n = f.getName();
            int t = guessType(n);
            if (t < 0) continue;
            if ((t == TYPE_VIDEO && !wantVideo) || (t == TYPE_AUDIO && !wantAudio)
                    || (t == TYPE_IMAGE && !wantImage)) continue;
            if (seen.add(f.getAbsolutePath())) {
                out.add(new MediaItem(f.getAbsolutePath(), n, f.length(), f.lastModified(), t, -1, 0));
            }
        }
    }

    private void renderLocal() {
        if (localFiles.isEmpty()) {
            localEmpty.setVisibility(View.VISIBLE);
            localSummary.setText("还没有扫描结果 · 右上角 ⋮ 可以扫描");
        } else {
            localEmpty.setVisibility(View.GONE);
            localSummary.setText("共 " + localFiles.size() + " 个 · 视频 "
                    + countType(localFiles, TYPE_VIDEO) + " / 音频 "
                    + countType(localFiles, TYPE_AUDIO) + " / 图片 "
                    + countType(localFiles, TYPE_IMAGE));
        }
        localGrid.setAdapter(new MediaAdapter(localFiles, false));
    }

    private void openVideosPage() {
        if (localFiles.isEmpty()) {
            Toast.makeText(this, "还没有扫描结果，先扫描一次", Toast.LENGTH_SHORT).show();
            switchPage(PAGE_SCAN);
            return;
        }
        switchPage(PAGE_VIDEOS);
    }

    private void renderVideos() {
        List<MediaItem> videos = new ArrayList<>();
        for (MediaItem m : localFiles) if (m.type == TYPE_VIDEO) videos.add(m);
        videoFiles = videos;
        videoEmpty.setVisibility(videos.isEmpty() ? View.VISIBLE : View.GONE);
        videoGrid.setAdapter(new MediaAdapter(videos, true));
    }

    private void playLocal(MediaItem m) {
        int type = m.type >= 0 ? m.type : guessType(m.name);
        // 同一批里点谁就打开谁，图片模式下底部会铺出这批的缩略图
        List<MediaItem> scope = localFiles.contains(m) ? localFiles : videoFiles;
        int idx = scope.indexOf(m);
        openPlayer(m.source, type, m.name, true, scope, idx);
    }

    /** 提取配乐：把视频里的音轨原样导出成 m4a，放在下载目录 */
    private void extractAudioFrom(MediaItem m) {
        final File dir = mediaOutputDir();
        if (dir == null) {
            Toast.makeText(this, "下载目录不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String base = m.name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        final File out = new File(dir, base + " 配乐.m4a");
        if (out.exists()) out.delete();
        Toast.makeText(this, "正在提取配乐…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = MediaUtil.extractAudio(this, m.source, out.getAbsolutePath());
            ui.post(() -> {
                if (ok) {
                    Toast.makeText(this, "已保存到 " + out.getName(), Toast.LENGTH_LONG).show();
                    openPlayer(out.getAbsolutePath(), TYPE_AUDIO, out.getName(), true);
                    refreshStorage();
                } else {
                    Toast.makeText(this, "提取失败：该视频没有可用的音轨", Toast.LENGTH_LONG).show();
                }
            });
        }, "vsp-extract-audio").start();
    }

    private File mediaOutputDir() {
        File dir = null;
        if (!downloadDir.isEmpty()) dir = new File(downloadDir);
        if (dir == null || !dir.isDirectory()) {
            File ext = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            dir = ext != null ? new File(ext, "VSP解析") : getFilesDir();
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void showScanDirPicker() {
        String[] items = {"扫描手机全部媒体（默认）", "应用下载目录", "系统下载目录",
                "自定义文件夹", "手动输入路径"};
        new AlertDialog.Builder(this)
                .setTitle("自定义扫描")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            prefs.edit().putString("scan_mode", "media")
                                    .putString("scan_label", "手机全部媒体").apply();
                            promptScanManual();
                            break;
                        case 1:
                            prefs.edit().putString("scan_mode", "path")
                                    .putString("scan_path", downloadDir)
                                    .putString("scan_label", "应用下载目录").apply();
                            promptScanManual();
                            break;
                        case 2:
                            prefs.edit().putString("scan_mode", "public")
                                    .putString("scan_label", "系统下载目录").apply();
                            promptScanManual();
                            break;
                        case 3:
                            pickScanTree();
                            break;
                        default:
                            showScanPathInput();
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickScanTree() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_SCAN_TREE);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件夹选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void showScanPathInput() {
        final EditText et = new EditText(this);
        et.setHint("/storage/emulated/0/Download");
        et.setText(prefs.getString("scan_path", ""));
        et.setTextColor(P.text);
        et.setHintTextColor(P.dim);
        et.setBackgroundColor(P.input);
        et.setPadding(dp(16), dp(10), dp(16), dp(10));
        new AlertDialog.Builder(this)
                .setTitle("输入扫描目录的绝对路径")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit()
                            .putString("scan_mode", "path")
                            .putString("scan_path", p)
                            .putString("scan_label", new File(p).getName())
                            .apply();
                    promptScanManual();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showScanFilter() {
        final boolean[] sel = {
                prefs.getBoolean("filter_video", true),
                prefs.getBoolean("filter_audio", true),
                prefs.getBoolean("filter_image", true)
        };
        final boolean[] replace = {prefs.getBoolean("scan_replace", true)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(2));

        final CheckBox[] cbs = new CheckBox[3];
        String[] names = {"视频", "音频", "图片"};
        for (int i = 0; i < 3; i++) {
            cbs[i] = new CheckBox(this);
            cbs[i].setText(names[i]);
            cbs[i].setChecked(sel[i]);
            cbs[i].setTextColor(P.text);
            cbs[i].setTextSize(14);
            cbs[i].setPadding(0, dp(5), 0, dp(5));
            box.addView(cbs[i]);
        }

        View div = new View(this);
        div.setBackgroundColor(P.divider);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.topMargin = dp(8);
        dlp.bottomMargin = dp(8);
        box.addView(div, dlp);

        final CheckBox onlyNew = new CheckBox(this);
        onlyNew.setText("每次扫描只保留本次结果");
        onlyNew.setChecked(replace[0]);
        onlyNew.setTextColor(P.text);
        onlyNew.setTextSize(14);
        onlyNew.setPadding(0, dp(4), 0, dp(4));
        box.addView(onlyNew);

        TextView hint = new TextView(this);
        hint.setText("勾选：扫描前清空上次结果；不勾：与上次结果合并保留。");
        hint.setTextColor(P.dim);
        hint.setTextSize(11);
        hint.setPadding(0, 0, 0, dp(6));
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("扫描过滤设置")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    if (!cbs[0].isChecked() && !cbs[1].isChecked() && !cbs[2].isChecked()) {
                        Toast.makeText(this, "至少要保留一种类型", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit()
                            .putBoolean("filter_video", cbs[0].isChecked())
                            .putBoolean("filter_audio", cbs[1].isChecked())
                            .putBoolean("filter_image", cbs[2].isChecked())
                            .putBoolean("scan_replace", onlyNew.isChecked())
                            .apply();
                    promptScanManual();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 扫描范围 / 过滤条件改动后只更新提示：扫描必须由用户点「开始扫描」触发 */
    private void promptScanManual() {
        updateScanScope();
        if (scanStatus != null) scanStatus.setText("扫描范围已更新");
        if (scanCount != null) {
            scanCount.setText(localFiles.isEmpty()
                    ? "点下方「开始扫描」开始，不会自动执行"
                    : "现有 " + localFiles.size() + " 个文件，可点「重新扫描」刷新");
        }
        if (btnScanStart != null) {
            btnScanStart.setText(localFiles.isEmpty() ? "开始扫描" : "重新扫描");
            btnScanStart.setEnabled(true);
        }
    }

    private void updateScanScope() {
        if (scanScope == null) return;
        String where = prefs.getString("scan_label", "手机全部媒体");
        StringBuilder types = new StringBuilder();
        if (prefs.getBoolean("filter_video", true)) types.append("视频 ");
        if (prefs.getBoolean("filter_audio", true)) types.append("音频 ");
        if (prefs.getBoolean("filter_image", true)) types.append("图片 ");
        if (types.length() == 0) types.append("未选择");
        scanScope.setText("扫描范围：" + where + " · 类型：" + types.toString().trim());
    }

    // ---------------- 存储空间 ----------------

    private List<File> listDownloadFiles() {
        List<File> out = new ArrayList<>();
        String dir = downloadDir;
        if (dir == null || dir.isEmpty()) {
            File ext = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (ext != null) dir = new File(ext, "VSP解析").getAbsolutePath();
        }
        if (dir == null || dir.isEmpty()) return out;
        File d = new File(dir);
        File[] fs = d.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && !f.getName().startsWith(".")) out.add(f);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.length(), a.length()));
        return out;
    }

    private void refreshStorage() {
        new Thread(() -> {
            final List<File> files = listDownloadFiles();
            long total = 0;
            for (File f : files) total += f.length();
            final long size = total;
            // 缓存 = 内部缓存目录（缩略图）+ 外部缓存目录，之前只算前者所以永远接近 0
            long cacheBytes = dirSize(getCacheDir());
            File extCache = getExternalCacheDir();
            if (extCache != null) cacheBytes += dirSize(extCache);
            final long cache = cacheBytes;
            ui.post(() -> {
                storageFiles = files;
                stCount.setText(files.size() + " 个");
                stSize.setText(humanSize(size));
                stCache.setText(humanSize(cache));
                if (currentPage == PAGE_FILES) renderFilesPage(files);
            });
        }, "vsp-storage").start();
    }

    private void refreshFilesPage() {
        refreshStorage();
        renderFilesPage(storageFiles);
    }

    private void renderFilesPage(List<File> files) {
        downloadFiles = files;
        filesTotal.setText(files.size() + " 个");
        filesEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        filesList.setAdapter(new FileAdapter(files));
    }

    private long dirSize(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long s = 0;
        for (File f : fs) s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }

    private void deleteChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) deleteChildren(f);
            try {
                f.delete();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 清空操作期间按钮内转圈并禁用，与解析按钮一致的反馈。 */
    private void setStorageBusy(boolean cache, boolean busy) {
        TextView btn = cache ? btnClearCache : btnClearAll;
        ProgressBar bar = cache ? clearCacheProgress : clearAllProgress;
        if (btn != null) {
            btn.setEnabled(!busy);
            btn.setText(busy ? "" : (cache ? "清空缓存" : "全部清空"));
        }
        if (bar != null) bar.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void clearCache() {
        setStorageBusy(true, true);
        new Thread(() -> {
            deleteChildren(getCacheDir());
            File ext = getExternalCacheDir();
            if (ext != null) deleteChildren(ext);
            File thumbs = new File(getCacheDir(), "thumbs");
            if (thumbs.exists()) deleteChildren(thumbs);
            synchronized (thumbCache) {
                thumbCache.clear();
            }
            ui.post(() -> {
                setStorageBusy(true, false);
                Toast.makeText(this, "缓存已清空", Toast.LENGTH_SHORT).show();
                refreshStorage();
                if (currentPage == PAGE_LOCAL) renderLocal();
                if (currentPage == PAGE_VIDEOS) renderVideos();
            });
        }).start();
    }

    private void clearAllDownloads() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部下载")
                .setMessage("将删除下载目录中的所有文件，此操作不可恢复。")
                .setPositiveButton("清空", (d, w) -> {
                    setStorageBusy(false, true);
                    new Thread(() -> {
                        int n = 0;
                        for (File f : listDownloadFiles()) {
                            if (f.delete()) n++;
                        }
                        final int count = n;
                        ui.post(() -> {
                            setStorageBusy(false, false);
                            Toast.makeText(this, "已删除 " + count + " 个文件", Toast.LENGTH_SHORT).show();
                            refreshStorage();
                            if (currentPage == PAGE_FILES) renderFilesPage(storageFiles);
                        });
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteStorageFile(File f) {
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage(f.getName())
                .setPositiveButton("删除", (d, w) -> {
                    boolean ok = f.delete();
                    Toast.makeText(this, ok ? "已删除" : "删除失败", Toast.LENGTH_SHORT).show();
                    refreshStorage();
                    if (currentPage == PAGE_FILES) renderFilesPage(storageFiles);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String humanSize(long n) {
        if (n <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        double v = n;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i]);
    }

    private String versionName() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return getPackageManager().getPackageInfo(getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).versionName;
            }
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    // ---------------- 检查更新（GitHub） ----------------

    /** 一次检查的结果：来源 / 版本 / 说明 / 时间 / 安装包地址与大小 */
    private static class UpdateInfo {
        String from = "";        // "github" / "gitee"
        String version = "";
        String notes = "";
        String date = "";
        String apkUrl = "";
        long size = 0L;
    }

    private void checkUpdate() {
        cleanupStaleUpdateState();

        // 已有下载在进行 / 上次中断但安装包还在：直接接回原来的进度，绝不重新下载
        if (UpdateService.running || UpdateStore.hasResumable(this)) {
            resumePendingDownload(true);
            showTopHint(UpdateService.running ? "正在更新中，请等当前下载结束" : "正在继续上次的下载");
            return;
        }
        // 已经下完但还没安装：继续提供安装入口，不重新检查 / 下载
        if (UpdateStore.hasCompleted(this)) {
            showUpdateProgress();
            return;
        }

        if (checkingUpdate) return;
        checkingUpdate = true;
        latestApkUrl = "";
        latestVersion = "";
        latestNotes = "";
        latestDate = "";
        latestSize = 0L;
        repoEmpty = false;
        updateProgress.setVisibility(View.VISIBLE);
        updateVersion.setText("检查中…");
        new Thread(() -> {
            UpdateInfo gh = null, ge = null;
            String ghErr = null, geErr = null;
            try {
                gh = fromGitHub(fetchLatestRelease());
            } catch (Exception e) {
                ghErr = shortMsg(e);
            }
            try {
                ge = fromGitee(fetchGiteeRelease());
                if (ge == null || ge.version.isEmpty()) {
                    ge = fromGiteeIndex(fetchGiteeIndex());
                }
            } catch (Exception e) {
                try {
                    ge = fromGiteeIndex(fetchGiteeIndex());
                } catch (Exception e2) {
                    geErr = shortMsg(e2);
                }
            }
            // 两个源版本相同时 pickNewer 取第一个参数，因此把 Gitee 放在前面：
            // Gitee 实测约 1.5 MB/s，GitHub 只有 270 KB/s 左右，同版本优先走快的源。
            // 若某天 GitHub 版本更高，pickNewer 仍会正确地选中它。
            UpdateInfo best = pickNewer(ge, gh);
            // Gitee 的 release 附件接口不返回 size，同版本时借 GitHub 的大小把「版本 · 大小」补全
            if (best != null && best.size <= 0 && gh != null
                    && best.version.equals(gh.version) && gh.size > 0) {
                best.size = gh.size;
            }
            final String err;
            if (best != null && !best.version.isEmpty()) {
                latestVersion = best.version;
                latestNotes = best.notes;
                latestDate = best.date;
                latestApkUrl = best.apkUrl;
                latestSize = best.size;
                latestSource = best.from;
                err = null;
            } else {
                repoEmpty = true;
                if (ghErr != null && geErr != null) {
                    err = "两个更新源都访问不到（GitHub：" + ghErr + "；Gitee：" + geErr + "）";
                } else {
                    err = "GitHub 与 Gitee 上都还没有发布任何版本，暂时没有可更新的安装包。";
                }
            }
            ui.post(() -> {
                checkingUpdate = false;
                updateProgress.setVisibility(View.GONE);
                updateVersion.setText("v" + versionName());
                if (err != null) showUpdateError(err);
                else showUpdateResult();
            });
        }, "vsp-update-check").start();
    }

    /** 两个源都拿到时取版本更高的那个；只有一个时用那一个 */
    private UpdateInfo pickNewer(UpdateInfo a, UpdateInfo b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.version.isEmpty()) return b;
        if (b.version.isEmpty()) return a;
        return compareVersion(b.version, a.version) > 0 ? b : a;
    }

    private String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private UpdateInfo fromGitHub(JSONObject rel) {
        if (rel == null) return null;
        UpdateInfo info = new UpdateInfo();
        info.from = "github";
        info.version = rel.optString("tag_name", "").trim().replaceFirst("^[vV]", "");
        info.notes = rel.optString("body", "").trim();
        info.date = rel.optString("published_at", "").trim();
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                if (a.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    info.apkUrl = a.optString("browser_download_url", "");
                    info.size = a.optLong("size", 0L);
                    break;
                }
            }
        }
        if (info.apkUrl.isEmpty() && !info.version.isEmpty()) {
            info.apkUrl = "https://github.com/" + UPDATE_REPO
                    + "/releases/download/v" + info.version + "/VSP-Parse-v" + info.version + ".apk";
        }
        return info;
    }

    private UpdateInfo fromGitee(JSONObject rel) {
        if (rel == null) return null;
        UpdateInfo info = new UpdateInfo();
        info.from = "gitee";
        info.version = rel.optString("tag_name", "").trim().replaceFirst("^[vV]", "");
        info.notes = rel.optString("body", "").trim();
        info.date = rel.optString("created_at", "").trim();
        if (info.date.isEmpty()) info.date = rel.optString("published_at", "").trim();
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                if (a.optString("name", "").toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    info.apkUrl = a.optString("browser_download_url", "");
                    info.size = a.optLong("size", 0L);
                    break;
                }
            }
        }
        if (info.apkUrl.isEmpty() && !info.version.isEmpty()) {
            info.apkUrl = "https://gitee.com/" + GITEE_REPO
                    + "/releases/download/v" + info.version + "/VSP-Parse-v" + info.version + ".apk";
        }
        return info;
    }

    /** Gitee 仓库里的版本索引：{version, notes, date, apk, size} */
    private JSONObject fetchGiteeIndex() throws Exception {
        String body = fetchText(GITEE_INDEX, 3);
        if (!body.startsWith("{")) throw new Exception("索引内容异常");
        return new JSONObject(body);
    }

    /** 取文本，自己跟随 302（Gitee 的 raw 会跳到带签名的 CDN 地址） */
    private String fetchText(String url, int hops) throws Exception {
        String cur = url;
        for (int i = 0; i < Math.max(1, hops); i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) throw new Exception("跳转缺少地址");
                    cur = loc.startsWith("http") ? loc : new URL(new URL(cur), loc).toString();
                    continue;
                }
                if (code != 200) throw new Exception("返回 " + code);
                return readStream(c.getInputStream()).trim();
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("跳转次数过多");
    }

    private UpdateInfo fromGiteeIndex(JSONObject idx) {
        if (idx == null) return null;
        String ver = idx.optString("version", "").trim().replaceFirst("^[vV]", "");
        if (ver.isEmpty()) return null;
        UpdateInfo info = new UpdateInfo();
        info.from = "gitee";
        info.version = ver;
        info.notes = idx.optString("notes", "").trim();
        info.date = idx.optString("date", "").trim();
        String apk = idx.optString("apk", "").trim();
        if (apk.startsWith("http://") || apk.startsWith("https://")) {
            info.apkUrl = apk;
        } else {
            // 安装包不进源码仓（apk/dist/* 被忽略），索引里的 apk 只是文件名参考；
            // 能下的是 release 附件，约定路径与 fromGitee 的兜底一致
            info.apkUrl = "https://gitee.com/" + GITEE_REPO
                    + "/releases/download/v" + ver + "/VSP-Parse-v" + ver + ".apk";
        }
        info.size = idx.optLong("size", 0L);
        return info;
    }

    private JSONObject fetchLatestRelease() throws Exception {
        return getJson(UPDATE_API, "application/vnd.github+json");
    }

    /** Gitee 的 OpenAPI 公开仓库匿名可读，没有 token 也能查最新发布 */
    private JSONObject fetchGiteeRelease() throws Exception {
        return getJson(GITEE_API, "application/json");
    }

    private JSONObject getJson(String api, String accept) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", UA);
        try {
            if (c.getResponseCode() != 200) return null;
            String body = readStream(c.getInputStream());
            if (body == null || body.trim().isEmpty()) return null;
            String t = body.trim();
            if (!t.startsWith("{")) return null;
            return new JSONObject(t);
        } finally {
            c.disconnect();
        }
    }

    /** API 被限流时的兜底：读 releases.atom，取最新一条的 tag */
    private String fetchLatestTagFromAtom() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(UPDATE_ATOM).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        try {
            if (c.getResponseCode() != 200) return "";
            String body = readStream(c.getInputStream());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<title>([^<]+)</title>").matcher(body);
            String first = "";
            while (m.find()) {
                String t = m.group(1).trim();
                if (t.isEmpty() || "Release notes".equalsIgnoreCase(t)) continue;
                first = t;
                break;
            }
            return first.replaceFirst("^[vV]", "");
        } finally {
            c.disconnect();
        }
    }

    private String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static final int UPDATE_MODE_AVAILABLE = 0;
    private static final int UPDATE_MODE_PROGRESS = 1;
    private static final int UPDATE_MODE_DONE = 2;
    private static final int UPDATE_MODE_FAILED = 3;

    private void showUpdateResult() {
        String cur = versionName();
        boolean newer = compareVersion(latestVersion, cur) > 0;
        if (!newer) {
            // 已是最新：只在顶部轻提示一句，不弹仓库/版本信息框
            showTopHint("当前已是最新版本 v" + cur);
            return;
        }
        showUpdateDialog(UPDATE_MODE_AVAILABLE);
    }

    /**
     * 与 App 主题一致的更新弹窗：圆角卡片、橙色描边主按钮。
     * 同一个 Dialog 内切换「有新版 / 下载中 / 下载完成 / 下载中断」四种状态，
     * 点「后台继续」或按返回只会关掉弹窗，下载服务仍在后台跑。
     */
    private void ensureUpdateDialog() {
        if (updateDialog != null && updateDialog.isShowing()) return;

        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(false);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(updateRoundRect(P.card, dp(18), 0, 0));
        card.setPadding(dp(22), dp(20), dp(22), dp(18));

        updateDialogTitle = new TextView(this);
        updateDialogTitle.setTextColor(P.text);
        updateDialogTitle.setTextSize(18);
        updateDialogTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        updateDialogTitle.setGravity(android.view.Gravity.CENTER);
        card.addView(updateDialogTitle);

        updateDialogSubtitle = new TextView(this);
        updateDialogSubtitle.setTextColor(P.dim);
        updateDialogSubtitle.setTextSize(12);
        updateDialogSubtitle.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleLp.topMargin = dp(6);
        card.addView(updateDialogSubtitle, subtitleLp);

        // 更新内容
        LinearLayout notesWrap = new LinearLayout(this);
        notesWrap.setOrientation(LinearLayout.VERTICAL);
        TextView notesLabel = new TextView(this);
        notesLabel.setText("更新内容：");
        notesLabel.setTextColor(P.text);
        notesLabel.setTextSize(13);
        notesLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        notesWrap.addView(notesLabel);

        ScrollView notesScroll = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                // 更新说明再长也不让弹窗顶出屏幕，内容在框内滚动
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(dp(210), MeasureSpec.AT_MOST));
            }
        };
        notesScroll.setVerticalScrollBarEnabled(true);
        notesScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        updateDialogNotes = new TextView(this);
        updateDialogNotes.setTextColor(P.text);
        updateDialogNotes.setTextSize(13);
        updateDialogNotes.setLineSpacing(dp(3), 1f);
        notesScroll.addView(updateDialogNotes);
        LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        notesLp.topMargin = dp(8);
        notesWrap.addView(notesScroll, notesLp);
        updateDialogNotesWrap = notesWrap;
        LinearLayout.LayoutParams notesWrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        notesWrapLp.topMargin = dp(18);
        card.addView(notesWrap, notesWrapLp);

        // 下载进度
        LinearLayout progressWrap = new LinearLayout(this);
        progressWrap.setOrientation(LinearLayout.VERTICAL);
        updateDialogPercent = new TextView(this);
        updateDialogPercent.setTextColor(P.accent);
        updateDialogPercent.setTextSize(22);
        updateDialogPercent.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        updateDialogPercent.setGravity(android.view.Gravity.END);
        progressWrap.addView(updateDialogPercent);

        updateDialogBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateDialogBar.setMax(100);
        try {
            updateDialogBar.setProgressTintList(ColorStateList.valueOf(P.accent));
            updateDialogBar.setProgressBackgroundTintList(ColorStateList.valueOf(P.divider));
        } catch (Throwable ignored) {
        }
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        barLp.topMargin = dp(10);
        progressWrap.addView(updateDialogBar, barLp);

        updateDialogLine = new TextView(this);
        updateDialogLine.setTextColor(P.dim);
        updateDialogLine.setTextSize(12);
        updateDialogLine.setSingleLine(true);
        updateDialogLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lineLp.topMargin = dp(10);
        progressWrap.addView(updateDialogLine, lineLp);

        updateDialogError = new TextView(this);
        updateDialogError.setTextColor(P.errorText);
        updateDialogError.setTextSize(12);
        updateDialogError.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errLp.topMargin = dp(8);
        progressWrap.addView(updateDialogError, errLp);
        updateDialogProgressWrap = progressWrap;
        LinearLayout.LayoutParams progressWrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressWrapLp.topMargin = dp(18);
        card.addView(progressWrap, progressWrapLp);

        // 有新版：取消 / 下载并安装
        updateDialogActionsAvailable = updateDialogActionRow();
        TextView availableCancel = updateDialogButton("取消", 0);
        TextView availableDownload = updateDialogButton(
                latestApkUrl.isEmpty() ? "打开下载页" : "下载并安装", 1);
        updateDialogActionsAvailable.addView(availableCancel,
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams dlLp =
                new LinearLayout.LayoutParams(0, dp(44), 1.55f);
        dlLp.leftMargin = dp(10);
        updateDialogActionsAvailable.addView(availableDownload, dlLp);
        availableCancel.setOnClickListener(v -> dismissUpdateDialog());
        availableDownload.setOnClickListener(v -> {
            if (latestApkUrl.isEmpty()) {
                openUrl("gitee".equals(latestSource) ? GITEE_PAGE : UPDATE_PAGE);
                dismissUpdateDialog();
            } else {
                downloadAndInstall(latestApkUrl, latestVersion);
            }
        });
        card.addView(updateDialogActionsAvailable, updateActionRowLp());

        // 下载中：取消下载 / 后台继续
        updateDialogActionsProgress = updateDialogActionRow();
        TextView progressCancel = updateDialogButton("取消下载", 2);
        TextView progressBackground = updateDialogButton("后台继续", 1);
        updateDialogActionsProgress.addView(progressCancel,
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams bgLp =
                new LinearLayout.LayoutParams(0, dp(44), 1.55f);
        bgLp.leftMargin = dp(10);
        updateDialogActionsProgress.addView(progressBackground, bgLp);
        progressCancel.setOnClickListener(v -> cancelUpdateDownload());
        progressBackground.setOnClickListener(v -> {
            dismissUpdateDialog();
            showTopHint("下载会在后台继续，可从通知栏查看进度");
        });
        card.addView(updateDialogActionsProgress, updateActionRowLp());

        // 下载完成：稍后 / 立即安装
        updateDialogActionsDone = updateDialogActionRow();
        TextView doneLater = updateDialogButton("稍后", 0);
        TextView doneInstall = updateDialogButton("立即安装", 1);
        updateDialogActionsDone.addView(doneLater,
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams installLp =
                new LinearLayout.LayoutParams(0, dp(44), 1.55f);
        installLp.leftMargin = dp(10);
        updateDialogActionsDone.addView(doneInstall, installLp);
        doneLater.setOnClickListener(v -> dismissUpdateDialog());
        doneInstall.setOnClickListener(v -> installDownloadedUpdate());
        card.addView(updateDialogActionsDone, updateActionRowLp());

        // 下载中断：取消 / 继续下载
        updateDialogActionsFailed = updateDialogActionRow();
        TextView failedCancel = updateDialogButton("取消", 2);
        TextView failedRetry = updateDialogButton("继续下载", 1);
        updateDialogActionsFailed.addView(failedCancel,
                new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams retryLp =
                new LinearLayout.LayoutParams(0, dp(44), 1.55f);
        retryLp.leftMargin = dp(10);
        updateDialogActionsFailed.addView(failedRetry, retryLp);
        failedCancel.setOnClickListener(v -> cancelUpdateDownload());
        failedRetry.setOnClickListener(v -> retryUpdateDownload());
        card.addView(updateDialogActionsFailed, updateActionRowLp());

        updateDialogBrowser = new TextView(this);
        updateDialogBrowser.setText("改用浏览器下载");
        updateDialogBrowser.setTextColor(P.accent);
        updateDialogBrowser.setTextSize(12);
        updateDialogBrowser.setGravity(android.view.Gravity.CENTER);
        updateDialogBrowser.setPadding(0, dp(10), 0, dp(2));
        updateDialogBrowser.setOnClickListener(v -> {
            openUrl("gitee".equals(latestSource) ? GITEE_PAGE : UPDATE_PAGE);
        });
        card.addView(updateDialogBrowser);

        d.setContentView(card);
        d.setOnDismissListener(x -> {
            updatePolling = false;
            ui.removeCallbacks(updatePollTick);
            updateDialog = null;
            updateDialogMode = -1;
        });

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.62f;
            w.setAttributes(lp);
        }
        updateDialog = d;
        try {
            d.show();
        } catch (Throwable t) {
            updateDialog = null;
            return;
        }
        if (w != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            w.setLayout(Math.min(width, dp(440)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private LinearLayout updateDialogActionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams updateActionRowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        return lp;
    }

    /** type: 0 = 次要文字按钮，1 = 橙色描边主按钮，2 = 危险文字按钮 */
    private TextView updateDialogButton(String text, int type) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14);
        b.setGravity(android.view.Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(10), 0, dp(10), 0);
        if (type == 1) {
            b.setTextColor(P.accent);
            b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            b.setBackground(updateRoundRect(Color.TRANSPARENT, dp(11),
                    Math.max(2, dp(1)), P.accent));
        } else if (type == 2) {
            b.setTextColor(P.danger);
        } else {
            b.setTextColor(P.dim);
        }
        return b;
    }

    private GradientDrawable updateRoundRect(int fill, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(radius);
        bg.setColor(fill);
        if (strokeWidth > 0) bg.setStroke(strokeWidth, strokeColor);
        return bg;
    }

    private void showUpdateDialog(int mode) {
        ensureUpdateDialog();
        if (updateDialog == null) return;
        applyUpdateDialogMode(mode);
        if (mode == UPDATE_MODE_PROGRESS) startUpdatePolling();
    }

    private void applyUpdateDialogMode(int mode) {
        if (updateDialog == null) return;
        updateDialogMode = mode;

        updateDialogNotesWrap.setVisibility(
                mode == UPDATE_MODE_AVAILABLE ? View.VISIBLE : View.GONE);
        updateDialogProgressWrap.setVisibility(
                mode == UPDATE_MODE_AVAILABLE ? View.GONE : View.VISIBLE);
        updateDialogActionsAvailable.setVisibility(
                mode == UPDATE_MODE_AVAILABLE ? View.VISIBLE : View.GONE);
        updateDialogActionsProgress.setVisibility(
                mode == UPDATE_MODE_PROGRESS ? View.VISIBLE : View.GONE);
        updateDialogActionsDone.setVisibility(
                mode == UPDATE_MODE_DONE ? View.VISIBLE : View.GONE);
        updateDialogActionsFailed.setVisibility(
                mode == UPDATE_MODE_FAILED ? View.VISIBLE : View.GONE);
        updateDialogBrowser.setVisibility(
                mode == UPDATE_MODE_FAILED ? View.VISIBLE : View.GONE);

        String ver = updateVersionForUi();
        long total = updateTotalForUi();
        if (mode == UPDATE_MODE_AVAILABLE) {
            updateDialogTitle.setText("发现新版本");
            updateDialogSubtitle.setText("v" + latestVersion
                    + (latestSize > 0 ? " · " + humanSize(latestSize) : ""));
            String notes = latestNotes == null ? "" : latestNotes.trim();
            if (notes.length() > 4000) notes = notes.substring(0, 4000) + "…";
            updateDialogNotes.setText(notes.isEmpty() ? "该版本没有填写更新说明。" : notes);
            updateDialogNotes.setTextColor(notes.isEmpty() ? P.dim : P.text);
        } else if (mode == UPDATE_MODE_PROGRESS) {
            updateDialogTitle.setText("正在下载新版本");
            updateDialogSubtitle.setText("v" + ver + (total > 0 ? " · " + humanSize(total) : ""));
            updateDialogError.setVisibility(View.GONE);
            updateProgressUi();
        } else if (mode == UPDATE_MODE_DONE) {
            updateDialogTitle.setText("下载完成");
            updateDialogSubtitle.setText("v" + ver + (total > 0 ? " · " + humanSize(total) : ""));
            updateDialogPercent.setText("100%");
            updateDialogBar.setProgress(100);
            updateDialogLine.setText("安装包已下载完成，点击「立即安装」完成更新");
            updateDialogError.setVisibility(View.GONE);
        } else {
            updateDialogTitle.setText("下载中断");
            updateDialogSubtitle.setText("v" + ver + (total > 0 ? " · " + humanSize(total) : ""));
            updateProgressUi();
            String err = UpdateService.errorText;
            if (err == null || err.isEmpty()) err = UpdateStore.error(this);
            updateDialogError.setText(err == null || err.isEmpty()
                    ? "网络连接中断，可稍后继续下载。" : err);
            updateDialogError.setVisibility(View.VISIBLE);
        }
    }

    private String updateVersionForUi() {
        String v = UpdateService.version;
        if (v == null || v.isEmpty()) v = UpdateStore.version(this);
        return v == null ? "" : v;
    }

    private long updateTotalForUi() {
        long t = UpdateService.totalSize;
        if (t <= 0) t = UpdateStore.size(this);
        return Math.max(0L, t);
    }

    private void updateProgressUi() {
        if (updateDialogPercent == null || updateDialogBar == null || updateDialogLine == null) {
            return;
        }
        int pct;
        long got;
        long total;
        String speed;
        if (!UpdateStore.STATE_IDLE.equals(UpdateService.state)) {
            pct = UpdateService.percent;
            got = UpdateService.downloaded;
            total = UpdateService.totalSize;
            speed = UpdateService.speedText;
        } else {
            got = UpdateStore.downloaded(this);
            total = UpdateStore.size(this);
            pct = total > 0 ? (int) Math.min(100L, got * 100L / total) : 0;
            speed = "";
        }
        if (total > 0) pct = (int) Math.min(100L, Math.max(0L, got) * 100L / total);
        pct = Math.max(0, Math.min(100, pct));
        updateDialogPercent.setText(pct + "%");
        updateDialogBar.setProgress(pct);
        String line = total > 0
                ? humanSize(got) + " / " + humanSize(total)
                : "已下载 " + humanSize(got);
        if (speed != null && !speed.isEmpty()) line += " · " + speed;
        updateDialogLine.setText(line);
    }

    private void startUpdatePolling() {
        if (updatePolling) return;
        updatePolling = true;
        ui.postDelayed(updatePollTick, 400);
    }

    private void stopUpdatePolling() {
        updatePolling = false;
        ui.removeCallbacks(updatePollTick);
    }

    /** 轮询前台服务的下载状态，弹窗跟着刷新；弹窗关掉后自动停止轮询 */
    private void pollUpdateProgress() {
        if (!updatePolling) return;
        if (updateDialog == null || !updateDialog.isShowing()) {
            updatePolling = false;
            return;
        }

        if (UpdateStore.hasCompleted(this)) {
            if (updateDialogMode != UPDATE_MODE_DONE) {
                applyUpdateDialogMode(UPDATE_MODE_DONE);
            } else {
                updateProgressUi();
            }
            updatePolling = false;
            return;
        }

        String st = UpdateService.state;
        if (UpdateStore.STATE_FAILED.equals(st)) {
            applyUpdateDialogMode(UPDATE_MODE_FAILED);
            updatePolling = false;
            return;
        }

        if (!UpdateService.running && UpdateStore.STATE_DOWNLOADING.equals(UpdateStore.state(this))) {
            // 服务被系统回收过：启动一次续传，成功后继续轮询
            if (!updateResumeTried) {
                updateResumeTried = true;
                UpdateService.resume(this);
            }
        }

        if (UpdateStore.STATE_IDLE.equals(st) && !UpdateStore.hasResumable(this)) {
            updatePolling = false;
            dismissUpdateDialog();
            return;
        }

        if (updateDialogMode != UPDATE_MODE_PROGRESS) {
            applyUpdateDialogMode(UPDATE_MODE_PROGRESS);
        } else {
            updateProgressUi();
        }
        ui.postDelayed(updatePollTick, 400);
    }

    private void dismissUpdateDialog() {
        stopUpdatePolling();
        Dialog d = updateDialog;
        updateDialog = null;
        updateDialogMode = -1;
        if (d != null) {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private void installDownloadedUpdate() {
        String path = UpdateStore.path(this);
        if (path == null || path.isEmpty()) path = UpdateService.savedPath;
        File f = new File(path == null ? "" : path);
        if (!f.isFile() || f.length() < 200L * 1024L) {
            Toast.makeText(this, "更新包不完整，请重新下载", Toast.LENGTH_LONG).show();
            UpdateStore.clear(this);
            UpdateService.state = UpdateStore.STATE_IDLE;
            dismissUpdateDialog();
            return;
        }
        dismissUpdateDialog();
        installApk(f);
    }

    private void retryUpdateDownload() {
        if (UpdateService.running) {
            showUpdateProgress();
            return;
        }
        String url = UpdateStore.url(this);
        String ver = UpdateStore.version(this);
        if (url.isEmpty() || ver.isEmpty()) {
            dismissUpdateDialog();
            checkUpdate();
            return;
        }
        updateResumeTried = false;
        UpdateService.start(this, url, ver, UpdateStore.size(this));
        showUpdateProgress();
        startUpdatePolling();
    }

    /** 清掉已经装上、文件丢失或版本已追平的旧下载状态 */
    private void cleanupStaleUpdateState() {
        String st = UpdateStore.state(this);
        if (UpdateStore.STATE_DONE.equals(st)) {
            String ver = UpdateStore.version(this);
            File f = new File(UpdateStore.path(this));
            if (!f.isFile() || f.length() < 200L * 1024L
                    || compareVersion(ver, versionName()) <= 0) {
                UpdateStore.clear(this);
                if (UpdateStore.STATE_DONE.equals(UpdateService.state)) {
                    UpdateService.state = UpdateStore.STATE_IDLE;
                }
            }
        } else if (UpdateStore.STATE_DOWNLOADING.equals(st)
                || UpdateStore.STATE_FAILED.equals(st)) {
            String ver = UpdateStore.version(this);
            // 地址 / 版本缺失，或下载的是已经装上的旧版本：都没有继续的意义
            if (UpdateStore.url(this).isEmpty() || ver.isEmpty()
                    || compareVersion(ver, versionName()) <= 0) {
                UpdateStore.clear(this);
            }
        }
    }

    /** 有未完成的下载时接回服务（不重复下载），show = true 时同时把弹窗换成进度态 */
    private void resumePendingDownload(boolean show) {
        if (UpdateStore.hasCompleted(this)) {
            if (show) showUpdateProgress();
            return;
        }
        if (!UpdateStore.hasResumable(this)) return;
        if (!UpdateService.running) {
            updateResumeTried = true;
            UpdateService.resume(this);
        }
        if (show) showUpdateProgress();
    }

    /** 顶部滑入的轻提示：只用于「已是最新版」这类不需要用户决策的反馈 */
    private void showTopHint(String msg) {
        try {
            ViewGroup host = findViewById(android.R.id.content);
            if (host == null) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                return;
            }
            TextView tv = new TextView(this);
            tv.setText(msg);
            tv.setTextSize(13);
            tv.setTextColor(0xFFFFFFFF);
            tv.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xF01F232C);
            bg.setCornerRadius(dp(22));
            tv.setBackground(bg);
            tv.setPadding(dp(18), dp(10), dp(18), dp(10));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(64);
            tv.setAlpha(0f);
            tv.setTranslationY(-dp(12));
            host.addView(tv, lp);
            tv.animate().alpha(1f).translationY(0f).setDuration(180).start();
            ui.postDelayed(() -> tv.animate().alpha(0f).translationY(-dp(12))
                    .setDuration(220)
                    .withEndAction(() -> {
                        try {
                            host.removeView(tv);
                        } catch (Throwable ignored) {
                        }
                    }).start(), 2000);
        } catch (Throwable t) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    /** 2026-11-10T05:20:31Z → 2026-11-10 13:20（本地时区） */
    private String shortDate(String iso) {
        try {
            java.text.SimpleDateFormat in =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = in.parse(iso.replaceAll("\\.\\d+Z$", "Z"));
            if (d == null) return iso;
            java.text.SimpleDateFormat out =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return out.format(d);
        } catch (Exception e) {
            return iso;
        }
    }

    private void showUpdateError(String err) {
        // 检查失败也不必糊一屏地址，给一句提示 + 一个「重试」入口
        new AlertDialog.Builder(this)
                .setTitle(repoEmpty ? "暂无可用更新" : "检查更新失败")
                .setMessage(err)
                .setPositiveButton("重试", (d, w) -> checkUpdate())
                .setNegativeButton("关闭", null)
                .show();
    }

    private int compareVersion(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length && !x[i].isEmpty() ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length && !y[i].isEmpty() ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 开始下载新版本：交给前台服务下载，通知栏与弹窗同步显示进度 / 大小 / 速度 */
    private void downloadAndInstall(String apkUrl, String ver) {
        // 已有同一版本的下载（包括上次中断的 .part）就接着来，不要再开一份
        if (UpdateService.running || UpdateStore.hasResumable(this)) {
            resumePendingDownload(false);
            showUpdateProgress();
            Toast.makeText(this, "正在继续上次的下载", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apkUrl == null || apkUrl.isEmpty()) {
            openUrl("gitee".equals(latestSource) ? GITEE_PAGE : UPDATE_PAGE);
            return;
        }
        ensureNotificationPermission();
        updateResumeTried = false;
        UpdateService.start(this, apkUrl, ver, latestSize);
        showUpdateProgress();
    }

    /** Android 13+ 不授权通知权限时，通知栏看不到下载进度 */
    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) return;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_PERM);
        } catch (Throwable ignored) {
        }
    }

    private void showUpdateProgress() {
        int mode;
        if (UpdateStore.hasCompleted(this) || UpdateStore.STATE_DONE.equals(UpdateService.state)) {
            mode = UPDATE_MODE_DONE;
        } else if (UpdateStore.STATE_FAILED.equals(UpdateService.state)
                || UpdateStore.STATE_FAILED.equals(UpdateStore.state(this))) {
            mode = UPDATE_MODE_FAILED;
        } else {
            mode = UPDATE_MODE_PROGRESS;
        }
        showUpdateDialog(mode);
        if (mode == UPDATE_MODE_PROGRESS) startUpdatePolling();
    }

    private void cancelUpdateDownload() {
        UpdateService.cancel(this);
        UpdateStore.clear(this);
        UpdateService.state = UpdateStore.STATE_IDLE;
        UpdateService.running = false;
        dismissUpdateDialog();
        Toast.makeText(this, "已取消下载更新", Toast.LENGTH_SHORT).show();
    }

    /** 退出重进时如果后台还在下载，直接把进度弹窗接回来；下载完成的不自动弹窗 */
    private void resumePendingUpdate() {
        cleanupStaleUpdateState();
        if (UpdateService.running) {
            showUpdateProgress();
            return;
        }
        if (UpdateStore.STATE_DOWNLOADING.equals(UpdateStore.state(this))
                && UpdateStore.hasResumable(this)) {
            UpdateService.resume(this);
            showUpdateProgress();
        }
    }

    private void installApk(File f) {
        if (f == null || !f.exists()) {
            Toast.makeText(this, "安装包不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许安装未知来源应用，再点一次检查更新", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动安装：" + shortMsg(e), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------- 下载目录 / 打开本地 ----------------

    private void openDir() {
        try {
            Intent i = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return;
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "应用下载目录: "
                + (downloadDir.isEmpty() ? "未初始化" : downloadDir), Toast.LENGTH_LONG).show();
    }

    private void openLocal(JSONObject t) {
        if (!pyReady) {
            Toast.makeText(this, "正在初始化，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        final String fn = t.optString("filename", "");
        if (fn.isEmpty()) {
            Toast.makeText(this, "文件信息缺失", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isPlayable(fn) && !isImageFile(fn)) {
            Toast.makeText(this, "该文件不支持在 app 内打开", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                String dir = py.getModule("vsp").callAttr("get_save_dir").toString();
                final File f = new File(dir, fn);
                ui.post(() -> {
                    if (!f.exists()) Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
                    else playLocal(new MediaItem(f.getAbsolutePath(), fn, f.length(), f.lastModified()));
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private static boolean isPlayable(String name) {
        return guessType(name) == TYPE_VIDEO || guessType(name) == TYPE_AUDIO;
    }

    private static boolean isAudioFile(String name) {
        return guessType(name) == TYPE_AUDIO;
    }

    private static boolean isImageFile(String name) {
        return guessType(name) == TYPE_IMAGE;
    }

    /** 按扩展名判断类型；不认识返回 -1 */
    private static int guessType(String name) {
        if (name == null) return -1;
        String n = name.toLowerCase(Locale.ROOT);
        String[] video = {".mp4", ".webm", ".mkv", ".mov", ".m4v", ".avi", ".3gp", ".flv", ".ts"};
        String[] audio = {".mp3", ".m4a", ".aac", ".opus", ".flac", ".wav", ".ogg", ".m4b", ".aiff"};
        String[] image = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"};
        for (String ext : video) if (n.endsWith(ext)) return TYPE_VIDEO;
        for (String ext : audio) if (n.endsWith(ext)) return TYPE_AUDIO;
        for (String ext : image) if (n.endsWith(ext)) return TYPE_IMAGE;
        return -1;
    }

    // ---------------- 下载队列 ----------------

    private void startTaskPolling() {
        taskPoller = Executors.newSingleThreadScheduledExecutor();
        taskPoller.scheduleWithFixedDelay(() -> {
            try {
                refreshTasks();
            } catch (Throwable ignored) {
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    private synchronized void refreshTasks() {
        if (!pyReady) return;
        try {
            String tasksJson = py.getModule("vsp").callAttr("get_tasks").toString();
            if (tasksJson.equals(lastTasksJson)) return;
            lastTasksJson = tasksJson;
            JSONArray arr = new JSONArray(tasksJson);
            List<JSONObject> active = new ArrayList<>();
            List<JSONObject> finished = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String st = o.optString("status", "running");
                if ("running".equals(st) || "paused".equals(st)) active.add(o);
                else finished.add(o);
            }
            Collections.reverse(active);
            Collections.reverse(finished);
            ui.post(() -> renderTasks(active, finished));
        } catch (Exception ignored) {
        }
    }

    private void renderTasks(List<JSONObject> active, List<JSONObject> finished) {
        activeTasks = active;
        finishedTasks = finished;
        downloadingList.setAdapter(new TaskAdapter(active, true));
        taskList.setAdapter(new TaskAdapter(finished, false));
        noDownloading.setVisibility(active.isEmpty() ? View.VISIBLE : View.GONE);
        noTask.setVisibility(finished.isEmpty() ? View.VISIBLE : View.GONE);
        dlCount.setText(active.size() + " 下载中 · " + finished.size() + " 已完成");
        queueSummary.setText(active.size() + " 下载中 / " + finished.size() + " 已完成 ›");

        Set<String> alive = new HashSet<>();
        for (JSONObject o : active) alive.add(o.optString("taskId", ""));
        for (JSONObject o : finished) alive.add(o.optString("taskId", ""));
        selectedIds.retainAll(alive);
        updateSelectionBar();
    }

    private void togglePause(String tid, String status) {
        final boolean resume = "paused".equals(status);
        new Thread(() -> {
            try {
                py.getModule("vsp").callAttr(resume ? "resume_task" : "pause_task", tid);
                lastTasksJson = "";
                refreshTasks();
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "操作失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // ---------------- 多选 ----------------

    private void enterSelection(int owner, String firstId) {
        selectionMode = true;
        selectionOwner = owner;
        selectedIds.clear();
        selectedIds.add(firstId);
        updateSelectionBar();
        notifyAdapters();
    }

    private void exitSelection() {
        selectionMode = false;
        selectionOwner = -1;
        selectedIds.clear();
        selectionBar.setVisibility(View.GONE);
        notifyAdapters();
    }

    private void toggleSelect(String id) {
        if (selectedIds.contains(id)) selectedIds.remove(id);
        else selectedIds.add(id);
        updateSelectionBar();
        notifyAdapters();
    }

    private void updateSelectionBar() {
        if (!selectionMode) {
            selectionBar.setVisibility(View.GONE);
            return;
        }
        selectionBar.setVisibility(View.VISIBLE);
        selCount.setText("已选择 " + selectedIds.size() + " 项");
        TextView action = findViewById(R.id.sel_delete);
        boolean batchDownload = selectionOwner == PAGE_HOME;
        action.setText(batchDownload ? "下载" : "删除");
        action.setTextColor(batchDownload ? P.accent : P.danger);
    }

    private void selectAll() {
        if (selectionOwner == PAGE_HOME) {
            for (JSONObject o : currentFormats) selectedIds.add(o.optString("formatId", ""));
        } else {
            List<JSONObject> list = selectionOwner == SEL_ACTIVE ? activeTasks : finishedTasks;
            for (JSONObject o : list) selectedIds.add(o.optString("taskId", ""));
        }
        updateSelectionBar();
        notifyAdapters();
    }

    private void confirmSelected() {
        if (selectionOwner == PAGE_HOME) downloadSelected();
        else deleteSelected();
    }

    private void downloadSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "请先选择条目", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> ids = new ArrayList<>(selectedIds);
        List<JSONObject> picked = new ArrayList<>();
        for (JSONObject o : currentFormats) {
            if (ids.contains(o.optString("formatId", ""))) picked.add(o);
        }
        exitSelection();
        for (JSONObject o : picked) startDownload(o, true);
        Toast.makeText(this, "已加入 " + picked.size() + " 个任务 · 我的 → 下载队列",
                Toast.LENGTH_SHORT).show();
    }

    private void deleteSelected() {
        if (selectedIds.isEmpty()) {
            Toast.makeText(this, "请先选择任务", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> ids = new ArrayList<>(selectedIds);
        new AlertDialog.Builder(this)
                .setTitle("删除所选任务")
                .setMessage("将删除 " + ids.size() + " 个任务及其已下载文件，确定吗？")
                .setPositiveButton("删除", (d, w) -> {
                    exitSelection();
                    new Thread(() -> {
                        for (String id : ids) {
                            try {
                                py.getModule("vsp").callAttr("delete_task", id);
                            } catch (Exception ignored) {
                            }
                        }
                        lastTasksJson = "";
                        refreshTasks();
                        ui.post(() -> Toast.makeText(this, "已删除 " + ids.size() + " 个任务",
                                Toast.LENGTH_SHORT).show());
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void notifyAdapters() {
        safeNotify(formatList);
        safeNotify(taskList);
        safeNotify(downloadingList);
    }

    private void safeNotify(RecyclerView rv) {
        RecyclerView.Adapter a = rv.getAdapter();
        if (a != null) a.notifyDataSetChanged();
    }

    // ---------------- Adapter ----------------

    class FormatAdapter extends RecyclerView.Adapter<FormatAdapter.VH> {
        final List<JSONObject> data;

        FormatAdapter(List<JSONObject> d) {
            data = d;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(getLayoutInflater().inflate(R.layout.item_format, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int i) {
            JSONObject f = data.get(i);
            final String fid = f.optString("formatId", "");
            h.quality.setText(f.optString("quality", ""));
            h.icon.setImageResource("videos".equals(currentTab) ? R.drawable.ic_video
                    : "audios".equals(currentTab) ? R.drawable.ic_audio : R.drawable.ic_image);
            StringBuilder meta = new StringBuilder();
            String vcodec = f.optString("vcodec", "");
            String acodec = f.optString("acodec", "");
            if (!vcodec.isEmpty() && !"null".equals(vcodec)) meta.append(vcodec.split("\\.")[0]).append(" ");
            if (!acodec.isEmpty() && !"null".equals(acodec)) meta.append(acodec.split("\\.")[0]);
            if (f.optBoolean("needsMerge", false)) meta.append(" · 下载后自动补音轨");
            h.meta.setText(meta.toString().trim());
            h.size.setText(f.optString("size", ""));

            boolean choosing = selectionMode && selectionOwner == PAGE_HOME;
            h.check.setVisibility(choosing ? View.VISIBLE : View.GONE);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(choosing && selectedIds.contains(fid));
            h.check.setOnCheckedChangeListener((b, checked) -> {
                if (checked) selectedIds.add(fid);
                else selectedIds.remove(fid);
                updateSelectionBar();
            });

            h.btnDownload.setOnClickListener(v -> {
                if (choosing) toggleSelect(fid);
                else startDownload(f, false);
            });
            h.itemView.setOnClickListener(v -> {
                if (choosing) toggleSelect(fid);
                else doPreview(f);
            });
            h.itemView.setOnLongClickListener(v -> {
                enterSelection(PAGE_HOME, fid);
                return true;
            });
            applyThemeToTree(h.itemView);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView quality, meta, size;
            ImageView icon, btnDownload;
            CheckBox check;

            VH(View v) {
                super(v);
                quality = v.findViewById(R.id.f_quality);
                meta = v.findViewById(R.id.f_meta);
                size = v.findViewById(R.id.f_size);
                icon = v.findViewById(R.id.f_icon);
                btnDownload = v.findViewById(R.id.btn_download);
                check = v.findViewById(R.id.f_check);
            }
        }
    }

    class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.VH> {
        final List<JSONObject> data;
        final boolean activeMode;

        TaskAdapter(List<JSONObject> d, boolean active) {
            data = d;
            activeMode = active;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(getLayoutInflater().inflate(R.layout.item_task, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int i) {
            JSONObject t = data.get(i);
            final String tid = t.optString("taskId", "");
            final String status = t.optString("status", "running");
            final String fn = t.optString("filename", "");
            int pct = (int) t.optDouble("progress", 0);

            h.title.setText(t.optString("title", ""));
            loadThumb(h.thumb, t.optString("thumb", ""));

            int myOwner = activeMode ? SEL_ACTIVE : SEL_FINISHED;
            boolean choosing = selectionMode && selectionOwner == myOwner;
            h.check.setVisibility(choosing ? View.VISIBLE : View.GONE);
            h.check.setOnCheckedChangeListener(null);
            h.check.setChecked(choosing && selectedIds.contains(tid));
            h.check.setOnCheckedChangeListener((b, checked) -> {
                if (checked) selectedIds.add(tid);
                else selectedIds.remove(tid);
                updateSelectionBar();
            });

            if (activeMode) {
                long total = t.optLong("total", 0L);
                long done = t.optLong("downloaded", 0L);
                String speed = t.optString("speed", "");
                // 速度字段有时被当成阶段提示用（「合并音视频…」「已暂停」），这类不算速度
                boolean realSpeed = speed.endsWith("/s");
                h.status.setText("paused".equals(status) ? "已暂停 · " + pct + "%"
                        : (!speed.isEmpty() && !realSpeed) ? speed
                        : "下载中 · " + pct + "%");
                // 进度条右边紧跟「已下载 / 总大小 · 速度」，进度与速度在同一行
                // 进度条右边是「已下载 / 总大小」，速度再单独占一格贴在它右边
                String sizeLine;
                if (total > 0) sizeLine = humanSize(done) + "/" + humanSize(total);
                else if (done > 0) sizeLine = humanSize(done);
                else sizeLine = "连接中…";
                h.size.setText(sizeLine);
                h.size.setVisibility(View.VISIBLE);
                if (realSpeed) {
                    h.speed.setText(speed);
                    h.speed.setVisibility(View.VISIBLE);
                } else {
                    h.speed.setVisibility(View.GONE);
                }
                h.progress.setVisibility(View.VISIBLE);
                h.progress.setProgress(pct);
                h.open.setVisibility(View.GONE);
                h.pause.setVisibility(View.VISIBLE);
                h.pause.setImageResource("paused".equals(status)
                        ? R.drawable.ic_resume : R.drawable.ic_pause);
                h.pause.setOnClickListener(v -> {
                    if (choosing) toggleSelect(tid);
                    else togglePause(tid, status);
                });
            } else {
                String err = t.optString("error", "");
                h.status.setText("done".equals(status) ? "完成"
                        : "已取消".equals(err) ? "已取消" : "失败");
                h.size.setVisibility(View.GONE);
                h.speed.setVisibility(View.GONE);
                h.progress.setVisibility(View.GONE);
                boolean canOpen = "done".equals(status) && (isPlayable(fn) || isImageFile(fn));
                h.open.setVisibility(canOpen ? View.VISIBLE : View.GONE);
                h.open.setOnClickListener(v -> {
                    if (!choosing) openLocal(t);
                });
                h.pause.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> {
                if (choosing) toggleSelect(tid);
                else if (!activeMode && "done".equals(status)
                        && (isPlayable(fn) || isImageFile(fn))) openLocal(t);
            });
            h.itemView.setOnLongClickListener(v -> {
                enterSelection(activeMode ? SEL_ACTIVE : SEL_FINISHED, tid);
                return true;
            });
            applyThemeToTree(h.itemView);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView title, status, open, size, speed;
            ImageView thumb, pause;
            CheckBox check;
            ProgressBar progress;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.t_title);
                status = v.findViewById(R.id.t_status);
                size = v.findViewById(R.id.t_size);
                speed = v.findViewById(R.id.t_speed);
                thumb = v.findViewById(R.id.t_thumb);
                check = v.findViewById(R.id.t_check);
                progress = v.findViewById(R.id.t_progress);
                open = v.findViewById(R.id.t_open);
                pause = v.findViewById(R.id.t_pause);
            }
        }
    }

    /** 本地媒体网格：三列，视频/图片显示缩略图，音频显示音符 */
    class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.VH> {
        final List<MediaItem> data;
        final boolean videoPage;

        MediaAdapter(List<MediaItem> d, boolean videoPage) {
            data = d;
            this.videoPage = videoPage;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(getLayoutInflater().inflate(
                    videoPage ? R.layout.item_video : R.layout.item_local, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int i) {
            final MediaItem m = data.get(i);
            // 卡片只留封面 + 时长这类媒体信息，不再摆文件名 / 体积（照片没有时长就不显示）
            if (h.name != null) h.name.setVisibility(View.GONE);
            if (h.meta != null) {
                if (m.duration > 0) {
                    h.meta.setText(fmtTime((int) m.duration));
                    h.meta.setVisibility(View.VISIBLE);
                } else {
                    h.meta.setText("");
                    h.meta.setVisibility(View.GONE);
                }
            }
            if (h.dur != null) {
                if (m.duration > 0) {
                    h.dur.setText(fmtTime((int) m.duration));
                    h.dur.setVisibility(View.VISIBLE);
                } else {
                    h.dur.setVisibility(View.GONE);
                }
            }
            if (h.type != null) {
                h.type.setImageResource(m.type == TYPE_VIDEO ? R.drawable.ic_video
                        : m.type == TYPE_AUDIO ? R.drawable.ic_music : R.drawable.ic_image);
            }
            loadMediaThumb(h.thumb, m);
            h.itemView.setOnClickListener(v -> playLocal(m));
            if (h.extract != null) {
                h.extract.setOnClickListener(v -> extractAudioFrom(m));
            }
            h.itemView.setOnLongClickListener(v -> {
                if (videoPage) {
                    showVideoActions(m);
                    return true;
                }
                return false;
            });
            applyThemeToTree(h.itemView);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name, meta, dur;
            ImageView thumb, type;
            ImageButton extract;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.l_name);
                meta = v.findViewById(R.id.l_meta);
                thumb = v.findViewById(R.id.l_thumb);
                type = v.findViewById(R.id.l_type);
                dur = v.findViewById(R.id.l_dur);
                extract = v.findViewById(R.id.v_extract);
                // item_video 用的是 v_ 前缀，取不到时按视频卡片再找一次
                if (name == null) name = v.findViewById(R.id.v_name);
                if (meta == null) meta = v.findViewById(R.id.v_meta);
                if (thumb == null) thumb = v.findViewById(R.id.v_thumb);
                if (dur == null) dur = v.findViewById(R.id.v_dur);
            }
        }
    }

    private void showVideoActions(MediaItem m) {
        new AlertDialog.Builder(this)
                .setTitle(m.name)
                .setItems(new CharSequence[]{"播放", "提取配乐"}, (d, which) -> {
                    if (which == 0) playLocal(m);
                    else extractAudioFrom(m);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        final List<File> data;

        FileAdapter(List<File> d) {
            data = d;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(getLayoutInflater().inflate(R.layout.item_file, p, false));
        }

        @Override
        public void onBindViewHolder(VH h, int i) {
            final File f = data.get(i);
            final String n = f.getName();
            h.name.setText(n);
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new java.util.Date(f.lastModified()));
            h.meta.setText(humanSize(f.length()) + " · " + time);
            h.icon.setImageResource(isImageFile(n) ? R.drawable.ic_image
                    : isAudioFile(n) ? R.drawable.ic_music : R.drawable.ic_video);
            h.open.setOnClickListener(v -> playLocal(
                    new MediaItem(f.getAbsolutePath(), n, f.length(), f.lastModified())));
            h.itemView.setOnClickListener(v -> playLocal(
                    new MediaItem(f.getAbsolutePath(), n, f.length(), f.lastModified())));
            h.delete.setOnClickListener(v -> deleteStorageFile(f));
            applyThemeToTree(h.itemView);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView name, meta;
            ImageView icon;
            ImageButton open, delete;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.fm_name);
                meta = v.findViewById(R.id.fm_meta);
                icon = v.findViewById(R.id.fm_icon);
                open = v.findViewById(R.id.fm_open);
                delete = v.findViewById(R.id.fm_delete);
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (updateDialog != null) updateDialog.dismiss();
        } catch (Throwable ignored) {
        }
        updateDialog = null;
        if (taskPoller != null) taskPoller.shutdownNow();
        if (swipePool != null) swipePool.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        stopPlayer();
    }
}
