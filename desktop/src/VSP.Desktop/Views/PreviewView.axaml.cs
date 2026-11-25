using System.ComponentModel;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.VisualTree;
using LibVLCSharp.Avalonia;
using LibVLCSharp.Shared;

namespace VSP.Desktop.Views;

public partial class PreviewView : UserControl
{
    // 整个进程共用一个 LibVLC。每次预览都 new LibVLC() 在首次打开时要几百毫秒到一两秒，
    // 放在 UI 线程上就是用户看到的“点预览卡住”。
    private static readonly Lazy<LibVLC> SharedVlc = new(() =>
        new LibVLC("--network-caching=1000", "--no-video-title-show", "--quiet"));

    private PreviewViewModel? _bound;
    private Window? _window;
    private int _generation;

    public PreviewView()
    {
        InitializeComponent();
        DataContextChanged += (_, _) => OnDataContextChanged();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _window = TopLevel.GetTopLevel(this) as Window;
        if (_window is not null)
        {
            _window.KeyDown += OnKeyDown;
            _window.Activated += OnWindowActivated;
        }
        if (DataContext is PreviewViewModel vm) ApplyFullScreen(vm.IsFullScreen);
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        if (_window is not null)
        {
            _window.KeyDown -= OnKeyDown;
            _window.Activated -= OnWindowActivated;
        }
        _window = null;
        base.OnDetachedFromVisualTree(e);
    }

    private void OnWindowActivated(object? sender, EventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.NotifyWindowActiveChanged(true);
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && DataContext is PreviewViewModel { IsFullScreen: true } vm)
        {
            vm.SetFullScreen(false);
            e.Handled = true;
        }
    }

    /// <summary>鼠标在媒体区移动时唤醒音频控制条，并重新开始 3 秒自动隐藏计时。</summary>
    private void OnMediaPointerMoved(object? sender, PointerEventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.NotifyPointerActivity();
    }

    /// <summary>控制条隐藏后，单击画面也能把它叫出来。</summary>
    private void OnMediaPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.NotifyPointerActivity();
    }

    /// <summary>鼠标在控制条上活动时重新计时；停在原处不动仍会隐藏。</summary>
    private void OnAudioBarPointerMoved(object? sender, PointerEventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.NotifyPointerActivity();
    }

    /// <summary>按住进度条/音量条拖动期间不自动隐藏。</summary>
    private void OnAudioBarPointerPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.SetControlsPressed(true);
    }

    private void OnAudioBarPointerReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.SetControlsPressed(false);
    }

    private void OnAudioBarPointerCaptureLost(object? sender, PointerCaptureLostEventArgs e)
    {
        if (DataContext is PreviewViewModel vm) vm.SetControlsPressed(false);
    }

    private void OnDataContextChanged()
    {
        var vm = DataContext as PreviewViewModel;
        if (ReferenceEquals(vm, _bound)) return;

        if (_bound is not null) _bound.PropertyChanged -= OnVmPropertyChanged;
        _bound = vm;
        _generation++;
        DetachPlayer();

        if (vm is null) return;

        vm.PropertyChanged += OnVmPropertyChanged;
        if (vm.IsImage)
        {
            _ = vm.LoadImageAsync();
            return;
        }
        if (vm.IsAudio) _ = vm.LoadCoverAsync();
        _ = InitializePlayerAsync(vm, _generation);
    }

    /// <summary>
    /// 在后台线程创建 LibVLC / MediaPlayer，主线程先把页面和控制条显示出来，
    /// 播放器准备好后再挂到 VideoView 上，避免首次预览整窗卡住。
    /// </summary>
    private async Task InitializePlayerAsync(PreviewViewModel vm, int generation)
    {
        try
        {
            var (libvlc, player, media) = await Task.Run(() =>
            {
                var vlc = SharedVlc.Value;
                var p = new MediaPlayer(vlc);
                var m = new Media(vlc, new Uri(vm.Url));
                return (vlc, p, m);
            });

            // 用户可能在加载过程中已经关掉预览或打开了另一个文件。
            if (generation != _generation || !ReferenceEquals(DataContext, vm))
            {
                media.Dispose();
                player.Dispose();
                return;
            }

            var view = this.FindControl<VideoView>("VideoView");
            // 音频只播放声音，不需要 NativeControlHost 子窗口
            if (view is not null && !vm.IsAudio) view.MediaPlayer = player;
            vm.Attach(libvlc, player, media);
            player.Play(media);
        }
        catch (Exception ex)
        {
            if (generation == _generation)
                vm.ReportError("播放器初始化失败：" + ex.Message);
        }
    }

    private void DetachPlayer()
    {
        var view = this.FindControl<VideoView>("VideoView");
        if (view is not null) view.MediaPlayer = null;
    }

    private void OnVmPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PreviewViewModel.IsFullScreen) && DataContext is PreviewViewModel vm)
            ApplyFullScreen(vm.IsFullScreen);
    }

    private void ApplyFullScreen(bool on)
    {
        if (_window is null) return;
        if (on) _window.WindowState = WindowState.FullScreen;
        else if (_window.WindowState == WindowState.FullScreen) _window.WindowState = WindowState.Normal;
    }
}
