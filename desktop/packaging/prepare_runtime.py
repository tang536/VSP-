"""Prepare a self-contained CPython runtime for the VSP desktop app.

The runtime is shared by the Web and Android engines (vsp.py + vsp_cli.py), so the
desktop build does NOT depend on a system-wide Python installation.

Usage:
    python desktop/packaging/prepare_runtime.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUNTIME = HERE / "runtime" / "python"
CACHE = HERE / "cache"
PY_VERSION = "3.13.1"
PY_ZIP = f"python-{PY_VERSION}-embed-amd64.zip"
PY_URL = f"https://www.python.org/ftp/python/{PY_VERSION}/{PY_ZIP}"
MARKER = RUNTIME / ".prepared"


def log(message: str) -> None:
    print(f"[prepare-runtime] {message}", flush=True)


def download() -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / PY_ZIP
    if target.exists() and target.stat().st_size > 5_000_000:
        return target
    log(f"downloading {PY_URL}")
    req = urllib.request.Request(PY_URL, headers={"User-Agent": "VSP-Desktop-Build/1.0"})
    with urllib.request.urlopen(req, timeout=120) as response, target.open("wb") as out:
        shutil.copyfileobj(response, out)
    if target.stat().st_size < 5_000_000:
        target.unlink(missing_ok=True)
        raise RuntimeError("download of embeddable Python looks truncated")
    return target


def prepare() -> None:
    if MARKER.exists():
        log(f"runtime already prepared at {RUNTIME}")
        return
    archive = download()
    if RUNTIME.exists():
        shutil.rmtree(RUNTIME)
    RUNTIME.parent.mkdir(parents=True, exist_ok=True)
    log(f"extracting to {RUNTIME}")
    with zipfile.ZipFile(archive) as zip_file:
        zip_file.extractall(RUNTIME)

    # Embeddable CPython ignores site-packages unless import site is enabled.
    pth_files = list(RUNTIME.glob("python*._pth"))
    if len(pth_files) != 1:
        raise RuntimeError(f"expected one ._pth file, found {pth_files}")
    pth = pth_files[0]
    text = pth.read_text(encoding="utf-8")
    text = text.replace("#import site", "import site")
    if "Lib\\site-packages" not in text:
        text += "\nLib\\site-packages\n"
    pth.write_text(text, encoding="utf-8")

    site_packages = RUNTIME / "Lib" / "site-packages"
    site_packages.mkdir(parents=True, exist_ok=True)
    log("installing yt-dlp (the only third-party dependency of vsp.py)")
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "--disable-pip-version-check",
         "--no-deps", "--upgrade", "--target", str(site_packages), "yt-dlp"],
        check=True,
    )

    python_exe = RUNTIME / "python.exe"
    result = subprocess.run(
        [str(python_exe), "-c", "import sys,yt_dlp;print(sys.executable);print(yt_dlp.version.__version__)"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(f"runtime self-check failed: {result.stderr}")
    log("runtime ready: " + result.stdout.strip().replace("\n", " / "))
    MARKER.write_text(result.stdout, encoding="utf-8")


if __name__ == "__main__":
    prepare()
