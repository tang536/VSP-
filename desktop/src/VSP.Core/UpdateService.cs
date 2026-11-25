using System.Security.Cryptography;
using System.Text.Json;

namespace VSP.Core;

public sealed record UpdateInfo(string Version, string DownloadUrl, string PageUrl, string Notes, long Size,
    IReadOnlyList<string>? Mirrors = null, string? Sha256 = null);

/// <summary>
/// Desktop-only update lookup. This deliberately never reads the App's apk/dist/latest.json:
/// App and Desktop have independent version lines, and an APK URL must never be offered to a
/// Windows installer. Only entries with an explicit .msi artifact are accepted.
/// </summary>
public sealed class UpdateService
{
    public const string Version = "1.2.2";
    private const string GithubApi = "https://api.github.com/repos/tang536/VSP-/releases/latest";
    private const string GiteeApi = "https://gitee.com/api/v5/repos/tang-rongqiang/VSP/releases/latest";
    private const string DesktopIndex = "https://gitee.com/tang-rongqiang/VSP/raw/main/desktop/dist/latest.json";
    private const string GithubIndex = "https://raw.githubusercontent.com/tang536/VSP-/main/desktop/dist/latest.json";
    private const string GiteeDist = "https://gitee.com/tang-rongqiang/VSP/raw/main/desktop/dist/";
    private const string GithubDist = "https://raw.githubusercontent.com/tang536/VSP-/main/desktop/dist/";
    private const string GithubRelease = "https://github.com/tang536/VSP-/releases/download/";
    private const string GiteeRelease = "https://gitee.com/tang-rongqiang/VSP/releases/download/";
    private const string GithubPage = "https://github.com/tang536/VSP-/releases";
    public const string PageUrl = GithubPage;

    private readonly HttpClient _client;

    public UpdateService()
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = true, AutomaticDecompression = System.Net.DecompressionMethods.All };
        _client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
        _client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", RefererPolicy.UserAgent);
    }

    public async Task<(UpdateInfo? Update, bool Reached)> CheckAsync(CancellationToken ct = default)
    {
        // 优先级：两边的 Release 资产 > GitHub raw 索引 > Gitee raw 索引。
        // Gitee 的 raw 对大文件要登录（返回 403 large file require login for access），
        // 所以只在没有别的位置可取包时才落到它。
        var sources = new (string Url, int Rank)[]
        {
            (GiteeApi, 0),
            (GithubApi, 1),
            (DesktopIndex, 2),
            (GithubIndex, 3),
        };

        // 四个源并发拉取：串行的话断网时要一个接一个等超时，最坏两分钟。
        var tasks = sources.Select(async s =>
        {
            try
            {
                var json = await _client.GetStringAsync(s.Url, ct);
                return (Ok: true, s.Rank, Info: Parse(s.Url, json));
            }
            catch
            {
                return (Ok: false, s.Rank, Info: (UpdateInfo?)null);
            }
        });
        var results = await Task.WhenAll(tasks);

        var candidates = results.Where(r => r.Ok && r.Info is not null)
                                .Select(r => (Info: r.Info!, r.Rank))
                                .ToList();
        var reached = results.Count(r => r.Ok);

        var best = candidates.Where(c => Compare(c.Info.Version, Version) > 0 && IsMsi(c.Info.DownloadUrl))
                             .OrderByDescending(c => c.Info.Version, Comparer<string>.Create(Compare))
                             .ThenBy(c => c.Rank)
                             .Select(c => c.Info)
                             .FirstOrDefault();

        // Reached=false 表示四个源全都连不上（离线 / 被墙），调用方要跟"已是最新"区分开。
        return (best, reached > 0);
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
            // 桌面版用 desktop-v1.0.0 这种带前缀的标签，和 App 的 v1.1.x 完全分开。
            tag = tag.Trim();
            if (tag.StartsWith("desktop-", StringComparison.OrdinalIgnoreCase)) tag = tag["desktop-".Length..];
            tag = tag.TrimStart('v', 'V');
            if (string.IsNullOrWhiteSpace(tag)) return null;

            // App 的 APK release 没有任何 .msi 资产，走不到下面，天然被排除。
            string download = "";
            var mirrors = new List<string>();
            var sha = NormalizeSha(Str(root, "sha256"));
            if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in assets.EnumerateArray())
                {
                    var name = Str(a, "name");
                    var url = Str(a, "browser_download_url");
                    if ((name.EndsWith(".msi", StringComparison.OrdinalIgnoreCase) || IsMsi(url)) &&
                        !string.IsNullOrWhiteSpace(url) && !mirrors.Contains(url, StringComparer.OrdinalIgnoreCase))
                    {
                        mirrors.Add(url);
                        // GitHub Release 资产带 digest=sha256:...，可用于下载后校验。
                        if (sha is null) sha = NormalizeSha(Str(a, "digest"));
                    }
                }
            }
            // 索引里可显式列出镜像（不同 CDN / 不同平台 Release），全部纳入候选。
            if (root.TryGetProperty("mirrors", out var mir) && mir.ValueKind == JsonValueKind.Array)
                foreach (var m in mir.EnumerateArray())
                    if (m.ValueKind == JsonValueKind.String)
                    {
                        var u = m.GetString() ?? "";
                        if (!string.IsNullOrWhiteSpace(u) && !mirrors.Contains(u, StringComparer.OrdinalIgnoreCase))
                            mirrors.Add(u);
                    }
            if (mirrors.Count > 0) download = mirrors[0];
            if (string.IsNullOrEmpty(download)) download = msiField;
            if (string.IsNullOrEmpty(download) || !IsMsi(download)) return null;

            if (!download.StartsWith("http", StringComparison.OrdinalIgnoreCase))
            {
                // 相对文件名按「索引所在的 dist 目录」补全，GitHub / Gitee 两个源都能直接下。
                var prefix = source == GithubIndex ? GithubDist : GiteeDist;
                download = prefix + download.TrimStart('/');
                mirrors = mirrors.Select(m => m.StartsWith("http", StringComparison.OrdinalIgnoreCase)
                    ? m : prefix + m.TrimStart('/')).ToList();
            }

            // 桌面版发布页与 App 发布页分开。
            var page = Str(root, "page");
            if (string.IsNullOrWhiteSpace(page)) page = GithubPage;
            return new UpdateInfo(tag, download, page, Str(root, "notes"), (long)Num(root, "size"), mirrors, sha);
        }
        catch { return null; }
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    private static double Num(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var n) ? n : 0;

    private static string? NormalizeSha(string? value)
    {
        var v = (value ?? "").Trim();
        if (v.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase)) v = v[7..].Trim();
        if (v.Length != 64 || !v.All(char.IsAsciiHexDigit)) return null;
        return v.ToLowerInvariant();
    }

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

    public async Task DownloadAsync(UpdateInfo info, string target, IProgress<double> progress,
        IProgress<string>? status, CancellationToken ct)
    {
        var name = info.DownloadUrl.Split('?')[0].Split('/').LastOrDefault() ?? "";
        var ver = info.Version.TrimStart('v', 'V');
        var candidates = new List<string>();
        void Add(string u) { if (!string.IsNullOrWhiteSpace(u) && !candidates.Contains(u, StringComparer.OrdinalIgnoreCase)) candidates.Add(u); }
        Add(info.DownloadUrl);
        if (info.Mirrors is not null) foreach (var m in info.Mirrors) Add(m);
        if (name.EndsWith(".msi", StringComparison.OrdinalIgnoreCase))
        {
            // GitHub / Gitee 桌面版 Release 同名资产 + 两个 raw 目录，全部作为候选源。
            Add(GithubRelease + "desktop-v" + ver + "/" + name);
            Add(GiteeRelease + "desktop-v" + ver + "/" + name);
            Add(GithubDist + name);
            Add(GiteeDist + name);
        }

        // 下载前并发测速：各源取前 256KB 计算速率，按快到慢排序。
        status?.Report("正在测速，自动选择最快的下载源…");
        using var probeClient = new HttpClient(new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = System.Net.DecompressionMethods.All
        }) { Timeout = TimeSpan.FromSeconds(12) };
        probeClient.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", RefererPolicy.UserAgent);

        var probes = await Task.WhenAll(candidates.Select(async url =>
        {
            var speed = await ProbeSpeedAsync(probeClient, url, ct);
            return (Url: url, Speed: speed);
        }));
        var ok = probes.Where(p => p.Speed > 0).OrderByDescending(p => p.Speed).ToList();
        var ranked = ok.Select(p => p.Url)
            .Concat(probes.Where(p => p.Speed <= 0).Select(p => p.Url)).ToList();
        if (ranked.Count == 0) throw new Exception("没有可用的下载源");
        status?.Report(ok.Count > 0
            ? $"已选最快源：{Friendly(ok[0].Url)}（{ok[0].Speed:F1} MB/s），开始下载…"
            : "测速未完成，按默认顺序下载…");

        using var handler = new HttpClientHandler { AllowAutoRedirect = false };
        using var client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        client.DefaultRequestHeaders.TryAddWithoutValidation("User-Agent", RefererPolicy.UserAgent);

        Exception? last = null;
        foreach (var url in ranked)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                status?.Report($"正在从 {Friendly(url)} 下载…");
                await DownloadOneAsync(client, url, target, progress, info, ct);
                if (info.Sha256 is { Length: 64 })
                {
                    status?.Report("正在校验安装包…");
                    var actual = await Sha256Async(target, ct);
                    if (!actual.Equals(info.Sha256, StringComparison.OrdinalIgnoreCase))
                    {
                        TryDelete(target);
                        throw new Exception("安装包校验不一致（可能下载到了旧版本），已自动切换下载源");
                    }
                }
                return;
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception ex)
            {
                last = ex;
                status?.Report($"{Friendly(url)} 下载失败，自动切换下一个源…");
            }
        }
        throw last ?? new Exception("下载失败");
    }

    /// <summary>取一小段数据测算该源速度；不支持 Range 的源读够即断开，不会下载整包。</summary>
    private static async Task<double> ProbeSpeedAsync(HttpClient client, string url, CancellationToken ct)
    {
        const int limit = 262144;
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            req.Headers.TryAddWithoutValidation("Range", "bytes=0-" + (limit - 1));
            using var resp = await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
            var code = (int)resp.StatusCode;
            if (code >= 400) return -1;
            await using var s = await resp.Content.ReadAsStreamAsync(ct);
            var buf = new byte[16384];
            long got = 0;
            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (got < limit)
            {
                var n = await s.ReadAsync(buf.AsMemory(0, (int)Math.Min(buf.Length, limit - got)), ct);
                if (n <= 0) break;
                got += n;
            }
            sw.Stop();
            if (got < 8192 || sw.Elapsed.TotalSeconds <= 0) return -1;
            return got / 1024.0 / 1024.0 / sw.Elapsed.TotalSeconds;
        }
        catch { return -1; }
    }

    private static string Friendly(string url) =>
        url.Contains("gitee", StringComparison.OrdinalIgnoreCase) ? "Gitee 镜像"
        : url.Contains("github", StringComparison.OrdinalIgnoreCase) ? "GitHub"
        : "备用源";

    private static async Task<string> Sha256Async(string path, CancellationToken ct)
    {
        await using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, true);
        var hash = await SHA256.HashDataAsync(fs, ct);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static void TryDelete(string path)
    {
        try { File.Delete(path); } catch { }
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
            if (total > 0 && got < total)
                throw new Exception($"下载不完整：{MediaUtil.FormatSize(got)} / {MediaUtil.FormatSize(total)}");
            return;
        }
        throw new Exception("重定向次数过多");
    }
}
