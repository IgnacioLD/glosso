"""Validate the shipped per-image provenance manifest.

The manifest lives next to the catalogue images in the content repo
(`glosso-data/content/en/images/provenance.json`), so this test locates it via
`$GLOSSO_CONTENT_DIR` or the sibling checkout and skips when it is not present.
The generator logic in `images.py` is covered by `images_test.py`.

Run: GLOSSO_CONTENT_DIR=/path/to/glosso-data/content/en python3 tools/content/images_provenance_test.py
"""

import glob
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import images

_HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CONTENT = os.path.abspath(os.path.join(_HERE, "..", "..", "..", "glosso-data", "content", "en"))

REQUIRED_FIELDS = (
    "provider", "model", "model_revision", "seed", "seed_offset", "steps", "guidance",
    "dtype", "prompt_hash", "duotone_version", "license", "commercial_license", "date",
    "provenance",
)


def content_dir():
    return os.environ.get("GLOSSO_CONTENT_DIR", DEFAULT_CONTENT)


class TestShippedImageProvenance(unittest.TestCase):
    def setUp(self):
        self.images_dir = os.path.join(content_dir(), "images")
        self.manifest_path = os.path.join(self.images_dir, images.PROVENANCE_FILE)
        if not os.path.isfile(self.manifest_path):
            self.skipTest(f"no provenance manifest at {self.manifest_path}")
        with open(self.manifest_path, encoding="utf-8") as fh:
            self.manifest = json.load(fh)

    def test_schema_and_required_fields(self):
        self.assertEqual(self.manifest["schema"], images.PROVENANCE_SCHEMA)
        for image_id, entry in self.manifest["images"].items():
            with self.subTest(image=image_id):
                for field in REQUIRED_FIELDS:
                    self.assertIn(field, entry)
                self.assertIn(entry["provenance"], ("observed", "inferred"))

    def test_every_image_on_disk_has_an_entry(self):
        pngs = {
            os.path.splitext(os.path.basename(path))[0]
            for path in glob.glob(os.path.join(self.images_dir, "*.png"))
        }
        missing = sorted(pngs - set(self.manifest["images"]))
        self.assertEqual(missing, [], f"images without provenance (first 10): {missing[:10]}")

    def test_catalogue_images_are_all_commercially_licensed(self):
        offenders = [
            image_id for image_id, entry in self.manifest["images"].items()
            if not entry["commercial_license"]
        ]
        self.assertEqual(offenders, [], f"non-commercial images in the catalogue (first 10): {offenders[:10]}")

    def test_no_entry_uses_a_known_non_commercial_model(self):
        offenders = [
            image_id for image_id, entry in self.manifest["images"].items()
            if entry["model"] in images.NON_COMMERCIAL_MODELS
        ]
        self.assertEqual(offenders, [], f"non-commercial model used by (first 10): {offenders[:10]}")


if __name__ == "__main__":
    unittest.main()
