#!/usr/bin/env python3
"""Give the app's brand faces the Cyrillic they lack.

Plus Jakarta Sans and Bricolage Grotesque have no basic Cyrillic, so Russian
text fell back to Roboto or SF in the middle of the brand faces. This script
rebuilds both variable fonts in shared/src/commonMain/composeResources/font
with Cyrillic merged in:

- Letters that share their shape with a Latin letter (А В Е К М Н О Р С Т Х,
  а е о р с у х, Ё ё ...) map in cmap to the face's own Latin glyph, so they
  keep its design, its variations and its kerning.
- The other letters come from an OFL donor face with Cyrillic. For every master
  location of the target, the donor is instanced at the weight whose stem, once
  scaled to the target's x-height (lowercase) or cap height (capitals), matches
  the target's stem there; spacing follows the target's H/O and n/o
  sidebearings. The masters become gvar deltas, so the letters follow the
  target's weight (and, in Bricolage, the spacing of its optical sizes).
- The new letters get mark anchors, so a stress mark (а́, я́) sits over them.
- The new letters join the kerning classes of the Latin letter whose side they
  share (Г kerns like T on its right, like H on its left).

Every input is downloaded from google/fonts at a pinned commit and checked
against its SHA-256, so the output is reproducible. Neither target declares a
Reserved Font Name, so the family names stay; the donor's copyright is added to
the name table and the app's licence list.

Usage (fontTools pinned below):
    python3 -m venv /tmp/fv && /tmp/fv/bin/pip install fonttools==4.69.1 opentype-sanitizer==9.2.0
    /tmp/fv/bin/python tools/fonts/cyrillic.py
"""
from __future__ import annotations

import argparse
import hashlib
import os
import unicodedata
import urllib.request
from functools import partial
from pathlib import Path

import ots
from fontTools.misc.roundTools import otRound
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables import _g_l_y_f, otTables
from fontTools.ttLib.tables.TupleVariation import TupleVariation
from fontTools.varLib.builder import buildVarData, buildVarRegion
from fontTools.varLib.hvar import add_HVAR
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.varLib.models import VariationModel, normalizeLocation

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "shared/src/commonMain/composeResources/font"
CACHE = ROOT / "build/fonts-src"
GF = "https://raw.githubusercontent.com/google/fonts/5026800714d07231937d92eb4cceabf06e05d74e/ofl/"

SOURCES = {
    "PlusJakartaSans": (
        "plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf",
        "89b3fb38aa0d275d7a731d0d817a4f1622b316b4d7fbdedcf02ee9099ff68bc8",
    ),
    "BricolageGrotesque": (
        "bricolagegrotesque/BricolageGrotesque%5Bopsz,wdth,wght%5D.ttf",
        "413e7357809ddd12fd80a96a8a396de0e401638d4acd3cb3e37532f0472ac682",
    ),
    "Onest": (
        "onest/Onest%5Bwght%5D.ttf",
        "966c5c29b4755da84b6854d5c21dd4eaa2420225d0e9874de602de176d4a9f31",
    ),
    "Geologica": (
        "geologica/Geologica%5BCRSV,SHRP,slnt,wght%5D.ttf",
        "9124d9e88ac6c11d761f35241713a51d68e2c4ebedce0edaca834717a00959ec",
    ),
}

# The locations the gvar deltas are built from. The app uses Plus Jakarta at
# 400-800 and Bricolage at 600-800 with opsz 20-72; each weight it uses is a
# master, and Bricolage's spacing changes linearly with opsz, so its two ends do.
TARGETS = [
    dict(
        source="PlusJakartaSans",
        out="plus_jakarta_sans.ttf",
        donor="Geologica",
        masters=[{"wght": w} for w in (200, 400, 500, 600, 700, 800)],
    ),
    dict(
        source="BricolageGrotesque",
        out="bricolage_grotesque.ttf",
        donor="Onest",
        # shortcut: the new letters keep wdth 100 at any width; give them wdth 75
        # masters if the app ever narrows Bricolage.
        masters=[
            {"opsz": o, "wght": w, "wdth": 100}
            for o in (12, 96)
            for w in (200, 400, 600, 700, 800)
        ],
    ),
]

# Cyrillic letters drawn like a Latin one: the cmap points them at its glyph.
ALIASES = dict(
    zip(
        "ЅІЈАВЕКМНОРСТХаеорсухѕіјЀЁЇѐёї",
        "SIJABEKMHOPCTXaeopcyxsijÈËÏèëï",
    )
)
# The rest of U+0400-045F and Ґ ґ come from the donor.
DONOR_CHARS = [
    chr(c)
    for c in [*range(0x0400, 0x0460), 0x0490, 0x0491]
    if chr(c) not in ALIASES and unicodedata.category(chr(c)) in ("Lu", "Ll")
]

# Kerning: the Latin letter each new letter resembles on its (left, right)
# side. None: the side kerns like nothing.
KERN_LIKE = {
    "Б": ("H", "B"), "Г": ("H", "T"), "Ѓ": ("H", "T"), "Ґ": ("H", "T"),
    "Ж": ("X", "X"), "З": (None, "B"), "И": ("H", "H"), "Й": ("H", "H"),
    "Ѝ": ("H", "H"), "Л": (None, "H"), "П": ("H", "H"), "У": ("Y", "Y"),
    "Ў": ("Y", "Y"), "Ф": ("O", "O"), "Ц": ("H", None), "Щ": ("H", None),
    "Ч": (None, "H"), "Ш": ("H", "H"), "Ъ": ("T", "B"), "Ы": ("H", "H"),
    "Ь": ("H", "B"), "Э": (None, "O"), "Є": ("O", None), "Ю": ("H", "O"),
    "Я": (None, "H"), "Њ": (None, "B"), "Љ": (None, "B"), "Џ": ("H", "H"),
    "Ќ": ("H", "K"), "Ђ": ("T", None), "Ћ": ("T", None),
    "б": ("o", "o"), "в": ("n", None), "г": ("n", "r"), "ѓ": ("n", "r"),
    "ґ": ("n", "r"), "ж": ("x", "x"), "и": ("n", "u"), "й": ("n", "u"),
    "ѝ": ("n", "u"), "к": ("n", "k"), "ќ": ("n", "k"), "л": (None, "n"),
    "м": (None, "n"), "н": ("n", "n"), "п": ("n", "n"), "ф": ("o", "o"),
    "ц": ("n", None), "щ": ("n", None), "ч": (None, "n"), "ш": ("n", "n"),
    "ы": ("n", "n"), "ь": ("n", None), "э": (None, "o"), "є": ("o", None),
    "ю": ("n", "o"), "я": (None, "n"), "ў": ("y", "y"), "џ": ("n", "n"),
}

UPPER_NAMES = {f"uni{ord(c):04X}" for c in DONOR_CHARS if c.isupper()}

CYRILLIC_RANGE_BIT = 9  # OS/2 ulUnicodeRange1
CP1251_BIT = 2  # OS/2 ulCodePageRange1


def fetch(name: str) -> Path:
    path, sha = SOURCES[name]
    CACHE.mkdir(parents=True, exist_ok=True)
    local = CACHE / f"{name}.ttf"
    if not local.exists() or hashlib.sha256(local.read_bytes()).hexdigest() != sha:
        with urllib.request.urlopen(GF + path) as r:
            local.write_bytes(r.read())
    got = hashlib.sha256(local.read_bytes()).hexdigest()
    if got != sha:
        raise SystemExit(f"{name}: SHA-256 {got}, expected {sha}")
    return local


def instance(path: Path, loc: dict) -> TTFont:
    """A static instance; axes missing from [loc] stay at their default."""
    font = TTFont(path)
    full = {a.axisTag: a.defaultValue for a in font["fvar"].axes} | loc
    return instantiateVariableFont(font, full)


def outline(font: TTFont, glyph: str):
    """Flattened points, contour ends, flags and advance width."""
    glyf = font["glyf"]
    coords, ends, flags = glyf[glyph].getCoordinates(glyf)
    # Flattened components can bring the overlap flag to later points; only the first may carry it.
    overlap = any(f & _g_l_y_f.flagOverlapSimple for f in flags)
    flags = [f & _g_l_y_f.flagOnCurve for f in flags]
    flags[0] |= _g_l_y_f.flagOverlapSimple if overlap else 0
    return list(coords), list(ends), bytes(flags), font["hmtx"][glyph][0]


def stem(coords, ends, y: float) -> float:
    """Width of the leftmost stroke crossed by a horizontal line at [y]."""
    xs, start = [], 0
    for end in ends:
        ring = coords[start : end + 1]
        for (x0, y0), (x1, y1) in zip(ring, ring[1:] + ring[:1]):
            if (y0 <= y) != (y1 <= y):
                xs.append(x0 + (y - y0) * (x1 - x0) / (y1 - y0))
        start = end + 1
    xs.sort()
    return xs[1] - xs[0]


def metrics(font: TTFont) -> dict:
    """Heights, stems and the sidebearings the spacing follows."""
    cmap = font.getBestCmap()

    def measure(ch):
        coords, ends, _, adv = outline(font, cmap[ord(ch)])
        xs = [x for x, _ in coords]
        return dict(coords=coords, ends=ends, lsb=min(xs), rsb=adv - max(xs), top=max(y for _, y in coords))

    n, o, x, h, big_o = (measure(c) for c in "noxHO")
    return dict(
        lower=dict(height=x["top"], stem=stem(n["coords"], n["ends"], x["top"] * 0.3), refs=(n, o)),
        upper=dict(height=h["top"], stem=stem(h["coords"], h["ends"], h["top"] * 0.3), refs=(h, big_o)),
    )


class Donor:
    """The donor face at any weight, extrapolated past the ends of its axis."""

    def __init__(self, path: Path):
        self.path = path
        axis = next(a for a in TTFont(path)["fvar"].axes if a.axisTag == "wght")
        self.lo, self.hi = axis.minValue, axis.maxValue
        self._cache: dict[int, TTFont] = {}
        # Stem-to-height ratio by weight, to solve for the weight to use.
        self.samples = {
            w: metrics(self._at(w)) for w in range(int(self.lo), int(self.hi) + 1, 50)
        }

    def _at(self, w: int) -> TTFont:
        if w not in self._cache:
            self._cache[w] = instance(self.path, {"wght": w})
        return self._cache[w]

    def weight_for(self, case: str, ratio: float) -> float:
        """The weight whose stem/height ratio is [ratio] (linear in between)."""
        pts = sorted((w, m[case]["stem"] / m[case]["height"]) for w, m in self.samples.items())
        segments = list(zip(pts, pts[1:]))
        # The segment that holds [ratio], or the end one to extrapolate from.
        (w0, r0), (w1, r1) = next(
            (seg for seg in segments if seg[0][1] <= ratio <= seg[1][1]),
            segments[0] if ratio < pts[0][1] else segments[-1],
        )
        return w0 + (ratio - r0) * (w1 - w0) / (r1 - r0)

    def glyphs(self, w: float, names: list[str]):
        """Outlines of [names] and the face's metrics at weight [w]."""
        w = round(w)
        if self.lo <= w <= self.hi:
            font = self._at(w)
            return {g: outline(font, g) for g in names}, metrics(font)
        # Past an end of the axis: extend its last 100 units linearly.
        end = int(self.hi if w > self.hi else self.lo)
        inner = end - 100 if w > self.hi else end + 100
        a, b = self._at(inner), self._at(end)
        t = (w - inner) / (end - inner)

        def mix(u, v):
            return u + (v - u) * t

        out = {}
        for g in names:
            ca, ends, flags, aa = outline(a, g)
            cb, _, _, ab = outline(b, g)
            out[g] = ([(mix(xa, xb), mix(ya, yb)) for (xa, ya), (xb, yb) in zip(ca, cb)], ends, flags, mix(aa, ab))
        ma, mb = metrics(a), metrics(b)
        mixed = {
            case: dict(
                height=mix(ma[case]["height"], mb[case]["height"]),
                refs=tuple(
                    {k: mix(ra[k], rb[k]) for k in ("lsb", "rsb")}
                    for ra, rb in zip(ma[case]["refs"], mb[case]["refs"])
                ),
            )
            for case in ma
        }
        return out, mixed


def glyph_name(ch: str) -> str:
    return f"uni{ord(ch):04X}"


def master_outlines(target_m: dict, donor: Donor, donor_cmap: dict):
    """Each donor letter fitted to one target master: {name: (coords, ends, flags, adv)}."""
    result = {}
    for case in ("lower", "upper"):
        chars = [
            c for c in DONOR_CHARS
            if ord(c) in donor_cmap and (unicodedata.category(c) == "Lu") == (case == "upper")
        ]
        t = target_m[case]
        w = donor.weight_for(case, t["stem"] / t["height"])
        outlines, dm = donor.glyphs(w, [donor_cmap[ord(c)] for c in chars])
        d = dm[case]
        s = t["height"] / d["height"]
        # Shift both sides so the reference letters' sidebearings match on average.
        shift = sum(
            tr[side] - dr[side] * s
            for tr, dr in zip(t["refs"], d["refs"])
            for side in ("lsb", "rsb")
        ) / 4
        for c in chars:
            coords, ends, flags, adv = outlines[donor_cmap[ord(c)]]
            pts = [(otRound(x * s + shift), otRound(y * s)) for x, y in coords]
            result[glyph_name(c)] = (pts, ends, flags, otRound(adv * s + 2 * shift))
    return result


def with_phantoms(fitted_glyph):
    pts, _, _, adv = fitted_glyph
    # The left phantom point sits at the origin because lsb == xMin.
    return _g_l_y_f.GlyphCoordinates([*pts, (0, 0), (adv, 0), (0, 0), (0, 0)])


def add_glyphs(font: TTFont, masters: list[dict], fitted: list[dict]):
    axes = {a.axisTag: (a.minValue, a.defaultValue, a.maxValue) for a in font["fvar"].axes}
    default = {tag: v[1] for tag, v in axes.items()}
    full = [default | m for m in masters]
    if default not in full:
        raise SystemExit(f"the default location {default} must be a master")
    model = VariationModel([normalizeLocation(m, axes) for m in full], axisOrder=list(axes))
    base = fitted[full.index(default)]

    glyf, hmtx, gvar = font["glyf"], font["hmtx"], font["gvar"]
    order = font.getGlyphOrder()
    for name, (pts, ends, flags, adv) in base.items():
        if name in glyf.glyphs:
            raise SystemExit(f"{name} already exists")
        for m in fitted:
            if len(m[name][0]) != len(pts) or m[name][1] != ends:
                raise SystemExit(f"{name}: masters are not point-compatible")
        g = _g_l_y_f.Glyph()
        g.numberOfContours = len(ends)
        g.coordinates = _g_l_y_f.GlyphCoordinates(pts)
        g.endPtsOfContours = ends
        g.flags = bytearray(flags)
        g.program = _g_l_y_f.ttProgram.Program()
        g.program.fromBytecode(b"")
        g.recalcBounds(glyf)
        order.append(name)
        glyf.glyphs[name] = g
        hmtx[name] = (adv, g.xMin)

        values = [with_phantoms(m[name]) for m in fitted]
        deltas = model.getDeltas(values, round=partial(_g_l_y_f.GlyphCoordinates.__round__, round=otRound))
        variations = []
        for delta, support in zip(deltas[1:], model.supports[1:]):
            if any(v for v in delta.array):
                var = TupleVariation(support, delta)
                var.optimize(deltas[0], ends, tolerance=0.5)
                variations.append(var)
        gvar.variations[name] = variations
    font.setGlyphOrder(order)
    glyf.glyphOrder = order
    if "GDEF" in font and font["GDEF"].table.GlyphClassDef:
        font["GDEF"].table.GlyphClassDef.classDefs.update({n: 1 for n in base})
    add_HVAR(font)
    return model


def add_marks(font: TTFont, model: VariationModel, fitted: list[dict]):
    """Anchors on the new letters, so a stress mark (U+0301) sits over them.

    Each letter takes the anchor heights of o (or O), variations included, and
    the middle of its advance, with its own deltas in the GDEF variation store.
    """
    cmap = font.getBestCmap()
    gid = font.getReverseGlyphMap()
    store = font["GDEF"].table.VarStore
    axis_tags = [a.axisTag for a in font["fvar"].axes]
    regions = store.VarRegionList.Region
    region_index = []
    for support in model.supports[1:]:
        region = buildVarRegion(support, axis_tags)
        if region not in regions:
            regions.append(region)
        region_index.append(regions.index(region))
    store.VarRegionList.RegionCount = len(regions)
    names = sorted(fitted[0], key=gid.__getitem__)
    rows, centers = [], {}
    for name in names:
        deltas = model.getDeltas([m[name][3] / 2 for m in fitted], round=otRound)
        centers[name] = deltas[0]
        rows.append(deltas[1:])
    outer = len(store.VarData)
    store.VarData.append(buildVarData(region_index, rows, optimize=False))
    store.VarDataCount = len(store.VarData)

    for lookup in font["GPOS"].table.LookupList.Lookup:
        for sub in lookup.SubTable:
            sub = getattr(sub, "ExtSubTable", sub)
            if getattr(sub, "LookupType", None) != 4:
                continue
            bases = sub.BaseCoverage.glyphs
            records = dict(zip(bases, sub.BaseArray.BaseRecord))
            for inner, name in enumerate(names):
                like = cmap[ord("O" if name in UPPER_NAMES else "o")]
                if like not in records:
                    continue
                record = otTables.BaseRecord()
                record.BaseAnchor = [a and anchor(a, centers[name], outer, inner) for a in records[like].BaseAnchor]
                records[name] = record
            bases[:] = sorted(records, key=gid.__getitem__)
            sub.BaseArray.BaseRecord = [records[g] for g in bases]
            sub.BaseArray.BaseCount = len(bases)


def anchor(like, x: int, outer: int, inner: int):
    """[like]'s anchor height at [x], which varies by row [outer]/[inner] of the store."""
    a = otTables.Anchor()
    a.Format = 3
    a.XCoordinate, a.YCoordinate = x, like.YCoordinate
    a.XDeviceTable = otTables.Device()
    a.XDeviceTable.StartSize, a.XDeviceTable.EndSize, a.XDeviceTable.DeltaFormat = outer, inner, 0x8000
    a.YDeviceTable = getattr(like, "YDeviceTable", None)
    return a


def add_kerning(font: TTFont):
    cmap = font.getBestCmap()
    gid = font.getReverseGlyphMap()
    for lookup in font["GPOS"].table.LookupList.Lookup:
        for sub in lookup.SubTable:
            sub = getattr(sub, "ExtSubTable", sub)
            if getattr(sub, "LookupType", None) != 2 or sub.Format != 2:
                continue
            covered = set(sub.Coverage.glyphs)
            first, second = sub.ClassDef1.classDefs, sub.ClassDef2.classDefs
            for ch, (left, right) in KERN_LIKE.items():
                name = glyph_name(ch)
                if name not in gid:
                    continue
                if right and cmap[ord(right)] in covered:
                    covered.add(name)
                    if cmap[ord(right)] in first:
                        first[name] = first[cmap[ord(right)]]
                if left and cmap[ord(left)] in second:
                    second[name] = second[cmap[ord(left)]]
            sub.Coverage.glyphs = sorted(covered, key=gid.__getitem__)


def merge(target: dict) -> TTFont:
    donor_name = target["donor"]
    src = fetch(target["source"])
    donor = Donor(fetch(donor_name))
    donor_cmap = TTFont(donor.path).getBestCmap()
    fitted = [master_outlines(metrics(instance(src, m)), donor, donor_cmap) for m in target["masters"]]

    font = TTFont(src, recalcTimestamp=False)  # reproducible output
    model = add_glyphs(font, target["masters"], fitted)
    add_marks(font, model, fitted)
    cmap = font.getBestCmap()
    for table in font["cmap"].tables:
        if table.isUnicode():
            table.cmap.update({ord(c): cmap[ord(l)] for c, l in ALIASES.items()})
            table.cmap.update({ord(c): glyph_name(c) for c in DONOR_CHARS if glyph_name(c) in fitted[0]})
    add_kerning(font)

    os2 = font["OS/2"]
    os2.ulUnicodeRange1 |= 1 << CYRILLIC_RANGE_BIT
    os2.ulCodePageRange1 |= 1 << CP1251_BIT
    donor_copyright = TTFont(donor.path)["name"].getDebugName(0)
    for rec in font["name"].names:
        if rec.nameID == 0:
            rec.string = f"{rec.toUnicode()} Cyrillic: {donor_copyright}"
        elif rec.nameID == 5:
            rec.string = f"{rec.toUnicode()}; Glosso Cyrillic from {donor_name}"
    return font


def check(path: Path, masters: list[dict]):
    """Browsers accept the file, every Russian letter resolves, every master instances."""
    # Chrome and Firefox run OTS on web fonts and drop a file it rejects.
    sanitized = ots.sanitize(str(path), os.devnull, capture_output=True)
    assert sanitized.returncode == 0, sanitized.stderr.decode()
    cmap = TTFont(path).getBestCmap()
    missing = [hex(c) for c in [*range(0x0410, 0x0450), 0x0401, 0x0451] if c not in cmap]
    assert not missing, f"missing {missing}"
    for m in masters:
        inst = instance(path, m)
        for c in DONOR_CHARS:
            assert c not in cmap or inst["hmtx"][cmap[ord(c)]][0] > 0


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--out", type=Path, default=OUT_DIR)
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for target in TARGETS:
        font = merge(target)
        path = args.out / target["out"]
        font.save(path)
        check(path, target["masters"])
        print(f"{path}: {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()
