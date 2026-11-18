using System.Text.Json;

namespace VSP.Core;

public sealed record UpdateInfo(string Version, string DownloadUrl, string PageUrl, string Notes, long Size);

/// <summary>
/// Desktop-only update lookup. This deliberately never reads the App's apk/dist/latest.json:
/// App and Desktop have independent version lines, and an APK URL must never be offered to a
/// Windows installer. Only entries with an explicit .msi artifact are accepted.
/// </summary>
public sealed class UpdateService
{
    public const string Version = "1.0.0";
    private const string GithubApi = "https://api.github.com/repos/tang536/VSP-/releases/latest";
    private const string GiteeApi = "https://gitee.com/api/v5/repos/tang-rongqiang/VSP/releases/latest";
    private const string DesktopIndex = "https://gitee.com/tang-rongqiang/VSP/raw/main/desktop/dist/latest.json";
    private const string GithubIndex = "https://raw.githubusercontent.com/tang536/VSP-/main/desktop/dist/latest.json";
    private const string GiteeDist = "https://gitee.com/tang-rongqiang/VSP/raw/main/desktop/dist/";
    private const string GithubDist = "https://raw.githubusercontent.com/tang536/VSP-/main/desktop/dist/";
    private const string GithubPage = "https://github.com/tang536/VSP-/releases";
    public const string PageUrl = GithubPage;

    private readonly HttpClient _client;

    public UpdateService()
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = true, AutomaticDecompression = System.Net.DecompressionMethods.All };
        _client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
        _client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", RefererPolicy.UserAgent);
    }

    public async Task<UpdateInfo?> CheckAsync(CancellationToken ct = default)
    {
        // 优先级：两边的 Release 资产 > GitHub raw 索引 > Gitee raw 索引。
        // Gitee 的 raw 对大文件要登录（返回 403 large file require login for access），
        // 所以只在没有别的位置可取包时才落到它。
        var sources = new (string Url, int Rank)[]
        {
            (GiteeApi, 0),
            (GithubApi, 1),
            (GithubIndex, 2),
            (DesktopIndex, 3),
        };

        var candidates = new List<(UpdateInfo Info, int Rank)>();
        foreach (var (url, rank) in sources)
        {
            try
            {
                var json = await _client.GetStringAsync(url, ct);
                var info = Parse(url, json);
                if (info is not null) candidates.Add((info, rank));
            }
            catch { }
        }
        return candidates.Where(c => Compare(c.Info.Version, Version) > 0 && IsMsi(c.Info.DownloadUrl))
                         .OrderByDescending(c => c.Info.Version, Comparer<string>.Create(Compare))
                         .ThenBy(c => c.Rank)
                         .Select(c => c.Info)
                         .FirstOrDefault();
    }

    private static bool IsMsi(string url) =>
        url.EndsWith(".msi", StringComparison.OrdinalIgnoreCase) ||
        url.Contains(".msi?", StringComparison.OrdinalIgnoreCase);

    private static UpdateInfo? Parse(string source, string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            // 桌面版索引必须写 platform=desktop/windows；带 apk 字段而没有 msi 字段的一律是 App 更新。
            var platform = Str(root, "platform");
            if (!string.IsNullOrWhiteSpace(platform) && platform is not ("desktop" or "windows" or "win")) return null;
            var apk = Str(root, "apk");
            var msiField = Str(root, "msi");
            if (!string.IsNullOrWhiteSpace(apk) && string.IsNullOrWhiteSpace(msiField)) return null;

            var tag = root.TryGetProperty("tag_name", out var t) && t.ValueKind == JsonValueKind.String
                ? t.GetString() ?? "" : Str(root, "version");
            tag = tag.TrimStart('v', 'V');
            if (string.IsNullOrWhiteSpace(tag)) return null;

            string download = "";
            if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = Str(a, "name");
                    var url = Str(a, "browser_download_url");
                    if (name.EndsWith(".msi", StringComparison.OrdinalIgnoreCase) || IsMsi(url))
                    { download = url; break; }
                }
            }
            if (string.IsNullOrEmpty(download)) download = msiField;
            if (string.IsNullOrEmpty(download) || !IsMsi(download)) return null;

            if (!download.StartsWith("http", StringComparison.OrdinalIgnoreCase))
            {
                // 相对文件名按「索引所在的 dist 目录」补全，GitHub / Gitee 两个源都能直接下。
                var prefix = source == GithubIndex ? GithubDist : GiteeDist;
                download = prefix + download.TrimStart('/');
            }

            // 桌面版发布页与 App 发布页分开。
            var page = Str(root, "page");
            if (string.IsNullOrWhiteSpace(page)) page = GithubPage;
            return new UpdateInfo(tag, download, page, Str(root, "notes"), (long)Num(root, "size"));
        }
        catch { return null; }
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static double Num(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var n) ? n : 0;

    public static int Compare(string a, string b)
    {
        var pa = a.TrimStart('v', 'V').Split('.');
        var pb = b.TrimStart('v', 'V').Split('.');
        for (int i = 0; i < Math.Max(pa.Length, pb.Length); i++)
        {
            int.TryParse(i < pa.Length ? pa[i] : "0", out var x);
            int.TryParse(i < pb.Length ? pb[i] : "0", out var y);
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }

    public async Task DownloadAsync(UpdateInfo info, string target, IProgress<double> progress, CancellationToken ct)
    {
        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
        using var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", RefererPolicy.UserAgent);

        // 主源拿不到时自动换另一个镜像再试一次（Gitee raw 对大文件会 403）。
        var urls = new List<string> { info.DownloadUrl };
        var name = info.DownloadUrl.Split('?')[0].Split('/').LastOrDefault() ?? "";
        if (name.EndsWith(".msi", StringComparison.OrdinalIgnoreCase))
        {
            var mirror = (info.DownloadUrl.Contains("gitee", StringComparison.OrdinalIgnoreCase) ? GithubDist : GiteeDist) + name;
            if (!urls.Contains(mirror)) urls.Add(mirror);
        }

        Exception? last = null;
        foreach (var url in urls)
        {
            try
            {
                await DownloadOneAsync(client, url, target, progress, info, ct);
                return;
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex) { last = ex; }
        }
        throw last ?? new Exception("下载失败");
    }

    private static async Task DownloadOneAsync(HttpClient client, string startUrl, string target,
        IProgress<double> progress, UpdateInfo info, CancellationToken ct)
    {
        var url = startUrl;
        for (int hop = 0; hop < 8; hop++)
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            using var resp = await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
            if ((int)resp.StatusCode >= 300 && (int)resp.StatusCode < 400 && resp.Headers.Location is not null)
            {
                url = new Uri(new Uri(url), resp.Headers.Location).ToString();
                continue;
            }
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? info.Size;
            await using var src = await resp.Content.ReadAsStreamAsync(ct);
            await using var dst = new FileStream(target, FileMode.Create, FileAccess.Write, FileShare.None, 81920, true);
            var buffer = new byte[81920];
            long got = 0;
            int read;
            while ((read = await src.ReadAsync(buffer, ct)) > 0)
            {
                await dst.WriteAsync(buffer.AsMemory(0, read), ct);
                got += read;
                if (total > 0) progress.Report(got * 100.0 / total);
            }
            if (total > 0 && got < total * 0.98)
                throw new Exception($"下载不完整：{MediaUtil.FormatSize(got)} / {MediaUtil.FormatSize(total)}");
            return;
        }
        throw new Exception("重定向次数过多");
    }
}
