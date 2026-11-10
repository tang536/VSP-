import json, os, threading, urllib.request, urllib.parse, tempfile, shutil, mimetypes
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from vsp import get_download_dir, set_private_dir, pick_media, _download_thumb
import yt_dlp

STATIC_DIR = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "static"))


def set_static_dir(path):
    """允许宿主（WebView / Java 层）指定静态页面目录。"""
    global STATIC_DIR
    STATIC_DIR = os.path.abspath(path)
    return STATIC_DIR


tasks = {}
task_lock = threading.Lock()
task_counter = [0]
cancel_flags = {}


def human_size(n):
    if not n or n <= 0:
        return "-"
    units = ["B", "KB", "MB", "GB"]
    i = 0
    while n >= 1024 and i < len(units) - 1:
        n /= 1024
        i += 1
    return f"{n:.1f} {units[i]}"


def normalize_info(info):
    videos, audios, images = [], [], []
    for f in info.get("formats", []):
        vcodec = f.get("vcodec", "none")
        acodec = f.get("acodec", "none")
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
            "vcodec": None if vcodec == "none" else vcodec,
            "acodec": None if acodec == "none" else acodec,
        }
        if vcodec and vcodec != "none":
            videos.append(item)
        elif acodec and acodec != "none":
            audios.append(item)
    if info.get("thumbnail"):
        images.append({"formatId": "thumb", "ext": "jpg", "container": "jpg",
                       "quality": "封面", "width": None, "height": None, "bitrate": 0,
                       "size": "-", "url": info["thumbnail"], "vcodec": None, "acodec": None})
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
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send_json(self, obj, code=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        qs = urllib.parse.parse_qs(parsed.query)

        if path == "/api/config":
            self._send_json({"saveDir": get_download_dir()})
            return

        if path == "/api/tasks":
            with task_lock:
                result = []
                for tid, t in tasks.items():
                    result.append({
                        "taskId": str(tid), "url": t.get("url", ""), "title": t.get("title", ""),
                        "savePath": get_download_dir(), "status": t.get("status", "running"),
                        "progress": t.get("progress", 0), "speed": t.get("speed", ""),
                        "eta": t.get("eta", ""), "filename": t.get("filename", ""),
                        "error": t.get("error", ""),
                    })
            self._send_json(result)
            return

        if path == "/api/open-folder":
            # 原来调用一个并不存在的 android.py 模块，静默失败；现在直接把目录交还给界面层
            self._send_json({"ok": True, "dir": get_download_dir()})
            return

        if path == "/api/proxy":
            url = qs.get("url", [""])[0]
            referer = qs.get("referer", [""])[0]
            if not url:
                self.send_error(400)
                return
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                if referer:
                    req.add_header("Referer", referer)
                range_header = self.headers.get("Range")
                if range_header:
                    req.add_header("Range", range_header)
                r = urllib.request.urlopen(req, timeout=30)
                status = getattr(r, "status", 200) or 200
                self.send_response(status)
                self.send_header("Content-Type", r.headers.get("Content-Type", "application/octet-stream"))
                cr = r.headers.get("Content-Range", "")
                # 206 分段响应不能同时发完整 Content-Length，否则 <video> 无法拖动进度
                if cr:
                    self.send_header("Content-Range", cr)
                else:
                    cl = r.headers.get("Content-Length", "")
                    if cl:
                        self.send_header("Content-Length", cl)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                while True:
                    chunk = r.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except Exception as e:
                self.send_error(502, str(e))
            return

        if path == "/api/download-stream":
            url = qs.get("url", [""])[0]
            title = qs.get("title", ["video"])[0]
            fmt = qs.get("formatId", qs.get("format", [""]))[0]
            preview = qs.get("preview", ["0"])[0] == "1"
            if not url:
                self._send_json({"detail": "缺少 url"}, 400)
                return

            with task_lock:
                task_counter[0] += 1
                tid = task_counter[0]
                tasks[tid] = {"taskId": str(tid), "url": url, "title": title,
                              "status": "running", "progress": 0, "speed": "", "eta": "",
                              "filename": "", "error": ""}
                cancel_flags[tid] = False

            tmpdir = tempfile.mkdtemp()
            try:
                if fmt == "thumb":
                    # 封面不在 yt-dlp formats 里，走缩略图直链
                    try:
                        fpath = _download_thumb(url, title, tmpdir)
                    except Exception as e:
                        with task_lock:
                            tasks[tid]["status"] = "error"
                            tasks[tid]["error"] = str(e)[:300]
                        self._send_json({"detail": str(e)[:300]}, 500)
                        return
                    with task_lock:
                        tasks[tid]["progress"] = 90
                else:
                    outtmpl = os.path.join(tmpdir, "%(title)s.%(ext)s")
                    fmt_arg = f"{fmt}/best" if fmt else "best"

                    def progress_hook(d):
                        if d['status'] == 'downloading':
                            if cancel_flags.get(tid):
                                raise Exception("已取消")
                            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
                            downloaded = d.get('downloaded_bytes', 0)
                            pct = (downloaded / total * 100) if total else 0
                            with task_lock:
                                tasks[tid]["progress"] = pct
                                tasks[tid]["speed"] = human_size(d.get('speed', 0)) + "/s"
                        elif d['status'] == 'finished':
                            with task_lock:
                                tasks[tid]["progress"] = 99

                    ydl_opts = {
                        'quiet': True, 'no_warnings': True, 'noplaylist': True,
                        'format': fmt_arg, 'outtmpl': outtmpl,
                        'progress_hooks': [progress_hook],
                    }
                    try:
                        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                            ydl.download([url])
                    except Exception as e:
                        with task_lock:
                            tasks[tid]["status"] = "error"
                            tasks[tid]["error"] = str(e)[:300]
                        self._send_json({"detail": str(e)[:300]}, 500)
                        return

                    files = [os.path.join(tmpdir, f) for f in os.listdir(tmpdir)]
                    fpath = pick_media(files)
                    if not fpath:
                        with task_lock:
                            tasks[tid]["status"] = "error"
                            tasks[tid]["error"] = "未找到媒体文件"
                        self._send_json({"detail": "未找到媒体文件"}, 500)
                        return
                try:
                    shutil.copy2(fpath, os.path.join(get_download_dir(), os.path.basename(fpath)))
                except Exception as e:
                    with task_lock:
                        tasks[tid]["status"] = "error"
                        tasks[tid]["error"] = f"保存失败: {e}"
                    self._send_json({"detail": f"保存失败: {e}"}, 500)
                    return
                with task_lock:
                    tasks[tid]["status"] = "done"
                    tasks[tid]["progress"] = 100
                    tasks[tid]["filename"] = os.path.basename(fpath)

                ctype, _ = mimetypes.guess_type(fpath)
                if not ctype:
                    ctype = "application/octet-stream"
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                disposition = "inline" if preview else "attachment"
                fname = os.path.basename(fpath)
                self.send_header("Content-Disposition",
                                 f"{disposition}; filename*=UTF-8''{urllib.parse.quote(fname)}")
                self.send_header("Content-Length", str(os.path.getsize(fpath)))
                self.end_headers()
                with open(fpath, "rb") as f:
                    shutil.copyfileobj(f, self.wfile, 65536)
            finally:
                shutil.rmtree(tmpdir, ignore_errors=True)
            return

        if path == "/":
            path = "/index.html"
        fp = os.path.abspath(os.path.join(STATIC_DIR, path.lstrip("/")))
        if os.path.commonpath([fp, STATIC_DIR]) == STATIC_DIR and os.path.isfile(fp):
            ctype, _ = mimetypes.guess_type(fp)
            if not ctype:
                ctype = "application/octet-stream"
            self.send_response(200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(os.path.getsize(fp)))
            self.end_headers()
            with open(fp, "rb") as f:
                shutil.copyfileobj(f, self.wfile, 65536)
            return
        self.send_error(404)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/api/cancel":
            qs = urllib.parse.parse_qs(parsed.query)
            tid = qs.get("taskId", [""])[0]
            try:
                tid_int = int(tid)
                cancel_flags[tid_int] = True
                with task_lock:
                    if tid_int in tasks:
                        tasks[tid_int]["status"] = "error"
                        tasks[tid_int]["error"] = "已取消"
            except Exception:
                pass
            self._send_json({"ok": True})
            return

        if path != "/api/parse":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body)
            url = data.get("url", "")
        except Exception:
            self._send_json({"detail": "bad json"}, 400)
            return
        if not url:
            self._send_json({"detail": "缺少 url"}, 400)
            return
        try:
            # 与 app 内走同一套解析：yt-dlp 失败时用 HTML 通用兜底，
            # 避免「自定义播放器的站点一律解析不了」
            from vsp import parse as vsp_parse
            self._send_json(json.loads(vsp_parse(url)))
        except Exception as e:
            self._send_json({"detail": f"解析失败: {str(e)[:300]}"}, 400)

    def do_DELETE(self):
        parsed = urllib.parse.urlparse(self.path)
        parts = parsed.path.strip("/").split("/")
        if len(parts) == 3 and parts[0] == "api" and parts[1] == "tasks":
            tid = parts[2]
            try:
                tid_int = int(tid)
                with task_lock:
                    if tid_int in tasks:
                        fn = tasks[tid_int].get("filename", "")
                        if fn:
                            fpath = os.path.join(get_download_dir(), fn)
                            if os.path.exists(fpath):
                                try:
                                    os.remove(fpath)
                                except Exception:
                                    pass
                        del tasks[tid_int]
                self._send_json({"ok": True})
            except Exception:
                self._send_json({"ok": False}, 400)
            return
        self.send_error(404)


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


def start_server(port=8080):
    srv = ThreadedHTTPServer(("127.0.0.1", port), Handler)
    srv.serve_forever()


if __name__ == "__main__":
    start_server()
