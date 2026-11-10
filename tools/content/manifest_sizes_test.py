"""Manifest size stamping tests. Run:  python3 manifest_sizes_test.py

- The published manifest gets the real byte size of every downloadable asset,
  read from the files on disk (audio/ and images/), never estimated.
- Every bank gets the total of the assets its own items point at.
- Re-running is idempotent: the same files produce the same bytes.
- A manifest without a matching asset dir still gets an empty map, not an error.
"""

import json
import os
import sqlite3
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from pipeline import SCHEMA, asset_sizes, stamp_manifest_sizes


class ManifestSizesTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = self.tmp.name
        os.makedirs(os.path.join(self.root, "audio"))
        os.makedirs(os.path.join(self.root, "images"))
        self._write("audio/drill.ogg", 10)
        self._write("audio/drill.slow.ogg", 20)
        self._write("audio/story.p0.ogg", 30)
        self._write("images/story.png", 40)
        self._write("images/not-referenced.svg", 50)

        self.manifest_path = os.path.join(self.root, "manifest.json")
        with open(self.manifest_path, "w", encoding="utf-8") as fh:
            json.dump(
                {
                    "version": 1,
                    "language": "en",
                    "banks": [
                        {"kind": "DRILL", "path": "drills/", "version": 1, "count": 1},
                        {"kind": "STORY", "path": "stories/", "version": 1, "count": 1},
                        {"kind": "LESSON", "path": "lessons/", "version": 1, "count": 0},
                    ],
                },
                fh,
            )

        conn = sqlite3.connect(os.path.join(self.root, "content.db"))
        conn.executescript(SCHEMA)
        self._item(conn, "d1", "DRILL", {"audio": {"en-GB-female": "audio/drill.ogg"}})
        self._item(
            conn,
            "s1",
            "STORY",
            {"image": "images/story.png", "paragraphAudio": [{"en-GB-female": "audio/story.p0.ogg"}]},
        )
        conn.commit()
        conn.close()

    def tearDown(self):
        self.tmp.cleanup()

    def _write(self, rel, size):
        with open(os.path.join(self.root, rel), "wb") as fh:
            fh.write(b"x" * size)

    def _item(self, conn, item_id, kind, data):
        conn.execute(
            "insert into content values (?,?,?,?,?,?,?,?)",
            (item_id, kind, "en", "A1", "city", "text", None, json.dumps(data)),
        )

    def _manifest(self):
        return self._read(self.manifest_path)

    def test_asset_sizes_read_the_files_on_disk(self):
        sizes = asset_sizes(self.root)
        self.assertEqual(10, sizes["audio/drill.ogg"])
        self.assertEqual(40, sizes["images/story.png"])
        self.assertEqual(5, len(sizes))

    def test_stamp_records_assets_and_per_bank_totals(self):
        measured = stamp_manifest_sizes(self.manifest_path, self.root)
        self.assertEqual(5, measured)

        manifest = self._manifest()
        self.assertEqual(10, manifest["assets"]["audio/drill.ogg"])
        self.assertEqual(40, manifest["assets"]["images/story.png"])

        banks = {b["kind"]: b for b in manifest["banks"]}
        # DRILL: one clip. STORY: one paragraph clip plus its artwork.
        self.assertEqual(10, banks["DRILL"]["bytes"])
        self.assertEqual(30 + 40, banks["STORY"]["bytes"])
        self.assertEqual(0, banks["LESSON"]["bytes"])

    def test_stamp_is_idempotent(self):
        stamp_manifest_sizes(self.manifest_path, self.root)
        first = json.dumps(self._manifest(), sort_keys=True)
        stamp_manifest_sizes(self.manifest_path, self.root)
        self.assertEqual(first, json.dumps(self._manifest(), sort_keys=True))

    def test_no_asset_dirs_is_an_empty_map_not_an_error(self):
        empty = tempfile.TemporaryDirectory()
        try:
            path = os.path.join(empty.name, "manifest.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"version": 1, "banks": []}, fh)
            measured = stamp_manifest_sizes(path, empty.name)
            self.assertEqual(0, measured)
            self.assertEqual({}, self._read(path)["assets"])
        finally:
            empty.cleanup()

    def _read(self, path):
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)


if __name__ == "__main__":
    unittest.main()
