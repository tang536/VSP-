using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;
using Avalonia.Threading;
using LibVLCSharp.Shared;
using VSP.Core;

namespace VSP.Desktop.Views;

public sealed record PreviewRequest(string Title, string Url, bool IsImage);

public sealed class PreviewViewModel : INotifyPropertyChanged
{
    private readonly PreviewRequest _request;
    private readonly DispatcherTimer _timer;
    private MediaPlayer? _player;
    private bool _seeking;
    private string _error = "";
    private string _imagePath = "";
    private double _position;
    private string _positionText = "00:00 / 00:00";
    private string _playPauseText = "暂停";

    public event PropertyChangedEventHandler? PropertyChanged;

    public string Title => _request.Title;
    public bool IsImage => _request.IsImage;
    public bool IsVideo => !_request.IsImage;
    public ICommand PlayPauseCommand { get; }
    public ICommand StopCommand { get; }
    public ICommand MuteCommand { get; }

    public string Error { get => _error; private set => Set(ref _error, value); }
    public bool HasError => !string.IsNullOrEmpty(Error);
    public string ImagePath { get => _imagePath; private set => Set(ref _imagePath, value); }

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

    public string PositionText { get => _positionText; private set => Set(ref _positionText, value); }
    public string PlayPauseText { get => _playPauseText; private set => Set(ref _playPauseText, value); }

    public PreviewViewModel(PreviewRequest request)
    {
        _request = request;
        PlayPauseCommand = new RelayCommand(() =>
        {
            if (_player is null) return;
            if (_player.IsPlaying) _player.Pause(); else _player.Play();
            PlayPauseText = _player.IsPlaying ? "暂停" : "播放";
        });
        StopCommand = new RelayCommand(() => _player?.Stop());
        MuteCommand = new RelayCommand(() => { if (_player is not null) _player.Mute = !_player.Mute; });

        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _timer.Tick += (_, _) => RefreshPosition();
        _timer.Start();
    }

    public void ReportError(string message) => Error = message;

    public void Attach(MediaPlayer player)
    {
        _player = player;
        player.EncounteredError += (_, _) =>
            Error = "播放失败。可尝试更新到最新版解析引擎，或下载后用本地播放器打开。";
    }

    public async Task LoadImageAsync()
    {
        try
        {
            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true });
            using var req = new HttpRequestMessage(HttpMethod.Get, _request.Url);
            RefererPolicy.Apply(req, _request.Url);
            using var resp = await client.SendAsync(req);
            resp.EnsureSuccessStatusCode();
            var dir = Path.Combine(Path.GetTempPath(), "vsp-preview");
            Directory.CreateDirectory(dir);
            var path = Path.Combine(dir, Guid.NewGuid().ToString("N") + MediaUtil.GuessExtension(_request.Url, ".jpg"));
            await using (var file = File.Create(path))
                await (await resp.Content.ReadAsStreamAsync()).CopyToAsync(file);
            ImagePath = path;
        }
        catch (Exception ex)
        {
            Error = "图片加载失败：" + ex.Message;
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
            PositionText = $"{MediaUtil.FormatDuration(time / 1000.0)} / {MediaUtil.FormatDuration(length / 1000.0)}";
            if (_player.IsPlaying) PlayPauseText = "暂停";
        }
        catch { }
    }

    public void Dispose() => _timer.Stop();

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        if (name == nameof(Error)) PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(HasError)));
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
