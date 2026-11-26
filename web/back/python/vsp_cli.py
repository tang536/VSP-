"""web 后端调用 app 端 vsp.py 的命令行壳。

为什么要有这一层：
- web 后端原来只用 yt-dlp，而 yt-dlp 不认这类「archives」站点的页面（Unsupported URL），
  也正因为它的 HLS 下载器只取一次播放列表，分片签名过期后不会刷新，才会一直卡在 2~3MB。
- app 端 vsp.py 里已经有站点提取器 + 自实现的 HLS 分片下载器（刷新播放列表、指数退避、分片缓存）。
  这里直接把同一份引擎以子进程方式复用，行为与 app 完全一致，不再两套实现各写一遍。

用法：
  python vsp_cli.py parse <url>
      → stdout 输出 vsp.parse 的 JSON（字段与 web 前端一致）
  python vsp_cli.py download <pageUrl> <formatId> <title> <outDir>
      → 每行输出一个 JSON 进度对象，直到 status 变成 done / error
"""
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
APK_VSP_DIR = os.path.abspath(
    os.path.join(HERE, "..", "..", "..", "apk", "app", "src", "main", "python")
)

# 优先用仓库里 apk 工程的那一份（保证和 app 同一源码，不会各改各的）；
# 找不到时才用同目录里的本地副本（发布/拷贝场景）。
if os.path.isfile(os.path.join(APK_VSP_DIR, "vsp.py")):
    sys.path.insert(0, APK_VSP_DIR)
else:
    sys.path.insert(0, HERE)

import vsp  # noqa: E402


def install_patient_watchdog():
    """把「假死保护」的阈值放宽到 10 分钟（仅本进程，app 里的 vsp.py 不动）。

    vsp.py 的分片退避最长 60 秒，而看门狗恰好也是 60 秒无数据就判失败，
    两边撞在一起：限速站点一退避就被掐断，队列里只剩「网络长时间无响应」。
    真正需要兜底的只有「连接被接受后彻底不再回数据」，10 分钟足够区分。
    """
    import threading
    import time as _t

    def loop():
        while True:
            _t.sleep(5)
            now = _t.time()
            for tid, t in list(vsp.tasks.items()):
                if t.get("status") != "running" or vsp.pause_flags.get(tid):
                    continue
                last = vsp.last_activity.get(tid)
                if not last:
                    continue
                idle = now - last
                if idle >= 3 and (t.get("speed") or "").endswith("/s"):
                    vsp._set_task(tid, speed="")
                if idle >= 60:
                    vsp.abort_flags[tid] = True
                    vsp._set_task(tid, status="error", speed="",
                                  error="网络长时间无响应，请重试")

    vsp._watchdog_loop = loop


def emit(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def cmd_parse(url, ttl=600):
    try:
        vsp.set_parse_ttl(ttl)
        data = vsp.parse(url)
    except Exception as e:
        sys.stderr.write(str(e) + "\n")
        sys.stderr.flush()
        return 3
    # vsp.parse 已经是 JSON 字符串
    sys.stdout.write(data)
    sys.stdout.flush()
    return 0


def cmd_download(page_url, format_id, title, out_dir, limit_kbps=0,
                 name_template="", media_id="", uploader="", source=""):
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        vsp.set_private_dir(out_dir)
    vsp.set_speed_limit(limit_kbps)
    install_patient_watchdog()
    tid = vsp.start_download(page_url, format_id, title or "download",
                             media_id=media_id, uploader=uploader, source=source,
                             name_template=name_template)
    last_state = None
    last_bytes = 0
    last_bytes_at = time.time()
    started = time.time()
    while True:
        try:
            tasks = json.loads(vsp.get_tasks())
        except Exception:
            tasks = []
        me = next((t for t in tasks if str(t.get("taskId")) == str(tid)), None)
        if me is not None:
            downloaded = int(me.get("downloaded") or 0)
            if downloaded != last_bytes:
                last_bytes = downloaded
                last_bytes_at = time.time()
            speed = me.get("speed") or ""
            # 源站限流时会一直退避重试，但 vsp 内部为了不被看门狗误杀会持续 _touch，
            # 速度字段于是停在最后一次的旧值——界面上就是「速度还在跳、进度不动」。
            # 这里按「数据多久没涨」自己判，超过 20 秒就不再显示旧速度。
            if speed and time.time() - last_bytes_at > 3:
                speed = "等待源站重试…"
            state = (me.get("status"), round(float(me.get("progress") or 0), 1),
                     speed, me.get("error") or "", me.get("filename") or "")
            if state != last_state:
                last_state = state
                emit({
                    "taskId": str(tid),
                    "status": me.get("status") or "running",
                    "progress": float(me.get("progress") or 0),
                    "speed": speed,
                    "error": me.get("error") or "",
                    "filename": me.get("filename") or "",
                    "total": int(me.get("total") or 0),
                    "downloaded": downloaded,
                })
            if me.get("status") in ("done", "error"):
                return 0 if me.get("status") == "done" else 2
        else:
            # 任务已被引擎自行清理
            emit({"status": "error", "progress": 0, "error": "任务已结束"})
            return 2
        # 进度只在变化时输出；这里限一下最长运行时间，避免僵尸进程
        if time.time() - started > 6 * 3600:
            emit({"status": "error", "progress": last_state[1] if last_state else 0,
                  "error": "下载超过 6 小时，已终止"})
            return 2
        time.sleep(0.25)


def cmd_control(action, out_dir, value=None):
    """暂停/继续/取消/限速：写控制文件，由正在下载的子进程轮询生效。"""
    target_dir = out_dir or vsp.get_download_dir()
    os.makedirs(target_dir, exist_ok=True)
    path = os.path.join(target_dir, "vsp_control.json")
    data = {}
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
    if action == "speed":
        try:
            data["__speed__"] = max(0, int(value or 0))
        except Exception:
            data["__speed__"] = 0
    else:
        data[str(value)] = action
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)
    emit({"status": "ok", "action": action, "value": value})
    return 0


def cmd_rules(action, out_dir=None, value=None):
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        vsp.set_private_dir(out_dir)
    if action == "rules":
        emit({"status": "ok", "rules": vsp.describe_rules()})
        return 0
    if action == "ttl":
        vsp.set_parse_ttl(value or 600)
        emit({"status": "ok", "ttl": vsp._parse_ttl[0]})
        return 0
    if action == "clearcache":
        vsp.clear_parse_cache()
        emit({"status": "ok", "action": "clearcache"})
        return 0
    return 64


def main(argv):
    if len(argv) < 2:
        sys.stderr.write("usage: vsp_cli.py parse <url> | download <pageUrl> <formatId> <title> <outDir> "
                         "[limitKBps] [nameTemplate] [id] [uploader] [source] | "
                         "pause|resume|cancel <engineTaskId> <outDir> | speed <kbps> <outDir> | "
                         "rules [outDir] | ttl <seconds> [outDir] | clearcache [outDir]\n")
        return 64
    cmd = argv[1]
    if cmd == "parse":
        if len(argv) < 3:
            return 64
        ttl = int(argv[3]) if len(argv) > 3 else 600
        return cmd_parse(argv[2], ttl)
    if cmd == "download":
        if len(argv) < 6:
            return 64
        extra = argv[6:] + [""] * (5 - min(5, len(argv[6:])))
        return cmd_download(argv[2], argv[3], argv[4], argv[5],
                            extra[0], extra[1], extra[2], extra[3], extra[4])
    if cmd in ("pause", "resume", "cancel"):
        if len(argv) < 5:
            return 64
        return cmd_control(cmd, argv[4], argv[3])
    if cmd == "speed":
        if len(argv) < 4:
            return 64
        return cmd_control("speed", argv[3], argv[2])
    if cmd == "rules":
        return cmd_rules("rules", argv[3] if len(argv) > 3 else None)
    if cmd == "ttl":
        if len(argv) < 3:
            return 64
        return cmd_rules("ttl", argv[3] if len(argv) > 3 else None, int(argv[2]))
    if cmd == "clearcache":
        return cmd_rules("clearcache", argv[3] if len(argv) > 3 else None)
    sys.stderr.write("unknown command: %s\n" % cmd)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv))
