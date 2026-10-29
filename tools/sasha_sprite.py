"""Pixel-art dachshund (Sasha) for the SashaBridge app.

Draws each frame on a small grid, outlines it automatically, and writes one VectorDrawable per
frame into the app's drawables, merging horizontal runs of a color into rectangles. With
--preview, it also writes a PNG with every frame, scaled up, on the app's dark and light
backgrounds (needs Pillow); the preview also shows frames reserved for future features.

Usage, from the repository root:
    python tools/sasha_sprite.py [--preview preview.png]
"""

import argparse
from pathlib import Path

# Size of the dog frames, wide enough for the ball to roll ahead and for the bucket. The
# hourglass has its own canvas, drawn over the bottom-right corner of the dog frame at
# HOURGLASS_OFFSET.
DOG_W, DOG_H = 60, 20
HOURGLASS_SIZE = 11
HOURGLASS_OFFSET = (DOG_W - HOURGLASS_SIZE, DOG_H - HOURGLASS_SIZE)

PALETTE = {
    "o": "#2E1A12",  # outline
    "b": "#8B5A2B",  # coat
    "s": "#63401F",  # shade: far legs, ear, belly
    "t": "#C98E5A",  # tan: muzzle, paws
    "k": "#1A1010",  # eye, nose
    "c": "#7B1E3A",  # wine collar
    "g": "#E0B04A",  # collar tag
    "z": "#C98E5A",  # sleeping z's (not outlined)
    "y": "#B5D334",  # tennis ball
    "w": "#F1F7D2",  # tennis ball seam
    "h": "#4BA9D6",  # hourglass caps
    "q": "#8FDAF8",  # hourglass glass
    "e": "#D9F3FD",  # hourglass empty inside
    "a": "#F2D58A",  # sand
    "r": "#A3243B",  # painter's beret
    "m": "#56606B",  # bucket rim and hoop
    "n": "#9AA4AE",  # bucket
    "u": "#4FA3E0",  # water
    "p": "#E07A8B",  # tongue
}


class Canvas:
    """A grid of palette keys. `px` is outlined; `overlay` is drawn on top as is."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = {}
        self.overlay = {}

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px[(x, y)] = c

    def dot(self, c, *points):
        for p in points:
            self.px[p] = c

    def clear(self, *points):
        for p in points:
            self.px.pop(p, None)

    def z(self, x, y):
        for dx, dy in [(0, 0), (1, 0), (2, 0), (3, 0), (2, 1), (1, 2), (0, 3), (1, 3), (2, 3), (3, 3)]:
            self.overlay[(x + dx, y + dy)] = "z"

    def outlined(self):
        out = dict(self.px)
        for (x, y) in self.px:
            for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                n = (x + dx, y + dy)
                if n not in self.px:
                    out[n] = "o"
        out.update(self.overlay)
        return {p: c for p, c in out.items() if 0 <= p[0] < self.w and 0 <= p[1] < self.h}


# --- Sasha -------------------------------------------------------------------------------

def head(cv, dx=0, dy=0):
    cv.rect(29 + dx, 2 + dy, 36 + dx, 8 + dy, "b")
    cv.clear((29 + dx, 2 + dy), (36 + dx, 2 + dy))
    cv.rect(37 + dx, 5 + dy, 40 + dx, 8 + dy, "b")
    cv.rect(36 + dx, 8 + dy, 40 + dx, 8 + dy, "t")
    cv.dot("k", (41 + dx, 5 + dy), (41 + dx, 6 + dy))
    cv.rect(31 + dx, 3 + dy, 33 + dx, 8 + dy, "s")
    cv.clear((31 + dx, 8 + dy))
    cv.dot("b", (31 + dx, 8 + dy))


def body(cv, dy=0):
    cv.rect(8, 8 + dy, 29, 13 + dy, "b")
    cv.clear((8, 8 + dy))
    cv.rect(10, 13 + dy, 27, 13 + dy, "s")
    cv.rect(27, 5 + dy, 31, 10 + dy, "b")
    head(cv, dy=dy)
    cv.dot("k", (35, 4 + dy))
    cv.rect(28, 8 + dy, 29, 10 + dy, "c")
    cv.dot("g", (28, 11 + dy))


def leg(cv, color, *steps):
    """A two-pixel-wide leg through `steps`, with a tan paw at the last one."""
    for x, y in steps:
        cv.dot(color, (x, y), (x + 1, y))
    x, y = steps[-1]
    cv.dot("t", (x, y), (x + 1, y))


def standing(tail):
    cv = Canvas(DOG_W, DOG_H)
    body(cv)
    leg(cv, "s", (12, 14), (12, 15), (12, 16))
    leg(cv, "s", (24, 14), (24, 15), (24, 16))
    leg(cv, "b", (9, 14), (9, 15), (9, 16))
    leg(cv, "b", (27, 14), (27, 15), (27, 16))
    if tail == "up":
        cv.dot("b", (7, 9), (7, 8), (6, 8), (6, 7), (5, 7), (5, 6), (4, 6), (4, 5), (3, 5), (3, 4))
    else:
        cv.dot("b", (7, 9), (7, 10), (6, 9), (5, 9), (5, 8), (4, 8), (3, 8), (3, 7), (2, 7))
    return cv


def ball(cv, x, y):
    cv.rect(x + 1, y, x + 2, y + 3, "y")
    cv.rect(x, y + 1, x + 3, y + 2, "y")
    cv.dot("w", (x + 1, y + 1), (x + 2, y + 2))


def running(stride, ball_x, ball_y):
    """Gallop in place: `stride` 0 stretches the legs out, 1 gathers them under the body.

    The ball is at (`ball_x`, `ball_y`). She stays put on purpose: moving a pixel sprite a
    pixel at a time reads as stutter, not as running.
    """
    cv = Canvas(DOG_W, DOG_H)
    lift = -1 if stride == 0 else 0
    body(cv, dy=lift)
    cv.dot("b", (7, 9 + lift), (6, 9 + lift), (5, 8 + lift), (4, 8 + lift), (3, 8 + lift))
    base = 14 + lift
    if stride == 0:
        # Stretched: back legs kick out behind, front legs reach ahead, ear flies back.
        leg(cv, "s", (11, base), (9, base + 1), (7, base + 2))
        leg(cv, "b", (8, base), (6, base + 1), (4, base + 2))
        leg(cv, "s", (25, base), (27, base + 1), (29, base + 2))
        leg(cv, "b", (28, base), (30, base + 1), (32, base + 2))
        cv.rect(29, 3 + lift, 30, 6 + lift, "s")
    else:
        # Gathered: all four legs under the body.
        leg(cv, "s", (13, base), (15, base + 1), (17, base + 2))
        leg(cv, "b", (10, base), (12, base + 1), (14, base + 2))
        leg(cv, "s", (24, base), (22, base + 1), (20, base + 2))
        leg(cv, "b", (27, base), (25, base + 1), (23, base + 2))
    ball(cv, ball_x, ball_y)
    return cv


def drinking(lap):
    """Head down in a bucket of water; on `lap` frames the tongue shows and water splashes."""
    cv = Canvas(DOG_W, DOG_H)
    cv.rect(8, 8, 29, 13, "b")
    cv.clear((8, 8))
    cv.rect(10, 13, 27, 13, "s")
    leg(cv, "s", (12, 14), (12, 15), (12, 16))
    leg(cv, "s", (24, 14), (24, 15), (24, 16))
    leg(cv, "b", (9, 14), (9, 15), (9, 16))
    leg(cv, "b", (27, 14), (27, 15), (27, 16))
    cv.dot("b", (7, 9), (7, 8), (6, 8), (6, 7), (5, 7), (5, 6), (4, 6), (4, 5), (3, 5), (3, 4))
    # Neck sloping down, then the head lowered into the bucket, under the line of the back.
    cv.rect(27, 8, 32, 12, "b")
    head(cv, dx=3, dy=6)
    cv.dot("k", (38, 10))
    cv.rect(28, 9, 29, 11, "c")
    cv.dot("g", (28, 12))
    if lap:
        # The tongue shows past the rim and the water splashes.
        cv.dot("p", (41, 10))
        cv.overlay[(46, 8)] = "u"
        cv.overlay[(48, 7)] = "u"
        cv.overlay[(44, 7)] = "u"
    # The bucket hides the snout below its rim.
    cv.rect(41, 11, 50, 11, "m")
    cv.rect(42, 12, 49, 12, "u")
    cv.rect(42, 13, 49, 16, "n")
    cv.rect(42, 14, 49, 14, "m")
    return cv


def sleeping(zs):
    cv = Canvas(DOG_W, DOG_H)
    cv.rect(8, 11, 29, 15, "b")
    cv.clear((8, 11))
    cv.rect(10, 15, 28, 15, "s")
    # Front paws stretched forward on the ground, head resting on them.
    cv.rect(30, 14, 37, 15, "b")
    cv.dot("t", (36, 15), (37, 15))
    cv.rect(27, 9, 31, 13, "b")
    head(cv, dy=6)
    cv.dot("k", (34, 10), (35, 10))
    cv.rect(28, 13, 29, 14, "c")
    # Tail curled along the ground.
    cv.dot("b", (7, 14), (6, 14), (5, 15), (4, 15), (3, 15), (3, 14))
    if zs >= 1:
        cv.z(35, 3)
    if zs >= 2:
        cv.z(40, 0)
    return cv


def painter():
    """Reserved for the S Pen drawing mode: Sasha with a painter's beret."""
    cv = standing("up")
    # A soft dome tilted toward the snout, with its brim drooping over the forehead.
    cv.rect(31, 0, 34, 0, "r")
    cv.rect(29, 1, 36, 1, "r")
    cv.rect(28, 2, 37, 2, "r")
    cv.dot("r", (38, 3), (37, 3))
    return cv


# --- Hourglass ---------------------------------------------------------------------------

# Upright glass, 7 x 9 inside an 11 x 11 canvas, with the inner cells in the order sand
# leaves the top chamber (last first) and fills the bottom one.
TOP_CELLS = [(5, 4), (4, 3), (5, 3), (6, 3), (4, 2), (5, 2), (6, 2)]
BOTTOM_CELLS = [(4, 8), (5, 8), (6, 8), (4, 7), (5, 7), (6, 7), (5, 6)]
NECK = (5, 5)


def hourglass(moved, sideways=False):
    """`moved` grains (0 to 7) have fallen; `sideways` is the frame halfway through a flip."""
    cv = Canvas(HOURGLASS_SIZE, HOURGLASS_SIZE)
    cv.rect(2, 1, 8, 1, "h")
    cv.rect(2, 9, 8, 9, "h")
    cv.dot("q", (3, 2), (7, 2), (3, 3), (7, 3), (4, 4), (6, 4), (4, 5), (6, 5), (4, 6), (6, 6), (3, 7), (7, 7), (3, 8), (7, 8))
    for cell in TOP_CELLS + BOTTOM_CELLS + [NECK]:
        cv.dot("e", cell)
    for cell in TOP_CELLS[: len(TOP_CELLS) - moved]:
        cv.dot("a", cell)
    for cell in BOTTOM_CELLS[:moved]:
        cv.dot("a", cell)
    if 0 < moved < len(TOP_CELLS):
        cv.dot("a", NECK)
    if sideways:
        turned = Canvas(HOURGLASS_SIZE, HOURGLASS_SIZE)
        turned.px = {(HOURGLASS_SIZE - 1 - y, x): c for (x, y), c in cv.px.items()}
        return turned
    return cv


# --- Output ------------------------------------------------------------------------------

FRAMES = {
    "sasha_awake": standing("up"),
    "sasha_wag": standing("side"),
    # The legs alternate every frame while the ball bounces twice, rolling ahead and back.
    "sasha_run_1": running(0, 46, 9),
    "sasha_run_2": running(1, 47, 12),
    "sasha_run_3": running(0, 49, 15),
    "sasha_run_4": running(1, 50, 12),
    "sasha_run_5": running(0, 51, 9),
    "sasha_run_6": running(1, 50, 12),
    "sasha_run_7": running(0, 49, 15),
    "sasha_run_8": running(1, 47, 12),
    "sasha_drink_1": drinking(False),
    "sasha_drink_2": drinking(True),
    "sasha_sleep_1": sleeping(1),
    "sasha_sleep_2": sleeping(2),
    "hourglass_0": hourglass(0),
    "hourglass_1": hourglass(2),
    "hourglass_2": hourglass(4),
    "hourglass_3": hourglass(6),
    "hourglass_4": hourglass(7),
    "hourglass_flip": hourglass(7, sideways=True),
}

# Frames drawn only in the preview until the feature that uses them exists.
FUTURE_FRAMES = {
    "sasha_painter": painter(),
}


def hex_rgb(value):
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


def preview(path, scale=6):
    from PIL import Image

    scenes = [[(cv, 0, 0)] for name, cv in {**FRAMES, **FUTURE_FRAMES}.items() if not name.startswith("hourglass")]
    scenes += [[(FRAMES["sasha_wag"], 0, 0), (FRAMES[f"hourglass_{i}"], *HOURGLASS_OFFSET)] for i in range(5)]
    scenes.append([(FRAMES["sasha_awake"], 0, 0), (FRAMES["hourglass_flip"], *HOURGLASS_OFFSET)])
    backgrounds = ["#1C1214", "#F7EFE8"]
    columns, pad = 4, 2
    tile_w, tile_h = (DOG_W + pad) * scale, (DOG_H + pad) * scale
    rows = (len(scenes) + columns - 1) // columns
    img = Image.new("RGB", (tile_w * columns * len(backgrounds), tile_h * rows))
    for half, background in enumerate(backgrounds):
        img.paste(hex_rgb(background), (half * tile_w * columns, 0, (half + 1) * tile_w * columns, tile_h * rows))
        for index, layers in enumerate(scenes):
            x0 = half * tile_w * columns + (index % columns) * tile_w + pad * scale // 2
            y0 = (index // columns) * tile_h + pad * scale // 2
            for cv, ox, oy in layers:
                for (x, y), c in cv.outlined().items():
                    px, py = x0 + (x + ox) * scale, y0 + (y + oy) * scale
                    img.paste(hex_rgb(PALETTE[c]), (px, py, px + scale, py + scale))
    img.save(path)


def vector(cv, dp_per_px=2):
    by_color = {}
    pixels = cv.outlined()
    for y in range(cv.h):
        x = 0
        while x < cv.w:
            c = pixels.get((x, y))
            if c is None:
                x += 1
                continue
            start = x
            while x < cv.w and pixels.get((x, y)) == c:
                x += 1
            by_color.setdefault(c, []).append(f"M{start},{y}h{x - start}v1h-{x - start}z")
    paths = "\n".join(
        f'    <path\n        android:fillColor="{PALETTE[c]}"\n        android:pathData="{"".join(runs)}" />'
        for c, runs in sorted(by_color.items())
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/sasha_sprite.py; edit the script, not this file. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{cv.w * dp_per_px:g}dp"\n    android:height="{cv.h * dp_per_px:g}dp"\n'
        f'    android:viewportWidth="{cv.w}"\n    android:viewportHeight="{cv.h}">\n'
        f"{paths}\n</vector>\n"
    )


RES_DIR = Path(__file__).resolve().parent.parent / "sashabridge-android" / "app" / "src" / "main" / "res" / "drawable"

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preview", type=Path, help="also write a scaled-up PNG of every frame")
    args = parser.parse_args()
    for name, cv in FRAMES.items():
        (RES_DIR / f"{name}.xml").write_text(vector(cv), encoding="utf-8", newline="\n")
    if args.preview:
        preview(args.preview)
