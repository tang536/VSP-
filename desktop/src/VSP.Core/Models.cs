using System.Text.Json;

namespace VSP.Core;

/// <summary>One downloadable entry returned by vsp.parse().</summary>
public sealed class MediaFormat
{
    public string FormatId { get; set; } = "";
    public string Ext { get; set; } = "";
    public string Container { get; set; } = "";
    public string Quality { get; set; } = "";
    public int? Width { get; set; }
    public int? Height { get; set; }
    public long Bitrate { get; set; }
    public string Size { get; set; } = "";
    public string Url { get; set; } = "";
    public string Protocol { get; set; } = "";
    public string Vcodec { get; set; } = "";
    public string Acodec { get; set; } = "";
    public bool NeedsMerge { get; set; }
    public string Thumbnail { get; set; } = "";
    public string AudioUrl { get; set; } = "";

    public bool IsImage => MediaUtil.IsImage(Url) ||
                           FormatId.StartsWith("thumb", StringComparison.OrdinalIgnoreCase);
    public bool IsHls => Url.Contains(".m3u8", StringComparison.OrdinalIgnoreCase);
    public string DisplayName => string.IsNullOrWhiteSpace(Quality) ? Ext.ToUpperInvariant() : Quality;
    public string Detail
    {
        get
        {
            var parts = new List<string>();
            if (!string.IsNullOrWhiteSpace(Container)) parts.Add(Container.ToUpperInvariant());
            if (Width > 0 && Height > 0) parts.Add($"{Width}×{Height}");
            if (Bitrate > 0) parts.Add($"{Bitrate / 1000} kb/s");
            if (!string.IsNullOrWhiteSpace(Size) && Size != "-") parts.Add(Size);
            if (NeedsMerge) parts.Add("含音轨");
            return string.Join(" · ", parts);
        }
    }
}

public sealed class ParseResult
{
    public string Title { get; set; } = "";
    public string Id { get; set; } = "";
    public string Uploader { get; set; } = "";
    public double Duration { get; set; }
    public string Thumbnail { get; set; } = "";
    public string Description { get; set; } = "";
    public string WebpageUrl { get; set; } = "";
    public string Source { get; set; } = "";
    public string Error { get; set; } = "";
    public List<MediaFormat> Videos { get; } = [];
    public List<MediaFormat> Audios { get; } = [];
    public List<MediaFormat> Images { get; } = [];
    public bool IsEmpty => Videos.Count == 0 && Audios.Count == 0 && Images.Count == 0;

    public static ParseResult Parse(string json)
    {
        var result = new ParseResult();
        if (string.IsNullOrWhiteSpace(json)) throw new Exception("解析引擎没有返回结果");
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        result.Title = Json.Str(root, "title");
        result.Id = Json.Str(root, "id");
        result.Uploader = Json.Str(root, "uploader");
        result.Duration = Json.Num(root, "duration");
        result.Thumbnail = Json.Str(root, "thumbnail");
        result.Description = Json.Str(root, "description");
        result.WebpageUrl = Json.Str(root, "webpageUrl");
        result.Source = Json.Str(root, "source");
        result.Error = Json.Str(root, "error");

        foreach (var name in new[] { "videos", "audios", "images" })
        {
            if (!root.TryGetProperty(name, out var arr) || arr.ValueKind != JsonValueKind.Array) continue;
            var target = name == "videos" ? result.Videos : name == "audios" ? result.Audios : result.Images;
            foreach (var item in arr.EnumerateArray())
            {
                target.Add(new MediaFormat
                {
                    FormatId = Json.Str(item, "formatId"),
                    Ext = Json.Str(item, "ext"),
                    Container = Json.Str(item, "container"),
                    Quality = Json.Str(item, "quality"),
                    Width = Json.NullableInt(item, "width"),
                    Height = Json.NullableInt(item, "height"),
                    Bitrate = (long)Json.Num(item, "bitrate"),
                    Size = Json.Str(item, "size"),
                    Url = Json.Str(item, "url"),
                    Protocol = Json.Str(item, "protocol"),
                    Vcodec = Json.Str(item, "vcodec"),
                    Acodec = Json.Str(item, "acodec"),
                    NeedsMerge = Json.Bool(item, "needsMerge"),
                    Thumbnail = Json.Str(item, "thumbnail"),
                    AudioUrl = Json.Str(item, "audioUrl"),
                });
            }
        }
        return result;
    }
}

internal static class Json
{
    public static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    public static double Num(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetDouble(out var n) ? n : 0;

    public static bool Bool(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && (v.ValueKind == JsonValueKind.True || v.ValueKind == JsonValueKind.False) && v.GetBoolean();

    public static int? NullableInt(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var n) ? n : null;
}
