using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows.Input;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Platform.Storage;
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
    public bool IsDone => Task.Status == "done";
    public bool HasError => !string.IsNullOrEmpty(Task.Error) || Task.Status == "error";
    public bool HasSpeed => !string.IsNullOrWhiteSpace(Task.Speed);
    public ICommand CancelCommand { get; }
    public ICommand RetryCommand { get; }
    public ICommand RemoveCommand { get; }
    public ICommand OpenFileCommand { get; }

    public TaskItemViewModel(MainViewModel owner, DownloadTaskInfo task)
    {
        _owner = owner;
        Task = task;
        task.PropertyChanged += OnTaskChanged;
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
            foreach (var name in new[] { nameof(StatusText), nameof(IsRunning), nameof(IsDone),
                                         nameof(HasError), nameof(Error) })
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
    private string _coverPath = "";
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
    public string CoverPath { get => _coverPath; private set => Set(ref _coverPath, value); }
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
    public bool HasUpdate => _update is not null;
    public bool IsUpdating { get => _isUpdating; private set => Set(ref _isUpdating, value); }
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
    public ICommand CheckUpdateCommand { get; }
    public ICommand DownloadUpdateCommand { get; }
    public ICommand OpenReleaseCommand { get; }
    public ICommand OpenGiteeCommand { get; }
    public ICommand OpenGithubCommand { get; }

    // ---------------- 局域网互传与远控 ----------------
    private LanService? _lan;
    public ObservableCollection<LanDevice> LanDevices { get; } = [];
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
    /// <summary>远控画面：由 LanView 把字节转成 Bitmap 显示。</summary>
    public byte[]? LanScreenBytes { get => _lanScreenBytes; private set => Set(ref _lanScreenBytes, value); }
    private byte[]? _lanScreenBytes;
    public bool IsRemoteControlling { get => _remoteCtl; private set => Set(ref _remoteCtl, value); }
    private bool _remoteCtl;
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
    public ICommand LanAddManualCommand { get; }
    public ICommand LanAcceptPairCommand { get; }
    public ICommand LanRejectPairCommand { get; }
    /// <summary>手动输入 IP 的文本框内容（设备弹窗绑定）。</summary>
    public string LanManualIp { get => _lanManualIp; set => Set(ref _lanManualIp, value); }
    private string _lanManualIp = "";
    /// <summary>收到的连接申请（弹窗显示）。</summary>
    public LanPairRequest? IncomingPair { get => _incomingPair; private set => Set(ref _incomingPair, value); }
    private LanPairRequest? _incomingPair;
    /// <summary>是否正在等待对方同意我的连接申请。</summary>
    public bool LanPairing { get => _lanPairing; private set => Set(ref _lanPairing, value); }
    private bool _lanPairing;
    /// <summary>UI 事件：请求打开「附近设备」弹窗。</summary>
    public event Action? RequestOpenLanDevices;
    /// <summary>UI 事件：收到连接申请，请求弹窗确认。</summary>
    public event Action? RequestPairDialog;

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
        CheckUpdateCommand = new RelayCommand(() => _ = CheckUpdateAsync());
        DownloadUpdateCommand = new RelayCommand(() => _ = DownloadUpdateAsync());
        OpenReleaseCommand = new RelayCommand(() => LocalLibrary.OpenUrl(UpdateService.PageUrl));
        OpenGiteeCommand = new RelayCommand(() => LocalLibrary.OpenUrl("https://gitee.com/tang-rongqiang/VSP"));
        OpenGithubCommand = new RelayCommand(() => LocalLibrary.OpenUrl("https://github.com/tang536/VSP-"));

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
        LanAddManualCommand = new RelayCommand(() => _ = LanAddManualAsync());
        LanAcceptPairCommand = new RelayCommand(() => DecideIncomingPair(true));
        LanRejectPairCommand = new RelayCommand(() => DecideIncomingPair(false));
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
        Url = ""; Status = ""; _result = null; _coverPath = "";
        CurrentFormats.Clear();
        Raise(nameof(HasResult)); Raise(nameof(CoverPath)); RaiseTabs(); Raise(nameof(FormatCountText));
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
            var json = await _services.Engine.ParseAsync(Url.Trim(), CancellationToken.None);
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
        CoverPath = "";
        if (string.IsNullOrWhiteSpace(url)) return;
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            RefererPolicy.Apply(req, url);
            using var resp = await _http.SendAsync(req);
            if (!resp.IsSuccessStatusCode) return;
            var dir = Path.Combine(Path.GetTempPath(), "vsp-preview");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, "cover-" + Guid.NewGuid().ToString("N") + MediaUtil.GuessExtension(url, ".jpg"));
            await using (var file = File.Create(path))
                await (await resp.Content.ReadAsStreamAsync()).CopyToAsync(file);
            CoverPath = path;
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
                                      format.IsImage ? "image" : "video", DownloadDir);
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
            UpdateStatus = "安装包已下载：" + target;
            LocalLibrary.OpenFolder(Path.GetDirectoryName(target)!);
        }
        catch (Exception ex) { UpdateStatus = "下载失败：" + ex.Message; }
        finally { IsUpdating = false; }
    }

    // ---------------- 局域网互传与远控实现 ----------------

    private void StartLan()
    {
        try
        {
            _lan = new LanService(Environment.MachineName, DownloadDir);
            _lan.DevicesChanged += () => Dispatch(() => RefreshLanDevices());
            _lan.PairRequested += req => Dispatch(() =>
            {
                IncomingPair = req;
                LanStatus = $"{req.Name} 请求连接，请在弹窗中选择是否同意";
                RequestPairDialog?.Invoke();
            });
            _lan.Start();
            Dispatch(() => { Raise(nameof(LanLocalIp)); LanStatus = "正在发现附近设备…"; });
        }
        catch (Exception ex)
        {
            Dispatch(() => LanStatus = "局域网服务启动失败：" + ex.Message);
        }
    }

    /// <summary>向选中设备发起连接申请，轮询对方是否同意，同意后才真正建立连接。</summary>
    private async Task LanConnectAsync(LanDevice dev)
    {
        if (_lan is null) return;
        LanPairing = true;
        LanStatus = $"正在请求连接 {dev.Name}，等待对方同意…";
        try
        {
            var sent = await _lan.RequestPairAsync(dev);
            if (!sent) { LanStatus = "连接申请发送失败，请确认对方在线"; LanPairing = false; return; }
            for (var i = 0; i < 120; i++) // 最多等 40 秒
            {
                await Task.Delay(1000);
                var result = await _lan.PollPairAsync(dev);
                if (result == "ok")
                {
                    SelectedLanDevice = dev;
                    LanStatus = "连接成功，可互传文件或远程控制";
                    LanPairing = false;
                    RequestCloseLanDevices?.Invoke();
                    return;
                }
                if (result == "no") { LanStatus = "对方拒绝了连接申请"; LanPairing = false; return; }
            }
            LanStatus = "对方未响应，连接超时";
        }
        catch (Exception ex) { LanStatus = "连接失败：" + ex.Message; }
        LanPairing = false;
    }

    /// <summary>手动按 IP 添加设备（广播发现不可用时兜底）。</summary>
    private async Task LanAddManualAsync()
    {
        if (_lan is null || string.IsNullOrWhiteSpace(LanManualIp)) return;
        LanStatus = "正在连接 " + LanManualIp + "…";
        var (ok, msg) = await _lan.AddManualDeviceAsync(LanManualIp);
        LanStatus = msg;
        if (ok) LanManualIp = "";
    }

    private void DecideIncomingPair(bool accept)
    {
        if (_lan is null || IncomingPair is null) return;
        var name = IncomingPair.Name;
        _lan.DecidePair(IncomingPair.Id, accept);
        IncomingPair = null;
        LanStatus = accept ? $"已同意 {name} 的连接申请" : $"已拒绝 {name} 的连接申请";
        RequestClosePairDialog?.Invoke();
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
            if (!list.Any(d => d.Id == LanDevices[i].Id)) LanDevices.RemoveAt(i);
        foreach (var d in list)
            if (!LanDevices.Any(x => x.Id == d.Id)) LanDevices.Add(d);
        LanStatus = list.Count == 0
            ? "未发现设备，请确保两端在同一 Wi-Fi"
            : $"发现 {list.Count} 台设备";
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
        LanStatus = $"正在接收 {entry.Name}…";
        try
        {
            var local = Path.Combine(DownloadDir, entry.Name);
            var ok = await _lan.DownloadAsync(SelectedLanDevice, entry, local,
                new Progress<double>(p => LanStatus = $"正在接收 {entry.Name} · {p:P0}"));
            LanStatus = ok ? $"已接收：{entry.Name}" : "接收失败";
            if (ok) RefreshLocal();
        }
        finally { LanBusy = false; }
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
                LanStatus = $"正在发送 {Path.GetFileName(path!)}…";
                var ok = await _lan.UploadAsync(SelectedLanDevice, path!);
                LanStatus = ok ? $"已发送：{Path.GetFileName(path!)}" : "发送失败";
            }
            await RefreshLanFilesAsync();
        }
        finally { LanBusy = false; }
    }

    private async Task StartLanControlAsync()
    {
        if (_lan is null || SelectedLanDevice is null) return;
        IsRemoteControlling = true;
        LanStatus = "远控中";
        while (IsRemoteControlling && SelectedLanDevice is not null)
        {
            var bytes = await _lan.GetScreenAsync(SelectedLanDevice);
            if (bytes is not null) Dispatch(() => LanScreenBytes = bytes);
            await Task.Delay(600);
        }
    }

    private void StopLanControl()
    {
        IsRemoteControlling = false;
        Dispatch(() => LanScreenBytes = null);
        LanStatus = "已停止远控";
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
