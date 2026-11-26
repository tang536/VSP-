using System.Threading;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Threading;

namespace VSP.Desktop;

internal static class Program
{
    // 单实例：同一登录会话只允许一个主程序。再次双击快捷方式时，
    // 第二个进程只负责把已有窗口叫到前台，然后立刻退出。
    private const string MutexName = @"Local\VSP.Desktop.SingleInstance";
    private const string ShowEventName = @"Local\VSP.Desktop.ShowWindow";
    // 单实例命令行转交文件：第二个进程把 URL / 文件路径写进去，主实例激活时读取。
    private static readonly string HandoffPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "VSP解析", "incoming.txt");

    private static Mutex? _instanceMutex;
    private static EventWaitHandle? _showEvent;

    [STAThread]
    private static void Main(string[] args)
    {
        // 截图模式（--shot）是开发工具，允许和日常实例并存。
        var shotMode = Array.IndexOf(args, "--shot") >= 0;
        if (!shotMode)
        {
            _instanceMutex = new Mutex(initiallyOwned: true, MutexName, out var createdNew);
            if (!createdNew)
            {
                WriteHandoff(args);
                SignalExistingInstance();
                return;
            }

            _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
            var listener = new Thread(ListenForActivation)
            {
                IsBackground = true,
                Name = "vsp-single-instance",
            };
            listener.Start();
        }

        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        GC.KeepAlive(_instanceMutex);
    }

    private static void WriteHandoff(string[] args)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(HandoffPath)!);
            // --shot / --verbose 这类开关不转交，只转 URL 和本地文件路径。
            File.WriteAllLines(HandoffPath, args.Where(a => !a.StartsWith("--")).ToArray());
        }
        catch { /* 转交失败最多是这次拖拽/右键没生效，不影响主程序 */ }
    }

    /// <summary>主实例读取并清空转交参数，避免下次激活重复处理。</summary>
    public static string[] ConsumeHandoff()
    {
        try
        {
            if (!File.Exists(HandoffPath)) return [];
            var lines = File.ReadAllLines(HandoffPath);
            File.Delete(HandoffPath);
            return lines;
        }
        catch { return []; }
    }

    /// <summary>第二个进程：通知正在运行的实例显示窗口。</summary>
    private static void SignalExistingInstance()
    {
        // 首个实例创建互斥体后才会创建事件，这里稍等它一下。
        for (var i = 0; i < 20; i++)
        {
            try
            {
                if (EventWaitHandle.TryOpenExisting(ShowEventName, out var handle))
                {
                    handle.Set();
                    handle.Dispose();
                    return;
                }
            }
            catch
            {
                // 权限或竞态问题：忽略，下次重试。
            }
            Thread.Sleep(100);
        }
    }

    private static void ListenForActivation()
    {
        while (true)
        {
            try
            {
                _showEvent!.WaitOne();
            }
            catch
            {
                return;
            }
            Dispatcher.UIThread.Post(ActivateMainWindow);
        }
    }

    private static void ActivateMainWindow()
    {
        if (Application.Current?.ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime desktop)
            return;

        var window = desktop.MainWindow;
        if (window is null) return;

        if (!window.IsVisible)
        {
            // 还在放启动动画：等主窗口显示出来再激活。
            window.Opened += OnMainWindowOpened;
            return;
        }

        BringToFront(window);

        var incoming = ConsumeHandoff();
        if (incoming.Length > 0 && window.DataContext is MainViewModel vm)
            vm.HandleCommandLine(incoming);
    }

    private static void OnMainWindowOpened(object? sender, EventArgs e)
    {
        if (sender is not Window window) return;
        window.Opened -= OnMainWindowOpened;
        BringToFront(window);

        var incoming = ConsumeHandoff();
        if (incoming.Length > 0 && window.DataContext is MainViewModel vm)
            vm.HandleCommandLine(incoming);
    }

    private static void BringToFront(Window window)
    {
        if (window.WindowState == WindowState.Minimized)
            window.WindowState = WindowState.Normal;

        window.Show();
        window.Activate();
        // Activate() 在 Windows 上有时只闪任务栏；短暂切换 Topmost 可稳定提到最前。
        window.Topmost = true;
        window.Topmost = false;
        window.Focus();
    }

    public static AppBuilder BuildAvaloniaApp() => AppBuilder.Configure<App>()
        .UsePlatformDetect()
        .WithInterFont()
        .LogToTrace();
}
