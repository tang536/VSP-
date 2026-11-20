using Avalonia.Controls;
using Avalonia.Input;

namespace VSP.Desktop.Views;

public partial class LanView : UserControl
{
    private bool _pressing;
    private double _downX, _downY;

    public LanView()
    {
        InitializeComponent();
        RemoteScreen.PointerPressed += OnScreenPressed;
        RemoteScreen.PointerMoved += OnScreenMoved;
        RemoteScreen.PointerReleased += OnScreenReleased;
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
