#!/usr/bin/env python3
"""Generates BootMakar's launcher foreground and leanback banner.

Uses Pillow + DejaVu Sans Bold (both present in the build VM) to render
real text: bold "BM" for the adaptive-icon foreground, and the full name
"BootMakar" for the leanback banner. Run from the project root:

    python3 tools/make_images.py

Outputs:
    app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png (432x432)
    app/src/main/res/drawable/tv_banner.png (1280x720)

The foreground is drawn on transparency: the adaptive-icon background
color (@color/bootmakar_launcher_background) shows through. Letters are
kept inside the center safe zone so launcher masks never clip them.
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
BG = (12, 32, 49, 255)          # #0C2031, same as the icon background
WHITE = (255, 255, 255, 255)
GOLD = (255, 193, 7, 255)


def fit_font(draw, text, max_width, max_height, start=200):
    """Largest DejaVu Bold size that fits text inside max_width x max_height."""
    size = start
    while size > 10:
        font = ImageFont.truetype(FONT_BOLD, size)
        box = draw.textbbox((0, 0), text, font=font)
        w, h = box[2] - box[0], box[3] - box[1]
        if w <= max_width and h <= max_height:
            return font
        size -= 4
    return ImageFont.truetype(FONT_BOLD, size)


def draw_centered(draw, img, text, font, fill):
    box = draw.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    x = (img.width - w) // 2 - box[0]
    y = (img.height - h) // 2 - box[1]
    draw.text((x, y), text, font=font, fill=fill)


def make_foreground(path):
    img = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    # Safe zone for adaptive icons is the center 66%; keep "BM" inside it.
    font = fit_font(draw, "BM", 280, 200)
    draw_centered(draw, img, "BM", font, WHITE)
    img.save(path)
    print(f"wrote {path} (432x432, BM)")


def make_banner(path):
    img = Image.new("RGBA", (1280, 720), BG)
    draw = ImageDraw.Draw(img)
    # Gold accent bar on the left.
    draw.rectangle([0, 0, 24, 720], fill=GOLD)
    font = fit_font(draw, "BootMakar", 900, 200)
    draw_centered(draw, img, "BootMakar", font, WHITE)
    img.save(path)
    print(f"wrote {path} (1280x720, BootMakar)")


def main():
    if not os.path.exists(FONT_BOLD):
        print(f"missing font: {FONT_BOLD}", file=sys.stderr)
        return 1
    fg = os.path.join(ROOT, "app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png")
    banner = os.path.join(ROOT, "app/src/main/res/drawable/tv_banner.png")
    os.makedirs(os.path.dirname(fg), exist_ok=True)
    os.makedirs(os.path.dirname(banner), exist_ok=True)
    make_foreground(fg)
    make_banner(banner)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
