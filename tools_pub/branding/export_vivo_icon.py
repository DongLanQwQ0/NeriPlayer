"""Export the approved vivo artwork at Android's xxxhdpi foreground size.

Run from any directory: python tools_pub/branding/export_vivo_icon.py
Requires Pillow. The selected source artwork stays unchanged.
"""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).with_name("vivo-orange.png")
OUTPUT = ROOT / "app/src/vivoRelease/res/drawable-nodpi/vivo_brand_art.png"
FOREGROUND_SIZE = 108 * 4  # 108dp adaptive foreground at xxxhdpi.


def main():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(SOURCE) as artwork:
        artwork.convert("RGBA").resize(
            (FOREGROUND_SIZE, FOREGROUND_SIZE), Image.Resampling.LANCZOS
        ).save(OUTPUT, format="PNG", optimize=True)
    print(f"Exported {OUTPUT.relative_to(ROOT)} ({OUTPUT.stat().st_size:,} bytes)")


if __name__ == "__main__":
    main()
