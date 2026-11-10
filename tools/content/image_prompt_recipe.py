"""Turn a catalogue `imagePrompt` into the flat line-illustration recipe.

The catalogue's stored `imagePrompt` values follow one template:

    abstract editorial illustration, <subject>,
    thin brass-gold continuous line on warm black background, minimal,
    lots of negative space, subtle grain, no text, 16:9

That template produced ambiguous, accidentally framed renders: "abstract" made
the model guess at the subject, "subtle grain" and the implicit fill broke the
vectoriser, and there was no composition instruction at all. The validated
recipe replaces the style and the composition, drops every "abstract", and
keeps the subject (rewritten only when it names something impossible).

This module is the mechanical part: `rewrite(subject)` returns the new prompt
for a template prompt's subject. Curated subject rewrites (a handful of prompts
that describe an incoherent artifact) live in a separate JSON passed as
`--overrides`, never hard-coded here.
"""

import argparse
import json
import re

PREFIX = "abstract editorial illustration, "
STYLE = ("thin brass-gold continuous line on warm black background, "
         "minimal, lots of negative space, subtle grain")
HEADER = "editorial line illustration"
NEW_STYLE = (
    "every shape drawn as a thin open single-weight brass-gold outline on a "
    "flat near-black background, absolutely no solid fills and no filled "
    "shapes, no glow and no gradients, no grain and no texture, flat two-tone "
    "image with only black and one gold tone, crisp uniform lines, no frames, "
    "no borders and no boxes around the subject"
)
NEW_COMPOSITION = (
    "centred and balanced composition with generous empty space, a ground line "
    "anchoring the scene, nothing cropped and nothing floating"
)
TAIL = "no text, 16:9"


def split_subject(image_prompt):
    """Extract the subject from the catalogue template, or None if it differs."""
    if not image_prompt.startswith(PREFIX) or not image_prompt.endswith(", " + TAIL):
        return None
    middle = image_prompt[len(PREFIX):-len(", " + TAIL)]
    if STYLE not in middle:
        return None
    return middle.replace(STYLE, "").strip().rstrip(",").strip()


def strip_abstract(subject):
    """Drop every 'abstract' and repair the a/an that removal can break."""
    out = re.sub(r"\babstract\b\s*", "", subject)
    out = re.sub(r"\s+", " ", out).strip()
    out = re.sub(r"\ban (?=[bcdfghjklmnpqrstvwxyz])", "a ", out)
    out = re.sub(r"\ba (?=[aeiou])", "an ", out)
    return out.strip(" ,")


def rewrite(subject, override=None):
    """Flat line-illustration prompt for `subject` (override replaces the subject)."""
    subject = strip_abstract(override if override is not None else subject)
    return f"{HEADER}, {subject}, {NEW_STYLE}, {NEW_COMPOSITION}, {TAIL}"


def rewrite_prompt(image_prompt, override=None):
    """`rewrite` for a full catalogue prompt; non-template prompts pass through."""
    subject = split_subject(image_prompt)
    if subject is None:
        return image_prompt
    return rewrite(subject, override)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("image_prompt", help="a catalogue imagePrompt string")
    ap.add_argument("--overrides", help="JSON object mapping subject -> replacement")
    args = ap.parse_args()
    override = None
    if args.overrides:
        with open(args.overrides, encoding="utf-8") as fh:
            mapping = json.load(fh)
        subject = split_subject(args.image_prompt)
        override = mapping.get(subject) if subject else None
    print(rewrite_prompt(args.image_prompt, override))


if __name__ == "__main__":
    main()
