using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;
using Avalonia;
using Avalonia.Controls;
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
    private string _imagePath = "";
    private string _coverPath = "";
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
    private bool _controlsHover;
    private bool _areControlsVisible = true;
    private double _controlsOpacity = 1;
    private double _playbackRate = 1.0;

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

    public string Error { get => _error; private set { if (Set(ref _error, value)) { Raise(nameof(ShowMediaError)); Raise(nameof(ShowBarError)); if (!string.IsNullOrEmpty(value)) ShowControls(autoHide: false); } } }
    public bool HasError => !string.IsNullOrEmpty(Error);
    public bool ShowMediaError => HasError && IsImage;
    public bool ShowBarError => HasError && !IsImage;
    public bool IsLoading { get => _isLoading; private set => Set(ref _isLoading, value); }
    public string ImagePath { get => _imagePath; private set => Set(ref _imagePath, value); }
    public string CoverPath { get => _coverPath; private set => Set(ref _coverPath, value); }
    public bool HasCover => !string.IsNullOrEmpty(CoverPath);

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

    /// <summary>鼠标在媒体区活动：立即显示控制条并重新计时。</summary>
    public void NotifyPointerActivity()
    {
        if (IsAudio) ShowControls();
    }

    /// <summary>鼠标进入/离开控制条：进入时保持显示，离开后重新开始 3 秒倒计时。</summary>
    public void SetControlsHover(bool hover)
    {
        _controlsHover = hover;
        if (hover) ShowControls(autoHide: false);
        else if (IsAudio) RestartHideTimer();
    }

    private void ShowControls(bool autoHide = true)
    {
        AreControlsVisible = true;
        ControlsOpacity = 1;
        if (autoHide && IsAudio) RestartHideTimer();
        else _hideControlsTimer.Stop();
    }

    private void RestartHideTimer()
    {
        _hideControlsTimer.Stop();
        _hideControlsTimer.Start();
    }

    private void HideControlsIfIdle()
    {
        if (!IsAudio || _controlsHover) return;
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
        var path = await DownloadAsync(_request.Url, "image-");
        if (path is not null) ImagePath = path;
    }

    public async Task LoadCoverAsync()
    {
        var path = await DownloadAsync(_request.CoverUrl, "cover-");
        if (path is not null)
        {
            CoverPath = path;
            Raise(nameof(HasCover));
        }
    }

    private async Task<string?> DownloadAsync(string url, string prefix)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        try
        {
            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true });
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            RefererPolicy.Apply(req, url);
            using var resp = await client.SendAsync(req);
            resp.EnsureSuccessStatusCode();
            var dir = Path.Combine(Path.GetTempPath(), "vsp-preview");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, prefix + Guid.NewGuid().ToString("N") + MediaUtil.GuessExtension(url, ".jpg"));
            await using (var file = File.Create(path))
                await (await resp.Content.ReadAsStreamAsync()).CopyToAsync(file);
            return path;
        }
        catch (Exception ex)
        {
            Error = (prefix == "cover-" ? "封面加载失败：" : "图片加载失败：") + ex.Message;
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

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _timer.Stop();
        _hideControlsTimer.Stop();
        try { _player?.Stop(); } catch { }
        _media?.Dispose();
        _player?.Dispose();
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
