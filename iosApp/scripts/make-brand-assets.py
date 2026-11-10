#!/usr/bin/env python3
"""Regenerates the iOS app icon and launch screen from the Android brand vectors.

Source of truth: androidApp/src/main/res/drawable/ic_launcher_foreground.xml (the
mark already placed on the 108 dp adaptive-icon grid) and docs/BRAND.md. Needs
rsvg-convert and ImageMagick (`brew install librsvg imagemagick`).

    python3 iosApp/scripts/make-brand-assets.py
"""
import json
import pathlib
import re
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "iosApp/Glosso/Assets.xcassets"
FOREGROUND = ROOT / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml"

INK = "#0B0B0E"
PAPER = "#F3F3F6"
TEXT_ON_INK = "#F4F4F6"
COBALT = "#3D51E0"


def mark(paths, first_bubble):
    return (
        f'<path fill="{first_bubble}" stroke="{first_bubble}" stroke-width="0.25" fill-rule="evenodd" d="{paths[0]}"/>'
        f'<path fill="{COBALT}" stroke="{COBALT}" stroke-width="0.25" fill-rule="evenodd" d="{paths[1]}"/>'
    )


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def color(hex_value, dark=False):
    h = hex_value.lstrip("#")
    entry = {
        "color": {
            "color-space": "srgb",
            "components": {"alpha": "1.000", "red": f"0x{h[0:2]}", "green": f"0x{h[2:4]}", "blue": f"0x{h[4:6]}"},
        },
        "idiom": "universal",
    }
    if dark:
        entry["appearances"] = [{"appearance": "luminosity", "value": "dark"}]
    return entry


def main():
    paths = re.findall(r'android:pathData="([^"]+)"', FOREGROUND.read_text())
    assert len(paths) == 2, "expected the two bubbles of the mark"
    info = {"author": "xcode", "version": 1}
    write_json(ASSETS / "Contents.json", {"info": info})

    # App icon: the adaptive icon (ink background + mark), framed on its central
    # 76 dp so the mark fills the square as it fills Android's launcher mask.
    icon_svg = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="16 16 76 76">'
        f'<rect x="0" y="0" width="108" height="108" fill="{INK}"/>'
        f'<g transform="translate(27.2034 30.4712) scale(0.108929)">{mark(paths, TEXT_ON_INK)}</g></svg>'
    )
    icon_dir = ASSETS / "AppIcon.appiconset"
    icon_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        svg = pathlib.Path(tmp) / "icon.svg"
        rgba = pathlib.Path(tmp) / "icon.png"
        svg.write_text(icon_svg)
        subprocess.run(["rsvg-convert", "-w", "1024", "-h", "1024", str(svg), "-o", str(rgba)], check=True)
        # The App Store rejects an icon with an alpha channel.
        subprocess.run(
            ["magick", str(rgba), "-background", INK, "-alpha", "remove", "-alpha", "off",
             f"PNG24:{icon_dir / 'AppIcon.png'}"],
            check=True,
        )
    write_json(icon_dir / "Contents.json", {
        "images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": info,
    })

    # Launch screen: the mark alone at the Android splash size (76 x 63.4 pt) on
    # paper or ink; its first bubble takes the theme's text colour.
    launch_dir = ASSETS / "LaunchMark.imageset"
    launch_dir.mkdir(parents=True, exist_ok=True)
    for name, first in (("LaunchMark-light.svg", INK), ("LaunchMark-dark.svg", TEXT_ON_INK)):
        (launch_dir / name).write_text(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="76" height="63.4" viewBox="0 0 501 418">'
            f"{mark(paths, first)}</svg>\n"
        )
    write_json(launch_dir / "Contents.json", {
        "images": [
            {"filename": "LaunchMark-light.svg", "idiom": "universal"},
            {
                "appearances": [{"appearance": "luminosity", "value": "dark"}],
                "filename": "LaunchMark-dark.svg",
                "idiom": "universal",
            },
        ],
        "info": info,
        "properties": {"preserves-vector-representation": True},
    })
    write_json(ASSETS / "LaunchBackground.colorset/Contents.json", {
        "colors": [color(PAPER), color(INK, dark=True)],
        "info": info,
    })


if __name__ == "__main__":
    main()
