"""Glosso content images: authored `imagePrompt` -> on-brand PNG.

Renders the abstract brand art described in `glosso-data/prompts/images.md` and
`glosso-pro/docs/BRAND.md`. The model prompt stays exactly what is stored in the
item (`imagePrompt`, reproducible), and a deterministic duotone pass maps the
result onto the strict palette (ink -> brass gold -> ivory) so the catalogue is
visually coherent even when the model drifts.

Every image is seeded from the item id, so `--force` keeps the same seed. Local
sampling is not bit-reproducible (GPU nondeterminism, and `brandify` adds
unseeded grain), but the same seed + model + sampling settings reproduces the
same picture visually. Output goes to `<content>/images/<id>.png`; the source
item gains `image` (and keeps `imagePrompt`).

Backends (pluggable via `--backend`):

  local      FLUX.2 [klein] base 4B on our own GPU via diffusers. This is the
             default and the backend we actually use: the base-4B weights are
             Apache 2.0, so the catalogue is licensed for commercial use. It is
             the *base* (not the distilled) model, trained without step or
             guidance distillation, so it needs real sampling settings:
             guidance 4.0 per the model card, and 32 steps (DEFAULT_LOCAL_STEPS;
             the card's 50 is the safe reference). For this line-art -> 6-colour
             duotone style 32 is enough and matches the shipped catalogue, while
             the distilled variants run in ~4 steps. The pipeline is loaded once
             per invocation and reused for every item.
  deepinfra  Hosted API, kept for reference. It has **no commercial default**:
             it needs an explicit `--model`. Its known non-commercial variant
             (FLUX-2-klein-9b, the FLUX Non-Commercial License) is reachable only
             by naming it, and the script warns and records it as non-commercial.

Every image records per-image provenance (provider, model + revision, seed,
steps, guidance, dtype, prompt hash, duotone version and date) in
`<content>/images/provenance.json`. `--backfill-provenance` fills the manifest
for images already on disk, marking the configuration as `inferred` (the known
defaults plus the file mtime) instead of pretending it was observed.

The brand art is language-agnostic: `--reuse-from <other>/images` copies existing
on-brand PNGs (preferring the item's theme) under the item id instead of
generating, so a new language gets artwork for free.

Usage:
  export DEEPINFRA_API_KEY=...            # only for --backend deepinfra
  python images.py --kind exercises --only en.exercise.food.001
  python images.py --kind exercises --dry-run
  # on the GPU box (model at /opt/gen/models/flux2-klein-base-4B):
  python images.py --backend local --kind exercises
  # redo a single item whose id-derived seed gave a bad render (e.g. rendered text):
  python images.py --backend local --kind <kind> --only <id> --force --seed-offset 3
  python images.py --language fr --kind exercises --reuse-from ../../../glosso-data/content/en/images
  # fill provenance for the images already on disk (best-effort, marked inferred):
  python images.py --backfill-provenance
  # also drop a grain-free copy per item, to trace to SVG (catalogue PNG keeps grain):
  python images.py --backend deepinfra --model ... --vector-source ../svg-src
"""

import argparse
import base64
import datetime
import glob
import hashlib
import io
import json
import os
import shutil
import sys

API_URL = "https://api.deepinfra.com/v1/inference/{model}"

# The commercial, local model is the only default. The hosted backend has no
# commercial default and its non-commercial 9B variant is never implicit.
DEFAULT_BACKEND = "local"
COMMERCIAL_MODEL = "black-forest-labs/FLUX.2-klein-base-4B"
NON_COMMERCIAL_MODELS = {
    "black-forest-labs/FLUX-2-klein-9b": "FLUX Non-Commercial License",
}
DEFAULT_MODEL_DIR = "/opt/gen/models/flux2-klein-base-4B"

PROVENANCE_FILE = "provenance.json"
PROVENANCE_SCHEMA = 1
# Bumped whenever `brandify`/PALETTE changes the pixels, so provenance can say
# which duotone pass produced an image.
DUOTONE_VERSION = 1

# FLUX.2 [klein] base 4B is undistilled, so it needs real sampling settings; the
# model card example uses guidance_scale=4.0 and num_inference_steps=50. We ship
# the catalogue at 32 steps: for line-art -> 6-colour duotone the tonal collapse
# hides the difference, and 32 is what the shipped images use, so `--force` with
# the defaults reproduces them. The distilled klein variants run in ~4 steps.
DEFAULT_LOCAL_STEPS = 32
DEFAULT_LOCAL_GUIDANCE = 4.0

# KINDS folder -> (list key in the bank, whether every item always gets an image)
KINDS = {
    "stories": ("stories", True),
    "articles": ("articles", True),
    "dialogues": ("dialogues", True),
    "exercises": ("exercises", False),
}

# BRAND.md palette, dark -> light. Duotone control points spread over luminance.
PALETTE = [
    (0x0E, 0x0E, 0x12),  # ink
    (0x2A, 0x23, 0x12),  # deep gold container
    (0x7A, 0x63, 0x2E),  # mid
    (0xC9, 0xA2, 0x4B),  # brass gold
    (0xE7, 0xC8, 0x78),  # light gold
    (0xF2, 0xED, 0xE3),  # ivory
]


def load_api_key(explicit=None):
    if explicit:
        return explicit
    env = os.environ.get("DEEPINFRA_API_KEY") or os.environ.get("DEEPINFRA_TOKEN")
    if env:
        return env
    env_file = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if os.path.isfile(env_file):
        for line in open(env_file, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            if key.strip() in ("DEEPINFRA_API_KEY", "DEEPINFRA_TOKEN"):
                return value.strip().strip("'\"")
    raise SystemExit("No API key: set DEEPINFRA_API_KEY or tools/content/.env")


def seed_for(item_id):
    return int(hashlib.sha1(item_id.encode("utf-8")).hexdigest(), 16) % (2 ** 32)


def prompt_hash(prompt):
    return "sha256:" + hashlib.sha256((prompt or "").encode("utf-8")).hexdigest()


def _now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def model_revision(model_dir):
    """Identity of the local checkpoint: HF revision file if present, else a hash."""
    if not model_dir or not os.path.isdir(model_dir):
        return "unknown"
    for name in ("REVISION", "revision"):
        path = os.path.join(model_dir, name)
        if os.path.isfile(path):
            with open(path, encoding="utf-8") as fh:
                return fh.read().strip()
    digest = hashlib.sha1()
    for fname in sorted(os.listdir(model_dir)):
        path = os.path.join(model_dir, fname)
        if os.path.isfile(path):
            digest.update(("%s:%d" % (fname, os.path.getsize(path))).encode())
    return "local-" + digest.hexdigest()[:12]


def model_licence(model):
    """(licence, commercial?) for a model id. Unknown ids are treated as not commercial."""
    if model in NON_COMMERCIAL_MODELS:
        return NON_COMMERCIAL_MODELS[model], False
    if model == COMMERCIAL_MODEL:
        return "Apache-2.0", True
    return "unknown", False


def provenance_record(*, provider, model, seed=None, seed_offset=0, steps=None, guidance=None,
                      dtype=None, prompt="", revision="unknown", date=None,
                      confidence="observed", note=None, license=None, commercial_license=None):
    resolved_license, resolved_commercial = model_licence(model)
    if license is not None:
        resolved_license = license
    if commercial_license is not None:
        resolved_commercial = commercial_license
    record = {
        "provider": provider,
        "model": model,
        "model_revision": revision,
        "seed": seed,
        "seed_offset": seed_offset,
        "steps": steps,
        "guidance": guidance,
        "dtype": dtype,
        "prompt_hash": prompt_hash(prompt),
        "duotone_version": DUOTONE_VERSION,
        "license": resolved_license,
        "commercial_license": resolved_commercial,
        "date": date,
        "provenance": confidence,
    }
    if note:
        record["note"] = note
    return record


def load_provenance(images_dir):
    path = os.path.join(images_dir, PROVENANCE_FILE)
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict) and isinstance(data.get("images"), dict):
            return data
    except Exception:
        pass
    return {
        "schema": PROVENANCE_SCHEMA,
        "generator": "tools/content/images.py",
        "duotone_version": DUOTONE_VERSION,
        "images": {},
    }


def write_provenance(images_dir, manifest):
    path = os.path.join(images_dir, PROVENANCE_FILE)
    manifest["schema"] = PROVENANCE_SCHEMA
    manifest["generator"] = "tools/content/images.py"
    manifest["duotone_version"] = DUOTONE_VERSION
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    os.replace(tmp, path)


def collect_item_prompts(content):
    """id -> imagePrompt for every item bank under <content>/<kind>/*.json."""
    prompts = {}
    for path in sorted(glob.glob(os.path.join(content, "*", "*.json"))):
        if os.path.basename(path) == PROVENANCE_FILE:
            continue
        try:
            bank = json.load(open(path, encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(bank, dict):
            continue
        for value in bank.values():
            if not isinstance(value, list):
                continue
            for item in value:
                if isinstance(item, dict) and item.get("id"):
                    prompts.setdefault(item["id"], item.get("imagePrompt") or "")
    return prompts


def backfill_provenance(content, images_dir, model_dir, steps, guidance, dtype, seed_offset):
    """Fill the manifest for images already on disk, marked `inferred`.

    The config is not read back from the render (it was not recorded), so it is
    the known generator default plus the file mtime - explicitly labelled, never
    passed off as observed.
    """
    manifest = load_provenance(images_dir)
    entries = manifest["images"]
    prompts = collect_item_prompts(content)
    revision = model_revision(model_dir)
    date_note = ("Config inferred from the generator defaults used for the 2026-09-23 "
                 "base-4B regeneration; date from the file mtime.")
    added = 0
    for path in sorted(glob.glob(os.path.join(images_dir, "*.png"))):
        item_id = os.path.splitext(os.path.basename(path))[0]
        existing = entries.get(item_id)
        if existing and existing.get("provenance") == "observed":
            continue
        when = datetime.datetime.fromtimestamp(
            os.path.getmtime(path), datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        record = provenance_record(
            provider="local",
            model=COMMERCIAL_MODEL,
            seed=seed_for(item_id),
            seed_offset=seed_offset,
            steps=steps,
            guidance=guidance,
            dtype=dtype,
            prompt=prompts.get(item_id, ""),
            revision=revision,
            date=when,
            confidence="inferred",
            note=date_note,
        )
        if existing == record:
            continue
        entries[item_id] = record
        added += 1
    if added:
        write_provenance(images_dir, manifest)
    return added, len(entries)


def build_lut():
    """Three concatenated per-channel LUTs (R,G,B): grayscale x -> palette color."""
    last = len(PALETTE) - 1
    red, green, blue = [], [], []
    for i in range(256):
        t = i / 255 * last
        a = min(int(t), last)
        b = min(a + 1, last)
        f = t - a
        c = tuple(round(PALETTE[a][k] * (1 - f) + PALETTE[b][k] * f) for k in range(3))
        red.append(c[0])
        green.append(c[1])
        blue.append(c[2])
    return red + green + blue


LUT = build_lut()


def brandify(raw, grain=True):
    """White-on-black line art -> exact ink/gold/ivory duotone.

    `grain=False` skips the noise blend. The grain is what makes the PNG
    non-bit-reproducible, and it also keeps the background off a flat tone, so a
    later trace to SVG explodes into hundreds of tiny paths (130 paths / 103 KB
    vs 14 paths / 20 KB without it). The catalogue PNG keeps the grain; the
    grain-free render is only the vector source.
    """
    from PIL import Image, ImageFilter

    g = Image.open(raw).convert("L")
    g = g.filter(ImageFilter.GaussianBlur(0.4))
    g = g.point(lambda x: max(0, min(255, (x - 24) * 255 // 200)))
    rgb = g.convert("RGB").point(LUT)
    if grain:
        noise = Image.effect_noise(rgb.size, 6).point(lambda x: x)
        rgb = Image.blend(rgb, Image.merge("RGB", (noise, noise, noise)), 0.025)
    return rgb


_PIPELINE_CACHE = {}


def _local_pipeline(model_dir, device, dtype):
    """Load the klein-base-4B pipeline once per (model_dir, device, dtype).

    torch/diffusers are imported lazily here so `--dry-run` (and anything that
    never reaches the local backend) does not need them installed.
    """
    key = (model_dir, device, dtype)
    pipe = _PIPELINE_CACHE.get(key)
    if pipe is None:
        import torch
        from diffusers import Flux2KleinPipeline

        pipe = Flux2KleinPipeline.from_pretrained(model_dir, dtype=getattr(torch, dtype))
        pipe.to(device)
        _PIPELINE_CACHE[key] = pipe
    return pipe


def generate_local(model_dir, prompt, width, height, seed, device="cuda", dtype="bfloat16", steps=DEFAULT_LOCAL_STEPS, guidance=DEFAULT_LOCAL_GUIDANCE):
    """Generate raw PNG bytes with FLUX.2 [klein] base 4B on our own GPU.

    Lazy imports so `--dry-run` never needs torch/diffusers; the pipeline itself
    is cached and reused across items (see `_local_pipeline`). Sampling defaults
    for the *base* 4B (undistilled): 32 steps, guidance 4.
    """
    import torch

    pipe = _local_pipeline(model_dir, device, dtype)
    image = pipe(
        prompt=prompt,
        width=width,
        height=height,
        num_inference_steps=steps,
        guidance_scale=guidance,
        generator=torch.Generator(device=device).manual_seed(seed),
    ).images[0]
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


def generate(api_key, model, prompt, width, height, seed, safety=2):
    import requests

    body = {
        "prompt": prompt,
        "width": width,
        "height": height,
        "seed": seed,
        "safety_tolerance": safety,
    }
    res = requests.post(
        API_URL.format(model=model),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json=body,
        timeout=180,
    )
    if res.status_code != 200:
        raise SystemExit(f"DeepInfra HTTP {res.status_code}: {res.text[:400]}")
    payload = res.json()
    status = (payload.get("inference_status") or {}).get("status")
    if status != "succeeded" or not payload.get("images"):
        raise SystemExit(f"DeepInfra failed: {json.dumps(payload)[:400]}")
    return base64.b64decode(payload["images"][0].split(",", 1)[1])


def _dump(value, level):
    """JSON with 2-space indent, but short scalar lists stay on one line."""
    pad = "  " * level
    if isinstance(value, dict):
        if not value:
            return "{}"
        body = ",\n".join(f'{pad}  {json.dumps(k, ensure_ascii=False)}: {_dump(v, level + 1)}' for k, v in value.items())
        return "{\n" + body + "\n" + pad + "}"
    if isinstance(value, list):
        if not value:
            return "[]"
        if all(not isinstance(x, (dict, list)) for x in value):
            return "[" + ", ".join(json.dumps(x, ensure_ascii=False) for x in value) + "]"
        body = ",\n".join(f"{pad}  {_dump(x, level + 1)}" for x in value)
        return "[\n" + body + "\n" + pad + "]"
    return json.dumps(value, ensure_ascii=False)


def write_bank(path, bank, key):
    with open(path, "w", encoding="utf-8") as f:
        f.write(_dump(bank, 0) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--language", default="en", help="target language (default content/<lang>)")
    ap.add_argument("--content", default=None, help="content dir; overrides --language")
    ap.add_argument("--kind", default="exercises", choices=sorted(KINDS))
    ap.add_argument("--only", action="append", default=[], help="item id (repeatable)")
    ap.add_argument("--theme", help="restrict to a theme file")
    ap.add_argument("--force", action="store_true", help="regenerate existing images")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--prompts", help="JSON {id: imagePrompt} merged into the source items")
    ap.add_argument(
        "--backend",
        default=DEFAULT_BACKEND,
        choices=("deepinfra", "local"),
        help="local (default) = FLUX.2 [klein] base 4B on our GPU, Apache 2.0; "
        "deepinfra = hosted API (kept for reference, needs an explicit --model)",
    )
    ap.add_argument(
        "--model",
        default=None,
        help="deepinfra model id; no default. Naming a non-commercial model is allowed but "
        "warns and is recorded as non-commercial",
    )
    ap.add_argument("--model-dir", default=os.environ.get("GLOSSO_FLUX_MODEL_DIR", DEFAULT_MODEL_DIR), help="local: dir with the klein-base-4B weights")
    ap.add_argument("--device", default="cuda", help="local: torch device")
    ap.add_argument("--dtype", default="bfloat16", help="local: torch dtype name")
    ap.add_argument("--steps", type=int, default=DEFAULT_LOCAL_STEPS, help=f"local: num_inference_steps (default {DEFAULT_LOCAL_STEPS}; card reference is 50)")
    ap.add_argument("--guidance", type=float, default=DEFAULT_LOCAL_GUIDANCE, help=f"local: guidance_scale (default {DEFAULT_LOCAL_GUIDANCE}, as in the model card)")
    ap.add_argument(
        "--seed-offset",
        type=int,
        default=0,
        help="local: add this to the per-id seed (0 = reproducible default). Use it to redo one "
        "image with --only <id> --force when the id-derived seed yields a bad result (e.g. text)",
    )
    ap.add_argument("--api-key")
    ap.add_argument(
        "--backfill-provenance",
        action="store_true",
        help="fill images/provenance.json for the images already on disk, marked inferred; "
        "generates nothing",
    )
    ap.add_argument(
        "--reuse-from",
        help="reuse brand art from another content dir's images/ instead of generating it "
        "(the art is language-agnostic); prefers images of the item's theme, copied under the item id",
    )
    ap.add_argument(
        "--vector-source",
        help="also write a grain-free duotone PNG per generated item under this dir, as the "
        "source for tracing to SVG; the catalogue PNG (with grain) is unchanged",
    )
    args = ap.parse_args()

    content = os.path.abspath(args.content or f"../../../glosso-data/content/{args.language}")
    images_dir = os.path.join(content, "images")
    width, height = (int(x) for x in args.size.lower().split("x"))
    list_key = KINDS[args.kind][0]

    if args.backfill_provenance:
        added, total = backfill_provenance(
            content, images_dir, args.model_dir, args.steps, args.guidance, args.dtype, args.seed_offset,
        )
        print(f"{added} inferred records added, {total} images in {os.path.relpath(images_dir, content)}/{PROVENANCE_FILE}")
        return

    if args.backend == "local":
        model = COMMERCIAL_MODEL
    else:
        if not args.model:
            raise SystemExit(
                "--backend deepinfra needs an explicit --model; there is no commercial default "
                "(the previous implicit default, FLUX-2-klein-9b, is non-commercial)"
            )
        model = args.model
        if model in NON_COMMERCIAL_MODELS:
            print(
                f"WARNING: {model} is under the {NON_COMMERCIAL_MODELS[model]}; "
                "the generated images are NOT for commercial distribution",
                file=sys.stderr,
            )

    reuse_dir = os.path.abspath(args.reuse_from) if args.reuse_from else None
    source_images = sorted(glob.glob(os.path.join(reuse_dir, "*.png"))) if reuse_dir else []
    if reuse_dir and not source_images:
        raise SystemExit(f"no images to reuse at {reuse_dir}")

    api_key = None if (args.dry_run or reuse_dir or args.backend == "local") else load_api_key(args.api_key)
    overlay = {}
    if args.prompts:
        with open(args.prompts, encoding="utf-8") as f:
            overlay = json.load(f)

    manifest = load_provenance(images_dir)
    pattern = os.path.join(content, args.kind, f"{args.theme}.json" if args.theme else "*.json")
    made = skipped = 0
    for path in sorted(glob.glob(pattern)):
        with open(path, encoding="utf-8") as f:
            bank = json.load(f)
        dirty = False
        for item in bank.get(list_key, []):
            if args.only and item["id"] not in args.only:
                continue
            if item["id"] in overlay and item.get("imagePrompt") != overlay[item["id"]]:
                item["imagePrompt"] = overlay[item["id"]]
                dirty = True
            prompt = item.get("imagePrompt")
            if not prompt:
                skipped += 1
                continue
            target = os.path.join(images_dir, f"{item['id']}.png")
            rel = f"images/{item['id']}.png"
            if os.path.isfile(target) and not args.force:
                if item.get("image") != rel:
                    item["image"] = rel
                    dirty = True
                continue
            print(f"{item['id']} <- {prompt[:70]}...")
            if args.dry_run:
                made += 1
                continue
            os.makedirs(images_dir, exist_ok=True)
            seed = (seed_for(item["id"]) + args.seed_offset) % (2 ** 32)
            if reuse_dir:
                theme = item.get("theme", "")
                pool = [s for s in source_images if f".{theme}." in os.path.basename(s)] or source_images
                shutil.copy2(pool[seed % len(pool)], target)
                record = provenance_record(
                    provider="reuse", model="n/a", prompt=prompt, date=_now(), revision="n/a",
                    license="Apache-2.0", commercial_license=True,
                    note=f"copied on-brand art from {reuse_dir}",
                )
            else:
                if args.backend == "local":
                    raw = generate_local(
                        args.model_dir, prompt, width, height, seed,
                        device=args.device, dtype=args.dtype, steps=args.steps, guidance=args.guidance,
                    )
                    revision = model_revision(args.model_dir)
                    steps, guidance, dtype = args.steps, args.guidance, args.dtype
                else:
                    raw = generate(api_key, model, prompt, width, height, seed)
                    revision, steps, guidance, dtype = "n/a", None, None, None
                brandify(io.BytesIO(raw)).save(target)
                if args.vector_source:
                    os.makedirs(args.vector_source, exist_ok=True)
                    brandify(io.BytesIO(raw), grain=False).save(
                        os.path.join(args.vector_source, f"{item['id']}.png"))
                record = provenance_record(
                    provider=args.backend, model=model, seed=seed, seed_offset=args.seed_offset,
                    steps=steps, guidance=guidance, dtype=dtype, prompt=prompt,
                    revision=revision, date=_now(),
                )
            manifest["images"][item["id"]] = record
            item["image"] = rel
            dirty = True
            made += 1
        if dirty and not args.dry_run:
            write_bank(path, bank, list_key)
            print(f"updated {os.path.relpath(path, content)}")
    if made and not args.dry_run:
        write_provenance(images_dir, manifest)
    print(f"\n{made} {'reused' if reuse_dir else 'generated'}, {skipped} without imagePrompt -> {os.path.relpath(images_dir, content)}")


if __name__ == "__main__":
    main()
