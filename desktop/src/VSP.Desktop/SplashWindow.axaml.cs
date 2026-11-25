using System;
using System.Threading.Tasks;
using Avalonia.Controls;
using Avalonia.Media.Transformation;

namespace VSP.Desktop;

/// <summary>
/// 冷启动进场画面：图标缩放淡入 → 名称 → 副标题 → 加载条 依次淡入，
/// 停留约 1.8 秒后整层淡出并关闭，随后由 App 打开主窗口。
/// 无边框、不进任务栏，观感与手机端开场一致。
/// </summary>
public partial class SplashWindow : Window
{
    public SplashWindow()
    {
        InitializeComponent();
    }

    /// <summary>播完进场动画；由 App 在打开主窗前 await。</summary>
    public async Task PlayAsync()
    {
        try
        {
            SplashIcon.Opacity = 1;
            SplashIcon.RenderTransform = TransformOperations.Parse("scale(1)");
            await Task.Delay(160);
            SplashName.Opacity = 1;
            await Task.Delay(140);
            SplashTip.Opacity = 1;
            await Task.Delay(120);
            SplashBar.Opacity = 1;
            await Task.Delay(1300);
            Root.Opacity = 0;
            await Task.Delay(360);
        }
        catch
        {
            // 进场动画失败不应阻塞主程序启动
        }
    }
}
