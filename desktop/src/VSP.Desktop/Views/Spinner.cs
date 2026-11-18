using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;

namespace VSP.Desktop.Views;

/// <summary>轻量转圈指示器：8 个点绕圈，解析时替代进度条。</summary>
public sealed class Spinner : Control
{
    public static readonly StyledProperty<IBrush?> BrushProperty =
        AvaloniaProperty.Register<Spinner, IBrush?>(nameof(Brush));

    public IBrush? Brush
    {
        get => GetValue(BrushProperty);
        set => SetValue(BrushProperty, value);
    }

    private readonly DispatcherTimer _timer;
    private double _angle;

    public Spinner()
    {
        Width = 18;
        Height = 18;
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(80) };
        _timer.Tick += (_, _) =>
        {
            _angle = (_angle + 45) % 360;
            InvalidateVisual();
        };
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        if (IsVisible) _timer.Start();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        _timer.Stop();
        base.OnDetachedFromVisualTree(e);
    }

    protected override void OnPropertyChanged(AvaloniaPropertyChangedEventArgs change)
    {
        base.OnPropertyChanged(change);
        if (change.Property == IsVisibleProperty)
        {
            if (IsVisible) _timer.Start(); else _timer.Stop();
        }
    }

    public override void Render(DrawingContext context)
    {
        var color = (Brush as ISolidColorBrush)?.Color ?? Colors.Orange;
        var cx = Bounds.Width / 2;
        var cy = Bounds.Height / 2;
        var radius = Math.Min(Bounds.Width, Bounds.Height) / 2 - 2.4;
        for (int i = 0; i < 8; i++)
        {
            var angle = (_angle + i * 45) * Math.PI / 180;
            var alpha = 0.12 + 0.88 * (i / 7.0);
            var dot = new SolidColorBrush(Color.FromArgb((byte)(alpha * 255), color.R, color.G, color.B));
            context.DrawEllipse(dot, null,
                new Point(cx + radius * Math.Cos(angle), cy + radius * Math.Sin(angle)), 1.7, 1.7);
        }
    }
}
