"""Pixel-art dachshund (Sasha) for the SashaBridge app.

Draws each frame on a small grid, outlines it automatically, and writes one VectorDrawable per
frame into the app's drawables, merging horizontal runs of a color into rectangles. With
--preview, it also writes a PNG with every frame, scaled up, on the app's dark and light
backgrounds (needs Pillow); the preview also shows frames reserved for future features.

Usage, from the repository root:
    python tools/sasha_sprite.py [--preview preview.png]
"""

import argparse
import math
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


def ball(cv, x, y, turn=0):
    """A tennis ball with its top-left at (`x`, `y`); `turn` 0 or 1 tilts the seam the other
    way, so alternating it makes the ball look like it rolls."""
    cv.rect(x + 1, y, x + 2, y + 3, "y")
    cv.rect(x, y + 1, x + 3, y + 2, "y")
    if turn:
        cv.dot("w", (x + 2, y + 1), (x + 1, y + 2))
    else:
        cv.dot("w", (x + 1, y + 1), (x + 2, y + 2))


# A gallop in six phases: (front paw offset, front folded, hind paw offset, hind folded,
# body lift). Offsets are in pixels ahead (+) or behind (-) the hip; a folded leg is a pixel
# shorter, off the ground. The body rises a pixel while all four feet are in the air.
GALLOP = [
    (2, False, -2, False, 0),   # reach: front ahead, hind behind, all on the ground
    (0, False, -2, True, 0),    # the front feet land under the shoulders, the hind lift
    (-2, False, 2, False, 0),   # gather: the feet cross under the body
    (-1, True, 0, False, -1),   # push off with the hind feet, the front fold
    (1, True, -1, True, -1),    # flight: every foot tucked
    (2, True, -2, False, -1),   # the front reach out again
]

# Hips of the near and far legs; the far legs are shaded and a phase behind the near ones.
FRONT_NEAR, FRONT_FAR, HIND_NEAR, HIND_FAR = 27, 24, 9, 12

# Ball path, in frames per loop: it rolls ahead and back once while bouncing twice, on the
# same ground as her paws. Its x never brings the outline past the frame's right edge.
RUN_FRAMES = 24
BALL_X_MID, BALL_X_SWING = 50, 5
BALL_GROUND, BALL_BOUNCE = 13, 6


def gallop_leg(cv, color, hip, base, offset, folded):
    """One leg from the hip at (`hip`, `base`) to its paw `offset` pixels ahead, in steps."""
    if folded:
        leg(cv, color, (hip, base), (hip + offset, base + 1))
    else:
        leg(cv, color, (hip, base), (hip + offset // 2, base + 1), (hip + offset, base + 2))


def running(frame):
    """Frame `frame` of the gallop after the ball, out of RUN_FRAMES.

    She gallops in place: moving a pixel sprite a pixel at a time reads as stutter, not as
    running. The legs go through the six GALLOP phases, the far pair a phase behind, while
    the ball rolls ahead and back in one-pixel steps.
    """
    cv = Canvas(DOG_W, DOG_H)
    phase = frame % len(GALLOP)
    front, front_folded, hind, hind_folded, lift = GALLOP[phase]
    far_front, far_front_folded, far_hind, far_hind_folded, _ = GALLOP[phase - 1]
    body(cv, dy=lift)
    base = 14 + lift
    gallop_leg(cv, "s", FRONT_FAR, base, far_front, far_front_folded)
    gallop_leg(cv, "s", HIND_FAR, base, far_hind, far_hind_folded)
    gallop_leg(cv, "b", HIND_NEAR, base, hind, hind_folded)
    gallop_leg(cv, "b", FRONT_NEAR, base, front, front_folded)
    if lift:
        # In the air the tail streams up and the ear flies back.
        cv.dot("b", (7, 9 + lift), (6, 8 + lift), (5, 8 + lift), (4, 7 + lift), (3, 7 + lift))
        cv.rect(29, 3 + lift, 30, 6 + lift, "s")
    else:
        cv.dot("b", (7, 9), (6, 9), (5, 8), (4, 8), (3, 8))
    angle = 2 * math.pi * frame / RUN_FRAMES
    bounce = math.pi * (frame % (RUN_FRAMES // 2)) / (RUN_FRAMES // 2)
    # The seam turns every two frames: the ball spins as it rolls.
    turn = (frame // 2) % 2
    ball(cv, BALL_X_MID + round(BALL_X_SWING * math.sin(angle)), BALL_GROUND - round(BALL_BOUNCE * math.sin(bounce)), turn)
    return cv


# Milliseconds per gallop frame: two refreshes at 30 Hz, four at 60 Hz, eight at 120 Hz, so
# every frame stays on screen the same time.
RUN_FRAME_MS = 66


def running_list():
    """The animation-list that plays the gallop frames in a loop."""
    items = "\n".join(
        f'    <item android:drawable="@drawable/sasha_run_{i + 1}" android:duration="{RUN_FRAME_MS}" />'
        for i in range(RUN_FRAMES)
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/sasha_sprite.py; edit the script, not this file. Sasha chases a\n"
        "     tennis ball while a PC is connected: she gallops in place while it bounces ahead\n"
        "     and comes back. -->\n"
        '<animation-list xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:oneshot="false">\n'
        f"{items}\n"
        "</animation-list>\n"
    )


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


def painter(tail):
    """The S Pen screens: Sasha with a painter's beret, wagging her tail (`tail` as in standing)."""
    cv = standing(tail)
    # A soft dome tilted toward the snout, with its brim drooping over the forehead.
    cv.rect(31, 0, 34, 0, "r")
    cv.rect(29, 1, 36, 1, "r")
    cv.rect(28, 2, 37, 2, "r")
    cv.dot("r", (38, 3), (37, 3))
    # One pixel down, so the top of the beret keeps its outline inside the frame.
    cv.px = {(x, y + 1): c for (x, y), c in cv.px.items()}
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
    **{f"sasha_run_{i + 1}": running(i) for i in range(RUN_FRAMES)},
    "sasha_drink_1": drinking(False),
    "sasha_drink_2": drinking(True),
    "sasha_sleep_1": sleeping(1),
    "sasha_sleep_2": sleeping(2),
    "sasha_paint_1": painter("up"),
    "sasha_paint_2": painter("side"),
    "hourglass_0": hourglass(0),
    "hourglass_1": hourglass(2),
    "hourglass_2": hourglass(4),
    "hourglass_3": hourglass(6),
    "hourglass_4": hourglass(7),
    "hourglass_flip": hourglass(7, sideways=True),
}

# Frames drawn only in the preview until the feature that uses them exists.
FUTURE_FRAMES = {}


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
    (RES_DIR / "sasha_running.xml").write_text(running_list(), encoding="utf-8", newline="\n")
    if args.preview:
        preview(args.preview)
