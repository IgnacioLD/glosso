#!/usr/bin/env python3
"""Compose the Play listing graphics from the raw captures.

These outputs, all deterministic from the raw captures:

  - framed/<locale>/NN_<screen>.png        phone, 1080x1920
  - framed-7in/<locale>/NN_<screen>.png    7-inch tablet, 1200x1920
  - framed-10in/<locale>/NN_<screen>.png   10-inch tablet, 1600x2560
  - icon-512.png                           the launcher icon, square, no badges
  - feature-1024x500[.<locale>].png        the feature graphic, opaque, no badges

The device mockup is drawn by hand (rounded rect + screen clipped into it); no
third-party device art is used, so there is nothing to attribute. Text is laid
out as SVG and rasterised with `rsvg-convert`, which is the only renderer here
that honours the variable fonts' weights through fontconfig.

Usage:
    python3 tools/store/compose.py [--locale en,es,fr,de] [--only phones,tablets,icon,feature]
"""
from __future__ import annotations

import argparse
import base64
import os
import re
import subprocess
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
FONTS = REPO / "shared/src/commonMain/composeResources/font"
RAW = REPO / "docs/store/screenshots/raw"
RAW_TABLETS = REPO / "docs/store/screenshots/raw-tablets"
OUT = REPO / "docs/store"

# Brand palette, kept in step with docs/BRAND.md and GlossoColor.kt.
INK = "#0B0B0E"
INK_DEEP = "#14142E"
COBALT = "#3D51E0"
COBALT_LIGHT = "#93A4FF"
TEXT = "#F4F4F6"
MUTED = "#A3A3AE"

BY_FAMILY = {
    "display": "Bricolage Grotesque",
    "ui": "Plus Jakarta Sans",
}

# One headline per screen, per locale. The order of PHONES is the order the
# listing shows them in; the first is the one that has to carry the promise.
HEADLINES = {
    "en": {
        "onboarding": ("Learn the language. Pronounce it well.", "A course built around your own voice."),
        "today": ("Your lesson, ready every day.", "Built from the sounds that resist you."),
        "practice": ("Speak. Get measured.", "Real pronunciation scoring, sound by sound."),
        "feedback": ("See exactly which sound slipped.", "Scores down to the phoneme, not a vibe."),
        "you": ("Watch a weak sound become a strength.", "Your own trend, from your own takes."),
        "lessons": ("A real course, from A1 up.", "Dialogues, articles and drills that build."),
        "story": ("Read and hear real English.", "Tap any word for its sound and meaning."),
        "stories": ("A library that grows with you.", "Short pieces to read and hear, at your level."),
        "dialogue": ("Real conversations, line by line.", "In American or British English, your choice."),
        "speak": ("Your turn: say it from memory.", "Hear the line, then answer in English yourself."),
        "library": ("Stories, exercises and free practice.", "Everything that is not a lesson."),
        "ru_dialogue": ("Now in Russian, too.", "Everyday dialogues, with the meaning in your language."),
        "ru_context": ("Learn the culture, not just the words.", "New Russian A1: what is done, and what is not."),
        "switcher": ("Three courses, one tap away.", "English, Russian and Spanish. Switch any time."),
        "es_dialogue": ("Now in Spanish, too.", "A new A1 course from Spain, with the meaning in your language."),
    },
    "es": {
        "onboarding": ("Aprende el idioma. Pronúncialo bien.", "Un curso hecho con tu propia voz."),
        "today": ("Tu lección, lista cada día.", "Hecha con los sonidos que se te resisten."),
        "practice": ("Habla. Te medimos.", "Nota de pronunciación real, sonido a sonido."),
        "feedback": ("Mira qué sonido se te escapó.", "Nota por fonema, no una impresión."),
        "you": ("Mira cómo un sonido débil se hace fuerte.", "Tu tendencia, con tus propias tomas."),
        "lessons": ("Un curso de verdad, desde A1.", "Diálogos, artículos y ejercicios que suman."),
        "story": ("Lee y escucha inglés real.", "Toca una palabra y oye su sonido."),
        "stories": ("Una biblioteca que crece contigo.", "Textos cortos para leer y escuchar."),
        "dialogue": ("Conversaciones reales, línea a línea.", "En inglés americano o británico, tú eliges."),
        "speak": ("Tu turno: dilo de memoria.", "Escuchas la frase y respondes tú en inglés."),
        "library": ("Historias, ejercicios y práctica libre.", "Todo lo que no es una lección."),
        "ru_dialogue": ("Ahora también en ruso.", "Diálogos del día a día, con su traducción."),
        "ru_context": ("Aprende la cultura, no solo las palabras.", "Nuevo ruso A1: lo que se hace y lo que no."),
        "switcher": ("Tres cursos, a un toque.", "Inglés, ruso y español. Cambia cuando quieras."),
        "es_dialogue": ("Ahora también en español.", "Nuevo curso A1 de España, con la traducción en tu idioma."),
    },
    "fr": {
        "onboarding": ("Apprenez la langue. Prononcez-la bien.", "Un cours construit autour de votre voix."),
        "today": ("Votre leçon, prête chaque jour.", "Faite à partir des sons qui vous résistent."),
        "practice": ("Parlez. On vous mesure.", "Une note de prononciation réelle, son par son."),
        "feedback": ("Voyez quel son vous a échappé.", "Une note par phonème, pas une impression."),
        "you": ("Voyez un son faible devenir fort.", "Votre tendance, à partir de vos prises."),
        "lessons": ("Un vrai cours, dès le niveau A1.", "Dialogues, articles et exercices qui s'enchaînent."),
        "story": ("Lisez et écoutez de l'anglais réel.", "Touchez un mot pour l'entendre et le comprendre."),
        "stories": ("Une bibliothèque qui grandit avec vous.", "Des textes courts à lire et écouter, à votre niveau."),
        "dialogue": ("De vraies conversations, ligne par ligne.", "En anglais américain ou britannique, à vous de choisir."),
        "speak": ("À vous : dites-le de mémoire.", "Écoutez la réplique, puis répondez en anglais."),
        "library": ("Histoires, exercices et entraînement libre.", "Tout ce qui n'est pas une leçon."),
        "ru_dialogue": ("Maintenant aussi en russe.", "Des dialogues du quotidien, traduits dans votre langue."),
        "ru_context": ("Apprenez la culture, pas seulement les mots.", "Nouveau russe A1 : ce qui se fait, et ce qui ne se fait pas."),
        "switcher": ("Trois cours, à portée de doigt.", "Anglais, russe et espagnol. Changez quand vous voulez."),
        "es_dialogue": ("Maintenant en espagnol aussi.", "Nouveau cours A1 d'Espagne, traduit dans votre langue."),
    },
    "de": {
        "onboarding": ("Lerne die Sprache. Sprich sie richtig aus.", "Ein Kurs rund um deine eigene Stimme."),
        "today": ("Deine Lektion, jeden Tag bereit.", "Gebaut aus den Lauten, die dir schwerfallen."),
        "practice": ("Sprich. Wir messen dich.", "Echte Aussprachebewertung, Laut für Laut."),
        "feedback": ("Sieh genau, welcher Laut danebenging.", "Bewertung pro Phonem, kein Gefühl."),
        "you": ("Sieh, wie ein schwacher Laut stark wird.", "Dein Trend, aus deinen eigenen Aufnahmen."),
        "lessons": ("Ein echter Kurs, ab A1.", "Dialoge, Artikel und Übungen, die aufbauen."),
        "story": ("Lies und höre echtes Englisch.", "Tippe ein Wort an für Laut und Bedeutung."),
        "stories": ("Eine Bibliothek, die mit dir wächst.", "Kurze Texte zum Lesen und Hören, auf deinem Niveau."),
        "dialogue": ("Echte Gespräche, Zeile für Zeile.", "In amerikanischem oder britischem Englisch, du wählst."),
        "speak": ("Du bist dran: Sag es aus dem Kopf.", "Hör den Satz und antworte selbst auf Englisch."),
        "library": ("Geschichten, Übungen und freies Üben.", "Alles, was keine Lektion ist."),
        "ru_dialogue": ("Jetzt auch auf Russisch.", "Alltagsdialoge, übersetzt in deine Sprache."),
        "ru_context": ("Lerne die Kultur, nicht nur Wörter.", "Neu: Russisch A1, was sich gehört und was nicht."),
        "switcher": ("Drei Kurse, ein Tipp entfernt.", "Englisch, Russisch und Spanisch. Jederzeit wechseln."),
        "es_dialogue": ("Jetzt auch auf Spanisch.", "Neuer A1-Kurs aus Spanien, mit Übersetzung."),
    },
}

# Which raw screen backs each framed phone shot, in listing order. The welcome
# screen is left out on purpose: it repeats the headline verbatim.
# Play takes at most eight: the practice screen and the story list gave their
# place to the lesson dialogue and the "Your turn" step, and the story reader
# and the Library hub to the Russian course (its dialogue and its culture notes).
PHONES = ["today", "feedback", "switcher", "es_dialogue", "ru_dialogue", "ru_context", "you", "lessons"]

# Tablets show the same design over a shorter, more representative set.
TABLETS = ["today", "feedback", "switcher", "es_dialogue", "ru_dialogue", "you", "lessons"]


def fontconfig() -> tuple[Path, Path]:
    """A throwaway fontconfig that exposes only the app's own fonts."""
    tmp = Path(tempfile.mkdtemp(prefix="glosso-store-fc-"))
    cache = tmp / "cache"
    cache.mkdir()
    conf = tmp / "fonts.conf"
    conf.write_text(
        '<?xml version="1.0"?>\n'
        '<!DOCTYPE fontconfig SYSTEM "fonts.dtd">\n'
        "<fontconfig>\n"
        f"  <dir>{FONTS}</dir>\n"
        f"  <cachedir>{cache}</cachedir>\n"
        "</fontconfig>\n"
    )
    return conf, tmp


def render(svg: str, out: Path, w: int, h: int, fc: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as fh:
        fh.write(svg)
        svg_path = fh.name
    env = dict(os.environ, FONTCONFIG_FILE=str(fc))
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), svg_path, "-o", str(out)],
                   check=True, env=env)
    os.unlink(svg_path)
    print(f"  wrote {out.relative_to(REPO)}")


XML_ESCAPE = {"&": "&amp;", "<": "&lt;", ">": "&gt;"}


def _esc(text: str) -> str:
    for k, v in XML_ESCAPE.items():
        text = text.replace(k, v)
    return text


_measure_fc: Path | None = None


def text_width(text: str, size: float, weight: int, family: str, spacing: float, fc: Path) -> float:
    """Ink width of one line, measured by rendering it and trimming the result.

    rsvg honours the variable fonts' weights, so this is the same shape the
    composed SVG will draw; measuring the metrics by hand would drift.
    """
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="4000" height="{int(size * 2.4)}">'
        f'<text x="20" y="{int(size * 1.7)}" font-family="{family}" font-weight="{weight}" '
        f'font-size="{size}" letter-spacing="{spacing}" fill="#ffffff">{_esc(text)}</text></svg>'
    )
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as fh:
        fh.write(svg)
        path = fh.name
    png = path + ".png"
    env = dict(os.environ, FONTCONFIG_FILE=str(fc))
    subprocess.run(["rsvg-convert", "-w", "4000", path, "-o", png], check=True, env=env)
    out = subprocess.run(["magick", png, "-background", "none", "-trim", "-format", "%w", "info:"],
                         capture_output=True, text=True, check=True).stdout.strip()
    os.unlink(path)
    os.unlink(png)
    return float(out or 0)


def wrap(text: str, size: float, weight: int, family: str, spacing: float,
         max_w: float, fc: Path) -> list[str]:
    """Greedy word wrap that never lets a line exceed [max_w]."""
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if current and text_width(candidate, size, weight, family, spacing, fc) > max_w:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


_crop_dir: Path | None = None

# Per-device frame recipe. The emulator status bar carries icons that are not
# the app's (a VPN shield, a microphone, an avatar) plus a "3G" tag, and the
# large-screen emulator adds a system taskbar: both are cropped out so the
# mockup shows the app and nothing of the emulator. Sizes are the ones Play
# asks for: 1080x1920 phone, 1200x1920 7-inch, 1600x2560 10-inch.
DEVICES = {
    "phone": dict(canvas=(1080, 1920), raw=(1080, 2340), crop_top=118, crop_bottom=0,
                  content_frac=0.559, gutter_frac=0.078, head_frac=0.0685, out="framed"),
    "tablet7": dict(canvas=(1200, 1920), raw=(1200, 1920), crop_top=72, crop_bottom=112,
                    content_frac=0.62, gutter_frac=0.078, head_frac=0.0685, out="framed-7in"),
    "tablet10": dict(canvas=(1600, 2560), raw=(1600, 2560), crop_top=96, crop_bottom=150,
                     content_frac=0.62, gutter_frac=0.078, head_frac=0.0685, out="framed-10in"),
}


def cropped(path: Path, spec: dict) -> Path:
    global _crop_dir
    if _crop_dir is None:
        _crop_dir = Path(tempfile.mkdtemp(prefix="glosso-store-crop-"))
    # Key on the parent too: every locale has a file named today.png.
    out = _crop_dir / f"{path.parent.name}__{spec['out']}__{path.name}"
    if out.exists():
        return out
    raw_w, raw_h = spec["raw"]
    geom = f"{raw_w}x{raw_h - spec['crop_top'] - spec['crop_bottom']}+0+{spec['crop_top']}"
    subprocess.run(["magick", str(path), "-crop", geom, "+repage", str(out)], check=True)
    return out


def data_uri(path: Path) -> str:
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode()


# The paths in the launcher foreground are authored in the owner's 501x418 logo
# box (the same numbers as docs/design/logo/glosso-logo.svg). The drawable then
# places and scales that box inside its 108-unit viewport.
LOGO_BOX_W, LOGO_BOX_H = 501.0, 418.0


def logo_geometry() -> tuple[list[tuple[str, str]]]:
    """The app's real symbol: each path of the launcher foreground and its colour.

    Reading the drawable (instead of re-drawing the logo) keeps the store assets
    in step with the icon the app actually ships, colour included.
    """
    xml = (REPO / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml").read_text()
    paths = re.findall(r'<path[^>]*?android:fillColor="([^"]+)"[^>]*?android:pathData="([^"]+)"', xml, re.S)
    if len(paths) < 2:
        raise RuntimeError("ic_launcher_foreground.xml no longer has two coloured paths")
    return [(color, d) for color, d in paths]


def logo_group(x: float, y: float, width: float, geom: tuple) -> str:
    """The symbol scaled to [width], top-left at (x, y)."""
    scale = width / LOGO_BOX_W
    paths = "".join(
        f'<path fill-rule="evenodd" clip-rule="evenodd" d="{d}" fill="{color}" '
        f'stroke="{color}" stroke-width="0.25"/>'
        for color, d in geom
    )
    return f'<g transform="translate({x:.1f},{y:.1f}) scale({scale:.6f})">{paths}</g>'


def text_lines(lines: list[str], x: float, y: float, size: float, leading: float,
               family: str, weight: int, fill: str, spacing: float = 0.0,
               anchor: str = "start") -> str:
    parts = [f'<text x="{x}" y="{y}" font-family="{family}" font-weight="{weight}" '
             f'font-size="{size}" fill="{fill}" letter-spacing="{spacing}" text-anchor="{anchor}">']
    for i, line in enumerate(lines):
        parts.append(f'<tspan x="{x}" dy="{0 if i == 0 else leading}">{line}</tspan>')
    parts.append("</text>")
    return "".join(parts)


def phone_frame(x: float, y: float, content_w: float, raw: Path, clip_id: str,
                aspect: float) -> tuple[str, float, float]:
    """A hand-built phone mockup with [raw] clipped into it."""
    bezel = content_w * 0.024
    radius = content_w * 0.13
    content_h = content_w * aspect
    body_w = content_w + 2 * bezel
    body_h = content_h + 2 * bezel
    svg = (
        f'<rect x="{x}" y="{y}" width="{body_w:.1f}" height="{body_h:.1f}" rx="{radius + bezel:.1f}" '
        f'fill="#08080C" stroke="#ffffff" stroke-opacity="0.10" stroke-width="2"/>'
        f'<clipPath id="{clip_id}">'
        f'<rect x="{x + bezel:.1f}" y="{y + bezel:.1f}" width="{content_w:.1f}" height="{content_h:.1f}" rx="{radius:.1f}"/>'
        "</clipPath>"
        f'<image xlink:href="{data_uri(raw)}" x="{x + bezel:.1f}" y="{y + bezel:.1f}" '
        f'width="{content_w:.1f}" height="{content_h:.1f}" preserveAspectRatio="xMidYMid slice" '
        f'clip-path="url(#{clip_id})"/>'
        # A hairline over the screen edge reads as glass, not a flat sticker.
        f'<rect x="{x + bezel:.1f}" y="{y + bezel:.1f}" width="{content_w:.1f}" height="{content_h:.1f}" '
        f'rx="{radius:.1f}" fill="none" stroke="#ffffff" stroke-opacity="0.14" stroke-width="2"/>'
    )
    return svg, body_w, body_h


def backdrop(w: int, h: int) -> str:
    """Brand background: near-black ink with a cobalt bloom behind the device."""
    return (
        f'<defs>'
        f'<linearGradient id="bg" x1="0" y1="0" x2="0.4" y2="1">'
        f'<stop offset="0" stop-color="{INK}"/><stop offset="1" stop-color="{INK_DEEP}"/>'
        f"</linearGradient>"
        f'<radialGradient id="bloom" cx="50%" cy="46%" r="60%">'
        f'<stop offset="0" stop-color="{COBALT}" stop-opacity="0.30"/>'
        f'<stop offset="0.55" stop-color="{COBALT}" stop-opacity="0.08"/>'
        f'<stop offset="1" stop-color="{COBALT}" stop-opacity="0"/>'
        f"</radialGradient>"
        f'<radialGradient id="glow" cx="50%" cy="55%" r="55%">'
        f'<stop offset="0" stop-color="{COBALT_LIGHT}" stop-opacity="0.18"/>'
        f'<stop offset="1" stop-color="{COBALT_LIGHT}" stop-opacity="0"/>'
        f"</radialGradient>"
        f"</defs>"
        f'<rect width="{w}" height="{h}" fill="url(#bg)"/>'
        f'<rect width="{w}" height="{h}" fill="url(#bloom)"/>'
    )


def framed_screen(raw: Path, locale: str, screen: str, geom: tuple, fc: Path,
                  spec: dict) -> tuple[str, int, int]:
    w, h = spec["canvas"]
    gutter = w * spec["gutter_frac"]
    max_text_w = w - 2 * gutter
    headline, subline = HEADLINES[locale][screen]

    head_size = w * spec["head_frac"]
    head_spacing = -head_size * 0.032
    lines = wrap(headline, head_size, 800, BY_FAMILY["display"], head_spacing, max_text_w, fc)
    leading = head_size * 1.06
    head_h = leading * (len(lines) - 1) + head_size * 1.15
    sub_size = head_size * 0.42
    sub_h = sub_size * 1.3

    img = cropped(raw, spec)
    raw_w, raw_h = spec["raw"]
    aspect = (raw_h - spec["crop_top"] - spec["crop_bottom"]) / raw_w
    content_w = w * spec["content_frac"]
    content_h = content_w * aspect
    bezel = content_w * 0.024
    body_h = content_h + 2 * bezel
    body_w = content_w + 2 * bezel
    px = (w - body_w) / 2
    py = h - body_h - h * 0.024

    # The wordmark is pinned top-left; the headline block is centred in the
    # space left above the device, so screens with 2 or 3 lines look deliberate.
    block_top = h * 0.104
    block_h = head_h + head_size * 0.4 + sub_h
    start = block_top + max(0.0, (py - h * 0.02 - block_top - block_h) / 2)
    head_y = start + head_size
    sub_y = start + head_h + head_size * 0.4 + sub_size

    mark_w = head_size * 0.62
    mark = logo_group(gutter, h * 0.044, mark_w, geom)
    wordmark = text_lines(["glosso"], gutter + mark_w * 1.38, h * 0.064, head_size * 0.54, 0,
                          BY_FAMILY["display"], 800, TEXT, -0.6)
    head = text_lines(lines, gutter, head_y, head_size, leading, BY_FAMILY["display"], 800, TEXT, head_spacing)
    sub = text_lines(wrap(subline, sub_size, 500, BY_FAMILY["ui"], 0, max_text_w, fc),
                     gutter, sub_y, sub_size, 0, BY_FAMILY["ui"], 500, MUTED, 0)

    frame, _, _ = phone_frame(px, py, content_w, img, f"clip-{locale}-{screen}", aspect)
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        f'width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
        + backdrop(w, h)
        + f'<ellipse cx="{w/2}" cy="{py + body_h*0.5:.0f}" rx="{body_w*1.05:.0f}" ry="{body_h*0.62:.0f}" fill="url(#glow)"/>'
        + mark + wordmark + head + sub + frame
        + "</svg>"
    )
    return svg, w, h


def icon_svg(geom: tuple) -> str:
    size = 512
    mark_w = size * 0.64
    x = (size - mark_w) / 2
    y = (size - mark_w * LOGO_BOX_H / LOGO_BOX_W) / 2
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 {size} {size}">'
        f'<rect width="{size}" height="{size}" fill="{INK}"/>'
        + logo_group(x, y, mark_w, geom)
        + "</svg>"
    )


def png32(path: Path) -> None:
    """Re-encode as a 32-bit RGBA PNG, which is what the Play icon expects."""
    subprocess.run(["magick", str(path), "-alpha", "on", "-define", "png:color-type=6", str(path)],
                   check=True)


# The feature graphic's headline is the onboarding promise; only the subtitle is
# feature-specific.
FEATURE_SUBLINES = {
    "en": "Pronunciation you can measure, sound by sound.",
    "es": "Pronunciación que se mide, sonido a sonido.",
    "fr": "Une prononciation qui se mesure, son par son.",
    "de": "Aussprache, die sich messen lässt, Laut für Laut.",
}

FEATURE_TEXT = {loc: (HEADLINES[loc]["onboarding"][0], sub) for loc, sub in FEATURE_SUBLINES.items()}


def feature_svg(raw: Path, locale: str, geom: tuple, fc: Path) -> str:
    w, h = 1024, 500
    gut = 72
    text_w = 560.0
    headline, subline = FEATURE_TEXT[locale]
    mark = logo_group(gut, 74, 38, geom)
    wordmark = text_lines(["glosso"], gut + 52, 106, 34, 0, BY_FAMILY["display"], 800, TEXT, -0.5)
    head = text_lines(wrap(headline, 50, 800, BY_FAMILY["display"], -1.6, text_w, fc),
                      gut, 226, 50, 55, BY_FAMILY["display"], 800, TEXT, -1.6)
    sub = text_lines(wrap(subline, 25, 500, BY_FAMILY["ui"], 0, text_w, fc),
                     gut, 404, 25, 33, BY_FAMILY["ui"], 500, MUTED, 0)
    phone = DEVICES["phone"]
    img = cropped(raw, phone)
    aspect = (phone["raw"][1] - phone["crop_top"] - phone["crop_bottom"]) / phone["raw"][0]
    content_w = 210.0
    content_h = content_w * aspect
    bezel = content_w * 0.024
    body_w = content_w + 2 * bezel
    body_h = content_h + 2 * bezel
    px = w - body_w - 70
    py = h - body_h - 20
    frame, _, _ = phone_frame(px, py, content_w, img, "clip-feature", aspect)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
        f'width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
        + backdrop(w, h)
        + f'<ellipse cx="{px + body_w/2:.0f}" cy="{py + body_h/2:.0f}" rx="{body_w*1.3:.0f}" ry="{body_h*0.7:.0f}" fill="url(#glow)"/>'
        + mark + wordmark + head + sub + frame
        + "</svg>"
    )


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--locale", default="en,es,fr,de")
    ap.add_argument("--only", default="phones,icon,feature,tablets")
    args = ap.parse_args()
    only = set(args.only.split(","))

    geom = logo_geometry()
    fc, _ = fontconfig()

    locales = [l.strip() for l in args.locale.split(",") if l.strip()]

    if "phones" in only:
        for locale in locales:
            spec = DEVICES["phone"]
            for i, screen in enumerate(PHONES, start=1):
                raw = RAW / locale / f"{screen}.png"
                if not raw.exists():
                    print(f"  skip {locale}/{screen}: no raw")
                    continue
                svg, w, h = framed_screen(raw, locale, screen, geom, fc, spec)
                render(svg, OUT / f"screenshots/{spec['out']}/{locale}/{i:02d}_{screen}.png", w, h, fc)

    if "tablets" in only:
        for device in ("tablet7", "tablet10"):
            spec = DEVICES[device]
            for locale in locales:
                for i, screen in enumerate(TABLETS, start=1):
                    raw = RAW_TABLETS / device / locale / f"{screen}.png"
                    if not raw.exists():
                        print(f"  skip {device}/{locale}/{screen}: no raw")
                        continue
                    svg, w, h = framed_screen(raw, locale, screen, geom, fc, spec)
                    render(svg, OUT / f"screenshots/{spec['out']}/{locale}/{i:02d}_{screen}.png", w, h, fc)

    if "icon" in only:
        icon = OUT / "icon-512.png"
        render(icon_svg(geom), icon, 512, 512, fc)
        png32(icon)

    if "feature" in only:
        for locale in locales:
            if locale not in FEATURE_TEXT:
                print(f"  skip feature/{locale}: no feature text")
                continue
            name = "feature-1024x500.png" if locale == "en" else f"feature-1024x500.{locale}.png"
            shot = RAW / locale / "feedback.png"
            if not shot.exists():
                print(f"  skip feature/{locale}: no feedback raw")
                continue
            render(feature_svg(shot, locale, geom, fc), OUT / name, 1024, 500, fc)


if __name__ == "__main__":
    main()
