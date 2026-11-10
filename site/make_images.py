"""Makes the site's images from the repository's own: the screenshots and the launcher icon.

Run it from anywhere after a screenshot or the icon changes, then commit what it writes:

    python site/make_images.py

Needs Pillow with WebP (pip install pillow). It writes into site/public/assets/img/.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "site" / "public" / "assets" / "img"
SHOTS = ["home", "home-guide-card", "home-rows", "guide", "search", "search-on-your-apps", "nfl"]
WIDTHS = [800, 1600]


def screenshots() -> None:
    for name in SHOTS:
        image = Image.open(ROOT / "docs" / "images" / f"{name}.jpg").convert("RGB")
        for width in WIDTHS:
            height = round(image.height * width / image.width)
            image.resize((width, height), Image.LANCZOS).save(
                OUT / f"{name}-{width}.webp", "WEBP", quality=80, method=6
            )


def preview() -> None:
    """The card Reddit, Discord and search results show: the home, cropped to 1200x630."""
    image = Image.open(ROOT / "docs" / "images" / "home.jpg").convert("RGB")
    scaled = image.resize((1200, 675), Image.LANCZOS)
    top = (675 - 630) // 2
    scaled.crop((0, top, 1200, top + 630)).save(OUT / "preview.jpg", "JPEG", quality=85, optimize=True)


def icons() -> None:
    res = ROOT / "app" / "src" / "main" / "res" / "mipmap-xxxhdpi"
    background = Image.open(res / "ic_launcher_background.png").convert("RGBA")
    foreground = Image.open(res / "ic_launcher_foreground.png").convert("RGBA")
    icon = Image.alpha_composite(background, foreground.resize(background.size, Image.LANCZOS))
    # An adaptive icon's outer sixth is bleed for the launcher's mask: keep the middle.
    inset = background.width // 6
    icon = icon.crop((inset, inset, background.width - inset, background.height - inset))
    for size, name in [(32, "favicon-32.png"), (180, "apple-touch-icon.png"), (512, "icon-512.png")]:
        icon.resize((size, size), Image.LANCZOS).save(OUT / name, "PNG", optimize=True)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    screenshots()
    preview()
    icons()
    for path in sorted(OUT.iterdir()):
        print(f"{path.relative_to(ROOT)}  {path.stat().st_size // 1024} KB")
