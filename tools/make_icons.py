#!/usr/bin/env python3
"""Build GameLingoPro launcher icons from the supplied artwork."""
import os
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

SRC = "../files/WhatsApp Image 2026-11-10 at 09.15.14 (3).jpeg"
RES = "app/src/main/res"

im = Image.open(SRC).convert("RGB")
w, h = im.size
inset = 30
crop = im.crop((inset, inset, w - inset, h - inset))
# square center crop
side = min(crop.size)
left = (crop.width - side) // 2
top = (crop.height - side) // 2
crop = crop.crop((left, top, left + side, top + side))


def rounded(img, radius_ratio=0.23):
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * radius_ratio), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def circular(img):
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def feather_paste(canvas, art, box):
    """Paste art (RGBA) inside box with a soft alpha edge so no seam shows."""
    x0, y0, x1, y1 = box
    art = art.resize((x1 - x0, y1 - y0), Image.LANCZOS).convert("RGBA")
    aw, ah = art.size
    ramp = np.ones((ah, aw), dtype=np.float32)
    f = max(8, int(min(aw, ah) * 0.06))
    yv = np.arange(ah)[:, None]
    xv = np.arange(aw)[None, :]
    edge = np.minimum(np.minimum(xv, aw - 1 - xv), np.minimum(yv, ah - 1 - yv)).astype(np.float32)
    ramp = np.clip(edge / f, 0.0, 1.0)
    a = np.asarray(art)[:, :, 3].astype(np.float32) * ramp
    arr = np.asarray(art).copy()
    arr[:, :, 3] = a.astype(np.uint8)
    canvas.alpha_composite(Image.fromarray(arr, "RGBA"), (x0, y0))


# ---- adaptive foreground: the artwork, rounded, inside the safe zone ---------
FG = 512
pad = int(FG * 0.115)
os.makedirs(f"{RES}/drawable-nodpi", exist_ok=True)
canvas = Image.new("RGBA", (FG, FG), (0, 0, 0, 0))
feather_paste(canvas, rounded(crop, 0.2), (pad, pad, FG - pad, FG - pad))
canvas.save(f"{RES}/drawable-nodpi/ic_launcher_foreground.png", optimize=True)
canvas.save(f"{RES}/drawable-nodpi/ic_launcher_foreground_round.png", optimize=True)

# ---- monochrome layer: neon strokes as a solid white mask -------------------
arr = np.asarray(crop.convert("RGB")).astype(np.float32)
lum = arr.max(axis=2)
bg_model = np.asarray(
    Image.fromarray(lum.astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(26))
).astype(np.float32)
alpha = np.clip((lum - bg_model - 3.0) / 26.0, 0.0, 1.0) ** 0.8
mono = Image.new("RGBA", (FG, FG), (255, 255, 255, 0))
mlayer = np.zeros((crop.height, crop.width, 4), dtype=np.uint8)
mlayer[:, :, :3] = 255
mlayer[:, :, 3] = (alpha * 255).astype(np.uint8)
feather_paste(mono, Image.fromarray(mlayer, "RGBA"), (pad, pad, FG - pad, FG - pad))
mono.save(f"{RES}/drawable-nodpi/ic_launcher_monochrome.png", optimize=True)

# ---- legacy mipmaps ----
densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, size in densities.items():
    out_dir = f"{RES}/mipmap-{name}"
    os.makedirs(out_dir, exist_ok=True)
    rounded(crop, 0.23).resize((size, size), Image.LANCZOS).save(f"{out_dir}/ic_launcher.png", optimize=True)
    circular(crop).resize((size, size), Image.LANCZOS).save(f"{out_dir}/ic_launcher_round.png", optimize=True)

print("icons written")
