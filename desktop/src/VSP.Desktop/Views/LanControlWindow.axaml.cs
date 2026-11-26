using Avalonia.Controls;
using Avalonia.Platform;

namespace VSP.Desktop.Views;

public partial class LanControlWindow : Window
{
    public LanControlWindow() => InitializeComponent();

    public LanControlWindow(MainViewModel owner)
    {
        InitializeComponent();
        DataContext = owner;
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }

        void OnClose() { owner.RequestCloseControlDialog -= OnClose; Close(); }
        owner.RequestCloseControlDialog += OnClose;
        Closing += (_, _) => owner.RequestCloseControlDialog -= OnClose;
    }
}
