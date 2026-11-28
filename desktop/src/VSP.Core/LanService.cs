using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
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

/// <summary>收到的远程控制申请。RequestId 由主控端生成，用于回传审批结果。</summary>
public sealed class LanControlRequest
{
    public string RequestId { get; set; } = "";
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Kind { get; set; } = "android";
    public string Ip { get; set; } = "";
    public int Port { get; set; }
    public DateTime Time { get; set; } = DateTime.Now;
}

/// <summary>主控端发起远控后的返回值：Cid 用于轮询，Result 为 ok / wait / busy。</summary>
public sealed record LanControlStart(string Cid, string Result);

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
    /// <summary>远控画面宽度：1024 在 60fps 与清晰度之间比较平衡。</summary>
    private const int DesktopCaptureWidth = 1024;
    public const string Token = "TBOX2026";

    private readonly string _deviceId = Guid.NewGuid().ToString("N")[..10];
    private readonly string _deviceName;
    private readonly string _shareRoot;
    private readonly ConcurrentDictionary<string, LanDevice> _devices = new();
    private readonly HashSet<string> _localIps = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, LanPairRequest> _pairRequests = new();
    private readonly ConcurrentDictionary<string, bool> _pairAnswers = new();
    private readonly ConcurrentDictionary<string, DateTime> _pairAnswerTimes = new();
    private readonly ConcurrentDictionary<string, LanControlRequest> _controlRequests = new();
    private readonly ConcurrentDictionary<string, bool> _controlAnswers = new();
    private string? _activeControlCid;
    private string? _activeControllerId;
    // 对端 hello 里声明"连着我"的最后时间；用于 /unpair 丢失时兜底检测断开。
    private long _peerClaimsMeAt;
    // 配对后为每台设备生成会话密钥，后续请求用 HMAC-SHA256 签名，不再只靠明文静态令牌。
    private readonly ConcurrentDictionary<string, string> _sessionKeys = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, long> _seenNonces = new(StringComparer.OrdinalIgnoreCase);
    private readonly HttpClient _client = new() { Timeout = TimeSpan.FromSeconds(8) };
    // 流式取帧要一直挂着连接，不能套用普通请求的 8 秒超时。
    private readonly HttpClient _streamClient = new(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(8),
        PooledConnectionIdleTimeout = TimeSpan.FromSeconds(30),
    })
    { Timeout = Timeout.InfiniteTimeSpan };
    private readonly object _screenCaptureLock = new();
    private CancellationTokenSource? _screenCaptureCts;
    private volatile byte[]? _screenFrame;
    private long _screenFrameStamp;
    private int _screenStreamClients;
    // 采集线程发布新帧时唤醒所有推流连接，替代 15ms 级别的轮询等待。
    private readonly object _frameSignalLock = new();
    private TaskCompletionSource _frameSignal = CreateFrameSignal();

    private static TaskCompletionSource CreateFrameSignal() =>
        new(TaskCreationOptions.RunContinuationsAsynchronously);
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
    /// <summary>本机在局域网里的设备 Id（诊断/回环测试用）。</summary>
    public string SelfId => _deviceId;
    /// <summary>发现/HTTP 服务启动失败时非空，UI 必须显示出来，不能静默失败。</summary>
    public string? ServiceError { get; private set; }
    public IReadOnlyList<LanDevice> Devices => _devices.Values
        .OrderBy(d => d.IsAndroid).ThenBy(d => d.Name).ToList();
    public event Action? DevicesChanged;
    /// <summary>收到文件分片：name, received, total。</summary>
    public event Action<string, long, long>? FileIncoming;
    /// <summary>整个文件接收完成：name, total。</summary>
    public event Action<string, long>? FileReceived;
    /// <summary>收到一台设备发起的连接申请，UI 应弹窗让用户选择同意/拒绝。</summary>
    public event Action<LanPairRequest>? PairRequested;
    /// <summary>收到远程控制申请，UI 必须弹窗让用户选择，不能默认允许。</summary>
    public event Action<LanControlRequest>? ControlRequested;
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
            // 不启用 ReuseAddress：旧实例仍占用端口时新实例必须明确失败，
            // 否则两个进程静默共用端口，手机连到的可能是旧进程，表现为“互相发现不了/传文件没反应”。
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
                var round = 0;
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
                    // 广播被路由器/防火墙丢弃时的兜底：没发现设备就每 12 秒对本机 /24 单播一轮。
                    if (++round % 6 == 0 && _devices.IsEmpty) await SweepSubnetAsync(helloBytes, ct);
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
                        var name = root.TryGetProperty("n", out var n) ? n.GetString() ?? "设备" : "设备";
                        var kind = root.TryGetProperty("t", out var t) ? t.GetString() ?? "pc" : "pc";
                        var port = root.TryGetProperty("p", out var p) ? p.GetInt32() : HttpPort;
                        // 复用同一个 LanDevice 实例：UI 列表按引用缓存，替换对象会让"已连接"标记刷新不到。
                        LanDevice dev;
                        if (_devices.TryGetValue(id, out var existing))
                        {
                            dev = existing;
                            dev.Name = name; dev.Kind = kind; dev.Ip = ip.ToString();
                            dev.Port = port; dev.LastSeen = DateTime.Now;
                        }
                        else
                        {
                            dev = new LanDevice
                            {
                                Id = id, Name = name, Kind = kind, Ip = ip.ToString(),
                                Port = port, LastSeen = DateTime.Now,
                            };
                            _devices[id] = dev;
                        }
                        // 对方 hello 里说它连着我 → 两端状态对称；同时记下心跳时间，
                        // 之后连续多个 hello 都不再声明连接时才判定对方已断开。
                        if (peerConnectedTo == _deviceId)
                        {
                            Interlocked.Exchange(ref _peerClaimsMeAt, DateTime.UtcNow.Ticks);
                            if (!IsConnectedTo(id)) SetConnected(dev);
                        }
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
                            // 已连接设备不因"没收到 hello"直接剔除：手机锁屏/休眠很常见。
                            DevicesChanged?.Invoke();
                        }
                        // /unpair 丢包兜底：连续约 12 秒没看到对端声明"连着我"，视为对方已断开。
                        if (ConnectedPeerId is not null)
                        {
                            var last = Interlocked.Read(ref _peerClaimsMeAt);
                            if (last == 0) Interlocked.Exchange(ref _peerClaimsMeAt, DateTime.UtcNow.Ticks);
                            else if (DateTime.UtcNow.Ticks - last > TimeSpan.FromSeconds(12).Ticks)
                                ClearConnected();
                        }
                    }
                    catch (OperationCanceledException) { break; }
                    catch { }
                }
            }, ct);
        }
        catch (Exception ex)
        {
            ServiceError = "局域网发现启动失败：" + ex.Message;
            try { DevicesChanged?.Invoke(); } catch { }
        }
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

    /// <summary>广播不可达时的兜底：对本机 /24 的每个地址发单播 hello。</summary>
    private async Task SweepSubnetAsync(byte[] hello, CancellationToken ct)
    {
        if (!IPAddress.TryParse(LocalIp, out var local) || !IsPrivate(local)) return;
        var b = local.GetAddressBytes();
        for (var i = 1; i < 255; i++)
        {
            if (ct.IsCancellationRequested) return;
            var target = new IPAddress(new[] { b[0], b[1], b[2], (byte)i });
            if (target.Equals(local) || _localIps.Contains(target.ToString())) continue;
            try { await _udp!.SendAsync(hello, new IPEndPoint(target, UdpPort), ct); }
            catch { }
        }
    }

    // ---------------- HTTP 服务（被控 / 被传） ----------------

    private void StartHttp()
    {
        try
        {
            _tcp = new TcpListener(IPAddress.Any, HttpPort);
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
        catch (Exception ex)
        {
            ServiceError = "局域网服务启动失败：" + ex.Message;
            try { DevicesChanged?.Invoke(); } catch { }
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        try
        {
            client.ReceiveTimeout = 20000;
            client.SendTimeout = 20000;
            // 远控输入是大量小包，Nagle 算法会把几十毫秒的延迟叠成肉眼可见的迟滞。
            client.NoDelay = true;
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
                               long.TryParse(qs.GetValueOrDefault("off"), out var off) ? off : 0,
                               long.TryParse(qs.GetValueOrDefault("size"), out var total) ? total : 0,
                               qs.GetValueOrDefault("last") == "1"); break;
                case "/screen":
                    // 只有当前被授权的主控端才能取帧，防止配对后任意设备直接窥屏。
                    if (!IsControlActiveFor(qs.GetValueOrDefault("id")))
                    { Respond(stream, 403, "control not allowed"); break; }
                    await ServeScreenAsync(stream);
                    break;
                case "/screen-stream":
                    // 长连接持续推帧：每帧 4 字节大端长度 + JPEG。
                    // 轮询一帧要一次完整 HTTP 往返，延迟被固定间隔和慢启动放大；
                    // 流式连接只在授权期间保持一次握手。
                    if (!IsControlActiveFor(qs.GetValueOrDefault("id")))
                    { Respond(stream, 403, "control not allowed"); break; }
                    await ServeScreenStreamAsync(stream, qs.GetValueOrDefault("id") ?? "");
                    break;
                case "/input":
                    if (!IsControlActiveFor(qs.GetValueOrDefault("id")))
                    { Respond(stream, 403, "control not allowed"); break; }
                    await ReceiveInputAsync(stream, body);
                    break;
                case "/pair":
                    {
                        // 对方请求连接：记录申请并通知 UI 弹窗。IP 以 TCP 对端为准，
                        // 手机自报的 0.0.0.0 / 空值 / VPN 地址不能再让申请被 400 拒绝。
                        var remote = client.Client.RemoteEndPoint as IPEndPoint;
                        var remoteIp = remote is { Address.AddressFamily: AddressFamily.InterNetwork }
                            ? remote.Address.ToString() : "";
                        var reportedIp = qs.GetValueOrDefault("ip") ?? "";
                        var ip = IsPrivateOrLoopback(remoteIp) && remoteIp != "127.0.0.1"
                            ? remoteIp
                            : reportedIp;
                        if (!IsPrivateOrLoopback(ip)) ip = remoteIp;
                        var req = new LanPairRequest
                        {
                            Id = qs.GetValueOrDefault("id") ?? "",
                            Name = qs.GetValueOrDefault("dn") ?? "设备",
                            Kind = qs.GetValueOrDefault("t") ?? "android",
                            Ip = ip,
                            Port = int.TryParse(qs.GetValueOrDefault("p"), out var pp) && pp > 0 && pp < 65536
                                ? pp : HttpPort,
                        };
                        if (string.IsNullOrEmpty(req.Id) || !IsPrivateOrLoopback(req.Ip))
                        {
                            Respond(stream, 400, "bad request");
                            break;
                        }

                        if (IsConnectedTo(req.Id)) { Respond(stream, 200, "ok"); break; }

                        // 过期的申请/答复不能永久堵住后续连接：旧弹窗用户没点就放它重新来。
                        if (_pairRequests.TryGetValue(req.Id, out var pending)
                            && DateTime.Now - pending.Time > TimeSpan.FromSeconds(30))
                        {
                            _pairRequests.TryRemove(req.Id, out _);
                            pending = null;
                        }
                        if (_pairAnswers.TryGetValue(req.Id, out _)
                            && _pairAnswerTimes.TryGetValue(req.Id, out var answeredAt)
                            && DateTime.Now - answeredAt > TimeSpan.FromSeconds(60))
                        {
                            _pairAnswers.TryRemove(req.Id, out _);
                            _pairAnswerTimes.TryRemove(req.Id, out _);
                        }

                        if (_pairRequests.ContainsKey(req.Id) || _pairAnswers.ContainsKey(req.Id))
                        {
                            // wait 表示申请已收到并在等待本机确认，发起方应继续轮询而不是报发送失败。
                            Respond(stream, 200, "wait");
                        }
                        else
                        {
                            _pairRequests[req.Id] = req;
                            _pairAnswers.TryRemove(req.Id, out _);
                            _pairAnswerTimes.TryRemove(req.Id, out _);
                            try { PairRequested?.Invoke(req); } catch { }
                            Respond(stream, 200, "ok");
                        }
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
                        {
                            _pairAnswers.TryRemove(rid, out _);
                            _pairAnswerTimes.TryRemove(rid, out _);
                            _pairRequests.TryRemove(rid, out _);
                        }
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
                case "/control-request":
                    {
                        var cid = qs.GetValueOrDefault("cid") ?? "";
                        var rid = qs.GetValueOrDefault("id") ?? "";
                        if (string.IsNullOrEmpty(cid)) { Respond(stream, 403, "bad request"); break; }
                        if (!IsConnectedTo(rid))
                        {
                            // 会话密钥还在说明之前配对过：借这次申请把两端状态重新同步回来。
                            if (!_devices.TryGetValue(rid, out var known) || !_sessionKeys.ContainsKey(rid))
                            { Respond(stream, 403, "not connected"); break; }
                            SetConnected(known);
                        }
                        if (IsControlActiveFor(rid))
                        {
                            // 同一台设备重复申请：视为已授权，允许它按新 cid 继续轮询。
                            Respond(stream, 200, "ok");
                        }
                        else if (_activeControllerId is not null || !_controlRequests.IsEmpty)
                        {
                            Respond(stream, 200, "busy");
                        }
                        else
                        {
                            var req = new LanControlRequest
                            {
                                RequestId = cid,
                                Id = rid,
                                Name = qs.GetValueOrDefault("dn") ?? "设备",
                                Kind = qs.GetValueOrDefault("t") ?? "android",
                                Ip = qs.GetValueOrDefault("ip") ?? "",
                                Port = int.TryParse(qs.GetValueOrDefault("p"), out var cp) ? cp : HttpPort,
                            };
                            if (_devices.TryGetValue(rid, out var known))
                            {
                                if (req.Name == "设备") req.Name = known.Name;
                                if (string.IsNullOrEmpty(req.Ip)) req.Ip = known.Ip;
                            }
                            _controlRequests[cid] = req;
                            _controlAnswers.TryRemove(cid, out _);
                            try { ControlRequested?.Invoke(req); } catch { }
                            Respond(stream, 200, "ok");
                        }
                        break;
                    }
                case "/control-result":
                    {
                        var cid = qs.GetValueOrDefault("cid") ?? "";
                        var rid = qs.GetValueOrDefault("id") ?? "";
                        if (!IsConnectedTo(rid))
                        { Respond(stream, 403, "not connected"); break; }
                        string result;
                        if (_activeControlCid is not null && rid == _activeControllerId)
                        {
                            result = "ok";
                        }
                        else if (_controlAnswers.TryGetValue(cid, out var answer))
                        {
                            result = answer ? "wait" : "no";
                        }
                        else if (_controlRequests.TryGetValue(cid, out var pending))
                        {
                            if (DateTime.Now - pending.Time > TimeSpan.FromSeconds(60))
                            {
                                _controlRequests.TryRemove(cid, out _);
                                result = "no";
                            }
                            else result = "wait";
                        }
                        else result = "no";
                        Respond(stream, 200, result);
                        if (result != "wait")
                        {
                            _controlAnswers.TryRemove(cid, out _);
                            _controlRequests.TryRemove(cid, out _);
                        }
                        break;
                    }
                case "/control-stop":
                    {
                        var cid = qs.GetValueOrDefault("cid") ?? "";
                        var rid = qs.GetValueOrDefault("id") ?? "";
                        if (!IsConnectedTo(rid))
                        { Respond(stream, 403, "not connected"); break; }
                        _controlRequests.TryRemove(cid, out _);
                        _controlAnswers.TryRemove(cid, out _);
                        if (_activeControlCid is not null && rid == _activeControllerId)
                            ClearControlState();
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

    private async Task ReceiveUploadAsync(NetworkStream stream, string? nameB64, byte[] body, long offset,
        long total, bool last)
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
            var received = fs.Length;
            Respond(stream, 200, "ok");
            try { FileIncoming?.Invoke(name, received, total); } catch { }
            // last 由新版发送方在最后一片带上；老版本没有时按“首片且不足一个分片”兜底。
            if (last || (!last && offset == 0 && body.Length < 4 * 1024 * 1024))
                try { FileReceived?.Invoke(name, total > 0 ? total : received); } catch { }
        }
        catch (Exception ex) { Respond(stream, 500, ex.Message); }
    }

    private async Task ServeScreenAsync(NetworkStream stream)
    {
        try
        {
            EnsureScreenCapture();
            TryGetScreenFrame(out var frame, out var stamp);
            var age = Stopwatch.GetTimestamp() - stamp;
            // 缓存超过 2 秒就现场补一帧，避免老客户端拿到很久以前的画面。
            if (frame is null || age > Stopwatch.Frequency * 2)
                frame = CaptureScreenJpeg();
            if (frame is null) { Respond(stream, 503, "no screen"); return; }
            await RespondBytesAsync(stream, 200, "image/jpeg", frame);
        }
        catch { }
    }

    /// <summary>
    /// 被控端屏幕采集循环：优先用 DXGI 从 GPU 取帧，目标 60fps；DXGI 不可用时退回 GDI。
    /// 所有取帧请求共享最新一帧，避免每个 /screen 请求都重新截屏 + 编码。
    /// </summary>
    private void EnsureScreenCapture()
    {
        lock (_screenCaptureLock)
        {
            if (_screenCaptureCts is not null) return;
            var cts = new CancellationTokenSource();
            _screenCaptureCts = cts;
            _ = Task.Run(() => ScreenCaptureLoopAsync(cts));
        }
    }

#pragma warning disable CA1416 // 远控采集只在 Windows 桌面端启用
    private async Task ScreenCaptureLoopAsync(CancellationTokenSource cts)
    {
        var ct = cts.Token;
        DxgiScreenCapture? dxgi = null;
        var lastDxgiAttempt = 0L;
        try
        {
            while (!ct.IsCancellationRequested && _activeControlCid is not null)
            {
                byte[]? frame = null;
                if (dxgi is not null && dxgi.IsAvailable)
                {
                    // 有主控端在拉流时按 90fps 采集（高刷屏实际约 60~80fps）；
                    // 只有旧版 /screen 轮询时降到 25fps，避免白烧 CPU。
                    var targetFps = Volatile.Read(ref _screenStreamClients) > 0 ? 90 : 25;
                    frame = dxgi.TryCaptureJpeg(DesktopCaptureWidth, targetFps);
                    if (!dxgi.IsAvailable)
                    {
                        dxgi.Dispose();
                        dxgi = null;
                        lastDxgiAttempt = Stopwatch.GetTimestamp();
                    }
                }
                else
                {
                    // DXGI 初始化失败（老显卡/远程会话）时每 30 秒再试一次，期间用 GDI 兜底。
                    if (dxgi is null && Stopwatch.GetTimestamp() - lastDxgiAttempt > Stopwatch.Frequency * 30)
                    {
                        lastDxgiAttempt = Stopwatch.GetTimestamp();
                        dxgi = DxgiScreenCapture.TryCreate();
                    }
                    if (dxgi is null || !dxgi.IsAvailable)
                    {
                        frame = CaptureScreenJpeg();
                        // GDI 单帧约 30~80ms，不加延迟也会自然限速。
                    }
                }

                if (frame is not null)
                {
                    PublishScreenFrame(frame);
                }
                else if (dxgi is null || !dxgi.IsAvailable)
                {
                    try { await Task.Delay(15, ct); }
                    catch (OperationCanceledException) { break; }
                }
            }
        }
        finally
        {
            dxgi?.Dispose();
            bool restart;
            lock (_screenCaptureLock)
            {
                restart = ReferenceEquals(_screenCaptureCts, cts) && _activeControlCid is not null;
                if (ReferenceEquals(_screenCaptureCts, cts)) _screenCaptureCts = null;
            }
            cts.Dispose();
            // 上一轮刚退出时如果新的远控会话已经建立，这里要把采集循环接上，
            // 否则新主控端会一直等不到帧。
            if (restart) EnsureScreenCapture();
        }
    }
#pragma warning restore CA1416

    /// <summary>GDI 兜底截屏：宽度限制在 1024，质量 58，尽量跟上推流节奏。</summary>
#pragma warning disable CA1416 // 桌面端只发布 Windows x64，System.Drawing 的屏幕捕获在目标平台可用
    private static byte[]? CaptureScreenJpeg()
    {
        try
        {
            var w = GetSystemMetrics(0);
            var h = GetSystemMetrics(1);
            if (w <= 0 || h <= 0) return null;
            const int maxWidth = DesktopCaptureWidth;
            var scale = Math.Min(1.0, (double)maxWidth / w);
            var outW = Math.Max(2, (int)Math.Round(w * scale));
            var outH = Math.Max(2, (int)Math.Round(h * scale));

            using var full = new Bitmap(w, h);
            using (var g = Graphics.FromImage(full))
                g.CopyFromScreen(0, 0, 0, 0, new Size(w, h));

            using var ms = new MemoryStream(128 * 1024);
            var codec = ImageCodecInfo.GetImageEncoders().First(c => c.MimeType == "image/jpeg");
            using var ep = new EncoderParameters(1);
            ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 58L);
            if (scale < 0.999)
            {
                using var scaled = new Bitmap(outW, outH);
                using (var gs = Graphics.FromImage(scaled))
                {
                    gs.InterpolationMode = InterpolationMode.Bilinear;
                    gs.DrawImage(full, new Rectangle(0, 0, outW, outH));
                }
                scaled.Save(ms, codec, ep);
            }
            else
            {
                full.Save(ms, codec, ep);
            }
            return ms.ToArray();
        }
        catch { return null; }
    }
#pragma warning restore CA1416

    /// <summary>持续推送最新帧；画面没变化时每秒重发一次，客户端可据此判断连接仍然有效。</summary>
    private async Task ServeScreenStreamAsync(NetworkStream stream, string controllerId)
    {
        Interlocked.Increment(ref _screenStreamClients);
        try
        {
            EnsureScreenCapture();
            var head = Encoding.ASCII.GetBytes(
                "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            await stream.WriteAsync(head.AsMemory());
            await stream.FlushAsync();

            var lenBuf = new byte[4];
            long lastStamp = 0;
            var lastSendAt = 0L;
            while (IsControlActiveFor(controllerId))
            {
                TryGetScreenFrame(out var frame, out var stamp);
                var now = Stopwatch.GetTimestamp();
                if (frame is not null && (stamp != lastStamp || now - lastSendAt > Stopwatch.Frequency))
                {
                    BinaryPrimitives.WriteInt32BigEndian(lenBuf, frame.Length);
                    await stream.WriteAsync(lenBuf.AsMemory());
                    await stream.WriteAsync(frame.AsMemory());
                    await stream.FlushAsync();
                    lastStamp = stamp;
                    lastSendAt = now;
                }
                else
                {
                    // 等采集线程发布新帧；最长 250ms 回来检查会话是否已结束。
                    await WaitForNextScreenFrameAsync(lastStamp);
                }
            }
        }
        catch { }
        finally
        {
            Interlocked.Decrement(ref _screenStreamClients);
        }
    }

    /// <summary>
    /// 取当前帧和时间戳。采集线程先写帧再写时间戳，这里前后各读一次时间戳，
    /// 只有两次一致才返回，避免把“新时间戳 + 旧帧”当成新帧而让画面卡到下一次保活。
    /// </summary>
    private bool TryGetScreenFrame(out byte[]? frame, out long stamp)
    {
        for (var i = 0; i < 4; i++)
        {
            var before = Volatile.Read(ref _screenFrameStamp);
            var current = _screenFrame;
            if (before == Volatile.Read(ref _screenFrameStamp))
            {
                frame = current;
                stamp = before;
                return current is not null;
            }
        }
        frame = _screenFrame;
        stamp = Volatile.Read(ref _screenFrameStamp);
        return frame is not null;
    }

    /// <summary>采集线程发布新帧：写入帧和时间戳后唤醒所有正在等待的推流连接。</summary>
    private void PublishScreenFrame(byte[] frame)
    {
        _screenFrame = frame;
        Volatile.Write(ref _screenFrameStamp, Stopwatch.GetTimestamp());
        TaskCompletionSource signal;
        lock (_frameSignalLock)
        {
            signal = _frameSignal;
            _frameSignal = CreateFrameSignal();
        }
        signal.TrySetResult();
    }

    /// <summary>等采集线程发布比 lastStamp 更新的帧；250ms 超时让调用方复查会话状态和保活。</summary>
    private async Task WaitForNextScreenFrameAsync(long lastStamp)
    {
        Task wait;
        lock (_frameSignalLock)
        {
            if (TryGetScreenFrame(out _, out var current) && current != lastStamp) return;
            wait = _frameSignal.Task;
        }
        // 取信号和复查之间可能刚好来了新帧，这里补一次判断，避免白等 250ms。
        if (TryGetScreenFrame(out _, out var again) && again != lastStamp) return;
        try { await wait.WaitAsync(TimeSpan.FromMilliseconds(250)); }
        catch (TimeoutException) { }
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
                case "move":
                {
                    // 手机端手指移动时高频发送，鼠标指针实时跟手，不需要等抬手。
                    var x = (int)(root.GetProperty("x").GetDouble() * sw);
                    var y = (int)(root.GetProperty("y").GetDouble() * sh);
                    SetCursorPos(x, y);
                    break;
                }
                case "down":
                {
                    var x = (int)(root.GetProperty("x").GetDouble() * sw);
                    var y = (int)(root.GetProperty("y").GetDouble() * sh);
                    SetCursorPos(x, y);
                    mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, UIntPtr.Zero);
                    break;
                }
                case "up":
                {
                    mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, UIntPtr.Zero);
                    break;
                }
                case "wheel":
                {
                    var delta = (int)root.GetProperty("d").GetDouble();
                    mouse_event(MOUSEEVENTF_WHEEL, 0, 0, (uint)delta, UIntPtr.Zero);
                    break;
                }
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
            var size = fs.Length;
            long off = 0;
            int n;
            while ((n = await fs.ReadAsync(buf)) > 0)
            {
                var piece = buf.AsMemory(0, n).ToArray();
                var query = new Dictionary<string, string>
                {
                    ["name"] = name,
                    ["off"] = off.ToString(),
                    ["size"] = size.ToString(),
                };
                if (off + n >= size) query["last"] = "1";
                var url = SignedUrl(dev, "/ul", query, "POST", piece);
                using var content = new ByteArrayContent(piece);
                var resp = await _client.PostAsync(url, content);
                resp.EnsureSuccessStatusCode();
                off += n;
                progress?.Report(size > 0 ? (double)off / size : 1);
            }
            return true;
        }
        catch { return false; }
    }

    private bool IsControlActiveFor(string? id) =>
        !string.IsNullOrEmpty(id) && _activeControlCid is not null && id == _activeControllerId;

    private void ClearControlState()
    {
        _activeControlCid = null;
        _activeControllerId = null;
        _controlRequests.Clear();
        _controlAnswers.Clear();
    }

    /// <summary>UI 审批收到的远控申请；允许后本机立即成为被控端。</summary>
    public bool DecideControl(string requestId, bool accept)
    {
        if (!_controlRequests.TryRemove(requestId, out var req)) return false;
        _controlAnswers[requestId] = accept;
        if (accept)
        {
            _activeControlCid = requestId;
            _activeControllerId = req.Id;
        }
        return true;
    }

    public async Task<byte[]?> GetScreenAsync(LanDevice dev)
    {
        try { return await _client.GetByteArrayAsync(SignedUrl(dev, "/screen")); }
        catch { return null; }
    }

    /// <summary>
    /// 主控端持续读取对方推送的屏幕帧。返回 true 表示流式接口可用（至少收到过一帧），
    /// false 表示对方是旧版、只能退回 /screen 轮询。取消令牌用于停止远控。
    /// </summary>
    public async Task<bool> StreamScreenAsync(LanDevice dev, Func<byte[], Task> onFrame, CancellationToken ct)
    {
        var gotFrame = false;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, SignedUrl(dev, "/screen-stream"));
            using var response = await _streamClient.SendAsync(
                request, HttpCompletionOption.ResponseHeadersRead, ct);
            if (!response.IsSuccessStatusCode) return false;
            await using var stream = await response.Content.ReadAsStreamAsync(ct);
            var lenBuf = new byte[4];
            while (!ct.IsCancellationRequested)
            {
                if (!await ReadExactAsync(stream, lenBuf, ct)) break;
                var len = BinaryPrimitives.ReadInt32BigEndian(lenBuf);
                if (len <= 0 || len > 8 * 1024 * 1024) break;
                var frame = new byte[len];
                if (!await ReadExactAsync(stream, frame, ct)) break;
                gotFrame = true;
                await onFrame(frame);
            }
        }
        catch (OperationCanceledException) { }
        catch { }
        return gotFrame;
    }

    private static async Task<bool> ReadExactAsync(Stream stream, byte[] buffer, CancellationToken ct)
    {
        // 被控端每秒至少发一帧保活；10 秒没有任何数据说明连接已死，主动断开让上层重连。
        using var readCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        readCts.CancelAfter(TimeSpan.FromSeconds(10));
        var offset = 0;
        while (offset < buffer.Length)
        {
            var n = await stream.ReadAsync(buffer.AsMemory(offset), readCts.Token);
            if (n <= 0) return false;
            offset += n;
        }
        return true;
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

    // ---------------- 远程控制申请 ----------------

    /// <summary>向对方发起远控申请；对方弹窗同意并完成授权后才返回 ok。</summary>
    public async Task<LanControlStart?> RequestControlAsync(LanDevice dev)
    {
        try
        {
            var cid = Guid.NewGuid().ToString("N");
            var myIp = GetLocalPrivateIps().FirstOrDefault() ?? LocalIp;
            var url = SignedUrl(dev, "/control-request", new()
            {
                ["cid"] = cid, ["dn"] = _deviceName, ["t"] = "pc",
                ["ip"] = myIp, ["p"] = HttpPort.ToString(),
            });
            var r = (await _client.GetStringAsync(url)).Trim();
            return r is "ok" or "wait" or "busy" ? new LanControlStart(cid, r) : null;
        }
        catch { return null; }
    }

    /// <summary>轮询对方是否已允许远控：wait / ok / no / busy。</summary>
    public async Task<string> PollControlAsync(LanDevice dev, string cid)
    {
        try
        {
            var url = SignedUrl(dev, "/control-result", new() { ["cid"] = cid });
            var r = (await _client.GetStringAsync(url)).Trim();
            return r is "ok" or "wait" or "no" or "busy" ? r : "wait";
        }
        catch { return "wait"; }
    }

    /// <summary>主控端停止远控：通知被控端关闭屏幕共享。</summary>
    public async Task StopControlAsync(LanDevice dev, string cid)
    {
        try { await _client.GetStringAsync(SignedUrl(dev, "/control-stop", new() { ["cid"] = cid })); }
        catch { }
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
                // wait = 申请已送达、对方还在等用户点确认；交给后面的 /pair-result 轮询，
                // 不能当成发送失败，否则第二次请求又会被对方当成重复申请继续 wait。
                if (r is "ok" or "wait" || r.StartsWith("ok:", StringComparison.Ordinal)) return true;
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
        _pairAnswerTimes[reqId] = DateTime.Now;
        if (accept)
        {
            _sessionKeys[reqId] = Guid.NewGuid().ToString("N");
            LanDevice dev;
            if (_devices.TryGetValue(req.Id, out var existing))
            {
                dev = existing;
                dev.Name = req.Name; dev.Kind = req.Kind; dev.Ip = req.Ip;
                dev.Port = req.Port; dev.LastSeen = DateTime.Now;
            }
            else
            {
                dev = new LanDevice
                {
                    Id = req.Id, Name = req.Name, Kind = req.Kind,
                    Ip = req.Ip, Port = req.Port, LastSeen = DateTime.Now,
                };
                _devices[req.Id] = dev;
            }
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
        {
            var target = dev;
            // 重试几次，避免单个 UDP/TCP 丢包让对端一直留在"已连接"。
            _ = Task.Run(async () =>
            {
                for (var i = 0; i < 3; i++)
                {
                    try { await _client.GetStringAsync(SignedUrl(target, "/unpair")); break; }
                    catch { await Task.Delay(300); }
                }
            });
        }
    }

    private void SetConnected(LanDevice dev)
    {
        lock (_connLock) { _connectedPeerId = dev.Id; _connectedPeer = dev; }
        Interlocked.Exchange(ref _peerClaimsMeAt, DateTime.UtcNow.Ticks);
        SyncDeviceFlags();
        try { ConnectionChanged?.Invoke(); } catch { }
    }

    private void ClearConnected()
    {
        bool changed;
        lock (_connLock) { changed = _connectedPeerId is not null; _connectedPeerId = null; _connectedPeer = null; }
        Interlocked.Exchange(ref _peerClaimsMeAt, 0);
        // 断开时立即撤销远控授权，避免旧主控端在重连前继续取帧。
        ClearControlState();
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
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
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
        try { _screenCaptureCts?.Cancel(); } catch { }
        _client.Dispose();
        _streamClient.Dispose();
    }
}
