#!/usr/bin/env python3
"""Build the GameLingoPro launcher mark as a VectorDrawable and preview it."""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from render_paths import render  # noqa: E402

OUT_VEC = "app/src/main/res/drawable/ic_launcher_foreground.xml"
PREVIEW = "../files/icon-vector-preview.png"

WARM = "#FFB259"
COOL = "#3BE3F2"
COOL_SOFT = "#9BEEF7"


def bubble(x0, y0, x1, y1, r, tail):
    tx1, ty1, tx2, ty2 = tail
    return (
        f"M{x0 + r},{y0} H{x1 - r} A{r},{r} 0 0 1 {x1},{y0 + r} V{y1 - r} "
        f"A{r},{r} 0 0 1 {x1 - r},{y1} H{tx1} L{tx2},{ty2} L{tx1},{ty1} "
        f"A{r},{r} 0 0 1 {x0},{y1 - r} V{y0 + r} A{r},{r} 0 0 1 {x0 + r},{y0} Z"
    )


def shapes():
    left_bubble = bubble(17, 14, 52, 43, 10, (38, 43, 23, 57))
    right_bubble = bubble(56, 14, 91, 43, 10, (84, 43, 70, 57))
    # G: almost-closed ring with the bar running into the middle
    letter_g = "M44,27.5 A7.5,7.5 0 1 0 44,40.5 L44,35 H38.5"
    letter_a = "M59.5,41 L64.5,26.5 L69.5,41 M61.7,35.6 H67.3"
    pad = (
        "M28,51 H80 A12,12 0 0 1 91,62.5 C91,67.5 86.5,70.5 82.5,70.5 "
        "C79,70.5 76.5,68.5 74,65 H34 C31.5,68.5 29,70.5 25.5,70.5 "
        "C21.5,70.5 17,67.5 17,62.5 A12,12 0 0 1 28,51 Z"
    )
    dpad = "M29,56.5 V65.5 M24.5,61 H33.5"
    stick_left = "M43,61 m-3.6,0 a3.6,3.6 0 1,0 7.2,0 a3.6,3.6 0 1,0 -7.2,0"
    stick_bar = "M48.5,61 H59.5"
    stick_right = "M65,61 m-3.6,0 a3.6,3.6 0 1,0 7.2,0 a3.6,3.6 0 1,0 -7.2,0"
    dots = ("M74,57.5 m-1.4,0 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0"
            " M79.5,57.5 m-1.4,0 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0"
            " M74,63.5 m-1.4,0 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0"
            " M79.5,63.5 m-1.4,0 a1.4,1.4 0 1,0 2.8,0 a1.4,1.4 0 1,0 -2.8,0")
    chev = "M42,78 L54,86.5 L66,78 M46,83.5 L54,90 L62,83.5"

    def glow(d, color, width, alpha):
        out = []
        for mul, a in ((3.0, 0.10), (2.1, 0.16), (1.5, 0.26)):
            out.append((d, color, width * mul, a))
        out.append((d, color, width, alpha))
        return out

    layers = []
    layers += glow(left_bubble, WARM, 1.7, 0.95)
    layers += glow(right_bubble, COOL, 1.7, 0.95)
    layers += glow(letter_g, "#FFE7C6", 1.7, 0.95)
    layers += glow(letter_a, "#D9F9FF", 1.7, 0.95)
    layers += glow(pad, COOL, 1.6, 0.9)
    layers += glow(dpad, WARM, 1.8, 0.95)
    layers += glow(stick_left, WARM, 1.6, 0.9)
    layers += glow(stick_right, COOL, 1.6, 0.9)
    layers += glow(dots, COOL, 1.3, 0.85)
    layers += glow(chev, COOL, 1.5, 0.85)
    layers += [(stick_bar, WARM, 1.3, 0.35)]
    return layers


def write_vector(layers):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="108dp"',
             '    android:height="108dp"',
             '    android:viewportWidth="108"',
             '    android:viewportHeight="108">']
    for d, color, width, alpha in layers:
        lines.append('    <path')
        lines.append(f'        android:pathData="{d}"')
        lines.append(f'        android:strokeColor="{color}"')
        lines.append(f'        android:strokeWidth="{width:.2f}"')
        lines.append(f'        android:strokeAlpha="{alpha:.2f}"')
        lines.append('        android:strokeLineCap="round"')
        lines.append('        android:strokeLineJoin="round" />')
    lines.append('</vector>')
    os.makedirs(os.path.dirname(OUT_VEC), exist_ok=True)
    open(OUT_VEC, "w").write("\n".join(lines) + "\n")


if __name__ == "__main__":
    layers = shapes()
    write_vector(layers)
    img = render(layers, size=432)
    img.save(PREVIEW)
    print("mark written")
