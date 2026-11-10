"""Unit tests for images.py (no GPU, no network, no torch).

Run: python tools/content/images_test.py   (or: pytest tools/content/images_test.py)
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
import contextlib
import io
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import images

IMAGES_PY = os.path.abspath(images.__file__)


class TestSeedGolden(unittest.TestCase):
    """seed_for is the reproducibility contract: pin known ids to known seeds."""

    GOLDEN = {
        "en.exercise.food.001": 729436253,
        "en.story.city.001": 780094177,
        "en.dialogue.family.001": 2748910327,
    }

    def test_seed_golden_values(self):
        for item_id, seed in self.GOLDEN.items():
            self.assertEqual(images.seed_for(item_id), seed)


class TestLutGolden(unittest.TestCase):
    """The duotone ramp is the brand contract: pin known grays to known colours."""

    GOLDEN = {
        0: (14, 14, 18),
        32: (32, 27, 18),
        64: (62, 51, 25),
        128: (162, 131, 61),
        192: (224, 191, 109),
        255: (242, 237, 227),
    }

    def test_lut_golden_values(self):
        for gray, colour in self.GOLDEN.items():
            self.assertEqual((images.LUT[gray], images.LUT[256 + gray], images.LUT[512 + gray]), colour)

    def test_lut_endpoints_are_exact_palette_entries(self):
        self.assertEqual((images.LUT[0], images.LUT[256], images.LUT[512]), images.PALETTE[0])
        self.assertEqual((images.LUT[255], images.LUT[511], images.LUT[767]), images.PALETTE[-1])


class TestBrandify(unittest.TestCase):
    def test_duotone_maps_endpoints_exactly(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("PIL not installed")
        black = Image.new("L", (8, 8), 0)
        self.assertEqual(black.convert("RGB").point(images.LUT).getpixel((0, 0)), images.PALETTE[0])
        white = Image.new("L", (8, 8), 255)
        self.assertEqual(white.convert("RGB").point(images.LUT).getpixel((0, 0)), images.PALETTE[-1])

    def test_brandify_without_grain_is_deterministic_and_flat(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("PIL not installed")
        import io

        buf = io.BytesIO()
        Image.new("L", (64, 64), 40).save(buf, format="PNG")
        raw = buf.getvalue()

        a = images.brandify(io.BytesIO(raw), grain=False)
        b = images.brandify(io.BytesIO(raw), grain=False)
        self.assertEqual(a.tobytes(), b.tobytes(), "grain-free render must be reproducible")
        self.assertEqual(len(a.getcolors(maxcolors=1 << 20)), 1, "grain-free render must be flat")

        c = images.brandify(io.BytesIO(raw), grain=True)
        self.assertGreater(len(c.getcolors(maxcolors=1 << 20)), 1, "grain must add tone variation")

    def test_brandify_keeps_solid_input_on_palette_endpoints(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("PIL not installed")
        import io

        buf = io.BytesIO()
        Image.new("L", (32, 32), 255).save(buf, format="PNG")
        out = images.brandify(buf)
        px = out.load()
        w, h = out.size
        for y in range(h):
            for x in range(w):
                for channel, target in zip(px[x, y], images.PALETTE[-1]):
                    self.assertLessEqual(abs(channel - target), 8, f"drifted off palette at {x},{y}: {px[x, y]}")


class TestCliDefaults(unittest.TestCase):
    def test_cli_defaults_match_documented_values(self):
        original = images.argparse.ArgumentParser.parse_args
        captured = {}

        def fake_parse(parser, args=None, namespace=None):
            captured["args"] = original(parser, [], namespace)
            raise SystemExit(0)

        with mock.patch.object(images.argparse.ArgumentParser, "parse_args", fake_parse):
            with self.assertRaises(SystemExit):
                images.main()
        args = captured["args"]
        self.assertEqual(args.backend, "local")
        self.assertIsNone(args.model)
        self.assertEqual(args.device, "cuda")
        self.assertEqual(args.dtype, "bfloat16")
        self.assertEqual(args.steps, images.DEFAULT_LOCAL_STEPS)
        self.assertEqual(args.steps, 32)
        self.assertEqual(args.guidance, images.DEFAULT_LOCAL_GUIDANCE)
        self.assertEqual(args.guidance, 4.0)
        self.assertEqual(args.seed_offset, 0)
        self.assertEqual(args.model_dir, images.DEFAULT_MODEL_DIR)

    def test_no_default_points_at_a_non_commercial_model(self):
        # Regression: DEFAULT_MODEL used to be the non-commercial 9B.
        self.assertFalse(
            hasattr(images, "DEFAULT_MODEL") and images.DEFAULT_MODEL in images.NON_COMMERCIAL_MODELS
        )
        self.assertEqual(images.DEFAULT_BACKEND, "local")
        licence, commercial = images.model_licence(images.COMMERCIAL_MODEL)
        self.assertEqual(licence, "Apache-2.0")
        self.assertTrue(commercial)

    def test_non_commercial_model_is_recognised_and_flagged(self):
        licence, commercial = images.model_licence("black-forest-labs/FLUX-2-klein-9b")
        self.assertEqual(licence, "FLUX Non-Commercial License")
        self.assertFalse(commercial)

    def test_deepinfra_backend_requires_an_explicit_model(self):
        argv = ["images.py", "--backend", "deepinfra", "--dry-run"]
        with mock.patch.object(sys, "argv", argv):
            with self.assertRaises(SystemExit):
                images.main()

    def test_non_commercial_model_warns_and_is_recorded_non_commercial(self):
        tmp = tempfile.mkdtemp(prefix="glosso-images-test-")
        try:
            kind_dir = os.path.join(tmp, "exercises")
            os.makedirs(kind_dir)
            with open(os.path.join(kind_dir, "exercises.json"), "w", encoding="utf-8") as f:
                json.dump({"exercises": [{"id": "en.exercise.z.001", "imagePrompt": "abstract"}]}, f)
            argv = [
                "images.py", "--backend", "deepinfra", "--model", "black-forest-labs/FLUX-2-klein-9b",
                "--dry-run", "--content", tmp, "--kind", "exercises",
            ]
            stderr = io.StringIO()
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stderr(stderr):
                images.main()
            self.assertIn("NOT for commercial distribution", stderr.getvalue())
            licence, commercial = images.model_licence("black-forest-labs/FLUX-2-klein-9b")
            self.assertFalse(commercial)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)



class TestLocalWiring(unittest.TestCase):
    def test_local_backend_forwards_cli_kwargs_to_generate_local(self):
        tmp = tempfile.mkdtemp(prefix="glosso-images-test-")
        try:
            kind_dir = os.path.join(tmp, "exercises")
            os.makedirs(kind_dir)
            item_id = "en.exercise.fixture.001"
            with open(os.path.join(kind_dir, "exercises.json"), "w", encoding="utf-8") as f:
                json.dump({"exercises": [{"id": item_id, "imagePrompt": "abstract test"}]}, f)
            fake_generate = mock.MagicMock(return_value=b"PNG")
            fake_brandify = mock.MagicMock()
            argv = [
                "images.py", "--backend", "local", "--kind", "exercises", "--content", tmp,
                "--steps", "7", "--guidance", "1.5", "--device", "cpu", "--dtype", "float32",
                "--model-dir", "/opt/models/x",
            ]
            with mock.patch.object(images, "generate_local", fake_generate), \
                 mock.patch.object(images, "brandify", fake_brandify), \
                 mock.patch.object(sys, "argv", argv):
                images.main()
            fake_generate.assert_called_once()
            pos, kw = fake_generate.call_args
            self.assertEqual(pos[0], "/opt/models/x")
            self.assertEqual(pos[1], "abstract test")
            self.assertEqual(pos[2:5], (1280, 720, images.seed_for(item_id)))
            self.assertEqual(kw, {"device": "cpu", "dtype": "float32", "steps": 7, "guidance": 1.5})
            fake_brandify.assert_called_once()
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_seed_offset_is_added_to_the_id_seed(self):
        tmp = tempfile.mkdtemp(prefix="glosso-images-test-")
        try:
            kind_dir = os.path.join(tmp, "exercises")
            os.makedirs(kind_dir)
            item_id = "en.exercise.fixture.002"
            with open(os.path.join(kind_dir, "exercises.json"), "w", encoding="utf-8") as f:
                json.dump({"exercises": [{"id": item_id, "imagePrompt": "abstract test"}]}, f)
            fake_generate = mock.MagicMock(return_value=b"PNG")
            argv = ["images.py", "--backend", "local", "--kind", "exercises", "--content", tmp, "--seed-offset", "5"]
            with mock.patch.object(images, "generate_local", fake_generate), \
                 mock.patch.object(images, "brandify", mock.MagicMock()), \
                 mock.patch.object(sys, "argv", argv):
                images.main()
            pos, _ = fake_generate.call_args
            expected = (images.seed_for(item_id) + 5) % (2 ** 32)
            self.assertEqual(pos[4], expected)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class TestPipelineCache(unittest.TestCase):
    def _fake_modules(self, calls):
        fake_torch = types.ModuleType("torch")
        fake_torch.bfloat16 = "bfloat16"
        fake_torch.float32 = "float32"

        class FakeGenerator:
            def __init__(self, device=None):
                self.device = device

            def manual_seed(self, seed):
                return self

        fake_torch.Generator = FakeGenerator

        class FakeImage:
            def save(self, buf, format=None):
                buf.write(b"PNG")

        class FakePipe:
            def to(self, device):
                return self

            def __call__(self, **kw):
                result = types.SimpleNamespace()
                result.images = [FakeImage()]
                return result

        class FakePipeline:
            @staticmethod
            def from_pretrained(model_dir, **kw):
                calls.append((model_dir, kw))
                return FakePipe()

        fake_diffusers = types.ModuleType("diffusers")
        fake_diffusers.Flux2KleinPipeline = FakePipeline
        return fake_torch, fake_diffusers

    def test_pipeline_loaded_once_and_reused(self):
        calls = []
        fake_torch, fake_diffusers = self._fake_modules(calls)
        with mock.patch.dict(sys.modules, {"torch": fake_torch, "diffusers": fake_diffusers}):
            images._PIPELINE_CACHE.clear()
            images.generate_local("/m", "p", 1280, 720, 1)
            images.generate_local("/m", "p", 1280, 720, 2)
            self.assertEqual(len(calls), 1, "pipeline must load once per invocation")
            self.assertEqual(calls[0][1].get("dtype"), "bfloat16")
            images.generate_local("/m", "p", 1280, 720, 3, dtype="float32")
            self.assertEqual(len(calls), 2, "different dtype needs its own pipeline")
            images._PIPELINE_CACHE.clear()


class TestDryRunLocalBackend(unittest.TestCase):
    def test_dry_run_with_local_backend_never_imports_torch_or_calls_api(self):
        tmp = tempfile.mkdtemp(prefix="glosso-images-test-")
        try:
            kind_dir = os.path.join(tmp, "exercises")
            os.makedirs(kind_dir)
            bank = {"exercises": [{"id": "en.exercise.fixture.001", "imagePrompt": "abstract gold line art, test"}]}
            with open(os.path.join(kind_dir, "exercises.json"), "w", encoding="utf-8") as f:
                json.dump(bank, f)
            script = (
                "import runpy, sys;"
                f"sys.argv=['images.py','--dry-run','--backend','local','--content','{tmp}','--kind','exercises'];"
                f"runpy.run_path({IMAGES_PY!r}, run_name='__main__');"
                "print('TORCH_LOADED=' + str('torch' in sys.modules))"
            )
            res = subprocess.run(
                [sys.executable, "-c", script],
                capture_output=True,
                text=True,
                timeout=60,
                env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertIn("1 generated, 0 without imagePrompt", res.stdout)
            self.assertIn("TORCH_LOADED=False", res.stdout)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class TestProvenance(unittest.TestCase):
    def _content(self, tmp):
        kind_dir = os.path.join(tmp, "exercises")
        os.makedirs(kind_dir)
        item = {"id": "en.exercise.food.001", "imagePrompt": "abstract gold line art"}
        with open(os.path.join(kind_dir, "exercises.json"), "w", encoding="utf-8") as f:
            json.dump({"exercises": [item]}, f)
        images_dir = os.path.join(tmp, "images")
        os.makedirs(images_dir)
        with open(os.path.join(images_dir, "en.exercise.food.001.png"), "wb") as f:
            f.write(b"PNG")
        return images_dir

    def test_record_carries_the_required_fields(self):
        record = images.provenance_record(
            provider="local", model=images.COMMERCIAL_MODEL, seed=7, steps=32, guidance=4.0,
            dtype="bfloat16", prompt="hello", revision="local-abc", date="2026-09-23T00:00:00Z",
        )
        for field in ("provider", "model", "model_revision", "seed", "steps", "guidance", "dtype",
                      "prompt_hash", "duotone_version", "license", "commercial_license", "date", "provenance"):
            self.assertIn(field, record)
        self.assertEqual(record["prompt_hash"], images.prompt_hash("hello"))
        self.assertTrue(record["commercial_license"])
        self.assertEqual(record["provenance"], "observed")

    def test_backfill_adds_inferred_records_and_is_idempotent(self):
        tmp = tempfile.mkdtemp(prefix="glosso-images-test-")
        try:
            images_dir = self._content(tmp)
            added, total = images.backfill_provenance(
                tmp, images_dir, images.DEFAULT_MODEL_DIR, 32, 4.0, "bfloat16", 0,
            )
            self.assertEqual((added, total), (1, 1))
            manifest = json.load(open(os.path.join(images_dir, "provenance.json"), encoding="utf-8"))
            entry = manifest["images"]["en.exercise.food.001"]
            self.assertEqual(entry["provenance"], "inferred")
            self.assertEqual(entry["model"], images.COMMERCIAL_MODEL)
            self.assertTrue(entry["commercial_license"])
            self.assertEqual(entry["prompt_hash"], images.prompt_hash("abstract gold line art"))
            self.assertEqual(entry["date"].endswith("Z"), True)
            added_again, _ = images.backfill_provenance(
                tmp, images_dir, images.DEFAULT_MODEL_DIR, 32, 4.0, "bfloat16", 0,
            )
            self.assertEqual(added_again, 0, "inferred records must not be rewritten on every run")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_backfill_never_overwrites_observed_records(self):
        tmp = tempfile.mkdtemp(prefix="glosso-images-test-")
        try:
            images_dir = self._content(tmp)
            observed = images.provenance_record(
                provider="local", model=images.COMMERCIAL_MODEL, prompt="x", date="2026-01-01T00:00:00Z",
            )
            images.write_provenance(images_dir, {"schema": 1, "images": {"en.exercise.food.001": observed}})
            images.backfill_provenance(tmp, images_dir, images.DEFAULT_MODEL_DIR, 32, 4.0, "bfloat16", 0)
            manifest = json.load(open(os.path.join(images_dir, "provenance.json"), encoding="utf-8"))
            self.assertEqual(manifest["images"]["en.exercise.food.001"], observed)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


    def test_reused_art_is_recorded_as_commercial(self):
        content = tempfile.mkdtemp(prefix="glosso-images-test-")
        source = tempfile.mkdtemp(prefix="glosso-images-source-")
        try:
            with open(os.path.join(source, "en.exercise.other.001.png"), "wb") as f:
                f.write(b"PNG")
            kind_dir = os.path.join(content, "exercises")
            os.makedirs(kind_dir)
            with open(os.path.join(kind_dir, "exercises.json"), "w", encoding="utf-8") as f:
                json.dump({"exercises": [{"id": "en.exercise.food.001", "imagePrompt": "abstract"}]}, f)
            argv = [
                "images.py", "--kind", "exercises", "--content", content, "--reuse-from", source,
            ]
            with mock.patch.object(sys, "argv", argv):
                images.main()
            manifest = json.load(open(os.path.join(content, "images", "provenance.json"), encoding="utf-8"))
            entry = manifest["images"]["en.exercise.food.001"]
            self.assertEqual(entry["provider"], "reuse")
            self.assertTrue(entry["commercial_license"])
        finally:
            shutil.rmtree(content, ignore_errors=True)
            shutil.rmtree(source, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
