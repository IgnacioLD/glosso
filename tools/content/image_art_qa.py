"""Objective artefact scan for the catalogue artwork, plus labelled contact sheets.

The brand is a flat two-tone line drawing: near-black ink, thin brass-gold open
lines, no fills. `brandify` guarantees the palette but not the drawing, so the
model can still fill a shape, draw a frame around the subject, crop it at the
edge or render text. This measures what the eye looks for:

  gold%      bright gold pixels. A line drawing is thin, so a large share of gold
             means shapes were filled in.
  filled%    the gold mask after an erosion, i.e. gold that is thicker than a
             line. This is the solid-fill test, and does not care how much thin
             line shares the frame.
  pale%      bright, low-chroma pixels that are not gold: an ivory fill. `gold%`
             only sees brass gold (`b < 130`), so a shape filled in pale gold or
             ivory slips past the solid-fill test; this is the colour it misses.
  pale_fill% the pale mask after the same erosion, i.e. pale that is a large
             region and not the antialiased core of a bright line. This is the
             pale-fill test, and it is what actually flags a filled shape.
  midtone%   pixels that are neither ink nor bright: grain, gradients, shading.
             A flat render keeps this near zero.
  edge%      brightest share in the outer border band, per edge. A frame/border
             lifts all four; a cropped subject lifts the edge it runs off.
  border     `edge%` high on opposite edges at once is a drawn frame.

It cannot name text or a missing subject; those are judged on the sheets. It
prints the offenders and writes `metrics.json` and contact sheets next to the
report so the eye can confirm every flag.

Usage:
  python image_art_qa.py --dir <content>/images --report /tmp/art-qa
  python image_art_qa.py --dir /tmp/svg-render --report /tmp/art-qa-render
"""

import argparse
import glob
import json
import os

import numpy as np
from PIL import Image

GOLD = dict(r_min=100, g_min=70, b_max=130, margin=25)
FILL_ERODE = 4
INK_MAX = 48
BRIGHT_MIN = 110
MID_LO, MID_HI = 55, 150
PALE_V = 150
PALE_C = 45
PALE_FILL_MIN = 0.2


def metrics(path):
    img = Image.open(path).convert("RGB")
    arr = np.asarray(img, dtype=np.int16)
    h, w, _ = arr.shape
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    gold = (r > GOLD["r_min"]) & (g > GOLD["g_min"]) & (b < GOLD["b_max"]) & (r > b + GOLD["margin"])
    bright = np.maximum(np.maximum(r, g), b) > BRIGHT_MIN
    v = arr.mean(axis=2)
    chroma = arr.max(axis=2) - arr.min(axis=2)
    ink = v < INK_MAX
    mid = (v >= MID_LO) & (v <= MID_HI) & (chroma < 45)
    pale = (v >= PALE_V) & (chroma < PALE_C) & ~gold

    filled = erode(gold, FILL_ERODE)
    pale_filled = erode(pale, FILL_ERODE)

    band = max(3, int(min(h, w) * 0.02))
    edges = {
        "top": gold[:band, :].mean(),
        "bottom": gold[-band:, :].mean(),
        "left": gold[:, :band].mean(),
        "right": gold[:, -band:].mean(),
    }
    border = min(edges["top"], edges["bottom"], edges["left"], edges["right"])

    return {
        "width": w,
        "height": h,
        "gold_pct": round(100 * gold.mean(), 3),
        "filled_pct": round(100 * filled.mean(), 4),
        "pale_pct": round(100 * pale.mean(), 3),
        "pale_fill_pct": round(100 * pale_filled.mean(), 4),
        "midtone_pct": round(100 * mid.mean(), 3),
        "bright_pct": round(100 * bright.mean(), 3),
        "edge_top": round(100 * edges["top"], 2),
        "edge_bottom": round(100 * edges["bottom"], 2),
        "edge_left": round(100 * edges["left"], 2),
        "edge_right": round(100 * edges["right"], 2),
        "border_pct": round(100 * border, 2),
    }


def erode(mask, k):
    m = mask
    for _ in range(k):
        m = (
            m
            & np.pad(m, ((1, 1), (1, 1)))[:-2, 1:-1]
            & np.pad(m, ((1, 1), (1, 1)))[2:, 1:-1]
            & np.pad(m, ((1, 1), (1, 1)))[1:-1, :-2]
            & np.pad(m, ((1, 1), (1, 1)))[1:-1, 2:]
        )
    return m


def flag(m):
    reasons = []
    if m["gold_pct"] > 8:
        reasons.append(f"gold {m['gold_pct']}%")
    if m["filled_pct"] > 0.2:
        reasons.append(f"solid fill {m['filled_pct']}%")
    if m["pale_fill_pct"] > PALE_FILL_MIN:
        reasons.append(f"pale fill {m['pale_fill_pct']}%")
    if m["midtone_pct"] > 3:
        reasons.append(f"midtone/grain {m['midtone_pct']}%")
    if m["border_pct"] > 4:
        reasons.append(f"border {m['border_pct']}%")
    edge = max(m["edge_top"], m["edge_bottom"], m["edge_left"], m["edge_right"])
    if edge > 8:
        reasons.append(f"edge/crop {edge}%")
    return reasons


def sheets(items, out_dir, name, cols=2, rows=3, cell=(640, 360)):
    from PIL import ImageDraw, ImageFont

    try:
        font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial.ttf", 16)
    except Exception:
        font = ImageFont.load_default()
    os.makedirs(out_dir, exist_ok=True)
    for s in range(0, len(items), cols * rows):
        chunk = items[s:s + cols * rows]
        w = cols * cell[0] + (cols + 1) * 8
        hgt = ((len(chunk) + cols - 1) // cols) * (cell[1] + 22) + 8
        canvas = Image.new("RGB", (w, hgt), (20, 20, 26))
        draw = ImageDraw.Draw(canvas)
        for i, (item_id, path) in enumerate(chunk):
            row, col = divmod(i, cols)
            x = 8 + col * (cell[0] + 8)
            y = 8 + row * (cell[1] + 22)
            draw.text((x, y), item_id, fill=(231, 200, 120), font=font)
            canvas.paste(Image.open(path).convert("RGB").resize(cell), (x, y + 22))
        out = os.path.join(out_dir, f"{name}_{s // (cols * rows) + 1:03d}.png")
        canvas.save(out)
        print(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="dir of PNGs to scan")
    ap.add_argument("--report", required=True, help="dir for metrics.json and sheets")
    ap.add_argument("--only", action="append", default=[])
    ap.add_argument("--sheets", action="store_true", help="build contact sheets")
    args = ap.parse_args()

    paths = sorted(glob.glob(os.path.join(args.dir, "*.png")))
    if args.only:
        wanted = set(args.only)
        paths = [p for p in paths if os.path.splitext(os.path.basename(p))[0] in wanted]
    os.makedirs(args.report, exist_ok=True)

    report, flagged = {}, []
    for path in paths:
        item_id = os.path.splitext(os.path.basename(path))[0]
        m = metrics(path)
        report[item_id] = m
        reasons = flag(m)
        if reasons:
            flagged.append((item_id, reasons))
            print(f"FLAG {item_id}: {', '.join(reasons)}")

    with open(os.path.join(args.report, "metrics.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2, sort_keys=True)
    with open(os.path.join(args.report, "flagged.txt"), "w", encoding="utf-8") as fh:
        for item_id, reasons in flagged:
            fh.write(f"{item_id}\t{'; '.join(reasons)}\n")
    print(f"\nscanned {len(paths)}, flagged {len(flagged)} -> {args.report}")

    if args.sheets:
        sheets([(i, os.path.join(args.dir, i + ".png")) for i in sorted(report)],
               os.path.join(args.report, "sheets"), "sheet")


if __name__ == "__main__":
    main()
