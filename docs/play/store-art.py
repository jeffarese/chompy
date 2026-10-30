"""Chompy's Play graphics: the 512 px icon, the 1024x500 feature graphic and a 1920x1080 promo-video backdrop.

    python3 docs/play/store-art.py      # from the repository's root; needs Pillow

The icon and feature graphic go in fastlane/metadata/android/en-US/images, the backdrop in build/demo for
the promo video (docs/demo/README.md).
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

repo = os.getcwd()
out_listing, out_build = f"{repo}/fastlane/metadata/android/en-US/images", f"{repo}/build/demo"
os.makedirs(out_build, exist_ok=True)
PLUM = (0x17, 0x12, 0x1F, 255)
BOLD = f"{repo}/app/src/main/res/font/jetbrains_mono_bold.ttf"
REG = f"{repo}/app/src/main/res/font/jetbrains_mono.ttf"
head = Image.open(f"{repo}/art/mascot/head2-gradient.png").convert("RGBA")
body = Image.open(f"{repo}/art/mascot/full-gradient.png").convert("RGBA")
logo = Image.open(f"{repo}/art/preview/logo-B.png").convert("RGBA")

# Icon: the launcher's art, full bleed. Play rounds the corners itself.
icon = Image.new("RGBA", logo.size, PLUM)
icon.alpha_composite(logo)
icon.convert("RGB").save(f"{out_listing}/icon.png")

def glow(size, centre, radius, colour, alpha):
    """A soft disc of sunset light behind the mascot."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = centre
    d.ellipse((x - radius, y - radius, x + radius, y + radius), fill=colour + (alpha,))
    return layer.filter(ImageFilter.GaussianBlur(radius * 0.45))

def fit(im, height):
    box = im.getbbox()
    im = im.crop(box)
    return im.resize((round(im.width * height / im.height), height), Image.LANCZOS)

def text_block(canvas, x, y, scale):
    d = ImageDraw.Draw(canvas)
    d.text((x, y), "Chompy", font=ImageFont.truetype(BOLD, round(104 * scale)), fill=(255, 255, 255, 255))
    y += round(140 * scale)
    tag = ImageFont.truetype(REG, round(31 * scale))
    for line in ("Your coding agents,", "on your phone."):
        d.text((x, y), line, font=tag, fill=(0xF2, 0xC6, 0xB4, 255))
        y += round(44 * scale)
    y += round(22 * scale)
    d.text((x, y), "An unofficial client for Rex", font=ImageFont.truetype(REG, round(21 * scale)), fill=(0x9A, 0x8F, 0xAD, 255))

# Feature graphic, 1024x500: the words on the left, Chompy standing on the right.
fg = Image.new("RGBA", (1024, 500), PLUM)
fg.alpha_composite(glow(fg.size, (790, 270), 230, (0xE6, 0x68, 0x3D), 70))
fg.alpha_composite(glow(fg.size, (840, 200), 170, (0xB0, 0x4C, 0xE0), 60))
mascot = fit(body, 440)
fg.alpha_composite(mascot, (1024 - mascot.width - 70, 500 - mascot.height - 22))
text_block(fg, 72, 92, 1.0)
fg.convert("RGB").save(f"{out_listing}/featureGraphic.png")

# Promo video backdrop, 1920x1080: the words and the head on the left; the phone's video goes on the right,
# 486x1080 at x=1250.
bg = Image.new("RGBA", (1920, 1080), PLUM)
bg.alpha_composite(glow(bg.size, (1493, 540), 520, (0xE6, 0x68, 0x3D), 46))
bg.alpha_composite(glow(bg.size, (1493, 380), 380, (0xB0, 0x4C, 0xE0), 40))
h = fit(head, 300)
bg.alpha_composite(h, (150, 150))
text_block(bg, 150, 500, 1.55)
bg.convert("RGB").save(f"{out_build}/promo-backdrop.png")
