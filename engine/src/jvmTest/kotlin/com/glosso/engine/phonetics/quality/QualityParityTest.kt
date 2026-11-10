package com.glosso.engine.phonetics.quality

import com.glosso.engine.phonetics.AcousticFrames
import com.glosso.engine.phonetics.ForcedAligner
import com.glosso.engine.phonetics.PhoneSpan
import com.glosso.engine.phonetics.PhoneticComparator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the Kotlin quality decision layer to the Python reference in
 * `benchmark/phon-multilingual` (PR #52): per-phone features from the frozen
 * recogniser posteriors, within-utterance normalisation, the frozen logistic
 * head and the mean-of-worst-3 aggregation.
 *
 * `quality/golden.json` was produced by `gen_quality_fixtures.py` over a
 * deterministic probability matrix, the real 392-token vocabulary and a
 * reference that contains the five diphthongs the model emits as one token. The
 * reference is **not** read from the golden for the feature checks: the test
 * rebuilds it from `refIpa` through the same production path the recognizer
 * uses (`PhoneticComparator.modelPhones` -> `ModelInventory.resolveAll` ->
 * `ForcedAligner.align`). If the port splits a diphthong (or otherwise
 * tokenises differently than the benchmark), the rebuilt list stops matching
 * the golden and the test fails.
 *
 * Tolerances are explicit: the features are reimplemented from first principles
 * over float32 probabilities, so the log/mean sums differ from the float64 numpy
 * reference by a little; the head coefficients are exact and are checked tightly.
 */
class QualityParityTest {

    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(readResource("quality/golden.json").decodeToString()).jsonObject
    }

    private fun readResource(path: String): ByteArray =
        QualityParityTest::class.java.getResourceAsStream("/$path")?.readAllBytes()
            ?: fail("missing test resource: $path")

    private class FakeFrames(
        override val timeSteps: Int,
        override val blankId: Int,
        override val vocabSize: Int,
        private val probabilities: FloatArray,
        private val idToToken: Map<Int, String>,
    ) : AcousticFrames {
        override fun tokenIndex(token: String): Int =
            idToToken.entries.firstOrNull { it.value == token }?.key ?: -1

        override fun probability(frame: Int, tokenId: Int): Float =
            probabilities[frame * vocabSize + tokenId]

        override fun tokenOf(id: Int): String? = idToToken[id]
    }

    private fun framesFromGolden(): FakeFrames {
        val vocab = golden.getValue("vocab").jsonObject
        val idToToken = HashMap<Int, String>(vocab.size)
        for ((token, id) in vocab) idToToken[id.jsonPrimitive.int] = token

        val shape = golden.getValue("probsShape").jsonArray
        val t = shape[0].jsonPrimitive.int
        val v = shape[1].jsonPrimitive.int
        val bytes = Base64.getDecoder().decode(golden.getValue("probsF32Base64").jsonPrimitive.content)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val probs = FloatArray(t * v) { buffer.get() }
        val blankId = golden.getValue("blankId").jsonPrimitive.int
        return FakeFrames(t, blankId, v, probs, idToToken)
    }

    /** token -> id, the map `ModelInventory.resolveAll` consumes. */
    private fun tokenIdsFromGolden(): Map<String, Int> =
        golden.getValue("vocab").jsonObject.entries.associate { (token, id) ->
            token to id.jsonPrimitive.int
        }

    /** The reference the production path builds from the raw IPA. */
    private fun productionReference(): List<String> {
        val refIpa = golden.getValue("refIpa").jsonPrimitive.content
        return PhoneticComparator.modelPhones(refIpa)
    }

    /** The aligned spans the production path computes for the quality head. */
    private fun productionSpans(): List<PhoneSpan> {
        val tokens = assertNotNull(
            ModelInventory.resolveAll(productionReference(), tokenIdsFromGolden()),
            "production reference must resolve against the vocabulary",
        )
        return assertNotNull(
            ForcedAligner.align(tokens, framesFromGolden()),
            "production forced alignment must succeed",
        )
    }

    private fun expectedSpans(): List<PhoneSpan> {
        val resolved = golden.getValue("resolved").jsonArray.map { it.jsonPrimitive.content }
        val refIds = golden.getValue("ref_ids").jsonArray.map { it.jsonPrimitive.int }
        val spans = golden.getValue("spans").jsonArray.map { it.jsonArray }
        return spans.mapIndexed { i, s ->
            PhoneSpan(
                phone = resolved[i],
                tokenId = refIds[i],
                startFrame = s[0].jsonPrimitive.int,
                endFrame = s[1].jsonPrimitive.int,
                posterior = 0f,
            )
        }
    }

    @Test
    fun `production reference construction reproduces the benchmark tokenisation`() {
        val canonical = productionReference()
        val expectedReference = golden.getValue("reference").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expectedReference, canonical, "modelPhones tokenisation")

        val resolved = assertNotNull(ModelInventory.resolveAll(canonical, tokenIdsFromGolden()))
        val expectedResolved = golden.getValue("resolved").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(expectedResolved, resolved, "reference token resolution")

        val spans = productionSpans()
        val expected = expectedSpans()
        assertEquals(expected.size, spans.size, "span count")
        for (i in spans.indices) {
            assertEquals(expected[i].startFrame, spans[i].startFrame, "span[$i] start frame")
            assertEquals(expected[i].endFrame, spans[i].endFrame, "span[$i] end frame")
        }
    }

    @Test
    fun `per-phone features and worst-3 score match the python reference`() {
        val expected = golden.getValue("phones").jsonArray.map { it.jsonObject }
        val actual = assertNotNull(
            PronunciationQuality.analyze(productionReference(), framesFromGolden(), productionSpans()),
            "no quality model produced",
        )

        assertEquals(expected.size, actual.phones.size, "phone count")
        val failures = ArrayList<String>()
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual.phones[i]
            fun num(key: String): Double = e.getValue(key).jsonPrimitive.doubleOrNull!!
            fun check(key: String, got: Double, tol: Double) {
                val diff = abs(got - num(key))
                if (diff > tol) failures.add("phone[$i]/$key expected=${num(key)} actual=$got diff=$diff > $tol")
            }
            if (e.getValue("phone").jsonPrimitive.content != a.phone) {
                failures.add("phone[$i] name expected=${e.getValue("phone")} actual=${a.phone}")
            }
            if (e.getValue("produced").jsonPrimitive.content != a.produced) {
                failures.add("phone[$i] produced expected=${e.getValue("produced")} actual=${a.produced}")
            }
            if (e.getValue("phone_class").jsonPrimitive.content != a.phoneClass) {
                failures.add("phone[$i] class expected=${e.getValue("phone_class")} actual=${a.phoneClass}")
            }
            assertEquals(e.getValue("frames").jsonPrimitive.int, a.frames, "phone[$i] frames")
            check("gop", a.gop.toDouble(), 1e-4)
            check("gop_rank", a.gopRank.toDouble(), 1e-9)
            check("margin", a.margin.toDouble(), 1e-5)
            check("entropy", a.entropy.toDouble(), 1e-4)
            check("quality", a.quality.toDouble(), 1e-5)
        }

        if (failures.isNotEmpty()) {
            fail("parity failures (${failures.size}):\n" + failures.joinToString("\n"))
        }

        val expectedScore = golden.getValue("utteranceScore").jsonPrimitive.doubleOrNull!!
        val diff = abs(expectedScore - actual.score.toDouble())
        assertTrue(diff <= 1e-5, "utterance score expected=$expectedScore actual=${actual.score} diff=$diff")
    }

    @Test
    fun `raw per-phone features match the python reference`() {
        val expected = golden.getValue("phones").jsonArray.map { it.jsonObject }
        val raw = QualityFeatures.extract(framesFromGolden(), productionSpans())
        assertEquals(expected.size, raw.size, "raw phone count")
        val failures = ArrayList<String>()
        for (i in expected.indices) {
            val e = expected[i]
            val r = raw[i]
            fun num(key: String): Double = e.getValue(key).jsonPrimitive.doubleOrNull!!
            fun check(key: String, got: Double, tol: Double) {
                val diff = abs(got - num(key))
                if (diff > tol) failures.add("raw[$i]/$key expected=${num(key)} actual=$got diff=$diff > $tol")
            }
            assertEquals(e.getValue("frames").jsonPrimitive.int, r.frames, "raw[$i] frames")
            assertEquals(e.getValue("produced_id").jsonPrimitive.int, r.producedId, "raw[$i] produced_id")
            assertEquals(e.getValue("token_id").jsonPrimitive.int, r.tokenId, "raw[$i] token_id")
            check("gop", r.gop, 1e-4)
            check("gopt", r.gopt, 1e-4)
            check("gop_rank", r.gopRank, 1e-9)
            // The Python reference rounds posterior/maxpost to 5 decimals in align.py.
            check("posterior", r.posterior, 1e-5)
            check("maxpost", r.maxpost, 1e-5)
            check("margin", r.margin, 1e-5)
            check("entropy", r.entropy, 1e-4)
        }
        if (failures.isNotEmpty()) {
            fail("raw parity failures (${failures.size}):\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `the frozen head reproduces the python probabilities from the golden features`() {
        val expected = golden.getValue("phones").jsonArray.map { it.jsonObject }
        for ((i, e) in expected.withIndex()) {
            val cont = e.getValue("cont").jsonArray.map { it.jsonPrimitive.doubleOrNull!! }.toDoubleArray()
            val tokenId = e.getValue("token_id").jsonPrimitive.int
            val phoneClass = e.getValue("phone_class").jsonPrimitive.content
            val want = e.getValue("quality").jsonPrimitive.doubleOrNull!!
            val got = QualityHead.probability(cont, phoneClass, tokenId)
            assertTrue(abs(want - got) <= 1e-9, "phone[$i] head expected=$want actual=$got")
        }
        assertEquals("shared-inventory-logreg-worst3-v1", golden.getValue("headId").jsonPrimitive.content)
        assertEquals(QualityHead.ID, golden.getValue("headId").jsonPrimitive.content)
    }
}
