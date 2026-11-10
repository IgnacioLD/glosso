package com.glosso.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glosso.app.api.ApiCache
import com.glosso.app.api.ScoreRequest
import com.glosso.engine.g2p.CmuDictG2P
import com.glosso.engine.g2p.widenReferenceVariants
import com.glosso.engine.model.MatchStatusModel
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device (emulator/phone) run of the offline scoring path with the published
 * CMUdict variants: a spoken British "three thirty" is recognized by the real
 * selective int8 model through [OnDeviceScorer] and scored against the widened reference, so
 * the device accepts the British /t/ exactly like the cloud does instead of
 * charging a false American /d/.
 *
 * The cloud/offline equality itself is pinned by `CloudOfflineParityTest` and
 * `ScoreRouteTest` (the device and the server share the engine and the reference
 * set); this test proves the device path runs with the real model and produces a
 * clean verdict.
 *
 * Assets are staged into `/data/local/tmp/glosso-parity` (model, vocab, cmudict,
 * thirty.wav). It skips when they are absent, so a plain
 * `connectedAndroidTest` still works.
 */
@RunWith(AndroidJUnit4::class)
class OfflineReferenceParityTest {

    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val STAGING_DIR = "/data/local/tmp/glosso-parity"
        const val TEXT = "The flight leaves at three thirty."
        const val EXPECTED = "ðə flˈaɪt lˈivz ˈæt θɹˈi θˈɝdˌi"
    }

    @Before
    fun initScorer() {
        ApiCache.init(target)
        OnDeviceScorer.init(target)
    }

    @Test
    fun offlineAcceptsTheBritishThirtyWithNoFalseFlap() = runBlocking {
        val staging = File(STAGING_DIR)
        val model = File(staging, "wav2vec2_phoneme_int8_dyn_pertensor.onnx")
        val vocab = File(staging, "phoneme_vocab.json")
        val cmudict = File(staging, "cmudict.dict")
        val wav = File(staging, "thirty.wav")
        if (!model.isFile || !vocab.isFile || !cmudict.isFile || !wav.isFile) {
            println("OfflineReferenceParityTest skipped: stage the assets in $STAGING_DIR")
            return@runBlocking
        }

        if (!OnDeviceScorer.available()) {
            val dest = File(target.filesDir, "scoring").apply { mkdirs() }
            model.copyTo(File(dest, model.name), overwrite = true, bufferSize = 1 shl 20)
            vocab.copyTo(File(dest, vocab.name), overwrite = true, bufferSize = 1 shl 20)
        }
        assertTrue("the scoring pack must be available", OnDeviceScorer.available())
        assertTrue("the model must load", OnDeviceScorer.ensureReady())

        val g2p = CmuDictG2P.fromLines(cmudict.readLines())
        val published = widenReferenceVariants(EXPECTED, g2p.pronunciations(TEXT).wordVariants)
        val audio = android.util.Base64.encodeToString(wav.readBytes(), android.util.Base64.NO_WRAP)
        val request = ScoreRequest(
            text = TEXT,
            expectedIpa = EXPECTED,
            audioBase64 = audio,
            language = "en_GB",
            uiLanguage = "en",
        )

        val withVariants = OnDeviceScorer.score(request, published)
        val without = OnDeviceScorer.score(request)

        println(
            "OFFLINE_PARITY|withVariants=${withVariants.score}|without=${without.score}" +
                "|published=${published.size}",
        )
        assertTrue("the attempt must score", withVariants.evaluated)
        // The detector for the bug: with the published readings the device must not
        // line the british /t/ up against an american /d/. (`>=` would pass even if
        // the fix were a no-op, so the alignment is the real check.)
        assertTrue(
            "offline must not charge the british /t/ as a missing american /d/",
            withVariants.alignment.none {
                it.expected == "d" && it.actual == "t" && it.status == MatchStatusModel.MISSED
            },
        )
    }
}
