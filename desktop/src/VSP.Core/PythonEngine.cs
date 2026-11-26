using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace VSP.Core;

public sealed record DownloadProgress(
    string Status, double Progress, string Speed, string Error, string Filename, long Total, long Downloaded,
    string TaskId = "");

/// <summary>
/// Bridges to the same vsp.py engine used by the Android and Web builds.
/// The installer ships a private CPython runtime, so users never need to install Python.
/// </summary>
public sealed class PythonEngine
{
    private readonly string _root;

    public PythonEngine(string appBaseDirectory) => _root = appBaseDirectory;

    public string ScriptPath => Path.Combine(_root, "python", "vsp_cli.py");
    public string BundledPython => Path.Combine(_root, "runtime", "python", "python.exe");

    public string PythonExe
    {
        get
        {
            if (File.Exists(BundledPython)) return BundledPython;
            var env = Environment.GetEnvironmentVariable("VSP_PYTHON");
            return string.IsNullOrWhiteSpace(env) ? "python" : env;
        }
    }

    public bool IsReady => File.Exists(ScriptPath) && (File.Exists(BundledPython) ||
        !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("VSP_PYTHON")));

    private ProcessStartInfo CreatePsi()
    {
        var psi = new ProcessStartInfo
        {
            FileName = PythonExe,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            UseShellExecute = false,
            CreateNoWindow = true,
            WorkingDirectory = _root,
        };
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        psi.Environment["PYTHONUTF8"] = "1";
        return psi;
    }

    public async Task<string> ParseAsync(string url, CancellationToken ct, int ttlSeconds = 600)
    {
        var psi = CreatePsi();
        psi.ArgumentList.Add(ScriptPath);
        psi.ArgumentList.Add("parse");
        psi.ArgumentList.Add(url);
        psi.ArgumentList.Add(ttlSeconds.ToString());

        using var proc = new Process { StartInfo = psi };
        using var registration = ct.Register(() => Kill(proc));
        try
        {
            proc.Start();
            var stdout = proc.StandardOutput.ReadToEndAsync();
            var stderr = proc.StandardError.ReadToEndAsync();
            var timeout = Task.Delay(TimeSpan.FromSeconds(90), ct);
            var finished = await Task.WhenAny(Task.WhenAll(stdout, stderr, proc.WaitForExitAsync()), timeout);
            if (finished == timeout)
            {
                Kill(proc);
                throw new Exception("解析超时（90 秒），请检查网络或链接");
            }
            var text = (await stdout).Trim();
            var err = (await stderr).Trim();
            if (proc.ExitCode != 0 || !text.StartsWith('{'))
                throw new Exception(string.IsNullOrWhiteSpace(err) ? "解析引擎没有返回结果" : err.Split('\n')[0].Trim());
            return text;
        }
        catch (OperationCanceledException)
        {
            Kill(proc);
            throw;
        }
    }

    public async Task RunDownloadAsync(string pageUrl, string formatId, string title, string dir,
                                       Action<DownloadProgress> report, CancellationToken ct,
                                        int speedLimitKbps = 0, string nameTemplate = "",
                                        string mediaId = "", string uploader = "", string source = "")
    {
        Directory.CreateDirectory(dir);
        var psi = CreatePsi();
        psi.ArgumentList.Add(ScriptPath);
        psi.ArgumentList.Add("download");
        psi.ArgumentList.Add(pageUrl);
        psi.ArgumentList.Add(formatId);
        psi.ArgumentList.Add(title);
        psi.ArgumentList.Add(dir);
        psi.ArgumentList.Add(speedLimitKbps.ToString());
        psi.ArgumentList.Add(nameTemplate);
        psi.ArgumentList.Add(mediaId);
        psi.ArgumentList.Add(uploader);
        psi.ArgumentList.Add(source);

        using var proc = new Process { StartInfo = psi };
        using var registration = ct.Register(() => Kill(proc));
        try
        {
            proc.Start();
            string? line;
            while ((line = await proc.StandardOutput.ReadLineAsync()) is not null)
            {
                var s = line.Trim();
                if (!s.StartsWith('{')) continue;
                try
                {
                    using var doc = JsonDocument.Parse(s);
                    var root = doc.RootElement;
                    report(new DownloadProgress(
                        Json.Str(root, "status"), Json.Num(root, "progress"), Json.Str(root, "speed"),
                        Json.Str(root, "error"), Json.Str(root, "filename"),
                        (long)Json.Num(root, "total"), (long)Json.Num(root, "downloaded"),
                        Json.Str(root, "taskId")));
                }
                catch { }
            }
            await proc.WaitForExitAsync();
            if (proc.ExitCode != 0 && !ct.IsCancellationRequested)
            {
                report(new DownloadProgress("error", 0, "", "下载进程异常退出", "", 0, 0));
            }
        }
        catch (OperationCanceledException)
        {
            Kill(proc);
            throw;
        }
    }

    /// <summary>读取当前站点规则链（内置 + 用户 vsp_rules.json）。</summary>
    public async Task<string> DescribeRulesAsync(string dir)
    {
        Directory.CreateDirectory(dir);
        var psi = CreatePsi();
        psi.ArgumentList.Add(ScriptPath);
        psi.ArgumentList.Add("rules");
        psi.ArgumentList.Add(dir);
        using var proc = new Process { StartInfo = psi };
        try
        {
            proc.Start();
            var text = await proc.StandardOutput.ReadToEndAsync();
            await proc.WaitForExitAsync();
            return text.Trim();
        }
        catch { return "[]"; }
    }

    /// <summary>通过控制文件指挥正在运行的下载进程：pause / resume / cancel / speed。</summary>
    public async Task ControlAsync(string action, string value, string dir)
    {
        Directory.CreateDirectory(dir);
        var psi = CreatePsi();
        psi.ArgumentList.Add(ScriptPath);
        psi.ArgumentList.Add(action);
        psi.ArgumentList.Add(value);
        psi.ArgumentList.Add(dir);
        using var proc = new Process { StartInfo = psi };
        try
        {
            proc.Start();
            await proc.WaitForExitAsync();
        }
        catch { /* 控制命令失败只影响一次操作，不影响主下载进程 */ }
    }

    private static void Kill(Process proc)
    {
        try { if (!proc.HasExited) proc.Kill(true); } catch { }
    }
}
