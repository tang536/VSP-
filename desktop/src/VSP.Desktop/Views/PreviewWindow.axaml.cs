using Avalonia.Controls;
using Avalonia.Platform;
using LibVLCSharp.Avalonia;
using LibVLCSharp.Shared;

namespace VSP.Desktop.Views;

public partial class PreviewWindow : Window
{
    private LibVLC? _libvlc;
    private MediaPlayer? _player;
    private Media? _media;

    // XAML 运行时加载器要求公共无参构造；实际使用走下面带请求的构造函数。
    public PreviewWindow() => InitializeComponent();

    public PreviewWindow(PreviewRequest request)
    {
        InitializeComponent();
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }
        var vm = new PreviewViewModel(request);
        DataContext = vm;
        Title = "预览 · " + request.Title;
        Closing += (_, _) =>
        {
            vm.Dispose();
            try { _player?.Stop(); } catch { }
            _media?.Dispose();
            _player?.Dispose();
            _libvlc?.Dispose();
        };

        if (request.IsImage)
        {
            _ = vm.LoadImageAsync();
            return;
        }

        try
        {
            _libvlc = new LibVLC("--network-caching=300", "--no-video-title-show", "--quiet");
            _player = new MediaPlayer(_libvlc);
            var view = this.FindControl<VideoView>("VideoView");
            if (view is not null) view.MediaPlayer = _player;
            vm.Attach(_player);
            _media = new Media(_libvlc, new Uri(request.Url));
            _player.Play(_media);
        }
        catch (Exception ex)
        {
            vm.ReportError("播放器初始化失败：" + ex.Message);
        }
    }
}
