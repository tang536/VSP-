namespace VSP.Core;

public sealed record DiskUsage(long Total, long Free)
{
    public long Used => Math.Max(0, Total - Free);
    public double UsagePercent => Total > 0 ? Used * 100.0 / Total : 0;
}

/// <summary>
/// 存储统计：磁盘容量、预览缓存占用与清理。对应 App 端「存储管理」页。
/// </summary>
public sealed class StorageService
{
    public string CacheDir => Path.Combine(Path.GetTempPath(), "vsp-preview");

    public DiskUsage Disk(string path)
    {
        try
        {
            var root = Path.GetPathRoot(Path.GetFullPath(path));
            if (string.IsNullOrEmpty(root)) return new DiskUsage(0, 0);
            var drive = new DriveInfo(root);
            return drive.IsReady ? new DiskUsage(drive.TotalSize, drive.AvailableFreeSpace) : new DiskUsage(0, 0);
        }
        catch { return new DiskUsage(0, 0); }
    }

    public long CacheBytes() => DirectoryBytes(CacheDir);

    public long DirectoryBytes(string dir)
    {
        try
        {
            if (!Directory.Exists(dir)) return 0;
            long total = 0;
            foreach (var file in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
            {
                try { total += new FileInfo(file).Length; } catch { }
            }
            return total;
        }
        catch { return 0; }
    }

    public void ClearCache()
    {
        try
        {
            if (!Directory.Exists(CacheDir)) return;
            foreach (var file in Directory.EnumerateFiles(CacheDir))
            {
                try { File.Delete(file); } catch { }
            }
        }
        catch { }
    }
}
