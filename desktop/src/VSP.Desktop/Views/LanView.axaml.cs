using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;

namespace VSP.Desktop.Views;

public partial class LanView : UserControl
{
    private bool _pressing;
    private double _downX, _downY;
    private MainViewModel? _bound;

    public LanView()
    {
        InitializeComponent();
        RemoteScreen.PointerPressed += OnScreenPressed;
        RemoteScreen.PointerMoved += OnScreenMoved;
        RemoteScreen.PointerReleased += OnScreenReleased;
        RemoteScreen.DoubleTapped += (_, _) => ToggleFullscreen();
        AttachedToVisualTree += (_, _) => BindEvents();
        DetachedFromVisualTree += (_, _) => UnbindEvents();
    }

    private void BindEvents()
    {
        if (DataContext is not MainViewModel vm || ReferenceEquals(vm, _bound)) return;
        UnbindEvents();
        _bound = vm;
        vm.RequestOpenLanDevices += OpenDevicesWindow;
        vm.RequestPairDialog += OpenPairWindow;
        vm.RequestControlDialog += OpenControlWindow;
    }

    private void UnbindEvents()
    {
        if (_bound is null) return;
        _bound.RequestOpenLanDevices -= OpenDevicesWindow;
        _bound.RequestPairDialog -= OpenPairWindow;
        _bound.RequestControlDialog -= OpenControlWindow;
        _bound = null;
    }

    private static Window? OwnerWindow() =>
        Application.Current?.ApplicationLifetime is Avalonia.Controls.ApplicationLifetimes.IClassicDesktopStyleApplicationLifetime lifetime
            ? lifetime.MainWindow : null;

    private void OpenDevicesWindow()
    {
        var owner = OwnerWindow();
        if (owner is null) return;
        // 避免重复打开
        foreach (var w in owner.OwnedWindows)
            if (w is LanDevicesWindow) { w.Activate(); return; }
        new LanDevicesWindow(_bound!).Show(owner);
    }

    private void OpenPairWindow()
    {
        var owner = OwnerWindow();
        if (owner is null) return;
        foreach (var w in owner.OwnedWindows)
            if (w is LanPairWindow) { w.Activate(); return; }
        new LanPairWindow(_bound!).Show(owner);
    }

    private void OpenControlWindow()
    {
        var owner = OwnerWindow();
        if (owner is null) return;
        foreach (var w in owner.OwnedWindows)
            if (w is LanControlWindow) { w.Activate(); return; }
        new LanControlWindow(_bound!).Show(owner);
    }

    private void FullScreen_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs e) => ToggleFullscreen();

    private void ToggleFullscreen()
    {
        var window = OwnerWindow();
        if (window is null) return;
        window.WindowState = window.WindowState == WindowState.FullScreen
            ? WindowState.Normal
            : WindowState.FullScreen;
    }

    private bool TryGetPoint(PointerEventArgs e, out double x, out double y)
    {
        x = y = 0;
        if (DataContext is not MainViewModel vm || !vm.IsRemoteControlling) return false;
        var pos = e.GetPosition(RemoteScreen);
        var w = RemoteScreen.Bounds.Width;
        var h = RemoteScreen.Bounds.Height;
        if (w <= 0 || h <= 0) return false;
        x = Math.Clamp(pos.X / w, 0, 1);
        y = Math.Clamp(pos.Y / h, 0, 1);
        return true;
    }

    private void OnScreenPressed(object? sender, PointerPressedEventArgs e)
    {
        if (!TryGetPoint(e, out var x, out var y)) return;
        _pressing = true;
        _downX = x;
        _downY = y;
        e.Pointer.Capture(RemoteScreen);
    }

    private void OnScreenMoved(object? sender, PointerEventArgs e)
    {
        // 桌面端拖拽即视为滑动：抬起时统一发送 swipe，避免每帧都发请求。
        if (!_pressing) return;
    }

    private void OnScreenReleased(object? sender, PointerReleasedEventArgs e)
    {
        if (!_pressing) return;
        _pressing = false;
        if (DataContext is not MainViewModel vm || !vm.IsRemoteControlling) return;
        if (!TryGetPoint(e, out var x, out var y)) return;
        var dx = x - _downX;
        var dy = y - _downY;
        if (Math.Abs(dx) > 0.01 || Math.Abs(dy) > 0.01)
            vm.LanSwipeCommand.Execute((_downX, _downY, x, y));
        else
            vm.LanTapCommand.Execute((x, y));
    }
}
