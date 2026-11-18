"""Generate and build the real Windows installer (MSI) for VSP Desktop.

Usage:
    python desktop/packaging/make_installer.py

Requires the self-contained publish output at desktop/publish/win-x64 and the
WiX 5 tool installed at desktop/tools/wix.exe (see README in this folder).
"""
from __future__ import annotations

import subprocess
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
DESKTOP = HERE.parent
PUBLISH = DESKTOP / "publish" / "win-x64"
WIX = DESKTOP / "tools" / "wix.exe"
OUT = DESKTOP / "dist"
WXS = HERE / "VSPDesktop.wxs"
VERSION = "1.0.1.0"
UPGRADE_CODE = "A0A1D4B2-7D6C-4B8E-9F35-1234567890AB"
NS = "http://wixtoolset.org/schemas/v4/wxs"
UI_NS = "http://wixtoolset.org/schemas/v4/wxs/ui"
ET.register_namespace("", NS)
ET.register_namespace("ui", UI_NS)

EXCLUDED_SUFFIXES = (".pdb",)
EXCLUDED_PARTS = ("\\__pycache__", "\\.git")


def q(tag: str) -> str:
    return f"{{{NS}}}{tag}"


def component_id(rel: str) -> str:
    digest = uuid.uuid5(uuid.NAMESPACE_URL, "vsp-desktop:" + rel.replace("\\", "/")).hex[:12]
    return "cmp" + digest


def file_id(rel: str) -> str:
    digest = uuid.uuid5(uuid.NAMESPACE_URL, "vsp-file:" + rel.replace("\\", "/")).hex[:12]
    return "fil" + digest


def dir_id(rel: str) -> str:
    digest = uuid.uuid5(uuid.NAMESPACE_URL, "vsp-dir:" + rel.replace("\\", "/")).hex[:10]
    return "dir" + digest


def guid_for(name: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "vsp-guid:" + name)).upper()


def collect_files() -> list[Path]:
    files: list[Path] = []
    for path in PUBLISH.rglob("*"):
        if not path.is_file():
            continue
        rel = "\\" + str(path.relative_to(PUBLISH))
        if path.suffix.lower() in EXCLUDED_SUFFIXES:
            continue
        if any(part in rel for part in EXCLUDED_PARTS):
            continue
        files.append(path)
    return sorted(files, key=lambda p: str(p.relative_to(PUBLISH)).lower())


def add_tree(parent: ET.Element, files: list[Path]) -> list[str]:
    """Create a Directory tree mirroring publish/; return all component IDs."""
    nodes: dict[str, ET.Element] = {"": parent}
    component_ids: list[str] = []

    def node_for(rel_dir: str) -> ET.Element:
        if rel_dir in nodes:
            return nodes[rel_dir]
        parts = rel_dir.split("\\") if rel_dir else []
        parent_dir = node_for("\\".join(parts[:-1]))
        node = ET.SubElement(parent_dir, q("Directory"), {
            "Id": dir_id(rel_dir or "root"), "Name": parts[-1],
        })
        nodes[rel_dir] = node
        return node

    for path in files:
        rel = str(path.relative_to(PUBLISH))
        rel_dir = str(path.parent.relative_to(PUBLISH))
        rel_dir = "" if rel_dir == "." else rel_dir
        node = node_for(rel_dir)
        cid = component_id(rel)
        component_ids.append(cid)
        component = ET.SubElement(node, q("Component"), {
            "Id": cid, "Guid": guid_for(rel),
        })
        ET.SubElement(component, q("File"), {
            "Id": file_id(rel), "Source": str(path), "KeyPath": "yes",
        })
    return component_ids


def build_wxs(files: list[Path]) -> None:
    wix = ET.Element(q("Wix"))
    package = ET.SubElement(wix, q("Package"), {
        "Name": "VSP解析 桌面端", "Language": "2052", "Codepage": "936",
        "Manufacturer": "VSP", "Version": VERSION,
        "UpgradeCode": UPGRADE_CODE, "ProductCode": "*", "Scope": "perUserOrMachine",
    })
    # 版本升级时自动卸载同 UpgradeCode 的旧版，避免 1.0.0 / 1.0.1 两个入口并存。
    ET.SubElement(package, q("MajorUpgrade"), {
        "Schedule": "afterInstallValidate",
        "DowngradeErrorMessage": "已安装更新版本的 VSP 桌面端，无法降级安装。",
    })
    ET.SubElement(package, q("MediaTemplate"), {"EmbedCab": "yes", "CompressionLevel": "high"})
    ET.SubElement(package, q("Icon"), {
        "Id": "AppIcon", "SourceFile": str(HERE / "app.ico"),
    })
    ET.SubElement(package, q("Property"), {"Id": "ARPPRODUCTICON", "Value": "AppIcon"})
    ET.SubElement(package, f"{{{UI_NS}}}WixUI", {
        "Id": "WixUI_InstallDir", "InstallDirectory": "INSTALLFOLDER",
    })

    feature = ET.SubElement(package, q("Feature"), {
        "Id": "Main", "Title": "VSP解析", "Level": "1",
    })
    ET.SubElement(feature, q("ComponentGroupRef"), {"Id": "AppFiles"})

    ET.SubElement(package, q("StandardDirectory"), {"Id": "DesktopFolder"})
    ET.SubElement(package, q("StandardDirectory"), {"Id": "ProgramMenuFolder"})
    pf = ET.SubElement(package, q("StandardDirectory"), {"Id": "ProgramFiles6432Folder"})
    install = ET.SubElement(pf, q("Directory"), {"Id": "INSTALLFOLDER", "Name": "VSP解析"})
    component_ids = add_tree(install, files)

    shortcuts = ET.SubElement(install, q("Component"), {
        "Id": "Shortcuts", "Guid": guid_for("shortcuts"),
    })
    ET.SubElement(shortcuts, q("Shortcut"), {
        "Id": "DesktopShortcut", "Directory": "DesktopFolder", "Name": "VSP解析",
        "Target": "[INSTALLFOLDER]VSP.Desktop.exe", "WorkingDirectory": "INSTALLFOLDER",
        "Icon": "AppIcon", "Description": "VSP 视频解析与下载",
    })
    ET.SubElement(shortcuts, q("Shortcut"), {
        "Id": "StartMenuShortcut", "Directory": "ProgramMenuFolder", "Name": "VSP解析",
        "Target": "[INSTALLFOLDER]VSP.Desktop.exe", "WorkingDirectory": "INSTALLFOLDER",
        "Icon": "AppIcon", "Description": "VSP 视频解析与下载",
    })
    ET.SubElement(shortcuts, q("RegistryValue"), {
        "Root": "HKCU", "Key": r"Software\VSP\VSPDesktop", "Name": "Installed",
        "Type": "integer", "Value": "1", "KeyPath": "yes",
    })

    group = ET.SubElement(package, q("ComponentGroup"), {"Id": "AppFiles"})
    for cid in component_ids:
        ET.SubElement(group, q("ComponentRef"), {"Id": cid})
    ET.SubElement(group, q("ComponentRef"), {"Id": "Shortcuts"})

    ET.indent(wix, space="  ")
    WXS.write_text('<?xml version="1.0" encoding="utf-8"?>\n' +
                   ET.tostring(wix, encoding="unicode"), encoding="utf-8")


def main() -> None:
    if not PUBLISH.exists() or not (PUBLISH / "VSP.Desktop.exe").exists():
        raise SystemExit(f"missing publish output: {PUBLISH}")
    if not WIX.exists():
        raise SystemExit(f"missing WiX tool: {WIX}")
    files = collect_files()
    print(f"[installer] {len(files)} files from {PUBLISH}")
    build_wxs(files)
    OUT.mkdir(parents=True, exist_ok=True)
    msi = OUT / f"VSP-Setup-v{VERSION.rsplit('.', 1)[0]}.msi"
    cmd = [str(WIX), "build", str(WXS), "-arch", "x64",
           "-ext", "WixToolset.UI.wixext", "-o", str(msi)]
    print("[installer] " + " ".join(cmd))
    subprocess.run(cmd, check=True)
    print(f"[installer] {msi}  {msi.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    main()
