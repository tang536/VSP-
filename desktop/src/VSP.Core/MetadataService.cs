using System.Security.Cryptography;
using System.Text.Json;

namespace VSP.Core;

public sealed record DuplicateGroup(string Hash, long Size, IReadOnlyList<string> Paths);

/// <summary>
/// 下载完成后的归类、元数据侧车文件与重复文件扫描。
/// 不依赖 ffmpeg：元信息写同名 .json 文件，封面仍可单独下载为 .jpg。
/// </summary>
public sealed class MetadataService
{
    private readonly SettingsService _settings;

    public MetadataService(SettingsService settings) => _settings = settings;

    /// <summary>下载完成后把文件按来源 / 月份归类，并写入 sidecar 元数据。</summary>
    public async Task PostProcessAsync(DownloadTaskInfo task)
    {
        if (!_settings.ClassifyAfterDownload) return;

        var current = Path.Combine(task.SaveDir, task.Filename);
        if (!File.Exists(current)) return;

        try
        {
            var folder = string.IsNullOrWhiteSpace(task.Source) ? "其他" : MediaUtil.SafeFileName(task.Source);
            var targetDir = Path.Combine(task.SaveDir, folder, DateTime.Now.ToString("yyyy-MM"));
            Directory.CreateDirectory(targetDir);

            var name = Path.GetFileName(task.Filename);
            var target = Path.Combine(targetDir, name);
            if (File.Exists(target) && !string.Equals(Path.GetFullPath(current), Path.GetFullPath(target)))
            {
                var stem = Path.GetFileNameWithoutExtension(name);
                var ext = Path.GetExtension(name);
                var i = 2;
                while (File.Exists(Path.Combine(targetDir, $"{stem} ({i}){ext}"))) i++;
                target = Path.Combine(targetDir, $"{stem} ({i}){ext}");
            }

            await Task.Run(() => File.Move(current, target));
            task.Filename = Path.GetRelativePath(task.SaveDir, target).Replace('\\', '/');
            await WriteSidecarAsync(task, target);
        }
        catch
        {
            // 归类失败不影响下载完成状态；文件仍在下载目录里。
        }
    }

    private static async Task WriteSidecarAsync(DownloadTaskInfo task, string mediaPath)
    {
        try
        {
            var meta = new
            {
                title = task.Title,
                uploader = task.Uploader,
                source = task.Source,
                id = task.MediaId,
                format = task.FormatId,
                savedAt = DateTime.Now.ToString("O"),
            };
            var json = JsonSerializer.Serialize(meta, new JsonSerializerOptions { WriteIndented = true });
            await File.WriteAllTextAsync(mediaPath + ".json", json);
        }
        catch { }
    }

    /// <summary>按大小预筛 + SHA-256 精确比对，找出重复文件。</summary>
    public async Task<IReadOnlyList<DuplicateGroup>> ScanDuplicatesAsync(string dir)
    {
        return await Task.Run<IReadOnlyList<DuplicateGroup>>(() =>
        {
            var bySize = new Dictionary<long, List<string>>();
            try
            {
                foreach (var file in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories))
                {
                    if (file.EndsWith(".json", StringComparison.OrdinalIgnoreCase)) continue;
                    try
                    {
                        var length = new FileInfo(file).Length;
                        if (!bySize.TryGetValue(length, out var list)) bySize[length] = list = [];
                        list.Add(file);
                    }
                    catch { }
                }
            }
            catch { return Array.Empty<DuplicateGroup>(); }

            var result = new List<DuplicateGroup>();
            foreach (var (size, paths) in bySize)
            {
                if (paths.Count < 2) continue;
                var byHash = new Dictionary<string, List<string>>();
                foreach (var path in paths)
                {
                    try
                    {
                        var hash = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path)));
                        if (!byHash.TryGetValue(hash, out var group)) byHash[hash] = group = [];
                        group.Add(path);
                    }
                    catch { }
                }
                foreach (var (hash, group) in byHash)
                    if (group.Count > 1) result.Add(new DuplicateGroup(hash, size, group));
            }
            return result;
        });
    }
}
