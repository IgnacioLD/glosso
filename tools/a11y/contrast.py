#!/usr/bin/env python3
"""Auditoria de contraste WCAG 2.1 del sistema de diseno de Glosso Pro.

Lee los tokens de color directamente de
`androidApp/src/main/java/com/glosso/app/designsystem/GlossoColor.kt` y calcula
el ratio de contraste de los pares semanticos (texto/fondo, botones, chips,
estados, enlaces y las tarjetas rellenas). No hay valores duplicados: si un
token cambia en Kotlin, la tabla cambia sola.

Uso:
    python3 tools/a11y/contrast.py            # tabla y resumen
    python3 tools/a11y/contrast.py --check    # sale 1 si hay algun fallo
    python3 tools/a11y/contrast.py --markdown # tabla en markdown

Criterio (WCAG 2.1 AA):
    - texto normal:            4.5:1
    - texto grande:            3.0:1
    - UI no textual:           3.0:1
    - estados deshabilitados:  exentos, se reportan pero no cuentan
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DS = REPO / "androidApp/src/main/java/com/glosso/app/designsystem"
COLOR_SRC = DS / "GlossoColor.kt"
THEME_SRC = DS / "GlossoTheme.kt"
ELEMENTS_SRC = REPO / "androidApp/src/main/java/com/glosso/app/GlossoElements.kt"

# Umbrales WCAG 2.1 AA.
TEXT = 4.5
LARGE = 3.0
UI = 3.0
EXEMPT = 0.0


# --------------------------------------------------------------------------- #
# Color y contraste
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class RGBA:
    r: int
    g: int
    b: int
    a: float = 1.0

    @staticmethod
    def from_argb(value: int) -> "RGBA":
        alpha = (value >> 24) & 0xFF
        return RGBA(
            (value >> 16) & 0xFF,
            (value >> 8) & 0xFF,
            value & 0xFF,
            alpha / 255.0,
        )

    def over(self, background: "RGBA") -> "RGBA":
        """Composita este color (con alfa) sobre un fondo opaco."""
        if self.a >= 1.0:
            return self
        return RGBA(
            round(self.r * self.a + background.r * (1 - self.a)),
            round(self.g * self.a + background.g * (1 - self.a)),
            round(self.b * self.a + background.b * (1 - self.a)),
            1.0,
        )

    def hex(self) -> str:
        return f"#{self.r:02X}{self.g:02X}{self.b:02X}"

    def _linear(self, channel: int) -> float:
        c = channel / 255.0
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    @property
    def luminance(self) -> float:
        return (
            0.2126 * self._linear(self.r)
            + 0.7152 * self._linear(self.g)
            + 0.0722 * self._linear(self.b)
        )


def contrast(fg: RGBA, bg: RGBA) -> float:
    hi, lo = sorted((fg.luminance, bg.luminance), reverse=True)
    return (hi + 0.05) / (lo + 0.05)


# --------------------------------------------------------------------------- #
# Parser del fuente Kotlin
# --------------------------------------------------------------------------- #

ARG = r"[0-9A-Fa-f]{1,8}"


def _object_block(src: str, name: str) -> str:
    match = re.search(rf"object {name} \{{(.*?)\n\}}", src, re.S)
    if not match:
        raise SystemExit(f"no encuentro el object {name} en {COLOR_SRC}")
    return match.group(1)


def _val_block(src: str, name: str) -> str:
    match = re.search(rf"val {name} = \w+\((.*?)\n\)", src, re.S)
    if not match:
        raise SystemExit(f"no encuentro el val {name} en {COLOR_SRC}")
    return match.group(1)


def _entries(block: str) -> dict[str, str]:
    return {
        key: expr.strip().rstrip(",")
        for key, expr in re.findall(r"^\s*(?:val )?(\w+) = (.+?),?\s*$", block, re.M)
    }


def _is_color(expr: str) -> bool:
    expr = expr.strip()
    return bool(re.match(r"(\w+\.\w+$)|Color\(0x", expr))


def _resolve(expr: str, brand: dict[str, RGBA]) -> RGBA:
    expr = expr.strip()
    ref = re.match(r"(\w+)\.(\w+)$", expr)
    if ref:
        return brand[f"{ref.group(1)}.{ref.group(2)}"]
    literal = re.match(rf"Color\(0x({ARG})\)", expr)
    if literal:
        return RGBA.from_argb(int(literal.group(1), 16))
    raise SystemExit(f"no se resolver el color: {expr!r}")


def _alpha(path: Path, pattern: str) -> float:
    """Lee un alfa literal (p.ej. el relleno de un chip) del componente que lo pinta."""
    match = re.search(pattern, path.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit(f"no encuentro el alfa {pattern!r} en {path}")
    return float(match.group(1))


# El hero retematiza los roles con blanco translucido (GlossoTheme.kt, OnHero).
HERO_TINT_ALPHA = _alpha(THEME_SRC, r"surfaceVariant = white\.copy\(alpha = ([\d.]+)f\)")
# El chip de racha tine el fondo con el propio ascua (GlossoElements.kt).
STREAK_TINT_ALPHA = _alpha(ELEMENTS_SRC, r"GlossoColors\.ember\.copy\(alpha = ([\d.]+)f\)")


def load_tokens() -> dict[str, RGBA]:
    src = COLOR_SRC.read_text(encoding="utf-8")
    brand: dict[str, RGBA] = {}
    for obj in ("BrandNight", "BrandDay"):
        for key, expr in _entries(_object_block(src, obj)).items():
            brand[f"{obj}.{key}"] = _resolve(expr, brand)

    tokens = dict(brand)
    for extended in ("DarkExtended", "LightExtended"):
        for key, expr in _entries(_val_block(src, extended)).items():
            if _is_color(expr):
                tokens[f"{extended}.{key}"] = _resolve(expr, brand)

    scheme_names = {
        "GlossoDarkColorScheme": "DarkScheme",
        "GlossoLightColorScheme": "LightScheme",
    }
    for val_name, prefix in scheme_names.items():
        for key, expr in _entries(_val_block(src, val_name)).items():
            if _is_color(expr):
                tokens[f"{prefix}.{key}"] = _resolve(expr, brand)

    # El tema claro tambien lee el cobalto de marca como primario.
    return tokens


# --------------------------------------------------------------------------- #
# Pares a auditar
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class Check:
    theme: str
    group: str
    label: str
    fg: str
    bg: str
    kind: str = "text"  # text | large | ui | exempt


def build_checks(t: dict[str, RGBA]) -> list[Check]:
    """Pares semanticos tal y como los pinta la app.

    Las referencias son claves del diccionario de tokens; para los roles con
    alfa se composita sobre el fondo indicado en el mismo check.
    """
    out: list[Check] = []

    for theme in ("Dark", "Light"):
        s = f"{theme}Scheme"
        e = f"{theme}Extended"
        brand = "BrandNight" if theme == "Dark" else "BrandDay"

        # Texto sobre el chrome.
        out += [
            Check(theme, "texto/fondo", "texto principal sobre fondo", f"{s}.onBackground", f"{s}.background"),
            Check(theme, "texto/fondo", "texto secundario sobre fondo", f"{s}.onSurfaceVariant", f"{s}.background"),
            Check(theme, "texto/superficie", "texto principal sobre tarjeta", f"{s}.onSurface", f"{s}.surface"),
            Check(theme, "texto/superficie", "texto secundario sobre tarjeta", f"{s}.onSurfaceVariant", f"{s}.surface"),
            Check(theme, "texto/raised", "texto principal sobre raised", f"{s}.onSurface", f"{s}.surfaceVariant"),
            Check(theme, "texto/raised", "texto secundario sobre raised", f"{s}.onSurfaceVariant", f"{s}.surfaceVariant"),
        ]

        # Acento como texto (accentText), enlaces y estados.
        out += [
            Check(theme, "acento", "acento como texto sobre fondo", f"{e}.accentText", f"{s}.background"),
            Check(theme, "acento", "acento como texto sobre tarjeta", f"{e}.accentText", f"{s}.surface"),
            Check(theme, "enlaces", "enlace (acento) sobre fondo", f"{e}.accentText", f"{s}.background"),
            Check(theme, "enlaces", "enlace quieto sobre fondo", f"{s}.onSurfaceVariant", f"{s}.background"),
        ]

        # Botones.
        out += [
            Check(theme, "botones", "primario: etiqueta sobre relleno", f"{s}.onPrimary", f"{s}.primary"),
            Check(theme, "botones", "secundario: contenido sobre relleno", f"{s}.onSurface", f"{s}.surfaceVariant"),
            Check(theme, "botones", "peligro: texto sobre fondo", f"{e}.danger", f"{s}.background"),
            Check(theme, "botones", "peligro: etiqueta sobre relleno", f"{e}.onDanger", f"{e}.danger"),
            Check(theme, "botones", "deshabilitado: contenido muted sobre relleno (exento)", f"{s}.onSurfaceVariant", f"{s}.surfaceVariant", "exempt"),
        ]
        if theme == "Dark":
            # Solo en oscuro: si la etiqueta usara el texto del tema (#F4F4F6)
            # en vez del onPrimary blanco. Es el par que se midio como fallo.
            out += [
                Check(theme, "botones", "primario: texto del tema sobre relleno (peor caso)", f"{brand}.Text", f"{s}.primary"),
            ]

        # Chips y pildoras.
        out += [
            Check(theme, "chips", "tag: texto sobre relleno", f"{s}.onSurface", f"{s}.surfaceVariant"),
            Check(theme, "chips", "racha viva sobre tinte", f"{e}.ember", f"{e}.ember", "text"),
            Check(theme, "chips", "racha viva sobre raised (base)", f"{e}.ember", f"{s}.surfaceVariant"),
            Check(theme, "chips", "racha a cero: texto sobre relleno", f"{s}.onSurface", f"{s}.surfaceVariant"),
        ]

        # Estados.
        out += [
            Check(theme, "estados", "exito como texto sobre fondo", f"{e}.success", f"{s}.background"),
            Check(theme, "estados", "exito: etiqueta sobre relleno", f"{e}.onSuccess", f"{e}.success"),
            Check(theme, "estados", "exito: contenedor", f"{e}.onSuccessContainer", f"{e}.successContainer"),
            Check(theme, "estados", "aviso como texto sobre fondo", f"{e}.warning", f"{s}.background"),
            Check(theme, "estados", "aviso: contenedor", f"{e}.onWarningContainer", f"{e}.warningContainer"),
            Check(theme, "estados", "peligro como texto sobre fondo", f"{e}.danger", f"{s}.background"),
            Check(theme, "estados", "peligro: contenedor (errorContainer)", f"{s}.onErrorContainer", f"{s}.errorContainer"),
            Check(theme, "estados", "error: etiqueta sobre relleno", f"{s}.onError", f"{s}.error"),
        ]

        # Contenedores secundarios / terciario.
        out += [
            Check(theme, "contenedores", "secondaryContainer", f"{s}.onSecondaryContainer", f"{s}.secondaryContainer"),
            Check(theme, "contenedores", "tertiary", f"{s}.onTertiary", f"{s}.tertiary"),
        ]

        # Tarjeta rellena (hero). El contenido se retematiza con OnHero.
        out += [
            Check(theme, "tarjeta hero", "titulo/kicker sobre hero", f"{e}.onHero", f"{e}.hero"),
            Check(theme, "tarjeta hero", "texto muted sobre hero", f"{e}.onHeroMuted", f"{e}.hero"),
            Check(theme, "tarjeta hero", "chip sobre hero (blanco translucido)", f"{e}.onHero", f"{e}.hero"),
        ]

    return out


# --------------------------------------------------------------------------- #

def _composite_spec(t: dict[str, RGBA], check: Check) -> tuple[RGBA, RGBA]:
    """Devuelve (fg, bg) ya compuestos.

    Algunos pares se pintan sobre un alfa del propio color (chips). Se detectan
    por la etiqueta para no inventar reglas genericas.
    """
    fg = t[check.fg]
    bg = t[check.bg]

    if "racha viva sobre tinte" in check.label:
        # El relleno del chip es el ascua con alfa sobre la superficie.
        base = t[f"{check.theme}Scheme.surfaceVariant"]
        bg = RGBA(fg.r, fg.g, fg.b, STREAK_TINT_ALPHA).over(base)
    elif "chip sobre hero" in check.label:
        # El chip del hero es blanco translucido sobre el cobalto.
        bg = RGBA(255, 255, 255, HERO_TINT_ALPHA).over(bg)

    # Un color de texto con alfa se lee compositado sobre su fondo.
    if fg.a < 1.0:
        fg = fg.over(bg)

    return fg, bg


def evaluate(checks: list[Check], t: dict[str, RGBA]) -> list[tuple[Check, float, float, bool]]:
    rows = []
    for check in checks:
        fg, bg = _composite_spec(t, check)
        ratio = contrast(fg, bg)
        threshold = {"text": TEXT, "large": LARGE, "ui": UI, "exempt": EXEMPT}[check.kind]
        ok = check.kind == "exempt" or ratio >= threshold
        rows.append((check, ratio, threshold, ok))
    return rows


def render(rows, markdown: bool) -> str:
    if markdown:
        lines = [
            "| tema | grupo | par | ratio | minimo | pasa |",
            "|---|---|---|---:|---:|:--:|",
        ]
        for check, ratio, threshold, ok in rows:
            need = "exento" if check.kind == "exempt" else f"{threshold:.1f}"
            mark = "si" if ok else "NO"
            lines.append(
                f"| {check.theme} | {check.group} | {check.label} | "
                f"{ratio:.2f}:1 | {need} | {mark} |"
            )
        return "\n".join(lines)

    lines = []
    theme = None
    group = None
    for check, ratio, threshold, ok in rows:
        if check.theme != theme:
            theme = check.theme
            group = None
            lines.append("")
            lines.append(f"== tema {theme.lower()} ==")
        if check.group != group:
            group = check.group
            lines.append(f"  -- {group} --")
        need = "exento" if check.kind == "exempt" else f"{threshold:.1f}"
        mark = "ok " if ok else "FALLA"
        lines.append(f"    [{mark}] {ratio:5.2f}:1  (min {need})  {check.label}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="sale 1 si hay fallos")
    parser.add_argument("--markdown", action="store_true", help="tabla markdown")
    args = parser.parse_args()

    tokens = load_tokens()
    checks = build_checks(tokens)
    rows = evaluate(checks, tokens)

    print(render(rows, markdown=args.markdown))

    failures = [r for r in rows if not r[3]]
    exempt = [r for r in rows if r[0].kind == "exempt"]
    print()
    print(
        f"{len(rows)} pares, {len(failures)} fallos, "
        f"{len(exempt)} exentos (deshabilitados)"
    )
    if failures:
        print("fallos:")
        for check, ratio, threshold, _ in failures:
            print(f"  - [{check.theme}] {check.label}: {ratio:.2f} < {threshold:.1f}")

    return 1 if (args.check and failures) else 0


if __name__ == "__main__":
    sys.exit(main())
