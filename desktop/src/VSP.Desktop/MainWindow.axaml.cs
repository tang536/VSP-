using System.ComponentModel;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Controls;
using Avalonia.Media.Transformation;
using Avalonia.Platform;
using Avalonia.Threading;
using VSP.Desktop.Views;

namespace VSP.Desktop;

public partial class MainWindow : Window
{
    private MainViewModel? _vm;
    private bool _ready;

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
        Opened += (_, _) =>
        {
            FitToScreen();
            PlayEntrance();
        };
        DataContextChanged += (_, _) => BindViewModel();
    }

    private void BindViewModel()
    {
        if (_vm is not null) _vm.PropertyChanged -= OnViewModelPropertyChanged;
        _vm = DataContext as MainViewModel;
        if (_vm is not null) _vm.PropertyChanged += OnViewModelPropertyChanged;
    }

    /// <summary>主窗口打开时整体淡入并轻微上浮，与手机端/启动画面的节奏一致。</summary>
    private void PlayEntrance()
    {
        _ready = true;
        RootContent.Opacity = 1;
        RootContent.RenderTransform = TransformOperations.Parse("translateY(0px)");
    }

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (!_ready || _vm is null) return;

        // 只有“新页面变为可见”时才播放进场动画；隐藏旧页面不需要。
        Control? page = e.PropertyName switch
        {
            nameof(MainViewModel.IsHome) when _vm.IsHome => PageHome,
            nameof(MainViewModel.IsLocal) when _vm.IsLocal => PageLocal,
            nameof(MainViewModel.IsMePage) when _vm.IsMePage => PageMe,
            nameof(MainViewModel.IsQueuePage) when _vm.IsQueuePage => PageQueue,
            nameof(MainViewModel.IsStoragePage) when _vm.IsStoragePage => PageStorage,
            nameof(MainViewModel.IsLanPage) when _vm.IsLanPage => PageLan,
            nameof(MainViewModel.IsPreviewPage) when _vm.IsPreviewPage => PagePreview,
            _ => null,
        };
        if (page is null) return;

        // 等 IsVisible 完成布局后再播放，避免动画被首帧吞掉。
        Dispatcher.UIThread.Post(() => AnimatePage(page), DispatcherPriority.Loaded);
    }

    private static void AnimatePage(Control page)
    {
        // 先把初始状态无动画地写入，再挂上过渡并切到最终状态。
        page.Transitions = null;
        page.Opacity = 0;
        page.RenderTransform = TransformOperations.Parse("translateY(10px)");
        page.Transitions = new Transitions
        {
            new DoubleTransition
            {
                Property = OpacityProperty,
                Duration = TimeSpan.FromMilliseconds(220),
            },
            new TransformOperationsTransition
            {
                Property = RenderTransformProperty,
                Duration = TimeSpan.FromMilliseconds(260),
            },
        };

        Dispatcher.UIThread.Post(() =>
        {
            page.Opacity = 1;
            page.RenderTransform = TransformOperations.Parse("translateY(0px)");
        }, DispatcherPriority.Render);
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
