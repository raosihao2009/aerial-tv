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
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

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

    icon = Image.open(ICON).convert("RGBA").resize((int(icon_side), int(icon_side)), Image.LANCZOS)
    radius = W * CARD_CORNER_RATIO
    mask = Image.new("L", (int(icon_side) * 4, int(icon_side) * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, int(icon_side) * 4 - 1, int(icon_side) * 4 - 1), radius=radius * 4, fill=255
    )
    icon.putalpha(mask.resize((int(icon_side), int(icon_side)), Image.LANCZOS))
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
