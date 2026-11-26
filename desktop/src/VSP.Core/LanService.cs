using System.Collections.Concurrent;
using System.ComponentModel;
using System.Drawing;
using System.Drawing.Imaging;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace VSP.Core;

/// <summary>局域网上发现的一台设备（手机或电脑）。</summary>
public sealed class LanDevice : INotifyPropertyChanged
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Kind { get; set; } = "pc"; // pc | android
    public string Ip { get; set; } = "";
    public int Port { get; set; }
    public DateTime LastSeen { get; set; }
    public bool IsAndroid => string.Equals(Kind, "android", StringComparison.OrdinalIgnoreCase);

    private bool _isConnected;
    /// <summary>当前是否已与本设备建立连接（两端状态通过 UDP hello 同步）。</summary>
    public bool IsConnected
    {
        get => _isConnected;
        set { if (_isConnected != value) { _isConnected = value; PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsConnected))); } }
    }
    public event PropertyChangedEventHandler? PropertyChanged;
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
    // 配对后为每台设备生成会话密钥，后续请求用 HMAC-SHA256 签名，不再只靠明文静态令牌。
    private readonly ConcurrentDictionary<string, string> _sessionKeys = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, long> _seenNonces = new(StringComparer.OrdinalIgnoreCase);
    private readonly HttpClient _client = new() { Timeout = TimeSpan.FromSeconds(8) };
    private readonly object _connLock = new();
    private string? _connectedPeerId;
    private LanDevice? _connectedPeer;
    private UdpClient? _udp;
    private TcpListener? _tcp;
    private CancellationTokenSource? _cts;
    private string DeviceId => _deviceId;

    private static string CanonicalQuery(IEnumerable<KeyValuePair<string, string>> qs) =>
        string.Join("&", qs
            .Where(x => !string.Equals(x.Key, "k", StringComparison.OrdinalIgnoreCase)
                     && !string.Equals(x.Key, "s", StringComparison.OrdinalIgnoreCase))
            .OrderBy(x => x.Key, StringComparer.Ordinal)
            .Select(x => $"{x.Key}={x.Value}"));

    private string Sign(string peerId, string method, string path, string canonical, byte[] body,
                        string? keyOverride = null)
    {
        // 配对阶段双方还没有会话密钥，必须用静态令牌签名，否则握手会死锁。
        var key = keyOverride ?? (_sessionKeys.TryGetValue(peerId, out var k) ? k : Token);
        // 必须与 Java 端 sha256Hex() 同为小写，否则 HMAC 签名跨平台永远不匹配。
        var bodyHash = Convert.ToHexString(SHA256.HashData(body)).ToLowerInvariant();
        var data = $"{method}\n{path}\n{canonical}\n{bodyHash}";
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(key));
        return Convert.ToHexString(hmac.ComputeHash(Encoding.UTF8.GetBytes(data))).ToLowerInvariant();
    }

    private bool Verify(IReadOnlyDictionary<string, string> qs, string method, string path, byte[] body)
    {
        if (!qs.TryGetValue("v", out var v) || v != "2") return true; // 兼容旧版客户端
        if (!qs.TryGetValue("s", out var sig) || string.IsNullOrEmpty(sig)) return false;
        if (!qs.TryGetValue("n", out var nonce) || string.IsNullOrEmpty(nonce)) return false;

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        foreach (var (key, seen) in _seenNonces)
            if (now - seen > 5 * 60 * 1000) _seenNonces.TryRemove(key, out _);
        if (!_seenNonces.TryAdd(nonce, now)) return false;

        var peer = qs.GetValueOrDefault("id") ?? "";
        var pairing = path is "/pair" or "/pair-result" or "/unpair";
        var canonical = CanonicalQuery(qs);
        var expected = Sign(peer, method, path, canonical, body, pairing ? Token : null);
        if (CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(sig.ToLowerInvariant())))
            return true;
        // 兼容会话密钥还没同步完成的窗口：静态令牌也认，避免配对刚完成时远控/断开消息丢失。
        var fallback = Sign(peer, method, path, canonical, body, Token);
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(fallback), Encoding.UTF8.GetBytes(sig.ToLowerInvariant()));
    }

    private string SignedUrl(LanDevice dev, string path, Dictionary<string, string>? query = null,
                             string method = "GET", byte[]? body = null)
    {
        query ??= [];
        query["id"] = DeviceId;
        query["n"] = Guid.NewGuid().ToString("N");
        query["v"] = "2";
        var pairing = path is "/pair" or "/pair-result" or "/unpair";
        query["s"] = Sign(dev.Id, method, path, CanonicalQuery(query), body ?? [],
            pairing ? Token : null);
        var text = string.Join("&", query.Select(x => $"{x.Key}={Uri.EscapeDataString(x.Value)}"));
        return $"http://{dev.Ip}:{dev.Port}{path}?k={Token}&{text}";
    }

    public string LocalIp { get; private set; } = "127.0.0.1";
    public string ShareRoot => _shareRoot;
    public IReadOnlyList<LanDevice> Devices => _devices.Values
        .OrderBy(d => d.IsAndroid).ThenBy(d => d.Name).ToList();
    public event Action? DevicesChanged;
    /// <summary>收到一台设备发起的连接申请，UI 应弹窗让用户选择同意/拒绝。</summary>
    public event Action<LanPairRequest>? PairRequested;
    /// <summary>连接状态变化（建立、断开、对方断开）。两端状态通过 hello 广播同步。</summary>
    public event Action? ConnectionChanged;

    /// <summary>当前已连接的对端 Id，未连接时为 null。</summary>
    public string? ConnectedPeerId { get { lock (_connLock) return _connectedPeerId; } }
    /// <summary>当前已连接的对端设备，未连接时为 null。</summary>
    public LanDevice? ConnectedPeer { get { lock (_connLock) return _connectedPeer; } }
    public bool IsConnectedTo(string id) => !string.IsNullOrEmpty(id) && id == ConnectedPeerId;

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

            // hello 携带 c=当前已连接对端的 id，另一端据此实时同步"已连接"状态。
            byte[] HelloBytes() => Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new
            {
                id = _deviceId, n = _deviceName, t = "pc", p = HttpPort, k = Token,
                c = ConnectedPeerId ?? ""
            }));
            var targets = BroadcastAddresses();
            foreach (var ip in GetLocalPrivateIps()) _localIps.Add(ip);
            LocalIp = _localIps.FirstOrDefault() ?? "127.0.0.1";

            _ = Task.Run(async () =>
            {
                while (!ct.IsCancellationRequested)
                {
                    var helloBytes = HelloBytes();
                    foreach (var t in targets)
                    {
                        try { await _udp!.SendAsync(helloBytes, new IPEndPoint(t, UdpPort), ct); }
                        catch { }
                    }
                    // 跨网段/AP 隔离时广播到不了对方；对已发现设备补一条定向 hello，
                    // 让两端都能互相发现（单向发现的主要修复）。
                    foreach (var dev in _devices.Values.ToArray())
                    {
                        if (!IPAddress.TryParse(dev.Ip, out var ip)) continue;
                        try { await _udp!.SendAsync(helloBytes, new IPEndPoint(ip, UdpPort), ct); }
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
                        var peerConnectedTo = root.TryGetProperty("c", out var c) ? c.GetString() ?? "" : "";
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
                        // 对方 hello 里说它连着我 → 本机也应进入已连接状态（握手双方对称）。
                        // 不再因为某一条 c 不一致就自动断开：手机休眠/多网卡/握手竞态都会
                        // 让 c 短暂为空或滞后，之前这会把已连接状态直接清掉。
                        if (peerConnectedTo == _deviceId && !IsConnectedTo(id))
                            SetConnected(dev);
                        SyncDeviceFlags();
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
                        var cutoff = DateTime.Now.AddSeconds(-30);
                        var dead = _devices.Values.Where(d => d.LastSeen < cutoff).Select(d => d.Id).ToList();
                        if (dead.Count > 0)
                        {
                            foreach (var id in dead) _devices.TryRemove(id, out _);
                            // 已连接设备超时不再自动断开：手机锁屏 / Wi-Fi 休眠很常见，
                            // 自动断开会让用户觉得“连上又自己断了”。断开只由显式 unpair 触发。
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
            var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            string? line;
            while (!string.IsNullOrEmpty(line = await ReadLineAsync(stream)))
            {
                var colon = line.IndexOf(':');
                if (colon <= 0) continue;
                var key = line[..colon].Trim();
                var val = line[(colon + 1)..].Trim();
                headers[key] = val;
                if (key.Equals("Content-Length", StringComparison.OrdinalIgnoreCase)
                    && long.TryParse(val, out var cl)) contentLen = cl;
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

            if (!Verify(qs, parts[0], path, body))
            {
                Respond(stream, 403, "bad signature");
                return;
            }

            switch (path)
            {
                case "/hello": Respond(stream, 200, $"{_deviceName}|pc"); break;
                case "/files": await RespondJsonAsync(stream, ListFiles(qs.GetValueOrDefault("dir"))); break;
                case "/dl": await ServeDownloadAsync(stream, qs.GetValueOrDefault("path"), headers.GetValueOrDefault("Range")); break;
                case "/ul": await ReceiveUploadAsync(stream, qs.GetValueOrDefault("name"), body,
                               long.TryParse(qs.GetValueOrDefault("off"), out var off) ? off : 0); break;
                case "/screen": await ServeScreenAsync(stream); break;
                case "/input": await ReceiveInputAsync(stream, body); break;
                case "/pair":
                    {
                        // 对方请求连接：记录申请并通知 UI 弹窗；附带把对方登记为设备，便于同意后直接操作。
                        var req = new LanPairRequest
                        {
                            Id = qs.GetValueOrDefault("id") ?? "",
                            Name = qs.GetValueOrDefault("dn") ?? "设备",
                            Kind = qs.GetValueOrDefault("t") ?? "android",
                            Ip = qs.GetValueOrDefault("ip") ?? "",
                            Port = int.TryParse(qs.GetValueOrDefault("p"), out var pp) ? pp : HttpPort,
                        };
                        if (!string.IsNullOrEmpty(req.Id) && IsPrivateOrLoopback(req.Ip))
                        {
                            // 已经和该设备连接，或该设备的申请正在处理：直接回应，绝不重复弹窗。
                            if (IsConnectedTo(req.Id)) { Respond(stream, 200, "ok"); }
                            else if (_pairRequests.ContainsKey(req.Id) || _pairAnswers.ContainsKey(req.Id))
                            { Respond(stream, 200, "wait"); }
                            else
                            {
                                _pairRequests[req.Id] = req;
                                _pairAnswers.TryRemove(req.Id, out _);
                                try { PairRequested?.Invoke(req); } catch { }
                                Respond(stream, 200, "ok");
                            }
                        }
                        else Respond(stream, 400, "bad request");
                        break;
                    }
                case "/pair-result":
                    {
                        // 发起方轮询：已连接/我是否同意/拒绝它的申请。
                        var rid = qs.GetValueOrDefault("id") ?? "";
                        var answer = IsConnectedTo(rid) ? "ok"
                            : _pairAnswers.TryGetValue(rid, out var a) ? (a ? "ok" : "no") : "wait";
                        // 同意配对时把本次会话密钥交给发起方，之后的请求用 HMAC 签名。
                        if (answer == "ok" && _sessionKeys.TryGetValue(rid, out var sessionKey))
                            answer += ":" + sessionKey;
                        Respond(stream, 200, answer);
                        if (answer != "wait" && !IsConnectedTo(rid))
                        { _pairAnswers.TryRemove(rid, out _); _pairRequests.TryRemove(rid, out _); }
                        break;
                    }
                case "/unpair":
                    {
                        // 对端主动断开：同步清除本机连接状态。
                        var rid = qs.GetValueOrDefault("id") ?? "";
                        if (IsConnectedTo(rid)) ClearConnected();
                        Respond(stream, 200, "ok");
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

    private async Task ServeDownloadAsync(NetworkStream stream, string? pathB64, string? range)
    {
        try
        {
            var rel = DecodePath(pathB64) ?? "";
            var full = Path.GetFullPath(Path.Combine(_shareRoot, rel));
            if (!IsUnderRoot(full) || !File.Exists(full)) { Respond(stream, 404, "missing"); return; }
            using var file = new FileStream(full, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, useAsync: true);

            var start = 0L;
            if (!string.IsNullOrEmpty(range) && range.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
            {
                var part = range[6..].Split('-')[0];
                if (long.TryParse(part, out var parsed) && parsed >= 0 && parsed < file.Length) start = parsed;
            }
            if (start > 0) file.Position = start;
            var length = file.Length - start;

            var code = start > 0 ? 206 : 200;
            var reason = code == 200 ? "OK" : "Partial Content";
            var head = new StringBuilder($"HTTP/1.1 {code} {reason}\r\nContent-Type: application/octet-stream\r\n");
            head.Append($"Content-Length: {length}\r\n");
            if (start > 0) head.Append($"Content-Range: bytes {start}-{file.Length - 1}/{file.Length}\r\n");
            head.Append("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(Encoding.ASCII.GetBytes(head.ToString()).AsMemory());
            await stream.FlushAsync();
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

    private async Task ReceiveUploadAsync(NetworkStream stream, string? nameB64, byte[] body, long offset)
    {
        var name = DecodePath(nameB64);
        if (string.IsNullOrWhiteSpace(name)) { Respond(stream, 400, "no name"); return; }
        name = Path.GetFileName(name);
        try
        {
            var full = Path.Combine(_shareRoot, name);
            var mode = offset > 0 && File.Exists(full) ? FileMode.Append : FileMode.Create;
            await using var fs = new FileStream(full, mode, FileAccess.Write, FileShare.None, 81920, true);
            await fs.WriteAsync(body.AsMemory());
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
        try { return await _client.GetStringAsync(SignedUrl(dev, "/hello")); }
        catch { return null; }
    }

    public async Task<List<LanFileEntry>> ListRemoteAsync(LanDevice dev, string? dirB64 = null)
    {
        try
        {
            var json = await _client.GetStringAsync(SignedUrl(dev, "/files",
                new() { ["dir"] = dirB64 ?? "" }));
            return JsonSerializer.Deserialize<List<LanFileEntry>>(json) ?? [];
        }
        catch { return []; }
    }

    public async Task<bool> DownloadAsync(LanDevice dev, LanFileEntry entry, string localPath,
        IProgress<double>? progress = null)
    {
        try
        {
            var url = SignedUrl(dev, "/dl", new() { ["path"] = entry.Path });
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            var have = File.Exists(localPath) ? new FileInfo(localPath).Length : 0;
            if (have > 0) req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(have, null);
            using var resp = await _client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
            resp.EnsureSuccessStatusCode();
            var append = have > 0 && resp.StatusCode == HttpStatusCode.PartialContent;
            var total = (resp.Content.Headers.ContentLength ?? 0) + (append ? have : 0);
            await using var src = await resp.Content.ReadAsStreamAsync();
            await using var dst = new FileStream(localPath, append ? FileMode.Append : FileMode.Create,
                FileAccess.Write, FileShare.None, 81920, true);
            var buf = new byte[81920];
            long read = append ? have : 0;
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

    public async Task<bool> UploadAsync(LanDevice dev, string localPath,
        IProgress<double>? progress = null)
    {
        try
        {
            var name = EncodePath(Path.GetFileName(localPath));
            const int chunk = 4 * 1024 * 1024;
            await using var fs = File.OpenRead(localPath);
            var buf = new byte[chunk];
            long off = 0;
            int n;
            while ((n = await fs.ReadAsync(buf)) > 0)
            {
                var piece = buf.AsMemory(0, n).ToArray();
                var url = SignedUrl(dev, "/ul",
                    new() { ["name"] = name, ["off"] = off.ToString() }, "POST", piece);
                using var content = new ByteArrayContent(piece);
                var resp = await _client.PostAsync(url, content);
                resp.EnsureSuccessStatusCode();
                off += n;
                progress?.Report(fs.Length > 0 ? (double)off / fs.Length : 1);
            }
            return true;
        }
        catch { return false; }
    }

    public async Task<byte[]?> GetScreenAsync(LanDevice dev)
    {
        try { return await _client.GetByteArrayAsync(SignedUrl(dev, "/screen")); }
        catch { return null; }
    }

    public async Task<bool> SendInputAsync(LanDevice dev, object payload)
    {
        try
        {
            var body = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload));
            var url = SignedUrl(dev, "/input", method: "POST", body: body);
            using var content = new ByteArrayContent(body);
            content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
            var resp = await _client.PostAsync(url, content);
            return resp.IsSuccessStatusCode;
        }
        catch { return false; }
    }

    // ---------------- 连接握手 ----------------

    /// <summary>向对方发起连接申请，携带本机信息；对方 UI 会弹窗确认。</summary>
    public async Task<bool> RequestPairAsync(LanDevice dev)
    {
        try
        {
            var myIp = GetLocalPrivateIps().FirstOrDefault() ?? LocalIp;
            var url = SignedUrl(dev, "/pair", new()
            {
                ["dn"] = _deviceName, ["t"] = "pc",
                ["p"] = HttpPort.ToString(), ["ip"] = myIp,
            });
            for (var i = 0; i < 3; i++)
            {
                var r = (await _client.GetStringAsync(url)).Trim();
                if (r.StartsWith("ok:", StringComparison.Ordinal)) _sessionKeys[dev.Id] = r[3..].Trim();
                if (r == "ok" || r.StartsWith("ok:", StringComparison.Ordinal)) return true;
                await Task.Delay(500);
            }
            return false;
        }
        catch { return false; }
    }

    private void ApplyPairAnswer(LanDevice dev, string answer)
    {
        if (answer.StartsWith("ok:", StringComparison.Ordinal))
            _sessionKeys[dev.Id] = answer[3..].Trim();
        if ((answer == "ok" || answer.StartsWith("ok:", StringComparison.Ordinal)) && !IsConnectedTo(dev.Id))
            SetConnected(dev);
    }

    /// <summary>轮询对方是否已同意我的申请：wait / ok[:sessionKey] / no。</summary>
    public async Task<string> PollPairAsync(LanDevice dev)
    {
        try
        {
            var url = SignedUrl(dev, "/pair-result");
            var r = (await _client.GetStringAsync(url)).Trim();
            ApplyPairAnswer(dev, r);
            return r.StartsWith("ok:", StringComparison.Ordinal) ? "ok" : r;
        }
        catch { return "wait"; }
    }

    /// <summary>本地审批对方发来的连接申请。同意后本机立即进入已连接状态。</summary>
    public void DecidePair(string reqId, bool accept)
    {
        if (!_pairRequests.TryGetValue(reqId, out var req)) return;
        _pairRequests.TryRemove(reqId, out _);
        _pairAnswers[reqId] = accept;
        if (accept)
        {
            _sessionKeys[reqId] = Guid.NewGuid().ToString("N");
            var dev = new LanDevice
            {
                Id = req.Id, Name = req.Name, Kind = req.Kind,
                Ip = req.Ip, Port = req.Port, LastSeen = DateTime.Now,
            };
            _devices[req.Id] = dev;
            SetConnected(dev);
            DevicesChanged?.Invoke();
        }
    }

    /// <summary>断开连接：清除本机状态并通知对端同步清除。</summary>
    public void Disconnect()
    {
        LanDevice? dev;
        lock (_connLock)
        {
            dev = _connectedPeer;
            if (dev is null && _connectedPeerId is not null)
                _devices.TryGetValue(_connectedPeerId, out dev);
        }
        ClearConnected();
        if (dev is not null)
            _ = _client.GetStringAsync(SignedUrl(dev, "/unpair"));
    }

    private void SetConnected(LanDevice dev)
    {
        lock (_connLock) { _connectedPeerId = dev.Id; _connectedPeer = dev; }
        SyncDeviceFlags();
        try { ConnectionChanged?.Invoke(); } catch { }
    }

    private void ClearConnected()
    {
        bool changed;
        lock (_connLock) { changed = _connectedPeerId is not null; _connectedPeerId = null; _connectedPeer = null; }
        SyncDeviceFlags();
        if (changed) { try { ConnectionChanged?.Invoke(); } catch { } }
    }

    private void SyncDeviceFlags()
    {
        var id = ConnectedPeerId;
        foreach (var d in _devices.Values) d.IsConnected = d.Id == id;
        var peer = ConnectedPeer;
        if (peer is not null) peer.IsConnected = true;
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
