"""让所有版本痕迹跟随根目录 version.json。

用法：
    python tools/sync_versions.py            # 只检查，发现漂移返回非 0
    python tools/sync_versions.py --write    # 修复可自动修复的文件

真正的构建已经直接读取 version.json：
  - 桌面 App：version.json 作为嵌入资源，UpdateService 运行时解析
  - 桌面 MSI：make_installer.py 读取 version.json，并自动重建 dist/latest.json
  - 安卓：app/build.gradle 用 JsonSlurper 读取 version.json

本脚本只处理仍保存了版本副本的发布元数据文件（apk/dist/latest.json、README.md），
并提供 --check 给 CI / 发版前自检。
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSIONS = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))
DESKTOP = VERSIONS["desktop"]["version"]
ANDROID = VERSIONS["android"]["version"]
ANDROID_CODE = int(VERSIONS["android"]["versionCode"])
DESKTOP_TAG = VERSIONS["tagPrefix"]["desktop"] + DESKTOP
ANDROID_TAG = VERSIONS["tagPrefix"]["android"] + ANDROID

APK_LATEST = ROOT / "apk" / "dist" / "latest.json"
DESKTOP_LATEST = ROOT / "desktop" / "dist" / "latest.json"
README = ROOT / "README.md"
WXS = ROOT / "desktop" / "packaging" / "VSPDesktop.wxs"
GRADLE = ROOT / "apk" / "app" / "build.gradle"
INSTALLER = ROOT / "desktop" / "packaging" / "make_installer.py"
UPDATE_CS = ROOT / "desktop" / "src" / "VSP.Core" / "UpdateService.cs"


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fix_apk_latest() -> None:
    data = read_json(APK_LATEST)
    changed = data.get("version") != ANDROID or data.get("versionCode") != ANDROID_CODE
    data["version"] = ANDROID
    data["versionCode"] = ANDROID_CODE
    data["apk"] = f"dist/VSP-Parse-v{ANDROID}.apk"
    if changed:
        # 版本变了，旧的大小/哈希一定失效，强制发版时重新填写。
        data["size"] = 0
        data["sha256"] = ""
        print("[warn] 安卓版本已变化，APK 的 size/sha256 已清空，请重新打包后填写")
    write_json(APK_LATEST, data)


def fix_readme() -> None:
    text = README.read_text(encoding="utf-8")
    line = f"- 当前版本：Android **v{ANDROID}**（versionCode {ANDROID_CODE}）｜桌面 **v{DESKTOP}**"
    new_text, count = re.subn(r"^- 当前版本：.*$", line, text, count=1, flags=re.MULTILINE)
    if count == 0:
        raise SystemExit("README.md 找不到版本行，无法自动更新")
    README.write_text(new_text, encoding="utf-8")


def check() -> list[str]:
    issues: list[str] = []

    gradle = GRADLE.read_text(encoding="utf-8")
    if "rootProject.file('../version.json')" not in gradle:
        issues.append("apk/app/build.gradle 没有读取 version.json")
    if re.search(r'versionCode\s+\d+|versionName\s+"', gradle):
        issues.append("apk/app/build.gradle 仍写死 versionCode / versionName")

    installer = INSTALLER.read_text(encoding="utf-8")
    if 'VERSION = "' in installer:
        issues.append("make_installer.py 仍写死 VERSION")
    if 'VERSIONS["desktop"]["version"]' not in installer:
        issues.append("make_installer.py 没有读取 version.json")

    update_cs = UPDATE_CS.read_text(encoding="utf-8")
    if re.search(r'const\s+string\s+Version\s*=', update_cs):
        issues.append("UpdateService.cs 仍写死 Version")
    if 'GetManifestResourceStream("VSP.Version.json")' not in update_cs:
        issues.append("UpdateService.cs 没有读取嵌入的 version.json")

    wxs = WXS.read_text(encoding="utf-8")
    if f'Version="{DESKTOP}.0"' not in wxs:
        issues.append(f"VSPDesktop.wxs 版本应为 {DESKTOP}.0")

    desktop_latest = read_json(DESKTOP_LATEST)
    if desktop_latest.get("version") != DESKTOP:
        issues.append(f"desktop/dist/latest.json 版本应为 {DESKTOP}")
    if f"desktop-v{DESKTOP}" not in " ".join(
        [desktop_latest.get("msi", ""), *desktop_latest.get("mirrors", []), desktop_latest.get("page", "")]
    ):
        issues.append(f"desktop/dist/latest.json 链接应为 tag {DESKTOP_TAG}")

    apk_latest = read_json(APK_LATEST)
    if apk_latest.get("version") != ANDROID or apk_latest.get("versionCode") != ANDROID_CODE:
        issues.append(f"apk/dist/latest.json 应为 {ANDROID} / {ANDROID_CODE}")
    if apk_latest.get("apk") != f"dist/VSP-Parse-v{ANDROID}.apk":
        issues.append(f"apk/dist/latest.json 的 apk 路径应为 dist/VSP-Parse-v{ANDROID}.apk")

    readme = README.read_text(encoding="utf-8")
    current = re.search(r"^- 当前版本：.*$", readme, re.MULTILINE)
    if current is None or f"v{ANDROID}" not in current.group(0) or f"v{DESKTOP}" not in current.group(0):
        issues.append("README.md 版本行与 version.json 不一致")

    return issues


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="修复可自动修复的版本副本")
    args = parser.parse_args()

    if args.write:
        fix_apk_latest()
        fix_readme()
        print("[ok] 已同步 apk/dist/latest.json 和 README.md")

    issues = check()
    if issues:
        print("[FAIL] 版本漂移：")
        for issue in issues:
            print("  - " + issue)
        raise SystemExit(1)
    print(f"[ok] 版本一致：桌面 {DESKTOP} / 安卓 {ANDROID} ({ANDROID_CODE})")


if __name__ == "__main__":
    main()
