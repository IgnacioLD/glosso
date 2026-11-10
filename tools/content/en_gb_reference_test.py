"""Unit tests for the en-GB reference tooling that need no espeak-ng.

Covers the two pieces the measurement rests on: the coda-rhotic detector and the
ARPAbet -> IPA mirror of `engine/.../g2p/Arpabet.kt`. Run with plain Python 3:

  python3 tools/content/en_gb_reference_test.py
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import cmudict_reverse
import en_gb_reference


class CodaRhoticTest(unittest.TestCase):
    def test_codas(self):
        for chunk in ("nˈɪɹ", "kˈɑɹ", "bˈɝd", "wˈɔtɚ", "hˈɑɹts"):
            self.assertTrue(en_gb_reference.has_coda_rhotic(chunk), chunk)

    def test_onsets_are_not_codas(self):
        # The r opens the next syllable, so non-rhotic English keeps it.
        for chunk in ("stˈɔɹi", "sˈɑɹi", "pˈɪɹiəd", "θɹˈiː", "ɹˈɛd"):
            self.assertFalse(en_gb_reference.has_coda_rhotic(chunk), chunk)

    def test_non_rhotic_readings_have_no_coda(self):
        for chunk in ("nˈiə", "kˈɑː", "wˈɔːtə", "hˈɑːts", "stˈɔːɹi"):
            self.assertFalse(en_gb_reference.has_coda_rhotic(chunk), chunk)


class ArpabetMirrorTest(unittest.TestCase):
    def test_stress_marks(self):
        self.assertEqual("ˈɪ", cmudict_reverse.phoneme("IH1"))
        self.assertEqual("ˌɪ", cmudict_reverse.phoneme("IH2"))
        self.assertEqual("ɪ", cmudict_reverse.phoneme("IH0"))

    def test_unstressed_reductions(self):
        self.assertEqual("ə", cmudict_reverse.phoneme("AH0"))
        self.assertEqual("ɚ", cmudict_reverse.phoneme("ER0"))

    def test_word_matches_the_kotlin_table(self):
        # Values taken from the published content.db (CMUdict primary).
        self.assertEqual("nˈɪɹ", cmudict_reverse.word("N IH1 R"))
        self.assertEqual("ðə", cmudict_reverse.word("DH AH0"))
        self.assertEqual("ɡˈeɪt", cmudict_reverse.word("G EY1 T"))
        self.assertEqual("θˈɝdˌi", cmudict_reverse.word("TH ER1 D IY2"))


if __name__ == "__main__":
    unittest.main()
