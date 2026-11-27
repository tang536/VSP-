using Avalonia.Controls;

namespace VSP.Desktop.Views;

public partial class DownloadSettingsWindow : Window
{
    public string ResultTemplate { get; private set; }
    public int ResultTtl { get; private set; }

    public DownloadSettingsWindow() : this("{title} [{quality}]", 600) { }

    public DownloadSettingsWindow(string template, int ttl)
    {
        InitializeComponent();
        ResultTemplate = template;
        ResultTtl = ttl;
        TemplateBox.Text = template;
        TtlBox.Value = ttl;
    }

    private void Save_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        var t = TemplateBox.Text?.Trim();
        ResultTemplate = string.IsNullOrEmpty(t) ? "{title} [{quality}]" : t;
        ResultTtl = (int)Math.Clamp(TtlBox.Value ?? 600, 0, 864000);
        Close(true);
    }

    private void Cancel_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs e) => Close(false);
}
