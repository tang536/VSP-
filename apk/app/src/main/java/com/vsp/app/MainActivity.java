package com.vsp.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
    /** Gitee 还没建 Release 时，读仓库里的 dist/latest.json（随源码一起推上去） */
    private static final String GITEE_INDEX =
            "https://gitee.com/" + GITEE_REPO + "/raw/main/dist/latest.json";
    private static final String GITEE_DIST =
            "https://gitee.com/" + GITEE_REPO + "/raw/main/";
    private static final String UPDATE_PAGE = "https://github.com/" + UPDATE_REPO + "/releases";
    private static final String GITEE_PAGE = "https://gitee.com/" + GITEE_REPO + "/releases";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

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
    private AlertDialog historyDialog;

    // 全屏
    private FrameLayout fullscreenHost;
    private boolean fullscreen = false;

    // 更新下载
    // 正在下载 / 安装更新：期间不允许重复触发检查更新与下载
    private volatile boolean updating = false;
    private AlertDialog updateDialog;
    private ProgressBar updateDialogBar;
    private TextView updateDialogText, updateDialogPct, updateDialogSpeed;

    /** 控制条 3 秒无操作自动收起（顶部标题栏与返回键一起收，不挡画面） */
    private final Runnable hideOverlay = () -> hidePlayerOverlay();

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            MediaPlayer mp = currentMp;
            if (mp == null) return;
            try {
                int pos = mp.getCurrentPosition();
                int dur = mp.getDuration();
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
                    MediaPlayer mp = currentMp;
                    if (mp != null) {
                        try {
                            mp.seekTo(progress);
                        } catch (Throwable ignored) {
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
                if (currentMp != null) {
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
            return BitmapFactory.decodeFile(f.getAbsolutePath());
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
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, fos);
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
        new Thread(() -> {
            Bitmap bmp = readDiskThumb(key);
            if (bmp == null) {
                bmp = fetchBitmap(key, sample);
                if (bmp != null) writeDiskThumb(key, bmp);
            }
            if (bmp != null) {
                synchronized (thumbCache) {
                    thumbCache.put(key, bmp);
                    if (thumbCache.size() > 80) {
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
        }, "vsp-thumb").start();
    }

    private Bitmap fetchBitmap(String url, int sample) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            try {
                URL u = new URL(url);
                c.setRequestProperty("Referer", u.getProtocol() + "://" + u.getHost() + "/");
            } catch (Exception ignored) {
            }
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
        new Thread(() -> {
            Bitmap bmp = readDiskThumb(key);
            try {
                if (bmp != null) {
                    // 命中磁盘缓存
                } else if (m.type == TYPE_IMAGE) {
                    if (m.source.startsWith("content://")) {
                        BitmapFactory.Options opt = new BitmapFactory.Options();
                        opt.inSampleSize = 4;
                        bmp = BitmapFactory.decodeStream(
                                getContentResolver().openInputStream(Uri.parse(m.source)), null, opt);
                    } else {
                        BitmapFactory.Options opt = new BitmapFactory.Options();
                        opt.inSampleSize = 4;
                        bmp = BitmapFactory.decodeFile(m.source, opt);
                    }
                } else {
                    bmp = videoThumb(m);
                }
            } catch (Throwable ignored) {
            }
            if (bmp != null) {
                writeDiskThumb(key, bmp);
                synchronized (thumbCache) {
                    thumbCache.put(key, bmp);
                    if (thumbCache.size() > 80) {
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
        }, "vsp-media-thumb").start();
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

        if (type == TYPE_IMAGE) {
            String url = currentThumb.isEmpty() ? fmt.optString("url", "") : currentThumb;
            if (url.isEmpty()) {
                Toast.makeText(this, "无法获取图片预览", Toast.LENGTH_SHORT).show();
                return;
            }
            openPlayer(url, TYPE_IMAGE, title, false);
            return;
        }
        if (!pyReady) {
            Toast.makeText(this, "正在初始化，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在准备预览…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String previewUrl = py.getModule("vsp").callAttr("get_preview_url", currentUrl, formatId).toString();
                ui.post(() -> {
                    if (previewUrl == null || previewUrl.isEmpty()) {
                        Toast.makeText(this, "无法获取预览", Toast.LENGTH_SHORT).show();
                    } else {
                        openPlayer(previewUrl, type, title, false);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "预览失败: " + friendlyError(e), Toast.LENGTH_SHORT).show());
            }
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

    private void openPlayer(String source, int type, String title, boolean local) {
        openPlayer(source, type, title, local, null, -1);
    }

    private void openPlayer(String source, int type, String title, boolean local,
                            List<MediaItem> siblings, int index) {
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
        playerVideo.setVisibility(type == TYPE_VIDEO ? View.VISIBLE : View.GONE);
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

        final boolean isContent = source != null && source.startsWith("content://");
        if (type == TYPE_VIDEO) {
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
            final boolean[] retriedPlain = {false};
            playerVideo.setOnErrorListener((mp, what, extra) -> {
                // 少数直链对自定义请求头更敏感（带 Referer 反而 403），去掉头再试一次
                if (!retriedPlain[0] && !local && !isContent) {
                    retriedPlain[0] = true;
                    try {
                        playerVideo.setVideoURI(Uri.parse(source));
                        playerVideo.start();
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
                Toast.makeText(this, "无法播放该视频（格式或直链可能受限）", Toast.LENGTH_SHORT).show();
                return true;
            });
            playerVideo.setOnCompletionListener(mp -> {
                playerAudioToggle.setImageResource(R.drawable.ic_resume);
                ui.removeCallbacks(progressTick);
            });
            playerVideo.start();
        } else if (type == TYPE_AUDIO) {
            playerAudioTitle.setText(title);
            playerAudioTime.setText("音频播放");
            prepareAudio(source, local);
        } else {
            playerImage.setImageDrawable(null);
            playerImage.setBackgroundColor(Color.BLACK);
            if (local || isContent) {
                Bitmap b;
                if (isContent) {
                    try {
                        InputStream in = getContentResolver().openInputStream(Uri.parse(source));
                        BitmapFactory.Options opt = new BitmapFactory.Options();
                        opt.inSampleSize = 2;
                        b = in == null ? null : BitmapFactory.decodeStream(in, null, opt);
                        if (in != null) in.close();
                    } catch (Exception e) {
                        b = null;
                    }
                } else {
                    // 原图可能有几千万像素，按 2400px 采样既清晰又不 OOM
                    b = decodeSampled(new File(source), 2400);
                }
                if (b != null) {
                    playerImage.setImageBitmap(b);
                    playerImage.post(this::applyImageFit);
                } else Toast.makeText(this, "图片解码失败", Toast.LENGTH_SHORT).show();
            } else {
                new Thread(() -> {
                    Bitmap b = fetchBitmap(source, 1);
                    ui.post(() -> {
                        if (b != null) {
                            playerImage.setImageBitmap(b);
                            playerImage.post(this::applyImageFit);
                        } else Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
                    });
                }, "vsp-view-image").start();
            }
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
        StringBuilder sb = new StringBuilder();
        int dur = 0;
        if (mp != null) {
            try {
                dur = mp.getDuration();
            } catch (Throwable ignored) {
            }
        }
        MediaItem m = currentWatchItem();
        if (dur <= 0 && m != null && m.duration > 0) dur = (int) m.duration;
        if (dur > 0) sb.append(fmtTime(dur));
        if (mp != null) {
            try {
                int w = mp.getVideoWidth();
                int h = mp.getVideoHeight();
                if (w > 0 && h > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(w).append("×").append(h);
                }
            } catch (Throwable ignored) {
            }
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
                openPlayer(m.source, m.type >= 0 ? m.type : guessType(m.name),
                        m.name, true, watchSiblings, pos);
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
                        // 未放大时左右一划就切上一张 / 下一张（相册的基本手势）
                        if (e1 == null || e2 == null) return false;
                        if (imageScale > imageBaseScale() + 0.02f) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) < dp(56)) return false;
                        if (Math.abs(dx) < Math.abs(dy) * 1.4f) return false;
                        if (Math.abs(vx) < 200f) return false;
                        stepWatch(dx < 0 ? 1 : -1);
                        return true;
                    }
                });
        playerImage.setOnTouchListener((v, ev) -> {
            if (playerType != TYPE_IMAGE) return false;
            if (imageScaleDetector != null) imageScaleDetector.onTouchEvent(ev);
            if (imageGestureDetector != null) imageGestureDetector.onTouchEvent(ev);
            return true;
        });
    }

    /** 在看图 / 看视频时切到相邻的一项（左滑下一张，右滑上一张） */
    private void stepWatch(int delta) {
        if (watchSiblings == null || watchSiblings.isEmpty() || watchIndex < 0) return;
        int next = watchIndex + delta;
        if (next < 0) {
            showTopHint("已经是第一张");
            return;
        }
        if (next >= watchSiblings.size()) {
            showTopHint("已经是最后一张");
            return;
        }
        MediaItem m = watchSiblings.get(next);
        openPlayer(m.source, m.type >= 0 ? m.type : guessType(m.name), m.name, true,
                watchSiblings, next);
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
        playerImage.post(this::applyImageFit);
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
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", UA);
                String ref = refererFor(source);
                if (ref != null) headers.put("Referer", ref);
                mp.setDataSource(this, Uri.parse(source), headers);
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
                Toast.makeText(this, "无法播放该音频", Toast.LENGTH_SHORT).show();
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
    private String refererFor(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return null;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.contains("bilibili") || h.contains("bilivideo") || h.contains("b23.tv"))
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
            return u.getScheme() + "://" + host + "/";
        } catch (Throwable t) {
            return null;
        }
    }

    private void applyPlaybackSettings(MediaPlayer mp) {
        if (mp == null) return;
        try {
            mp.setVolume(playVolume, playVolume);
        } catch (Throwable ignored) {
        }
        // 1.0x 时不要碰 PlaybackParams：部分机型只要调过一次
        // 就会把音频输出切走，表现正好是「有画面、没声音」。
        float speed = SPEEDS[speedIndex];
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
        MediaPlayer mp = currentMp;
        if (mp != null) {
            try {
                // 切回 1.0x 也要设一次，把之前调过的倍速恢复掉
                applySpeed(mp);
            } catch (Throwable ignored) {
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
        // 已经在下载更新时，不重复检查、也不允许再触发一次下载
        if (updating || UpdateService.running) {
            showUpdateProgress();
            showTopHint("正在更新中，请等当前下载结束");
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
            UpdateInfo best = pickNewer(gh, ge);
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
        if (apk.isEmpty()) apk = "dist/VSP-Parse-v" + ver + ".apk";
        info.apkUrl = GITEE_DIST + apk;
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

    private void showUpdateResult() {
        String cur = versionName();
        boolean newer = compareVersion(latestVersion, cur) > 0;
        if (!newer) {
            // 已是最新：只在顶部轻提示一句，不弹仓库/版本信息框
            showTopHint("当前已是最新版本 v" + cur);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前版本 v").append(cur).append(" → 最新版本 v").append(latestVersion).append("\n");
        if (latestSize > 0) sb.append("安装包大小：").append(humanSize(latestSize)).append("\n");
        if (!latestDate.isEmpty()) sb.append("发布时间：").append(shortDate(latestDate)).append("\n");
        sb.append('\n');
        if (!latestNotes.isEmpty()) {
            String notes = latestNotes.length() > 1400 ? latestNotes.substring(0, 1400) + "…" : latestNotes;
            sb.append("更新内容：\n").append(notes);
        } else {
            sb.append("该版本没有填写更新说明。");
        }
        String page = "gitee".equals(latestSource) ? GITEE_PAGE : UPDATE_PAGE;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + latestVersion)
                .setMessage(sb.toString())
                .setPositiveButton(latestApkUrl.isEmpty() ? "打开下载页" : "立即更新", (d, w) -> {
                    if (latestApkUrl.isEmpty()) openUrl(page);
                    else downloadAndInstall(latestApkUrl, latestVersion);
                })
                .setNegativeButton("稍后", null);
        if (latestApkUrl.isEmpty()) {
            b.setNeutralButton("打开仓库", (d, w) -> openUrl(page));
        }
        b.show();
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
        if (updating || UpdateService.running) {
            showUpdateProgress();
            Toast.makeText(this, "正在更新中，请等下载结束", Toast.LENGTH_SHORT).show();
            return;
        }
        if (apkUrl == null || apkUrl.isEmpty()) {
            openUrl(UPDATE_PAGE);
            return;
        }
        ensureNotificationPermission();
        updating = true;
        UpdateService.start(this, apkUrl, ver);
        showUpdateProgress();
        pollUpdateProgress();
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
        if (updateDialog != null && updateDialog.isShowing()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(4));

        updateDialogPct = new TextView(this);
        updateDialogPct.setText(UpdateService.percent + "%");
        updateDialogPct.setTextColor(P.text);
        updateDialogPct.setTextSize(24);
        updateDialogPct.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(updateDialogPct);

        updateDialogBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateDialogBar.setMax(100);
        updateDialogBar.setProgress(UpdateService.percent);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        blp.topMargin = dp(12);
        box.addView(updateDialogBar, blp);

        updateDialogText = new TextView(this);
        updateDialogText.setText("正在连接…");
        updateDialogText.setTextColor(P.dim);
        updateDialogText.setTextSize(12);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        box.addView(updateDialogText, tlp);

        // 速度单独一行：通知栏与弹窗都能看到「已下载 / 总大小」和实时速度
        updateDialogSpeed = new TextView(this);
        updateDialogSpeed.setText("—");
        updateDialogSpeed.setTextColor(P.accent);
        updateDialogSpeed.setTextSize(12);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        box.addView(updateDialogSpeed, slp);

        updateDialog = new AlertDialog.Builder(this)
                .setTitle("正在下载新版本")
                .setView(box)
                .setPositiveButton("取消下载", (d, w) -> cancelUpdateDownload())
                .setNegativeButton("后台继续", null)
                .setCancelable(false)
                .create();
        updateDialog.show();
    }

    private void dismissUpdateDialog() {
        try {
            if (updateDialog != null && updateDialog.isShowing()) updateDialog.dismiss();
        } catch (Throwable ignored) {
        }
        updateDialog = null;
        updateDialogPct = null;
        updateDialogBar = null;
        updateDialogText = null;
        updateDialogSpeed = null;
    }

    private void updateProgressUi(int pct, long got, long total, String speed) {
        if (updateDialogPct != null) updateDialogPct.setText(pct + "%");
        if (updateDialogBar != null) updateDialogBar.setProgress(pct);
        if (updateDialogText != null) {
            updateDialogText.setText(total > 0
                    ? humanSize(got) + " / " + humanSize(total)
                    : "已下载 " + humanSize(got));
        }
        if (updateDialogSpeed != null) {
            updateDialogSpeed.setText(speed == null || speed.isEmpty() ? "—" : speed);
        }
    }

    /** 轮询前台服务的下载状态，弹窗跟着刷新 */
    private void pollUpdateProgress() {
        if (updateDialog == null) return;
        updateProgressUi(UpdateService.percent, UpdateService.downloaded,
                UpdateService.totalSize, UpdateService.speedText);
        String st = UpdateService.state;
        if ("done".equals(st)) {
            finishUpdate(true);
            return;
        }
        if ("failed".equals(st)) {
            finishUpdate(false);
            return;
        }
        if (!UpdateService.running) {
            updating = false;
            dismissUpdateDialog();
            return;
        }
        ui.postDelayed(this::pollUpdateProgress, 400);
    }

    private void finishUpdate(boolean ok) {
        updating = false;
        String path = UpdateService.savedPath;
        String err = UpdateService.errorText;
        UpdateService.state = "idle";
        dismissUpdateDialog();
        if (!ok) {
            Toast.makeText(this, err == null || err.isEmpty()
                    ? "更新包下载失败，请稍后重试" : "下载失败：" + err, Toast.LENGTH_LONG).show();
            return;
        }
        File f = new File(path == null ? "" : path);
        if (!f.exists() || f.length() < 1024 * 200) {
            Toast.makeText(this, "更新包不完整，请重新下载", Toast.LENGTH_LONG).show();
            return;
        }
        installApk(f);
    }

    private void cancelUpdateDownload() {
        UpdateService.cancel(this);
        updating = false;
        dismissUpdateDialog();
        Toast.makeText(this, "已取消下载更新", Toast.LENGTH_SHORT).show();
    }

    /** 退出重进时如果后台还在下载，直接把进度弹窗接回来 */
    private void resumePendingUpdate() {
        if (!UpdateService.running) return;
        updating = true;
        showUpdateProgress();
        pollUpdateProgress();
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
                h.status.setText("paused".equals(status)
                        ? "已暂停 · " + pct + "%" : pct + "%");
                // 「已下载 / 总大小 · 速度」：正在下的任务要能看到具体数字
                String sizeLine;
                if (total > 0) sizeLine = humanSize(done) + " / " + humanSize(total);
                else if (done > 0) sizeLine = "已下载 " + humanSize(done);
                else sizeLine = "正在连接…";
                if (!speed.isEmpty() && !"已暂停".equals(speed)) sizeLine += "  ·  " + speed;
                h.size.setText(sizeLine);
                h.size.setVisibility(View.VISIBLE);
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
            TextView title, status, open, size;
            ImageView thumb, pause;
            CheckBox check;
            ProgressBar progress;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.t_title);
                status = v.findViewById(R.id.t_status);
                size = v.findViewById(R.id.t_size);
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
        if (taskPoller != null) taskPoller.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        stopPlayer();
    }
}
