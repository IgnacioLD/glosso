"""Trace the catalogue's grain-free renders to SVG and raster a QA copy back.

`images.py --vector-source` writes one grain-free duotone PNG per item (the
catalogue PNG keeps its grain). Those are what this traces: the grain is exactly
what makes the background non-flat and explodes the trace into hundreds of
specks, so the vector source and not the shipped PNG is the input.

Recipe (validated before the catalogue pass): vtracer colour mode, stacked
hierarchy, spline curves. `filter_speckle=8` drops the stray dots, `corner`/
`length` thresholds keep the hand-drawn line, `path_precision=1` keeps the file
small (the palette is six flat colours, so sub-integer coordinates are noise).

Every SVG is rendered back to a PNG so the QA pass compares what ships against
what the model drew.

Usage:
  python vectorize.py --vector-source <content>/svg-src --svg-out <content>/images \
      --render-out /tmp/svg-render
  python vectorize.py --vector-source <content>/svg-src --svg-out <content>/images \
      --only en.exercise.food.001 --force
"""

import argparse
import glob
import os
import sys

VTRACER = dict(
    colormode="color",
    hierarchical="stacked",
    mode="spline",
    filter_speckle=8,
    corner_threshold=60,
    length_threshold=4.0,
    path_precision=1,
)


def vectorize_one(source_png, svg_path, render_path=None, render_width=1280):
    import vtracer

    os.makedirs(os.path.dirname(svg_path) or ".", exist_ok=True)
    vtracer.convert_image_to_svg_py(source_png, svg_path, **VTRACER)
    if render_path:
        import cairosvg

        os.makedirs(os.path.dirname(render_path) or ".", exist_ok=True)
        cairosvg.svg2png(url=svg_path, write_to=render_path, output_width=render_width)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vector-source", required=True, help="dir with the grain-free PNGs")
    ap.add_argument("--svg-out", required=True, help="dir for the traced SVGs")
    ap.add_argument("--render-out", help="also raster each SVG here, for QA")
    ap.add_argument("--render-width", type=int, default=1280)
    ap.add_argument("--only", action="append", default=[], help="item id (repeatable)")
    ap.add_argument("--force", action="store_true", help="re-trace existing SVGs")
    args = ap.parse_args()

    sources = sorted(glob.glob(os.path.join(args.vector_source, "*.png")))
    if args.only:
        wanted = set(args.only)
        sources = [p for p in sources if os.path.splitext(os.path.basename(p))[0] in wanted]
    if not sources:
        raise SystemExit(f"no source PNGs in {args.vector_source}")

    traced = skipped = 0
    bytes_out = 0
    for source in sources:
        item_id = os.path.splitext(os.path.basename(source))[0]
        svg_path = os.path.join(args.svg_out, item_id + ".svg")
        if os.path.isfile(svg_path) and not args.force:
            skipped += 1
            continue
        render_path = os.path.join(args.render_out, item_id + ".png") if args.render_out else None
        vectorize_one(source, svg_path, render_path, args.render_width)
        size = os.path.getsize(svg_path)
        bytes_out += size
        traced += 1
        print(f"{item_id}: {size // 1024}K")
    print(f"\n{traced} traced, {skipped} skipped -> {args.svg_out} ({bytes_out / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
