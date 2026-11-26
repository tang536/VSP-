using VSP.Core;

namespace VSP.Desktop;

/// <summary>Composition root: the same shape as the layered C# projects on this machine.</summary>
public sealed class AppServices : IDisposable
{
    public SettingsService Settings { get; }
    public PythonEngine Engine { get; }
    public DownloadManager Downloads { get; }
    public PreviewProxy Preview { get; }
    public UpdateService Updates { get; }
    public LocalLibrary Local { get; }
    public StorageService Storage { get; }
    public MetadataService Metadata { get; }

    public AppServices()
    {
        Settings = new SettingsService();
        Directory.CreateDirectory(Settings.DownloadDir);
        Engine = new PythonEngine(AppContext.BaseDirectory);
        Metadata = new MetadataService(Settings);
        Downloads = new DownloadManager(Engine, Settings, Metadata.PostProcessAsync);
        Preview = new PreviewProxy();
        Updates = new UpdateService();
        Local = new LocalLibrary();
        Storage = new StorageService();
    }

    public void Dispose() => Preview.Dispose();
}
