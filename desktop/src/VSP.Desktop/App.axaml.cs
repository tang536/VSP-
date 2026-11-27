using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Styling;
using LibVLCSharp.Shared;
using VSP.Core;

namespace VSP.Desktop;

public partial class App : Application
{
    public static AppServices Services { get; private set; } = null!;
    private static TrayIcon? _tray;
    private static TrayIcons? _trayIcons;
    private static bool _exitRequested;
    internal static bool IsTrayHidden { get; private set; }

    /// <summary>窗口收进托盘时，接收通知至少能通过托盘提示文本让用户看见。</summary>
    public static void SetTrayHint(string message)
    {
        if (_tray is not null)
        {
            _tray.ToolTipText = "全能工具箱 · " + message;
            if (!_tray.IsVisible) _tray.IsVisible = true;
        }
    }

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

            SetupTray(desktop, window);
            // 冷启动进场动画：先显示 Splash，播完后再显示主窗口。
            var splash = new SplashWindow();
            splash.Show();
            _ = ShowMainWindowAsync(desktop, splash, window);
        }
        base.OnFrameworkInitializationCompleted();
    }

    /// <summary>
    /// 点关闭只把窗口收进托盘：后台下载 / 局域网服务继续运行，托盘菜单可以重新打开或退出。
    /// </summary>
    private static void SetupTray(IClassicDesktopStyleApplicationLifetime desktop, MainWindow window)
    {
        window.Closing += (_, e) =>
        {
            if (_exitRequested) return;
            e.Cancel = true;
            HideToTray(window);
        };
        // 最小化也收进托盘：只把窗口缩到任务栏时，托盘里没有可点的图标，
        // 用户从后台找不到入口。托盘图标在窗口 Opened 后才会真正注册。
        window.PropertyChanged += (_, e) =>
        {
            if (e.Property == Window.WindowStateProperty && window.WindowState == WindowState.Minimized)
                HideToTray(window);
        };

        var created = false;
        window.Opened += (_, _) =>
        {
            if (created) return;
            created = true;
            CreateTray(desktop, window);
        };
    }

    private static void HideToTray(Window window)
    {
        IsTrayHidden = true;
        window.WindowState = WindowState.Normal;
        window.ShowInTaskbar = false;
        window.Hide();
    }

    internal static void RestoreFromTray(Window window)
    {
        IsTrayHidden = false;
        window.Show();
        window.WindowState = WindowState.Normal;
        window.ShowInTaskbar = true;
        window.Activate();
        window.Focus();
    }

    private static void CreateTray(IClassicDesktopStyleApplicationLifetime desktop, MainWindow window)
    {
        var menu = new NativeMenu();
        var open = new NativeMenuItem("打开主界面");
        open.Click += (_, _) => RestoreFromTray(window);
        var exit = new NativeMenuItem("退出");
        exit.Click += (_, _) =>
        {
            _exitRequested = true;
            desktop.Shutdown();
        };
        menu.Add(open);
        menu.Add(new NativeMenuItemSeparator());
        menu.Add(exit);

        var tray = new TrayIcon { Menu = menu, ToolTipText = "全能工具箱" };
        tray.Clicked += (_, _) => RestoreFromTray(window);
        // 先设图标再设 IsVisible，顺序反了 Windows 上有机会注册出无图标的空白托盘项。
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            tray.Icon = new WindowIcon(stream);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine("tray icon failed: " + ex.Message);
        }

        // Avalonia 12 的托盘图标只有挂到 Application.Icons 上才会创建平台实现；
        // 单独 new TrayIcon + IsVisible=true 只是改了一个属性，不会在通知区注册任何图标。
        _tray = tray;
        _trayIcons = new TrayIcons { tray };
        TrayIcon.SetIcons(Current!, _trayIcons);
        tray.IsVisible = true;

        desktop.Exit += (_, _) =>
        {
            _tray = null;
            _trayIcons = null;
            tray.IsVisible = false;
        };
    }

    private static async System.Threading.Tasks.Task ShowMainWindowAsync(
        IClassicDesktopStyleApplicationLifetime desktop, SplashWindow splash, MainWindow window)
    {
        await splash.PlayAsync();
        window.Show();
        try { splash.Close(); } catch { /* 进场窗关闭失败不影响主窗 */ }
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
