using Avalonia.Controls;
using Avalonia.Platform;

namespace VSP.Desktop.Views;

public partial class LanPairWindow : Window
{
    public LanPairWindow() => InitializeComponent();

    public LanPairWindow(MainViewModel owner)
    {
        InitializeComponent();
        DataContext = owner;
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }

        void OnClose() { owner.RequestClosePairDialog -= OnClose; Close(); }
        owner.RequestClosePairDialog += OnClose;
        Closing += (_, _) => owner.RequestClosePairDialog -= OnClose;
    }
}
