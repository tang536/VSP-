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

    public bool IsHome => _page == 0;
    public bool IsLocal => _page == 1;
    public bool IsMePage => _page == 2;
    public bool IsQueuePage => _page == 3;
    public bool IsStoragePage => _page == 4;
    public bool IsMeNav => _page >= 2;
    private int _page;

    public MainViewModel(AppServices services)
    {
        _services = services;
        ShowHomeCommand = new RelayCommand(() => Go(0));
        ShowLocalCommand = new RelayCommand(() => { Go(1); RefreshLocal(); });
        ShowMeCommand = new RelayCommand(() => { Go(2); RefreshLocal(); RefreshStorage(); });
        ShowQueueCommand = new RelayCommand(() => { Go(3); RefreshLocal(); });
        ShowStorageCommand = new RelayCommand(() => { Go(4); RefreshLocal(); RefreshStorage(); });
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
        _page = page;
        Raise(nameof(IsHome)); Raise(nameof(IsLocal)); Raise(nameof(IsMePage));
        Raise(nameof(IsQueuePage)); Raise(nameof(IsStoragePage)); Raise(nameof(IsMeNav));
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
            _result = result;
            _formatTasks.Clear();
            _services.Settings.AddRecent(Url.Trim());
            RebuildRecent();
            Status = $"解析完成：{result.Videos.Count} 个视频 / {result.Audios.Count} 个音频 / {result.Images.Count} 张图片";
            Raise(nameof(HasResult)); Raise(nameof(ResultTitle)); Raise(nameof(ResultMeta));
            Raise(nameof(ResultUploader)); Raise(nameof(HasUploader)); Raise(nameof(ResultDurationText));
            Raise(nameof(HasDuration)); Raise(nameof(ResultDescription)); Raise(nameof(HasDescription));
            RaiseTabs();
            SelectedTab = result.Videos.Count > 0 ? 0 : result.Audios.Count > 0 ? 1 : 2;
            _ = LoadCoverAsync(result.Thumbnail);
        }
        catch (Exception ex)
        {
            Status = "失败：" + ex.Message;
        }
        finally { IsBusy = false; }
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
            ShowPreview(new PreviewRequest(format.DisplayName, localUrl, false));
        }
        catch (Exception ex) { Status = "失败：无法启动预览：" + ex.Message; }
        await Task.CompletedTask;
    }

    public async Task PreviewLocalAsync(LocalMedia media)
    {
        await Task.CompletedTask;
        var isImage = MediaUtil.IsImage(media.Path);
        var url = isImage ? new Uri(media.Path).AbsoluteUri : _services.Preview.Register("file:///" + media.Path.Replace('\\', '/'));
        ShowPreview(new PreviewRequest(media.Name, url, isImage));
    }

    private static void ShowPreview(PreviewRequest request)
    {
        var lifetime = Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime;
        var window = new PreviewWindow(request);
        if (lifetime?.MainWindow is { } owner) window.Show(owner);
        else window.Show();
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
    }

    public void UseRecent(string url) { Url = url; _ = ParseAsync(); }

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
            await _services.Updates.DownloadAsync(_update, target,
                new Progress<double>(p => UpdateProgress = p), CancellationToken.None);
            UpdateStatus = "安装包已下载：" + target;
            LocalLibrary.OpenFolder(Path.GetDirectoryName(target)!);
        }
        catch (Exception ex) { UpdateStatus = "下载失败：" + ex.Message; }
        finally { IsUpdating = false; }
    }
}
