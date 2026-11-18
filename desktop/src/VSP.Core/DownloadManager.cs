using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace VSP.Core;

public abstract class BindableBase : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        return true;
    }

    protected void Raise([CallerMemberName] string? name = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class DownloadTaskInfo : BindableBase
{
    public string TaskId { get; } = Guid.NewGuid().ToString("N")[..12];
    public string Url { get; init; } = "";
    public string FormatId { get; init; } = "";
    public string DirectUrl { get; init; } = "";
    public string Kind { get; init; } = "";
    public string SaveDir { get; init; } = "";
    public string Title { get; init; } = "download";
    public DateTime StartedAt { get; } = DateTime.Now;

    private string _status = "running";
    public string Status
    {
        get => _status;
        set { if (Set(ref _status, value)) { Raise(nameof(StatusText)); Raise(nameof(CanCancel)); } }
    }

    private double _progress;
    public double Progress { get => _progress; set => Set(ref _progress, value); }

    private string _speed = "";
    public string Speed { get => _speed; set => Set(ref _speed, value); }

    private string _filename = "";
    public string Filename { get => _filename; set => Set(ref _filename, value); }

    private string _error = "";
    public string Error { get => _error; set => Set(ref _error, value); }

    private long _totalBytes;
    public long TotalBytes { get => _totalBytes; set { if (Set(ref _totalBytes, value)) Raise(nameof(SizeText)); } }

    private long _downloadedBytes;
    public long DownloadedBytes { get => _downloadedBytes; set { if (Set(ref _downloadedBytes, value)) Raise(nameof(SizeText)); } }

    public string StatusText => Status switch
    {
        "running" => "下载中",
        "done" => "已完成",
        "error" => "失败",
        "paused" => "已暂停",
        _ => Status,
    };

    public string SizeText => TotalBytes > 0
        ? $"{MediaUtil.FormatSize(DownloadedBytes)} / {MediaUtil.FormatSize(TotalBytes)}"
        : MediaUtil.FormatSize(DownloadedBytes);

    public bool CanCancel => Status == "running";
}

public sealed record DownloadRequest(
    string Url, string FormatId, string DirectUrl, string Title, string Kind, string SaveDir);

public sealed class DownloadManager
{
    private readonly PythonEngine _engine;
    private readonly System.Collections.Concurrent.ConcurrentDictionary<string, CancellationTokenSource> _cancellations = new();
    private readonly HttpClient _client;

    public ObservableCollection<DownloadTaskInfo> Tasks { get; } = [];

    public DownloadManager(PythonEngine engine)
    {
        _engine = engine;
        var handler = new HttpClientHandler { AllowAutoRedirect = true, AutomaticDecompression = System.Net.DecompressionMethods.None };
        _client = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
    }

    public DownloadTaskInfo Start(DownloadRequest request)
    {
        var task = new DownloadTaskInfo
        {
            Url = request.Url, FormatId = request.FormatId, DirectUrl = request.DirectUrl,
            Title = request.Title, Kind = request.Kind, SaveDir = request.SaveDir,
        };
        Tasks.Insert(0, task);
        Run(task, request);
        return task;
    }

    private void Run(DownloadTaskInfo task, DownloadRequest request)
    {
        var cts = new CancellationTokenSource();
        _cancellations[task.TaskId] = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                var direct = !string.IsNullOrEmpty(request.DirectUrl) &&
                             (request.FormatId.StartsWith("url:", StringComparison.Ordinal) ||
                              request.Kind == "image" ||
                              request.DirectUrl.Contains(".m3u8", StringComparison.OrdinalIgnoreCase));
                if (request.Kind == "image" && direct)
                    await DownloadImageAsync(task, request.DirectUrl, cts.Token);
                else
                    await _engine.RunDownloadAsync(
                        request.Url,
                        request.FormatId.StartsWith("url:", StringComparison.Ordinal)
                            ? request.FormatId
                            : string.IsNullOrEmpty(request.DirectUrl) ? request.FormatId : "url:" + request.DirectUrl,
                        request.Title, request.SaveDir, p =>
                        {
                            task.Status = p.Status;
                            task.Progress = Math.Max(0, Math.Min(100, p.Progress));
                            task.Speed = p.Speed;
                            task.Filename = p.Filename;
                            task.TotalBytes = p.Total;
                            task.DownloadedBytes = p.Downloaded;
                            if (!string.IsNullOrEmpty(p.Error)) task.Error = p.Error;
                        }, cts.Token);
                if (task.Status == "running") { task.Status = "done"; task.Progress = 100; task.Speed = ""; }
            }
            catch (OperationCanceledException)
            {
                if (task.Status != "done") { task.Status = "error"; task.Error = "已取消"; task.Speed = ""; }
            }
            catch (Exception ex)
            {
                task.Status = "error";
                task.Error = ex.Message;
                task.Speed = "";
            }
            finally
            {
                _cancellations.TryRemove(task.TaskId, out var old);
                old?.Dispose();
            }
        });
    }

    private async Task DownloadImageAsync(DownloadTaskInfo task, string url, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        RefererPolicy.Apply(req, url);
        using var resp = await _client.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        resp.EnsureSuccessStatusCode();
        var total = resp.Content.Headers.ContentLength ?? 0;
        task.TotalBytes = total;

        var ext = MediaUtil.GuessExtension(url, ".jpg");
        var name = MediaUtil.SafeFileName(task.Title) + ext;
        Directory.CreateDirectory(task.SaveDir);
        var path = Path.Combine(task.SaveDir, name);
        await using var output = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 81920, true);
        await using var source = await resp.Content.ReadAsStreamAsync(ct);
        var buffer = new byte[81920];
        long downloaded = 0;
        int read;
        while ((read = await source.ReadAsync(buffer, ct)) > 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, read), ct);
            downloaded += read;
            task.DownloadedBytes = downloaded;
            task.Progress = total > 0 ? downloaded * 100.0 / total : 0;
            task.Speed = MediaUtil.FormatSize(downloaded) + " 已下载";
        }
        task.Filename = name;
    }

    public void Cancel(string taskId)
    {
        if (_cancellations.TryGetValue(taskId, out var cts)) cts.Cancel();
        var task = Tasks.FirstOrDefault(t => t.TaskId == taskId);
        if (task is { Status: "running" }) { task.Status = "error"; task.Error = "已取消"; task.Speed = ""; }
    }

    public void Retry(string taskId)
    {
        var task = Tasks.FirstOrDefault(t => t.TaskId == taskId);
        if (task is null) return;
        var request = new DownloadRequest(task.Url, task.FormatId, task.DirectUrl, task.Title, task.Kind, task.SaveDir);
        task.Status = "running"; task.Error = ""; task.Progress = 0; task.Speed = "";
        task.DownloadedBytes = 0; task.TotalBytes = 0;
        Run(task, request);
    }

    public void Remove(string taskId)
    {
        Cancel(taskId);
        var task = Tasks.FirstOrDefault(t => t.TaskId == taskId);
        if (task is not null) Tasks.Remove(task);
        if (!string.IsNullOrEmpty(task?.Filename))
        {
            try
            {
                var path = Path.Combine(task.SaveDir, task.Filename);
                if (File.Exists(path)) File.Delete(path);
            }
            catch { }
        }
    }

    public void ClearFinished()
    {
        foreach (var done in Tasks.Where(t => t.Status == "done" || t.Status == "error").ToList()) Tasks.Remove(done);
    }
}
