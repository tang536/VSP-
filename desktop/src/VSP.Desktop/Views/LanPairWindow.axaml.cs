using Avalonia.Controls;
using Avalonia.Platform;

namespace VSP.Desktop.Views;

public partial class LanPairWindow : Window
{
    private bool _decided;

    public LanPairWindow() => InitializeComponent();

    public LanPairWindow(MainViewModel owner)
    {
        InitializeComponent();
        DataContext = owner;
        try
        {
            using var stream = AssetLoader.Open(new Uri("avares://VSP.Desktop/Assets/app.png"));
            Icon = new WindowIcon(stream);
        }
        catch { }

        void OnClose() { owner.RequestClosePairDialog -= OnClose; Close(); }
        owner.RequestClosePairDialog += OnClose;
        Closing += (_, _) =>
        {
            owner.RequestClosePairDialog -= OnClose;
            // 用户用右上角 X 关掉申请窗时视为拒绝：否则底层申请会一直挂着，
            // 手机重试只会收到 wait，表现为"连接申请发送失败"。
            if (_decided || owner.IncomingPair is null) return;
            _decided = true;
            owner.LanRejectPairCommand.Execute(null);
        };
        owner.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(MainViewModel.IncomingPair) && owner.IncomingPair is null) _decided = true;
        };
    }
}
