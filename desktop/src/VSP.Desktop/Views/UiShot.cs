using Avalonia;
using Avalonia.Controls;
using Avalonia.Media.Imaging;
using Avalonia.Threading;

namespace VSP.Desktop.Views;

/// <summary>
/// 开发期验收用的界面快照：把每个页面按 100% / 125% 两种缩放渲染成 PNG。
/// 只在命令行带 --shot 时启用，正常运行完全不受影响。
/// </summary>
internal static class UiShot
{
    public static async Task CaptureAll(Window window, MainViewModel vm, string dir)
    {
        Directory.CreateDirectory(dir);
        var wasDark = vm.IsDarkTheme;
        vm.IsDarkTheme = true;

        var pages = new (string Name, System.Windows.Input.ICommand Command)[]
        {
            ("1-home", vm.ShowHomeCommand),
            ("2-local", vm.ShowLocalCommand),
            ("3-me", vm.ShowMeCommand),
            ("4-queue", vm.ShowQueueCommand),
            ("5-storage", vm.ShowStorageCommand),
        };

        foreach (var (name, command) in pages)
        {
            command.Execute(null);
            await Settle();
            Save(window, Path.Combine(dir, name + ".png"), 1.0);
            Save(window, Path.Combine(dir, name + "@125.png"), 1.25);
        }

        // 浅色主题下再出一套「我的」页，确认浅色对比度
        vm.IsDarkTheme = false;
        vm.ShowMeCommand.Execute(null);
        await Settle();
        Save(window, Path.Combine(dir, "6-me-light.png"), 1.0);

        vm.IsDarkTheme = wasDark;
        Console.WriteLine("shot done");
    }

    private static async Task Settle()
    {
        for (var i = 0; i < 4; i++)
        {
            await Dispatcher.UIThread.InvokeAsync(() => { }, DispatcherPriority.Loaded);
            await Task.Delay(80);
        }
    }

    private static void Save(Window window, string path, double scale)
    {
        var root = window.Content as Control;
        if (root is null) return;

        var size = root.Bounds.Size;
        if (size.Width < 10 || size.Height < 10) size = window.ClientSize;

        var px = new PixelSize(
            Math.Max(1, (int)Math.Round(size.Width * scale)),
            Math.Max(1, (int)Math.Round(size.Height * scale)));

        using var rtb = new RenderTargetBitmap(px, new Vector(96 * scale, 96 * scale));
        rtb.Render(root);
        rtb.Save(path);
        Console.WriteLine($"shot {path} {px.Width}x{px.Height} (dip {size.Width:0}x{size.Height:0})");
    }
}
