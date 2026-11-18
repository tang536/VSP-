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
    private PreviewViewModel? _bound;
    private Window? _window;

    public PreviewView()
    {
        InitializeComponent();
        DataContextChanged += (_, _) => OnDataContextChanged();
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        _window = TopLevel.GetTopLevel(this) as Window;
        if (_window is not null) _window.KeyDown += OnKeyDown;
        if (DataContext is PreviewViewModel vm) ApplyFullScreen(vm.IsFullScreen);
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        if (_window is not null) _window.KeyDown -= OnKeyDown;
        _window = null;
        base.OnDetachedFromVisualTree(e);
    }

    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && DataContext is PreviewViewModel { IsFullScreen: true } vm)
        {
            vm.SetFullScreen(false);
            e.Handled = true;
        }
    }

    private void OnDataContextChanged()
    {
        if (DataContext is not PreviewViewModel vm)
        {
            if (_bound is not null) _bound.PropertyChanged -= OnVmPropertyChanged;
            _bound = null;
            DetachPlayer();
            return;
        }
        if (ReferenceEquals(vm, _bound)) return;

        if (_bound is not null) _bound.PropertyChanged -= OnVmPropertyChanged;
        _bound = vm;
        vm.PropertyChanged += OnVmPropertyChanged;
        DetachPlayer();

        if (!vm.IsVideo)
        {
            _ = vm.LoadImageAsync();
            return;
        }

        try
        {
            var libvlc = new LibVLC("--network-caching=300", "--no-video-title-show", "--quiet");
            var player = new MediaPlayer(libvlc);
            var media = new Media(libvlc, new Uri(vm.Url));
            var view = this.FindControl<VideoView>("VideoView");
            if (view is not null) view.MediaPlayer = player;
            vm.Attach(libvlc, player, media);
            player.Play(media);
        }
        catch (Exception ex)
        {
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
