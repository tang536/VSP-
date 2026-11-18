using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Platform;

namespace VSP.Desktop.Views;

public partial class HistoryWindow : Window
{
    // XAML 运行时加载器要求公共无参构造；实际使用走带 MainViewModel 的构造函数。
    public HistoryWindow() => InitializeComponent();

    public HistoryWindow(MainViewModel owner)
    {
        InitializeComponent();
        DataContext = owner;
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }

        void OnPicked()
        {
            owner.HistoryItemPicked -= OnPicked;
            Close();
        }
        owner.HistoryItemPicked += OnPicked;
        Closing += (_, _) => owner.HistoryItemPicked -= OnPicked;
    }

    private void Close_Click(object? sender, RoutedEventArgs e) => Close();
}
