using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Text;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

var builder = WebApplication.CreateBuilder(args);

// 端口：硬编码 80 会在已有实例 / 系统服务占用时直接抛 AddressInUseException 导致「启动报错」。
// 现在优先读取 VSP_PORT 或 --port，默认仍为 80；被占用时自动顺延到下一个空闲端口，并打印真实地址。
var presetUrls = Environment.GetEnvironmentVariable("ASPNETCORE_URLS");
int port = 80;
var envPort = Environment.GetEnvironmentVariable("VSP_PORT");
if (int.TryParse(envPort, out var ep) && ep > 0 && ep <= 65535) port = ep;
var portArg = Array.IndexOf(args, "--port");
if (portArg >= 0 && portArg + 1 < args.Length && int.TryParse(args[portArg + 1], out var ap) && ap > 0 && ap <= 65535) port = ap;
if (string.IsNullOrWhiteSpace(presetUrls))
{
    port = FirstFreePort(port);
    builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
}

// 静态文件目录：优先 wwwroot（发布后），其次 ../front/dist（开发时）
var contentRoot = builder.Environment.ContentRootPath;
var wwwroot = Path.Combine(contentRoot, "wwwroot");
var devFrontDist = Path.GetFullPath(Path.Combine(contentRoot, "..", "front", "dist"));
if (!Directory.Exists(wwwroot) && Directory.Exists(devFrontDist))
{
    wwwroot = devFrontDist;
}

// 默认保存目录：目录不可写不能让整个服务起不来
var saveDir = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
    "Downloads", "VSP解析");
try { Directory.CreateDirectory(saveDir); }
catch (Exception ex) { Console.Error.WriteLine($"下载目录不可写，将使用临时目录：{ex.Message}"); saveDir = Path.GetTempPath(); }

static int FirstFreePort(int start)
{
    for (int p = start; p < start + 24 && p <= 65535; p++)
    {
        try
        {
            // 用与 Kestrel 相同的 0.0.0.0 + 独占绑定来探测；
            // 仅探测 127.0.0.1 在 Windows 的 SO_REUSEADDR 下会误判为空闲。
            using var probe = new System.Net.Sockets.Socket(
                System.Net.Sockets.AddressFamily.InterNetwork,
                System.Net.Sockets.SocketType.Stream,
                System.Net.Sockets.ProtocolType.Tcp);
            probe.ExclusiveAddressUse = true;
            probe.Bind(new System.Net.IPEndPoint(System.Net.IPAddress.Any, p));
            return p;
        }
        catch (System.Net.Sockets.SocketException) { /* 已占用，继续找 */ }
    }
    return start;
}

builder.Services.AddSingleton(new DownloadTaskManager());
builder.Services.AddSingleton(new YtDlpRunner());
builder.Services.AddCors(o => o.AddDefaultPolicy(p => p.AllowAnyOrigin().AllowAnyHeader().AllowAnyMethod()));

var app = builder.Build();
app.UseCors();

// ---------------- API ----------------

app.MapGet("/api/config", () => Results.Ok(new { saveDir }));

// 打开浏览器默认下载目录（~/Downloads）
app.MapGet("/api/open-folder", () =>
{
    var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    if (!Directory.Exists(dir)) Directory.CreateDirectory(dir);
    System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
    {
        FileName = dir,
        UseShellExecute = true,
    });
    return Results.Ok(new { dir });
});

app.MapPost("/api/parse", async (ParseReq req, YtDlpRunner yt, ILoggerFactory logger) =>
{
    if (string.IsNullOrWhiteSpace(req.Url))
        return Results.BadRequest(new { detail = "URL 不能为空" });
    try
    {
        var result = await yt.ParseAsync(req.Url);
        return Results.Ok(result);
    }
    catch (Exception ex)
    {
        // yt-dlp 不认这类「archives」站点（Unsupported URL）。app 端 vsp.py 自带站点提取器，
        // 这里回退到同一份引擎，web 与 app 的解析能力保持一致。
        try
        {
            var json = await VspBridge.ParseAsync(req.Url);
            // 引擎偶发不吐出结果（进程被系统杀掉、输出被截断）时会返回空串，
            // 直接 200 + 空响应体，前端 r.json() 会抛「Unexpected end of JSON input」。
            if (string.IsNullOrWhiteSpace(json) || !json.TrimStart().StartsWith("{"))
                throw new Exception("解析引擎没有返回结果，请重试");
            return Results.Text(json, "application/json");
        }
        catch (Exception ex2)
        {
            var note = $"{ex.Message}；App 引擎：{ex2.Message}";
            return Results.BadRequest(new { detail = note });
        }
    }
});

app.MapPost("/api/download", async (DownloadReq req, DownloadTaskManager mgr, YtDlpRunner yt) =>
{
    var dir = string.IsNullOrWhiteSpace(req.SaveDir) ? saveDir : req.SaveDir;
    Directory.CreateDirectory(dir);
    var task = mgr.Create(req.Url, req.Title ?? "video", dir);
    var direct = req.DirectUrl ?? "";
    var isHls = direct.Contains(".m3u8", StringComparison.OrdinalIgnoreCase);
    var isDirect = !string.IsNullOrEmpty(direct)
                   && ((req.FormatId ?? "").StartsWith("url:") || isHls);

    if (req.Kind == "image" && !string.IsNullOrEmpty(direct))
    {
        _ = yt.DownloadImageAsync(task, direct, req.Title ?? "image", dir);
    }
    else if (isDirect)
    {
        // 直链 / m3u8：交给 app 引擎。它自己下分片、失败刷新播放列表重新拿签名，
        // 不会像 yt-dlp 的 HLS 下载器那样卡在 2~3MB 还留着假速度。
        var formatId = (req.FormatId ?? "").StartsWith("url:") ? req.FormatId! : "url:" + direct;
        _ = VspBridge.DownloadAsync(task, req.Url, formatId, req.Title ?? "video", dir);
    }
    else
    {
        _ = yt.DownloadWithYtDlpAsync(task, req.Url, req.FormatId, dir);
    }
    return Results.Ok(new { taskId = task.TaskId });
});

app.MapGet("/api/tasks", (DownloadTaskManager mgr) =>
    Results.Ok(mgr.All().OrderByDescending(t => t.TaskId).Select(t => t.ToDto())));

app.MapGet("/api/tasks/{id}", (string id, DownloadTaskManager mgr) =>
    mgr.Get(id) is { } t ? Results.Ok(t.ToDto()) : Results.NotFound());

app.MapDelete("/api/tasks/{id}", (string id, DownloadTaskManager mgr) =>
{
    var t = mgr.Get(id);
    if (t == null) return Results.NotFound();
    t.Cancel();
    // 删除已下载的文件
    if (!string.IsNullOrEmpty(t.Filename) && t.SavePath != "（浏览器下载）")
    {
        try { var fp = Path.Combine(t.SavePath, t.Filename); if (File.Exists(fp)) File.Delete(fp); } catch { }
    }
    mgr.Remove(id);
    return Results.Ok(new { ok = true });
});

app.MapPost("/api/cancel", (string taskId, DownloadTaskManager mgr) =>
{
    var t = mgr.Get(taskId);
    if (t != null) t.Cancel();
    return Results.Ok(new { ok = true });
});

// 浏览器原生流式下载：后端不存固定目录，直接把文件流传给浏览器，用户自己选保存位置
app.MapGet("/api/download-stream", async (HttpRequest request, HttpResponse response, DownloadTaskManager mgr) =>
{
    var kind = request.Query["kind"].ToString();
    var pageUrl = request.Query["url"].ToString();
    var formatId = request.Query["formatId"].ToString();
    var directUrl = request.Query["directUrl"].ToString();
    var title = request.Query["title"].ToString();
    if (string.IsNullOrEmpty(title)) title = "download";
    var safeTitle = string.Join("_", title.Split(Path.GetInvalidFileNameChars()));
    if (safeTitle.Length > 80) safeTitle = safeTitle[..80];

    var task = mgr.Create(pageUrl, safeTitle, "（浏览器下载）");
    task.Status = "running";

    try
    {
        if (kind == "image" && !string.IsNullOrEmpty(directUrl))
        {
            task.Progress = 10;
            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true });
            using var req = new HttpRequestMessage(HttpMethod.Get, directUrl);
            if (!string.IsNullOrEmpty(pageUrl)) req.Headers.Referrer = new Uri(pageUrl);
            var resp = await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, request.HttpContext.RequestAborted);
            if (!resp.IsSuccessStatusCode)
            {
                task.Status = "error"; task.Error = $"源站 {resp.StatusCode}";
                response.StatusCode = (int)resp.StatusCode;
                return;
            }
            string ext = ".jpg";
            var m = Regex.Match(directUrl, @"\.(jpg|jpeg|png|webp|gif|bmp)(\?|$)", RegexOptions.IgnoreCase);
            if (m.Success) ext = "." + m.Groups[1].Value.ToLower();
            var filename = safeTitle + ext;
            task.Filename = filename;
            response.Headers["Content-Disposition"] = $"attachment; filename=\"{Uri.EscapeDataString(filename)}\"; filename*=UTF-8''{Uri.EscapeDataString(filename)}";
            response.ContentType = resp.Content.Headers.ContentType?.ToString() ?? "application/octet-stream";
            long cl = resp.Content.Headers.ContentLength ?? 0;
            if (cl > 0) response.ContentLength = cl;
            await using var src = await resp.Content.ReadAsStreamAsync(request.HttpContext.RequestAborted);
            var buf = new byte[81920]; int n; long got = 0;
            while ((n = await src.ReadAsync(buf, request.HttpContext.RequestAborted)) > 0)
            {
                await response.Body.WriteAsync(buf.AsMemory(0, n), request.HttpContext.RequestAborted);
                got += n;
                if (cl > 0) task.Progress = 10 + got * 90.0 / cl;
            }
            task.Status = "done"; task.Progress = 100;
            return;
        }

        // 视频/音频：用 yt-dlp 下载到临时目录，再流式返回给浏览器
        var tmpDir = Path.Combine(Path.GetTempPath(), "vsp_dl_" + Guid.NewGuid().ToString("N")[..10]);
        Directory.CreateDirectory(tmpDir);
        try
        {
            var outTmpl = Path.Combine(tmpDir, "%(title)s.%(ext)s");
            // 自动合并：选了纯视频轨就加最佳音频；合并失败 fallback 到 best
            var fmt = string.IsNullOrEmpty(formatId) ? "best" : $"{formatId}+bestaudio/best";
            var psi = new ProcessStartInfo
            {
                FileName = "python",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            foreach (var a in new[] { "-m", "yt_dlp", "--no-playlist", "--no-warnings", "--no-check-certificate",
                       "--newline", "--progress-template", "%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.speed)s|%(progress.eta)s",
                       "-f", fmt, "-o", outTmpl, pageUrl })
                psi.ArgumentList.Add(a);

            var proc = new Process { StartInfo = psi };
            task.Proc = proc;
            proc.Start();
            task.Progress = 5;
            string? line;
            while ((line = await proc.StandardOutput.ReadLineAsync()) != null)
            {
                var parts = line.Split('|');
                if (parts.Length >= 2 && long.TryParse(parts[0], out var dl) && long.TryParse(parts[1], out var total) && total > 0)
                {
                    task.Progress = 5 + dl * 70.0 / total;
                    if (parts.Length >= 3 && parts[2] != "NA" && parts[2] != "None") task.Speed = parts[2];
                    if (parts.Length >= 4 && parts[3] != "NA" && parts[3] != "None") task.Eta = parts[3];
                }
            }
            await proc.WaitForExitAsync();
            if (proc.ExitCode != 0)
            {
                task.Status = "error"; task.Error = "yt-dlp 下载失败";
                response.StatusCode = 500;
                return;
            }

            var file = Directory.GetFiles(tmpDir).FirstOrDefault();
            if (file == null)
            {
                task.Status = "error"; task.Error = "下载完成但未找到文件";
                response.StatusCode = 500;
                return;
            }

            task.Filename = Path.GetFileName(file);
            task.Progress = 80;
            var cdName = Path.GetFileName(file);
            var isPreview = request.Query["preview"].ToString() == "1";
            response.Headers["Content-Disposition"] =
                $"{(isPreview ? "inline" : "attachment")}; filename=\"{Uri.EscapeDataString(cdName)}\"; filename*=UTF-8''{Uri.EscapeDataString(cdName)}";
            response.ContentType = isPreview ? "video/mp4" : "application/octet-stream";
            response.Headers["Content-Type"] = "application/octet-stream";
            await using var fs = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, true);
            response.ContentLength = fs.Length;
            long sent = 0;
            var fbuf = new byte[81920]; int fn;
            while ((fn = await fs.ReadAsync(fbuf, request.HttpContext.RequestAborted)) > 0)
            {
                await response.Body.WriteAsync(fbuf.AsMemory(0, fn), request.HttpContext.RequestAborted);
                sent += fn;
                task.Progress = 80 + sent * 20.0 / fs.Length;
            }
            task.Status = "done"; task.Progress = 100;
        }
        finally
        {
            try { Directory.Delete(tmpDir, true); } catch { }
        }
    }
    catch (Exception ex)
    {
        task.Status = "error"; task.Error = ex.Message;
        if (!response.HasStarted)
        {
            response.StatusCode = 500;
            await response.WriteAsync(ex.Message);
        }
    }
});

// 流式代理（解决防盗链 / CORS）
app.MapGet("/api/proxy", async (HttpRequest request, HttpResponse response) =>
{
    var url = request.Query["url"].ToString();
    var referer = request.Query["referer"].ToString();
    if (string.IsNullOrEmpty(url))
    {
        response.StatusCode = 400;
        return;
    }

    using var client = new HttpClient(new HttpClientHandler
    {
        AllowAutoRedirect = true,
        AutomaticDecompression = DecompressionMethods.All,
    });
    client.Timeout = TimeSpan.FromMinutes(5);

    var req = new HttpRequestMessage(HttpMethod.Get, url);
    if (!string.IsNullOrEmpty(referer))
        req.Headers.Referrer = new Uri(referer);
    // 转发 Range（用于 <video> 拖动进度）
    if (request.Headers.TryGetValue("Range", out var range))
        req.Headers.TryAddWithoutValidation("Range", range.ToString());

    try
    {
        var upstream = await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, request.HttpContext.RequestAborted);
        response.StatusCode = (int)upstream.StatusCode;
        foreach (var h in new[] { "content-type", "content-length", "accept-ranges", "last-modified", "etag" })
        {
            if (upstream.Content.Headers.TryGetValues(h, out var vals))
                response.Headers[h] = vals.First();
        }
        if (upstream.Headers.TryGetValues("content-range", out var cr))
            response.Headers["Content-Range"] = cr.First();

        await using var src = await upstream.Content.ReadAsStreamAsync();
        await src.CopyToAsync(response.Body, 81920, request.HttpContext.RequestAborted);
    }
    catch (Exception)
    {
        // 客户端断开等，忽略
    }
});

// ---------------- 静态文件 ----------------
if (Directory.Exists(wwwroot))
{
    var fileProvider = new Microsoft.Extensions.FileProviders.PhysicalFileProvider(wwwroot);
    var opts = new StaticFileOptions { FileProvider = fileProvider };
    app.UseDefaultFiles(new DefaultFilesOptions { FileProvider = fileProvider });
    app.UseStaticFiles(opts);
    // SPA fallback
    app.MapFallback(async context =>
    {
        if (context.Request.Path.StartsWithSegments("/api"))
        {
            context.Response.StatusCode = 404;
            return;
        }
        context.Response.ContentType = "text/html";
        await context.Response.SendFileAsync(Path.Combine(wwwroot, "index.html"));
    });
}

app.Lifetime.ApplicationStarted.Register(() =>
{
    var shown = string.IsNullOrWhiteSpace(presetUrls) ? $"http://localhost:{port}" : presetUrls!;
    Console.WriteLine($"\n  VSP 解析已启动 → {shown}\n");
});

app.Run();

// ---------------- 请求模型 ----------------
record ParseReq(string Url);
record DownloadReq(string Url, string? FormatId, string Kind, string? DirectUrl, string? Title, string? SaveDir);

// ---------------- 下载任务 ----------------
class DownloadTask
{
    public string TaskId { get; } = Guid.NewGuid().ToString("N")[..12];
    public string Url { get; init; } = "";
    public string Title { get; set; } = "";
    public string SavePath { get; init; } = "";
    public string Status { get; set; } = "running";
    public double Progress { get; set; }
    public string Speed { get; set; } = "";
    public string Eta { get; set; } = "";
    public string Filename { get; set; } = "";
    public string Error { get; set; } = "";
    public Process? Proc { get; set; }
    public CancellationTokenSource Cts { get; } = new();

    public object ToDto() => new
    {
        taskId = TaskId,
        url = Url,
        title = Title,
        savePath = SavePath,
        status = Status,
        progress = Math.Round(Progress, 1),
        speed = Speed,
        eta = Eta,
        filename = Filename,
        error = Error,
    };

    public void Cancel()
    {
        try { Proc?.Kill(true); } catch { }
        Cts.Cancel();
        Status = "error";
        Error = "已取消";
    }
}

class DownloadTaskManager
{
    private readonly ConcurrentDictionary<string, DownloadTask> _tasks = new();
    public DownloadTask Create(string url, string title, string saveDir)
    {
        var t = new DownloadTask { Url = url, Title = title, SavePath = saveDir };
        _tasks[t.TaskId] = t;
        return t;
    }
    public DownloadTask? Get(string id) => _tasks.TryGetValue(id, out var t) ? t : null;
    public bool Remove(string id) => _tasks.TryRemove(id, out _);
    public IEnumerable<DownloadTask> All() => _tasks.Values;
}

// ---------------- App 引擎（vsp.py）桥接 ----------------
// web 与 app 共用同一份解析 / 下载实现：站点提取器、HLS 分片下载（刷新播放列表、
// 指数退避、分片缓存）都只有一套代码，不会出现「App 能下、网页卡死」的分叉。

class PythonLocator
{
    private static string? _cached;
    private static readonly object _lock = new();

    public static string Exe
    {
        get
        {
            lock (_lock)
            {
                if (_cached != null) return _cached;
                _cached = Probe() ?? "python";
                Console.WriteLine($"Python 解释器：{_cached}");
                return _cached;
            }
        }
    }

    private static string? Probe()
    {
        var cands = new List<string>();
        var env = Environment.GetEnvironmentVariable("VSP_PYTHON");
        if (!string.IsNullOrWhiteSpace(env)) cands.Add(env);
        cands.Add("python");
        try
        {
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
                                    ".dsh", "dsh-runtimes");
            if (Directory.Exists(root))
                foreach (var d in Directory.GetDirectories(root))
                    cands.Add(Path.Combine(d, "dependencies", "python", "python.exe"));
        }
        catch { }
        cands.Add("py");

        foreach (var c in cands)
        {
            try
            {
                var psi = new ProcessStartInfo
                {
                    FileName = c,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    StandardOutputEncoding = Encoding.UTF8,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                };
                psi.ArgumentList.Add("-c");
                psi.ArgumentList.Add("import sys,yt_dlp;print(sys.executable)");
                using var p = new Process { StartInfo = psi };
                p.Start();
                var stdout = p.StandardOutput.ReadToEnd();
                if (!p.WaitForExit(15000)) { try { p.Kill(true); } catch { } continue; }
                if (p.ExitCode == 0 && stdout.Trim().Length > 0) return stdout.Trim();
            }
            catch { }
        }
        return null;
    }
}

class VspBridge
{
    private static string? _script;

    public static string ScriptPath
    {
        get
        {
            if (_script != null) return _script;
            var cands = new List<string>();
            var env = Environment.GetEnvironmentVariable("VSP_PY_SCRIPT");
            if (!string.IsNullOrWhiteSpace(env)) cands.Add(env);
            var roots = new List<string> { AppContext.BaseDirectory, Directory.GetCurrentDirectory() };
            var d = Directory.GetCurrentDirectory();
            for (int i = 0; i < 4 && d.Length > 3; i++)
            {
                d = Path.GetDirectoryName(d) ?? d;
                if (d.Length <= 3) break;
                roots.Add(d);
            }
            foreach (var r in roots) cands.Add(Path.Combine(r, "python", "vsp_cli.py"));
            foreach (var c in cands)
                if (File.Exists(c)) { _script = c; return c; }
            throw new Exception("找不到 vsp_cli.py，无法加载 App 引擎");
        }
    }

    private static ProcessStartInfo MakePsi()
    {
        var psi = new ProcessStartInfo
        {
            FileName = PythonLocator.Exe,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        return psi;
    }

    public static async Task<string> ParseAsync(string url)
    {
        var psi = MakePsi();
        psi.ArgumentList.Add(ScriptPath);
        psi.ArgumentList.Add("parse");
        psi.ArgumentList.Add(url);

        using var proc = new Process { StartInfo = psi };
        proc.Start();
        var outTask = proc.StandardOutput.ReadToEndAsync();
        var errTask = proc.StandardError.ReadToEndAsync();
        var exitTask = proc.WaitForExitAsync();
        var timeout = Task.Delay(90000);
        if (await Task.WhenAny(Task.WhenAll(outTask, errTask, exitTask), timeout) == timeout)
        {
            try { proc.Kill(true); } catch { }
            throw new Exception("App 引擎解析超时（90 秒）");
        }
        var stdout = (await outTask).Trim();
        var stderr = (await errTask).Trim();
        if (proc.ExitCode != 0 || string.IsNullOrEmpty(stdout))
        {
            var msg = string.IsNullOrEmpty(stderr) ? "App 引擎解析失败" : stderr.Split('\n')[0].Trim();
            throw new Exception(msg);
        }
        return stdout;
    }

    public static async Task DownloadAsync(DownloadTask task, string pageUrl, string formatId,
                                          string title, string dir)
    {
        try
        {
            var psi = MakePsi();
            psi.ArgumentList.Add(ScriptPath);
            psi.ArgumentList.Add("download");
            psi.ArgumentList.Add(pageUrl);
            psi.ArgumentList.Add(formatId);
            psi.ArgumentList.Add(title);
            psi.ArgumentList.Add(dir);

            var proc = new Process { StartInfo = psi };
            task.Proc = proc;
            proc.Start();

            var tail = new Queue<string>();
            string? line;
            while ((line = await proc.StandardOutput.ReadLineAsync()) != null)
            {
                var s = line.Trim();
                if (!s.StartsWith("{")) continue;
                try
                {
                    using var doc = JsonDocument.Parse(s);
                    var st = doc.RootElement.TryGetProperty("status", out var se) ? se.GetString() ?? "running" : "running";
                    if (doc.RootElement.TryGetProperty("progress", out var pr) && pr.ValueKind == JsonValueKind.Number)
                        task.Progress = pr.GetDouble();
                    if (doc.RootElement.TryGetProperty("speed", out var sp) && sp.ValueKind == JsonValueKind.String)
                        task.Speed = sp.GetString() ?? "";
                    if (doc.RootElement.TryGetProperty("filename", out var fn) && fn.ValueKind == JsonValueKind.String)
                    {
                        var v = fn.GetString() ?? "";
                        if (!string.IsNullOrEmpty(v)) task.Filename = v;
                    }
                    if (st == "done") { task.Progress = 100; task.Status = "done"; task.Speed = ""; }
                    else if (st == "error")
                    {
                        task.Status = "error";
                        task.Speed = "";
                        task.Error = doc.RootElement.TryGetProperty("error", out var er) ? er.GetString() ?? "下载失败" : "下载失败";
                        tail.Enqueue(task.Error);
                    }
                }
                catch { }
            }
            await proc.WaitForExitAsync();

            if (task.Status == "done") return;
            if (proc.ExitCode == 0) { task.Progress = 100; task.Status = "done"; return; }
            if (task.Status != "error")
            {
                task.Status = "error";
                task.Speed = "";
                task.Error = tail.Count > 0 ? tail.Last() : "下载失败";
            }
        }
        catch (Exception ex)
        {
            task.Status = "error";
            task.Speed = "";
            task.Error = ex.Message;
        }
    }
}

// ---------------- yt-dlp 调用 ----------------
class YtDlpRunner
{
    // 不能再直接写 "python"：PATH 里的解释器不一定装了 yt_dlp。
    // PythonLocator 会探到一个真正带 yt_dlp 的解释器，vsp 引擎也共用它。
    private static string PythonExe => PythonLocator.Exe;

    private List<string> BaseArgs() => new()
    {
        "-m", "yt_dlp",
        "--no-playlist", "--no-warnings", "--ignore-errors", "--no-check-certificates",
    };

    public async Task<object> ParseAsync(string url)
    {
        var psi = new ProcessStartInfo
        {
            FileName = PythonExe,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        foreach (var a in BaseArgs()) psi.ArgumentList.Add(a);
        psi.ArgumentList.Add("--dump-json");
        psi.ArgumentList.Add(url);

        using var proc = new Process { StartInfo = psi };
        proc.Start();
        var stdoutTask = proc.StandardOutput.ReadToEndAsync();
        var stderrTask = proc.StandardError.ReadToEndAsync();
        var exitTask = proc.WaitForExitAsync();
        var timeout = Task.Delay(60000);
        var done = await Task.WhenAny(Task.WhenAll(stdoutTask, stderrTask, exitTask), timeout);
        if (done == timeout)
        {
            try { proc.Kill(); } catch { }
            throw new Exception("解析超时（60秒），请检查网络或链接");
        }
        var stdout = await stdoutTask;
        var stderr = await stderrTask;

        if (proc.ExitCode != 0)
            throw new Exception($"解析失败: {Truncate(string.IsNullOrEmpty(stderr) ? stdout : stderr, 500)}");

        var firstLine = stdout.Split('\n', 2)[0];
        using var doc = JsonDocument.Parse(firstLine);
        return Normalize(doc.RootElement);
    }

    private static string Truncate(string s, int n) => s.Length <= n ? s : s[..n];

    private static string HumanSize(double? n)
    {
        if (n is null or <= 0) return "-";
        double v = n.Value;
        string[] units = { "B", "KB", "MB", "GB" };
        int i = 0;
        while (v >= 1024 && i < units.Length - 1) { v /= 1024; i++; }
        return $"{v:F1} {units[i]}";
    }

    private object Normalize(JsonElement d)
    {
        var videos = new List<object>();
        var audios = new List<object>();
        var images = new List<object>();

        if (d.TryGetProperty("formats", out var formats) && formats.ValueKind == JsonValueKind.Array)
        {
            foreach (var f in formats.EnumerateArray())
            {
                string GetStr(string k) => f.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
                double? GetNum(string k) => f.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;

                var fmtId = GetStr("format_id");
                var vcodec = GetStr("vcodec");
                var acodec = GetStr("acodec");
                var ext = GetStr("ext");
                var height = (int?)GetNum("height");
                var width = (int?)GetNum("width");
                var tbr = GetNum("tbr") ?? GetNum("abr") ?? 0;
                var size = GetNum("filesize") ?? GetNum("filesize_approx");
                var furl = GetStr("url");
                if (string.IsNullOrEmpty(furl)) continue;

                var item = new
                {
                    formatId = fmtId,
                    ext,
                    vcodec = vcodec != "none" ? vcodec : null,
                    acodec = acodec != "none" ? acodec : null,
                    width,
                    height,
                    quality = height > 0 ? $"{height}p" : (string.IsNullOrEmpty(GetStr("format")) ? ext : GetStr("format")),
                    bitrate = (int)tbr,
                    size = HumanSize(size),
                    url = furl,
                    container = ext,
                };

                if (vcodec != "none") videos.Add(item);
                else if (acodec != "none") audios.Add(item);
            }
        }

        if (d.TryGetProperty("thumbnails", out var thumbs) && thumbs.ValueKind == JsonValueKind.Array)
        {
            int idx = 0;
            foreach (var t in thumbs.EnumerateArray())
            {
                var u = t.TryGetProperty("url", out var v) ? v.GetString() ?? "" : "";
                if (string.IsNullOrEmpty(u)) continue;
                images.Add(new
                {
                    formatId = $"thumb_{idx++}",
                    quality = t.TryGetProperty("resolution", out var r) ? r.GetString() ?? "thumb" : "thumb",
                    ext = "jpg",
                    size = "-",
                    url = u,
                    width = t.TryGetProperty("width", out var w) && w.ValueKind == JsonValueKind.Number ? (int?)w.GetDouble() : null,
                    height = t.TryGetProperty("height", out var h) && h.ValueKind == JsonValueKind.Number ? (int?)h.GetDouble() : null,
                });
            }
        }

        string S(string k) => d.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
        double? N(string k) => d.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;

        return new
        {
            title = S("title"),
            id = S("id"),
            uploader = S("uploader"),
            duration = N("duration") ?? 0,
            thumbnail = S("thumbnail"),
            description = Truncate(S("description"), 500),
            webpageUrl = S("webpage_url"),
            videos = videos.OrderByDescending(v => ((dynamic)v).height ?? 0).ToList(),
            audios = audios.OrderByDescending(a => ((dynamic)a).bitrate).ToList(),
            images,
        };
    }

    public async Task DownloadWithYtDlpAsync(DownloadTask task, string url, string? formatId, string dir)
    {
        try
        {
            var outTmpl = Path.Combine(dir, "%(title)s.%(ext)s");
            var fmt = string.IsNullOrEmpty(formatId) ? "best" : formatId;

            var psi = new ProcessStartInfo
            {
                FileName = PythonExe,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            foreach (var a in BaseArgs()) psi.ArgumentList.Add(a);
            psi.ArgumentList.Add("-f"); psi.ArgumentList.Add(fmt);
            psi.ArgumentList.Add("-o"); psi.ArgumentList.Add(outTmpl);
            psi.ArgumentList.Add("--newline");
            psi.ArgumentList.Add("--progress-template");
            psi.ArgumentList.Add("%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.speed)s|%(progress.eta)s");
            psi.ArgumentList.Add(url);

            var proc = new Process { StartInfo = psi };
            task.Proc = proc;
            proc.Start();

            var tail = new Queue<string>();
            string? line;
            while ((line = await proc.StandardOutput.ReadLineAsync()) != null)
            {
                tail.Enqueue(line);
                if (tail.Count > 20) tail.Dequeue();

                var parts = line.Split('|');
                if (parts.Length >= 2 && long.TryParse(parts[0], out var dl) && long.TryParse(parts[1], out var total) && total > 0)
                {
                    task.Progress = dl * 100.0 / total;
                    if (parts.Length >= 3 && parts[2] != "NA" && parts[2] != "None") task.Speed = parts[2];
                    if (parts.Length >= 4 && parts[3] != "NA" && parts[3] != "None") task.Eta = parts[3];
                }
            }
            await proc.WaitForExitAsync();

            if (proc.ExitCode == 0)
            {
                task.Status = "done";
                task.Progress = 100;
                task.Filename = task.Title;
            }
            else
            {
                task.Status = "error";
                task.Error = Truncate(string.Join("\n", tail), 500);
            }
        }
        catch (Exception ex)
        {
            task.Status = "error";
            task.Error = ex.Message;
        }
    }

    public async Task DownloadImageAsync(DownloadTask task, string directUrl, string title, string dir)
    {
        try
        {
            var ext = ".jpg";
            var m = Regex.Match(directUrl, @"\.(jpg|jpeg|png|webp|gif|bmp)(\?|$)", RegexOptions.IgnoreCase);
            if (m.Success) ext = "." + m.Groups[1].Value.ToLower();
            var safe = Regex.Replace(title, @"[\\/:*?""<>|]", "_");
            if (safe.Length > 80) safe = safe[..80];
            if (string.IsNullOrEmpty(safe)) safe = "image";
            var outPath = Path.Combine(dir, safe + ext);

            using var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = true });
            using var req = new HttpRequestMessage(HttpMethod.Get, directUrl);
            if (!string.IsNullOrEmpty(task.Url))
                req.Headers.Referrer = new Uri(task.Url);

            using var resp = await client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? 0;
            using var fs = new FileStream(outPath, FileMode.Create, FileAccess.Write, FileShare.None);
            await using var src = await resp.Content.ReadAsStreamAsync();
            var buf = new byte[81920];
            long downloaded = 0;
            int read;
            while ((read = await src.ReadAsync(buf, task.Cts.Token)) > 0)
            {
                await fs.WriteAsync(buf.AsMemory(0, read), task.Cts.Token);
                downloaded += read;
                if (total > 0) task.Progress = downloaded * 100.0 / total;
            }
            task.Filename = Path.GetFileName(outPath);
            task.Status = "done";
            task.Progress = 100;
        }
        catch (Exception ex)
        {
            task.Status = "error";
            task.Error = ex.Message;
        }
    }
}
