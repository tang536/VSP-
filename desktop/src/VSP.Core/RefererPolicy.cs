namespace VSP.Core;

/// <summary>
/// Mirrors vsp.py::_SITE_REFERERS. Only User-Agent and Referer are sent to CDNs;
/// Origin / text/html Accept make several video CDNs return 403 or an HTML page.
/// </summary>
public static class RefererPolicy
{
    private static readonly (string Host, string Referer)[] Rules =
    [
        ("bilibili.com", "https://www.bilibili.com/"),
        ("b23.tv", "https://www.bilibili.com/"),
        ("bilivideo.com", "https://www.bilibili.com/"),
        ("hdslb.com", "https://www.bilibili.com/"),
        ("douyin.com", "https://www.douyin.com/"),
        ("iesdouyin.com", "https://www.douyin.com/"),
        ("douyinvod.com", "https://www.douyin.com/"),
        ("douyinstatic.com", "https://www.douyin.com/"),
        ("snssdk.com", "https://www.douyin.com/"),
        ("amemv.com", "https://www.douyin.com/"),
        ("bytecdn.com", "https://www.douyin.com/"),
        ("kuaishou.com", "https://www.kuaishou.com/"),
        ("kwaicdn.com", "https://www.kuaishou.com/"),
        ("gifshow.com", "https://www.kuaishou.com/"),
        ("xiaohongshu.com", "https://www.xiaohongshu.com/"),
        ("xhscdn.com", "https://www.xiaohongshu.com/"),
        ("weibo.com", "https://weibo.com/"),
        ("weibo.cn", "https://m.weibo.cn/"),
        ("sinaimg.cn", "https://weibo.com/"),
        ("ixigua.com", "https://www.ixigua.com/"),
        ("ixiguavideo.com", "https://www.ixigua.com/"),
        ("zhihu.com", "https://www.zhihu.com/"),
        ("zhimg.com", "https://www.zhihu.com/"),
        ("v.qq.com", "https://v.qq.com/"),
        ("gtimg.com", "https://v.qq.com/"),
        ("iqiyi.com", "https://www.iqiyi.com/"),
        ("youku.com", "https://www.youku.com/"),
        ("ykimg.com", "https://v.youku.com/"),
        ("haokan.baidu.com", "https://haokan.baidu.com/"),
        ("acfun.cn", "https://www.acfun.cn/"),
        ("aixifan.com", "https://www.acfun.cn/"),
        ("huya.com", "https://www.huya.com/"),
        ("douyu.com", "https://www.douyu.com/"),
    ];

    public const string UserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    public static string For(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return "";
        var host = uri.Host.ToLowerInvariant();
        foreach (var (key, referer) in Rules)
            if (host.EndsWith(key, StringComparison.OrdinalIgnoreCase)) return referer;
        return "";
    }

    public static void Apply(HttpRequestMessage request, string url)
    {
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        var referer = For(url);
        if (!string.IsNullOrEmpty(referer))
            request.Headers.TryAddWithoutValidation("Referer", referer);
    }
}
