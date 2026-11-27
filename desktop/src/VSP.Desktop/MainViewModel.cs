using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows.Input;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Platform.Storage;
using Avalonia.Media.Imaging;
using VSP.Core;
using VSP.Desktop.Views;

namespace VSP.Desktop;

public sealed class FormatItemViewModel : IDisposable
{
    private readonly MainViewModel _owner;
    public MediaFormat Format { get; }
    public string Quality => string.IsNullOrWhiteSpace(Format.Quality) ? Format.Ext.ToUpperInvariant() : Format.Quality;
    public string Container => Format.Container.ToUpperInvariant();
    public bool HasContainer => !string.IsNullOrWhiteSpace(Container);
    public string Resolution => Format.Width > 0 && Format.Height > 0 ? $"{Format.Width}×{Format.Height}" : "";
    public bool HasResolution => !string.IsNullOrWhiteSpace(Resolution);
    public string VideoCodec => ShortCodec(Format.Vcodec);
    public string AudioCodec => ShortCodec(Format.Acodec);
    public bool HasVideoCodec => !string.IsNullOrWhiteSpace(VideoCodec);
    public bool HasAudioCodec => !string.IsNullOrWhiteSpace(AudioCodec);
    public string Bitrate => Format.Bitrate > 0 ? $"{Format.Bitrate / 1000}k" : "";
    public bool HasBitrate => !string.IsNullOrWhiteSpace(Bitrate);
    public string Size => string.IsNullOrWhiteSpace(Format.Size) || Format.Size == "-" ? "" : Format.Size;
    public bool HasSize => !string.IsNullOrWhiteSpace(Size);
    public string Detail => Format.Detail;

    public bool HasRowTask => _owner.TaskFor(Format) is not null;
    public bool IsRowRunning => _owner.TaskFor(Format)?.Status == "running";
    public bool IsRowDone => _owner.TaskFor(Format)?.Status == "done";
    public bool IsRowError => _owner.TaskFor(Format)?.Status == "error";
    public bool CanPreviewAndDownload => !HasRowTask || IsRowError;
    public bool CanDeleteRow => IsRowDone || IsRowError;
    public double RowProgress => _owner.TaskFor(Format)?.Progress ?? 0;
    public string RowStateText
    {
        get
        {
            var task = _owner.TaskFor(Format);
            if (task is null) return "";
            return task.Status switch
            {
                "running" => $"{task.Progress:0}%" + (string.IsNullOrWhiteSpace(task.Speed) ? "" : " · " + task.Speed),
                "done" => "已存入下载目录",
                "error" => string.IsNullOrWhiteSpace(task.Error) ? "失败" : task.Error,
                _ => task.StatusText,
            };
        }
    }
    public bool IsErrorRow => IsRowError;

    public ICommand PreviewCommand { get; }
    public ICommand DownloadCommand { get; }
    public ICommand CancelRowCommand { get; }
    public ICommand DeleteRowCommand { get; }

    public FormatItemViewModel(MainViewModel owner, MediaFormat format)
    {
        _owner = owner;
        Format = format;
        PreviewCommand = new RelayCommand(() => _ = owner.PreviewAsync(format));
        DownloadCommand = new RelayCommand(() => owner.Download(format));
        CancelRowCommand = new RelayCommand(() => { if (_owner.TaskFor(format) is { } t) owner.CancelTask(t); });
        DeleteRowCommand = new RelayCommand(() => { if (_owner.TaskFor(format) is { } t) owner.RemoveTask(t); });
        owner.FormatTasksChanged += OnFormatTasksChanged;
    }

    private static string ShortCodec(string codec)
    {
        if (string.IsNullOrWhiteSpace(codec) || codec == "none") return "";
        var i = codec.IndexOf('.');
        return i >= 0 ? codec[..i] : codec;
    }

    private void OnFormatTasksChanged()
    {
        foreach (var name in new[] { nameof(HasRowTask), nameof(IsRowRunning), nameof(IsRowDone),
                                     nameof(IsRowError), nameof(CanPreviewAndDownload), nameof(CanDeleteRow),
                                     nameof(RowProgress), nameof(RowStateText), nameof(IsErrorRow) })
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    public void Dispose() => _owner.FormatTasksChanged -= OnFormatTasksChanged;
}

public sealed class TaskItemViewModel : IDisposable
{
    private readonly MainViewModel _owner;
    public DownloadTaskInfo Task { get; }
    public string Title => Task.Title;
    public string StatusText => Task.StatusText;
    public string Speed => Task.Speed;
    public string Error => Task.Error;
    public double Progress => Task.Progress;
    public string ProgressText => $"{Task.Progress:0.0}% · {Task.SizeText}";
    public bool IsRunning => Task.Status == "running";
    public bool IsPaused => Task.Status == "paused";
    public bool IsDone => Task.Status == "done";
    public bool HasError => !string.IsNullOrEmpty(Task.Error) || Task.Status == "error";
    public bool HasSpeed => !string.IsNullOrWhiteSpace(Task.Speed);
    public ICommand PauseCommand { get; }
    public ICommand ResumeCommand { get; }
    public ICommand CancelCommand { get; }
    public ICommand RetryCommand { get; }
    public ICommand RemoveCommand { get; }
    public ICommand OpenFileCommand { get; }

    public TaskItemViewModel(MainViewModel owner, DownloadTaskInfo task)
    {
        _owner = owner;
        Task = task;
        task.PropertyChanged += OnTaskChanged;
        PauseCommand = new RelayCommand(() => owner.PauseTask(task));
        ResumeCommand = new RelayCommand(() => owner.ResumeTask(task));
        CancelCommand = new RelayCommand(() => owner.CancelTask(task));
        RetryCommand = new RelayCommand(() => owner.RetryTask(task));
        RemoveCommand = new RelayCommand(() => owner.RemoveTask(task));
        OpenFileCommand = new RelayCommand(() => owner.OpenFile(task));
    }

    private void OnTaskChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is null) return;
        if (e.PropertyName is nameof(DownloadTaskInfo.Status) or nameof(DownloadTaskInfo.Error))
        {
            foreach (var name in new[] { nameof(StatusText), nameof(IsRunning), nameof(IsPaused),
                                         nameof(IsDone), nameof(HasError), nameof(Error) })
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        }
        if (e.PropertyName is nameof(DownloadTaskInfo.Progress) or nameof(DownloadTaskInfo.DownloadedBytes)
            or nameof(DownloadTaskInfo.TotalBytes))
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Progress)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(ProgressText)));
        }
        if (e.PropertyName == nameof(DownloadTaskInfo.Speed))
        {
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Speed)));
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(HasSpeed)));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    public void Dispose() => Task.PropertyChanged -= OnTaskChanged;
}

public sealed class LocalItemViewModel
{
    private readonly MainViewModel _owner;
    public LocalMedia Media { get; }
    public string Name => Media.Name;
    public string SizeText => Media.SizeText;
    public string ModifiedText => Media.Modified.ToString("yyyy-MM-dd HH:mm");
    public ICommand PreviewCommand { get; }

    public LocalItemViewModel(MainViewModel owner, LocalMedia media)
    {
        _owner = owner;
        Media = media;
        PreviewCommand = new RelayCommand(() => _ = owner.PreviewLocalAsync(media));
    }
}

public sealed class RecentItemViewModel
{
    private readonly MainViewModel _owner;
    public string Url { get; }
    public ICommand UseCommand { get; }
    public RecentItemViewModel(MainViewModel owner, string url)
    {
        _owner = owner;
        Url = url;
        UseCommand = new RelayCommand(() => _owner.UseRecent(url));
    }
}

public sealed class MainViewModel : BindableBase
{
    private readonly AppServices _services;
    private readonly HttpClient _http = new(new HttpClientHandler { AllowAutoRedirect = true });
    private readonly Dictionary<MediaFormat, string> _formatTasks = [];
    private ParseResult? _result;
    private string _url = "";
    private string _status = "";
    private Bitmap? _coverImage;
    private bool _isBusy;
    private int _selectedTab;
    private UpdateInfo? _update;

    public ObservableCollection<FormatItemViewModel> CurrentFormats { get; } = [];
    public ObservableCollection<TaskItemViewModel> TaskItems { get; } = [];
    public ObservableCollection<LocalItemViewModel> LocalItems { get; } = [];
    public ObservableCollection<RecentItemViewModel> RecentItems { get; } = [];

    public event Action? FormatTasksChanged;
    public event Action? HistoryItemPicked;

    public string Url { get => _url; set => Set(ref _url, value); }
    public string Status { get => _status; private set { if (Set(ref _status, value)) { Raise(nameof(HasStatus)); Raise(nameof(IsErrorStatus)); } } }
    public bool HasStatus => !string.IsNullOrEmpty(Status);
    public bool IsErrorStatus => Status.StartsWith("失败", StringComparison.Ordinal);
    public bool IsInfoStatus => HasStatus && !IsErrorStatus;
    /// <summary>解析结果封面（Bitmap，XAML 可直接绑定 Image.Source）。</summary>
    public Bitmap? CoverImage
    {
        get => _coverImage;
        private set
        {
            if (ReferenceEquals(_coverImage, value)) return;
            var old = _coverImage;
            _coverImage = value;
            Raise(nameof(CoverImage));
            old?.Dispose();
        }
    }
    /// <summary>个人头像：与手机端一样支持更换和恢复默认，存到 %APPDATA%\VSP解析。</summary>
    private Bitmap? _avatarImage;
    public Bitmap? AvatarImage
    {
        get => _avatarImage;
        private set
        {
            if (ReferenceEquals(_avatarImage, value)) return;
            var old = _avatarImage;
            _avatarImage = value;
            Raise(nameof(AvatarImage));
            Raise(nameof(HasAvatar));
            old?.Dispose();
        }
    }
    public bool HasAvatar => _avatarImage is not null;
    private static string AvatarFile => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "VSP解析", "avatar.img");
    public string AvatarHint => HasAvatar ? "点击头像可再次更换 · 长按恢复默认" : "点击头像可更换 · 长按恢复默认";

    public bool IsBusy { get => _isBusy; set => Set(ref _isBusy, value); }
    public bool HasResult => _result is not null;
    public string ResultTitle => _result?.Title ?? "";
    public string ResultUploader => _result?.Uploader ?? "";
    public bool HasUploader => !string.IsNullOrWhiteSpace(ResultUploader);
    public string ResultDurationText => _result is { Duration: > 0 } ? MediaUtil.FormatDuration(_result.Duration) : "";
    public bool HasDuration => !string.IsNullOrWhiteSpace(ResultDurationText);
    public string ResultDescription => _result?.Description ?? "";
    public bool HasDescription => !string.IsNullOrWhiteSpace(ResultDescription);
    public string ResultMeta => _result is null ? "" :
        string.Join(" · ", new[] { _result.Uploader, MediaUtil.FormatDuration(_result.Duration), _result.Source }
                              .Where(x => !string.IsNullOrWhiteSpace(x)));

    public int SelectedTab { get => _selectedTab; private set { if (Set(ref _selectedTab, value)) { RefreshFormats(); RaiseTabs(); } } }
    public bool IsVideoTab => SelectedTab == 0;
    public bool IsAudioTab => SelectedTab == 1;
    public bool IsImageTab => SelectedTab == 2;
    public string VideoTabLabel => $"视频 ({_result?.Videos.Count ?? 0})";
    public string AudioTabLabel => $"音频 ({_result?.Audios.Count ?? 0})";
    public string ImageTabLabel => $"图片 ({_result?.Images.Count ?? 0})";
    public string FormatCountText => $"共 {CurrentFormats.Count} 项";

    public string DownloadDir { get => _services.Settings.DownloadDir; set => _services.Settings.DownloadDir = value; }

    public int MaxConcurrentDownloads
    {
        get => _services.Settings.MaxConcurrentDownloads;
        set { _services.Settings.MaxConcurrentDownloads = value; Raise(nameof(MaxConcurrentDownloads)); }
    }
    public int SpeedLimitKbps
    {
        get => _services.Settings.SpeedLimitKbps;
        set { _services.Settings.SpeedLimitKbps = value; Raise(nameof(SpeedLimitKbps)); }
    }
    public string NameTemplate
    {
        get => _services.Settings.NameTemplate;
        set { _services.Settings.NameTemplate = value; Raise(nameof(NameTemplate)); }
    }
    public bool ClassifyAfterDownload
    {
        get => _services.Settings.ClassifyAfterDownload;
        set
        {
            _services.Settings.ClassifyAfterDownload = value;
            Raise(nameof(ClassifyAfterDownload));
        }
    }
    public int ParseTtlSeconds
    {
        get => _services.Settings.ParseTtlSeconds;
        set { _services.Settings.ParseTtlSeconds = value; Raise(nameof(ParseTtlSeconds)); }
    }
    public string EngineStatus => _services.Engine.IsReady
        ? "解析引擎已就绪（内嵌 Python 3.13 + yt-dlp，与 App / Web 同源）"
        : "解析引擎缺失：请重新安装本程序";
    public bool IsDarkTheme
    {
        get => _services.Settings.Theme == "Dark";
        set
        {
            var target = value ? "Dark" : "Light";
            if (_services.Settings.Theme == target) return;
            _services.Settings.Theme = target;
            App.ApplyTheme(target);
            Raise(nameof(IsDarkTheme)); Raise(nameof(ThemeText)); Raise(nameof(ThemeIcon));
        }
    }
    public string ThemeText => IsDarkTheme ? "深色" : "浅色";
    public Avalonia.Media.Geometry? ThemeIcon =>
        Application.Current?.FindResource(IsDarkTheme ? "IconMoon" : "IconSun") as Avalonia.Media.Geometry;
    public string AppVersion => $"v{UpdateService.Version}";
    public string UpdateStatus { get => _updateStatus; private set => Set(ref _updateStatus, value); }
    private string _updateStatus = "尚未检查更新";
    // 下载中不再显示"下载更新"按钮，进度条本身已经说明状态。
    public bool HasUpdate => _update is not null && !IsUpdating;
    public bool IsUpdating
    {
        get => _isUpdating;
        private set { if (Set(ref _isUpdating, value)) Raise(nameof(HasUpdate)); }
    }
    private bool _isUpdating;
    public double UpdateProgress { get => _updateProgress; private set => Set(ref _updateProgress, value); }
    private double _updateProgress;

    public bool HasNoTasks => TaskItems.Count == 0;
    public bool HasNoLocal => LocalItems.Count == 0;
    public bool HasRecent => RecentItems.Count > 0;
    public string RecentSummary => $"{RecentItems.Count} 条";
    public string LocalSummary => $"共 {LocalItems.Count} 个媒体文件";
    public string TaskSummary => TaskItems.Count == 0 ? "暂无任务" : $"{TaskItems.Count} 个任务";

    // 存储管理（对应 App 端存储页）
    public string StorageDiskText { get => _storageDisk; private set => Set(ref _storageDisk, value); }
    private string _storageDisk = "";
    public string StoragePercentText { get => _storagePercent; private set => Set(ref _storagePercent, value); }
    private string _storagePercent = "";
    public double StorageUsage { get => _storageUsage; private set => Set(ref _storageUsage, value); }
    private double _storageUsage;
    public string StorageDownloadText { get => _storageDownload; private set => Set(ref _storageDownload, value); }
    private string _storageDownload = "";
    public string StorageCacheText { get => _storageCache; private set => Set(ref _storageCache, value); }
    private string _storageCache = "";
    public string StorageTotalText { get => _storageTotal; private set => Set(ref _storageTotal, value); }
    private string _storageTotal = "";
    public bool HasCache { get => _hasCache; private set => Set(ref _hasCache, value); }
    private bool _hasCache;

    public ICommand ShowHomeCommand { get; }
    public ICommand ShowLocalCommand { get; }
    public ICommand ShowMeCommand { get; }
    public ICommand ShowQueueCommand { get; }
    public ICommand ShowStorageCommand { get; }
    public ICommand ShowHistoryCommand { get; }
    public ICommand ClearHistoryCommand { get; }
    public ICommand ClosePreviewCommand { get; }
    public ICommand BackCommand { get; }
    public ICommand ParseCommand { get; }
    public ICommand ClearCommand { get; }
    public ICommand SelectTabCommand { get; }
    public ICommand OpenDownloadDirCommand { get; }
    public ICommand RefreshLocalCommand { get; }
    public ICommand ClearFinishedCommand { get; }
    public ICommand RefreshStorageCommand { get; }
    public ICommand ClearCacheCommand { get; }
    public ICommand PickFolderCommand { get; }
    public ICommand ApplyDownloadDirCommand { get; }
    public ICommand ToggleThemeCommand { get; }
    public ICommand ToggleClassifyCommand { get; }
    public ICommand ScanDuplicatesCommand { get; }
    public ICommand ShowRulesCommand { get; }
    public ICommand CheckUpdateCommand { get; }
    public ICommand DownloadUpdateCommand { get; }
    public ICommand OpenReleaseCommand { get; }
    public ICommand OpenGiteeCommand { get; }
    public ICommand OpenGithubCommand { get; }
    public ICommand ChangeAvatarCommand { get; }
    public ICommand ResetAvatarCommand { get; }
    public ICommand ShowDownloadSettingsCommand { get; }

    // ---------------- 局域网互传与远控 ----------------
    private LanService? _lan;
    public ObservableCollection<LanDevice> LanDevices { get; } = [];
    public bool HasNoLanDevices => LanDevices.Count == 0;
    public ObservableCollection<LanFileEntry> LanRemoteFiles { get; } = [];
    public LanDevice? SelectedLanDevice { get => _selectedLan; set { if (Set(ref _selectedLan, value)) { _lanDirStack.Clear(); _ = RefreshLanFilesAsync(); Raise(nameof(HasSelectedLanDevice)); Raise(nameof(LanBreadcrumb)); } } }
    private LanDevice? _selectedLan;
    public bool HasSelectedLanDevice => _selectedLan is not null;
    public string LanLocalIp => _lan?.LocalIp ?? "启动中…";
    public string LanStatus { get => _lanStatus; private set => Set(ref _lanStatus, value); }
    private string _lanStatus = "正在发现附近设备…";
    public string LanBreadcrumb => _lanDirStack.Count == 0 ? "共享目录" : "共享目录 / " + string.Join(" / ", _lanDirStack);
    private readonly Stack<string> _lanDirStack = new();
    public bool LanBusy { get => _lanBusy; private set => Set(ref _lanBusy, value); }
    private bool _lanBusy;
    /// <summary>互传进度（0~1），由 LanView 显示为进度条。</summary>
    public double LanTransferProgress { get => _lanTransferProgress; private set => Set(ref _lanTransferProgress, value); }
    private double _lanTransferProgress;
    public bool LanTransferring { get => _lanTransferring; private set => Set(ref _lanTransferring, value); }
    private bool _lanTransferring;
    /// <summary>收到文件时的通知，MainWindow 用它弹应用内通知（即使停在别的页面也能看到）。</summary>
    public Action<string>? LanNotification;
    /// <summary>远控画面：由 LanView 把字节转成 Bitmap 显示。</summary>
    public byte[]? LanScreenBytes { get => _lanScreenBytes; private set => Set(ref _lanScreenBytes, value); }
    private byte[]? _lanScreenBytes;
    public bool IsRemoteControlling { get => _remoteCtl; private set => Set(ref _remoteCtl, value); }
    private bool _remoteCtl;
    /// <summary>正在等待对方同意远控申请（尚未开始拉画面）。</summary>
    public bool LanControlPending { get => _lanControlPending; private set => Set(ref _lanControlPending, value); }
    private bool _lanControlPending;
    public ICommand ShowLanCommand { get; }
    public ICommand SelectLanDeviceCommand { get; }
    public ICommand LanOpenDirCommand { get; }
    public ICommand LanBackDirCommand { get; }
    public ICommand LanDownloadCommand { get; }
    public ICommand LanUploadCommand { get; }
    public ICommand LanStartControlCommand { get; }
    public ICommand LanStopControlCommand { get; }
    public ICommand LanTapCommand { get; }
    public ICommand LanSwipeCommand { get; }
    public ICommand LanKeyCommand { get; }
    public ICommand OpenLanDevicesCommand { get; }
    public ICommand LanConnectCommand { get; }
    public ICommand LanAcceptPairCommand { get; }
    public ICommand LanRejectPairCommand { get; }
    public ICommand LanAcceptControlCommand { get; }
    public ICommand LanRejectControlCommand { get; }
    public ICommand LanDisconnectCommand { get; }
    private CancellationTokenSource? _lanConnectCts;
    private CancellationTokenSource? _lanControlCts;
    private string? _lanControlCid;
    /// <summary>收到的连接申请（弹窗显示）。</summary>
    public LanPairRequest? IncomingPair { get => _incomingPair; private set => Set(ref _incomingPair, value); }
    private LanPairRequest? _incomingPair;
    /// <summary>收到的远程控制申请（弹窗显示）。</summary>
    public LanControlRequest? IncomingControl { get => _incomingControl; private set => Set(ref _incomingControl, value); }
    private LanControlRequest? _incomingControl;
    /// <summary>是否正在等待对方同意我的连接申请。</summary>
    public bool LanPairing { get => _lanPairing; private set => Set(ref _lanPairing, value); }
    private bool _lanPairing;
    /// <summary>UI 事件：请求打开「附近设备」弹窗。</summary>
    public event Action? RequestOpenLanDevices;
    /// <summary>UI 事件：收到连接申请，请求弹窗确认。</summary>
    public event Action? RequestPairDialog;
    /// <summary>UI 事件：收到远程控制申请，请求弹窗确认。</summary>
    public event Action? RequestControlDialog;
    /// <summary>UI 事件：远控申请审批完成后关闭弹窗。</summary>
    public event Action? RequestCloseControlDialog;

    public bool IsHome => _page == 0;
    public bool IsLocal => _page == 1;
    public bool IsMePage => _page == 2;
    public bool IsQueuePage => _page == 3;
    public bool IsStoragePage => _page == 4;
    public bool IsPreviewPage => _page == 5;
    public bool IsLanPage => _page == 6;
    public bool IsMeNav => _page >= 2 && _page != 5;
    private int _page;
    private int _returnPage;
    private Views.PreviewViewModel? _preview;
    public Views.PreviewViewModel? Preview { get => _preview; private set => Set(ref _preview, value); }
    public bool IsPlayerFullScreen { get => _isPlayerFullScreen; private set => Set(ref _isPlayerFullScreen, value); }
    private bool _isPlayerFullScreen;

    public MainViewModel(AppServices services)
    {
        _services = services;
        ShowHomeCommand = new RelayCommand(() => Go(0));
        ShowLocalCommand = new RelayCommand(() => { Go(1); RefreshLocal(); });
        ShowMeCommand = new RelayCommand(() => { Go(2); RefreshLocal(); RefreshStorage(); });
        ShowQueueCommand = new RelayCommand(() => { Go(3); RefreshLocal(); });
        ShowStorageCommand = new RelayCommand(() => { Go(4); RefreshLocal(); RefreshStorage(); });
        ShowHistoryCommand = new RelayCommand(ShowHistory);
        ClearHistoryCommand = new RelayCommand(ClearHistory);
        ClosePreviewCommand = new RelayCommand(ClosePreview);
        BackCommand = new RelayCommand(() => Go(2));
        ParseCommand = new RelayCommand(() => _ = ParseAsync());
        ClearCommand = new RelayCommand(Clear);
        SelectTabCommand = new RelayCommand(p => { if (int.TryParse(p?.ToString(), out var tab)) SelectedTab = tab; });
        OpenDownloadDirCommand = new RelayCommand(() => LocalLibrary.OpenFolder(DownloadDir));
        RefreshLocalCommand = new RelayCommand(RefreshLocal);
        ClearFinishedCommand = new RelayCommand(() => { _services.Downloads.ClearFinished(); RebuildTasks(); });
        RefreshStorageCommand = new RelayCommand(RefreshStorage);
        ClearCacheCommand = new RelayCommand(ClearCache);
        PickFolderCommand = new RelayCommand(() => _ = PickFolderAsync());
        ApplyDownloadDirCommand = new RelayCommand(() => { Directory.CreateDirectory(DownloadDir); RefreshLocal(); Status = "下载目录已更新"; });
        ToggleThemeCommand = new RelayCommand(ToggleTheme);
        ToggleClassifyCommand = new RelayCommand(() => ClassifyAfterDownload = !ClassifyAfterDownload);
        ScanDuplicatesCommand = new RelayCommand(() => _ = ScanDuplicatesAsync());
        ShowRulesCommand = new RelayCommand(() => _ = ShowRulesAsync());
        CheckUpdateCommand = new RelayCommand(() => _ = CheckUpdateAsync());
        DownloadUpdateCommand = new RelayCommand(() => _ = DownloadUpdateAsync());
        OpenReleaseCommand = new RelayCommand(() => LocalLibrary.OpenUrl(UpdateService.PageUrl));
        OpenGiteeCommand = new RelayCommand(() => LocalLibrary.OpenUrl("https://gitee.com/tang-rongqiang/VSP"));
        OpenGithubCommand = new RelayCommand(() => LocalLibrary.OpenUrl("https://github.com/tang536/VSP-"));
        ChangeAvatarCommand = new RelayCommand(() => _ = ChangeAvatarAsync());
        ResetAvatarCommand = new RelayCommand(ResetAvatar);
        ShowDownloadSettingsCommand = new RelayCommand(() => _ = ShowDownloadSettingsAsync());
        LoadAvatar();

        // 局域网互传与远控
        ShowLanCommand = new RelayCommand(() => { Go(6); RefreshLanDevices(); });
        SelectLanDeviceCommand = new RelayCommand(p => { if (p is LanDevice d) SelectedLanDevice = d; });
        LanOpenDirCommand = new RelayCommand(p => { if (p is LanFileEntry e && e.IsDir) { _lanDirStack.Push(DecodeLan(e.Path)!); _ = RefreshLanFilesAsync(); Raise(nameof(LanBreadcrumb)); } });
        LanBackDirCommand = new RelayCommand(() => { if (_lanDirStack.Count > 0) { _lanDirStack.Pop(); _ = RefreshLanFilesAsync(); Raise(nameof(LanBreadcrumb)); } });
        LanDownloadCommand = new RelayCommand(p => { if (p is LanFileEntry e && !e.IsDir) _ = DownloadLanAsync(e); });
        LanUploadCommand = new RelayCommand(() => _ = UploadLanAsync());
        LanStartControlCommand = new RelayCommand(() => _ = StartLanControlAsync());
        LanStopControlCommand = new RelayCommand(StopLanControl);
        LanTapCommand = new RelayCommand(p => _ = LanTapAsync(p));
        LanSwipeCommand = new RelayCommand(p => _ = LanSwipeAsync(p));
        LanKeyCommand = new RelayCommand(p => _ = LanSendKeyAsync(p?.ToString() ?? ""));
        OpenLanDevicesCommand = new RelayCommand(() => RequestOpenLanDevices?.Invoke());
        LanConnectCommand = new RelayCommand(p => { if (p is LanDevice d) _ = LanConnectAsync(d); });
        LanAcceptPairCommand = new RelayCommand(() => DecideIncomingPair(true));
        LanRejectPairCommand = new RelayCommand(() => DecideIncomingPair(false));
        LanAcceptControlCommand = new RelayCommand(() => DecideIncomingControl(true));
        LanRejectControlCommand = new RelayCommand(() => DecideIncomingControl(false));
        LanDisconnectCommand = new RelayCommand(DisconnectLan);
        StartLan();

        _services.Downloads.Tasks.CollectionChanged += (_, _) =>
        {
            RebuildTasks();
            FormatTasksChanged?.Invoke();
            Raise(nameof(HasNoTasks));
            Raise(nameof(TaskSummary));
        };
        RebuildRecent();
        RefreshLocal();
        RefreshStorage();
        if (!_services.Engine.IsReady) Status = "失败：解析引擎缺失，请先重新安装本程序";
    }

    private void Clear()
    {
        Url = ""; Status = ""; _result = null; CoverImage = null;
        CurrentFormats.Clear();
        Raise(nameof(HasResult)); RaiseTabs(); Raise(nameof(FormatCountText));
    }

    private void Go(int page)
    {
        if (_page == 5 && page != 5) DisposePreview();
        _page = page;
        Raise(nameof(IsHome)); Raise(nameof(IsLocal)); Raise(nameof(IsMePage));
        Raise(nameof(IsQueuePage)); Raise(nameof(IsStoragePage)); Raise(nameof(IsMeNav));
        Raise(nameof(IsPreviewPage)); Raise(nameof(IsLanPage));
    }

    private void RaiseTabs()
    {
        Raise(nameof(IsVideoTab)); Raise(nameof(IsAudioTab)); Raise(nameof(IsImageTab));
        Raise(nameof(VideoTabLabel)); Raise(nameof(AudioTabLabel)); Raise(nameof(ImageTabLabel));
    }

    public DownloadTaskInfo? TaskFor(MediaFormat format) =>
        _formatTasks.TryGetValue(format, out var id)
            ? _services.Downloads.Tasks.FirstOrDefault(t => t.TaskId == id)
            : null;

    private async Task ParseAsync()
    {
        if (string.IsNullOrWhiteSpace(Url)) { Status = "失败：请先粘贴链接"; return; }
        if (IsBusy) return;
        IsBusy = true;
        Status = "正在解析…";
        try
        {
            var json = await _services.Engine.ParseAsync(Url.Trim(), CancellationToken.None,
                _services.Settings.ParseTtlSeconds);
            var result = ParseResult.Parse(json);
            if (result.IsEmpty)
            {
                Status = "失败：" + (string.IsNullOrEmpty(result.Error) ? "没有找到可下载的音视频" : result.Error);
                return;
            }
            ApplyResult(result);
        }
        catch (Exception ex)
        {
            Status = "失败：" + ex.Message;
        }
        finally { IsBusy = false; }
    }

    internal void ApplyResult(ParseResult result)
    {
        _result = result;
        _formatTasks.Clear();
        _services.Settings.AddRecent(Url.Trim());
        RebuildRecent();
        Status = $"解析完成：{result.Videos.Count} 个视频 / {result.Audios.Count} 个音频 / {result.Images.Count} 张图片";
        Raise(nameof(HasResult)); Raise(nameof(ResultTitle)); Raise(nameof(ResultMeta));
        Raise(nameof(ResultUploader)); Raise(nameof(HasUploader)); Raise(nameof(ResultDurationText));
        Raise(nameof(HasDuration)); Raise(nameof(ResultDescription)); Raise(nameof(HasDescription));
        // SelectedTab 首次解析时仍是默认值 0，setter 里的 Set 不会触发刷新，
        // 必须显式重建列表，否则第一次解析看不到任何格式项。
        _selectedTab = result.Videos.Count > 0 ? 0 : result.Audios.Count > 0 ? 1 : 2;
        RefreshFormats();
        RaiseTabs();
        _ = LoadCoverAsync(result.Thumbnail);
    }

    private async Task LoadCoverAsync(string url)
    {
        CoverImage = null;
        if (string.IsNullOrWhiteSpace(url)) return;
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            RefererPolicy.Apply(req, url);
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return;
            await using var ms = new MemoryStream();
            await (await resp.Content.ReadAsStreamAsync()).CopyToAsync(ms);
            ms.Position = 0;
            CoverImage = new Bitmap(ms);
        }
        catch { }
    }

    private void RefreshFormats()
    {
        foreach (var old in CurrentFormats) old.Dispose();
        CurrentFormats.Clear();
        var list = SelectedTab switch
        {
            0 => _result?.Videos,
            1 => _result?.Audios,
            _ => _result?.Images,
        };
        if (list is not null)
            foreach (var format in list) CurrentFormats.Add(new FormatItemViewModel(this, format));
        Raise(nameof(FormatCountText));
    }

    public void Download(MediaFormat format)
    {
        var title = MediaUtil.SafeFileName(string.IsNullOrWhiteSpace(_result?.Title) ? format.FormatId : _result.Title);
        var req = new DownloadRequest(_result?.WebpageUrl ?? Url, format.FormatId, format.Url, title,
                                      format.IsImage ? "image" : "video", DownloadDir,
                                      _result?.Id ?? "", _result?.Uploader ?? "", _result?.Source ?? "");
        if (_services.Downloads.Tasks.Any(t =>
                (t.Status == "running" || t.Status == "paused")
                && string.Equals(t.Url, req.Url, StringComparison.OrdinalIgnoreCase)
                && string.Equals(t.FormatId, req.FormatId, StringComparison.OrdinalIgnoreCase)))
            return;
        var task = _services.Downloads.Start(req);
        _formatTasks[format] = task.TaskId;
        Status = "已加入下载队列：" + title;
        RebuildTasks();
        FormatTasksChanged?.Invoke();    }

    public async Task PreviewAsync(MediaFormat format)
    {
        if (format.IsImage)
        {
            ShowPreview(new PreviewRequest(format.Quality, format.Url, true));
            return;
        }
        try
        {
            var referer = RefererPolicy.For(format.Url);
            if (string.IsNullOrEmpty(referer) && !string.IsNullOrEmpty(_result?.WebpageUrl))
                referer = _result.WebpageUrl;
            var localUrl = _services.Preview.Register(format.Url, referer);
            var isAudio = _result?.Audios.Contains(format) ?? false;
            ShowPreview(new PreviewRequest(format.DisplayName, localUrl, false, isAudio, format.Thumbnail));
        }
        catch (Exception ex) { Status = "失败：无法启动预览：" + ex.Message; }
        await Task.CompletedTask;
    }

    public async Task PreviewLocalAsync(LocalMedia media)
    {
        await Task.CompletedTask;
        var isImage = MediaUtil.IsImage(media.Path);
        var isAudio = !isImage && MediaUtil.IsAudio(media.Path);
        var url = isImage ? new Uri(media.Path).AbsoluteUri : _services.Preview.Register("file:///" + media.Path.Replace('\\', '/'));
        ShowPreview(new PreviewRequest(media.Name, url, isImage, isAudio));
    }

    private void ShowPreview(PreviewRequest request)
    {
        DisposePreview();
        if (_page != 5) _returnPage = _page;
        var vm = new PreviewViewModel(request);
        vm.CloseRequested += ClosePreview;
        vm.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(PreviewViewModel.IsFullScreen)) IsPlayerFullScreen = vm.IsFullScreen;
        };
        Preview = vm;
        Go(5);
    }

    public void OpenPreview(PreviewRequest request) => ShowPreview(request);

    private void ClosePreview()
    {
        DisposePreview();
        Go(_returnPage);
    }

    private void DisposePreview()
    {
        if (Preview is not { } vm) return;
        vm.CloseRequested -= ClosePreview;
        Preview = null;
        vm.Dispose();
        IsPlayerFullScreen = false;
    }

    private void RebuildTasks()
    {
        foreach (var old in TaskItems) old.Dispose();
        TaskItems.Clear();
        foreach (var task in _services.Downloads.Tasks)
        {
            TaskItems.Add(new TaskItemViewModel(this, task));
            task.PropertyChanged -= OnTaskProgress;
            task.PropertyChanged += OnTaskProgress;
        }
        Raise(nameof(HasNoTasks));
        Raise(nameof(TaskSummary));
    }

    private void OnTaskProgress(object? sender, PropertyChangedEventArgs e) => FormatTasksChanged?.Invoke();

    private void RebuildRecent()
    {
        RecentItems.Clear();
        foreach (var url in _services.Settings.Recent) RecentItems.Add(new RecentItemViewModel(this, url));
        Raise(nameof(HasRecent));
        Raise(nameof(RecentSummary));
    }

    private void ShowHistory()
    {
        var lifetime = Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        var window = new Views.HistoryWindow(this);
        if (lifetime?.MainWindow is { } owner) window.ShowDialog(owner);
        else window.Show();
    }

    private void ClearHistory()
    {
        _services.Settings.ClearRecent();
        RebuildRecent();
    }

    public void UseRecent(string url)
    {
        Url = url;
        _ = ParseAsync();
        HistoryItemPicked?.Invoke();
    }

    /// <summary>系统整合入口：命令行 / 拖放 / 右键菜单传进来的 URL 或本地文件。</summary>
    public void HandleCommandLine(string[] args)
    {
        foreach (var arg in args)
        {
            var value = arg.Trim().Trim('"');
            if (string.IsNullOrWhiteSpace(value)) continue;

            if (Uri.TryCreate(value, UriKind.Absolute, out var uri) &&
                (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps))
            {
                UseRecent(value);
                return;
            }

            if (File.Exists(value))
            {
                var info = new FileInfo(value);
                _ = PreviewLocalAsync(new LocalMedia
                {
                    Path = value,
                    Length = info.Length,
                    Modified = info.LastWriteTime,
                });
                return;
            }
        }
    }

    public void PauseTask(DownloadTaskInfo task) => _services.Downloads.Pause(task.TaskId);
    public void ResumeTask(DownloadTaskInfo task) => _services.Downloads.Resume(task.TaskId);
    public void CancelTask(DownloadTaskInfo task) => _services.Downloads.Cancel(task.TaskId);
    public void RetryTask(DownloadTaskInfo task) => _services.Downloads.Retry(task.TaskId);
    public void RemoveTask(DownloadTaskInfo task) => _services.Downloads.Remove(task.TaskId);
    public void OpenFile(DownloadTaskInfo task)
    {
        var path = Path.Combine(task.SaveDir, task.Filename);
        if (File.Exists(path))
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo { FileName = path, UseShellExecute = true });
    }

    private void RefreshLocal()
    {
        LocalItems.Clear();
        foreach (var media in _services.Local.Scan(DownloadDir)) LocalItems.Add(new LocalItemViewModel(this, media));
        Raise(nameof(HasNoLocal)); Raise(nameof(LocalSummary));
        RefreshStorage();
    }

    private void RefreshStorage()
    {
        var disk = _services.Storage.Disk(DownloadDir);
        long downloadBytes = 0;
        foreach (var item in LocalItems) downloadBytes += item.Media.Length;
        var cacheBytes = _services.Storage.CacheBytes();

        // 0 字节时显示 "0 B"，不要出现 "-" 这种占位符
        static string Size(long bytes) => bytes <= 0 ? "0 B" : MediaUtil.FormatSize(bytes);

        StorageDiskText = disk.Total > 0
            ? $"已用 {MediaUtil.FormatSize(disk.Used)} / 共 {MediaUtil.FormatSize(disk.Total)}"
            : "无法读取磁盘容量";
        StoragePercentText = disk.Total > 0 ? $"{disk.UsagePercent:0.#}%" : "";
        StorageUsage = disk.UsagePercent;
        StorageDownloadText = $"{Size(downloadBytes)} · {LocalItems.Count} 个文件";
        StorageCacheText = Size(cacheBytes);
        StorageTotalText = Size(downloadBytes + cacheBytes);
        HasCache = cacheBytes > 0;
    }

    private void ClearCache()
    {
        _services.Storage.ClearCache();
        RefreshStorage();
        Status = "预览缓存已清空";
    }

    private async Task PickFolderAsync()
    {
        var lifetime = Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        if (lifetime?.MainWindow?.StorageProvider is not { } storage) return;
        var folders = await storage.OpenFolderPickerAsync(new FolderPickerOpenOptions { AllowMultiple = false });
        if (folders.Count > 0)
        {
            DownloadDir = folders[0].TryGetLocalPath() ?? DownloadDir;
            Directory.CreateDirectory(DownloadDir);
            RefreshLocal();
        }
    }

    private void ToggleTheme() => IsDarkTheme = !IsDarkTheme;

    private async Task ShowRulesAsync()
    {
        Status = "正在读取站点规则…";
        var json = await _services.Engine.DescribeRulesAsync(DownloadDir);
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(json);
            var names = doc.RootElement.EnumerateArray()
                .Select(r => r.GetProperty("name").GetString())
                .Where(n => !string.IsNullOrEmpty(n))
                .ToArray();
            Status = "站点规则：" + string.Join(" / ", names);
        }
        catch { Status = "站点规则读取失败"; }
    }

    private async Task ScanDuplicatesAsync()
    {
        Status = "正在扫描重复文件…";
        var groups = await _services.Metadata.ScanDuplicatesAsync(DownloadDir);
        var files = groups.Sum(g => g.Paths.Count);
        Status = groups.Count == 0
            ? "未发现重复文件"
            : $"发现 {groups.Count} 组重复文件，共 {files} 个文件（每组保留一份即可）";
    }

    private async Task CheckUpdateAsync()
    {
        UpdateStatus = "正在检查更新…";
        try
        {
            var (info, reached) = await _services.Updates.CheckAsync();
            _update = info;
            // 只显示桌面版本身的更新，App 的 APK 版本绝不参与比较。
            UpdateStatus = !reached
                ? "检查更新失败：连不上更新服务器，请检查网络后重试"
                : info is null
                    ? $"当前已是最新版本 {AppVersion}"
                    : $"发现新版本 v{info.Version}：{info.Notes}";
        }
        catch (Exception ex) { UpdateStatus = "检查更新失败：" + ex.Message; }
        Raise(nameof(HasUpdate));
    }

    private async Task DownloadUpdateAsync()
    {
        if (_update is null || IsUpdating) return;
        IsUpdating = true;
        UpdateProgress = 0;
        var target = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                                  "Downloads", $"VSP-Setup-v{_update.Version}.msi");
        try
        {
            UpdateStatus = "正在下载桌面版安装包…";
            var statusProgress = new Progress<string>(s => UpdateStatus = s);
            await _services.Updates.DownloadAsync(_update, target,
                new Progress<double>(p => UpdateProgress = p), statusProgress, CancellationToken.None);
            UpdateStatus = "安装包已下载，正在启动安装程序…";
            try
            {
                // 直接拉起安装向导，用户不用自己去 Downloads 里找文件。
                // 安装程序会提示关闭正在运行的全能工具箱后继续。
                System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                {
                    FileName = "msiexec.exe",
                    Arguments = $"/i \"{target}\"",
                    UseShellExecute = true,
                });
            }
            catch (Exception ex)
            {
                UpdateStatus = "安装包已下载，但启动安装失败：" + ex.Message + "（文件：" + target + "）";
                LocalLibrary.OpenFolder(Path.GetDirectoryName(target)!);
            }
        }
        catch (Exception ex) { UpdateStatus = "下载失败：" + ex.Message; }
        finally { IsUpdating = false; }
    }

    // ---------------- 头像 / 高级设置 ----------------

    private void LoadAvatar()
    {
        AvatarImage = null;
        try
        {
            if (!File.Exists(AvatarFile)) return;
            using var fs = File.OpenRead(AvatarFile);
            AvatarImage = new Bitmap(fs);
        }
        catch { }
    }

    private async Task ChangeAvatarAsync()
    {
        var lifetime = Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        if (lifetime?.MainWindow?.StorageProvider is not { } storage) return;
        var files = await storage.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            AllowMultiple = false,
            Title = "选择头像图片",
            FileTypeFilter = [FilePickerFileTypes.ImageAll],
        });
        var path = files.FirstOrDefault()?.TryGetLocalPath();
        if (string.IsNullOrWhiteSpace(path)) return;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(AvatarFile)!);
            File.Copy(path!, AvatarFile, true);
            LoadAvatar();
            Status = "头像已更新";
        }
        catch (Exception ex) { Status = "头像更新失败：" + ex.Message; }
    }

    private void ResetAvatar()
    {
        try { if (File.Exists(AvatarFile)) File.Delete(AvatarFile); } catch { }
        AvatarImage = null;
        Status = "已恢复默认头像";
    }

    /// <summary>命名模板 / 解析缓存移到独立弹窗，避免主界面误触，也把占位符含义说清楚。</summary>
    private async Task ShowDownloadSettingsAsync()
    {
        var lifetime = Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        var owner = lifetime?.MainWindow;
        if (owner is null) return;
        var dialog = new Views.DownloadSettingsWindow(NameTemplate, ParseTtlSeconds);
        var saved = await dialog.ShowDialog<bool>(owner);
        if (!saved) return;
        NameTemplate = dialog.ResultTemplate;
        ParseTtlSeconds = dialog.ResultTtl;
        Status = "下载与解析设置已保存";
    }

    // ---------------- 局域网互传与远控实现 ----------------

    private void StartLan()
    {
        try
        {
            _lan = new LanService(Environment.MachineName, DownloadDir);
            _lan.DevicesChanged += () => Dispatch(() => RefreshLanDevices());
            _lan.ConnectionChanged += () => Dispatch(OnLanConnectionChanged);
            _lan.PairRequested += req => Dispatch(() =>
            {
                // 已连接时不再弹申请窗（底层已去重，这里再兜一层）
                if (_lan.IsConnectedTo(req.Id)) return;
                IncomingPair = req;
                LanStatus = $"{req.Name} 请求连接，请在弹窗中选择是否同意";
                RequestPairDialog?.Invoke();
            });
            _lan.ControlRequested += req => Dispatch(() =>
            {
                // 未连接或已连接其他设备时不允许远控，避免陌生设备弹窗骚扰。
                if (!_lan.IsConnectedTo(req.Id)) return;
                // 同一时间只处理一个申请，后来的直接拒绝，避免覆盖正在显示的申请。
                if (IncomingControl is not null) { _lan.DecideControl(req.RequestId, false); return; }
                IncomingControl = req;
                LanStatus = $"{req.Name} 请求远程控制本机，请在弹窗中选择是否允许";
                RequestControlDialog?.Invoke();
            });
            _lan.FileIncoming += (name, received, total) => Dispatch(() =>
            {
                LanTransferring = true;
                LanTransferProgress = total > 0 ? Math.Clamp((double)received / total, 0, 1) : 0;
                LanStatus = $"正在接收 {name} · {LanTransferProgress:P0}";
            });
            _lan.FileReceived += (name, total) => Dispatch(() =>
            {
                LanTransferring = false;
                LanTransferProgress = 0;
                LanStatus = $"已接收：{name}";
                LanNotification?.Invoke($"已接收：{name}");
                RefreshLocal();
            });
            _lan.Start();
            Dispatch(() => { Raise(nameof(LanLocalIp)); LanStatus = "正在发现附近设备…"; });
        }
        catch (Exception ex)
        {
            Dispatch(() => LanStatus = "局域网服务启动失败：" + ex.Message);
        }
    }

    /// <summary>向选中设备发起连接申请，轮询对方是否同意，同意后两端同时进入已连接。</summary>
    private async Task LanConnectAsync(LanDevice dev)
    {
        if (_lan is null) return;
        if (_lan.IsConnectedTo(dev.Id))
        {
            SelectedLanDevice = dev;
            LanStatus = $"已连接 {dev.Name}，可互传文件或远程控制";
            RequestCloseLanDevices?.Invoke();
            return;
        }
        _lanConnectCts?.Cancel();
        var cts = new CancellationTokenSource();
        _lanConnectCts = cts;
        LanPairing = true;
        LanStatus = $"正在请求连接 {dev.Name}，等待对方同意…";
        try
        {
            var sent = await _lan.RequestPairAsync(dev);
            if (!sent) { if (!cts.IsCancellationRequested) LanStatus = "连接申请发送失败，请确认对方在线"; LanPairing = false; return; }
            for (var i = 0; i < 120 && !cts.IsCancellationRequested; i++) // 最多等 40 秒
            {
                await Task.Delay(1000, cts.Token);
                var result = await _lan.PollPairAsync(dev);
                if (result == "ok" || _lan.IsConnectedTo(dev.Id))
                {
                    // 状态由 ConnectionChanged 事件统一更新，两端同步进入已连接
                    LanPairing = false;
                    RequestCloseLanDevices?.Invoke();
                    return;
                }
                if (result == "no") { LanStatus = "对方拒绝了连接申请"; LanPairing = false; return; }
            }
            if (!cts.IsCancellationRequested) LanStatus = "对方未响应，连接超时";
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { LanStatus = "连接失败：" + ex.Message; }
        LanPairing = false;
    }

    /// <summary>连接状态变化：两端同步刷新"已连接"界面与设备列表标记。</summary>
    private void OnLanConnectionChanged()
    {
        var peer = _lan?.ConnectedPeer;
        LanPairing = false;
        if (peer is not null)
        {
            if (!ReferenceEquals(SelectedLanDevice, peer))
            {
                _lanConnectCts?.Cancel();
                SelectedLanDevice = peer;
            }
            LanStatus = $"已连接 {peer.Name}，可互传文件或远程控制";
            RequestCloseLanDevices?.Invoke();
        }
        else
        {
            AbortLanControl();
            if (SelectedLanDevice is not null) SelectedLanDevice = null;
            LanStatus = "已断开连接";
        }
        RefreshLanDevices();
    }

    /// <summary>断开当前连接，并通知对端同步断开。</summary>
    private void DisconnectLan()
    {
        _lanConnectCts?.Cancel();
        _lan?.Disconnect();
        if (_lan?.ConnectedPeer is null)
        {
            SelectedLanDevice = null;
            LanStatus = "已断开连接";
        }
    }

    private void DecideIncomingPair(bool accept)
    {
        if (_lan is null || IncomingPair is null) return;
        var req = IncomingPair;
        IncomingPair = null;
        RequestClosePairDialog?.Invoke();
        _lan.DecidePair(req.Id, accept); // 同意时连接事件会把两端界面切到"已连接"
        if (!accept) LanStatus = $"已拒绝 {req.Name} 的连接申请";
        else LanStatus = $"正在连接 {req.Name}…";
    }

    /// <summary>审批对方的远控申请：桌面端不需要额外的系统屏幕授权。</summary>
    private void DecideIncomingControl(bool accept)
    {
        if (_lan is null || IncomingControl is null) return;
        var req = IncomingControl;
        IncomingControl = null;
        RequestCloseControlDialog?.Invoke();
        if (!_lan.DecideControl(req.RequestId, accept))
        {
            LanStatus = "该远控申请已失效";
            return;
        }
        LanStatus = accept
            ? $"已允许 {req.Name} 远程控制本机"
            : $"已拒绝 {req.Name} 的远控申请";
    }

    /// <summary>UI 事件：连接成功后关闭「附近设备」弹窗。</summary>
    public event Action? RequestCloseLanDevices;
    /// <summary>UI 事件：审批完成后关闭连接申请弹窗。</summary>
    public event Action? RequestClosePairDialog;

    private void RefreshLanDevices()
    {
        if (_lan is null) return;
        var list = _lan.Devices;
        // 增量同步，避免重复添加
        for (var i = LanDevices.Count - 1; i >= 0; i--)
        {
            var latest = list.FirstOrDefault(d => d.Id == LanDevices[i].Id);
            if (latest is null) LanDevices.RemoveAt(i);
            else if (!ReferenceEquals(latest, LanDevices[i])) LanDevices[i] = latest;
        }
        foreach (var d in list)
            if (!LanDevices.Any(x => x.Id == d.Id)) LanDevices.Add(d);
        Raise(nameof(HasNoLanDevices));
        if (!string.IsNullOrEmpty(_lan.ServiceError))
        {
            LanStatus = _lan.ServiceError + "（请检查防火墙或端口 45320/45321 是否被占用）";
            return;
        }
        // 已连接/正在连接时不要被周期性的设备发现刷掉状态
        if (_lan.ConnectedPeer is not null || LanPairing) return;
        LanStatus = list.Count == 0
            ? "未发现设备，请确保两端在同一 Wi-Fi"
            : $"发现 {list.Count} 台设备，点击连接（对方同意后两端同时已连接）";
    }

    private async Task RefreshLanFilesAsync()
    {
        LanRemoteFiles.Clear();
        if (_lan is null || SelectedLanDevice is null) return;
        LanBusy = true;
        LanStatus = "正在读取对方文件…";
        try
        {
            var dir = _lanDirStack.Count == 0 ? "" : EncodeLan(string.Join("/", _lanDirStack));
            var files = await _lan.ListRemoteAsync(SelectedLanDevice, dir);
            foreach (var f in files) LanRemoteFiles.Add(f);
            LanStatus = $"文件 {files.Count} 项";
        }
        catch (Exception ex) { LanStatus = "读取失败：" + ex.Message; }
        finally { LanBusy = false; }
    }

    private async Task DownloadLanAsync(LanFileEntry entry)
    {
        if (_lan is null || SelectedLanDevice is null) return;
        LanBusy = true;
        LanTransferring = true;
        LanTransferProgress = 0;
        LanStatus = $"正在接收 {entry.Name}…";
        try
        {
            var local = Path.Combine(DownloadDir, entry.Name);
            var ok = await _lan.DownloadAsync(SelectedLanDevice, entry, local,
                new Progress<double>(p =>
                {
                    LanTransferProgress = Math.Clamp(p, 0, 1);
                    LanStatus = $"正在接收 {entry.Name} · {p:P0}";
                }));
            LanStatus = ok ? $"已接收：{entry.Name}" : "接收失败";
            if (ok) RefreshLocal();
        }
        finally { LanBusy = false; LanTransferring = false; }
    }

    private async Task UploadLanAsync()
    {
        if (_lan is null || SelectedLanDevice is null) return;
        var lifetime = Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        if (lifetime?.MainWindow?.StorageProvider is not { } storage) return;
        var files = await storage.OpenFilePickerAsync(new FilePickerOpenOptions { AllowMultiple = true, Title = "选择要发送的文件" });
        var paths = files.Select(f => f.TryGetLocalPath()).Where(p => !string.IsNullOrEmpty(p)).ToList();
        if (paths.Count == 0) return;
        LanBusy = true;
        try
        {
            foreach (var path in paths)
            {
                var name = Path.GetFileName(path!);
                LanStatus = $"正在发送 {name}…";
                LanTransferring = true;
                LanTransferProgress = 0;
                var ok = await _lan.UploadAsync(SelectedLanDevice, path!,
                    new Progress<double>(p =>
                    {
                        LanTransferProgress = Math.Clamp(p, 0, 1);
                        LanStatus = $"正在发送 {name} · {p:P0}";
                    }));
                LanStatus = ok ? $"已发送：{name}" : "发送失败";
            }
            await RefreshLanFilesAsync();
        }
        finally { LanBusy = false; LanTransferring = false; }
    }

    private async Task StartLanControlAsync()
    {
        if (_lan is null || SelectedLanDevice is null) return;
        if (IsRemoteControlling || LanControlPending) return;
        var dev = SelectedLanDevice;
        _lanControlCts?.Cancel();
        var cts = new CancellationTokenSource();
        _lanControlCts = cts;
        LanControlPending = true;
        LanStatus = $"正在请求 {dev.Name} 授权远程控制…";
        try
        {
            var start = await _lan.RequestControlAsync(dev);
            if (cts.IsCancellationRequested)
            {
                if (start is not null) await _lan.StopControlAsync(dev, start.Cid);
                return;
            }
            if (start is null) { LanStatus = "远控请求发送失败，请确认对方在线"; return; }
            if (start.Result == "busy") { LanStatus = $"{dev.Name} 正在被其他设备控制"; return; }
            if (start.Result != "ok" && start.Result != "wait")
            {
                LanStatus = "对方没有允许远程控制";
                return;
            }

            _lanControlCid = start.Cid;
            var result = start.Result;
            if (result != "ok")
                LanStatus = $"已发送远控申请，等待 {dev.Name} 同意…";

            var deadline = DateTime.UtcNow.AddSeconds(60);
            while (result != "ok")
            {
                if (cts.IsCancellationRequested) return;
                if (DateTime.UtcNow >= deadline)
                {
                    LanStatus = "等待对方授权超时";
                    return;
                }
                try { await Task.Delay(700, cts.Token); }
                catch (OperationCanceledException) { return; }
                result = await _lan.PollControlAsync(dev, start.Cid);
                if (result == "no") { LanStatus = "对方拒绝或未完成屏幕共享授权"; return; }
                if (result == "busy") { LanStatus = "对方正在被其他设备控制"; return; }
            }

            if (cts.IsCancellationRequested) return;
            IsRemoteControlling = true;
            LanStatus = "远控中";
            var ticks = 0;
            while (IsRemoteControlling && !cts.IsCancellationRequested)
            {
                var bytes = await _lan.GetScreenAsync(dev);
                if (cts.IsCancellationRequested || !IsRemoteControlling) break;
                if (bytes is not null) Dispatch(() => LanScreenBytes = bytes);
                // 每 5 秒左右确认一次对方是否已停止共享，避免画面卡在最后一帧。
                if (++ticks % 8 == 0)
                {
                    var state = await _lan.PollControlAsync(dev, start.Cid);
                    if (cts.IsCancellationRequested || !IsRemoteControlling) break;
                    if (state == "no")
                    {
                        LanStatus = "对方已停止屏幕共享";
                        break;
                    }
                }
                try { await Task.Delay(600, cts.Token); }
                catch (OperationCanceledException) { break; }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { LanStatus = "远控失败：" + ex.Message; }
        finally
        {
            LanControlPending = false;
            IsRemoteControlling = false;
            Dispatch(() => LanScreenBytes = null);
            // 正常停止时 StopLanControl 已经取走 cid 并通知过对方；这里只为异常/超时兜底。
            var cid = _lanControlCid;
            _lanControlCid = null;
            if (cid is not null) _ = _lan.StopControlAsync(dev, cid);
        }
    }

    private void StopLanControl()
    {
        _lanControlCts?.Cancel();
        _lanControlCts = null;
        var cid = _lanControlCid;
        _lanControlCid = null;
        var dev = SelectedLanDevice;
        IsRemoteControlling = false;
        LanControlPending = false;
        Dispatch(() => LanScreenBytes = null);
        if (cid is not null && dev is not null && _lan is not null)
            _ = _lan.StopControlAsync(dev, cid);
        LanStatus = "已停止远控";
    }

    /// <summary>连接断开时本地收尾，不再向已离线设备发请求。</summary>
    private void AbortLanControl()
    {
        _lanControlCts?.Cancel();
        _lanControlCts = null;
        _lanControlCid = null;
        LanControlPending = false;
        IsRemoteControlling = false;
        Dispatch(() => LanScreenBytes = null);
    }

    private async Task LanTapAsync(object? p)
    {
        if (_lan is null || SelectedLanDevice is null) return;
        // p 为 (double x, double y) 归一化坐标，由 LanView 传入
        if (p is not ValueTuple<double, double> xy) return;
        await _lan.SendInputAsync(SelectedLanDevice, new { a = "tap", x = xy.Item1, y = xy.Item2 });
    }

    private async Task LanSwipeAsync(object? p)
    {
        if (_lan is null || SelectedLanDevice is null) return;
        // p 为 (x1, y1, x2, y2) 归一化坐标，由 LanView 拖拽手势传入
        if (p is not ValueTuple<double, double, double, double> s) return;
        await _lan.SendInputAsync(SelectedLanDevice,
            new { a = "swipe", x1 = s.Item1, y1 = s.Item2, x2 = s.Item3, y2 = s.Item4 });
    }

    private async Task LanSendKeyAsync(string key)
    {
        if (_lan is null || SelectedLanDevice is null) return;
        await _lan.SendInputAsync(SelectedLanDevice, new { a = "key", k = key });
    }

    private static string EncodeLan(string s) => Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(s));
    private static string? DecodeLan(string? s)
    {
        if (string.IsNullOrEmpty(s)) return null;
        try { return System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(s)); } catch { return null; }
    }

    private static void Dispatch(Action a)
    {
        var app = Application.Current;
        if (app is null) { a(); return; }
        var disp = Avalonia.Threading.Dispatcher.UIThread;
        if (disp.CheckAccess()) a(); else disp.Invoke(a);
    }
}
