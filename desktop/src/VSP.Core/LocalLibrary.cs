namespace VSP.Core;

public sealed class LocalMedia
{
    public string Path { get; init; } = "";
    public string Name => System.IO.Path.GetFileName(Path);
    public long Length { get; init; }
    public DateTime Modified { get; init; }
    public string SizeText => MediaUtil.FormatSize(Length);
    public bool IsImage => MediaUtil.IsImage(Path);
}

public sealed class LocalLibrary
{
    public IReadOnlyList<LocalMedia> Scan(string directory)
    {
        if (string.IsNullOrWhiteSpace(directory) || !Directory.Exists(directory)) return [];
        var result = new List<LocalMedia>();
        try
        {
            foreach (var file in Directory.EnumerateFiles(directory, "*", SearchOption.TopDirectoryOnly))
            {
                if (!MediaUtil.IsMediaFile(file)) continue;
                try
                {
                    var info = new FileInfo(file);
                    result.Add(new LocalMedia { Path = file, Length = info.Length, Modified = info.LastWriteTime });
                }
                catch { }
            }
        }
        catch { }
        return result.OrderByDescending(m => m.Modified).ToList();
    }

    public static void OpenFolder(string path)
    {
        try
        {
            Directory.CreateDirectory(path);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = path, UseShellExecute = true,
            });
        }
        catch { }
    }

    public static void OpenUrl(string url)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = url, UseShellExecute = true,
            });
        }
        catch { }
    }
}
