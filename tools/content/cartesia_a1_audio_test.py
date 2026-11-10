"""Narrator-voice tests for the Cartesia render script. Run:  python3 cartesia_a1_audio_test.py

- `--narrator one` keeps a single voice per drill, word or story: the item's own
  `narrator`, else a gender already rendered and current, else one picked from
  the id. Dialogues keep every speaker's voice.
- `--retire` moves the clips of planned items that the plan no longer reads
  (changed text, dropped voice, slow variants) and never touches other items.
"""
import os
import tempfile
import unittest

import cartesia_a1_audio as c


def clip(name, kind="drills", item="ru.x.001", gender="female", narrator=""):
    return c.Clip(name=name, text="Привет", voice_name="v", voice_id="id", gender=gender, speed=1.0,
                  kind=kind, item_id=item, level="A1", chars=6, narrator=narrator)


class OneNarratorTest(unittest.TestCase):

    def test_the_authored_narrator_wins(self):
        plan = [clip("a.f", gender="female", narrator="male"), clip("a.m", gender="male", narrator="male")]
        kept = c.one_narrator(plan, is_cached=lambda _: False)
        self.assertEqual(["a.m"], [k.name for k in kept])

    def test_an_already_rendered_voice_is_reused(self):
        plan = [clip("a.f", gender="female"), clip("a.m", gender="male")]
        kept = c.one_narrator(plan, is_cached=lambda k: k.gender == "male")
        self.assertEqual(["a.m"], [k.name for k in kept])

    def test_otherwise_one_voice_is_picked_and_it_is_stable(self):
        plan = [clip("a.f", gender="female"), clip("a.m", gender="male")]
        first = c.one_narrator(plan, is_cached=lambda _: False)
        self.assertEqual(1, len(first))
        self.assertEqual(first, c.one_narrator(plan, is_cached=lambda _: False))

    def test_paragraphs_of_a_story_share_the_voice(self):
        plan = [clip(f"s.p{i}.{g}", kind="stories", item="ru.story.x.001", gender=g)
                for i in range(3) for g in ("female", "male")]
        kept = c.one_narrator(plan, is_cached=lambda _: False)
        self.assertEqual(3, len(kept))
        self.assertEqual(1, len({k.gender for k in kept}))

    def test_dialogues_keep_every_speaker(self):
        plan = [clip("d.t0", kind="dialogues", item="ru.dialogue.x.001", gender="female"),
                clip("d.t1", kind="dialogues", item="ru.dialogue.x.001", gender="male")]
        self.assertEqual(plan, c.one_narrator(plan, is_cached=lambda _: False))


class RetireTest(unittest.TestCase):

    def test_moves_only_unplanned_clips_of_planned_items(self):
        with tempfile.TemporaryDirectory() as tmp:
            audio = os.path.join(tmp, "audio")
            retired = os.path.join(tmp, "retired")
            os.makedirs(audio)
            for name in ("ru.x.001.ru-RU-female", "ru.x.001.ru-RU-male", "ru.x.001.ru-RU-female.slow",
                         "ru.y.001.ru-RU-male"):
                open(os.path.join(audio, name + ".ogg"), "w").close()
            plan = [clip("ru.x.001.ru-RU-female")]

            self.assertEqual(2, c.retire_unplanned(plan, audio, retired, dry_run=True))
            self.assertFalse(os.path.exists(retired))

            self.assertEqual(2, c.retire_unplanned(plan, audio, retired, dry_run=False))
            self.assertEqual(["ru.x.001.ru-RU-female.ogg", "ru.y.001.ru-RU-male.ogg"], sorted(os.listdir(audio)))
            self.assertEqual(["ru.x.001.ru-RU-female.slow.ogg", "ru.x.001.ru-RU-male.ogg"],
                             sorted(os.listdir(retired)))


if __name__ == "__main__":
    unittest.main()
