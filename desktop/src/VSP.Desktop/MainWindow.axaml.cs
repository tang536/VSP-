using Avalonia;
using Avalonia.Controls;
using Avalonia.Platform;

namespace VSP.Desktop;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        // 与手机端共用同一个启动图标，不再使用自造的蓝色 V。
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }
        // Screens 在构造阶段还未就绪，Opened 时再按工作区收缩。
        Opened += (_, _) => FitToScreen();
    }

    /// <summary>
    /// 1200x780 是设计尺寸；在小屏或高缩放（如 1536x864 @125%）上必须收缩，
    /// 否则窗口被系统压到工作区后，顶栏右侧会落到可见区域之外。
    /// </summary>
    private void FitToScreen()
    {
        var screen = Screens.Primary;
        if (screen is null) return;
        var scale = screen.Scaling <= 0 ? 1 : screen.Scaling;
        var workW = screen.WorkingArea.Width / scale;
        var workH = screen.WorkingArea.Height / scale;
        if (workW <= 0 || workH <= 0) return;

        Width = Math.Min(Width, Math.Max(900, workW - 40));
        Height = Math.Min(Height, Math.Max(600, workH - 40));
        MinWidth = Math.Min(MinWidth, Width);
        MinHeight = Math.Min(MinHeight, Height);

        // 收缩后重新居中，避免窗口右下角伸出工作区。
        var px = (int)Math.Round(Width * scale);
        var py = (int)Math.Round(Height * scale);
        Position = new PixelPoint(
            screen.WorkingArea.X + Math.Max(0, (screen.WorkingArea.Width - px) / 2),
            screen.WorkingArea.Y + Math.Max(0, (screen.WorkingArea.Height - py) / 2));
    }
}
