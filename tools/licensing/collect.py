#!/usr/bin/env python3
"""Collect the licence catalogue that ships inside the app.

The Android About screen cannot link to this repository (it is private), so the
full attribution list has to travel inside the APK.

Coverage is the **resolved debug runtime classpath**, not the hand-declared
dependencies: Play Services and the rest of the transitive closure end
up in the APK and need attribution too. The classpath lock is produced by the
Gradle task `:androidApp:licensesRuntimeClasspath` (kept fresh by
`:androidApp:licensesRuntimeClasspathCheck`). For each runtime module this script
reads the licence declared in its POM in the Gradle cache; a small curated
catalogue overrides the rows that are special (the fonts, the model, the
generated art, ...) and group rules cover artifacts shipped without a POM
(Compose publishes Gradle Module Metadata). POMs are matched by local XML name,
so namespace-less POMs are attributed too.

It writes `shared/src/commonMain/composeResources/files/licenses.json`, which
`LicensesScreen` renders on Android and iOS, and copies the privacy policy (one
file per app locale) next to it.

`--check` is a fail-closed gate (wired into `preBuild` by
`:androidApp:licensesCatalogueCheck`): it fails if any runtime artifact is
unattributed, if the assets drift, or if a shipped entry carries a prohibited
licence (GPL/AGPL, non-commercial, share-alike, or anything not in
`ALLOWED_LICENCES`). Server/build/test-only artifacts are still catalogued and
must be attributed, but their licence only warns, because they never enter the
artifact (see `tools/licensing/runtime-classpath-<flavor>.txt`, one lock per
app flavor; the catalogue covers their union).

Usage:
  ./gradlew :androidApp:licensesRuntimeClasspath   # refresh the classpath lock
  python tools/licensing/collect.py                # rewrite the app assets
  python tools/licensing/collect.py --check        # validate only, non-zero on drift or a prohibited licence
"""

import argparse
import functools
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:  # Python 3.11+
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - very old interpreters
    tomllib = None

ROOT = Path(__file__).resolve().parents[2]
# Shared Compose resources: the same files ship in the Android and the iOS app.
RESOURCE_FILES = ROOT / "shared/src/commonMain/composeResources/files"
ASSET = RESOURCE_FILES / "licenses.json"
SCHEMA = 2

# One lock per app flavor (fdroid, playstore). The catalogue lives in :shared,
# which has no flavors, so it attributes everything either build can ship.
RUNTIME_LOCKS = [ROOT / f"tools/licensing/runtime-classpath-{flavor}.txt" for flavor in ("fdroid", "playstore")]
GRADLE_CACHE = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"

# Privacy policy, one source per app locale. Spanish reuses the working doc; the
# other three live next to this script. `PrivacyScreen` picks the active locale.
PRIVACY_SOURCES = {
    "en": ROOT / "tools/licensing/privacy/privacy.en.md",
    "es": ROOT / "docs/PRIVACY.md",
    "fr": ROOT / "tools/licensing/privacy/privacy.fr.md",
    "de": ROOT / "tools/licensing/privacy/privacy.de.md",
}
PRIVACY_ASSET_DIR = RESOURCE_FILES / "privacy"


def privacy_asset(locale):
    return PRIVACY_ASSET_DIR / f"privacy-{locale}.md"


# Standard licence texts kept in the repository. A component names one with
# `text`; otherwise the licence default is used. Google's proprietary terms have
# no redistributable text, only a notice plus the canonical URL.
TEXT_FILES = {
    "Apache-2.0": "licenses/Apache-2.0.txt",
    "MIT": "licenses/MIT.txt",
    "BSD-2-Clause": "licenses/BSD-2-Clause.txt",
    "EPL-1.0": "licenses/EPL-1.0.txt",
    "OFL-BricolageGrotesque": "licenses/fonts/OFL-BricolageGrotesque.txt",
    "OFL-PlusJakartaSans": "licenses/fonts/OFL-PlusJakartaSans.txt",
    "OFL-JetBrainsMono": "licenses/fonts/OFL-JetBrainsMono.txt",
}
# Licence id -> text key, when there is a redistributable text.
LICENCE_TEXT = {
    "Apache-2.0": "Apache-2.0",
    "MIT": "MIT",
    "BSD-2-Clause": "BSD-2-Clause",
    "EPL-1.0": "EPL-1.0",
    "OFL-1.1": "OFL-PlusJakartaSans",
}

LICENSE_NAMES = {
    "Apache-2.0": "Apache License 2.0",
    "MIT": "MIT License",
    "BSD-2-Clause": "BSD 2-Clause \"Simplified\" License",
    "OFL-1.1": "SIL Open Font License 1.1",
    "EPL-1.0": "Eclipse Public License 1.0",
    "Android-SDK": "Android Software Development Kit License",
    "Play-Core-ToS": "Play Core Software Development Kit Terms of Service",
    "CC-BY-2.0-FR": "Creative Commons Attribution 2.0 France",
    "CEFR-J": "CEFR-J Wordlist licence (free with attribution)",
    "proprietary": "Proprietary",
}

LICENSE_URLS = {
    "Android-SDK": "https://developer.android.com/studio/terms.html",
    "Play-Core-ToS": "https://developer.android.com/guide/playcore/in-app-review",
    "CC-BY-2.0-FR": "https://creativecommons.org/licenses/by/2.0/fr/",
    "CEFR-J": "https://www.cefr-j.org/",
}

# Licences cleared to reach users. The gate is fail-closed: anything not listed
# here aborts the build, so shipping a new copyleft or non-commercial artefact
# takes a deliberate, reviewed edit to this set. `proprietary` is deliberately
# absent: Google's terms are allowed only through their explicit ids.
ALLOWED_LICENCES = {
    "Apache-2.0",
    "MIT",
    "BSD-2-Clause",
    "EPL-1.0",
    "OFL-1.1",
    "Android-SDK",
    "Play-Core-ToS",
    "CC-BY-2.0-FR",
    "CEFR-J",
}

# Substrings that mark a licence as forbidden even if some future allow-list edit
# would name it. `gpl` also covers `lgpl`/`agpl` text; the rest catch the common
# non-commercial and share-alike spellings a POM might use.
PROHIBITED_LICENCE_MARKERS = (
    "gpl",
    "copyleft",
    "non-commercial",
    "noncommercial",
    "non commercial",
    "cc-by-nc",
    "cc by-nc",
    "cc-by-sa",
    "cc by-sa",
    "research only",
    "all rights reserved",
)

# Non-Gradle components that ship in or build the product. Hand-written from
# docs/LICENSING.md and the model cards; keep `usage` precise about what
# actually ships. Fonts carry their own OFL text (the copyright line differs).
COMPONENTS = [
    # --- Content model and language data ------------------------------------
    dict(
        id="model:wav2vec2-lv-60-espeak-cv-ft",
        name="wav2vec2-lv-60-espeak-cv-ft (fp16 ONNX)",
        author="Meta Platforms (Facebook AI Research) and contributors; ONNX export by onnx-community",
        license="Apache-2.0",
        url="https://huggingface.co/facebook/wav2vec2-lv-60-espeak-cv-ft",
        usage="Acoustic phoneme model that scores pronunciation, on device and on the server.",
        distribution="app",
    ),
    dict(
        id="model:phoneme-vocab",
        name="Phoneme vocabulary (phoneme_vocab.json)",
        author="onnx-community (export of the wav2vec2 model above)",
        license="Apache-2.0",
        url="https://huggingface.co/onnx-community/wav2vec2-lv-60-espeak-cv-ft-ONNX",
        usage="Tokenizer vocabulary for the acoustic model.",
        distribution="app",
    ),
    dict(
        id="data:cmudict",
        name="CMU Pronouncing Dictionary (CMUdict)",
        author="Carnegie Mellon University",
        license="BSD-2-Clause",
        url="https://github.com/cmusphinx/cmudict",
        usage="English grapheme-to-phoneme reference dictionary.",
        distribution="app",
    ),
    dict(
        id="data:cefrj-wordlist-1.6",
        name="CEFR-J Wordlist v1.6",
        author="Yukio Tono, Tokyo University of Foreign Studies (Tono Lab)",
        license="CEFR-J",
        url="https://www.cefr-j.org/",
        notice="Wordlist used for CEFR levels and parts of speech. Redistributed with the required attribution; see the source file headers.",
        usage="Course levels and vocabulary bands.",
        distribution="content",
    ),
    dict(
        id="data:tatoeba-frequency",
        name="Tatoeba English frequency snapshot",
        author="Tatoeba contributors",
        license="CC-BY-2.0-FR",
        url="https://tatoeba.org/",
        notice="Derived frequency snapshot; Tatoeba and the CC BY 2.0 FR licence are credited in the file header.",
        usage="Word frequency ordering in the vocabulary.",
        distribution="content",
    ),
    # --- Native libraries not resolved by Gradle ------------------------------
    dict(
        id="native:onnxruntime-ios",
        name="ONNX Runtime for iOS (onnxruntime-c 1.30.0)",
        author="Microsoft Corporation",
        license="MIT",
        url="https://github.com/microsoft/onnxruntime",
        notice="Official iOS build, linked statically into the iOS app; fetched by tools/fetch-onnxruntime-ios.sh (version and SHA-256 pinned).",
        usage="Acoustic model inference on iOS devices.",
        distribution="app",
    ),
    dict(
        id="native:stb-vorbis",
        name="stb_vorbis 1.22",
        author="Sean Barrett and contributors",
        license="MIT",
        url="https://github.com/nothings/stb",
        notice="Copyright (c) 2017 Sean Barrett. Dual licensed MIT or public domain (Unlicense); used under the MIT licence. Vendored unmodified in shared/src/nativeInterop/stb_vorbis (commit and SHA-256 in its README) and linked statically into the iOS app.",
        usage="Decodes the Ogg Vorbis lesson and story audio on iOS, which AVFoundation cannot play.",
        distribution="app",
    ),
    # --- Fonts (each with its own OFL copy) ----------------------------------
    dict(
        id="font:BricolageGrotesque",
        name="Bricolage Grotesque",
        author="2022 The Bricolage Grotesque Project Authors",
        license="OFL-1.1",
        text="OFL-BricolageGrotesque",
        url="https://github.com/ateliertriay/bricolage",
        usage="Display, titles and large numbers.",
        distribution="app",
    ),
    dict(
        id="font:PlusJakartaSans",
        name="Plus Jakarta Sans",
        author="2020 The Plus Jakarta Sans Project Authors",
        license="OFL-1.1",
        text="OFL-PlusJakartaSans",
        url="https://github.com/tokotype/PlusJakartaSans",
        usage="User-interface sans serif.",
        distribution="app",
    ),
    dict(
        id="font:JetBrainsMono",
        name="JetBrains Mono",
        author="2020 The JetBrains Mono Project Authors",
        license="OFL-1.1",
        text="OFL-JetBrainsMono",
        url="https://github.com/JetBrains/JetBrainsMono",
        usage="IPA transcription and tabular numbers.",
        distribution="app",
    ),
    # --- Catalogue media -----------------------------------------------------
    dict(
        id="asset:catalogue-images",
        name="Catalogue artwork (FLUX.2 [klein] base 4B)",
        author="Generated with Black Forest Labs FLUX.2 [klein] base 4B",
        license="Apache-2.0",
        url="https://huggingface.co/black-forest-labs/FLUX.2-klein-base-4B",
        notice="Generated brand artwork, no third-party source imagery. Model weights are Apache-2.0; the non-commercial 9B variant is never used (see tools/licensing and docs/LICENSING.md).",
        usage="Story, article, dialogue and exercise illustrations.",
        distribution="app",
    ),
    dict(
        id="asset:catalogue-audio",
        name="Catalogue audio (Chatterbox, cloning Kokoro references)",
        author="Rendered by Resemble AI Chatterbox from synthetic Kokoro reference voices",
        license="MIT",
        url="https://github.com/resemble-ai/chatterbox",
        notice="Rendered offline; no TTS engine or model ships in the app. Kokoro (Apache-2.0) generated the synthetic reference voices, so no real person's voice is cloned. Each clip carries an implicit Resemble Perth watermark.",
        usage="Pronunciation clips and dialogues served to the app.",
        distribution="app",
    ),
    # --- Build-time tooling (not distributed) --------------------------------
    dict(
        id="tool:kokoro",
        name="Kokoro (kokoro)",
        author="hexgrad and contributors",
        license="Apache-2.0",
        url="https://github.com/hexgrad/kokoro",
        usage="Build-time: renders the synthetic reference voices in tools/content/voices/.",
        distribution="build",
    ),
    dict(
        id="tool:chatterbox",
        name="Chatterbox (chatterbox-tts)",
        author="Resemble AI",
        license="MIT",
        url="https://github.com/resemble-ai/chatterbox",
        usage="Build-time: renders the catalogue audio by cloning each Kokoro reference.",
        distribution="build",
    ),
    dict(
        id="tool:perth",
        name="Perth (resemble-perth)",
        author="Resemble AI",
        license="MIT",
        url="https://github.com/resemble-ai/Perth",
        usage="Build-time: implicit watermark embedded by Chatterbox in every rendered clip.",
        distribution="build",
    ),
    dict(
        id="tool:flux2-klein-base-4b",
        name="FLUX.2 [klein] base 4B (diffusers)",
        author="Black Forest Labs",
        license="Apache-2.0",
        url="https://huggingface.co/black-forest-labs/FLUX.2-klein-base-4B",
        usage="Build-time: renders the catalogue artwork on our own GPU. This is the only default model; the non-commercial 9B variant requires an explicit --model.",
        distribution="build",
    ),
]


def _dep(id, name, author, license, url, usage, distribution):
    return dict(id=id, name=name, author=author, license=license, url=url, usage=usage, distribution=distribution)


# Curated overrides: rows no POM can describe correctly, or that deserve a human
# name/usage. Everything else is resolved from the runtime classpath.
CURATED_DEPS = {c["id"]: c for c in [
    _dep("org.jetbrains.kotlin:kotlin-stdlib", "Kotlin standard library", "JetBrains s.r.o.",
         "Apache-2.0", "https://github.com/JetBrains/kotlin",
         "Kotlin runtime, on the app and the server.", "app"),
    _dep("org.jetbrains.kotlin:kotlin-test", "kotlin-test", "JetBrains s.r.o.",
         "Apache-2.0", "https://github.com/JetBrains/kotlin",
         "Kotlin assertion library for tests.", "test"),
    _dep("org.jetbrains.kotlinx:kotlinx-serialization-json", "kotlinx.serialization (JSON)",
         "JetBrains s.r.o.", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization",
         "JSON serialization on the app and the server.", "app"),
    _dep("org.jetbrains.kotlinx:kotlinx-coroutines-core", "kotlinx.coroutines (core)",
         "JetBrains s.r.o.", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines",
         "Structured concurrency on the server and the engine.", "app"),
    _dep("org.jetbrains.kotlinx:kotlinx-coroutines-android", "kotlinx.coroutines (Android)",
         "JetBrains s.r.o.", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines",
         "Coroutine dispatcher for Android.", "app"),
    _dep("org.jetbrains.kotlinx:kotlinx-coroutines-test", "kotlinx.coroutines (test)",
         "JetBrains s.r.o.", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines",
         "Coroutine testing utilities.", "test"),
    _dep("com.microsoft.onnxruntime:onnxruntime", "ONNX Runtime", "Microsoft Corporation",
         "MIT", "https://github.com/microsoft/onnxruntime",
         "Acoustic model inference on the server JVM.", "server"),
    _dep("com.microsoft.onnxruntime:onnxruntime-android", "ONNX Runtime for Android",
         "Microsoft Corporation", "MIT", "https://github.com/microsoft/onnxruntime",
         "Acoustic model inference on device.", "app"),
    _dep("io.github.oshai:kotlin-logging-jvm", "kotlin-logging", "Ohad Shai",
         "Apache-2.0", "https://github.com/oshai/kotlin-logging", "Server logging facade.", "server"),
    _dep("org.slf4j:slf4j-api", "SLF4J API", "QOS.ch",
         "MIT", "https://www.slf4j.org/", "Logging facade used by the runtime.", "app"),
    _dep("org.slf4j:slf4j-simple", "SLF4J Simple", "QOS.ch",
         "MIT", "https://www.slf4j.org/", "Server logging binding.", "server"),
    _dep("org.postgresql:postgresql", "PostgreSQL JDBC Driver", "PostgreSQL Global Development Group",
         "BSD-2-Clause", "https://jdbc.postgresql.org/", "Analytics database driver.", "server"),
    _dep("com.zaxxer:HikariCP", "HikariCP", "Brett Wooldridge",
         "Apache-2.0", "https://github.com/brettwooldridge/HikariCP", "Server connection pool.", "server"),
    _dep("org.xerial:sqlite-jdbc", "sqlite-jdbc", "Xerial",
         "Apache-2.0", "https://github.com/xerial/sqlite-jdbc", "Content database driver.", "server"),
    # Curated so the gate does not depend on the local Gradle cache holding their
    # POMs (an IDE that fetched only jars and sources leaves them unresolved).
    _dep("com.nimbusds:nimbus-jose-jwt", "Nimbus JOSE + JWT", "Connect2id Ltd.",
         "Apache-2.0", "https://bitbucket.org/connect2id/nimbus-jose-jwt",
         "Verifies Google sign-in tokens on the server.", "server"),
    _dep("io.zonky.test:embedded-postgres", "Embedded PostgreSQL", "Tomas Vanek",
         "Apache-2.0", "https://github.com/zonkyio/embedded-postgres",
         "PostgreSQL for the server tests.", "test"),
    _dep("io.zonky.test.postgres:embedded-postgres-binaries-darwin-arm64v8", "Embedded PostgreSQL binaries",
         "Tomas Vanek", "Apache-2.0", "https://github.com/zonkyio/embedded-postgres-binaries",
         "PostgreSQL binaries for the server tests on Apple Silicon.", "test"),
    _dep("io.micrometer:micrometer-registry-prometheus", "Micrometer Prometheus registry",
         "VMware, Inc. / Broadcom", "Apache-2.0",
         "https://github.com/micrometer-metrics/micrometer", "Server metrics.", "server"),
    _dep("io.coil-kt:coil-compose", "Coil", "Coil Contributors", "Apache-2.0",
         "https://github.com/coil-kt/coil", "Image loading in Compose.", "app"),
    _dep("junit:junit", "JUnit 4", "JUnit", "EPL-1.0", "https://junit.org/junit4/",
         "Unit tests (EPL-1.0 applies to the test framework only).", "test"),
    _dep("org.robolectric:robolectric", "Robolectric", "Robolectric", "MIT",
         "https://github.com/robolectric/robolectric", "Android unit tests on the JVM.", "test"),
    _dep("androidx.compose:compose-bom", "Compose Bill of Materials",
         "The Android Open Source Project", "Apache-2.0",
         "https://developer.android.com/jetpack/androidx", "Pins the Compose library versions.", "build"),
    _dep("io.ktor:ktor-client-okhttp", "Ktor client (OkHttp engine)", "JetBrains s.r.o.",
         "Apache-2.0", "https://github.com/ktorio/ktor", "HTTP client engine (declared, unused).", "app"),
]}

# Entries whose POM carries no licence field; the artefact is known.
ID_OVERRIDES = {
    "com.google.guava:listenablefuture": ("Apache-2.0", "Google LLC"),
    "javax.inject:javax.inject": ("Apache-2.0", "JSR-330 Expert Group"),
    "org.slf4j:slf4j-api": ("MIT", "QOS.ch"),
}

# Fallback for artefacts published without a POM (Compose uses Gradle Module
# Metadata): licence by group. Order matters, most specific first.
GROUP_RULES = [
    ("androidx.compose.", ("Apache-2.0", "The Android Open Source Project")),
    ("androidx.", ("Apache-2.0", "The Android Open Source Project")),
    ("com.google.android.gms", ("Android-SDK", "Google LLC")),
    ("com.google.android.", ("Apache-2.0", "Google LLC")),
    ("com.google.firebase", ("Apache-2.0", "Google LLC")),
    ("com.google.", ("Apache-2.0", "Google LLC")),
    ("io.ktor", ("Apache-2.0", "JetBrains s.r.o.")),
    ("org.jetbrains.kotlinx", ("Apache-2.0", "JetBrains s.r.o.")),
    ("org.jetbrains", ("Apache-2.0", "JetBrains s.r.o.")),
    ("org.jspecify", ("Apache-2.0", "JSpecify")),
    ("com.squareup", ("Apache-2.0", "Square, Inc.")),
    ("software.amazon.awssdk", ("Apache-2.0", "Amazon.com, Inc.")),
    ("io.coil-kt", ("Apache-2.0", "Coil Contributors")),
    ("redis.clients", ("MIT", "Redis Inc.")),
    ("com.github.codemonstur", ("Apache-2.0", "Jurgen Voorneveld")),
]

GROUP_AUTHORS = [(prefix, author) for prefix, (_licence, author) in GROUP_RULES]


def _group_author(group):
    for prefix, author in GROUP_AUTHORS:
        if group.startswith(prefix):
            return author
    return group

# POM licence names vary; normalise by substring.
POM_LICENCE_MATCHES = [
    (lambda k: "apache" in k and ("2.0" in k or "2 " in k), "Apache-2.0"),
    (lambda k: "android software development kit" in k, "Android-SDK"),
    (lambda k: "play core" in k, "Play-Core-ToS"),
    (lambda k: "eclipse public license" in k, "EPL-1.0"),
    (lambda k: "sil open font" in k, "OFL-1.1"),
    (lambda k: "bsd" in k and "2" in k, "BSD-2-Clause"),
    (lambda k: k == "mit" or k.startswith("mit ") or k.endswith(" mit") or " mit " in k, "MIT"),
]


def _normalise_licence_name(name):
    key = (name or "").strip().lower()
    if not key:
        return None
    for predicate, licence in POM_LICENCE_MATCHES:
        if predicate(key):
            return licence
    return None


def _group_rule(group):
    for prefix, rule in GROUP_RULES:
        if group.startswith(prefix):
            return rule
    return None


def _pom_paths(group, artifact, version=None):
    base = GRADLE_CACHE / group / artifact
    if not base.is_dir():
        return []
    candidates = [base / version] if version else sorted(p for p in base.iterdir() if p.is_dir())
    poms = []
    for version_dir in candidates:
        poms += sorted(version_dir.glob("*/*.pom"))
    return poms


def _local_name(tag):
    """Element tag without its `{namespace}` prefix, if any."""
    return tag.rsplit("}", 1)[-1]


def _first_local(element, name):
    return next((child for child in element.iter() if _local_name(child.tag) == name), None)


def _pom_licence(group, artifact, version=None):
    for pom in _pom_paths(group, artifact, version):
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError:
            continue
        # Maven publishes POMs both with and without the 4.0.0 namespace. Match by
        # local name so a namespace-less POM (embedded-redis) is not skipped and
        # left unattributed.
        licenses = _first_local(root, "licenses")
        if licenses is None:
            continue
        licence = _first_local(licenses, "license")
        if licence is None:
            continue
        name_el = _first_local(licence, "name")
        url_el = _first_local(licence, "url")
        name = (name_el.text or "").strip() if name_el is not None else ""
        url = (url_el.text or "").strip() if url_el is not None else ""
        if name or url:
            return name, url
    return None


def _auto_component(module_id, version=None, scope="app"):
    """A licence entry for a runtime/declared module, or None if unknown."""
    if module_id in CURATED_DEPS:
        return dict(CURATED_DEPS[module_id])
    group, _, artifact = module_id.partition(":")
    override = ID_OVERRIDES.get(module_id)
    pom = _pom_licence(group, artifact, version)
    if override:
        licence, author = override
        name = LICENSE_NAMES.get(licence, licence)
        url = LICENSE_URLS.get(licence) or (pom[1] if pom else None)
        notice = None
    elif pom:
        raw_name, url = pom
        licence = _normalise_licence_name(raw_name)
        author = _group_author(group)
        if licence:
            name = LICENSE_NAMES[licence]
            notice = None
        else:
            licence, name = raw_name or "unknown", raw_name or "Unknown licence"
            notice = f"Licence declared in the artifact POM: {raw_name or 'none'}." + (f" See {url}." if url else "")
    else:
        rule = _group_rule(group)
        if rule is None:
            return None
        licence, author = rule
        name = LICENSE_NAMES[licence]
        url = LICENSE_URLS.get(licence)
        notice = None
    return dict(id=module_id, name=artifact, author=author, license=licence, licenseName=name,
                url=url, usage=_SCOPE_USAGE.get(scope, _SCOPE_USAGE["build"]), distribution=scope, notice=notice)


def runtime_coordinates():
    coordinates = set()
    for lock in RUNTIME_LOCKS:
        if not lock.is_file():
            raise SystemExit(
                f"missing {lock.relative_to(ROOT)}. Run:\n"
                "  ./gradlew :androidApp:licensesRuntimeClasspath"
            )
        coordinates.update(line.strip() for line in lock.read_text(encoding="utf-8").splitlines() if line.strip())
    return sorted(coordinates)


def runtime_module_ids():
    return {":".join(coordinate.split(":")[:2]) for coordinate in runtime_coordinates()}


def _parse_version_catalog(root):
    path = root / "gradle/libs.versions.toml"
    if tomllib is None:  # pragma: no cover
        raise SystemExit("Python 3.11+ with tomllib is required")
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    alias_to_module = {}
    for alias, spec in data.get("libraries", {}).items():
        if isinstance(spec, str):
            parts = spec.split(":")
            if len(parts) >= 2:
                alias_to_module[alias] = ":".join(parts[:2])
            continue
        module = spec.get("module")
        if not module and spec.get("group") and spec.get("name"):
            module = f"{spec['group']}:{spec['name']}"
        if module:
            alias_to_module[alias] = module
    return alias_to_module


_LIB_REF = re.compile(r"libs\.([a-zA-Z0-9_.]+)")
_KOTLIN_REF = re.compile(r'kotlin\("([a-zA-Z0-9_.-]+)"\)')
# Raw coordinates written directly in a build file (not through the version
# catalog) used to be ignored silently, so a dependency added that way would
# never be attributed. Match the common dependency verbs only, so `project(...)`
# and plugin ids are not mistaken for a module.
_RAW_DEP_REF = re.compile(
    r"\b(?:implementation|api|compileOnly|runtimeOnly|testImplementation|"
    r"androidTestImplementation|debugImplementation|classpath)\s*\(\s*[\"']"
    r"([a-zA-Z0-9_.\-]+):([a-zA-Z0-9_.\-]+)(?::[a-zA-Z0-9_.\-]+)?[\"']"
)
_DEP_VERB_RE = re.compile(
    r"\b(implementation|api|compileOnly|runtimeOnly|testImplementation|"
    r"androidTestImplementation|debugImplementation|classpath)\s*\("
)
_KOTLIN_MODULE = {"test": "org.jetbrains.kotlin:kotlin-test"}
_UNUSED_DECLARED = {"io.ktor:ktor-client-okhttp"}

# Where a module is used, most shipped first. A module resolved into the APK
# runtime is always "app"; the rest is classified from the build file and the
# dependency verb, so the About screen does not present server/test-only rows as
# Android runtime dependencies.
_SCOPE_RANK = {"build": 0, "test": 1, "server": 2, "app": 3}
_SCOPE_USAGE = {
    "app": "Transitive Android runtime dependency.",
    "server": "Server-side dependency; not shipped in the app.",
    "test": "Test-only dependency; not shipped in the app.",
    "build": "Build-time or catalog-only dependency; not shipped in the app.",
}
_BUILD_FILE_SCOPE = {"androidApp": "app", "engine": "app", "server": "server"}


def _modules_in_line(line, alias_to_module):
    found = set()
    for ref in _LIB_REF.findall(line):
        if ref.split(".")[0] in ("plugins", "versions"):
            continue
        module = alias_to_module.get(ref.replace(".", "-"))
        if module:
            found.add(module)
    for name in _KOTLIN_REF.findall(line):
        if not name.startswith("plugin"):
            found.add(_KOTLIN_MODULE.get(name, f"org.jetbrains.kotlin:kotlin-{name}"))
    for group, artifact in _RAW_DEP_REF.findall(line):
        found.add(f"{group}:{artifact}")
    return found


def declared_dependency_scopes(root=ROOT):
    """External module id -> where it is used: app | server | test | build.

    Catalog-only entries that no build file references default to "build"; they
    are still catalogued (attribution has no scope) but never masquerade as app.
    """
    alias_to_module = _parse_version_catalog(root)
    scopes = {module: "build" for module in alias_to_module.values()}
    scopes.update({module: "build" for module in _UNUSED_DECLARED})
    for build_file in sorted(root.glob("**/build.gradle.kts")):
        if "build" in build_file.parts:
            continue
        source = build_file.read_text(encoding="utf-8")
        base = _BUILD_FILE_SCOPE.get(build_file.relative_to(root).parts[0], "build")
        for line in source.splitlines():
            verb = _DEP_VERB_RE.search(line)
            if verb is None:
                continue
            scope = "test" if verb.group(1) in ("testImplementation", "androidTestImplementation") else base
            for module in _modules_in_line(line, alias_to_module):
                if _SCOPE_RANK[scope] > _SCOPE_RANK.get(scopes.get(module, "build"), 0):
                    scopes[module] = scope
    return scopes


def declared_dependency_ids(root=ROOT):
    """Every external module declared by the version catalog or a build file."""
    return sorted(declared_dependency_scopes(root))


@functools.lru_cache(maxsize=1)
def _catalog_and_unresolved():
    """(components tuple, unresolved coordinates tuple), cached per process."""
    runtime_ids = runtime_module_ids()
    version_of = {":".join(c.split(":")[:2]): c.split(":")[2] for c in runtime_coordinates()}
    scopes = declared_dependency_scopes()
    needed = dict.fromkeys(runtime_ids | set(scopes) | set(CURATED_DEPS))
    components, unresolved = {}, []
    for module_id in sorted(needed):
        scope = "app" if module_id in runtime_ids else scopes.get(module_id, "build")
        entry = _auto_component(module_id, version_of.get(module_id), scope)
        if entry is None:
            unresolved.append(module_id)
        else:
            components[module_id] = entry
    for component in COMPONENTS:
        components[component["id"]] = component
    return tuple(sorted(components.values(), key=lambda c: c["id"])), tuple(unresolved)


def build_catalog():
    return list(_catalog_and_unresolved()[0])


def unresolved_dependencies():
    return list(_catalog_and_unresolved()[1])


def distributed_component_ids():
    """Ids that reach users: the resolved APK runtime plus the shipped content.

    Server/build/test-only artifacts are still catalogued (attribution has no
    scope), but only this set gates the build on its licence, matching the audit:
    the binary ships the runtime classpath and the served content, nothing else.
    """
    shipped = {c["id"] for c in COMPONENTS if c.get("distribution") in ("app", "content")}
    return set(runtime_module_ids()) | shipped


def _licence_problems(component):
    licence = (component.get("license") or "").strip()
    haystack = f"{licence} {component.get('licenseName') or ''}".lower()
    problems = []
    if not licence:
        problems.append("unattributed: no licence resolved")
    for marker in PROHIBITED_LICENCE_MARKERS:
        if marker in haystack:
            problems.append(f"prohibited licence marker '{marker}'")
    if licence and licence not in ALLOWED_LICENCES and not problems:
        problems.append(f"licence '{licence}' is not in the allow-list")
    return problems


def licence_violations(components=None, distributed_ids=None):
    """Every catalogued entry whose licence is unattributed, prohibited or not
    explicitly allowed. Fail-closed: an unreadable/unknown licence is a finding.
    """
    entries = list(build_catalog() if components is None else components)
    distributed = distributed_component_ids() if distributed_ids is None else set(distributed_ids)
    violations = []
    for component in entries:
        problems = _licence_problems(component)
        if problems:
            violations.append({
                "id": component["id"],
                "license": component.get("license"),
                "scope": "distributed" if component["id"] in distributed else "non-distributed",
                "problems": problems,
            })
    return violations


def _is_blocking(violation):
    """A violation stops the build when it is shipped, or when it leaves any
    catalogued entry unattributed. Server/build/test-only copyleft is a warning:
    it does not enter the artifact (see tools/licensing/runtime-classpath-*.txt)."""
    if violation["scope"] == "distributed":
        return True
    return any(problem.startswith("unattributed") for problem in violation["problems"])


def gate_decision(components=None, distributed_ids=None):
    """(blocking, warnings) exactly as the `--check` gate would decide them."""
    violations = licence_violations(components, distributed_ids)
    blocking = [v for v in violations if _is_blocking(v)]
    warnings = [v for v in violations if not _is_blocking(v)]
    return blocking, warnings


def _normalise_component(component):
    license = component["license"]
    text_key = component.get("text") or LICENCE_TEXT.get(license)
    out = {key: component.get(key) for key in ("id", "name", "author", "url", "usage", "distribution")}
    out["license"] = license
    out["licenseName"] = component.get("licenseName") or LICENSE_NAMES.get(license, license)
    out["licenseText"] = text_key
    out["licenseTextUrl"] = None if text_key else (component.get("licenseTextUrl") or LICENSE_URLS.get(license))
    out["notice"] = component.get("notice")
    return out


def build_asset():
    components = [_normalise_component(c) for c in build_catalog()]
    texts = {}
    for component in components:
        key = component["licenseText"]
        if key and key not in texts:
            texts[key] = (ROOT / TEXT_FILES[key]).read_text(encoding="utf-8")
    return {
        "schema": SCHEMA,
        "generatedBy": "tools/licensing/collect.py",
        "texts": texts,
        "components": components,
    }


def _sync_privacy():
    PRIVACY_ASSET_DIR.mkdir(parents=True, exist_ok=True)
    for locale, source in PRIVACY_SOURCES.items():
        privacy_asset(locale).write_text(source.read_text(encoding="utf-8"), encoding="utf-8")


def _privacy_stale():
    stale = []
    for locale, source in PRIVACY_SOURCES.items():
        target = privacy_asset(locale)
        if not target.is_file() or target.read_text(encoding="utf-8") != source.read_text(encoding="utf-8"):
            stale.append(str(target.relative_to(ROOT)))
    return stale


def check(verbose=True):
    """Returns (missing, stale, blocking); all empty when the assets are in
    lockstep and no shipped catalogue entry carries a prohibited licence."""
    components = build_catalog()
    component_ids = {c["id"] for c in components}
    missing = sorted(
        coordinate for coordinate in runtime_coordinates()
        if ":".join(coordinate.split(":")[:2]) not in component_ids
    )
    missing += [f"{m} (unresolved licence)" for m in unresolved_dependencies()]

    stale = []
    if ASSET.is_file():
        if json.loads(ASSET.read_text(encoding="utf-8")) != build_asset():
            stale.append(str(ASSET.relative_to(ROOT)))
    else:
        stale.append(f"{ASSET.relative_to(ROOT)} (missing)")
    stale += _privacy_stale()

    blocking, warnings = gate_decision(components)

    if verbose:
        print(f"runtime modules: {len(runtime_coordinates())}")
        print(f"catalogued components: {len(component_ids)}")
        if missing:
            print("MISSING from the catalogue:")
            for m in missing:
                print(f"  - {m}")
        if stale:
            print(f"STALE (run `python tools/licensing/collect.py`): {', '.join(stale)}")
        for label, entries in (("BLOCKING", blocking), ("WARNING (not distributed)", warnings)):
            if entries:
                print(f"{label} licences:")
                for violation in entries:
                    problems = "; ".join(violation["problems"])
                    print(f"  - [{violation['scope']}] {violation['id']}: {violation['license'] or '(none)'} - {problems}")
    return missing, stale, blocking


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="validate coverage/freshness and licence policy, write nothing")
    args = ap.parse_args()

    if args.check:
        missing, stale, blocking = check()
        if missing or stale or blocking:
            raise SystemExit(1)
        print("OK: every runtime artifact is catalogued, the assets are up to date and no shipped licence is prohibited")
        return

    asset = build_asset()
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_text(json.dumps(asset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    _sync_privacy()
    print(f"wrote {ASSET.relative_to(ROOT)} ({len(asset['components'])} components, {len(asset['texts'])} licence texts)")
    for locale in PRIVACY_SOURCES:
        print(f"wrote {privacy_asset(locale).relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())
