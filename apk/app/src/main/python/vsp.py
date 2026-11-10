import json, os, threading, tempfile, shutil, re, time
import urllib.request, urllib.parse, urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn
import yt_dlp

# 关键：模块导入阶段绝不写磁盘。
# Android 11+ 分区存储下 /sdcard/Download 不是应用可直接写入的路径，
# 原来的 os.makedirs(DOWNLOAD_DIR) 在导入时就抛 PermissionError，
# 导致 Chaquopy 初始化失败、App 一启动就报错。
_private_dir = None
_cached_dir = None

VIDEO_AUDIO_EXTS = ('.mp4', '.webm', '.mkv', '.mov', '.m4v', '.avi', '.3gp', '.flv', '.ts',
                    '.mp3', '.m4a', '.aac', '.opus', '.flac', '.wav', '.ogg', '.m4b', '.aiff')
IMAGE_EXTS = ('.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp')
SKIP_EXTS = ('.json', '.part', '.ytdl', '.srt', '.vtt', '.ass', '.ssa', '.lrc',
             '.txt', '.description', '.nfo', '.tmp')

# B 站/抖音/Douyin 这些站在移动端 UA 下会走精简接口，返回的 formats 直接是空数组，
# 所以解析与下载统一用桌面 UA，再按站点补 Referer。
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
MOBILE_UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
             "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
HTML_HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.6",
}

# 站点 -> Referer：这些站在校验 Referer 后才会返回 play_addr / 直链。
# 直链通常落在 CDN 域名上（bilivideo / douyinvod / kwaicdn …），
# 光靠主域名匹配不到，所以 CDN 域名也要单独列出来，否则下载会 403。
_SITE_REFERERS = (
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
)


def _referer_for(url):
    try:
        host = (urllib.parse.urlparse(url).hostname or "").lower()
    except Exception:
        return ""
    for key, ref in _SITE_REFERERS:
        if host.endswith(key):
            return ref
    return ""


def _headers_for(url, extra=None):
    headers = dict(HTML_HEADERS)
    ref = _referer_for(url)
    if ref:
        # 只带 Referer：有些防盗链 CDN 见到 Origin 会直接 403
        headers["Referer"] = ref
    if extra:
        headers.update(extra)
    return headers


def _play_headers(url):
    """给播放器 / 直链用的精简请求头：只要 UA 与站点 Referer。

    不能把 HTML_HEADERS 的 Accept 一起塞进来：Accept: text/html 会让某些
    CDN 或接口按「要网页」处理，返回 HTML 而不是媒体流。
    """
    headers = {"User-Agent": UA}
    ref = _referer_for(url)
    if ref:
        headers["Referer"] = ref
    return headers


def _ydl_opts(url, **over):
    """统一的 yt-dlp 选项：桌面 UA + 站点 Referer + 不写缓存目录。"""
    opts = {
        'quiet': True, 'no_warnings': True, 'noplaylist': True, 'skip_download': True,
        'nocheckcertificate': True, 'noprogress': True, 'concurrent_fragment_downloads': 1,
        'socket_timeout': 25, 'retries': 2, 'extractor_retries': 1,
        # Android 上默认缓存目录可能不可写，关掉它避免解析直接被 IO 异常带崩
        'cachedir': False,
        'http_headers': _play_headers(url),
    }
    opts.update(over)
    return opts


# 短链（b23.tv / v.douyin.com / v.kuaishou.com）要先跟随跳转拿到真实地址
_SHORT_HOSTS = ("b23.tv", "v.douyin.com", "v.kuaishou.com", "xhslink.com", "t.cn")


def _resolve_short(url):
    host = ""
    try:
        host = (urllib.parse.urlparse(url).hostname or "").lower()
    except Exception:
        return url
    if not any(host.endswith(h) for h in _SHORT_HOSTS):
        return url
    for method in ("HEAD", "GET"):
        try:
            req = urllib.request.Request(url, method=method, headers=_headers_for(url, {
                "Accept": "*/*", "User-Agent": MOBILE_UA}))
            with urllib.request.urlopen(req, timeout=20) as r:
                real = r.geturl()
            if real and real.startswith("http") and real != url:
                return real
        except Exception:
            continue
    return url


# 直链 formatId 前缀：Java 层只回传 format_id，用它把真实地址带过去，避免额外状态
DIRECT_PREFIX = "url:"


def _writable(path):
    try:
        os.makedirs(path, exist_ok=True)
        probe = os.path.join(path, ".vsp_write_test")
        with open(probe, "w", encoding="utf-8") as f:
            f.write("ok")
        os.remove(probe)
        return path
    except Exception:
        return None


def set_private_dir(path):
    """由 Java 层传入应用私有目录（不需要任何存储权限，必定可写）。"""
    global _private_dir, _cached_dir
    _private_dir = path
    _cached_dir = None
    return get_download_dir()


def get_download_dir():
    """优先宿主指定的私有目录，其次共享 Download，最后临时目录；任何一步失败都不影响导入。"""
    global _cached_dir
    if _cached_dir:
        return _cached_dir
    for candidate in (_private_dir, "/sdcard/Download/VSP解析",
                      os.path.join(tempfile.gettempdir(), "VSP解析")):
        if not candidate:
            continue
        ok = _writable(candidate)
        if ok:
            _cached_dir = ok
            return ok
    _cached_dir = tempfile.gettempdir()
    return _cached_dir


def pick_media(paths):
    """从 yt-dlp 产物中挑出真正的媒体文件。

    下载目录里常混有 .info.json / 字幕 / .part / 缩略图，直接取 files[0]
    会把元数据文件当成视频保存，导致任务 filename 错误、app 内「打开」不可用。
    """
    av, images = [], []
    for p in paths:
        if not os.path.isfile(p):
            continue
        low = os.path.basename(p).lower()
        ext = os.path.splitext(low)[1]
        if low.endswith(('.part', '.ytdl')) or ext in SKIP_EXTS:
            continue
        if ext in VIDEO_AUDIO_EXTS:
            av.append(p)
        elif ext in IMAGE_EXTS:
            images.append(p)
    pool = av or images
    return max(pool, key=os.path.getsize) if pool else None


def _safe_name(title, ext):
    name = re.sub(r'[\\/:*?"<>|\r\n\t]', '_', title or '').strip()[:80]
    return (name or 'cover') + ext


def human_size(n):
    if not n or n <= 0:
        return "-"
    units = ["B", "KB", "MB", "GB"]
    i = 0
    while n >= 1024 and i < len(units) - 1:
        n /= 1024
        i += 1
    return f"{n:.1f} {units[i]}"


# ---------------------------------------------------------------- 解析
#
# 有些站点（自定义 DPlayer / 文章页）yt-dlp 没有对应 extractor，
# 直接抛 Unsupported URL。这里补一层通用兜底：抓 HTML，反转义后
# 从 data-config、内联脚本、<video>/<source>、og:video、JSON-LD 里
# 找 m3u8 / mp4 直链，并跟随一层 iframe（播放器常在 iframe 里）。

_MEDIA_RE = re.compile(
    r'https?://[^\s"\'\\<>\u4e00-\u9fff]+?\.(?:m3u8|mp4|m4v|webm|flv|mov|mkv)'
    r'(?:\?[^\s"\'\\<>\u4e00-\u9fff]*)?', re.I)
_REL_RE = re.compile(
    r'["\'](/(?!/)[^"\'\s<>]+?\.(?:m3u8|mp4|m4v|webm|flv))(\?[^"\']*)?["\']', re.I)
_IFRAME_RE = re.compile(r'<iframe[^>]+src=["\']([^"\']+)["\']', re.I)
_TITLE_RE = re.compile(r'<title[^>]*>(.*?)</title>', re.I | re.S)
_OG_RE = lambda prop: re.compile(
    r'<meta[^>]+(?:property|name)=["\']%s["\'][^>]+content=["\']([^"\']+)["\']' % prop, re.I)
_JSONLD_RE = re.compile(r'"contentUrl"\s*:\s*"([^"]+)"', re.I)


def _fetch(url, referer=""):
    headers = _headers_for(url, {"Referer": referer} if referer else None)
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        raw = r.read(4 * 1024 * 1024)
        charset = r.headers.get_content_charset()
    if not charset:
        m = re.search(br'charset=["\']?([\w-]+)', raw[:4096])
        charset = m.group(1).decode("ascii", "ignore") if m else "utf-8"
    return raw.decode(charset or "utf-8", "ignore")


def _unescape(text):
    """DPlayer 一类播放器把配置塞在 data-config 里，斜杠和 amp 都是转义的。"""
    return (text.replace('\\/', '/')
                .replace('\\u002F', '/').replace('\\u002f', '/')
                .replace('\\u0026', '&').replace('&amp;', '&'))


def _scan_media(text, base=""):
    found = []
    t = _unescape(text)
    for m in _MEDIA_RE.finditer(t):
        u = m.group(0)
        if u not in found:
            found.append(u)
    if base:
        for m in _REL_RE.finditer(t):
            # 相对地址要带上查询串，签名参数丢了会 403
            u = urllib.parse.urljoin(base, m.group(1) + (m.group(2) or ""))
            if u not in found:
                found.append(u)
    return found


def _ext_of(url):
    path = urllib.parse.urlparse(url).path
    return (os.path.splitext(path)[1] or ".mp4").lower()


def _direct_format(url, index):
    ext = _ext_of(url).lstrip(".")
    is_hls = ext == "m3u8"
    return {
        "formatId": DIRECT_PREFIX + url,
        "ext": "mp4" if is_hls else ext,
        "container": "mp4" if is_hls else ext,
        "quality": "HLS 在线流" if is_hls else "页面直链 " + ext.upper(),
        "width": None,
        "height": None,
        "bitrate": 0,
        "size": "-",
        "url": url,
        "vcodec": "h264",
        "acodec": "aac",
        "needsMerge": False,
    }


def _generic_parse(url):
    """yt-dlp 不认的页面：直接从 HTML 里挖媒体直链。"""
    try:
        html = _fetch(url)
    except Exception as e:
        raise Exception(f"页面无法访问（{str(e)[:80]}）")

    title = ""
    m = _OG_RE("og:title").search(html)
    if m:
        title = m.group(1).strip()
    if not title:
        m = _TITLE_RE.search(html)
        if m:
            title = re.sub(r'\s+', ' ', m.group(1)).strip()

    thumb = ""
    for prop in ("og:image", "twitter:image"):
        m = _OG_RE(prop).search(html)
        if m:
            thumb = _unescape(m.group(1)).strip()
            break

    media = _scan_media(html, url)
    for m in _JSONLD_RE.finditer(_unescape(html)):
        u = m.group(1)
        if u.startswith("http") and u not in media:
            media.append(u)

    # 播放器常被塞进 iframe：跟随一层，把里面的直链也捞出来
    iframes = []
    for m in _IFRAME_RE.finditer(html):
        src = _unescape(m.group(1)).strip()
        if src.startswith("//"):
            src = "https:" + src
        elif src.startswith("/"):
            src = urllib.parse.urljoin(url, src)
        if src.startswith("http") and src not in iframes:
            iframes.append(src)
    for frame in iframes[:2]:
        try:
            inner = _fetch(frame, referer=url)
        except Exception:
            continue
        for u in _scan_media(inner, frame):
            if u not in media:
                media.append(u)
        for u in _scan_media(inner, url):
            if u not in media:
                media.append(u)

    # 过滤掉明显不是视频的（统计图、1px 占位）
    cleaned = []
    for u in media:
        low = u.lower()
        if any(k in low for k in (".gif", "/1x1", "pixel.", "spacer.", "blank.")):
            continue
        if u not in cleaned:
            cleaned.append(u)

    videos = [_direct_format(u, i) for i, u in enumerate(cleaned[:8])]
    host = urllib.parse.urlparse(url).hostname or ""
    return {
        "title": title or host,
        "id": "",
        "uploader": host,
        "duration": 0,
        "thumbnail": thumb,
        "description": "",
        "webpageUrl": url,
        "videos": videos,
        "audios": [],
        "images": ([{"formatId": "thumb", "ext": "jpg", "container": "jpg",
                     "quality": "封面", "width": None, "height": None, "bitrate": 0,
                     "size": "-", "url": thumb, "vcodec": None, "acodec": None,
                     "needsMerge": False}] if thumb else []),
        "source": "generic",
    }


# ---------------------------------------------------------------- 抖音
#
# 抖音的 web 接口要 msToken/x-bogus 签名，yt-dlp 在无 cookie 的干净环境里
# 基本拿不到 play_addr。分享页（iesdouyin /share/video/<id>/）仍然会把整条
# 作品的 JSON 塞在 window._ROUTER_DATA 里，直接挖出来最稳。

_DY_ID_RE = re.compile(r'/(?:video|note|share/video|share/note)/(\d{15,25})')


def _js_json(html, var_name):
    """从 HTML 里截出 window.<var_name> = {...}; 这段 JSON（括号计数，容忍字符串里的括号）。"""
    m = re.search(r'window\.' + re.escape(var_name) + r'\s*=\s*', html)
    if not m:
        return None
    i = html.find('{', m.end())
    if i < 0:
        return None
    depth, in_str, esc = 0, False, False
    for j in range(i, len(html)):
        ch = html[j]
        if in_str:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(html[i:j + 1])
                except Exception:
                    return None
    return None


def _walk_find(obj, want):
    """在任意深度的 JSON 里找第一个满足 want(dict) 的节点。"""
    stack = [obj]
    while stack:
        cur = stack.pop()
        if isinstance(cur, dict):
            if want(cur):
                return cur
            stack.extend(cur.values())
        elif isinstance(cur, list):
            stack.extend(cur)
    return None


def _url_list(node):
    if not isinstance(node, dict):
        return []
    lst = node.get("url_list") or node.get("urlList") or []
    return [u for u in lst if isinstance(u, str) and u.startswith("http")]


def _douyin_parse(url):
    real = _resolve_short(url)
    m = _DY_ID_RE.search(real)
    if not m:
        raise Exception("无法从链接里识别抖音作品 ID，请用视频分享链接")
    item_id = m.group(1)
    html = ""
    for page in ("https://www.iesdouyin.com/share/video/%s/" % item_id,
                 "https://www.douyin.com/video/%s" % item_id):
        try:
            html = _fetch(page, referer="https://www.douyin.com/")
            if html:
                break
        except Exception:
            continue
    if not html:
        raise Exception("抖音页面打不开，可能需要登录或该视频已删除")

    data = _js_json(html, "_ROUTER_DATA") or _js_json(html, "RENDER_DATA")
    item = None
    if data:
        item = _walk_find(data, lambda d: ("video" in d and isinstance(d.get("video"), dict))
                          or "play_addr" in d or "playAddr" in d)
    play, cover, music, desc, duration, images = [], [], [], "", 0, []

    if item:
        desc = item.get("desc") or item.get("title") or ""
        duration = int((item.get("video") or {}).get("duration") or item.get("duration") or 0)
        vid = item.get("video") or {}
        play = _url_list(vid.get("play_addr") or vid.get("playAddr"))
        cover = _url_list(vid.get("cover") or vid.get("origin_cover") or vid.get("dynamic_cover"))
        music = _url_list((item.get("music") or {}).get("play_url"))
        for img in (item.get("images") or []):
            images.extend(_url_list(img))

    if not play:
        # 页面里直接带的视频地址（部分分享页是平铺的）
        for u in _scan_media(html):
            if "douyin" in u or "snssdk" in u or "byteimg" in u or ".mp4" in u:
                play.append(u)
    if not play:
        raise Exception("没有从抖音页面里找到播放地址（可能需要登录或用手机分享的短链）")

    # playwm 是带水印版本，换成 play 即无水印
    clean = []
    for u in play:
        u2 = u.replace("/playwm/", "/play/").replace("playwm", "play")
        if u2 not in clean:
            clean.append(u2)

    if not desc:
        m2 = _OG_RE("og:title").search(html)
        desc = (m2.group(1).strip() if m2 else "抖音视频")
    thumb = cover[0] if cover else ""
    videos = [_direct_format(u, i) for i, u in enumerate(clean[:6])]
    for v in videos:
        v["quality"] = "抖音原画" if v is videos[0] else "抖音备用线路"
    audios = []
    if music:
        f = _direct_format(music[0], 0)
        f["quality"] = "原声"
        audios.append(f)
    imgs = []
    for u in images[:12]:
        imgs.append({"formatId": "thumb", "ext": "jpg", "container": "jpg",
                     "quality": "图集", "width": None, "height": None, "bitrate": 0,
                     "size": "-", "url": u, "vcodec": None, "acodec": None,
                     "needsMerge": False})
    if thumb:
        imgs.insert(0, {"formatId": "thumb", "ext": "jpg", "container": "jpg",
                        "quality": "封面", "width": None, "height": None, "bitrate": 0,
                        "size": "-", "url": thumb, "vcodec": None, "acodec": None,
                        "needsMerge": False})
    return {
        "title": desc, "id": item_id, "uploader": "抖音",
        "duration": duration, "thumbnail": thumb, "description": "",
        "webpageUrl": real, "videos": videos, "audios": audios, "images": imgs,
        "source": "douyin",
    }


def _host_is(url, *keys):
    try:
        host = (urllib.parse.urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return any(k in host for k in keys)


# ---------------------------------------------------------------- B 站
# yt-dlp 走的是 DASH，画面流和音轨是分开的两个文件：直接播画面流就只有画面没有声音。
# B 站的 playurl 接口还有一个 fnval=1 的「合流」模式，直接给一个带音轨的 mp4/flv，
# 这里优先用它，保证点开就有声音；只有合流拿不到时才退回 yt-dlp。

_BILI_Q = {6: "240P", 16: "360P", 32: "480P", 64: "720P", 74: "720P60",
           80: "1080P", 112: "1080P+", 116: "1080P60", 120: "4K", 125: "HDR", 126: "杜比"}


def _bili_ids(url):
    """从各种 B 站链接里认出 BV 号或 av 号"""
    m = re.search(r"(BV[0-9A-Za-z]{10})", url or "")
    if m:
        return m.group(1), None
    m = re.search(r"/av(\d+)", url or "", re.I)
    if m:
        return None, int(m.group(1))
    m = re.search(r"[?&]aid=(\d+)", url or "")
    if m:
        return None, int(m.group(1))
    return None, None


def _bili_api(path, **params):
    q = urllib.parse.urlencode(params)
    api = "https://api.bilibili.com" + path + ("?" + q if q else "")
    req = urllib.request.Request(api, headers={
        "User-Agent": UA,
        "Referer": "https://www.bilibili.com/",
        "Accept": "application/json, text/plain, */*",
    })
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def _bilibili_parse(url):
    bvid, aid = _bili_ids(url)
    real = url
    if not bvid and not aid:
        # b23.tv 短链先跟随跳转
        real = _resolve_short(url) or url
        bvid, aid = _bili_ids(real)
    if not bvid and not aid:
        raise Exception("没从链接里认出 BV 号")

    view = _bili_api("/x/web-interface/view",
                     **({"bvid": bvid} if bvid else {"aid": aid}))
    if view.get("code") not in (0, None):
        raise Exception("B 站接口返回：" + str(view.get("message") or view.get("code")))
    vd = view.get("data") or {}
    bvid = vd.get("bvid") or bvid
    title = vd.get("title") or "B 站视频"
    pages = vd.get("pages") or [{}]
    cid = pages[0].get("cid")
    duration = vd.get("duration") or pages[0].get("duration") or 0
    owner = (vd.get("owner") or {}).get("name") or "B 站"
    pic = vd.get("pic") or ""
    if not cid:
        raise Exception("没取到视频 cid")

    # fnval=1 = 合流（带音轨）；qn 尽量往高要，没登录会被自动降级
    play = _bili_api("/x/player/playurl", bvid=bvid, cid=cid,
                     qn=80, fnval=1, fnver=0, fourk=1)
    pd = play.get("data") or {}
    durl = pd.get("durl") or []
    qname = _BILI_Q.get(pd.get("quality") or 0, "视频")
    videos = []
    for i, seg in enumerate(durl):
        u = seg.get("url") or ""
        if not u:
            continue
        f = _direct_format(u, i)
        f["quality"] = qname + " 合流（含音轨）"
        if len(durl) > 1:
            f["quality"] += " · 第 %d/%d 段" % (i + 1, len(durl))
        videos.append(f)
        for j, bu in enumerate(seg.get("backup_url") or []):
            if not bu:
                continue
            b = _direct_format(bu, 100 + i * 10 + j)
            b["quality"] = qname + " 合流 · 备用线路 " + str(j + 1)
            videos.append(b)

    if not videos:
        raise Exception("B 站没返回合流地址（该视频可能需要登录或大会员）")

    images = []
    if pic:
        images.append({"formatId": "thumb", "ext": "jpg", "container": "jpg",
                       "quality": "封面", "width": None, "height": None, "bitrate": 0,
                       "size": "-", "url": pic, "vcodec": None, "acodec": None,
                       "needsMerge": False})
    return {
        "title": title, "id": bvid, "uploader": owner,
        "duration": duration, "thumbnail": pic, "description": vd.get("desc") or "",
        "webpageUrl": real, "videos": videos, "audios": [], "images": images,
        "source": "bilibili",
    }


def parse(url):
    """解析入口：B 站 / 抖音专用 -> yt-dlp -> HTML 通用兜底。"""
    url = (url or "").strip()
    if not url.lower().startswith(("http://", "https://")):
        raise Exception("请输入完整的 http(s) 链接")

    # B 站先走合流接口：yt-dlp 的 DASH 流是画面和音轨分离的，播出来没有声音
    bili_error = ""
    if _host_is(url, "bilibili.com", "b23.tv"):
        try:
            return json.dumps(_bilibili_parse(url), ensure_ascii=False)
        except Exception as e:
            bili_error = str(e)

    if _host_is(url, "douyin.com", "iesdouyin.com"):
        try:
            return json.dumps(_douyin_parse(url), ensure_ascii=False)
        except Exception as e:
            # 抖音专用通道失败后仍尝试 yt-dlp，最后的错误信息取更具体的那个
            dy_error = str(e)
    else:
        dy_error = ""

    real = _resolve_short(url)
    try:
        with yt_dlp.YoutubeDL(_ydl_opts(real)) as ydl:
            info = ydl.extract_info(real, download=False)
        if info:
            entries = info.get("entries")
            if entries:
                info = next((e for e in entries if e), info)
            if info.get("formats") or info.get("url"):
                data = normalize(info)
                if data["videos"] or data["audios"] or data["images"]:
                    return json.dumps(data, ensure_ascii=False)
    except Exception:
        pass

    data = _generic_parse(real)
    if not data["videos"] and not data["images"]:
        if bili_error:
            raise Exception("B 站解析失败：" + bili_error)
        if dy_error:
            raise Exception(dy_error)
        raise Exception("该页面找不到可下载的音视频直链（可能需要登录或由脚本动态加载）")
    return json.dumps(data, ensure_ascii=False)


def normalize(info):
    videos, audios, images = [], [], []
    for f in info.get("formats", []):
        vcodec = f.get("vcodec", "none")
        acodec = f.get("acodec", "none")
        has_v = bool(vcodec and vcodec != "none")
        has_a = bool(acodec and acodec != "none")
        item = {
            "formatId": f.get("format_id", ""),
            "ext": f.get("ext", "mp4"),
            "container": f.get("ext", "mp4"),
            "quality": f.get("format", "") or f.get("format_id", ""),
            "width": f.get("width"),
            "height": f.get("height"),
            "bitrate": f.get("tbr") or 0,
            "size": human_size(f.get("filesize") or f.get("filesize_approx")),
            "url": f.get("url", ""),
            "vcodec": vcodec if has_v else None,
            "acodec": acodec if has_a else None,
            # 只有画面没有声音的流，下载后需要本地合并一条音轨
            "needsMerge": bool(has_v and not has_a),
        }
        if has_v:
            videos.append(item)
        elif has_a:
            audios.append(item)
    if info.get("thumbnail"):
        images.append({"formatId": "thumb", "ext": "jpg", "container": "jpg",
                       "quality": "封面", "width": None, "height": None, "bitrate": 0,
                       "size": "-", "url": info["thumbnail"], "vcodec": None, "acodec": None,
                       "needsMerge": False})
    videos.sort(key=lambda x: x.get("height") or 0, reverse=True)
    audios.sort(key=lambda x: x.get("bitrate") or 0, reverse=True)
    return {
        "title": info.get("title", ""),
        "id": info.get("id", ""),
        "uploader": info.get("uploader", ""),
        "duration": info.get("duration") or 0,
        "thumbnail": info.get("thumbnail", ""),
        "description": info.get("description", ""),
        "webpageUrl": info.get("webpage_url", ""),
        "videos": videos,
        "audios": audios,
        "images": images,
        "source": "ytdlp",
    }


def _download_thumb(url, title, tmpdir):
    """图片 tab 的封面不在 yt-dlp formats 里，直接取缩略图直链。"""
    opts = {'quiet': True, 'no_warnings': True, 'noplaylist': True, 'skip_download': True,
            'http_headers': {'User-Agent': UA}}
    info = yt_dlp.YoutubeDL(opts).extract_info(url, download=False)
    thumb = info.get("thumbnail") or ""
    if not thumb:
        raise Exception("该页面没有封面图")
    req = urllib.request.Request(thumb, headers=_headers_for(url))
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    ext = os.path.splitext(urllib.parse.urlparse(thumb).path)[1].lower()
    if ext not in IMAGE_EXTS:
        ext = ".jpg"
    fpath = os.path.join(tmpdir, _safe_name(title, ext))
    with open(fpath, "wb") as f:
        f.write(data)
    return fpath


# ---------------------------------------------------------------- 任务

tasks = {}
task_lock = threading.Lock()
task_counter = [0]
cancel_flags = {}
pause_flags = {}
pause_conds = {}
ydl_instances = {}


def _set_task(tid, **fields):
    """任务可能已被用户删除，任何状态写入都要先确认它还在，避免后台线程 KeyError。"""
    with task_lock:
        t = tasks.get(tid)
        if t is None:
            return False
        t.update(fields)
        return True


def _task_alive(tid):
    with task_lock:
        return tid in tasks


def _wake(tid):
    cond = pause_conds.get(tid)
    if cond is not None:
        with cond:
            cond.notify_all()


def _merge_av(video_path, audio_path, out_path):
    """用 Android 自带的 MediaExtractor + MediaMuxer 做无损封装。

    等价于 ffmpeg -c copy：不解码、不重编码，只把两条轨道写进同一个 mp4，
    所以体积不变、速度是秒级，也不用把几十 MB 的 ffmpeg 打进包里。
    """
    try:
        from java import jclass
        MediaUtil = jclass("com.vsp.app.MediaUtil")
        return bool(MediaUtil.callStatic("merge", video_path, audio_path, out_path))
    except Exception:
        return False


def start_download(url, format_id, title, thumb="", needs_merge=False):
    with task_lock:
        task_counter[0] += 1
        tid = task_counter[0]
        tasks[tid] = {"taskId": str(tid), "title": title, "status": "running",
                      "progress": 0, "speed": "", "filename": "", "error": "",
                      "total": 0, "downloaded": 0, "thumb": thumb or ""}
        cancel_flags[tid] = False
        pause_flags[tid] = False
        pause_conds[tid] = threading.Condition()

    direct = format_id[len(DIRECT_PREFIX):] if format_id.startswith(DIRECT_PREFIX) else ""
    target_url = direct or url
    merge = bool(needs_merge) and not direct

    def wait_if_paused():
        cond = pause_conds.get(tid)
        if cond is not None:
            while pause_flags.get(tid) and not cancel_flags.get(tid):
                with cond:
                    cond.wait(0.5)
        if cancel_flags.get(tid):
            raise Exception("已取消")

    def make_hook(base_from, base_to):
        def progress_hook(d):
            wait_if_paused()
            if d['status'] == 'downloading':
                total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
                done = d.get('downloaded_bytes', 0)
                pct = (done / total) if total else 0
                value = base_from + pct * (base_to - base_from)
                _set_task(tid, progress=value,
                          total=total, downloaded=done,
                          speed=human_size(d.get('speed', 0)) + "/s")
            elif d['status'] == 'finished':
                _set_task(tid, progress=base_to)
        return progress_hook

    def ydl_download(target, fmt, outtmpl, base_from, base_to):
        # Referer 取「原页面」的站点，而不是直链所在的 CDN 域名：
        # B 站 / 抖音只认自己站点主页，给成 CDN 域名反而 403
        opts = {
            'quiet': True, 'no_warnings': True, 'noplaylist': True,
            'format': fmt, 'outtmpl': outtmpl,
            'progress_hooks': [make_hook(base_from, base_to)],
            'http_headers': _play_headers(url),
            'nocheckcertificate': True,
            'retries': 3,
            'cachedir': False,
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl_instances[tid] = ydl
            ydl.download([target])

    def raw_download(target, dest, base_from, base_to):
        """直链（mp4/m4a）直接用 urllib 流式落盘：比走 yt-dlp 少一层解析，更不容易失败。"""
        req = urllib.request.Request(target, headers=_play_headers(url))
        with urllib.request.urlopen(req, timeout=30) as r, open(dest, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            last_bytes, last_time = 0, time.time()
            while True:
                wait_if_paused()
                buf = r.read(256 * 1024)
                if not buf:
                    break
                f.write(buf)
                done += len(buf)
                pct = (done / total) if total else 0
                now = time.time()
                speed = ""
                if now - last_time >= 0.4:
                    speed = human_size((done - last_bytes) / (now - last_time)) + "/s"
                    last_bytes, last_time = done, now
                _set_task(tid, progress=base_from + pct * (base_to - base_from),
                          total=total, downloaded=done, speed=speed)
        return dest

    def do_download():
        tmpdir = tempfile.mkdtemp()
        try:
            if format_id == "thumb":
                fpath = _download_thumb(url, title, tmpdir)
                _set_task(tid, progress=90)
            elif direct:
                # 通用兜底拿到的直链：mp4 直接流式下载，m3u8 分片交给 yt-dlp
                low = target_url.lower()
                if ".m3u8" in low:
                    outtmpl = os.path.join(tmpdir, "%(title)s.%(ext)s")
                    ydl_download(target_url, "best", outtmpl, 0, 92)
                    fpath = pick_media([os.path.join(tmpdir, f) for f in os.listdir(tmpdir)])
                else:
                    ext = _ext_of(target_url) or ".mp4"
                    fpath = os.path.join(tmpdir, _safe_name(title, ext))
                    raw_download(target_url, fpath, 0, 92)
                if not fpath or not os.path.exists(fpath) or os.path.getsize(fpath) == 0:
                    _set_task(tid, status="error", error="未找到媒体文件")
                    return
            else:
                outtmpl = os.path.join(tmpdir, "%(title)s.%(ext)s")
                fmt_arg = f"{format_id}/best" if format_id else "best"
                ydl_download(target_url, fmt_arg, outtmpl,
                             0, 60 if merge else 92)
                files = [os.path.join(tmpdir, f) for f in os.listdir(tmpdir)]
                fpath = pick_media(files)

                if merge:
                    wait_if_paused()
                    # 单独补一条音轨，再用系统 muxer 合成带声音的 mp4
                    audio_dir = os.path.join(tmpdir, "audio")
                    os.makedirs(audio_dir, exist_ok=True)
                    try:
                        ydl_download(target_url, "bestaudio[ext=m4a]/bestaudio",
                                     os.path.join(audio_dir, "%(title)s.%(ext)s"), 60, 92)
                        afiles = [os.path.join(audio_dir, f) for f in os.listdir(audio_dir)]
                        apath = pick_media(afiles)
                    except Exception:
                        apath = None
                    if apath and fpath:
                        merged = os.path.join(tmpdir, "merged.mp4")
                        _set_task(tid, progress=95, speed="合并音视频…")
                        if _merge_av(fpath, apath, merged) and os.path.getsize(merged) > 0:
                            fpath = merged
                        else:
                            _set_task(tid, progress=97, speed="合并失败，保留画面轨")
                if not fpath:
                    _set_task(tid, status="error", error="未找到媒体文件")
                    return

            # 删除任务后不再落盘，避免产生没有队列记录的文件
            if not _task_alive(tid):
                return
            target_dir = get_download_dir()
            try:
                shutil.copy2(fpath, os.path.join(target_dir, os.path.basename(fpath)))
            except Exception as e:
                _set_task(tid, status="error", error=f"保存失败: {e}")
                return
            _set_task(tid, status="done", progress=100,
                      total=os.path.getsize(fpath), downloaded=os.path.getsize(fpath),
                      speed="", filename=os.path.basename(fpath))
        except Exception as e:
            _set_task(tid, status="error", error=str(e)[:200])
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)
            ydl_instances.pop(tid, None)
            pause_flags.pop(tid, None)
            pause_conds.pop(tid, None)

    threading.Thread(target=do_download, daemon=True).start()
    return str(tid)


def pause_task(task_id):
    try:
        tid = int(task_id)
        if not _task_alive(tid):
            return "ok"
        pause_flags[tid] = True
        with task_lock:
            if tasks[tid].get("status") == "running":
                tasks[tid]["status"] = "paused"
                tasks[tid]["speed"] = "已暂停"
    except Exception:
        pass
    return "ok"


def resume_task(task_id):
    try:
        tid = int(task_id)
        pause_flags[tid] = False
        _wake(tid)
        with task_lock:
            if tasks.get(tid, {}).get("status") == "paused":
                tasks[tid]["status"] = "running"
                tasks[tid]["speed"] = ""
    except Exception:
        pass
    return "ok"


def cancel_task(task_id):
    try:
        tid = int(task_id)
        cancel_flags[tid] = True
        _wake(tid)
        _set_task(tid, status="error", error="已取消")
    except Exception:
        pass
    return "ok"


def delete_task(task_id):
    """删除队列项：活跃任务先置取消标记并唤醒下载线程，再移除记录与已下载文件。"""
    try:
        tid = int(task_id)
        cancel_flags[tid] = True
        _wake(tid)
        with task_lock:
            t = tasks.pop(tid, None)
            fn = (t or {}).get("filename", "")
        if fn:
            fp = os.path.join(get_download_dir(), fn)
            if os.path.exists(fp):
                try:
                    os.remove(fp)
                except Exception:
                    pass
        cancel_flags.pop(tid, None)
        pause_flags.pop(tid, None)
        pause_conds.pop(tid, None)
    except Exception:
        pass
    return "ok"


def get_tasks():
    with task_lock:
        result = []
        for tid, t in tasks.items():
            result.append({
                "taskId": str(tid), "title": t.get("title", ""),
                "status": t.get("status", "running"), "progress": t.get("progress", 0),
                "speed": t.get("speed", ""), "filename": t.get("filename", ""),
                "error": t.get("error", ""), "thumb": t.get("thumb", ""),
                # 列表要显示「已下载 / 总大小」，以前漏了这两个字段，
                # 界面拿到的一直是 0，只能显示「连接中…」
                "total": int(t.get("total") or 0),
                "downloaded": int(t.get("downloaded") or 0),
            })
    return json.dumps(result, ensure_ascii=False)


def get_save_dir():
    return get_download_dir()


def get_preview_url(url, format_id):
    """获取可直接播放/查看的预览 URL；通用兜底的直链直接返回。"""
    if format_id.startswith(DIRECT_PREFIX):
        return format_id[len(DIRECT_PREFIX):]
    with yt_dlp.YoutubeDL(_ydl_opts(url)) as ydl:
        info = ydl.extract_info(url, download=False)
    if format_id == "thumb":
        return info.get("thumbnail", "")
    for f in info.get("formats", []):
        if format_id and f.get("format_id") == format_id:
            return f.get("url", "")
    for f in info.get("formats", []):
        if f.get("vcodec", "none") != "none" and f.get("acodec", "none") != "none":
            return f.get("url", "")
    return ""


# ---------------------------------------------------------------- 预览代理
# 很多站点的直链会先 302 到 CDN，而 Android 的 MediaPlayer 跟随跳转时
# 不保证把 Referer / UA 一起带过去，结果就是「下载能成、预览 403」。
# 这里在 127.0.0.1 上起一个小转发服务：App 播本地地址，
# 由 Python 带齐请求头去拉真实流，并把 Range 原样转发（进度条能拖）。

_proxy_port = [0]
_proxy_lock = threading.Lock()
# 短 ID 表：CDN 直链常带一两千字符的签名参数，直接塞进 ?u= 会让播放器
# （以及部分机型 native 的 URL 解析）拒绝连接，所以只给播放器一个 /v/<id>。
_preview_store = {}
_preview_store_lock = threading.Lock()
_preview_seq = [0]
_PREVIEW_KEEP = 64


class _PreviewProxy(BaseHTTPRequestHandler):
    # 用 HTTP/1.0 语义：一个请求一个连接，读不到长度时靠关闭连接收尾
    def log_message(self, fmt, *args):
        pass

    def _target(self):
        parsed = urllib.parse.urlparse(self.path)
        m = re.match(r"^/v/([0-9]+)$", parsed.path)
        if m:
            with _preview_store_lock:
                hit = _preview_store.get(m.group(1))
            return hit if hit else None
        if parsed.path in ("/p", "/preview"):
            qs = urllib.parse.parse_qs(parsed.query)
            url = (qs.get("u") or [""])[0]
            ref = (qs.get("r") or [""])[0]
            if url.lower().startswith(("http://", "https://")):
                return (url, ref)
        return None

    def _serve(self, with_body):
        hit = self._target()
        if not hit:
            self.send_error(404)
            return
        target, referer = hit
        headers = {"User-Agent": UA, "Accept": "*/*"}
        if referer:
            headers["Referer"] = referer
        rng = self.headers.get("Range")
        if rng:
            headers["Range"] = rng
        try:
            resp = urllib.request.urlopen(urllib.request.Request(target, headers=headers), timeout=30)
        except urllib.error.HTTPError as e:
            self.send_error(e.code, "target %d" % e.code)
            return
        except Exception as e:
            self.send_error(502, str(e)[:120])
            return
        try:
            self.send_response(getattr(resp, "status", 200) or 200)
            ctype = resp.headers.get("Content-Type") or "application/octet-stream"
            self.send_header("Content-Type", ctype)
            cr = resp.headers.get("Content-Range")
            if cr:
                self.send_header("Content-Range", cr)
            cl = resp.headers.get("Content-Length")
            if cl:
                self.send_header("Content-Length", cl)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Connection", "close")
            self.end_headers()
            if not with_body:
                return
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except Exception:
            pass
        finally:
            try:
                resp.close()
            except Exception:
                pass

    def do_GET(self):
        self._serve(True)

    def do_HEAD(self):
        self._serve(False)


def _ensure_proxy():
    with _proxy_lock:
        if _proxy_port[0]:
            return _proxy_port[0]

        class _Server(ThreadingMixIn, HTTPServer):
            daemon_threads = True
            allow_reuse_address = True

        srv = _Server(("127.0.0.1", 0), _PreviewProxy)
        port = int(srv.server_address[1])
        threading.Thread(target=srv.serve_forever, daemon=True,
                         name="vsp-preview-proxy").start()
        _proxy_port[0] = port
        return port


def preview_proxy_url(url, referer=""):
    """把直链包成 127.0.0.1 的短地址交给播放器；参数不合法时返回空串。"""
    url = (url or "").strip()
    if not url.lower().startswith(("http://", "https://")):
        return ""
    try:
        port = _ensure_proxy()
    except Exception:
        return ""
    with _preview_store_lock:
        _preview_seq[0] += 1
        pid = str(_preview_seq[0])
        _preview_store[pid] = ((url, referer or ""))
        # 只留最近若干条，避免长会话里越攒越多
        if len(_preview_store) > _PREVIEW_KEEP:
            for k in sorted(_preview_store, key=lambda x: int(x))[:-_PREVIEW_KEEP]:
                _preview_store.pop(k, None)
    return "http://127.0.0.1:%d/v/%s" % (port, pid)


def warm_proxy():
    """提前把转发服务拉起来，免得第一次点预览时还要在 UI 线程里 bind 一次端口。"""
    try:
        return bool(_ensure_proxy())
    except Exception:
        return False


def preview_probe(url, referer=""):
    """预览诊断：真去拉一次，把状态码 / 类型 / 文件头 / 代理地址一起返回。

    播放器报错时 App 会调它，把结果直接摊给用户，免得「预览失败」四个字谁都看不懂。
    """
    url = (url or "").strip()
    info = {"host": "", "status": 0, "type": "", "len": 0, "magic": "", "error": ""}
    try:
        info["host"] = (urllib.parse.urlparse(url).hostname or "")[:80]
    except Exception:
        pass
    if url.lower().startswith(("http://", "https://")):
        headers = {"User-Agent": UA, "Accept": "*/*"}
        if referer:
            headers["Referer"] = referer
        try:
            resp = urllib.request.urlopen(
                urllib.request.Request(url, headers=headers), timeout=20)
            info["status"] = getattr(resp, "status", 200) or 200
            info["type"] = (resp.headers.get("Content-Type") or "")[:60]
            try:
                info["len"] = int(resp.headers.get("Content-Length") or 0)
            except Exception:
                info["len"] = 0
            head = resp.read(16)
            info["magic"] = head.hex()
            resp.close()
        except urllib.error.HTTPError as e:
            info["status"] = e.code
            info["error"] = ("HTTP %d" % e.code)[:120]
        except Exception as e:
            info["error"] = str(e)[:120]
    try:
        info["proxy"] = preview_proxy_url(url, referer)
    except Exception:
        info["proxy"] = ""
    return json.dumps(info, ensure_ascii=False)


def format_has_audio(url, format_id):
    """判断某条格式是否自带音轨，供 Java 决定下载后是否要合并。"""
    opts = {'quiet': True, 'no_warnings': True, 'noplaylist': True, 'skip_download': True,
            'http_headers': {'User-Agent': UA}, 'nocheckcertificate': True}
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        for f in info.get("formats", []):
            if f.get("format_id") == format_id:
                return f.get("acodec", "none") != "none"
    except Exception:
        pass
    return True
