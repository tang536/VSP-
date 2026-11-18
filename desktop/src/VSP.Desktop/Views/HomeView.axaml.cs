using Avalonia.Controls;
using Avalonia.Input;
using VSP.Desktop;

namespace VSP.Desktop.Views;

public partial class HomeView : UserControl
{
    public HomeView() => InitializeComponent();

    private void UrlBox_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter || DataContext is not MainViewModel vm) return;
        vm.ParseCommand.Execute(null);
    }
}
