using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Net.NetworkInformation;
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

/// <summary>收到的连接申请（待对方同意）。</summary>
public sealed class LanPairRequest
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Kind { get; set; } = "android";
    public string Ip { get; set; } = "";
    public int Port { get; set; }
    public DateTime Time { get; set; } = DateTime.Now;
}

/// <summary>
/// 局域网直连服务：UDP 广播发现 + 内嵌 HTTP 服务（文件互传、屏幕查看、输入注入）。
/// 两端协议完全一致，全部走局域网 IP，不经公网服务器、不消耗移动流量。
/// 只接受私网地址，且所有请求必须带配对令牌 TOKEN。
///
/// HTTP 服务用裸 TcpListener 手写解析（而非 HttpListener）：Windows 上 HttpListener
/// 绑定非 localhost 前缀需要管理员执行 netsh urlacl，普通权限会抛 E_ACCESSDENIED，
/// 导致服务静默启动失败、手机发现电脑后却连不上。TcpListener 绑定 Any:端口无此限制，
/// 且与手机端 ServerSocket 的协议完全对称。
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
    private readonly HashSet<string> _localIps = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, LanPairRequest> _pairRequests = new();
    private readonly ConcurrentDictionary<string, bool> _pairAnswers = new();
    private readonly HttpClient _client = new() { Timeout = TimeSpan.FromSeconds(8) };
    private UdpClient? _udp;
    private TcpListener? _tcp;
    private CancellationTokenSource? _cts;
    private string DeviceId => _deviceId;

    public string LocalIp { get; private set; } = "127.0.0.1";
    public string ShareRoot => _shareRoot;
    public IReadOnlyList<LanDevice> Devices => _devices.Values
        .OrderBy(d => d.IsAndroid).ThenBy(d => d.Name).ToList();
    public event Action? DevicesChanged;
    /// <summary>收到一台设备发起的连接申请，UI 应弹窗让用户选择同意/拒绝。</summary>
    public event Action<LanPairRequest>? PairRequested;

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
            var targets = BroadcastAddresses();
            foreach (var ip in GetLocalPrivateIps()) _localIps.Add(ip);
            LocalIp = _localIps.FirstOrDefault() ?? "127.0.0.1";

            _ = Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    foreach (var t in targets)
                    {
                        try { await _udp!.SendAsync(helloBytes, new IPEndPoint(t, UdpPort), ct); }
                        catch { }
                    }
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
                        if (!IsPrivate(ip) || _localIps.Contains(ip.ToString())) continue;
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
                    try
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
                    catch (OperationCanceledException) { break; }
                    catch { }
                }
            }, ct);
        }
        catch { }
    }

    /// <summary>各私网网卡的子网定向广播地址 + 全局广播兜底。</summary>
    private static List<IPAddress> BroadcastAddresses()
    {
        var list = new List<IPAddress>();
        var seen = new HashSet<string>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork || !IsPrivate(ua.Address)) continue;
                    var ip = ua.Address.GetAddressBytes();
                    var mask = ua.IPv4Mask?.GetAddressBytes();
                    byte[] bc;
                    if (mask != null && mask.Length == 4)
                    {
                        bc = new byte[4];
                        for (var i = 0; i < 4; i++) bc[i] = (byte)(ip[i] | (~mask[i] & 0xFF));
                    }
                    else bc = new[] { ip[0], ip[1], ip[2], (byte)255 };
                    var addr = new IPAddress(bc);
                    if (seen.Add(addr.ToString())) list.Add(addr);
                }
            }
        }
        catch { }
        if (seen.Add(IPAddress.Broadcast.ToString())) list.Add(IPAddress.Broadcast);
        return list;
    }

    // ---------------- HTTP 服务（被控 / 被传） ----------------

    private void StartHttp()
    {
        try
        {
            _tcp = new TcpListener(IPAddress.Any, HttpPort);
            _tcp.Server.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            _tcp.Start();
            _ = Task.Run(async () =>
            {
                while (_tcp is not null)
                {
                    TcpClient client;
                    try { client = await _tcp.AcceptTcpClientAsync(); }
                    catch { break; }
                    _ = Task.Run(() => HandleClientAsync(client));
                }
            });
        }
        catch { }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        try
        {
            client.ReceiveTimeout = 20000;
            client.SendTimeout = 20000;
            var stream = client.GetStream();

            var requestLine = await ReadLineAsync(stream);
            if (string.IsNullOrEmpty(requestLine)) return;
            var parts = requestLine.Split(' ');
            if (parts.Length < 2) { Respond(stream, 400, "bad request"); return; }
            var target = parts[1];

            var qi = target.IndexOf('?');
            var path = qi >= 0 ? target[..qi] : target;
            var qs = ParseQuery(qi >= 0 ? target[(qi + 1)..] : "");
            if (!qs.TryGetValue("k", out var token) || token != Token)
            {
                Respond(stream, 403, "forbidden");
                return;
            }

            const long maxBody = 64L * 1024L * 1024L;
            long contentLen = 0;
            string? line;
            while (!string.IsNullOrEmpty(line = await ReadLineAsync(stream)))
            {
                if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)
                    && long.TryParse(line[(line.IndexOf(':') + 1)..].Trim(), out var cl))
                    contentLen = cl;
            }
            if (contentLen < 0 || contentLen > maxBody)
            {
                Respond(stream, 413, "too large");
                return;
            }
            var body = Array.Empty<byte>();
            if (contentLen > 0)
            {
                body = new byte[contentLen];
                var off = 0;
                while (off < contentLen)
                {
                    var n = await stream.ReadAsync(body.AsMemory(off, (int)contentLen - off));
                    if (n <= 0) break;
                    off += n;
                }
            }

            switch (path)
            {
                case "/hello": Respond(stream, 200, $"{_deviceName}|pc"); break;
                case "/files": await RespondJsonAsync(stream, ListFiles(qs.GetValueOrDefault("dir"))); break;
                case "/dl": await ServeDownloadAsync(stream, qs.GetValueOrDefault("path")); break;
                case "/ul": await ReceiveUploadAsync(stream, qs.GetValueOrDefault("name"), body); break;
                case "/screen": await ServeScreenAsync(stream); break;
                case "/input": await ReceiveInputAsync(stream, body); break;
                case "/pair":
                    {
                        // 对方请求连接：记录申请并通知 UI 弹窗；附带把对方登记为设备，便于同意后直接操作。
                        var req = new LanPairRequest
                        {
                            Id = qs.GetValueOrDefault("id") ?? "",
                            Name = qs.GetValueOrDefault("n") ?? "设备",
                            Kind = qs.GetValueOrDefault("t") ?? "android",
                            Ip = qs.GetValueOrDefault("ip") ?? "",
                            Port = int.TryParse(qs.GetValueOrDefault("p"), out var pp) ? pp : HttpPort,
                        };
                        if (!string.IsNullOrEmpty(req.Id) && IsPrivateOrLoopback(req.Ip))
                        {
                            _pairRequests[req.Id] = req;
                            _pairAnswers.TryRemove(req.Id, out _);
                            try { PairRequested?.Invoke(req); } catch { }
                            Respond(stream, 200, "ok");
                        }
                        else Respond(stream, 400, "bad request");
                        break;
                    }
                case "/pair-result":
                    {
                        // 发起方轮询：我是否已同意/拒绝它的申请。
                        var rid = qs.GetValueOrDefault("id") ?? "";
                        var answer = _pairAnswers.TryGetValue(rid, out var a) ? (a ? "ok" : "no") : "wait";
                        Respond(stream, 200, answer);
                        if (answer != "wait") { _pairAnswers.TryRemove(rid, out _); _pairRequests.TryRemove(rid, out _); }
                        break;
                    }
                default: Respond(stream, 404, "not found"); break;
            }
        }
        catch { }
        finally { try { client.Close(); } catch { } }
    }

    private static async Task<string?> ReadLineAsync(NetworkStream stream)
    {
        var sb = new StringBuilder();
        var one = new byte[1];
        while (true)
        {
            var n = await stream.ReadAsync(one.AsMemory(0, 1));
            if (n <= 0) return sb.Length > 0 ? sb.ToString() : null;
            if (one[0] == (byte)'\n') break;
            if (one[0] != (byte)'\r') sb.Append((char)one[0]);
            if (sb.Length > 16384) break;
        }
        return sb.ToString();
    }

    private static Dictionary<string, string> ParseQuery(string query)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var pair in query.Split('&'))
        {
            if (string.IsNullOrEmpty(pair)) continue;
            var eq = pair.IndexOf('=');
            var key = eq >= 0 ? pair[..eq] : pair;
            var val = eq >= 0 ? pair[(eq + 1)..] : "";
            try { result[key] = Uri.UnescapeDataString(val); } catch { result[key] = val; }
        }
        return result;
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

    private async Task ServeDownloadAsync(NetworkStream stream, string? pathB64)
    {
        try
        {
            var rel = DecodePath(pathB64) ?? "";
            var full = Path.GetFullPath(Path.Combine(_shareRoot, rel));
            if (!IsUnderRoot(full) || !File.Exists(full)) { Respond(stream, 404, "missing"); return; }
            using var file = new FileStream(full, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, useAsync: true);
            await WriteResponseHeadAsync(stream, 200, "application/octet-stream", file.Length);
            await file.CopyToAsync(stream);
        }
        catch { }
    }

    private static async Task WriteResponseHeadAsync(NetworkStream stream, int code, string type, long length)
    {
        try
        {
            var reason = code == 200 ? "OK" : "Error";
            var head = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 {code} {reason}\r\nContent-Type: {type}\r\nContent-Length: {length}\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(head.AsMemory());
            await stream.FlushAsync();
        }
        catch { }
    }

    private async Task ReceiveUploadAsync(NetworkStream stream, string? nameB64, byte[] body)
    {
        var name = DecodePath(nameB64);
        if (string.IsNullOrWhiteSpace(name)) { Respond(stream, 400, "no name"); return; }
        name = Path.GetFileName(name);
        try
        {
            var full = Path.Combine(_shareRoot, name);
            await File.WriteAllBytesAsync(full, body);
            Respond(stream, 200, "ok");
        }
        catch (Exception ex) { Respond(stream, 500, ex.Message); }
    }

    private async Task ServeScreenAsync(NetworkStream stream)
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
            await RespondBytesAsync(stream, 200, "image/jpeg", ms.ToArray());
        }
        catch { }
    }

    private async Task ReceiveInputAsync(NetworkStream stream, byte[] body)
    {
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
            Respond(stream, 200, "ok");
        }
        catch (Exception ex) { Respond(stream, 500, ex.Message); }
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

    // ---------------- 连接握手 + 手动直连 ----------------

    /// <summary>手动按 IP 添加设备（广播发现不可用时兜底；会校验 hello）。</summary>
    public async Task<(bool ok, string msg)> AddManualDeviceAsync(string ipText)
    {
        var ip = ipText.Trim();
        if (string.IsNullOrEmpty(ip) || !IsPrivateOrLoopback(ip))
            return (false, "请输入有效的局域网 IP（192.168.* / 10.* / 172.16~31.*）");
        var dev = new LanDevice { Id = "manual-" + ip, Name = ip, Kind = "pc", Ip = ip, Port = HttpPort, LastSeen = DateTime.Now };
        var hello = await HelloAsync(dev);
        if (hello is null) return (false, "连接失败：请确认对方已打开本功能，且未开启 AP 隔离/防火墙拦截");
        var parts = hello.Split('|');
        if (parts.Length > 1) dev.Kind = parts[1];
        if (parts.Length > 0 && !string.IsNullOrWhiteSpace(parts[0])) dev.Name = parts[0];
        dev.Id = "manual-" + ip; // 保持固定，避免被超时剔除后又与发现的设备冲突
        _devices[dev.Id] = dev;
        DevicesChanged?.Invoke();
        return (true, $"已添加：{dev.Name}（{ip}）");
    }

    /// <summary>向对方发起连接申请，携带本机信息；对方 UI 会弹窗确认。</summary>
    public async Task<bool> RequestPairAsync(LanDevice dev)
    {
        try
        {
            var myIp = GetLocalPrivateIps().FirstOrDefault() ?? LocalIp;
            var url = $"http://{dev.Ip}:{dev.Port}/pair?k={Token}&id={Uri.EscapeDataString(DeviceId)}"
                      + $"&n={Uri.EscapeDataString(_deviceName)}&t=pc&p={HttpPort}&ip={Uri.EscapeDataString(myIp)}";
            var r = await _client.GetStringAsync(url);
            return r.Trim() == "ok";
        }
        catch { return false; }
    }

    /// <summary>轮询对方是否已同意我的申请：wait / ok / no。</summary>
    public async Task<string> PollPairAsync(LanDevice dev)
    {
        try
        {
            var r = await _client.GetStringAsync($"http://{dev.Ip}:{dev.Port}/pair-result?k={Token}&id={Uri.EscapeDataString(DeviceId)}");
            return r.Trim();
        }
        catch { return "wait"; }
    }

    /// <summary>本地审批对方发来的连接申请。同意则同时把对方登记为设备。</summary>
    public void DecidePair(string reqId, bool accept)
    {
        if (!_pairRequests.TryGetValue(reqId, out var req)) return;
        _pairRequests.TryRemove(reqId, out _);
        _pairAnswers[reqId] = accept;
        if (accept && !string.IsNullOrWhiteSpace(req.Name))
        {
            var dev = new LanDevice
            {
                Id = req.Id, Name = req.Name, Kind = req.Kind,
                Ip = req.Ip, Port = req.Port, LastSeen = DateTime.Now,
            };
            _devices[req.Id] = dev;
            DevicesChanged?.Invoke();
        }
    }

    private static bool IsPrivateOrLoopback(string ipText)
    {
        if (System.Net.IPAddress.TryParse(ipText, out var ip))
        {
            if (System.Net.IPAddress.IsLoopback(ip)) return true;
            return IsPrivate(ip);
        }
        return false;
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
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    if (ua.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivate(ua.Address))
                        list.Add(ua.Address.ToString());
            }
        }
        catch { }
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

    private static void Respond(NetworkStream stream, int code, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        WriteResponse(stream, code, "text/plain; charset=utf-8", bytes);
    }

    private static async Task RespondJsonAsync(NetworkStream stream, object obj)
    {
        var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(obj));
        await WriteResponseAsync(stream, 200, "application/json; charset=utf-8", bytes);
    }

    private static async Task RespondBytesAsync(NetworkStream stream, int code, string type, byte[] bytes)
        => await WriteResponseAsync(stream, code, type, bytes);

    private static void WriteResponse(NetworkStream stream, int code, string type, byte[] bytes)
    {
        try
        {
            var reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" :
                         code == 503 ? "Service Unavailable" : "Error";
            var head = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 {code} {reason}\r\nContent-Type: {type}\r\nContent-Length: {bytes.Length}\r\nConnection: close\r\n\r\n");
            stream.Write(head, 0, head.Length);
            stream.Write(bytes, 0, bytes.Length);
            stream.Flush();
        }
        catch { }
    }

    private static async Task WriteResponseAsync(NetworkStream stream, int code, string type, byte[] bytes)
    {
        try
        {
            var reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" :
                         code == 503 ? "Service Unavailable" : "Error";
            var head = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 {code} {reason}\r\nContent-Type: {type}\r\nContent-Length: {bytes.Length}\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(head.AsMemory());
            await stream.WriteAsync(bytes.AsMemory());
            await stream.FlushAsync();
        }
        catch { }
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
        try { _tcp?.Stop(); } catch { }
        _client.Dispose();
    }
}
