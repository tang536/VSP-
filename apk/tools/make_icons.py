"""生成 VSP 启动图标：满幅橙色渐变圆角方块 + 白色 V，四角透明（去掉旧版四周的黑底）。"""
from PIL import Image, ImageDraw

OUT = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
BASE = r"C:/Users/32854/Desktop/VSP解析/apk/app/src/main/res"
SS = 8  # 超采样倍数

# 108 空间下的 V 字多边形（与 ic_launcher_foreground.xml 一致）
V_108 = [(27, 29.7), (44.28, 29.7), (54, 57.24), (63.72, 29.7), (81, 29.7),
         (62.64, 78.3), (45.36, 78.3)]

TOP = (251, 146, 60)     # #fb923c
BOTTOM = (234, 88, 12)   # #ea580c


def gradient(size):
    g = Image.new("RGB", (1, size))
    d = ImageDraw.Draw(g)
    for y in range(size):
        t = y / max(1, size - 1)
        c = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
        d.point((0, y), c)
    return g.resize((size, size))


def v_points(size, scale=1.0, dy=0.0):
    k = size / 108.0
    pts = [((x - 54) * k * scale + size / 2, (y - 54) * k * scale + size / 2 + dy) for x, y in V_108]
    return pts


def render(size, round_icon=False):
    W = size * SS
    bg = gradient(W).convert("RGBA")
    mask = Image.new("L", (W, W), 0)
    dm = ImageDraw.Draw(mask)
    if round_icon:
        dm.ellipse([0, 0, W - 1, W - 1], fill=255)
        glyph_scale = 0.86
    else:
        r = int(W * 0.235)
        dm.rounded_rectangle([0, 0, W - 1, W - 1], radius=r, fill=255)
        glyph_scale = 1.0
    canvas = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    canvas.paste(bg, (0, 0), mask)
    dg = ImageDraw.Draw(canvas)
    dg.polygon(v_points(W, glyph_scale), fill=(255, 255, 255, 255))
    return canvas.resize((size, size), Image.LANCZOS)


for name, size in OUT.items():
    render(size).save(f"{BASE}/mipmap-{name}/ic_launcher.png", optimize=True)
    render(size, round_icon=True).save(f"{BASE}/mipmap-{name}/ic_launcher_round.png", optimize=True)
    print("generated", name, size)

# 512 预览图（入库便于查看）
render(512).save(f"{BASE}/../icon-preview-512.png", optimize=True)
print("preview 512 done")
