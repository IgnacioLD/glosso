"""Reference-IPA contract of phonemize.py, without espeak-ng. Run:  python3 phonemize_test.py

- Russian espeak-ng spellings are rewritten to the form the scorer compares, the
  same folds as RU_SYMBOL_FOLDS in the engine's PhoneticComparator.
- Other languages are left exactly as espeak-ng wrote them.
- Loanword language flags are dropped for every language except English, whose
  output must stay byte-identical.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import phonemize


class FakeBackend:
    """Stands in for phonemizer.phonemize; records how it was called."""

    def __init__(self, output):
        self.output = output
        self.kwargs = None

    def __call__(self, words, **kwargs):
        self.kwargs = kwargs
        # A sentence the test did not spell out reads as its words one after another.
        return [self.output[w] if w in self.output else " ".join(self.output[x] for x in w.split()) for w in words]


class RussianStressTest(unittest.TestCase):
    def test_the_authored_stress_mark_never_reaches_espeak_or_the_lexicon(self):
        backend = FakeBackend({"город": "ɡˈorʌt", "в": "vˈɛ", "центре": "tsˈɛntrʲi",
                               "город в центре": "ɡˈorʌt f tsˈɛntrʲi"})
        ipa, lexicon = phonemize.phone_sentence("Го\u0301род в це\u0301нтре.", backend, "ru", "ru")
        # The sentence is read whole (the preposition devoices before Ц); the
        # lexicon keeps each word as it reads alone.
        self.assertEqual(ipa, "ɡˈorʌt f tsˈɛntrʲi")
        self.assertEqual(lexicon, {"город": "ɡˈorʌt", "в": "v", "центре": "tsˈɛntrʲi"})

    def test_a_sentence_espeak_does_not_split_word_by_word_falls_back_to_single_words(self):
        backend = FakeBackend({"кот": "kˈot", "там": "tˈɑm", "кот там": "kˈottˈɑm"})
        self.assertEqual(phonemize.phone_sentence("Кот там.", backend, "ru", "ru")[0], "kˈot tˈɑm")

    def test_a_spaced_dash_is_not_a_word(self):
        backend = FakeBackend({"квас": "kvˈɑs", "это": "ˈɛtʌ", "квас это": "kvˈɑs ˈɛtʌ"})
        self.assertEqual(phonemize.phone_sentence("Квас - э\u0301то.", backend, "ru", "ru")[0], "kvˈɑs ˈɛtʌ")

    def test_an_override_corrects_only_the_reading_the_author_marked(self):
        backend = FakeBackend({"дела": "dʲˈeɭa"})
        overrides = {"дела\u0301": "dʲiɭˈɑ"}
        self.assertEqual(phonemize.phone_sentence("дела\u0301", backend, "ru", "ru", overrides)[0], "dʲiɭˈɑ")
        self.assertEqual(phonemize.phone_sentence("де\u0301ла", backend, "ru", "ru", overrides)[0], "dʲˈeɭa")

    def test_a_stress_on_another_syllable_than_authored_is_reported(self):
        self.assertTrue(phonemize.stress_mismatch("моя\u0301", "mˈoja", "ru"))
        self.assertFalse(phonemize.stress_mismatch("моя\u0301", "mʌjˈɑ", "ru"))
        self.assertFalse(phonemize.stress_mismatch("го\u0301род", "ɡˈorʌt", "ru"))
        self.assertFalse(phonemize.stress_mismatch("кот", "kˈot", "ru"))


class CanonicalIpaTest(unittest.TestCase):
    def test_russian_folds(self):
        self.assertEqual(phonemize.canonical_ipa("nˈotʃʲ", "ru"), "nˈotʃ")
        self.assertEqual(phonemize.canonical_ipa("dvʲˈerɪ^", "ru"), "dvʲˈerʲ")
        self.assertEqual(phonemize.canonical_ipa('ɭʲu"bʲˈitʲ', "ru"), "ɭʲubʲˈitʲ")

    def test_soft_consonants_and_reduction_are_kept(self):
        self.assertEqual(phonemize.canonical_ipa("mʌɭʌkˈo", "ru"), "mʌɭʌkˈo")
        self.assertEqual(phonemize.canonical_ipa("mˈɑtʲ", "ru"), "mˈɑtʲ")

    def test_other_languages_are_untouched(self):
        for language in ("en", "fr", "de", None):
            self.assertEqual(phonemize.canonical_ipa("fˈʏnf tʃʲ", language), "fˈʏnf tʃʲ")


class PhoneSentenceTest(unittest.TestCase):
    def test_russian_sentence_is_folded_word_by_word(self):
        backend = FakeBackend({"ночь": "nˈotʃʲ ", "дверь": "dvʲˈerɪ^ "})
        ipa, lexicon = phonemize.phone_sentence("Ночь, дверь.", backend, "ru", "ru")
        self.assertEqual(ipa, "nˈotʃ dvʲˈerʲ")
        self.assertEqual(lexicon, {"ночь": "nˈotʃ", "дверь": "dvʲˈerʲ"})
        self.assertEqual(backend.kwargs["language_switch"], "remove-flags")

    def test_german_drops_loanword_flags(self):
        backend = FakeBackend({"baby": "bˈeɪbi "})
        phonemize.phone_sentence("Baby", backend, "de", "de")
        self.assertEqual(backend.kwargs["language_switch"], "remove-flags")

    def test_english_callers_keep_the_phonemizer_default(self):
        for language in ("en", None):
            backend = FakeBackend({"cat": "kˈat "})
            args = ("cat", backend, "en-gb") + ((language,) if language else ())
            phonemize.phone_sentence(*args)
            self.assertEqual(backend.kwargs["language_switch"], "keep-flags")


if __name__ == "__main__":
    unittest.main()
