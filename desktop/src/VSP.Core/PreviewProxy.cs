using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;

namespace VSP.Core;

/// <summary>
/// Local preview proxy. VLC only sees a short http://127.0.0.1/v/&lt;id&gt; URL while this
/// process attaches the original CDN Referer/UA and forwards Range, so direct links that
/// would 403 in a plain player play fine. Same idea as vsp.py's _PreviewProxy.
/// </summary>
public sealed class PreviewProxy : IDisposable
{
    private sealed record Target(string Url, string Referer);

    private readonly ConcurrentDictionary<long, Target> _store = new();
    private readonly HttpClient _client;
    private HttpListener? _listener;
    private string _prefix = "";
    private long _nextId;
    private readonly object _gate = new();
    private bool _disposed;

    public PreviewProxy()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.None,
            MaxConnectionsPerServer = 8,
        };
        _client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
    }

    public string Register(string upstreamUrl, string referer = "")
    {
        if (string.IsNullOrWhiteSpace(upstreamUrl)) throw new ArgumentException("URL 不能为空", nameof(upstreamUrl));
        EnsureStarted();
        var id = Interlocked.Increment(ref _nextId);
        if (!string.IsNullOrWhiteSpace(referer) && Uri.TryCreate(referer, UriKind.Absolute, out var refUri))
            referer = refUri.ToString();
        else
            referer = RefererPolicy.For(upstreamUrl);
        _store[id] = new Target(upstreamUrl, referer);
        if (_store.Count > 128)
        {
            var oldest = _store.Keys.OrderBy(k => k).Take(_store.Count - 128).ToList();
            foreach (var key in oldest) _store.TryRemove(key, out _);
        }
        return $"{_prefix}v/{id}";
    }

    private void EnsureStarted()
    {
        if (_listener is not null) return;
        lock (_gate)
        {
            if (_listener is not null) return;
            int port;
            using (var probe = new TcpListener(IPAddress.Loopback, 0))
            {
                probe.Start();
                port = ((IPEndPoint)probe.LocalEndpoint).Port;
            }
            _prefix = $"http://127.0.0.1:{port}/";
            var listener = new HttpListener();
            listener.Prefixes.Add(_prefix);
            listener.Start();
            _listener = listener;
            _ = Task.Run(ServeLoopAsync);
        }
    }

    private async Task ServeLoopAsync()
    {
        var listener = _listener;
        if (listener is null) return;
        while (!_disposed)
        {
            HttpListenerContext ctx;
            try { ctx = await listener.GetContextAsync(); }
            catch { return; }
            _ = Task.Run(() => HandleAsync(ctx));
        }
    }

    private async Task HandleAsync(HttpListenerContext ctx)
    {
        var response = ctx.Response;
        try
        {
            var path = ctx.Request.Url?.AbsolutePath ?? "";
            if (!path.StartsWith("/v/", StringComparison.Ordinal) ||
                !long.TryParse(path[3..], out var id) ||
                !_store.TryGetValue(id, out var target))
            {
                response.StatusCode = 404;
                response.Close();
                return;
            }

            var method = ctx.Request.HttpMethod.Equals("HEAD", StringComparison.OrdinalIgnoreCase)
                ? HttpMethod.Head : HttpMethod.Get;
            using var request = new HttpRequestMessage(method, target.Url);
            request.Headers.TryAddWithoutValidation("User-Agent", RefererPolicy.UserAgent);
            if (!string.IsNullOrEmpty(target.Referer))
                request.Headers.TryAddWithoutValidation("Referer", target.Referer);
            if (!string.IsNullOrEmpty(ctx.Request.Headers["Range"]))
                request.Headers.TryAddWithoutValidation("Range", ctx.Request.Headers["Range"]!);

            using var upstream = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead);
            response.StatusCode = (int)upstream.StatusCode;
            if (upstream.Content.Headers.ContentType is not null)
                response.ContentType = upstream.Content.Headers.ContentType.ToString();
            if (upstream.Content.Headers.ContentLength is { } length)
                response.ContentLength64 = length;
            if (upstream.Content.Headers.ContentRange is not null)
                response.Headers["Content-Range"] = upstream.Content.Headers.ContentRange.ToString();
            if (upstream.Headers.TryGetValues("Accept-Ranges", out var ranges))
                response.Headers["Accept-Ranges"] = string.Join(",", ranges);
            response.Headers["Access-Control-Allow-Origin"] = "*";
            response.Headers["Cache-Control"] = "no-store";

            if (method == HttpMethod.Head)
            {
                response.Close();
                return;
            }

            await using var source = await upstream.Content.ReadAsStreamAsync();
            var buffer = new byte[81920];
            int read;
            while ((read = await source.ReadAsync(buffer)) > 0)
            {
                await response.OutputStream.WriteAsync(buffer.AsMemory(0, read));
            }
            response.Close();
        }
        catch
        {
            try { response.StatusCode = 502; response.Close(); } catch { }
        }
    }

    public void Dispose()
    {
        _disposed = true;
        try { _listener?.Stop(); } catch { }
        _client.Dispose();
    }
}
