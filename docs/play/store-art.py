"""Chompy's Play graphics: the 512 px icon, the 1024x500 feature graphic, the six phone screenshots and a
1920x1080 promo-video backdrop; and the feature graphic again at 1280x640, as GitHub's social preview.

    python3 docs/play/store-art.py      # from the repository's root; needs Pillow

The icon, feature graphic and screenshots go in fastlane/metadata/android/en-US/images, the backdrop in
build/demo for the promo video (docs/demo/README.md). The screenshots frame the raw 1080x1920 captures in
docs/play/shots, under a headline each, so a new recording only needs this run again.
"""
import math
import os
import random
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

repo = os.getcwd()
out_listing, out_build = f"{repo}/fastlane/metadata/android/en-US/images", f"{repo}/build/demo"
shots_in, shots_out = f"{repo}/docs/play/shots", f"{out_listing}/phoneScreenshots"
os.makedirs(out_build, exist_ok=True)
os.makedirs(shots_out, exist_ok=True)
PLUM = (0x17, 0x12, 0x1F, 255)
ORANGE, MAGENTA, VIOLET, AMBER = (0xE6, 0x68, 0x3D), (0xE0, 0x4C, 0x9A), (0xB0, 0x4C, 0xE0), (0xFF, 0xA5, 0x2E)
PEACH, MUTED = (0xF2, 0xC6, 0xB4, 255), (0xC9, 0xBE, 0xD9, 255)
BOLD = f"{repo}/app/src/main/res/font/jetbrains_mono_bold.ttf"
REG = f"{repo}/app/src/main/res/font/jetbrains_mono.ttf"
head = Image.open(f"{repo}/art/mascot/head2-gradient.png").convert("RGBA")
body = Image.open(f"{repo}/art/mascot/full-gradient.png").convert("RGBA")
logo = Image.open(f"{repo}/art/preview/logo-B.png").convert("RGBA")


def save(im, path):
    """24-bit PNG, as Play asks, with no metadata."""
    im.convert("RGB").save(path)


# Icon: the launcher's art, full bleed. Play rounds the corners itself.
icon = Image.new("RGBA", logo.size, PLUM)
icon.alpha_composite(logo)
save(icon, f"{out_listing}/icon.png")


def glow(size, centre, radius, colour, alpha):
    """A soft disc of sunset light."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = centre
    d.ellipse((x - radius, y - radius, x + radius, y + radius), fill=colour + (alpha,))
    return layer.filter(ImageFilter.GaussianBlur(radius * 0.45))


def fit(im, height):
    box = im.getbbox()
    im = im.crop(box)
    return im.resize((round(im.width * height / im.height), height), Image.LANCZOS)


def sunset(size):
    """The sunset gradient, amber to orange to magenta, left to right."""
    stops = [(0.0, AMBER), (0.45, (0xFF, 0x7A, 0x45)), (1.0, (0xF2, 0x5C, 0xA8))]
    strip = Image.new("RGB", (256, 1))
    for i in range(256):
        t = i / 255
        for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
            if t0 <= t <= t1:
                k = (t - t0) / (t1 - t0)
                strip.putpixel((i, 0), tuple(round(a + (b - a) * k) for a, b in zip(c0, c1)))
                break
    return strip.resize(size, Image.BILINEAR).convert("RGBA")


def gradient_text(canvas, xy, text, font):
    """Text filled with the sunset gradient."""
    x, y = xy
    left, top, right, bottom = font.getbbox(text)
    mask = Image.new("L", (right, bottom), 0)
    ImageDraw.Draw(mask).text((0, 0), text, font=font, fill=255)
    fill = sunset(mask.size)
    fill.putalpha(mask)
    canvas.alpha_composite(fill, (round(x), round(y)))


def shadow(size, box, radius, blur, alpha, offset=(0, 0)):
    """The soft shadow of a rounded rectangle."""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    x0, y0, x1, y1 = box
    dx, dy = offset
    ImageDraw.Draw(layer).rounded_rectangle((x0 + dx, y0 + dy, x1 + dx, y1 + dy), radius, fill=(6, 3, 10, alpha))
    return layer.filter(ImageFilter.GaussianBlur(blur))


def rounded(im, radius):
    """The image with its corners rounded off."""
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(ImageChops.multiply(out.getchannel("A"), mask))
    return out


def phone(capture, width):
    """The capture in a plain dark phone: thin bezel, rounded corners, a punch-hole camera, side keys."""
    bezel = max(5, round(width * 0.018))
    screen_w = width - 2 * bezel
    screen = capture.convert("RGB").resize((screen_w, round(capture.height * screen_w / capture.width)), Image.LANCZOS)
    height = screen.height + 2 * bezel
    keys = max(2, round(width * 0.006))
    out = Image.new("RGBA", (width + 2 * keys, height), (0, 0, 0, 0))
    d = ImageDraw.Draw(out)
    # Power and volume keys on the right edge.
    for top, length in ((0.20, 0.08), (0.32, 0.13)):
        d.rounded_rectangle((width, round(height * top), width + 2 * keys - 1, round(height * (top + length))),
                            keys, fill=(0x2A, 0x24, 0x33, 255))
    outer_r = round(width * 0.085)
    rim = max(1, round(width * 0.003))
    d.rounded_rectangle((0, 0, width - 1, height - 1), outer_r, fill=(0x4E, 0x43, 0x5E, 255))
    d.rounded_rectangle((rim, rim, width - 1 - rim, height - 1 - rim), outer_r - rim, fill=(0x0B, 0x09, 0x0F, 255))
    out.alpha_composite(rounded(screen, outer_r - bezel), (bezel, bezel))
    cam = max(3, round(width * 0.011))
    cx, cy = width // 2, bezel + round(width * 0.03)
    d.ellipse((cx - cam, cy - cam, cx + cam, cy + cam), fill=(0x05, 0x04, 0x07, 255))
    return out, bezel, screen_w / capture.width


# The phone screenshots, 1080x1920, as Play wants them for promotion (9:16, 1080 px at least). Each is a
# headline of two lines, the second in the sunset's colours, a line saying more, and the capture in a phone
# that runs off the bottom edge. "lift" raises one part of the capture out of the phone, a little larger:
# its box in the capture, the corner radius there, and how much larger. The boxes fit these captures, so
# check them after a new recording.
SHOTS = [
    ("1_board", "Every agent,", "on one board",
     "Claude Code, Codex and Gemini on every Mac, ordered by what needs you",
     ((42, 280, 1038, 772), 44, 1.07)),
    ("2_notification", "Answer from", "the notification",
     "Pick an agent's answer in the shade, without opening the app",
     ((42, 568, 1038, 891), 44, 1.08)),
    ("3_permission", "Approve without", "the laptop",
     "Permission prompts in the server's own terminal, one tap each",
     ((21, 868, 1059, 1563), 46, 1.1)),
    ("4_note", "Read what", "Claude flags",
     "Claude's “You should know” notes, in full",
     ((21, 792, 1059, 1563), 46, 1.1)),
    ("5_claude_controls", "Claude's controls", "at hand",
     "Effort, model and commands, right above the keyboard",
     None),
    ("6_themes", "Themes for", "day and night",
     "15 of Chompy's own, each with terminal colors to match",
     ((42, 646, 1038, 794), 40, 1.08)),
]
W, H = 1080, 1920
HEAD, SUB = ImageFont.truetype(BOLD, 84), ImageFont.truetype(REG, 38)
MARGIN = 72
PHONE_W, PHONE_Y = 860, 470


def wrap(text, font, width):
    """One line if it fits, else two of about the same length."""
    if font.getlength(text) <= width:
        return [text]
    words = text.split()
    splits = [(" ".join(words[:i]), " ".join(words[i:])) for i in range(1, len(words))]
    best = min(splits, key=lambda pair: max(font.getlength(line) for line in pair))
    assert max(font.getlength(line) for line in best) <= width, f"too long for two lines: {text}"
    return list(best)


def backdrop():
    """One wide background behind all six, so the glow runs on from one screenshot to the next."""
    n, s = len(SHOTS), 4  # the glows are drawn at a quarter of the size: they're blurred anyway
    size = (W * n // s, H // s)
    bg = Image.new("RGBA", size, PLUM)
    top = Image.new("RGBA", size, (0x22, 0x18, 0x30, 255))
    fade = Image.linear_gradient("L").resize(size).point(lambda v: 255 - v)
    bg = Image.composite(top, bg, fade)
    for i in range(n):
        cx = (i * W + W / 2) / s
        wave = math.sin(i * 1.3)
        bg.alpha_composite(glow(size, (cx + 260 / s * wave, 1300 / s), 560 / s, ORANGE, 92))
        bg.alpha_composite(glow(size, (cx - 300 / s * wave, 820 / s), 470 / s, VIOLET, 74))
        bg.alpha_composite(glow(size, ((i + 1) * W / s, 1500 / s), 380 / s, MAGENTA, 60))
    bg = bg.resize((W * n, H), Image.BICUBIC)
    # A little grain, so the dark gradients don't band. Seeded, so the same captures make the same files.
    noise = Image.frombytes("L", (W * n, H), random.Random(7).randbytes(W * n * H))
    grain = noise.point(lambda v: 128 + (v - 128) // 43)
    return ImageChops.add(bg.convert("RGB"), Image.merge("RGB", (grain,) * 3), offset=-128).convert("RGBA")


def screenshot(panel, name, line1, line2, subline, lift):
    canvas = panel.copy()
    d = ImageDraw.Draw(canvas)
    y = 104
    for text, colour in ((line1, (255, 255, 255, 255)), (line2, None)):
        x = (W - HEAD.getlength(text)) / 2
        if colour:
            d.text((x, y), text, font=HEAD, fill=colour)
        else:
            gradient_text(canvas, (x, y), text, HEAD)
        y += 100
    y += 22
    for line in wrap(subline, SUB, W - 2 * MARGIN - 40):
        d.text(((W - SUB.getlength(line)) / 2, y), line, font=SUB, fill=MUTED)
        y += 50
    assert y <= PHONE_Y - 20, f"{name}: the words run into the phone"

    capture = Image.open(f"{shots_in}/{name}.png").convert("RGB")
    assert capture.size == (W, H), f"{name}: the capture is {capture.size}, not 1080x1920"
    device, bezel, scale = phone(capture, PHONE_W)
    px = (W - PHONE_W) // 2
    box = (px, PHONE_Y, px + PHONE_W, PHONE_Y + device.height)
    canvas.alpha_composite(shadow(canvas.size, box, 80, 46, 210, (0, 34)))
    canvas.alpha_composite(device, (px, PHONE_Y))
    if lift:
        (x0, y0, x1, y1), radius, grow = lift
        part = capture.crop((x0, y0, x1, y1))
        w, h = round(part.width * scale * grow), round(part.height * scale * grow)
        part = rounded(part.resize((w, h), Image.LANCZOS), round(radius * scale * grow))
        # Centred where it sits in the phone.
        cx = px + bezel + (x0 + x1) / 2 * scale
        cy = PHONE_Y + bezel + (y0 + y1) / 2 * scale
        lx, ly = round(cx - w / 2), round(cy - h / 2)
        canvas.alpha_composite(shadow(canvas.size, (lx, ly, lx + w, ly + h), 30, 30, 230, (0, 22)))
        canvas.alpha_composite(part, (lx, ly))
        rim = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        ImageDraw.Draw(rim).rounded_rectangle((lx, ly, lx + w - 1, ly + h - 1), round(radius * scale * grow),
                                              outline=(0xFF, 0xFF, 0xFF, 40), width=2)
        canvas.alpha_composite(rim)
    save(canvas, f"{shots_out}/{name}.png")


wide = backdrop()
for i, shot in enumerate(SHOTS):
    screenshot(wide.crop((i * W, 0, (i + 1) * W, H)), *shot)

def feature(size, scale):
    """The feature graphic: the name and what it is on the left, a board of agents in a phone on the right, one in
    each state with nothing to answer (docs/demo/still.sh), and Chompy beside it, off the screen. Laid out at
    1024x500 and drawn at [scale]. Play may crop the edges and put a play button in the middle, so both keep clear
    of them."""
    w, h = size
    def px(v):
        return round(v * scale)
    fg = Image.new("RGBA", size, PLUM)
    fg.alpha_composite(glow(size, (px(760), px(320)), px(250), ORANGE, 96))
    fg.alpha_composite(glow(size, (px(860), px(150)), px(190), VIOLET, 84))
    fg.alpha_composite(glow(size, (px(640), px(400)), px(200), MAGENTA, 70))
    fg.alpha_composite(glow(size, (px(160), h + px(20)), px(220), MAGENTA, 40))
    # The phone runs off the bottom edge, below the board's cards and above its buttons, wherever the graphic is
    # taller than its layout.
    board, _, _ = phone(Image.open(f"{shots_in}/feature_board.png"), px(300))
    fx, fy = w - px(86 + 300), px(38) + h - px(500)
    fg.alpha_composite(shadow(size, (fx, fy, fx + px(300), fy + board.height), px(28), px(18), 210, (0, px(10))))
    fg.alpha_composite(board, (fx, fy))
    # Standing at the phone's side, below where a play button would go.
    mascot = fit(body, px(186))
    fg.alpha_composite(mascot, (fx - mascot.width + px(26), h - mascot.height - px(12)))
    d = ImageDraw.Draw(fg)
    d.text((px(70), px(104)), "Chompy", font=ImageFont.truetype(BOLD, px(100)), fill=(255, 255, 255, 255))
    tag = ImageFont.truetype(BOLD, px(32))
    gradient_text(fg, (px(74), px(250)), "Your coding agents,", tag)
    gradient_text(fg, (px(74), px(296)), "on your phone.", tag)
    d.text((px(75), px(362)), "A client for Rex", font=ImageFont.truetype(REG, px(24)), fill=MUTED)
    return fg


save(feature((1024, 500), 1), f"{out_listing}/featureGraphic.png")
# GitHub's social preview, 1280x640, the same a quarter larger: Settings › Social preview takes it.
save(feature((1280, 640), 1.25), f"{repo}/docs/media/social-preview.png")


def text_block(canvas, x, y, scale):
    d = ImageDraw.Draw(canvas)
    d.text((x, y), "Chompy", font=ImageFont.truetype(BOLD, round(104 * scale)), fill=(255, 255, 255, 255))
    y += round(140 * scale)
    tag = ImageFont.truetype(REG, round(31 * scale))
    for line in ("Your coding agents,", "on your phone."):
        d.text((x, y), line, font=tag, fill=PEACH)
        y += round(44 * scale)
    y += round(22 * scale)
    d.text((x, y), "An unofficial client for Rex", font=ImageFont.truetype(REG, round(21 * scale)), fill=(0x9A, 0x8F, 0xAD, 255))


# Promo video backdrop, 1920x1080: the words and the head on the left; the phone's video goes on the right,
# 486x1080 at x=1250.
bg = Image.new("RGBA", (1920, 1080), PLUM)
bg.alpha_composite(glow(bg.size, (1493, 540), 520, ORANGE, 46))
bg.alpha_composite(glow(bg.size, (1493, 380), 380, VIOLET, 40))
h = fit(head, 300)
bg.alpha_composite(h, (150, 150))
text_block(bg, 150, 500, 1.55)
save(bg, f"{out_build}/promo-backdrop.png")
