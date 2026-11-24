#!/usr/bin/env python3
"""Drive the Glosso debug build on an emulator and capture the store raws.

This is the reproducible half of the store assets: it configures a permitted
emulator, walks the app through the screens the listing shows, and writes one
PNG per screen to `docs/store/screenshots/raw/<locale>/`. The composed (framed,
headline-bearing) assets are produced separately by `compose.py`.

Requirements:
  - `:androidApp` debug build with the store-capture flag:
      ./gradlew :androidApp:assembleDebug -PglossoStoreScreenshots=true \
          -PglossoDebugReleaseSigning=true
  - the APK installed on the target emulator
  - `adb` on PATH (or ANDROID_HOME set)

Device rule: only the emulators 5558, 5560, 5562, 5568 and
5570 are ever touched; the owner's phone and 5554/5556 are never used.

Usage:
    python3 tools/store/capture.py [--serial emulator-5568] [--locales en,es,fr,de]
                                   [--device phone|tablet7|tablet10]
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PKG = "me.shirobyte42.glosso"
ACTIVITY = f"{PKG}/com.glosso.app.MainActivity"
RAW = REPO / "docs/store/screenshots/raw"
RAW_TABLETS = REPO / "docs/store/screenshots/raw-tablets"

# Emulator geometry: pixel size, density, and the labels below.
GEOMETRY = {
    "phone": ("1080x2340", 480),
    "tablet7": ("1200x1920", 240),
    "tablet10": ("1600x2560", 320),
}

# Strings that mark a screen has settled, per locale.
# Strings that mark a screen has settled, per locale. They are picked so they
# do not collide with the persistent bottom-bar labels (which would make a wait
# succeed on any screen).
MARKERS = {
    "en": {
        "onboarding": "Learn the language",
        "today": "Your lesson",
        "practice": "Your practice",
        "feedback": "Next exercise",
        "you": "Pronunciation",
        "lessons": "Start here",
        "library": "Free practice",
        "stories": "A2 ·",
        "story": "Paragraph",
        "dialogue": "Read or listen",
        "speak": "Your turn",
        "context": "Not said",
    },
    "es": {
        "onboarding": "Aprende el idioma",
        "today": "Tu lección",
        "practice": "Tu práctica",
        "feedback": "Siguiente",
        "you": "Pronunciaci",
        "lessons": "Empieza",
        "library": "Práctica libre",
        "stories": "A2 ·",
        "story": "Párrafo",
        "dialogue": "Lee o escucha",
        "speak": "Tu turno",
        "context": "No se dice",
    },
    "fr": {
        "onboarding": "Apprends la langue",
        "today": "Ta leçon",
        "practice": "Votre entraînement",
        "feedback": "Exercice suivant",
        "you": "Prononciation",
        "lessons": "Commence ici",
        "library": "Entraînement libre",
        "stories": "A2 ·",
        "story": "Paragraphe",
        "dialogue": "Lis ou écoute",
        "speak": "À toi",
        "context": "Ne se dit pas",
    },
    "de": {
        "onboarding": "Lerne die Sprache",
        "today": "Deine Lektion",
        "practice": "Dein Üben",
        "feedback": "Nächste Übung",
        "you": "Aussprache",
        "lessons": "Hier starten",
        "library": "Freies Üben",
        "stories": "A2 ·",
        "story": "Absatz",
        "dialogue": "Lies oder höre",
        "speak": "Du bist dran",
        "context": "Sagt man nicht",
    },
}

# Tap targets are the same layout across locales; text is not needed. Normalised
# to the phone geometry and rescaled per device.
def taps(w: int, h: int) -> dict[str, tuple[int, int]]:
    def p(fx: float, fy: float) -> tuple[int, int]:
        return int(w * fx), int(h * fy)
    return {
        "nav_today": p(0.125, 0.9479),
        "nav_lessons": p(0.375, 0.9479),
        "nav_you": p(0.625, 0.9479),
        "nav_library": p(0.875, 0.9479),
        "library_practice": p(0.5, 0.2991),
        "library_stories": p(0.5, 0.4218),
        "story_first": p(0.5, 0.2991),
        "onboarding_skip": p(0.885, 0.1047),
        "practice_inject": p(0.9111, 0.0893),
        "back": p(0.0889, 0.0893),
    }


def adb(serial: str, *args: str, check: bool = True) -> str:
    res = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {res.stderr.strip()}")
    return res.stdout


def screencap(serial: str, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as fh:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], stdout=fh, check=True)


def nodes(serial: str) -> list[tuple[str, int, int]]:
    # uiautomator refuses to dump while the window is still settling; retry a
    # few times and treat a persistent failure as "no nodes" rather than dying,
    # so a flaky dump never aborts a whole capture run.
    xml = ""
    for _ in range(4):
        out = adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        if "UI hierchary dumped to" in out or "dumped to" in out:
            xml = adb(serial, "shell", "cat", "/sdcard/ui.xml", check=False)
            if xml.strip().startswith("<?xml"):
                break
        time.sleep(0.6)
    out = []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return out
    for n in root.iter("node"):
        text = (n.get("text") or n.get("content-desc") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not text or not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def find_text(serial: str, needle: str) -> tuple[int, int] | None:
    needle = needle.lower()
    for text, x, y in nodes(serial):
        if needle in text.lower():
            return x, y
    return None


def wait_for(serial: str, needle: str, timeout: float = 20.0) -> bool:
    end = time.time() + timeout
    while time.time() < end:
        if find_text(serial, needle):
            return True
        time.sleep(0.6)
    return False


def tap(serial: str, xy: tuple[int, int]) -> None:
    adb(serial, "shell", "input", "tap", str(xy[0]), str(xy[1]))


def tap_bottom(serial: str, label: str) -> bool:
    """Tap the lowest node whose text is [label] (the bottom-bar item, not a title)."""
    hits = [n for n in nodes(serial) if n[0].lower() == label.lower()]
    if not hits:
        hits = [n for n in nodes(serial) if label.lower() in n[0].lower()]
    if not hits:
        return False
    _, x, y = max(hits, key=lambda n: n[2])
    tap(serial, (x, y))
    return True


def tap_text(serial: str, label: str) -> bool:
    hit = find_text(serial, label)
    if not hit:
        return False
    tap(serial, hit)
    return True


def tap_right_of(serial: str, label: str, offset: int) -> bool:
    """Tap [offset] px right of the right edge of the node that shows [label]."""
    adb(serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
    xml = adb(serial, "shell", "cat", "/sdcard/ui.xml", check=False)
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return False
    for n in root.iter("node"):
        text = (n.get("text") or n.get("content-desc") or "").strip()
        if label.lower() not in text.lower():
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        tap(serial, (x2 + offset, (y1 + y2) // 2))
        return True
    return False


def goto(serial: str, xy: tuple[int, int], marker: str, tries: int = 4, timeout: float = 8.0) -> bool:
    """Tap [xy] until [marker] is on screen.

    The app is cloud-first, so a first tap can land while a list is still
    loading and open nothing. Retapping the same target is safe on every
    navigation control used here and makes the run independent of that timing.
    """
    for _ in range(tries):
        tap(serial, xy)
        if wait_for(serial, marker, timeout):
            return True
    return False


def setup(serial: str, device: str) -> None:
    size, density = GEOMETRY[device]
    adb(serial, "root", check=False)
    time.sleep(1)
    adb(serial, "wait-for-device", check=False)
    adb(serial, "shell", "wm", "size", size)
    adb(serial, "shell", "wm", "density", str(density))
    # Landscape is never used: keep the capture upright whatever the sensor says.
    adb(serial, "shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb(serial, "shell", "settings", "put", "system", "user_rotation", "0")
    # The listing is shot in the daylight theme; the ink theme is a separate need.
    # Both the runtime override and the persisted secure value are set: some
    # configuration changes reload the latter and would put the app back in ink.
    adb(serial, "shell", "cmd", "uimode", "night", "no")
    adb(serial, "shell", "settings", "put", "secure", "ui_night_mode", "1", check=False)
    # Final states only: no half-played animations in a screenshot.
    for scale in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb(serial, "shell", "settings", "put", "global", scale, "0")
    adb(serial, "shell", "svc", "power", "stayon", "true")
    # The capture drive never plays audio; keep the emulator silent anyway so
    # nothing can leak to the host speakers.
    adb(serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--set", "0", check=False)
    # A clean, deliberate status bar: full battery, a fixed morning time.
    adb(serial, "shell", "dumpsys", "battery", "set", "level", "100", check=False)
    adb(serial, "shell", "dumpsys", "battery", "set", "ac", "1", check=False)
    adb(serial, "shell", "dumpsys", "battery", "set", "status", "5", check=False)
    # Real time: a fixed fake date breaks TLS, and the status bar is cropped anyway.
    adb(serial, "shell", "settings", "put", "global", "auto_time", "1", check=False)
    time.sleep(2)


def set_locale(serial: str, locale: str) -> None:
    adb(serial, "shell", "cmd", "locale", "set-app-locales", PKG, "--locales", locale, check=False)


def launch(serial: str, seed: bool = False) -> None:
    adb(serial, "shell", "am", "force-stop", PKG)
    time.sleep(1)
    args = ["shell", "am", "start", "-n", ACTIVITY]
    if seed:
        args += ["--ez", "seed_profile_demo", "true"]
    adb(serial, *args)
    dismiss_consent(serial)


def dismiss_consent(serial: str, timeout: float = 6.0) -> None:
    """Declines the usage-consent question if it opens over the first screen.

    Matched exactly: the dialog's body text also contains the button's word.
    """
    words = {labels["decline"].lower() for labels in LABELS.values()}
    end = time.time() + timeout
    while time.time() < end:
        hits = [n for n in nodes(serial) if n[0].lower() in words]
        if hits:
            tap(serial, (hits[0][1], hits[0][2]))
            time.sleep(1.0)
            return
        time.sleep(0.6)


def write_pref(serial: str, name: str, values: dict[str, str]) -> None:
    """Sets string values in one of the app's preference files (debug build, `run-as`).

    The app has to be stopped: it keeps its preferences in memory and would write
    the old values back.
    """
    path = f"shared_prefs/{name}.xml"
    current = adb(serial, "shell", "run-as", PKG, "cat", path, check=False)
    entries = dict(re.findall(r'<string name="([^"]+)">([^<]*)</string>', current))
    entries.update(values)
    others = [line for line in current.splitlines()
              if line.strip().startswith("<") and "<string " not in line
              and not line.strip().startswith(("<?xml", "<map", "</map"))]
    body = "".join(f'    <string name="{k}">{v}</string>\n' for k, v in entries.items())
    xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
           + body + "".join(line + "\n" for line in others) + "</map>\n")
    subprocess.run(["adb", "-s", serial, "shell", "run-as", PKG, "sh", "-c", f"'cat > {path}'"],
                   input=xml.encode("utf-8"), check=True)


def raw_dir(locale: str, device: str) -> Path:
    return (RAW / locale) if device == "phone" else (RAW_TABLETS / device / locale)


def shoot(serial: str, locale: str, device: str, name: str) -> None:
    # Guard against the theme drifting back to ink between screens; writing the
    # value it already has is a no-op, so this never adds a visible flash.
    if adb(serial, "shell", "settings", "get", "secure", "ui_night_mode", check=False).strip() != "1":
        adb(serial, "shell", "cmd", "uimode", "night", "no", check=False)
        adb(serial, "shell", "settings", "put", "secure", "ui_night_mode", "1", check=False)
        time.sleep(1.0)
    path = raw_dir(locale, device) / f"{name}.png"
    screencap(serial, path)
    print(f"  captured {path.relative_to(REPO)}")


# The Russian lessons the listing shows: one whose first note is short and
# cultural (flowers are given in odd numbers, with the even bunch crossed out),
# and the "Привет / Как дела?" dialogue between two friends.
RU_CONTEXT_LESSON = "ru.lesson.invitations.002"
RU_DIALOGUE_LESSON = "ru.lesson.greetings.002"
# The first Spanish lesson: greetings at a café, with its illustration.
ES_DIALOGUE_LESSON = "es.lesson.greetings.001"


def drag(serial: str, x: int, y_from: int, y_to: int) -> None:
    """Scrolls by exactly (y_from - y_to): a drag that rests before lifting.

    `input swipe` lifts the finger while still moving, so the list flings past
    the target; holding still before UP leaves no velocity to fling with.
    """
    if abs(y_from - y_to) < 40:
        return
    steps = 12
    moves = "; ".join(f"input motionevent MOVE {x} {y_from + (y_to - y_from) * i // steps}"
                      for i in range(1, steps + 1))
    adb(serial, "shell", f"input motionevent DOWN {x} {y_from}; {moves}; sleep 0.5; "
                         f"input motionevent UP {x} {y_to}")


def anchor_under_step(serial: str, label: str, step_marker: str, density: int, w: int, h: int) -> None:
    """Scrolls a lesson dialogue so its translation toggle sits under the step label.

    The lesson title takes one or two lines depending on the interface
    language, so a fixed swipe would leave the list at a different place in
    each locale (half a card, or the toggle cut by the edge fade). Anchoring
    the toggle gives every locale the same framing: toggle, then the lines.
    """
    toggle = find_text(serial, label)
    if not toggle:
        # Below the fold (a lesson with a cover image): bring it up first.
        drag(serial, w // 2, int(h * 0.75), int(h * 0.35))
        time.sleep(1.0)
        toggle = find_text(serial, label)
    step = find_text(serial, step_marker)
    if not (toggle and step):
        return
    target = step[1] + int(130 * density / 480)
    drag(serial, w // 2, int(h * 0.75), int(h * 0.75) - (toggle[1] - target))
    time.sleep(1.0)


def open_course_lesson(serial: str, language: str, lesson_id: str, labels: dict) -> None:
    """Switches the course to [language] and opens [lesson_id] from its start, from Today."""
    adb(serial, "shell", "am", "force-stop", PKG)
    write_pref(serial, "glosso_language", {"target_language": language})
    write_pref(serial, "glosso_progress", {"lesson_id": lesson_id, "lesson_step": "BASE",
                                           "resume_level": "A1"})
    launch(serial)
    # The Today card shows its button only once the Russian course has loaded.
    wait_for(serial, labels["continue"], 25)
    time.sleep(1.0)
    tap_text(serial, labels["continue"])


# Text used to drive navigation when the layout is not the phone's. Tapping the
# label itself is layout-independent, which the bottom bar and the Library cards
# both allow.
LABELS = {
    "en": {"skip": "Skip", "practice": "Free practice", "back": "Back",
           "today": "Today", "lessons": "Lessons", "you": "You", "library": "Library",
           "start": "Start", "continue": "Continue", "translation": "Show translation", "hide": "Hide translation",
           "decline": "Decline", "switcher": "Target language"},
    "es": {"skip": "Saltar", "practice": "Práctica libre", "back": "Atrás",
           "today": "Hoy", "lessons": "Lecciones", "you": "Tú", "library": "Biblioteca",
           "start": "Empezar", "continue": "Continuar", "translation": "Ver traducción", "hide": "Ocultar traducción",
           "decline": "Rechazar", "switcher": "Idioma objetivo"},
    "fr": {"skip": "Passer", "practice": "Entraînement libre", "back": "Retour",
           "today": "Aujourd'hui", "lessons": "Leçons", "you": "Toi", "library": "Bibliothèque",
           "start": "Commencer", "continue": "Continuer", "translation": "Voir la traduction", "hide": "Masquer la traduction",
           "decline": "Refuser", "switcher": "Langue étudiée"},
    "de": {"skip": "Überspringen", "practice": "Freies Üben", "back": "Zurück",
           "today": "Heute", "lessons": "Lektionen", "you": "Du", "library": "Bibliothek",
           "start": "Starten", "continue": "Weiter", "translation": "Übersetzung anzeigen", "hide": "Übersetzung ausblenden",
           "decline": "Ablehnen", "switcher": "Zielsprache"},
}


def capture_locale(serial: str, locale: str, device: str, density: int, taps_: dict) -> None:
    marker = MARKERS[locale]
    labels = LABELS[locale]
    phone = device == "phone"
    # Half a 48dp touch target, in px: the practice header's invisible inject
    # target sits right after the title, whatever the density.
    inject_offset = int(24 * density / 160)
    print(f"[{locale}] start ({device})")
    # Fresh install state: the welcome is the first screen and is captured as is.
    # The per-app locale is set *after* the clear: `pm clear` wipes the override,
    # so setting it first would leave the run in the system language.
    adb(serial, "shell", "pm", "clear", PKG)
    time.sleep(1)
    set_locale(serial, locale)
    time.sleep(1)
    launch(serial)
    wait_for(serial, marker["onboarding"], 25)
    shoot(serial, locale, device, "onboarding")

    # Past the welcome, with the documented multi-day profile seeded, so the
    # progress and "weakest sounds" views carry real numbers. On a tablet the
    # top bar sits further in, so the welcome's Skip is found by its label.
    if phone:
        tap(serial, taps_["onboarding_skip"])
    else:
        tap_text(serial, labels["skip"])
    time.sleep(1)
    # The listing shows the American course: its wording and voices are the
    # newest part of the product, and en-US is the listing's base language.
    adb(serial, "shell", "am", "force-stop", PKG)
    write_pref(serial, "glosso_voice", {"accent": "en-US"})
    launch(serial, seed=True)
    if not wait_for(serial, marker["today"], 25):
        # The seed launch is the gate for the whole run: without it the app
        # stays on onboarding and every later tap lands on the wrong screen.
        # Fail here rather than write a set of wrong screenshots.
        raise RuntimeError(f"{locale}: 'today' never settled after seeding; capture aborted")

    # Free practice first: it is the product's core claim, and the scored take
    # also lights the streak that the later Today and Lessons shots show.
    if phone:
        goto(serial, taps_["nav_library"], marker["library"])
        goto(serial, taps_["library_practice"], marker["practice"])
    else:
        tap_bottom(serial, labels["library"]); time.sleep(1.0)
        tap_text(serial, labels["practice"]); wait_for(serial, marker["practice"], 12)
    shoot(serial, locale, device, "practice")

    # Scored take. The store build exposes one invisible tap target in the
    # practice header for exactly this; nothing debug is ever on screen.
    if phone:
        goto(serial, taps_["practice_inject"], marker["feedback"], tries=2, timeout=9.0)
    else:
        tap_right_of(serial, marker["practice"], inject_offset)
        wait_for(serial, marker["feedback"], 12)
    time.sleep(5)
    shoot(serial, locale, device, "feedback")

    # Back out of the practice route: every Library sub-screen owns the whole
    # window, so the bottom bar is not there to tap until we leave it.
    if phone:
        tap(serial, taps_["back"])
    else:
        tap_text(serial, labels["back"])
    time.sleep(1.5)

    # Today now carries the streak the scored take just earned.
    if phone:
        goto(serial, taps_["nav_today"], marker["today"])
    else:
        tap_bottom(serial, labels["today"]); wait_for(serial, marker["today"], 12)
    shoot(serial, locale, device, "today")

    # The course switcher in the header, open over Today.
    tap_text(serial, labels["switcher"])
    time.sleep(1.5)
    shoot(serial, locale, device, "switcher")
    adb(serial, "shell", "input", "keyevent", "4")
    time.sleep(1.0)

    if phone:
        # The lesson of the day: its dialogue, then the step where the learner
        # answers from memory. The step is reached by setting the saved lesson
        # step, so the run does not depend on solving the exercises in between.
        tap_text(serial, labels["start"])
        wait_for(serial, marker["dialogue"], 15)
        time.sleep(1.0)
        w, h = (int(v) for v in GEOMETRY[device][0].split("x"))
        anchor_under_step(serial, labels["translation"], marker["dialogue"], density, w, h)
        shoot(serial, locale, device, "dialogue")
        adb(serial, "shell", "am", "force-stop", PKG)
        write_pref(serial, "glosso_progress", {"lesson_step": "SPEAK"})
        launch(serial)
        wait_for(serial, marker["today"], 25)
        # The Today card already names the step, so the label cannot tell the
        # two screens apart: the lesson is given time to open instead.
        tap_text(serial, labels["continue"])
        time.sleep(4.0)
        shoot(serial, locale, device, "speak")
        tap(serial, taps_["back"])
        time.sleep(1.5)

    # Progress: the pronunciation profile with its per-sound trend.
    if phone:
        goto(serial, taps_["nav_you"], marker["you"])
    else:
        tap_bottom(serial, labels["you"]); wait_for(serial, marker["you"], 12)
    shoot(serial, locale, device, "you")

    # Lessons.
    if phone:
        goto(serial, taps_["nav_lessons"], marker["lessons"])
    else:
        tap_bottom(serial, labels["lessons"]); wait_for(serial, marker["lessons"], 12)
    shoot(serial, locale, device, "lessons")

    if phone:
        # The Library hub, then the story bank and a reader. The run ends in the
        # reader, which has no bottom bar, so nothing is navigated after this.
        goto(serial, taps_["nav_library"], marker["library"])
        shoot(serial, locale, device, "library")
        goto(serial, taps_["library_stories"], marker["stories"])
        shoot(serial, locale, device, "stories")
        goto(serial, taps_["story_first"], marker["story"], tries=2, timeout=8.0)
        time.sleep(1.0)
        shoot(serial, locale, device, "story")

    # The Russian course: a lesson opened from its start sets the scene with
    # its culture notes (the "Context" step) before its dialogue, shown with
    # the meaning in the interface language because the learner cannot read
    # Russian yet. Each lesson is picked by saving it as the one in progress.
    w, h = (int(v) for v in GEOMETRY[device][0].split("x"))
    open_course_lesson(serial, "ru", RU_CONTEXT_LESSON, labels)
    wait_for(serial, marker["context"], 15)
    time.sleep(1.0)
    shoot(serial, locale, device, "ru_context")

    for language, lesson_id, name in (("ru", RU_DIALOGUE_LESSON, "ru_dialogue"),
                                      ("es", ES_DIALOGUE_LESSON, "es_dialogue")):
        open_course_lesson(serial, language, lesson_id, labels)
        # Lessons with culture notes open on them; the dialogue is the next step.
        # The context step can mention the dialogue's label too, so its own
        # marker decides whether the dialogue is really on screen yet.
        wait_for(serial, marker["dialogue"], 15)
        for _ in range(3):
            if find_text(serial, marker["dialogue"]) and not find_text(serial, marker["context"]):
                break
            tap_text(serial, labels["continue"])
            time.sleep(2.0)
        time.sleep(1.0)
        tap_text(serial, labels["translation"])
        time.sleep(1.5)
        anchor_under_step(serial, labels["hide"], marker["dialogue"], density, w, h)
        shoot(serial, locale, device, name)
    print(f"[{locale}] done")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5568"))
    ap.add_argument("--locales", default="en,es,fr,de")
    ap.add_argument("--device", default="phone", choices=sorted(GEOMETRY))
    args = ap.parse_args()

    if args.serial not in {"emulator-5558", "emulator-5560", "emulator-5562",
                           "emulator-5568", "emulator-5570"}:
        sys.exit(f"refusing to touch {args.serial}: not a permitted emulator")

    size, density = GEOMETRY[args.device]
    w, h = (int(v) for v in size.split("x"))
    taps_ = taps(w, h)
    setup(args.serial, args.device)
    for locale in args.locales.split(","):
        locale = locale.strip()
        if locale:
            capture_locale(args.serial, locale, args.device, density, taps_)


if __name__ == "__main__":
    main()
