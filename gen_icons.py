# -*- coding: utf-8 -*-
"""从用户设计的 icon 源图生成 Android 启动图标资源。

产出：
1. 自适应图标前景（白色波形抠到透明底，内容缩到安全区 ~50% 画布宽）
2. 传统 mipmap 回退图（整图缩放）
3. mipmap-anydpi-v26 的 adaptive-icon XML（黑色背景 + 前景）
"""
import os
from PIL import Image

SRC = os.path.join(os.path.dirname(__file__), "icon-source.jpg")
RES = os.path.join(os.path.dirname(__file__), "app", "src", "main", "res")

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

img = Image.open(SRC).convert("RGB")
print("source:", img.size)

# --- 1. 抠出白色内容到透明底（亮度→alpha，RGB 统一置白） ---
lum = img.convert("L")


def to_alpha(v):
    if v <= 50:
        return 0
    if v >= 190:
        return 255
    return int((v - 50) / 140 * 255)


alpha = lum.point(to_alpha)
content = Image.new("RGBA", img.size, (255, 255, 255, 0))
white = Image.new("RGBA", img.size, (255, 255, 255, 255))
content.paste(white, (0, 0), alpha)
bbox = content.getbbox()
print("content bbox:", bbox)
content = content.crop(bbox)
cw, ch = content.size
print("content size:", content.size)

# --- 2. 自适应图标前景：108dp 画布，内容缩到画布宽 50%，居中 ---
for dpi, scale in DENSITIES.items():
    canvas_size = int(108 * scale)
    fg = Image.new("RGBA", (canvas_size, canvas_size), (255, 255, 255, 0))
    target_w = int(canvas_size * 0.50)
    target_h = int(target_w * ch / cw)
    if target_h > canvas_size * 0.5:
        target_h = int(canvas_size * 0.50)
        target_w = int(target_h * cw / ch)
    piece = content.resize((target_w, target_h), Image.LANCZOS)
    off = ((canvas_size - target_w) // 2, (canvas_size - target_h) // 2)
    fg.paste(piece, off, piece)
    d = os.path.join(RES, "mipmap-%s" % dpi)
    os.makedirs(d, exist_ok=True)
    fg.save(os.path.join(d, "ic_launcher_foreground.png"))
    # --- 3. 传统回退图：整图（黑底白波形）直接缩放 ---
    legacy_size = int(48 * scale)
    full = img.resize((legacy_size, legacy_size), Image.LANCZOS)
    full.save(os.path.join(d, "ic_launcher.png"))
    full.save(os.path.join(d, "ic_launcher_round.png"))
    print("mipmap-%s done (%d px canvas)" % (dpi, canvas_size))

# --- 4. adaptive-icon XML ---
d = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(d, exist_ok=True)
ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(d, name), "w", encoding="utf-8") as f:
        f.write(ADAPTIVE)
print("adaptive xml done")
