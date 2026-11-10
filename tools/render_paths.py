#!/usr/bin/env python3
"""Tiny path rasterizer (M L H V A Z, absolute + relative) used to preview the
VectorDrawable geometry without any external SVG library."""
import math
import re
from PIL import Image, ImageDraw

TOKEN = re.compile(r"([MmLlHhVvAaZz])|(-?\d*\.?\d+(?:e-?\d+)?)")


def tokenize(d):
    out = []
    for m in TOKEN.finditer(d):
        out.append(m.group(0))
    return out


def flatten(d, steps=14):
    """Return a list of polylines (each a list of (x, y))."""
    toks = tokenize(d)
    i = 0
    polys = []
    cur = []
    x = y = 0.0
    sx = sy = 0.0
    cmd = None

    def num():
        nonlocal i
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t
            i += 1
            if cmd in "Zz":
                if cur:
                    cur.append((sx, sy))
                    polys.append(cur)
                cur = []
                x, y = sx, sy
                continue
            if cmd in "Mm":
                x, y = num(), num()
                if cur:
                    polys.append(cur)
                cur = [(x, y)]
                sx, sy = x, y
                cmd = "L" if cmd == "M" else "l"
                continue
            continue
        if cmd in "Ll":
            nx, ny = num(), num()
            if cmd == "l":
                nx, ny = x + nx, y + ny
            x, y = nx, ny
            cur.append((x, y))
        elif cmd in "Hh":
            nx = num()
            x = nx if cmd == "H" else x + nx
            cur.append((x, y))
        elif cmd in "Vv":
            ny = num()
            y = ny if cmd == "V" else y + ny
            cur.append((x, y))
        elif cmd in "Aa":
            rx, ry, rot, large, sweep = num(), num(), num(), num(), num()
            nx, ny = num(), num()
            if cmd == "a":
                nx, ny = x + nx, y + ny
            # endpoint -> center parameterisation (circular arcs only)
            x1, y1 = x, y
            dx2, dy2 = (x1 - nx) / 2.0, (y1 - ny) / 2.0
            r = rx
            d2 = dx2 * dx2 + dy2 * dy2
            if d2 > r * r and r > 0:
                r = math.sqrt(d2)
            cx = cy = 0.0
            if d2 > 0:
                h = math.sqrt(max(0.0, (r * r - d2) / d2))
                sign = -1 if large == sweep else 1
                cx = sign * h * dy2 + (x1 + nx) / 2.0
                cy = sign * h * -dx2 + (y1 + ny) / 2.0
            a0 = math.atan2(y1 - cy, x1 - cx)
            a1 = math.atan2(ny - cy, nx - cx)
            if sweep and a1 < a0:
                a1 += 2 * math.pi
            if not sweep and a1 > a0:
                a1 -= 2 * math.pi
            for s in range(1, steps + 1):
                a = a0 + (a1 - a0) * s / steps
                cur.append((cx + r * math.cos(a), cy + r * math.sin(a)))
            x, y = nx, ny
    if cur:
        polys.append(cur)
    return polys


def render(layers, size=432, view=108, bg=((6, 14, 26), (18, 48, 73))):
    scale = size / view
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # radial navy background
    base = Image.new("RGB", (size, size), bg[1])
    px = base.load()
    cx, cy, rad = size * 0.5, size * 0.42, size * 0.68
    for yy in range(size):
        for xx in range(0, size):
            t = min(1.0, math.hypot(xx - cx, yy - cy) / rad)
            px[xx, yy] = (
                int(bg[1][0] + (bg[0][0] - bg[1][0]) * t),
                int(bg[1][1] + (bg[0][1] - bg[1][1]) * t),
                int(bg[1][2] + (bg[0][2] - bg[1][2]) * t),
            )
    img.paste(base, (0, 0))
    draw = ImageDraw.Draw(img, "RGBA")
    for d, color, width, alpha in layers:
        rgb = tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))
        w = max(1, int(round(width * scale)))
        for poly in flatten(d):
            pts = [(px * scale, py * scale) for px, py in poly]
            if len(pts) < 2:
                continue
            draw.line(pts, fill=rgb + (int(255 * alpha),), width=w, joint="curve")
            r = w / 2.0
            for (px0, py0) in (pts[0], pts[-1]):
                draw.ellipse([px0 - r, py0 - r, px0 + r, py0 + r], fill=rgb + (int(255 * alpha),))
    return img
