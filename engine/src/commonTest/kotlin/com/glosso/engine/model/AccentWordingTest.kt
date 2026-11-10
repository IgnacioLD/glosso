package com.glosso.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.serialization.json.Json

class AccentWordingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val drill = DrillModel(
        id = "en.l.r.city.001",
        target = "l",
        focus = listOf("load", "road"),
        text = "The load on the lorry is light.",
        ipa = "ðə lˈoʊd ˈɑn ðə lˈɔɹi ˈɪz lˈaɪt",
        accentIpa = mapOf("en-GB" to "ðə lˈəʊd ˈɒn ðə lˈɒɹi ɪz lˈaɪt"),
        audio = mapOf("en-GB-female" to "audio/a.ogg", "en-US-female" to "audio/b.ogg"),
        buildup = listOf("is light", "the lorry is light"),
        wording = mapOf(
            "en-US" to AccentWording(
                text = "The load on the truck is light.",
                ipa = "ðə lˈoʊd ˈɑn ðə tɹˈʌk ˈɪz lˈaɪt",
                accentIpa = mapOf("en-GB" to "ðə lˈəʊd ˈɒn ðə tɹˈʌk ɪz lˈaɪt"),
            ),
        ),
    )

    @Test
    fun `an accent with its own wording replaces the text and every reference of it`() {
        val american = drill.forAccent("en-US")

        assertEquals("The load on the truck is light.", american.text)
        assertEquals("ðə lˈoʊd ˈɑn ðə tɹˈʌk ˈɪz lˈaɪt", american.ipa)
        assertEquals(mapOf("en-GB" to "ðə lˈəʊd ˈɒn ðə tɹˈʌk ɪz lˈaɪt"), american.accentIpa)
        // The clips are keyed by voice, and the chunks spelled the other wording.
        assertEquals(drill.audio, american.audio)
        assertEquals(emptyList(), american.buildup)
    }

    @Test
    fun `changing the accent back restores the authored wording`() {
        val american = drill.forAccent("en-US")
        val british = american.forAccent("en-GB")

        assertEquals(drill.text, british.text)
        assertEquals(drill.ipa, british.ipa)
        assertEquals(drill.accentIpa, british.accentIpa)
        // Applying the same accent twice changes nothing.
        assertSame(american, american.forAccent("en-US"))
        assertEquals("The load on the truck is light.", british.forAccent("en-US").text)
    }

    @Test
    fun `the textbook transcription is only shown under the accent the bank was written in`() {
        val plain = DrillModel(
            id = "en.th.f.city.001",
            target = "θ",
            text = "I think so.",
            ipa = "ˈaɪ θˈɪŋk sˈoʊ",
            displayIpa = "aɪ θˈɪŋk sˈəʊ",
            buildup = listOf("so", "think so"),
        )

        val american = plain.forAccent("en-US", authored = "en-GB")
        assertEquals("", american.displayIpa)
        assertEquals("ˈaɪ θˈɪŋk sˈoʊ", american.displayPhonetic)
        assertEquals(plain.text, american.text)
        assertEquals(plain.buildup, american.buildup)

        assertSame(plain, plain.forAccent("en-GB", authored = "en-GB"))
        assertEquals("aɪ θˈɪŋk sˈəʊ", american.forAccent("en-GB", authored = "en-GB").displayIpa)
        assertSame(american, american.forAccent("en-US", authored = "en-GB"))
    }

    @Test
    fun `an accent without wording reads the authored item`() {
        assertSame(drill, drill.forAccent("en-GB"))
        val blank = drill.copy(wording = mapOf("en-US" to AccentWording()))
        assertSame(blank, blank.forAccent("en-US"))
    }

    @Test
    fun `a dialogue rewords only the lines that have the accent`() {
        val dialogue = DialogueModel(
            id = "en.dialogue.home.001",
            title = "A lamp",
            turns = listOf(
                DialogueTurn(speaker = "A", text = "Can I help you?"),
                DialogueTurn(
                    speaker = "B",
                    text = "Yes, please. A lamp for my flat.",
                    wording = mapOf("en-US" to AccentWording(text = "Yes, please. A lamp for my apartment.")),
                ),
            ),
        )

        val american = dialogue.forAccent("en-US")

        assertEquals(listOf("Can I help you?", "Yes, please. A lamp for my apartment."), american.turns.map { it.text })
        assertSame(dialogue, dialogue.forAccent("en-GB"))
    }

    @Test
    fun `a story keeps its paragraphs when the accent list does not line up with the audio`() {
        val story = StoryModel(
            id = "en.story.city.003",
            title = "New in town",
            paragraphs = listOf("Her flat is small.", "She has no friends here."),
            accentParagraphs = mapOf("en-US" to listOf("Her apartment is small.", "She has no friends here.")),
        )

        val american = story.forAccent("en-US")
        assertEquals("Her apartment is small.", american.paragraphs.first())
        assertEquals(story.paragraphs, american.forAccent("en-GB").paragraphs)
        val broken = story.copy(accentParagraphs = mapOf("en-US" to listOf("Her apartment is small.")))
        assertSame(broken, broken.forAccent("en-US"))
    }

    @Test
    fun `an exercise is reworded whole so it stays solvable and goes back to the authored text`() {
        val exercise = ExerciseModel(
            id = "en.exercise.movies.001",
            type = "multiple-choice",
            prompt = "Where do they watch the film?",
            options = listOf("at the cinema", "at home", "at school"),
            answer = "at the cinema",
            accentText = mapOf(
                "en-US" to mapOf(
                    "prompt" to "Where do they watch the movie?",
                    "opt0" to "at the movie theater",
                    "answer" to "at the movie theater",
                    "opt9" to "no such option",
                ),
            ),
        )

        val american = exercise.forAccent("en-US")

        assertEquals("Where do they watch the movie?", american.prompt)
        assertEquals(listOf("at the movie theater", "at home", "at school"), american.options)
        assertEquals("at the movie theater", american.answer)
        assertEquals(null, american.instruction)
        assertSame(american, american.forAccent("en-US"))
        val back = american.forAccent("en-GB")
        assertEquals(exercise.prompt, back.prompt)
        assertEquals(exercise.options, back.options)
        assertEquals(exercise.answer, back.answer)
        assertSame(exercise, exercise.forAccent("en-GB"))
    }

    @Test
    fun `a dialogue renames its characters in the turns and in the cast together`() {
        val dialogue = DialogueModel(
            id = "en.dialogue.family.001",
            title = "A phone call with Mum",
            turns = listOf(DialogueTurn("Mum", "Hello, Tom."), DialogueTurn("Tom", "Hi!"), DialogueTurn("Mum", "How are you?")),
            cast = listOf(DialogueSpeaker("Mum", VoiceGender.FEMALE), DialogueSpeaker("Tom")),
            accentText = mapOf(
                "en-US" to mapOf("title" to "A phone call with Mom", "speaker0" to "Mom", "speaker2" to "Mom", "cast0" to "Mom"),
            ),
        )

        val american = dialogue.forAccent("en-US")

        assertEquals("A phone call with Mom", american.title)
        assertEquals(listOf("Mom", "Tom", "Mom"), american.turns.map { it.speaker })
        assertEquals(listOf("Mom", "Tom"), american.cast.map { it.name })
        assertEquals(VoiceGender.FEMALE, american.cast.first().gender)
        val back = american.forAccent("en-GB")
        assertEquals(dialogue.title, back.title)
        assertEquals(dialogue.turns, back.turns)
        assertEquals(dialogue.cast, back.cast)
    }

    @Test
    fun `titles - objectives and word examples follow the accent`() {
        val lesson = LessonModel(
            id = "en.lesson.movies.001",
            title = "At the Cinema",
            objective = "Ask about a film.",
            accentText = mapOf("en-US" to mapOf("title" to "At the Movie Theater", "objective" to "Ask about a movie.")),
        )
        val word = WordModel(
            id = "en.word.colors.001",
            lemma = "red",
            examples = listOf("Red is my favourite colour.", "The door is red."),
            accentText = mapOf("en-US" to mapOf("ex0" to "Red is my favorite color.")),
        )
        val story = StoryModel(
            id = "en.story.home.002",
            title = "A New Flat",
            paragraphs = listOf("The flat is small."),
            accentParagraphs = mapOf("en-US" to listOf("The apartment is small.")),
            accentText = mapOf("en-US" to mapOf("title" to "A New Apartment")),
        )

        assertEquals("At the Movie Theater", lesson.forAccent("en-US").title)
        assertEquals("Ask about a movie.", lesson.forAccent("en-US").objective)
        assertEquals(lesson.title, lesson.forAccent("en-US").forAccent("en-GB").title)
        assertEquals(listOf("Red is my favorite color.", "The door is red."), word.forAccent("en-US").examples)
        assertEquals(word.examples, word.forAccent("en-US").forAccent("en-GB").examples)
        val american = story.forAccent("en-US")
        assertEquals("A New Apartment", american.title)
        assertEquals(listOf("The apartment is small."), american.paragraphs)
        assertEquals(story.title, american.forAccent("en-GB").title)
        assertEquals(story.paragraphs, american.forAccent("en-GB").paragraphs)
    }

    @Test
    fun `content published before accents existed still decodes`() {
        val old = """{"id":"en.x.001","target":"l","text":"The load is light.","ipa":"x"}"""

        val decoded = json.decodeFromString(DrillModel.serializer(), old)

        assertEquals(emptyMap(), decoded.accentIpa)
        assertEquals(emptyMap(), decoded.wording)
    }

    @Test
    fun `a reworded drill gets its build-up back with the authored wording`() {
        val american = drill.forAccent("en-US")
        assertEquals(emptyList(), american.buildup)

        assertEquals(drill.buildup, american.forAccent("en-GB").buildup)
    }
}
