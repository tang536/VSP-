using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using LibVLCSharp.Shared;
using VSP.Core;

namespace VSP.Desktop.Views;

public sealed record PreviewRequest(string Title, string Url, bool IsImage,
    bool IsAudio = false, string CoverUrl = "");

public sealed class PreviewViewModel : INotifyPropertyChanged
{
    private readonly PreviewRequest _request;
    private readonly DispatcherTimer _timer;
    private MediaPlayer? _player;
    private Media? _media;
    private LibVLC? _libvlc;
    private bool _seeking;
    private bool _disposed;
    private string _error = "";
    private Bitmap? _imageBitmap;
    private Bitmap? _coverBitmap;
    private double _position;
    private double _volume = 100;
    private bool _isMuted;
    private bool _isPlaying;
    private bool _isFullScreen;
    private string _elapsedText = "00:00";
    private string _durationText = "00:00";
    private bool _volumePopupOpen;
    private bool _isLoading;
    private readonly DispatcherTimer _hideControlsTimer;
    private bool _controlsPressed;
    private bool _areControlsVisible = true;
    private double _controlsOpacity = 1;
    private double _playbackRate = 1.0;
    private string _snapshotMessage = "";
    private readonly DispatcherTimer _snapshotMessageTimer;

    private static readonly double[] PlaybackRates = [1.0, 1.25, 1.5, 2.0, 0.75];

    public event PropertyChangedEventHandler? PropertyChanged;
    public event Action? CloseRequested;

    public string Title => _request.Title;
    public string Url => _request.Url;
    public bool IsImage => _request.IsImage;
    public bool IsAudio => _request.IsAudio;
    public bool IsVideo => !_request.IsImage && !_request.IsAudio;
    public ICommand PlayPauseCommand { get; }
    public ICommand StopCommand { get; }
    public ICommand ToggleMuteCommand { get; }
    public ICommand ToggleVolumePopupCommand { get; }
    public ICommand CloseCommand { get; }
    public ICommand ToggleFullScreenCommand { get; }
    public ICommand SeekBackCommand { get; }
    public ICommand SeekForwardCommand { get; }
    public ICommand CycleRateCommand { get; }
    public ICommand SnapshotCommand { get; }
    public ICommand ClipCommand { get; }

    /// <summary>截图保存目录，由 View 在挂载时注入（LibVLC 的 --snapshot-path）。</summary>
    public string SnapshotDirectory { get; set; } = "";
    public string SnapshotMessage { get => _snapshotMessage; private set { if (Set(ref _snapshotMessage, value)) Raise(nameof(HasSnapshotMessage)); } }
    public bool HasSnapshotMessage => !string.IsNullOrEmpty(SnapshotMessage);

    public string Error { get => _error; private set { if (Set(ref _error, value)) { Raise(nameof(ShowMediaError)); Raise(nameof(ShowBarError)); } } }
    public bool HasError => !string.IsNullOrEmpty(Error);
    // 图片/音频的错误用画面顶部的独立浮层，控制栏该隐藏时仍然隐藏；
    // 视频画面是原生子窗口，浮层会被盖住，所以错误仍放在下方控制条里。
    public bool ShowMediaError => HasError && !IsVideo;
    public bool ShowBarError => HasError && IsVideo;
    public bool IsLoading { get => _isLoading; private set => Set(ref _isLoading, value); }
    /// <summary>图片预览：本地文件或网络下载都统一转成 Bitmap，XAML 可直接绑定。</summary>
    public Bitmap? ImageBitmap
    {
        get => _imageBitmap;
        private set
        {
            if (ReferenceEquals(_imageBitmap, value)) return;
            var old = _imageBitmap;
            _imageBitmap = value;
            Raise(nameof(ImageBitmap));
            old?.Dispose();
        }
    }
    public Bitmap? CoverBitmap
    {
        get => _coverBitmap;
        private set
        {
            if (ReferenceEquals(_coverBitmap, value)) return;
            var old = _coverBitmap;
            _coverBitmap = value;
            Raise(nameof(CoverBitmap));
            Raise(nameof(HasCover));
            old?.Dispose();
        }
    }
    public bool HasCover => CoverBitmap is not null;

    public double Position
    {
        get => _position;
        set
        {
            if (Set(ref _position, value) && !_seeking && _player is { Length: > 0 } player)
            {
                try { player.Time = (long)(value / 100.0 * player.Length); } catch { }
            }
        }
    }

    public double Volume
    {
        get => _volume;
        set
        {
            var v = Math.Clamp(value, 0, 100);
            if (Set(ref _volume, v) && _player is not null)
            {
                try { _player.Volume = (int)Math.Round(v); } catch { }
            }
            Raise(nameof(VolumeText));
            IsMuted = v <= 0;
        }
    }

    public bool IsMuted { get => _isMuted; private set { if (Set(ref _isMuted, value)) { Raise(nameof(MuteText)); Raise(nameof(MuteIcon)); } } }
    public string MuteText => IsMuted ? "取消静音" : "静音";
    public Avalonia.Media.Geometry? MuteIcon =>
        Application.Current?.FindResource(IsMuted ? "IconMute" : "IconVolume") as Avalonia.Media.Geometry;

    public bool IsPlaying { get => _isPlaying; private set { if (Set(ref _isPlaying, value)) Raise(nameof(PlayPauseIcon)); } }
    public Avalonia.Media.Geometry? PlayPauseIcon =>
        Application.Current?.FindResource(IsPlaying ? "IconPause" : "IconPlay") as Avalonia.Media.Geometry;

    public bool IsFullScreen { get => _isFullScreen; private set { if (Set(ref _isFullScreen, value)) { Raise(nameof(FullScreenText)); Raise(nameof(ShowVideoControls)); Raise(nameof(ShowHeader)); } } }
    public string FullScreenText => IsFullScreen ? "退出全屏" : "全屏";
    // 视频控制条在画面下方单独一行（原生视频窗口无法被 Avalonia 控件覆盖）；
    // 音频控制条叠在画面底部，并支持 3 秒自动隐藏。
    public bool ShowVideoControls => IsVideo;
    public bool ShowHeader => !IsFullScreen;
    public void SetFullScreen(bool value) => IsFullScreen = value;

    /// <summary>音频控制条是否可见（也决定是否可点击）。</summary>
    public bool AreControlsVisible { get => _areControlsVisible; private set => Set(ref _areControlsVisible, value); }
    /// <summary>用于 Opacity 过渡的数值：1 显示 / 0 淡出。</summary>
    public double ControlsOpacity { get => _controlsOpacity; private set => Set(ref _controlsOpacity, value); }

    /// <summary>当前播放倍速；点击“倍速”按钮循环切换。</summary>
    public double PlaybackRate
    {
        get => _playbackRate;
        private set
        {
            if (Set(ref _playbackRate, value)) Raise(nameof(RateText));
        }
    }

    public string RateText => $"倍速 {PlaybackRate:0.0#}x";

    /// <summary>鼠标在媒体区或控制条上活动：显示控制条并重新开始 3 秒倒计时。</summary>
    public void NotifyPointerActivity()
    {
        if (!IsAudio || AreControlsVisible || _controlsPressed) return;
        // 控制条已经显示时不再重置计时器：系统偶尔重复投递 PointerMoved，
        // 若每次都续 3 秒就永远不会隐藏，这正是用户看到「鼠标不动也不消失」的原因。
        ShowControls();
    }

    /// <summary>按住控制条（拖动进度/音量）期间不自动隐藏，松开后重新计时。</summary>
    public void SetControlsPressed(bool pressed)
    {
        _controlsPressed = pressed;
        if (!IsAudio) return;
        if (pressed)
        {
            ShowControls();
            _hideControlsTimer.Stop();
        }
        else
        {
            RestartHideTimer();
        }
    }

    /// <summary>窗口重新激活时把控制条叫出来；失焦后照常由计时器隐藏。</summary>
    public void NotifyWindowActiveChanged(bool active)
    {
        if (active) ShowControls();
    }

    private void ShowControls()
    {
        AreControlsVisible = true;
        ControlsOpacity = 1;
        if (IsAudio && !_controlsPressed) RestartHideTimer();
    }

    private void RestartHideTimer()
    {
        _hideControlsTimer.Stop();
        _hideControlsTimer.Start();
    }

    private void HideControlsIfIdle()
    {
        if (!IsAudio || _controlsPressed) return;

        // 3 秒内没有鼠标操作就隐藏；即使光标停在控制条上也一样，
        // 避免鼠标恰好停在底部导致控制条永远不消失。
        _hideControlsTimer.Stop();
        AreControlsVisible = false;
        ControlsOpacity = 0;
    }

    // 手机端播放器：当前时间 / 总时长分列进度条两侧
    public string ElapsedText { get => _elapsedText; private set => Set(ref _elapsedText, value); }
    public string DurationText { get => _durationText; private set => Set(ref _durationText, value); }

    // 音量浮层：默认收起，点音量图标才在图标上方展开（与手机端一致）
    public bool IsVolumePopupOpen { get => _volumePopupOpen; private set => Set(ref _volumePopupOpen, value); }
    public string VolumeText => $"{Math.Round(Volume)}%";

    public PreviewViewModel(PreviewRequest request)
    {
        _request = request;
        _isLoading = !request.IsImage;
        PlayPauseCommand = new RelayCommand(() =>
        {
            if (_player is null) return;
            try
            {
                if (_player.IsPlaying) _player.Pause(); else _player.Play();
                IsPlaying = _player.IsPlaying;
            }
            catch (Exception ex) { Error = "播放控制失败：" + ex.Message; }
        });
        StopCommand = new RelayCommand(() =>
        {
            try { _player?.Stop(); IsPlaying = false; } catch { }
        });
        ToggleMuteCommand = new RelayCommand(() =>
        {
            if (_player is null) return;
            try { _player.Mute = !_player.Mute; IsMuted = _player.Mute; } catch { }
        });
        ToggleVolumePopupCommand = new RelayCommand(() => IsVolumePopupOpen = !IsVolumePopupOpen);
        CloseCommand = new RelayCommand(() => CloseRequested?.Invoke());
        ToggleFullScreenCommand = new RelayCommand(() => IsFullScreen = !IsFullScreen);
        SeekBackCommand = new RelayCommand(() => SeekBy(-10));
        SeekForwardCommand = new RelayCommand(() => SeekBy(10));
        CycleRateCommand = new RelayCommand(CyclePlaybackRate);
        SnapshotCommand = new RelayCommand(TakeSnapshot);
        ClipCommand = new RelayCommand(() => _ = ExportClipAsync());

        _snapshotMessageTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3.5) };
        _snapshotMessageTimer.Tick += (_, _) => { _snapshotMessageTimer.Stop(); SnapshotMessage = ""; };

        _hideControlsTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _hideControlsTimer.Tick += (_, _) => HideControlsIfIdle();
        // 音频打开后控制条先显示，3 秒内没有鼠标活动就自动隐藏。
        if (request.IsAudio) _hideControlsTimer.Start();

        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        _timer.Tick += (_, _) => RefreshPosition();
        _timer.Start();
    }

    public void ReportError(string message)
    {
        IsLoading = false;
        Error = message;
    }

    public void Attach(LibVLC libvlc, MediaPlayer player, Media media)
    {
        _libvlc = libvlc;
        _player = player;
        _media = media;
        try
        {
            player.Volume = (int)Math.Round(_volume);
            if (Math.Abs(_playbackRate - 1.0) > 0.001) player.SetRate((float)_playbackRate);
        }
        catch { }
        // LibVLC 的事件来自原生线程，必须回到 UI 线程再改绑定属性。
        player.Playing += (_, _) => Dispatcher.UIThread.Post(() =>
        {
            IsLoading = false;
            IsPlaying = true;
        });
        player.EncounteredError += (_, _) => Dispatcher.UIThread.Post(() =>
        {
            IsLoading = false;
            Error = "播放失败。可尝试更新到最新版解析引擎，或下载后用本地播放器打开。";
        });
        player.EndReached += (_, _) => Dispatcher.UIThread.Post(() => IsPlaying = false);
        player.Stopped += (_, _) => Dispatcher.UIThread.Post(() => IsPlaying = false);
    }

    public async Task LoadImageAsync()
    {
        var bitmap = await LoadBitmapAsync(_request.Url, "image-");
        if (bitmap is not null) ImageBitmap = bitmap;
    }

    public async Task LoadCoverAsync()
    {
        var bitmap = await LoadBitmapAsync(_request.CoverUrl, "cover-");
        if (bitmap is not null) CoverBitmap = bitmap;
    }

    private async Task<Bitmap?> LoadBitmapAsync(string url, string prefix)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        try
        {
            // 本地文件直接读路径，不能交给 HttpClient：file:// 不是它支持的 scheme。
            if (Uri.TryCreate(url, UriKind.Absolute, out var uri) && uri.IsFile && File.Exists(uri.LocalPath))
            {
                await using var file = File.OpenRead(uri.LocalPath);
                return new Bitmap(file);
            }
            if (File.Exists(url))
            {
                await using var file = File.OpenRead(url);
                return new Bitmap(file);
            }
            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true });
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            RefererPolicy.Apply(req, url);
            using var resp = await client.SendAsync(req);
            resp.EnsureSuccessStatusCode();
            await using var ms = new MemoryStream();
            await (await resp.Content.ReadAsStreamAsync()).CopyToAsync(ms);
            ms.Position = 0;
            return new Bitmap(ms);
        }
        catch (Exception ex)
        {
            // 封面只是装饰，下载失败就回退到占位图标，不要弹错误，
            // 更不要因为封面失败影响音频控制条的自动隐藏。
            if (prefix != "cover-") Error = "图片加载失败：" + ex.Message;
            return null;
        }
    }

    private void RefreshPosition()
    {
        if (_player is null) return;
        try
        {
            var length = _player.Length;
            var time = Math.Max(0, _player.Time);
            _seeking = true;
            Position = length > 0 ? Math.Min(100, time * 100.0 / length) : 0;
            _seeking = false;
            var timeText = time > 0 ? MediaUtil.FormatDuration(time / 1000.0) : "00:00";
            var lengthText = length > 0 ? MediaUtil.FormatDuration(length / 1000.0) : "00:00";
            ElapsedText = timeText;
            DurationText = lengthText;
            IsPlaying = _player.IsPlaying;
        }
        catch { }
    }

    private void SeekBy(int seconds)
    {
        if (_player is not { Length: > 0 } player) return;
        try
        {
            var target = Math.Clamp(player.Time + seconds * 1000L, 0, Math.Max(0, player.Length - 500));
            player.Time = target;
            NotifyPointerActivity();
        }
        catch { }
    }

    private void CyclePlaybackRate()
    {
        var index = Array.IndexOf(PlaybackRates, PlaybackRate);
        var next = PlaybackRates[(index + 1) % PlaybackRates.Length];
        PlaybackRate = next;
        try { _player?.SetRate((float)next); } catch { }
        NotifyPointerActivity();
    }

    /// <summary>滚轮 / 上下键调整音量。</summary>
    public void ChangeVolume(double delta) => Volume += delta;

    /// <summary>用 LibVLC 快照当前视频帧；目录由 LibVLC 启动参数指定。</summary>
    private void TakeSnapshot()
    {
        if (_player is null || IsImage)
        {
            SnapshotMessage = IsImage ? "图片可直接另存" : "画面尚未就绪";
        }
        else
        {
            try
            {
                var ok = _player.TakeSnapshot(0, null, 0, 0);
                SnapshotMessage = ok
                    ? $"截图已保存到 {SnapshotDirectory}"
                    : "截图失败，请确认媒体已开始播放";
            }
            catch (Exception ex)
            {
                SnapshotMessage = "截图失败：" + ex.Message;
            }
        }
        _snapshotMessageTimer.Stop();
        _snapshotMessageTimer.Start();
        NotifyPointerActivity();
    }

    /// <summary>用 LibVLC 的 sout 把当前位置起 15 秒直接封装成 mp4（不重编码）。</summary>
    private async Task ExportClipAsync()
    {
        if (_player is null || _libvlc is null || IsImage)
        {
            SnapshotMessage = "当前媒体不支持片段截取";
            return;
        }

        var start = Math.Max(0.0, Math.Min(_player.Length / 1000.0 - 15, _player.Time / 1000.0));
        var end = start + 15;
        var outPath = Path.Combine(SnapshotDirectory, $"clip-{DateTime.Now:yyyyMMdd-HHmmss}.mp4");

        try
        {
            Directory.CreateDirectory(SnapshotDirectory);
            var dst = outPath.Replace('\\', '/');
            string[] options =
            [
                $":start-time={start:0.000}",
                $":stop-time={end:0.000}",
                $":sout=#std{{access=file,mux=mp4,dst={dst}}}",
                ":sout-all",
                ":sout-keep",
            ];

            using var media = new Media(_libvlc, new Uri(Url), options);
            using var clipPlayer = new MediaPlayer(_libvlc);
            var done = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            clipPlayer.EndReached += (_, _) => done.TrySetResult(true);
            clipPlayer.EncounteredError += (_, _) => done.TrySetResult(false);
            clipPlayer.Play(media);
            SnapshotMessage = $"正在截取 15 秒片段：{Path.GetFileName(outPath)}";
            _snapshotMessageTimer.Stop();

            var finished = await Task.WhenAny(done.Task, Task.Delay(TimeSpan.FromSeconds(40)));
            if (finished == done.Task && done.Task.Result)
                SnapshotMessage = $"片段已保存：{outPath}";
            else
                SnapshotMessage = "片段截取超时或失败";
        }
        catch (Exception ex)
        {
            SnapshotMessage = "片段截取失败：" + ex.Message;
        }

        _snapshotMessageTimer.Start();
        NotifyPointerActivity();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _timer.Stop();
        _hideControlsTimer.Stop();
        _snapshotMessageTimer.Stop();
        try { _player?.Stop(); } catch { }
        _media?.Dispose();
        _player?.Dispose();
        _imageBitmap?.Dispose();
        _coverBitmap?.Dispose();
        // LibVLC 是进程内共享的，这里不能释放，否则下一个预览会失败。
        _libvlc = null;
    }

    private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name!));
        if (name == nameof(Error)) Raise(nameof(HasError));
        return true;
    }
}

public sealed class RelayCommand : ICommand
{
    private readonly Action<object?> _action;
    public RelayCommand(Action action) : this(_ => action()) { }
    public RelayCommand(Action<object?> action) => _action = action;
    public event EventHandler? CanExecuteChanged;
    public bool CanExecute(object? parameter) => true;
    public void Execute(object? parameter) => _action(parameter);
    public void RaiseCanExecuteChanged() => CanExecuteChanged?.Invoke(this, EventArgs.Empty);
}
