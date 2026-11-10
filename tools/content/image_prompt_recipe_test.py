"""Unit tests for image_prompt_recipe.py (pure string work, no network).

Run: python tools/content/image_prompt_recipe_test.py   (or: pytest ...)
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import image_prompt_recipe as recipe

TEMPLATE = (
    "abstract editorial illustration, {subject}, "
    "thin brass-gold continuous line on warm black background, minimal, "
    "lots of negative space, subtle grain, no text, 16:9"
)


class TestSplitSubject(unittest.TestCase):
    def test_extracts_subject_from_template(self):
        self.assertEqual(
            recipe.split_subject(TEMPLATE.format(subject="an abstract cup")),
            "an abstract cup",
        )

    def test_non_template_is_none(self):
        self.assertIsNone(recipe.split_subject("a photo of a dog"))
        self.assertIsNone(recipe.split_subject(""))


class TestStripAbstract(unittest.TestCase):
    def test_drops_every_abstract_and_repairs_articles(self):
        cases = {
            "an abstract cup": "a cup",              # an -> a before consonant
            "an abstract elder silhouette": "an elder silhouette",  # stays an
            "abstract sound-wave lines": "sound-wave lines",
            "two abstract silhouettes": "two silhouettes",
            "a abstract tool": "a tool",
        }
        for given, want in cases.items():
            self.assertEqual(recipe.strip_abstract(given), want, given)


class TestRewrite(unittest.TestCase):
    def test_keeps_subject_and_swaps_style_and_composition(self):
        out = recipe.rewrite("an abstract bus shape on a road line")
        self.assertTrue(out.startswith("editorial line illustration, a bus shape on a road line, "))
        self.assertTrue(out.endswith(", no text, 16:9"))
        self.assertIn("absolutely no solid fills", out)
        self.assertIn("a ground line anchoring the scene", out)
        self.assertNotIn("abstract", out)
        self.assertNotIn("subtle grain", out)

    def test_override_replaces_subject_but_keeps_recipe(self):
        out = recipe.rewrite("an abstract cup", override="a red mug outline")
        self.assertIn("editorial line illustration, a red mug outline, ", out)

    def test_rewrite_prompt_passes_non_template_through(self):
        self.assertEqual(recipe.rewrite_prompt("a photo of a dog"), "a photo of a dog")

    def test_rewrite_prompt_on_template(self):
        out = recipe.rewrite_prompt(TEMPLATE.format(subject="an abstract cup"))
        self.assertTrue(out.startswith("editorial line illustration, a cup, "))
        self.assertNotIn("subtle grain", out)


if __name__ == "__main__":
    unittest.main()
