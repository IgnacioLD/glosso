"""Guard: espeak-ng (GPL-3.0) must never enter a distributable artifact.

espeak-ng is a **build-time** tool only (tools/content/phonemize.py, invoked with
`uv run --with espeakng-loader --with phonemizer-fork`). It must not appear as a
runtime dependency of any distributable module, in the server Dockerfile, in the
app/server sources or in a packaged APK. See docs/LICENSING.md section 4.b.

Run: python3 tools/licensing/espeak_boundary_test.py
"""

import glob
import os
import re
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Any mention in a dependency declaration or the container build is a failure.
DEPENDENCY_PATTERN = re.compile(r"espeak|phonemizer", re.IGNORECASE)

# Code that would link or shell out to the library at runtime. Plain comments
# that name the model (wav2vec2-...-espeak-...) or the phonemizer are not
# matched on purpose: only import/binding forms count.
CODE_PATTERN = re.compile(
    r"espeakng_loader|espeakng-loader|phonemizer_fork|phonemizer-fork|"
    r"from phonemizer|import phonemizer|EspeakWrapper"
)

# Source trees that build a distributable artifact (APK/AAB, server jar/image).
DISTRIBUTABLE_SOURCES = ("androidApp/src", "engine/src", "server/src")

# The one place espeak-ng is allowed: the build-time authoring tool. The guard
# itself references the library names in its patterns, so it is excluded too.
ALLOWED_CODE_REFERENCES = {
    "tools/content/phonemize.py",
    "tools/licensing/espeak_boundary_test.py",
}


def _iter_files(base, extra_globs):
    files = set()
    for pattern in extra_globs:
        files.update(ROOT.glob(pattern))
    root = ROOT / base
    if root.exists():
        files.update(path for path in root.rglob("*") if path.is_file())
    return sorted(
        path
        for path in files
        if path.is_file()
        and "__pycache__" not in path.parts
        and path.suffix != ".pyc"
    )


class EspeakBoundaryTest(unittest.TestCase):
    def test_no_espeak_runtime_dependency_in_gradle_or_catalog(self):
        gradle_files = {
            path for path in ROOT.glob("**/*.gradle.kts")
            if path.is_file() and "build" not in path.parts and ".gradle" not in path.parts
        }
        gradle_files.add(ROOT / "gradle/libs.versions.toml")
        offenders = [
            str(path.relative_to(ROOT))
            for path in sorted(gradle_files)
            if path.is_file() and DEPENDENCY_PATTERN.search(path.read_text(encoding="utf-8"))
        ]
        self.assertEqual(offenders, [], f"espeak-ng dependency declared in: {offenders}")

    def test_no_espeak_install_in_the_server_container(self):
        dockerfile = ROOT / "deploy/Dockerfile"
        if not dockerfile.is_file():
            self.skipTest("no Dockerfile")
        offenders = [
            line for line in dockerfile.read_text(encoding="utf-8").splitlines()
            if DEPENDENCY_PATTERN.search(line)
        ]
        self.assertEqual(offenders, [], f"espeak-ng referenced in the Dockerfile: {offenders}")

    def test_only_the_build_tool_references_the_espeak_library(self):
        in_product = [
            str(path.relative_to(ROOT))
            for base in DISTRIBUTABLE_SOURCES
            for path in _iter_files(base, ())
            if CODE_PATTERN.search(path.read_text(encoding="utf-8", errors="ignore"))
        ]
        self.assertEqual(in_product, [], f"espeak-ng code in a distributable module: {in_product}")

        in_tools = sorted(
            str(path.relative_to(ROOT))
            for path in _iter_files("tools", ())
            if CODE_PATTERN.search(path.read_text(encoding="utf-8", errors="ignore"))
        )
        self.assertEqual(
            in_tools, sorted(ALLOWED_CODE_REFERENCES),
            "the espeak-ng library must only be referenced by the build-time tool and this guard",
        )

    def test_packaged_apks_do_not_bundle_espeak(self):
        apks = list(ROOT.glob("apks/*.apk")) + list(ROOT.glob("androidApp/build/**/*.apk"))
        checked = 0
        for apk in apks:
            with zipfile.ZipFile(apk) as archive:
                hits = [name for name in archive.namelist() if DEPENDENCY_PATTERN.search(name)]
            self.assertEqual(hits, [], f"espeak-ng bundled in {apk.name}: {hits}")
            checked += 1
        if checked == 0:
            self.skipTest("no packaged APK present")


if __name__ == "__main__":
    unittest.main()
