using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace VSP.Core;

/// <summary>
/// 基于 DXGI Desktop Duplication 的屏幕采集。
///
/// 原来的 GDI CopyFromScreen 在部分机器上一次要 30~80ms，只够 12~20fps；
/// DXGI 直接从 GPU 取帧，复制到 staging 纹理后读回内存通常只要 1~4ms，
/// 剩下的时间留给缩放和 JPEG 编码，可以稳定跑满 60fps。
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class DxgiScreenCapture : IDisposable
{
    private readonly object _gate = new();
    private ID3D11Device? _device;
    private ID3D11DeviceContext? _context;
    private IDXGIOutputDuplication? _duplication;
    private ID3D11Texture2D? _staging;
    private Bitmap? _full;
    private Bitmap? _scaled;
    private Graphics? _scaledGraphics;
    private MemoryStream? _jpegBuffer;
    private ImageCodecInfo? _jpegCodec;
    private EncoderParameters? _jpegParams;
    private int _screenW;
    private int _screenH;
    private long _lastFrameStamp;
    private int _outputLeft;
    private int _outputTop;
    private bool _failed;

    public bool IsAvailable => !_failed && _duplication is not null;

    /// <summary>创建失败（显卡/驱动不支持）时返回 null，调用方退回 GDI 截屏。</summary>
    public static DxgiScreenCapture? TryCreate()
    {
        var capture = new DxgiScreenCapture();
        try
        {
            capture.Initialize();
            return capture.IsAvailable ? capture : null;
        }
        catch
        {
            capture.Dispose();
            return null;
        }
    }

    private void Initialize()
    {
        using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
        IDXGIAdapter1? chosenAdapter = null;
        IDXGIOutput? chosenOutput = null;

        // 选面积最大的输出（通常是主屏）；混合显卡笔记本上要选真正连着显示器的适配器。
        long bestArea = 0;
        for (uint a = 0; ; a++)
        {
            if (factory.EnumAdapters1(a, out var adapter).Failure) break;
            var keepAdapter = false;
            for (uint o = 0; ; o++)
            {
                if (adapter.EnumOutputs(o, out var output).Failure) break;
                var desc = output.Description;
                var w = desc.DesktopCoordinates.Right - desc.DesktopCoordinates.Left;
                var h = desc.DesktopCoordinates.Bottom - desc.DesktopCoordinates.Top;
                var area = (long)w * h;
                if (w > 0 && h > 0 && area > bestArea)
                {
                    // 同一块显卡的第二个输出比第一个大时，不能把正在用的适配器释放掉。
                    if (!ReferenceEquals(chosenAdapter, adapter)) chosenAdapter?.Dispose();
                    chosenOutput?.Dispose();
                    bestArea = area;
                    chosenAdapter = adapter;
                    chosenOutput = output;
                    keepAdapter = true;
                }
                else
                {
                    output.Dispose();
                }
            }
            if (!keepAdapter) adapter.Dispose();
        }

        if (chosenAdapter is null || chosenOutput is null)
            throw new InvalidOperationException("no DXGI output");

        var desc2 = chosenOutput.Description;
        _outputLeft = desc2.DesktopCoordinates.Left;
        _outputTop = desc2.DesktopCoordinates.Top;

        var result = D3D11.D3D11CreateDevice(
            chosenAdapter, DriverType.Unknown, DeviceCreationFlags.BgraSupport,
            new[] { FeatureLevel.Level_11_0 }, out var device);
        chosenAdapter.Dispose();
        if (result.Failure || device is null)
        {
            chosenOutput.Dispose();
            throw new InvalidOperationException("D3D11 device creation failed");
        }
        _device = device;
        _context = device.ImmediateContext;

        using var output1 = chosenOutput.QueryInterface<IDXGIOutput1>();
        chosenOutput.Dispose();
        _duplication = output1.DuplicateOutput(device);

        _jpegCodec = ImageCodecInfo.GetImageEncoders().First(c => c.MimeType == "image/jpeg");
        _jpegParams = new EncoderParameters(1);
        _jpegParams.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, 55L);
        _jpegBuffer = new MemoryStream(256 * 1024);
    }

    /// <summary>
    /// 等待并抓取一帧。没有新画面时返回 null（不重复编码静止画面）。
    /// 返回的字节是 JPEG，可直接推给主控端。
    /// </summary>
    public byte[]? TryCaptureJpeg(int maxWidth, int maxFps)
    {
        lock (_gate)
        {
            if (_failed || _duplication is null || _context is null || _device is null) return null;

            while (true)
            {
                IDXGIResource? resource = null;
                var acquired = false;
                try
                {
                    var result = _duplication.AcquireNextFrame(15, out _, out resource);
                    if (result == Vortice.DXGI.ResultCode.WaitTimeout) return null;
                    if (result.Failure)
                    {
                        _failed = true;
                        return null;
                    }
                    acquired = true;

                    // 高刷屏（120/144/165Hz）上按目标帧率节流：保持 60fps 以上，
                    // 又不会每 6ms 编码一次把 CPU 占满。低刷新率屏幕则每帧都编码。
                    var now = Stopwatch.GetTimestamp();
                    var minInterval = Stopwatch.Frequency / Math.Max(1, maxFps);
                    if (_lastFrameStamp != 0 && now - _lastFrameStamp < minInterval)
                        continue;

                    using var texture = resource.QueryInterface<ID3D11Texture2D>();
                    var desc = texture.Description;
                    var w = (int)desc.Width;
                    var h = (int)desc.Height;
                    if (w <= 0 || h <= 0) return null;

                    if (_staging is null || _screenW != w || _screenH != h)
                    {
                        _staging?.Dispose();
                        var stagingDesc = desc;
                        stagingDesc.Usage = ResourceUsage.Staging;
                        stagingDesc.BindFlags = BindFlags.None;
                        stagingDesc.CPUAccessFlags = CpuAccessFlags.Read;
                        stagingDesc.MiscFlags = ResourceOptionFlags.None;
                        stagingDesc.ArraySize = 1;
                        stagingDesc.MipLevels = 1;
                        _staging = _device.CreateTexture2D(stagingDesc);
                        _screenW = w;
                        _screenH = h;
                        _full?.Dispose();
                        _full = new Bitmap(w, h, PixelFormat.Format32bppRgb);
                        _scaledGraphics?.Dispose();
                        _scaled?.Dispose();
                        _scaled = null;
                        _scaledGraphics = null;
                    }

                    _context.CopyResource(_staging!, texture);
                    var mapped = _context.Map(_staging!, 0, MapMode.Read, Vortice.Direct3D11.MapFlags.None);
                    try
                    {
                        // 映射内存通常是写合并/不可缓存，GDI+ 直接缩放会慢 5ms 以上；
                        // 先整屏拷进普通 Bitmap，再从缓存内存缩放编码，整体更快。
                        CopyMappedToBitmap(mapped.DataPointer, (int)mapped.RowPitch, _full!);
                        var jpeg = EncodeJpeg(_full!, maxWidth);
                        if (jpeg is not null) _lastFrameStamp = now;
                        return jpeg;
                    }
                    finally
                    {
                        _context.Unmap(_staging!, 0);
                    }
                }
                catch
                {
                    _failed = true;
                    return null;
                }
                finally
                {
                    resource?.Dispose();
                    if (acquired)
                    {
                        try { _duplication.ReleaseFrame(); } catch { }
                    }
                }
            }
        }
    }

    private static unsafe void CopyMappedToBitmap(IntPtr source, int sourceStride, Bitmap target)
    {
        var data = target.LockBits(new Rectangle(0, 0, target.Width, target.Height),
            ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
        try
        {
            var dstStride = data.Stride;
            var rowBytes = target.Width * 4;
            if (sourceStride == dstStride)
            {
                Buffer.MemoryCopy((void*)source, (void*)data.Scan0,
                    (long)dstStride * target.Height, (long)sourceStride * target.Height);
            }
            else
            {
                for (var y = 0; y < target.Height; y++)
                {
                    Buffer.MemoryCopy(
                        (void*)IntPtr.Add(source, y * sourceStride),
                        (void*)IntPtr.Add(data.Scan0, y * dstStride),
                        rowBytes, rowBytes);
                }
            }
        }
        finally
        {
            target.UnlockBits(data);
        }
    }

    private byte[]? EncodeJpeg(Bitmap full, int maxWidth)
    {
        if (_jpegCodec is null || _jpegParams is null || _jpegBuffer is null) return null;
        var scale = Math.Min(1.0, (double)maxWidth / full.Width);
        var outW = Math.Max(2, (int)Math.Round(full.Width * scale));
        var outH = Math.Max(2, (int)Math.Round(full.Height * scale));

        if (_scaled is null || _scaled.Width != outW || _scaled.Height != outH)
        {
            _scaledGraphics?.Dispose();
            _scaled?.Dispose();
            _scaled = new Bitmap(outW, outH, PixelFormat.Format24bppRgb);
            _scaledGraphics = Graphics.FromImage(_scaled);
            // 远控画面在手机上观看，最近邻缩放比双线性快一倍，60fps 比轻微锯齿更重要。
            _scaledGraphics.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.NearestNeighbor;
            _scaledGraphics.PixelOffsetMode = PixelOffsetMode.HighSpeed;
            _scaledGraphics.CompositingMode = CompositingMode.SourceCopy;
        }

        _scaledGraphics!.DrawImage(full, new Rectangle(0, 0, outW, outH));
        DrawCursor(_scaledGraphics, scale);

        _jpegBuffer.SetLength(0);
        _scaled.Save(_jpegBuffer, _jpegCodec, _jpegParams);
        return _jpegBuffer.ToArray();
    }

    /// <summary>DXGI 桌面复制不包含鼠标指针，这里按真实光标位置补画到缩放后的画面上。</summary>
    private void DrawCursor(Graphics g, double scale)
    {
        try
        {
            var ci = new CURSORINFO { cbSize = Marshal.SizeOf<CURSORINFO>() };
            if (!GetCursorInfo(ref ci) || (ci.flags & CURSOR_SHOWING) == 0) return;
            if (ci.hCursor == IntPtr.Zero) return;

            // GetSystemMetrics 与 DXGI 输出尺寸在 DPI 感知进程里一致；不一致时按比例校正。
            var sysW = GetSystemMetrics(0);
            var sysH = GetSystemMetrics(1);
            var cx = (ci.ptScreenPos.X - _outputLeft) * (sysW > 0 ? (double)_screenW / sysW : 1);
            var cy = (ci.ptScreenPos.Y - _outputTop) * (sysH > 0 ? (double)_screenH / sysH : 1);
            cx *= scale;
            cy *= scale;

            var icon = new ICONINFO();
            var hasIcon = GetIconInfo(ci.hCursor, out icon);
            var hotspotX = hasIcon ? icon.xHotspot : 0;
            var hotspotY = hasIcon ? icon.yHotspot : 0;
            if (hasIcon)
            {
                if (icon.hbmMask != IntPtr.Zero) DeleteObject(icon.hbmMask);
                if (icon.hbmColor != IntPtr.Zero) DeleteObject(icon.hbmColor);
            }

            var size = (int)Math.Max(8, Math.Round(32 * scale));
            var hdc = g.GetHdc();
            try
            {
                DrawIconEx(hdc, (int)Math.Round(cx - hotspotX * scale), (int)Math.Round(cy - hotspotY * scale),
                    ci.hCursor, size, size, 0, IntPtr.Zero, DI_NORMAL);
            }
            finally
            {
                g.ReleaseHdc(hdc);
            }
        }
        catch { }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _failed = true;
            _scaledGraphics?.Dispose();
            _scaled?.Dispose();
            _full?.Dispose();
            _jpegBuffer?.Dispose();
            _jpegParams?.Dispose();
            _staging?.Dispose();
            _duplication?.Dispose();
            _context?.Dispose();
            _device?.Dispose();
            _scaledGraphics = null;
            _scaled = null;
            _full = null;
            _jpegBuffer = null;
            _jpegParams = null;
            _staging = null;
            _duplication = null;
            _context = null;
            _device = null;
        }
    }

    private const int CURSOR_SHOWING = 0x0001;
    private const int DI_NORMAL = 0x0003;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CURSORINFO
    {
        public int cbSize;
        public int flags;
        public IntPtr hCursor;
        public POINT ptScreenPos;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ICONINFO
    {
        public int fIcon;
        public int xHotspot;
        public int yHotspot;
        public IntPtr hbmMask;
        public IntPtr hbmColor;
    }

    [DllImport("user32.dll")]
    private static extern bool GetCursorInfo(ref CURSORINFO pci);

    [DllImport("user32.dll")]
    private static extern bool GetIconInfo(IntPtr hIcon, out ICONINFO piconinfo);

    [DllImport("user32.dll")]
    private static extern bool DrawIconEx(IntPtr hdc, int xLeft, int yTop, IntPtr hIcon,
        int cxWidth, int cyWidth, int istepIfAniCur, IntPtr hbrFlickerFreeDraw, int diFlags);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);
}
