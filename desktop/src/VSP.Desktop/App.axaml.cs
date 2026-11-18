using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Styling;
using LibVLCSharp.Shared;
using VSP.Core;

namespace VSP.Desktop;

public partial class App : Application
{
    public static AppServices Services { get; private set; } = null!;

    public override void Initialize() => AvaloniaXamlLoader.Load(this);

    public override void OnFrameworkInitializationCompleted()
    {
        Services = new AppServices();
        ApplyTheme(Services.Settings.Theme);
        try
        {
            var vlcDir = Path.Combine(AppContext.BaseDirectory, "libvlc", "win-x64");
            LibVLCSharp.Shared.Core.Initialize(Directory.Exists(vlcDir) ? vlcDir : AppContext.BaseDirectory);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine("VLC init failed: " + ex.Message);
        }

        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var vm = new MainViewModel(Services);
            var window = new MainWindow { DataContext = vm };
            desktop.MainWindow = window;
            desktop.Exit += (_, _) => Services.Dispose();

            // 开发期验收：--shot <目录> 会依次渲染各页截图后退出。
            var args = Environment.GetCommandLineArgs();
            var idx = Array.IndexOf(args, "--shot");
            if (idx >= 0)
            {
                var dir = idx + 1 < args.Length && !args[idx + 1].StartsWith("--")
                    ? args[idx + 1]
                    : Path.Combine(AppContext.BaseDirectory, "shots");
                window.Opened += async (_, _) =>
                {
                    try { await Views.UiShot.CaptureAll(window, vm, dir); }
                    catch (Exception ex) { Console.WriteLine("shot failed: " + ex); }
                    desktop.Shutdown();
                };
            }
        }
        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>
    /// Web 端用 CSS 变量切换主题；桌面端用同名调色板替换 DynamicResource。
    /// 两套颜色值取自 Web/front/src/App.css 与 Android res/values。
    /// </summary>
    public static void ApplyTheme(string theme)
    {
        var dark = string.Equals(theme, "Dark", StringComparison.OrdinalIgnoreCase);
        var palette = new Dictionary<string, string>();
        void Add(string key, string hex) => palette[key] = hex;

        if (dark)
        {
            Add("AppBackground", "#0F1115");
            Add("Surface", "#171A21");
            Add("Surface2", "#1F232C");
            Add("Ink", "#E8EAED");
            Add("InkDim", "#8B909A");
            Add("Line", "#2A2E38");
            Add("Accent", "#F97316");
            Add("AccentPress", "#EA6A0C");
            Add("AccentSoft", "#3A2415");
            Add("Danger", "#EF4444");
            Add("DangerSoft", "#3A1D1D");
            Add("Ok", "#22C55E");
            Add("OkSoft", "#163522");
            Add("ChipBg", "#1F232C");
            Add("OnAccent", "#FFFFFF");
            Add("PlayerBar", "#E6192231");
        }
        else
        {
            Add("AppBackground", "#F7F7F8");
            Add("Surface", "#FFFFFF");
            Add("Surface2", "#F0F0F2");
            Add("Ink", "#1A1A1A");
            Add("InkDim", "#6B7280");
            Add("Line", "#E5E7EB");
            Add("Accent", "#EA580C");
            Add("AccentPress", "#C2410C");
            Add("AccentSoft", "#FFF1E6");
            Add("Danger", "#DC2626");
            Add("DangerSoft", "#FEE2E2");
            Add("Ok", "#16A34A");
            Add("OkSoft", "#DCFCE7");
            Add("ChipBg", "#F0F0F2");
            Add("OnAccent", "#FFFFFF");
            Add("PlayerBar", "#E6192231");
        }

        // 只覆盖调色板键，保留 XAML 里定义的图标几何等静态资源。
        var res = Current!.Resources;
        foreach (var (key, hex) in palette) res[key] = B(hex);
        Current.RequestedThemeVariant = dark ? ThemeVariant.Dark : ThemeVariant.Light;
    }

    private static SolidColorBrush B(string hex)
    {
        var h = hex.TrimStart('#');
        if (h.Length == 6) h = "FF" + h;
        return new SolidColorBrush(Color.FromUInt32(Convert.ToUInt32(h, 16)));
    }
}
