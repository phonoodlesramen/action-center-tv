"""Makes Action Center's TV banner and adaptive launcher icon from the TVAC art (tvac-art.png).

The art is a wordmark ("TV" white, "AC" cyan to blue with a plug) on a navy field with blue
curves. The banner is the art cropped a little, so the wordmark reads at banner size. The icon
is a square of the art at the same scale, centred on the wordmark: the letters are lifted off
the navy (matting against the known background) into the foreground layer, and the navy is
filled in under them for the background layer. Adaptive icons keep only the middle 66 of 108 dp
safe, so the square is taller than the art; the art is mirrored past its top and bottom edges.

usage, from the repo root (needs Pillow and NumPy):
    python -I docs/design/brand/make_icons.py docs/design/brand/tvac-art.png app/src/main/res [preview.png]
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

art_path, out = Path(sys.argv[1]), Path(sys.argv[2])
preview = Path(sys.argv[3]) if len(sys.argv) > 3 else None
art = Image.open(art_path).convert("RGB")
W, H = art.size
px = np.asarray(art).astype(np.float64)

# ---- the navy behind the wordmark: close to flat, so a plane per channel fitted to a ring
# around the wordmark's box.
BOX = (400, 300, 1290, 615)  # x0, y0, x1, y1 in the art: generous; the bounds printed below are exact
x0, y0, x1, y1 = BOX
ring = np.zeros((H, W), bool)
ring[y0 - 40:y1 + 40, x0 - 60:x1 + 60] = True
ring[y0:y1, x0:x1] = False
ys, xs = np.nonzero(ring)
A = np.c_[xs, ys, np.ones_like(xs)].astype(np.float64)
coef = [np.linalg.lstsq(A, px[ys, xs, c], rcond=None)[0] for c in range(3)]
gy, gx = np.mgrid[0:H, 0:W]
navy = np.stack([coef[c][0] * gx + coef[c][1] * gy + coef[c][2] for c in range(3)], -1)
print("navy ring residual", np.abs(px[ys, xs] - navy[ys, xs]).mean().round(2))

# ---- matting: alpha is how far a pixel moved from the navy towards its letter's own colour.
diff = np.linalg.norm(px - navy, axis=-1)
inbox = np.zeros((H, W), bool)
inbox[y0:y1, x0:x1] = True
solid = inbox & (diff > 120)  # letter interiors (the C's deep-blue tail is ~150 from the navy)


def nearest_fill(values, known, iterations=12):
    """Spreads each known pixel's colour outward to the unknown pixels next to it."""
    vals = values.copy()
    have = known.copy()
    for _ in range(iterations):
        acc = np.zeros_like(vals)
        cnt = np.zeros(have.shape)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dx == dy == 0:
                    continue
                cnt += np.roll(np.roll(have, dy, 0), dx, 1)
                acc += np.roll(np.roll(vals * have[..., None], dy, 0), dx, 1)
        grow = ~have & (cnt > 0)
        vals[grow] = acc[grow] / cnt[grow][:, None]
        have = have | grow
    return vals, have


fg, have = nearest_fill(px * solid[..., None], solid)
d = fg - navy
alpha = np.where(have & inbox, ((px - navy) * d).sum(-1) / np.maximum((d * d).sum(-1), 1e-6), 0.0)
alpha = np.clip(alpha, 0, 1)
alpha[solid] = 1.0
alpha[alpha < 0.04] = 0  # flat navy noise
ys_a, xs_a = np.nonzero(alpha > 0.5)
print("wordmark bounds", xs_a.min(), ys_a.min(), xs_a.max(), ys_a.max())
word = np.dstack([np.where(alpha[..., None] > 0, fg, 0), alpha * 255]).clip(0, 255)
wx0, wy0, wx1, wy1 = xs_a.min() - 6, ys_a.min() - 6, xs_a.max() + 7, ys_a.max() + 7
cx, cy = (wx0 + wx1) / 2, (wy0 + wy1) / 2

# ---- the art without the wordmark: the letters, grown a few px past their soft edges, are
# filled by relaxing the navy around them inwards. Only ever seen under the foreground.
hole = Image.fromarray(((alpha > 0.01) * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(13))
hole = np.asarray(hole) > 0
hx0, hy0, hx1, hy1 = x0 - 30, y0 - 30, x1 + 30, y1 + 30
region = px[hy0:hy1, hx0:hx1].copy()
rh = hole[hy0:hy1, hx0:hx1]
region[rh] = navy[hy0:hy1, hx0:hx1][rh]
for _ in range(600):
    padded = np.pad(region, ((1, 1), (1, 1), (0, 0)), mode="edge")
    mean = (padded[:-2, 1:-1] + padded[2:, 1:-1] + padded[1:-1, :-2] + padded[1:-1, 2:]) / 4
    region[rh] = mean[rh]
field = px.copy()
field[hy0:hy1, hx0:hx1] = region


def save(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# ---- banner: 320x180 dp at each density (the Shield is xhdpi: 640x360 px). A 16:9 crop of 80%
# of the art around the wordmark, so the wordmark spans about 2/3 of the width.
bw = W * 0.80
bh = bw * 9 / 16
left = min(max(cx - bw / 2, 0), W - bw)
top = min(max(cy - bh / 2, 0), H - bh)
banner_art = art.crop((round(left), round(top), round(left + bw), round(top + bh)))
for name, scale in DENSITIES.items():
    save(banner_art.resize((round(320 * scale), round(180 * scale)), Image.LANCZOS), out / f"drawable-{name}" / "banner.png")
banner = banner_art.resize((640, 360), Image.LANCZOS)

# ---- adaptive icon layers, 108 dp at each density. The wordmark spans 56 of the 108 dp, inside
# the 66 dp safe circle (its corners reach ~29.5 dp from the centre).
WORD_DP = 56.0
side = round((wx1 - wx0) * 108 / WORD_DP)
sx0, sy0 = round(cx - side / 2), round(cy - side / 2)
pad = ((max(0, -sy0), max(0, sy0 + side - H)), (max(0, -sx0), max(0, sx0 + side - W)))


def square(a):
    p = np.pad(a, pad + ((0, 0),), mode="reflect")
    oy, ox = sy0 + pad[0][0], sx0 + pad[1][0]
    return p[oy:oy + side, ox:ox + side]


bg_square = Image.fromarray(square(field).clip(0, 255).astype(np.uint8), "RGB")
fg_square = Image.fromarray(square(word).astype(np.uint8), "RGBA")
for name, scale in DENSITIES.items():
    n = round(108 * scale)
    save(bg_square.resize((n, n), Image.LANCZOS), out / f"mipmap-{name}" / "ic_launcher_background.png")
    layer = fg_square.resize((n, n), Image.LANCZOS)
    save(layer, out / f"mipmap-{name}" / "ic_launcher_foreground.png")
    # Themed icons: the wordmark's shape alone, which the system tints.
    mono = Image.new("RGBA", (n, n), (255, 255, 255, 255))
    mono.putalpha(layer.getchannel("A"))
    save(mono, out / f"mipmap-{name}" / "ic_launcher_monochrome.png")

# ---- optional preview: the icon under a circle and a rounded-square mask, large and at 48 dp,
# beside the banner at full size and half size.
if preview:
    full = bg_square.resize((432, 432), Image.LANCZOS).convert("RGBA")
    full.alpha_composite(fg_square.resize((432, 432), Image.LANCZOS))
    view = full.crop((72, 72, 360, 360))  # the 72 dp a launcher shows
    sheet = Image.new("RGBA", (1306, 420), (40, 40, 48, 255))
    for i, shape in enumerate(("circle", "squircle")):
        m = Image.new("L", (288, 288), 0)
        draw = ImageDraw.Draw(m)
        if shape == "circle":
            draw.ellipse((0, 0, 287, 287), fill=255)
        else:
            draw.rounded_rectangle((0, 0, 287, 287), radius=64, fill=255)
        tile = Image.new("RGBA", (288, 288), (0, 0, 0, 0))
        tile.paste(view, (0, 0), m)
        sheet.alpha_composite(tile, (20 + i * 308, 20))
        sheet.alpha_composite(tile.resize((96, 96), Image.LANCZOS), (20 + i * 308 + 96, 318))
    sheet.alpha_composite(banner.convert("RGBA"), (636, 20))
    sheet.alpha_composite(banner.resize((320, 180), Image.LANCZOS).convert("RGBA"), (636, 230))
    save(sheet, preview)
print("ok")
