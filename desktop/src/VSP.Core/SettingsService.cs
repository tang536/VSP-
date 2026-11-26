using System.Text.Json;

namespace VSP.Core;

/// <summary>Small JSON settings file, kept next to the other VSP data (%APPDATA%\VSP解析).</summary>
public sealed class SettingsService
{
    private sealed class SettingsDto
    {
        public string DownloadDir { get; set; } = "";
        public string Theme { get; set; } = "Light";
        public List<string> Recent { get; set; } = [];
        public int MaxConcurrentDownloads { get; set; } = 2;
        public int SpeedLimitKbps { get; set; } = 0;
        public string NameTemplate { get; set; } = "{title} [{quality}]";
        public bool ClassifyAfterDownload { get; set; } = true;
        public int ParseTtlSeconds { get; set; } = 600;

    }

    private readonly string _dir;
    private readonly string _file;
    private SettingsDto _data;

    public SettingsService()
    {
        _dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "VSP解析");
        _file = Path.Combine(_dir, "settings.json");
        _data = Load();
        if (string.IsNullOrWhiteSpace(_data.DownloadDir))
            _data.DownloadDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "VSP解析");
    }

    public string DownloadDir
    {
        get => _data.DownloadDir;
        set { _data.DownloadDir = value; Save(); }
    }

    public string Theme
    {
        get => _data.Theme;
        set { _data.Theme = value; Save(); }
    }

    public int MaxConcurrentDownloads
    {
        get => Math.Clamp(_data.MaxConcurrentDownloads, 1, 6);
        set { _data.MaxConcurrentDownloads = Math.Clamp(value, 1, 6); Save(); }
    }

    public int SpeedLimitKbps
    {
        get => Math.Max(0, _data.SpeedLimitKbps);
        set { _data.SpeedLimitKbps = Math.Max(0, value); Save(); }
    }

    public string NameTemplate
    {
        get => _data.NameTemplate;
        set { _data.NameTemplate = value; Save(); }
    }

    public bool ClassifyAfterDownload
    {
        get => _data.ClassifyAfterDownload;
        set { _data.ClassifyAfterDownload = value; Save(); }
    }

    public int ParseTtlSeconds
    {
        get => Math.Max(0, _data.ParseTtlSeconds);
        set { _data.ParseTtlSeconds = Math.Max(0, value); Save(); }
    }

    public IReadOnlyList<string> Recent => _data.Recent;

    public void AddRecent(string url)
    {
        if (string.IsNullOrWhiteSpace(url)) return;
        _data.Recent.Remove(url);
        _data.Recent.Insert(0, url);
        if (_data.Recent.Count > 12) _data.Recent.RemoveRange(12, _data.Recent.Count - 12);
        Save();
    }

    public void ClearRecent() { _data.Recent.Clear(); Save(); }

    private SettingsDto Load()
    {
        try
        {
            if (File.Exists(_file))
                return JsonSerializer.Deserialize<SettingsDto>(File.ReadAllText(_file)) ?? new SettingsDto();
        }
        catch { }
        return new SettingsDto();
    }

    private void Save()
    {
        try
        {
            Directory.CreateDirectory(_dir);
            File.WriteAllText(_file, JsonSerializer.Serialize(_data, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}
