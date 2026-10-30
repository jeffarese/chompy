"""Chompy's themes, written to app/src/main/assets/themes.json.

    python3 art/themes/themes.py        # from the repository's root; needs nothing beyond Python

Each theme is a few colors chosen by hand: its canvas, its text, its accent and the six colors its terminals
show. Everything else comes from them. The greys are steps from the canvas toward the text, the app's
statuses are the terminal's colors (working blue, needs-you yellow, done green, failed red), and the 256-color
palette is a cube with the canvas, the six colors and the text at its corners, so programs that pick from it
match the theme, in a light one too.

A color that reads at less than 4.5:1 where it is drawn moves toward the text, keeping its hue, just far
enough to read: the statuses and the accent on the canvas and on a card, and the terminal's colors on its
background.
"""
import json
import math
import os

THEMES = [
    # id, name, dark, canvas, text, accent, then red, green, yellow, blue, magenta and cyan.
    ("cretaceous", "Cretaceous", True, "#1d1128", "#efe4f5", "#f25ca8",
     "#ff6b7a", "#9fd67a", "#ffc66b", "#8fa8ff", "#e78bf0", "#6fd9d2"),
    ("cycad", "Cycad", True, "#0e2117", "#dbeadf", "#f0a55c",
     "#f07c6e", "#8fd68a", "#e6cc6e", "#7fb4e8", "#d498d8", "#6ccfc0"),
    ("panthalassa", "Panthalassa", True, "#091a30", "#d5e5f3", "#4fd6e6",
     "#f2797a", "#6fd6a6", "#e8c873", "#6aa8f2", "#b79bf2", "#5ed3e0"),
    ("meteor-shower", "Meteor Shower", True, "#14173a", "#e6e8fb", "#ffd166",
     "#ff7b93", "#7fe0b0", "#ffb85c", "#8c9eff", "#c79bff", "#74d7f2"),
    ("lava-flow", "Lava Flow", True, "#1b0f0d", "#f3e3da", "#ff9a3c",
     "#ff6b5b", "#b8cf72", "#ffc857", "#8db1e0", "#e79ab5", "#82cfc3"),
    ("trilobite", "Trilobite", True, "#232a33", "#e1e6ec", "#b9a2ff",
     "#ef8585", "#8fd0a0", "#e8c77e", "#82aee6", "#c9a6e8", "#7fcad2"),
    ("ammonite", "Ammonite", True, "#2a2230", "#efe6f1", "#f5afc8",
     "#f48f8f", "#9fd6a9", "#efcf8f", "#9db4f2", "#e2a6e8", "#91d3d9"),
    ("obsidian", "Obsidian", True, "#0a0a0c", "#e8e8ea", "#ff5f87",
     "#ff6b6b", "#69db7c", "#ffd43b", "#5c9dff", "#da77f2", "#3bc9db"),
    ("glowworm", "Glowworm", True, "#061315", "#d0f5eb", "#7cf29c",
     "#ff6e86", "#7cf29c", "#e9f27c", "#6cc3ff", "#c38cff", "#5cf2e0"),

    ("sandstone", "Sandstone", False, "#f4ebde", "#2b2320", "#c2501c",
     "#c23b2f", "#3f7d3a", "#9a6b00", "#2d64a8", "#9c3f86", "#1f7a78"),
    ("chalk", "Chalk", False, "#fafbfc", "#1e2430", "#2f5bd3",
     "#c9302c", "#2e7d32", "#8f6a00", "#2f5bd3", "#8e3fbf", "#10798a"),
    ("fern", "Fern", False, "#e9f1e5", "#1d2a20", "#2c7a4b",
     "#b8403a", "#2c7a4b", "#876800", "#2f6795", "#8a4a8c", "#1f7a73"),
    ("wisteria", "Wisteria", False, "#f2edf9", "#26222e", "#7046c2",
     "#c23a54", "#3a7d52", "#8a6500", "#3d5fc2", "#7046c2", "#1e7488"),
    ("apricot", "Apricot", False, "#fdeee2", "#2e2421", "#b8335a",
     "#c0392b", "#4f7a2b", "#9a6200", "#3460a0", "#b03a7a", "#2b7a7a"),
    ("ice-age", "Ice Age", False, "#e6f1f6", "#172630", "#0d6c8a",
     "#c0392b", "#2f7a4a", "#8a6400", "#2f5fae", "#8a44a8", "#0d6c8a"),
]

READABLE = 4.5
# Over READABLE by this much, so a color still reads at READABLE once it is rounded to hex.
MARGIN = 0.04


def rgb(hex_):
    h = hex_.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def hexed(c):
    return "#" + "".join(f"{round(min(1, max(0, v)) * 255):02x}" for v in c)


def linear(v):
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def gamma(v):
    return 12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055


def luminance(c):
    r, g, b = (linear(v) for v in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def oklab(c):
    r, g, b = (linear(v) for v in c)
    l = (0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b) ** (1 / 3)
    m = (0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b) ** (1 / 3)
    s = (0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b) ** (1 / 3)
    return (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)


def from_oklab(lab):
    L, a, b = lab
    l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3
    lin = (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
           -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
           -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    return tuple(gamma(max(0.0, v)) for v in lin)


def in_gamut(lab):
    L, a, b = lab
    l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3
    lin = (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
           -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
           -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    return all(-1e-4 <= v <= 1 + 1e-4 for v in lin)


def with_lightness(c, L):
    """[c] at OKLab lightness [L], as saturated as sRGB lets it be there."""
    _, a, b = oklab(c)
    k = 1.0
    while k > 0 and not in_gamut((L, a * k, b * k)):
        k -= 0.02
    return from_oklab((L, a * max(k, 0), b * max(k, 0)))


def mix(a, b, t):
    """From [a] toward [b], in OKLab."""
    x, y = oklab(a), oklab(b)
    return from_oklab(tuple(p + (q - p) * t for p, q in zip(x, y)))


def readable(c, on, toward, ratio=READABLE):
    """[c] if it reads at [ratio] on each of [on], else the lightness nearest its own, on the way to [toward], that does."""
    ratio += MARGIN
    if min(contrast(c, o) for o in on) >= ratio:
        return c
    L0, L1 = oklab(c)[0], oklab(toward)[0]
    lo, hi = 0.0, 1.0
    for _ in range(30):
        f = (lo + hi) / 2
        if min(contrast(with_lightness(c, L0 + (L1 - L0) * f), o) for o in on) >= ratio:
            hi = f
        else:
            lo = f
    return with_lightness(c, L0 + (L1 - L0) * hi)


def theme(id_, name, dark, canvas, text, accent, *hues):
    bg, fg = rgb(canvas), rgb(text)
    # A card, as the app draws it (ui/Theme.kt paletteOf): the text read there must read on it too.
    card = mix(bg, fg, 0.055 if dark else 0.044)
    ui_on = [bg, card]
    red, green, yellow, blue, magenta, cyan = (readable(rgb(h), [bg], fg) for h in hues)
    if dark:
        bright = [readable(with_lightness(c, min(0.95, oklab(c)[0] + 0.07)), [bg], fg) for c in (red, green, yellow, blue, magenta, cyan)]
        black, bright_black = mix(bg, fg, 0.16), readable(mix(bg, fg, 0.42), [bg], fg, 3.5)
        white, bright_white = mix(bg, fg, 0.82), mix(fg, (1, 1, 1), 0.5)
    else:
        # Brighter means deeper here, as on a light canvas a lighter one would be harder to read.
        bright = [readable(with_lightness(c, oklab(c)[0] - 0.06), [bg], fg) for c in (red, green, yellow, blue, magenta, cyan)]
        black, bright_black = mix(fg, bg, 0.05), readable(mix(fg, bg, 0.35), [bg], fg)
        white, bright_white = readable(mix(fg, bg, 0.5), [bg], fg), readable(mix(fg, bg, 0.62), [bg], fg, 3.0)
    ansi = [black, red, green, yellow, blue, magenta, cyan, white, bright_black, *bright, bright_white]

    faint = readable(mix(bg, fg, 0.5), ui_on, fg)
    dim = readable(mix(bg, fg, 0.7), ui_on, fg, min(contrast(faint, o) for o in ui_on) * 1.25)
    acc = readable(rgb(accent), ui_on, fg)
    on_accent = bg if contrast(bg, acc) >= contrast(fg, acc) else fg
    ui = {
        "canvas": bg, "content": fg, "contentSecondary": dim, "contentTertiary": faint,
        "strokeSubtle": mix(bg, fg, 0.13), "accent": acc, "accentOnSolid": on_accent,
        "success": readable(green, ui_on, fg), "warning": readable(yellow, ui_on, fg),
        "danger": readable(red, ui_on, fg), "info": readable(blue, ui_on, fg),
    }
    for token in ("accent", "success", "warning", "danger", "info", "contentTertiary"):
        assert min(contrast(ui[token], o) for o in ui_on) >= READABLE - 0.01, (id_, token)
    assert contrast(on_accent, acc) >= READABLE - 0.01, (id_, "accentOnSolid")

    # The cube: the canvas, the six colors and the text at its corners, each step between them in OKLab.
    corners = {(0, 0, 0): bg, (1, 0, 0): red, (0, 1, 0): green, (0, 0, 1): blue,
               (1, 1, 0): yellow, (1, 0, 1): magenta, (0, 1, 1): cyan, (1, 1, 1): fg}
    lab = {k: oklab(v) for k, v in corners.items()}
    cube = []
    for r in range(6):
        for g in range(6):
            for b in range(6):
                x, y, z = r / 5, g / 5, b / 5
                point = [0.0, 0.0, 0.0]
                for (i, j, k), v in lab.items():
                    w = (x if i else 1 - x) * (y if j else 1 - y) * (z if k else 1 - z)
                    point = [p + w * q for p, q in zip(point, v)]
                cube.append(from_oklab(tuple(point)))
    greys = [mix(bg, fg, (i + 1) / 25) for i in range(24)]
    return {
        "id": id_, "name": name, "dark": dark,
        "ui": {k: hexed(v) for k, v in ui.items()},
        "terminal": {"background": hexed(bg), "foreground": hexed(fg), "cursor": hexed(acc),
                     "palette": [hexed(c) for c in ansi + cube + greys]},
    }


if __name__ == "__main__":
    themes = [theme(*t) for t in THEMES]
    assert len({t["id"] for t in themes}) == len(themes)
    out = os.path.join(os.getcwd(), "app/src/main/assets/themes.json")
    with open(out, "w") as f:
        json.dump(themes, f, separators=(",", ":"))
    print(f"{len(themes)} themes, {sum(t['dark'] for t in themes)} dark, to {os.path.relpath(out)}")
