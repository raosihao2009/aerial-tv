#!/usr/bin/env python3
"""Renders the cold-start clip, assets/intro.mp4, from the same numbers SplashArt draws with.

    python3 tools/render-intro.py

The first frame has to look exactly like the still the window background shows while the content
view is being built (SplashArt in ui/SplashVeil.kt): black, the rounded launcher icon, and the
white word in Inter, laid out left to right on the screen's centre line. The only motion is a
faint gleam that sweeps across the mark and is gone again by the last frame, so the clip loops
seamlessly and can be cut into at any point.

The reveal itself is not in here: it needs the live desktop behind it, so the app draws that
(ui/SplashVeil.kt) once this clip has played.
"""
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# --- continuous (Apple/Figma) corners, the same path SmoothCorners.kt draws -------------------
# The app cuts the launcher icon with smoothCut(), i.e. Apple's continuous corner at smoothing 0.6.
# A plain rounded_rectangle here would give the clip a bigger, circular corner, and the icon would
# change its rounding between the clip and the view behind it.
APPLE_SMOOTHING = 0.6


def _corner(radius, smoothing, budget):
    """SmoothCorner in ui/components/SmoothCorners.kt, as numbers."""
    r = min(radius, budget)
    sm = smoothing
    if radius > 0 and (1 + sm) * radius > budget:
        sm = max(0.0, min(sm, budget / radius - 1))
    p = min((1 + sm) * r, budget)
    arc = math.pi / 2 * (1 - sm)
    arc_len = math.sin(arc / 2) * r * math.sqrt(2)
    alpha = (math.pi / 2 - arc) / 2
    p3p4 = r * math.tan(alpha / 2)
    beta = math.pi / 4 * sm
    c = p3p4 * math.cos(beta)
    d = c * math.tan(beta)
    b = (p - arc_len - c - d) / 3
    a = 2 * b
    return r, p, arc, a, b, c, d


def _cubic(p0, p1, p2, p3, steps=20):
    out = []
    for i in range(1, steps + 1):
        t = i / steps
        mt = 1 - t
        x = mt ** 3 * p0[0] + 3 * mt ** 2 * t * p1[0] + 3 * mt * t ** 2 * p2[0] + t ** 3 * p3[0]
        y = mt ** 3 * p0[1] + 3 * mt ** 2 * t * p1[1] + 3 * mt * t ** 2 * p2[1] + t ** 3 * p3[1]
        out.append((x, y))
    return out


def _round_corner(points, vertex, u, v, geo):
    r, p, arc, a, b, c, d = geo

    def at(x, y):
        return (vertex[0] + u[0] * x + v[0] * y, vertex[1] + u[1] * x + v[1] * y)

    if r <= 0:
        points.append(vertex)
        return
    points.append(at(-p, 0))

    def cubic(x1, y1, x2, y2, x3, y3):
        points.extend(_cubic(points[-1], at(x1, y1), at(x2, y2), at(x3, y3)))

    cubic(-p + a, 0, -p + a + b, 0, -p + a + b + c, d)
    f0 = -math.pi / 4 - arc / 2
    f1 = -math.pi / 4 + arc / 2
    k = 4 / 3 * math.tan(arc / 4) * r
    e0 = (-r + r * math.cos(f0), r + r * math.sin(f0))
    e1 = (-r + r * math.cos(f1), r + r * math.sin(f1))
    cubic(e0[0] - k * math.sin(f0), e0[1] + k * math.cos(f0),
          e1[0] + k * math.sin(f1), e1[1] - k * math.cos(f1), e1[0], e1[1])
    cubic(0, p - a - b, 0, p - a, 0, p)


def brand_icon(side, radius):
    """The launcher icon as the app draws it: the adaptive icon's two vectors (gradient, arc), full
    bleed, cut to the same continuous corner the runtime uses.

    docs/brand/icon.png carries its own transparent margin, so using it (and replacing its alpha) left
    a black wedge inside the cut and made the clip's icon smaller and rounder than the view's.
    """
    ss = 4  # supersample, so the glyph's stroke stays clean
    n = side * ss
    # android:startX/Y=18 endX/Y=90 of a 108 viewport: a linear gradient along the diagonal.
    img = Image.new("RGB", (n, n))
    px = img.load()
    start = (0x2F, 0xD0, 0xA5)
    end = (0x5B, 0x5B, 0xF0)
    k = n / 108.0
    for y in range(n):
        for x in range(n):
            t = (x / k + y / k - 36.0) / 144.0
            t = 0.0 if t < 0 else (1.0 if t > 1 else t)
            px[x, y] = (
                int(start[0] + (end[0] - start[0]) * t),
                int(start[1] + (end[1] - start[1]) * t),
                int(start[2] + (end[2] - start[2]) * t),
            )
    img = img.convert("RGBA")
    # M33.75,65.25 c6.75,-13.5 13.5,-20.25 20.25,-20.25 s13.5,6.75 20.25,20.25, stroked 5.4
    pts = []
    p0 = (33.75, 65.25)
    pts += _cubic(p0, (40.5, 51.75), (47.25, 45.0), (54.0, 45.0), 24)
    pts += _cubic((54.0, 45.0), (60.75, 45.0), (67.5, 51.75), (74.25, 65.25), 24)
    draw = ImageDraw.Draw(img)
    draw.line([(x * ss, y * ss) for x, y in pts], fill=(255, 255, 255, 255), width=int(round(5.4 * ss)), joint="curve")
    for x, y in (pts[0], pts[-1]):
        r = 5.4 / 2
        draw.ellipse(((x - r) * ss, (y - r) * ss, (x + r) * ss, (y + r) * ss), fill=(255, 255, 255, 255))
    img = img.resize((side, side), Image.LANCZOS)
    img.putalpha(smooth_rect_mask(side, side, radius, APPLE_SMOOTHING))
    return img

def smooth_rect_mask(w, h, radius, smoothing=APPLE_SMOOTHING, supersample=4):
    """White inside a w×h rect with continuous corners, clear outside (an L-mode mask)."""
    budget = min(w, h) / 2
    geo = _corner(radius, smoothing, budget)
    pts = []
    _round_corner(pts, (w, 0), (1, 0), (0, 1), geo)           # top right
    _round_corner(pts, (w, h), (0, 1), (-1, 0), geo)          # bottom right
    _round_corner(pts, (0, h), (-1, 0), (0, -1), geo)         # bottom left
    _round_corner(pts, (0, 0), (0, -1), (1, 0), geo)          # top left
    big = [(x * supersample, y * supersample) for x, y in pts]
    mask = Image.new("L", (w * supersample, h * supersample), 0)
    ImageDraw.Draw(mask).polygon(big, fill=255)
    return mask.resize((w, h), Image.LANCZOS)


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON = os.path.join(ROOT, "docs/brand/icon.png")
FONT = os.path.join(ROOT, "app/src/main/res/font/inter.ttf")
OUT = os.path.join(ROOT, "app/src/main/assets/intro.mp4")

W, H = 1920, 1080
FPS = 30
SECONDS = 2.0
FRAMES = int(FPS * SECONDS)

# The numbers SplashArt uses; keep the two in step.
CARD_CORNER_RATIO = 1 / 80


def smooth(a, b, x):
    t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


def mark():
    """The icon and the word, as an RGBA layer the size of the screen."""
    text_size = min(W * 0.082, H * 0.175)
    icon_side = text_size
    gap = text_size * 0.42
    spacing = text_size * 0.04
    font = ImageFont.truetype(FONT, int(round(text_size)))
    ascent, descent = font.getmetrics()
    text = "Aerial"
    advances = [font.getlength(ch) + spacing for ch in text]
    text_w = sum(advances)  # Android counts the trailing letter spacing too
    icon_room = icon_side + gap
    left = W / 2 - (icon_room + text_w) / 2

    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    radius = W * CARD_CORNER_RATIO
    side = int(icon_side)
    icon = brand_icon(side, radius)
    top = (H - icon_side) / 2
    layer.alpha_composite(icon, (int(round(left)), int(round(top))))

    draw = ImageDraw.Draw(layer)
    baseline = H / 2 + (ascent - descent) / 2
    x = left + icon_room
    for ch, adv in zip(text, advances):
        draw.text((x, baseline), ch, font=font, fill=(255, 255, 255, 255), anchor="ls")
        x += adv
    return layer


def gleam(t):
    """A soft diagonal band of white, at [t] through the camera's travel."""
    band = np.zeros((H, W), np.float32)
    x0 = -0.25 * W + t * 1.5 * W
    width = 0.06 * W
    xs = np.arange(W, dtype=np.float32) - x0
    # Diagonal band: rows shift the band so it leans the way light would cross the mark.
    ys = np.arange(H, dtype=np.float32)
    for y in range(0, H, 4):
        shift = (y - H / 2) * 0.28
        d = (xs - shift) / width
        band[y:y + 4] = np.exp(-(d * d) * 4)[None, :]
    return band


def frames():
    base = mark()
    a = np.asarray(base).astype(np.float32)
    rgb, alpha = a[:, :, :3], a[:, :, 3:] / 255.0
    glow = np.asarray(base.filter(ImageFilter.GaussianBlur(W / 90))).astype(np.float32)[:, :, :3]

    for i in range(FRAMES):
        t = i / FRAMES
        # The gleam is in and out well inside the clip so frame 0 and the last frame are identical.
        env = smooth(0.0, 0.18, t) * (1 - smooth(0.82, 1.0, t))
        sweep = smooth(0.0, 1.0, t) * 1.0
        lit = gleam(sweep) * env * 0.55
        pixels = rgb + (255.0 * lit)[:, :, None] * alpha
        # A barely-there bloom that breathes once, under the mark.
        b = 0.05 + 0.02 * np.sin(np.pi * t)
        pixels = np.clip(pixels + glow * b, 0, 255)
        yield np.dstack([pixels * alpha, np.full((H, W, 1), 255.0)]).astype(np.uint8)[:, :, :3]


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    p = subprocess.Popen(
        [
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
            "-c:v", "libx264", "-profile:v", "baseline", "-level", "3.1", "-pix_fmt", "yuv420p",
            "-crf", "20", "-g", str(FRAMES), "-movflags", "+faststart", OUT,
        ],
        stdin=subprocess.PIPE,
    )
    for frame in frames():
        p.stdin.write(frame.tobytes())
    p.stdin.close()
    if p.wait() != 0:
        sys.exit("ffmpeg failed")
    print(OUT, os.path.getsize(OUT) // 1024, "KB")


if __name__ == "__main__":
    main()
