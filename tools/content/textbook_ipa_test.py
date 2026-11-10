"""Unit tests for the textbook (learner-dictionary) IPA rewrite. No espeak needed.

  python3 tools/content/textbook_ipa_test.py
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import textbook_ipa


class RewriteTest(unittest.TestCase):
    def test_dress_is_e_not_epsilon(self):
        # Oxford/Cambridge DRESS: bed /bed/, red /red/.
        self.assertEqual("bˈed", textbook_ipa.to_textbook("bˈɛd"))
        self.assertEqual("rˈed", textbook_ipa.to_textbook("ɹˈɛd"))

    def test_trap_is_ae_but_the_diphthongs_keep_a(self):
        self.assertEqual("mˈæn", textbook_ipa.to_textbook("mˈan"))
        # PRICE and MOUTH are aɪ/aʊ, not æɪ/æʊ.
        self.assertEqual("prˈaɪs", textbook_ipa.to_textbook("pɹˈaɪs"))
        self.assertEqual("mˈaʊθ", textbook_ipa.to_textbook("mˈaʊθ"))

    def test_near_is_the_short_i_but_long_ii_stays(self):
        self.assertEqual("nˈɪə", textbook_ipa.to_textbook("nˈiə"))
        # "Leo" is /ˈliːəʊ/, the long vowel must not shrink.
        self.assertEqual("lˈiːəʊ", textbook_ipa.to_textbook("lˈiːəʊ"))

    def test_unstressed_central_vowel_folds_to_schwa(self):
        self.assertEqual("əbˈaʊt", textbook_ipa.to_textbook("ɐbˈaʊt"))
        self.assertEqual("bənˈɑːnə", textbook_ipa.to_textbook("bɐnˈɑːnə"))

    def test_syllabic_n_is_written_plain(self):
        self.assertEqual("wˈɒznt", textbook_ipa.to_textbook("wˈɒzn\u0329t"))

    def test_the_dictionary_vowels_are_untouched(self):
        for ipa in ("iː", "ɪ", "ɒ", "ɔː", "ʊ", "uː", "ʌ", "ɑː", "ɜː", "ə",
                    "eɪ", "aɪ", "ɔɪ", "əʊ", "aʊ", "ɪə", "eə", "ʊə"):
            self.assertEqual(ipa, textbook_ipa.to_textbook(ipa), ipa)

    def test_stress_and_length_survive(self):
        self.assertEqual("ˌɪntəˈnæʃənəl", textbook_ipa.to_textbook("ˌɪntəˈnæʃənəl"))
        self.assertEqual("θˈɜːti", textbook_ipa.to_textbook("θˈɜːti"))

    def test_rewrite_leaves_no_espeak_only_symbol(self):
        sample = "ðə kˈɒt ɪz nˈɒt bɪsˈaɪd ðə lˈɒŋ ʃˈɛlvz"
        textbook_ipa.assert_textbook(textbook_ipa.to_textbook(sample))

    def test_rewrite_is_idempotent(self):
        for ipa in ("nˈiə", "mˈan", "ɐbˈaʊt", "ɹˈɛd", "wˈɔːtə"):
            once = textbook_ipa.to_textbook(ipa)
            self.assertEqual(once, textbook_ipa.to_textbook(once), ipa)


class GoldenTest(unittest.TestCase):
    """The owner's hard cases, pinned so the notation cannot drift."""

    CASES = {
        "nˈiə": "nˈɪə",           # near
        "θˈɜːti": "θˈɜːti",       # thirty
        "wˈɔːtə": "wˈɔːtə",       # water
        "ɐpˈɑːθeɪd": "əpˈɑːθeɪd",  # apartheid
        "kɹˈuːtsfɛlt": "krˈuːtsfelt",  # creutzfeldt
        "tˈaʊ": "tˈaʊ",           # tao
    }

    def test_owner_cases(self):
        for espeak, textbook in self.CASES.items():
            self.assertEqual(textbook, textbook_ipa.to_textbook(espeak), espeak)


class GeneratedDataTest(unittest.TestCase):
    """Guards the committed data file, when it is present."""

    def test_display_tsv_is_textbook_and_matches_the_reference(self):
        here = os.path.dirname(os.path.abspath(__file__))
        reference = os.path.join(here, "data", "en_gb_ipa.tsv")
        display = os.path.join(here, "data", "en_gb_display.tsv")
        if not os.path.isfile(reference) or not os.path.isfile(display):
            self.skipTest("generated data not present")

        expected = {
            key: textbook_ipa.to_textbook(new_ipa)
            for key, _kind, _old, new_ipa in textbook_ipa.read_reference(reference)
        }
        rows = 0
        with open(display, encoding="utf-8") as fh:
            for i, line in enumerate(fh):
                if i == 0:
                    continue
                key, _kind, _internal, shown = line.rstrip("\n").split("\t")
                textbook_ipa.assert_textbook(shown)
                self.assertEqual(expected[key], shown, key)
                rows += 1
        self.assertEqual(len(expected), rows)


class ApplyBanksTest(unittest.TestCase):
    """`--banks` must be idempotent: re-running never duplicates the field."""

    def _records(self):
        here = os.path.dirname(os.path.abspath(__file__))
        reference = os.path.join(here, "data", "en_gb_ipa.tsv")
        if not os.path.isfile(reference):
            self.skipTest("generated data not present")
        return textbook_ipa.display_records(textbook_ipa.read_reference(reference))

    def test_second_run_is_a_no_op(self):
        records = self._records()
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "drills"))
            path = os.path.join(tmp, "drills", "a.json")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(
                    '{"version":1,"language":"en","drills":['
                    '{"id":"en.aa.ae.health.001","target":"ɑ","text":"a","ipa":"x"},'
                    '{"id":"en.aa.ae.health.002","target":"ɑ","text":"b","ipa":"y"}]}'
                )
            textbook_ipa.apply_to_banks(tmp, records)
            first = open(path, encoding="utf-8").read()
            self.assertEqual(2, first.count("displayIpa"))
            report = textbook_ipa.apply_to_banks(tmp, records)
            second = open(path, encoding="utf-8").read()
            self.assertEqual(first, second, "re-running must not change the file")
            self.assertEqual(2, second.count("displayIpa"))
            self.assertEqual((0, 0), report["drills"])

    def test_rows_already_carrying_the_field_are_updated_not_duplicated(self):
        records = self._records()
        expected = {key: display for key, _kind, _internal, display in records}
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "words"))
            path = os.path.join(tmp, "words", "a.json")
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(
                    '{"version":1,"language":"en","words":['
                    '{"id":"en.word.airport.001","lemma":"gate","ipa":"ɡˈeɪt",'
                    '"displayIpa":"stale"}]}'
                )
            textbook_ipa.apply_to_banks(tmp, records)
            text = open(path, encoding="utf-8").read()
            self.assertEqual(1, text.count("displayIpa"))
            bank = json.loads(text)
            self.assertEqual(
                expected["en.word.airport.001"], bank["words"][0]["displayIpa"],
            )


if __name__ == "__main__":
    unittest.main()
