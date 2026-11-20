using Avalonia.Controls;
using Avalonia.Input;

namespace VSP.Desktop.Views;

public partial class LanView : UserControl
{
    public LanView()
    {
        InitializeComponent();
        RemoteScreen.PointerPressed += OnScreenPressed;
    }

    private void OnScreenPressed(object? sender, PointerPressedEventArgs e)
    {
        if (DataContext is not MainViewModel vm || !vm.IsRemoteControlling) return;
        var pos = e.GetPosition(RemoteScreen);
        var w = RemoteScreen.Bounds.Width;
        var h = RemoteScreen.Bounds.Height;
        if (w <= 0 || h <= 0) return;
        var x = Math.Clamp(pos.X / w, 0, 1);
        var y = Math.Clamp(pos.Y / h, 0, 1);
        vm.LanTapCommand.Execute((x, y));
    }
}
