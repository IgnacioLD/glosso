package com.glosso.engine.phonetics

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Wav2Vec2PhonemeRecognizerTest {

    @Test
    fun `missing model assets leave the recognizer uninitialized`() {
        val recognizer = Wav2Vec2PhonemeRecognizer(
            modelFile = File("does-not-exist.onnx"),
            vocabFile = File("does-not-exist.json"),
        )

        recognizer.initialize()

        assertEquals(ModelState.UNINITIALIZED, recognizer.state)
        assertNull(recognizer.error)
        assertNull(recognizer.recognize("AAAA"))
    }

    @Test
    fun `fromPack loads a second model from its own pack directory`() {
        val dir = Files.createTempDirectory("pack").toFile()
        val model = File(dir, "wav2vec2_phoneme_fp16_ft.onnx")
        model.writeText("onnx")
        File(dir, "phoneme_vocab.json").writeText("""{"<pad>":0,"a":1}""")

        val recognizer = Wav2Vec2PhonemeRecognizer.fromPack(dir, "wav2vec2_phoneme_fp16_ft.onnx")

        assertNotNull(recognizer)
        assertEquals(ModelState.UNINITIALIZED, recognizer.state)
    }

    @Test
    fun `fromPack reads the vocabulary from the processor directory`() {
        val dir = Files.createTempDirectory("pack").toFile()
        File(dir, "wav2vec2_phoneme_fp16_ft.onnx").writeText("onnx")
        val processor = File(dir, "processor").apply { mkdirs() }
        File(processor, "vocab.json").writeText("""{"<pad>":0,"a":1}""")

        assertNotNull(Wav2Vec2PhonemeRecognizer.fromPack(dir, "wav2vec2_phoneme_fp16_ft.onnx"))
    }

    @Test
    fun `fromPack returns null when the model or vocabulary is missing`() {
        val noModel = Files.createTempDirectory("pack").toFile()
        File(noModel, "phoneme_vocab.json").writeText("{}")
        assertNull(Wav2Vec2PhonemeRecognizer.fromPack(noModel, "wav2vec2_phoneme_fp16_ft.onnx"))

        val noVocab = Files.createTempDirectory("pack").toFile()
        File(noVocab, "wav2vec2_phoneme_fp16_ft.onnx").writeText("onnx")
        assertNull(Wav2Vec2PhonemeRecognizer.fromPack(noVocab, "wav2vec2_phoneme_fp16_ft.onnx"))
    }
}
