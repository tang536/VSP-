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


def cmd_parse(url):
    try:
        data = vsp.parse(url)
    except Exception as e:
        sys.stderr.write(str(e) + "\n")
        sys.stderr.flush()
        return 3
    # vsp.parse 已经是 JSON 字符串
    sys.stdout.write(data)
    sys.stdout.flush()
    return 0


def cmd_download(page_url, format_id, title, out_dir):
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        vsp.set_private_dir(out_dir)
    install_patient_watchdog()
    tid = vsp.start_download(page_url, format_id, title or "download")
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


def main(argv):
    if len(argv) < 2:
        sys.stderr.write("usage: vsp_cli.py parse <url> | download <pageUrl> <formatId> <title> <outDir>\n")
        return 64
    cmd = argv[1]
    if cmd == "parse":
        if len(argv) < 3:
            return 64
        return cmd_parse(argv[2])
    if cmd == "download":
        if len(argv) < 6:
            return 64
        return cmd_download(argv[2], argv[3], argv[4], argv[5])
    sys.stderr.write("unknown command: %s\n" % cmd)
    return 64


if __name__ == "__main__":
    sys.exit(main(sys.argv))
