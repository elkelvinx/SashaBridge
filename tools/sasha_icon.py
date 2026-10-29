"""App icon of SashaBridge: a detailed pixel-art Sasha on a wine background.

Writes the adaptive icon layers into the app's drawables:
- ic_launcher_foreground.xml: Sasha, shaded, with her collar and a soft ground shadow;
- ic_launcher_background.xml: a wine-to-coffee radial gradient;
- ic_launcher_monochrome.xml: Sasha's silhouette, for Android's themed icons.

The launcher crops the layers to its own shape (a rounded square on Samsung) and only the
central 66dp of the 108dp canvas are guaranteed visible, so Sasha fits in that circle.
With --preview, also writes a PNG of the icon under several masks and sizes; with --pc-icon,
the rounded-square icon as a 256-pixel PNG for the PC client's notifications (both need
Pillow).

Usage, from the repository root:
    python tools/sasha_icon.py [--preview preview.png] [--pc-icon sashabridge-pc/assets/sasha-icon.png]
"""

import argparse
import math
from pathlib import Path

GRID = 100  # pixels across the 108dp canvas; Sasha's pixels are 1.08dp
CENTER = GRID / 2

COLORS = {
    "outline": "#2A160E",
    "coat_hi": "#B87A42",
    "coat": "#93602F",
    "coat_lo": "#6E4421",
    "coat_deep": "#4E2E16",
    "ear": "#5C3519",
    "ear_hi": "#7A4A24",
    "tan": "#CF955F",
    "tan_hi": "#E8B888",
    "tan_lo": "#A8703F",
    "eye": "#140A08",
    "shine": "#FFFFFF",
    "nose": "#1A0E0B",
    "nose_hi": "#5A4038",
    "collar": "#8E2343",
    "collar_lo": "#5E1229",
    "tag": "#E2B24C",
    "tag_hi": "#F8DE95",
    "tongue": "#D96B7E",
    "mouth": "#3A1C14",
    "shadow": "#000000",
}

# Coat tones are shaded automatically by their exposed edges; the rest keep their color.
SHADED = {"coat", "ear", "tan"}


class Canvas:
    """Pixel grid of color names, drawn in order; later shapes cover earlier ones."""

    def __init__(self):
        self.px = {}
        self.shadow = set()

    def put(self, x, y, c):
        self.px[(int(x), int(y))] = c

    def ellipse(self, cx, cy, rx, ry, c, only=None):
        for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1:
                    if only is None or self.px.get((x, y)) in only:
                        self.put(x, y, c)

    def rounded(self, x0, y0, x1, y1, r, c):
        """Filled rectangle with corners rounded by radius r."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                cx = min(max(x + 0.5, x0 + r), x1 + 1 - r)
                cy = min(max(y + 0.5, y0 + r), y1 + 1 - r)
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= r * r:
                    self.put(x, y, c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)

    def line(self, points, width, c):
        """Thick polyline through `points`."""
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 2) + 1
            for i in range(steps + 1):
                t = i / steps
                self.ellipse(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, width / 2, width / 2, c)

    def arc(self, cx, cy, r, a0, a1, c, only=None):
        """One-pixel arc from angle a0 to a1 in degrees, 0 pointing right, growing clockwise."""
        steps = int(abs(a1 - a0) * r / 20) + 8
        for i in range(steps + 1):
            a = math.radians(a0 + (a1 - a0) * i / steps)
            x, y = int(cx + r * math.cos(a)), int(cy + r * math.sin(a))
            if only is None or self.px.get((x, y)) in only:
                self.put(x, y, c)

    def ground_shadow(self, cx, cy, rx, ry):
        for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1:
                    self.shadow.add((x, y))

    def shaded(self):
        """Light from the top left: exposed top and left edges brighten, bottom and right darken."""
        out = {}
        for (x, y), c in self.px.items():
            if c not in SHADED:
                out[(x, y)] = c
                continue
            same = lambda dx, dy: self.px.get((x + dx, y + dy)) is not None
            top = not same(0, -1) or not same(0, -2)
            left = not same(-1, 0)
            bottom = not same(0, 1) or not same(0, 2)
            right = not same(1, 0)
            if c == "coat":
                if not same(0, 1) or (not same(0, 2) and not same(1, 1)):
                    out[(x, y)] = "coat_deep" if not same(0, 1) else "coat_lo"
                elif bottom or right:
                    out[(x, y)] = "coat_lo"
                elif top or left:
                    out[(x, y)] = "coat_hi"
                else:
                    out[(x, y)] = c
            elif c == "ear":
                out[(x, y)] = "ear_hi" if left else c
            else:  # tan
                out[(x, y)] = "tan_hi" if top and not bottom else ("tan_lo" if bottom else c)
        for (x, y) in list(self.px):
            for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
                n = (x + dx, y + dy)
                if n not in self.px:
                    out[n] = "outline"
        return out


def sasha():
    cv = Canvas()
    o = 13  # offset that centers the drawing in the grid
    cv.ground_shadow(37 + o, 52 + o, 19, 2.2)

    # Tail: raised and curved, drawn first so the body covers its root.
    cv.line([(21 + o, 37 + o), (16 + o, 33 + o), (13 + o, 27 + o), (13 + o, 24 + o)], 2.6, "coat")

    # Far legs, darker because they are in the body's shadow.
    cv.rect(23 + o, 43 + o, 25 + o, 50 + o, "coat_lo")
    cv.rect(41 + o, 43 + o, 43 + o, 50 + o, "coat_lo")
    cv.rect(23 + o, 50 + o, 26 + o, 50 + o, "tan_lo")
    cv.rect(41 + o, 50 + o, 44 + o, 50 + o, "tan_lo")

    # Long straight body with a deep chest.
    cv.rounded(18 + o, 33 + o, 49 + o, 45 + o, 5.5, "coat")
    cv.ellipse(46 + o, 39.5 + o, 5, 6, "coat")
    # Tan chest and belly line.
    cv.ellipse(48 + o, 40 + o, 3, 4, "tan", only={"coat"})
    cv.ellipse(36 + o, 45 + o, 9, 1.5, "tan", only={"coat"})

    # Muscle definition: the curve of the thigh and of the shoulder.
    cv.arc(23 + o, 39 + o, 4.5, 20, 160, "coat_lo", only={"coat"})
    cv.arc(44 + o, 38 + o, 4.5, 30, 150, "coat_lo", only={"coat"})

    # Near legs, with tan paws pointing forward and the toes marked.
    cv.rect(26 + o, 43 + o, 28 + o, 50 + o, "coat")
    cv.rect(44 + o, 43 + o, 46 + o, 50 + o, "coat")
    cv.rect(26 + o, 49 + o, 29 + o, 50 + o, "tan")
    cv.rect(44 + o, 49 + o, 47 + o, 50 + o, "tan")
    cv.put(28 + o, 50 + o, "tan_lo")
    cv.put(46 + o, 50 + o, "tan_lo")

    # Neck rising to the head.
    cv.ellipse(48 + o, 33 + o, 4.5, 6, "coat")

    # Head, muzzle and the tan of the cheeks and jaw.
    cv.ellipse(51 + o, 27 + o, 5.5, 5, "coat")
    cv.ellipse(57 + o, 30 + o, 4.5, 2.6, "coat")
    cv.ellipse(56 + o, 31.5 + o, 4.2, 1.6, "tan")
    cv.ellipse(53 + o, 30.5 + o, 2.2, 1.6, "tan")

    # Long floppy ear.
    cv.ellipse(48.5 + o, 30 + o, 2.6, 5.5, "ear")

    # Collar with its gold tag.
    for y in range(33, 37):
        cv.put(45 + o, y + o, "collar")
        cv.put(46 + o, y + o, "collar")
    cv.put(47 + o, 36 + o, "collar_lo")
    cv.put(46 + o, 37 + o, "collar_lo")
    cv.put(47 + o, 37 + o, "tag")
    cv.put(47 + o, 38 + o, "tag")
    cv.put(48 + o, 37 + o, "tag_hi")

    # Open mouth with the tongue showing: Sasha is happy.
    for x in range(55, 59):
        cv.put(x + o, 31 + o, "mouth")
    cv.put(55 + o, 32 + o, "tongue")
    cv.put(56 + o, 32 + o, "tongue")
    cv.put(56 + o, 33 + o, "tongue")

    # Lighter tip of the tail.
    cv.ellipse(13 + o, 24.5 + o, 1.3, 1.3, "coat_hi")

    # Eye with a highlight, tan brow, and nose.
    cv.put(53 + o, 25 + o, "eye")
    cv.put(54 + o, 25 + o, "eye")
    cv.put(53 + o, 26 + o, "eye")
    cv.put(54 + o, 26 + o, "eye")
    cv.put(53 + o, 25 + o, "shine")
    cv.put(53 + o, 23 + o, "tan")
    cv.put(54 + o, 23 + o, "tan")
    cv.put(61 + o, 28 + o, "nose")
    cv.put(61 + o, 29 + o, "nose")
    cv.put(60 + o, 28 + o, "nose_hi")
    return cv


def vector_paths(pixels, alpha=None):
    by_color = {}
    for y in range(GRID):
        x = 0
        while x < GRID:
            c = pixels.get((x, y))
            if c is None:
                x += 1
                continue
            start = x
            while x < GRID and pixels.get((x, y)) == c:
                x += 1
            by_color.setdefault(c, []).append(f"M{start},{y}h{x - start}v1h-{x - start}z")
    return by_color


def vector(groups, header):
    paths = []
    for color, runs, alpha in groups:
        fill_alpha = f'\n        android:fillAlpha="{alpha}"' if alpha is not None else ""
        paths.append(
            f'    <path\n        android:fillColor="{color}"{fill_alpha}\n        android:pathData="{"".join(runs)}" />'
        )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Generated by tools/sasha_icon.py; edit the script, not this file. {header} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        f'    android:viewportWidth="{GRID}"\n    android:viewportHeight="{GRID}">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def foreground(cv):
    groups = [(COLORS["shadow"], vector_paths({p: "s" for p in cv.shadow})["s"], 0.35)] if cv.shadow else []
    for name, runs in sorted(vector_paths(cv.shaded()).items()):
        groups.append((COLORS[name], runs, None))
    return vector(groups, "Foreground layer.")


def monochrome(cv):
    silhouette = {p: "m" for p in cv.shaded()}
    return vector([("#FFFFFF", vector_paths(silhouette)["m"], None)], "Themed icon layer.")


BACKGROUND = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/sasha_icon.py; edit the script, not this file. Background layer. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="46"
                android:gradientRadius="76">
                <item android:offset="0" android:color="#FF7A2340" />
                <item android:offset="0.55" android:color="#FF4A1426" />
                <item android:offset="1" android:color="#FF24120E" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


def masked_icons(cv):
    """The icon rendered and cropped as launchers show it: rounded square, then circle."""
    from PIL import Image, ImageDraw

    def hex_rgba(value, alpha=255):
        value = value.lstrip("#")
        return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (alpha,)

    size = 432  # 4x the 108dp canvas
    bg = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(bg)
    stops = [(0, "#7A2340"), (0.55, "#4A1426"), (1, "#24120E")]
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - size / 2, y - size * 46 / 108) / (size * 76 / 108)
            d = min(d, 1)
            for (o0, c0), (o1, c1) in zip(stops, stops[1:]):
                if o0 <= d <= o1:
                    t = (d - o0) / (o1 - o0)
                    a, b = hex_rgba(c0), hex_rgba(c1)
                    bg.putpixel((x, y), tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4)))
                    break
    fg = Image.new("RGBA", (size, size))
    scale = size / GRID
    fd = ImageDraw.Draw(fg)
    for (x, y) in cv.shadow:
        fd.rectangle([x * scale, y * scale, (x + 1) * scale - 1, (y + 1) * scale - 1], fill=(0, 0, 0, 90))
    for (x, y), c in cv.shaded().items():
        fd.rectangle([x * scale, y * scale, (x + 1) * scale - 1, (y + 1) * scale - 1], fill=hex_rgba(COLORS[c]))
    icon = Image.alpha_composite(bg, fg)

    masks = []
    for shape in ("squircle", "circle"):
        m = Image.new("L", (size, size), 0)
        md = ImageDraw.Draw(m)
        inset = size * (108 - 72) / 2 / 108  # launchers show about 72 of the 108dp
        box = [inset, inset, size - inset, size - inset]
        if shape == "circle":
            md.ellipse(box, fill=255)
        else:
            md.rounded_rectangle(box, radius=(box[2] - box[0]) * 0.28, fill=255)
        masked = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        masked.paste(icon, (0, 0), m)
        masks.append(masked.crop([int(v) for v in box]))
    return masks


def pc_icon(cv, path, side=256):
    """Writes the rounded-square icon as a `side`-pixel PNG."""
    from PIL import Image

    masked_icons(cv)[0].resize((side, side), Image.LANCZOS).save(path)


def preview(cv, path):
    from PIL import Image

    masks = masked_icons(cv)
    tile = masks[0].width
    sheet = Image.new("RGBA", (tile * 2 + 96 + 48 + 80, tile), "#F2ECE8")
    sheet.paste(masks[0], (0, 0), masks[0])
    sheet.paste(masks[1], (tile + 16, 0), masks[1])
    small = masks[0].resize((96, 96), Image.LANCZOS)
    tiny = masks[0].resize((48, 48), Image.LANCZOS)
    sheet.paste(small, (tile * 2 + 32, 20), small)
    sheet.paste(tiny, (tile * 2 + 32 + 112, 44), tiny)
    sheet.save(path)


RES = Path(__file__).resolve().parent.parent / "sashabridge-android" / "app" / "src" / "main" / "res" / "drawable"

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--preview", type=Path, help="also write a PNG of the masked icon")
    parser.add_argument("--pc-icon", type=Path, help="also write the icon of the PC client's notifications")
    args = parser.parse_args()
    dog = sasha()
    (RES / "ic_launcher_foreground.xml").write_text(foreground(dog), encoding="utf-8", newline="\n")
    (RES / "ic_launcher_background.xml").write_text(BACKGROUND, encoding="utf-8", newline="\n")
    (RES / "ic_launcher_monochrome.xml").write_text(monochrome(dog), encoding="utf-8", newline="\n")
    if args.preview:
        preview(dog, args.preview)
    if args.pc_icon:
        pc_icon(dog, args.pc_icon)
