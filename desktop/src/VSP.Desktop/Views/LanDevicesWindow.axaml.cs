using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform;

namespace VSP.Desktop.Views;

public partial class LanDevicesWindow : Window
{
    public LanDevicesWindow() => InitializeComponent();

    public LanDevicesWindow(MainViewModel owner)
    {
        InitializeComponent();
        DataContext = owner;
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }

        void OnClose() { owner.RequestCloseLanDevices -= OnClose; Close(); }
        owner.RequestCloseLanDevices += OnClose;
        Closing += (_, _) => owner.RequestCloseLanDevices -= OnClose;
    }

    private void Close_Click(object? sender, RoutedEventArgs e) => Close();
}
