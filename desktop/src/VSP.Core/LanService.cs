using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace VSP.Core;

/// <summary>局域网上发现的一台设备（手机或电脑）。</summary>
public sealed class LanDevice
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Kind { get; set; } = "pc"; // pc | android
    public string Ip { get; set; } = "";
    public int Port { get; set; }
    public DateTime LastSeen { get; set; }
    public bool IsAndroid => string.Equals(Kind, "android", StringComparison.OrdinalIgnoreCase);
}

public sealed class LanFileEntry
{
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";
    [JsonPropertyName("path")]
    public string Path { get; set; } = "";   // base64 相对路径
    [JsonPropertyName("dir")]
    public bool IsDir { get; set; }
    [JsonPropertyName("size")]
    public long Size { get; set; }
}

/// <summary>
/// 局域网直连服务：UDP 广播发现 + 内嵌 HTTP 服务（文件互传、屏幕查看、输入注入）。
/// 两端协议完全一致，全部走局域网 IP，不经公网服务器、不消耗移动流量。
/// 只接受私网地址，且所有请求必须带配对令牌 TOKEN。
/// </summary>
public sealed class LanService : IDisposable
{
    public const int UdpPort = 45320;
    public const int HttpPort = 45321;
    public const string Token = "TBOX2026";

    private readonly string _deviceId = Guid.NewGuid().ToString("N")[..10];
    private readonly string _deviceName;
    private readonly string _shareRoot;
    private readonly ConcurrentDictionary<string, LanDevice> _devices = new();
    private readonly HttpClient _client = new() { Timeout = TimeSpan.FromSeconds(20) };
    private UdpClient? _udp;
    private HttpListener? _http;
    private CancellationTokenSource? _cts;

    public string LocalIp { get; private set; } = "127.0.0.1";
    public string ShareRoot => _shareRoot;
    public IReadOnlyList<LanDevice> Devices => _devices.Values
        .OrderBy(d => d.IsAndroid).ThenBy(d => d.Name).ToList();
    public event Action? DevicesChanged;

    public LanService(string deviceName, string shareRoot)
    {
        _deviceName = string.IsNullOrWhiteSpace(deviceName) ? "电脑" : deviceName;
        _shareRoot = shareRoot;
        try { Directory.CreateDirectory(_shareRoot); } catch { }
    }

    public void Start()
    {
        _cts = new CancellationTokenSource();
        StartDiscovery(_cts.Token);
        StartHttp();
    }

    // ---------------- 设备发现 ----------------

    private void StartDiscovery(CancellationToken ct)
    {
        try
        {
            _udp = new UdpClient { EnableBroadcast = true };
            _udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _udp.Client.Bind(new IPEndPoint(IPAddress.Any, UdpPort));

            var hello = JsonSerializer.Serialize(new
            {
                id = _deviceId, n = _deviceName, t = "pc", p = HttpPort, k = Token
            });
            var helloBytes = Encoding.UTF8.GetBytes(hello);

            _ = Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    try { await _udp!.SendAsync(helloBytes, new IPEndPoint(IPAddress.Broadcast, UdpPort), ct); }
                    catch { }
                    try { await Task.Delay(2000, ct); } catch { break; }
                }
            }, ct);

            _ = Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    try
                    {
                        var r = await _udp!.ReceiveAsync(ct);
                        var ip = r.RemoteEndPoint.Address;
                        if (!IsPrivate(ip)) continue;
                        using var doc = JsonDocument.Parse(Encoding.UTF8.GetString(r.Buffer));
                        var root = doc.RootElement;
                        if (!root.TryGetProperty("k", out var k) || k.GetString() != Token) continue;
                        var id = root.GetProperty("id").GetString() ?? "";
                        if (string.IsNullOrEmpty(id) || id == _deviceId) continue;
                        var dev = new LanDevice
                        {
                            Id = id,
                            Name = root.TryGetProperty("n", out var n) ? n.GetString() ?? "设备" : "设备",
                            Kind = root.TryGetProperty("t", out var t) ? t.GetString() ?? "pc" : "pc",
                            Ip = ip.ToString(),
                            Port = root.TryGetProperty("p", out var p) ? p.GetInt32() : HttpPort,
                            LastSeen = DateTime.Now,
                        };
                        _devices[id] = dev;
                        DevicesChanged?.Invoke();
                    }
                    catch (OperationCanceledException) { break; }
                    catch { }
                }
            }, ct);

            // 超时剔除
            _ = Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    await Task.Delay(3000, ct);
                    var cutoff = DateTime.Now.AddSeconds(-9);
                    var dead = _devices.Values.Where(d => d.LastSeen < cutoff).Select(d => d.Id).ToList();
                    if (dead.Count > 0)
                    {
                        foreach (var id in dead) _devices.TryRemove(id, out _);
                        DevicesChanged?.Invoke();
                    }
                }
            }, ct);
        }
        catch { }
    }

    // ---------------- HTTP 服务（被控 / 被传） ----------------

    private void StartHttp()
    {
        try
        {
            _http = new HttpListener();
            var ips = GetLocalPrivateIps();
            LocalIp = ips.FirstOrDefault() ?? "127.0.0.1";
            foreach (var ip in ips)
            {
                try { _http.Prefixes.Add($"http://{ip}:{HttpPort}/"); } catch { }
            }
            _http.Start();
            _ = Task.Run(async () =>
            {
                while (_http.IsListening)
                {
                    HttpListenerContext ctx;
                    try { ctx = await _http.GetContextAsync(); } catch { break; }
                    _ = Task.Run(() => HandleAsync(ctx));
                }
            });
        }
        catch { }
    }

    private async Task HandleAsync(HttpListenerContext ctx)
    {
        try
        {
            var q = ctx.Request.QueryString;
            if (q["k"] != Token) { Respond(ctx, 403, "forbidden"); return; }
            var path = ctx.Request.Url?.AbsolutePath ?? "";
            switch (path)
            {
                case "/hello":
                    Respond(ctx, 200, $"{_deviceName}|pc");
                    break;
                case "/files":
                    RespondJson(ctx, ListFiles(q["dir"]));
                    break;
                case "/dl":
                    await ServeDownload(ctx, q["path"]);
                    break;
                case "/ul":
                    await ReceiveUpload(ctx, q["name"]);
                    break;
                case "/screen":
                    await ServeScreen(ctx);
                    break;
                case "/input":
                    await ReceiveInput(ctx);
                    break;
                default:
                    Respond(ctx, 404, "not found");
                    break;
            }
        }
        catch (Exception ex)
        {
            try { Respond(ctx, 500, ex.Message); } catch { }
        }
    }

    private List<LanFileEntry> ListFiles(string? dirB64)
    {
        var result = new List<LanFileEntry>();
        try
        {
            var dir = DecodePath(dirB64);
            var full = Path.GetFullPath(string.IsNullOrEmpty(dir) ? _shareRoot : Path.Combine(_shareRoot, dir));
            if (!IsUnderRoot(full)) return result;
            foreach (var f in Directory.EnumerateDirectories(full))
                result.Add(new LanFileEntry
                {
                    Name = Path.GetFileName(f), IsDir = true, Path = EncodePath(Path.GetRelativePath(_shareRoot, f))
                });
            foreach (var f in Directory.EnumerateFiles(full))
            {
                var info = new FileInfo(f);
                result.Add(new LanFileEntry
                {
                    Name = info.Name, IsDir = false, Size = info.Length,
                    Path = EncodePath(Path.GetRelativePath(_shareRoot, f))
                });
            }
        }
        catch { }
        return result.OrderBy(x => !x.IsDir).ThenBy(x => x.Name).ToList();
    }

    private async Task ServeDownload(HttpListenerContext ctx, string? pathB64)
    {
        try
        {
            var rel = DecodePath(pathB64) ?? "";
            var full = Path.GetFullPath(Path.Combine(_shareRoot, rel));
            if (!IsUnderRoot(full) || !File.Exists(full)) { Respond(ctx, 404, "missing"); return; }
            ctx.Response.ContentType = "application/octet-stream";
            ctx.Response.ContentLength64 = new FileInfo(full).Length;
            await using var fs = File.OpenRead(full);
            await fs.CopyToAsync(ctx.Response.OutputStream);
        }
        catch { }
        finally { try { ctx.Response.Close(); } catch { } }
    }

    private async Task ReceiveUpload(HttpListenerContext ctx, string? nameB64)
    {
        var name = DecodePath(nameB64);
        if (string.IsNullOrWhiteSpace(name)) { Respond(ctx, 400, "no name"); return; }
        name = Path.GetFileName(name);
        try
        {
            var full = Path.Combine(_shareRoot, name);
            await using var fs = File.Create(full);
            await ctx.Request.InputStream.CopyToAsync(fs);
            Respond(ctx, 200, "ok");
        }
        catch (Exception ex) { Respond(ctx, 500, ex.Message); }
    }

    private async Task ServeScreen(HttpListenerContext ctx)
    {
        try
        {
            var w = GetSystemMetrics(0);
            var h = GetSystemMetrics(1);
            using var bmp = new Bitmap(w, h);
            using (var g = Graphics.FromImage(bmp))
                g.CopyFromScreen(0, 0, 0, 0, new Size(w, h));
            using var ms = new MemoryStream();
            var codec = ImageCodecInfo.GetImageEncoders().First(c => c.MimeType == "image/jpeg");
            using var ep = new EncoderParameters(1);
            ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 68L);
            bmp.Save(ms, codec, ep);
            var bytes = ms.ToArray();
            ctx.Response.ContentType = "image/jpeg";
            ctx.Response.ContentLength64 = bytes.Length;
            await ctx.Response.OutputStream.WriteAsync(bytes);
        }
        catch { }
        finally { try { ctx.Response.Close(); } catch { } }
    }

    private async Task ReceiveInput(HttpListenerContext ctx)
    {
        string body;
        using (var reader = new StreamReader(ctx.Request.InputStream))
            body = await reader.ReadToEndAsync();
        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            var action = root.GetProperty("a").GetString() ?? "";
            var sw = GetSystemMetrics(0);
            var sh = GetSystemMetrics(1);
            switch (action)
            {
                case "tap":
                {
                    var x = (int)(root.GetProperty("x").GetDouble() * sw);
                    var y = (int)(root.GetProperty("y").GetDouble() * sh);
                    SetCursorPos(x, y);
                    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                    mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                }
                case "swipe":
                {
                    var x1 = (int)(root.GetProperty("x1").GetDouble() * sw);
                    var y1 = (int)(root.GetProperty("y1").GetDouble() * sh);
                    var x2 = (int)(root.GetProperty("x2").GetDouble() * sw);
                    var y2 = (int)(root.GetProperty("y2").GetDouble() * sh);
                    SetCursorPos(x1, y1);
                    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                    for (var i = 1; i <= 8; i++)
                    {
                        SetCursorPos(x1 + (x2 - x1) * i / 8, y1 + (y2 - y1) * i / 8);
                        await Task.Delay(12);
                    }
                    mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                }
                case "key":
                {
                    var key = root.GetProperty("k").GetString() ?? "";
                    switch (key)
                    {
                        case "back": keybd_event(VK_ESCAPE, 0, 0, UIntPtr.Zero); keybd_event(VK_ESCAPE, 0, KEYEVENTF_KEYUP, UIntPtr.Zero); break;
                        case "home": keybd_event(VK_LWIN, 0, 0, UIntPtr.Zero); keybd_event(VK_LWIN, 0, KEYEVENTF_KEYUP, UIntPtr.Zero); break;
                        case "recent":
                            keybd_event(VK_MENU, 0, 0, UIntPtr.Zero); keybd_event(VK_TAB, 0, 0, UIntPtr.Zero);
                            keybd_event(VK_TAB, 0, KEYEVENTF_KEYUP, UIntPtr.Zero); keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
                            break;
                    }
                    break;
                }
            }
            Respond(ctx, 200, "ok");
        }
        catch (Exception ex) { Respond(ctx, 500, ex.Message); }
    }

    // ---------------- 客户端（主控 / 传文件） ----------------

    public async Task<string?> HelloAsync(LanDevice dev)
    {
        try
        {
            var r = await _client.GetStringAsync($"http://{dev.Ip}:{dev.Port}/hello?k={Token}");
            return r;
        }
        catch { return null; }
    }

    public async Task<List<LanFileEntry>> ListRemoteAsync(LanDevice dev, string? dirB64 = null)
    {
        try
        {
            var json = await _client.GetStringAsync(
                $"http://{dev.Ip}:{dev.Port}/files?k={Token}&dir={Uri.EscapeDataString(dirB64 ?? "")}");
            return JsonSerializer.Deserialize<List<LanFileEntry>>(json) ?? [];
        }
        catch { return []; }
    }

    public async Task<bool> DownloadAsync(LanDevice dev, LanFileEntry entry, string localPath,
        IProgress<double>? progress = null)
    {
        try
        {
            using var resp = await _client.GetAsync(
                $"http://{dev.Ip}:{dev.Port}/dl?k={Token}&path={Uri.EscapeDataString(entry.Path)}",
                HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? 0;
            await using var src = await resp.Content.ReadAsStreamAsync();
            await using var dst = File.Create(localPath);
            var buf = new byte[81920];
            long read = 0;
            int n;
            while ((n = await src.ReadAsync(buf)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, n));
                read += n;
                if (total > 0) progress?.Report((double)read / total);
            }
            return true;
        }
        catch { return false; }
    }

    public async Task<bool> UploadAsync(LanDevice dev, string localPath)
    {
        try
        {
            var name = Path.GetFileName(localPath);
            await using var fs = File.OpenRead(localPath);
            using var content = new StreamContent(fs);
            var url = $"http://{dev.Ip}:{dev.Port}/ul?k={Token}&name={Uri.EscapeDataString(EncodePath(name))}";
            var resp = await _client.PostAsync(url, content);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    public async Task<byte[]?> GetScreenAsync(LanDevice dev)
    {
        try { return await _client.GetByteArrayAsync($"http://{dev.Ip}:{dev.Port}/screen?k={Token}"); }
        catch { return null; }
    }

    public async Task<bool> SendInputAsync(LanDevice dev, object payload)
    {
        try
        {
            var json = JsonSerializer.Serialize(payload);
            using var content = new StringContent(json, Encoding.UTF8, "application/json");
            var resp = await _client.PostAsync($"http://{dev.Ip}:{dev.Port}/input?k={Token}", content);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    // ---------------- 工具 ----------------

    private static bool IsPrivate(IPAddress ip)
    {
        var b = ip.GetAddressBytes();
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        if (b[0] == 192 && b[1] == 168) return true;
        if (b[0] == 10) return true;
        if (b[0] == 172 && b[1] >= 16 && b[1] <= 31) return true;
        return false;
    }

    private static List<string> GetLocalPrivateIps()
    {
        var list = new List<string>();
        try
        {
            foreach (var a in Dns.GetHostAddresses(Dns.GetHostName()))
                if (a.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a)) list.Add(a.ToString());
        }
        catch { }
        if (list.Count == 0) list.Add("127.0.0.1");
        return list.Distinct().ToList();
    }

    private bool IsUnderRoot(string full)
    {
        var root = Path.GetFullPath(_shareRoot);
        return full.Equals(root, StringComparison.OrdinalIgnoreCase)
               || full.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    }

    private static string EncodePath(string s) => Convert.ToBase64String(Encoding.UTF8.GetBytes(s));
    private static string? DecodePath(string? s)
    {
        if (string.IsNullOrEmpty(s)) return null;
        try { return Encoding.UTF8.GetString(Convert.FromBase64String(s)); } catch { return null; }
    }

    private static void Respond(HttpListenerContext ctx, int code, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        ctx.Response.StatusCode = code;
        ctx.Response.ContentType = "text/plain; charset=utf-8";
        ctx.Response.ContentLength64 = bytes.Length;
        ctx.Response.OutputStream.Write(bytes);
        ctx.Response.Close();
    }

    private static void RespondJson(HttpListenerContext ctx, object obj)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(obj));
        ctx.Response.StatusCode = 200;
        ctx.Response.ContentType = "application/json; charset=utf-8";
        ctx.Response.ContentLength64 = bytes.Length;
        ctx.Response.OutputStream.Write(bytes);
        ctx.Response.Close();
    }

    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern void mouse_event(uint f, uint dx, uint dy, uint d, UIntPtr e);
    [DllImport("user32.dll")] private static extern void keybd_event(byte vk, byte scan, uint f, UIntPtr e);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int n);

    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const byte VK_ESCAPE = 0x1B;
    private const byte VK_LWIN = 0x5B;
    private const byte VK_MENU = 0x12;
    private const byte VK_TAB = 0x09;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    public void Dispose()
    {
        _cts?.Cancel();
        try { _udp?.Close(); } catch { }
        try { _http?.Stop(); _http?.Close(); } catch { }
        _client.Dispose();
    }
}
