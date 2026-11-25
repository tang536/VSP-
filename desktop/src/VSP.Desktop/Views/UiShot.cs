using Avalonia;
using Avalonia.Controls;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using VSP.Core;

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

        // 预览页：内嵌播放器 + 进度/音量/全屏控制条
        vm.OpenPreview(new PreviewRequest("示例视频 · 内嵌播放器验收", "about:blank", false));
        await Settle();
        Save(window, Path.Combine(dir, "7-preview.png"), 1.0);
        if (vm.Preview is { } previewVm)
        {
            previewVm.SetFullScreen(true);
            await Settle();
            Save(window, Path.Combine(dir, "7b-preview-fullscreen.png"), 1.0);
            previewVm.SetFullScreen(false);
            await Settle();
        }
        vm.ClosePreviewCommand.Execute(null);

        // 音频预览：手机端风格播放控制（进度行 + 控制行 + 音量浮层）
        vm.OpenPreview(new PreviewRequest("示例音频 · 沉浸式预览", "about:blank", false, true, ""));
        await Settle();
        Save(window, Path.Combine(dir, "7c-audio.png"), 1.0);
        if (vm.Preview is { } audioVm)
        {
            audioVm.ToggleVolumePopupCommand.Execute(null);
            await Settle();
            Save(window, Path.Combine(dir, "7d-audio-volume.png"), 1.0);
            // 鼠标移出控制条后 3 秒应自动隐藏（先清掉示例 URL 的播放错误，
            // 否则错误状态会有意保持控制条可见）
            audioVm.ReportError("");
            audioVm.SetControlsHover(false);
            audioVm.NotifyPointerActivity();
            await Task.Delay(3400);
            Save(window, Path.Combine(dir, "7e-audio-hidden.png"), 1.0);
        }
        vm.ClosePreviewCommand.Execute(null);

        // 局域网互传与远控页：验收引导页 + 附近设备弹窗
        vm.ShowLanCommand.Execute(null);
        await Settle();
        vm.IsDarkTheme = true;
        var dev = vm.LanDevices.FirstOrDefault() ?? new LanDevice
        {
            Name = "示例手机", Ip = "192.168.1.20", Kind = "android", LastSeen = DateTime.Now
        };
        if (!vm.LanDevices.Contains(dev)) vm.LanDevices.Add(dev);
        Save(window, Path.Combine(dir, "6b-lan-control.png"), 1.0);
        // 设备弹窗
        var devicesWin = new LanDevicesWindow(vm);
        devicesWin.Show();
        await Settle();
        Save(devicesWin, Path.Combine(dir, "6c-lan-devices.png"), 1.0);
        devicesWin.Close();

        // 历史解析弹窗
        var history = new HistoryWindow(vm);
        history.Show();
        await Settle();
        Save(history, Path.Combine(dir, "8-history.png"), 1.25);
        history.Close();

        // 首次解析结果：默认 Tab 就是 0，专门验证结果列表仍会被填充
        vm.ShowHomeCommand.Execute(null);
        vm.Url = "https://example.com/watch?v=demo";
        var fake = new ParseResult
        {
            Title = "示例视频 · 结果列表验收",
            Uploader = "演示频道",
            Duration = 372,
            Description = "用于验收首次解析后的结果卡片与格式列表",
            Source = "示例站点",
        };
        fake.Videos.Add(new MediaFormat
        {
            FormatId = "1080p", Quality = "1080p", Ext = "mp4", Container = "mp4",
            Width = 1920, Height = 1080, Size = "128.4 MiB", Url = "https://example.com/v1080.mp4",
            Protocol = "https", Vcodec = "h264", Acodec = "aac",
        });
        fake.Videos.Add(new MediaFormat
        {
            FormatId = "720p", Quality = "720p", Ext = "mp4", Container = "mp4",
            Width = 1280, Height = 720, Size = "64.2 MiB", Url = "https://example.com/v720.mp4",
            Protocol = "https", Vcodec = "h264", Acodec = "aac",
        });
        fake.Audios.Add(new MediaFormat
        {
            FormatId = "audio", Quality = "128kbps", Ext = "m4a", Size = "5.6 MiB",
            Url = "https://example.com/a.m4a", Protocol = "https",
        });
        vm.ApplyResult(fake);
        await Settle();
        Save(window, Path.Combine(dir, "9-first-result.png"), 1.0);

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
