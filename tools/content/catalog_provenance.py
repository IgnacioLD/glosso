"""Rewrite the catalogue provenance manifest from what actually ran.

`images.py --backfill-provenance` fills the manifest from the generator defaults
and the file mtime, which is honest but says `inferred`: it cannot know the seed,
the prompt or the sampling that produced each file. This writes the `observed`
record instead, for the DeepInfra catalogue pass:

  - provider/model/size are the ones the generation actually used,
  - seed is the deterministic per-id seed plus any QA retry offset,
  - prompt_hash is the hash of the prompt that was sent,
  - date comes from the file mtime,
  - has_svg records whether an SVG was traced next to the PNG.

Sampling steps/guidance are null: the hosted endpoint runs its own defaults and
does not expose them, and recording the model card's numbers would be guessing.

Entries for items not in the pass (the older story art) are kept unchanged.

Usage:
  python catalog_provenance.py --images <content>/images --prompts prompts.json \
      --model black-forest-labs/FLUX-2-klein-4b --provider deepinfra --size 1280x720 \
      [--retries retries.json] [--only-id A --only-id B]
"""

import argparse
import datetime
import glob
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import images

NOTE = (
    "Observed catalogue pass: provider/model/size/seed/prompt are what the client "
    "sent and the deterministic id seed; date is the file mtime. Sampling steps and "
    "guidance are the hosted endpoint's defaults and are not exposed by the API, so "
    "they stay null rather than guessed."
)


def iso_mtime(path):
    return datetime.datetime.fromtimestamp(
        os.path.getmtime(path), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True, help="content images dir")
    ap.add_argument("--prompts", required=True, help="JSON {id: prompt} the pass used")
    ap.add_argument("--model", required=True)
    ap.add_argument("--provider", default="deepinfra")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--retries", help="JSON {id: seed_offset} for reseeded items")
    ap.add_argument("--only-id", action="append", default=[])
    ap.add_argument("--out", help="manifest path (default <images>/provenance.json)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    with open(args.prompts, encoding="utf-8") as fh:
        prompts = json.load(fh)
    retries = {}
    if args.retries and os.path.isfile(args.retries):
        with open(args.retries, encoding="utf-8") as fh:
            retries = json.load(fh)

    manifest = images.load_provenance(args.images)
    ids = args.only_id or sorted(prompts)
    width, height = (int(x) for x in args.size.lower().split("x"))
    added = 0
    for item_id in ids:
        png = os.path.join(args.images, item_id + ".png")
        if not os.path.isfile(png):
            raise SystemExit(f"missing PNG for {item_id}: {png}")
        seed_offset = int(retries.get(item_id, 0))
        record = images.provenance_record(
            provider=args.provider,
            model=args.model,
            seed=(images.seed_for(item_id) + seed_offset) % (2 ** 32),
            seed_offset=seed_offset,
            steps=None,
            guidance=None,
            dtype=None,
            prompt=prompts[item_id],
            revision="n/a",
            date=iso_mtime(png),
            license="Apache-2.0",
            commercial_license=True,
            note=NOTE,
        )
        record["size"] = f"{width}x{height}"
        record["has_svg"] = os.path.isfile(os.path.join(args.images, item_id + ".svg"))
        manifest["images"][item_id] = record
        added += 1

    default_out = os.path.join(args.images, images.PROVENANCE_FILE)
    out = args.out or default_out
    if args.dry_run:
        print(f"would write {added} observed records, {len(manifest['images'])} total -> {out}")
        return
    if out == default_out:
        images.write_provenance(args.images, manifest)
    else:
        with open(out, "w", encoding="utf-8") as fh:
            fh.write(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(f"{added} observed records, {len(manifest['images'])} total -> {out}")


if __name__ == "__main__":
    main()
