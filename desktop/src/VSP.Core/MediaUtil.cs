using System.Text.RegularExpressions;

namespace VSP.Core;

public static class MediaUtil
{
    private static readonly string[] ImageExts = [".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg"];
    private static readonly string[] AudioExts = [".mp3", ".m4a", ".aac", ".opus", ".flac", ".wav", ".ogg", ".m4b"];

    public static bool IsImage(string url) =>
        ImageExts.Any(e => url.Contains(e, StringComparison.OrdinalIgnoreCase)) ||
        url.Contains("image", StringComparison.OrdinalIgnoreCase);

    public static bool IsAudio(string url) => AudioExts.Any(e => url.EndsWith(e, StringComparison.OrdinalIgnoreCase));

    public static bool IsMediaFile(string path)
    {
        var ext = Path.GetExtension(path);
        return ImageExts.Concat(AudioExts).Concat([".mp4", ".webm", ".mkv", ".mov", ".m4v", ".avi", ".flv", ".ts"])
                        .Any(e => string.Equals(ext, e, StringComparison.OrdinalIgnoreCase));
    }

    public static string FormatSize(long bytes)
    {
        if (bytes <= 0) return "-";
        double v = bytes;
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        int i = 0;
        while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
        return $"{v:0.0} {units[i]}";
    }

    public static string FormatDuration(double seconds)
    {
        if (seconds <= 0) return "-";
        var ts = TimeSpan.FromSeconds(seconds);
        return ts.TotalHours >= 1 ? $"{(int)ts.TotalHours}:{ts.Minutes:00}:{ts.Seconds:00}"
                                  : $"{ts.Minutes}:{ts.Seconds:00}";
    }

    public static string SafeFileName(string name, int maxLength = 80)
    {
        var safe = Regex.Replace(name.Trim(), @"[\\/:*?""<>|\r\n\t]", "_");
        safe = Regex.Replace(safe, @"\s+", " ");
        if (safe.Length > maxLength) safe = safe[..maxLength];
        return string.IsNullOrWhiteSpace(safe) ? "download" : safe;
    }

    public static string GuessExtension(string url, string fallback = ".mp4")
    {
        var clean = url.Split('?', '#')[0];
        var ext = Path.GetExtension(clean);
        if (!string.IsNullOrEmpty(ext) && ext.Length <= 6) return ext.ToLowerInvariant();
        return fallback;
    }

    public static string GuessContentType(string url)
    {
        var ext = GuessExtension(url, "");
        return ext switch
        {
            ".m3u8" => "application/vnd.apple.mpegurl",
            ".mpd" => "application/dash+xml",
            ".webm" => "video/webm",
            ".m4a" => "audio/mp4",
            ".mp3" => "audio/mpeg",
            ".jpg" or ".jpeg" => "image/jpeg",
            ".png" => "image/png",
            ".webp" => "image/webp",
            ".gif" => "image/gif",
            _ => "video/mp4",
        };
    }
}
