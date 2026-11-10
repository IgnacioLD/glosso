package com.glosso.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glosso.app.api.ApiCache
import com.glosso.app.api.GlossoApi
import com.glosso.app.api.ScoreRequest
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cloud-vs-on-device score parity, the measured version of the product claim
 * that "the score is the same in the cloud and on the device, because it is the
 * same engine".
 *
 * It scores the exact same committed WAVs through [OnDeviceScorer] (the real
 * offline path: the selective int8 ONNX model on the Android runtime) and compares the
 * result with a golden captured from the real server `/v1/score` route. See
 * `androidApp/src/androidTest/assets/score-parity/README.md` for how the fixtures
 * and golden are made, the measured tolerance and its justification, and how to
 * regenerate.
 *
 * The test is a gate: it fails if the model or vocab on disk does not match the
 * hashes the golden was captured with, and if the on-device result drifts past
 * the recorded tolerance - so the model, the vocab or the scoring logic cannot
 * change without a deliberate golden regeneration.
 *
 * Setup: `tools/score-parity/run-parity-test.sh` stages the model + vocab on the
 * device and the test copies them into the app's private scoring dir; if nothing
 * is staged it falls back to the app's own download when a `glossoAssetBase`
 * argument is passed. With neither, it skips (so a plain `connectedAndroidTest`
 * still works):
 *
 *   tools/score-parity/run-parity-test.sh
 */
@RunWith(AndroidJUnit4::class)
class ScoreParityTest {

    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private companion object {
        /** Where tools/score-parity/run-parity-test.sh stages the model to push-copy. */
        const val STAGING_DIR = "/data/local/tmp/glosso-parity"
    }

    private fun golden(): JSONObject =
        JSONObject(
            testAssets.open("score-parity/golden.json").bufferedReader().use { it.readText() },
        )

    private fun fixture(name: String): ByteArray =
        testAssets.open("score-parity/fixtures/$name").use { it.readBytes() }

    private fun packFile(dir: File, name: String) = File(dir, name)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Before
    fun initScorer() {
        ApiCache.init(target)
        OnDeviceScorer.init(target)
    }

    /**
     * Copies the model + vocab staged by the harness into the app's private
     * scoring dir. Fast and network-free, which keeps the gate reliable on
     * emulators and devices alike. Returns false when nothing is staged.
     */
    private fun provisionFromStaging(provenance: JSONObject): Boolean {
        val staging = File(STAGING_DIR)
        val model = File(staging, provenance.getString("modelFile"))
        val vocab = File(staging, provenance.getString("vocabFile"))
        if (!model.isFile || !vocab.isFile) return false
        println("Provisioning the scoring pack from $STAGING_DIR ...")
        val dest = File(target.filesDir, "scoring").apply { mkdirs() }
        model.copyTo(File(dest, model.name), overwrite = true, bufferSize = 1 shl 20)
        vocab.copyTo(File(dest, vocab.name), overwrite = true, bufferSize = 1 shl 20)
        return OnDeviceScorer.available()
    }

    @Test
    fun onDeviceMatchesCloudGolden() = runBlocking {
        val golden = golden()
        val provenance = golden.getJSONObject("provenance")
        val request = golden.getJSONObject("request")
        val scoreAbs = golden.getJSONObject("tolerance").getInt("scoreAbs")

        val packDir = File(target.filesDir, "scoring")
        val model = packFile(packDir, provenance.getString("modelFile"))
        val vocab = packFile(packDir, provenance.getString("vocabFile"))

        if (!OnDeviceScorer.available()) {
            if (!provisionFromStaging(provenance)) {
                val assetBase = InstrumentationRegistry.getArguments().getString("glossoAssetBase")
                if (assetBase.isNullOrBlank()) {
                    println(
                        "ScoreParityTest skipped: no model pack on the device, nothing staged in " +
                            "$STAGING_DIR and no glossoAssetBase argument. " +
                            "Run tools/score-parity/run-parity-test.sh",
                    )
                    return@runBlocking
                }
                println("Downloading the scoring pack from $assetBase via the app's own path...")
                OnDeviceScorer.download(GlossoApi(baseUrl = assetBase))
            }
        }
        assertTrue(
            "The scoring pack is not available after provisioning",
            OnDeviceScorer.available(),
        )

        // The golden is only meaningful against the exact same model + vocab.
        assertEquals(
            "Model on the device does not match the golden. Regenerate the golden " +
                "(tools/score-parity/regenerate-golden.py) after a deliberate model change.",
            provenance.getString("modelSha256"),
            sha256(model),
        )
        assertEquals(
            "Vocab on the device does not match the golden.",
            provenance.getString("vocabSha256"),
            sha256(vocab),
        )

        assertTrue("The scoring model failed to load", OnDeviceScorer.ensureReady())

        var evaluated = 0
        for (i in 0 until golden.getJSONArray("cases").length()) {
            val case = golden.getJSONArray("cases").getJSONObject(i)
            val name = case.getString("fixture")
            val server = case.getJSONObject("server")
            val evaluable = case.getBoolean("evaluable")
            val audio = android.util.Base64.encodeToString(fixture(name), android.util.Base64.NO_WRAP)
            val scoreRequest = ScoreRequest(
                text = request.getString("text"),
                expectedIpa = request.getString("expectedIpa"),
                audioBase64 = audio,
                language = request.getString("language"),
                uiLanguage = request.getString("uiLanguage"),
            )

            if (evaluable) {
                val result = OnDeviceScorer.score(scoreRequest)
                assertTrue("$name should be an evaluated attempt", result.evaluated)
                val delta = abs(result.score - server.getInt("score"))
                println(
                    "SCORE_PARITY|$name|onDevice=${result.score}|cloud=${server.getInt("score")}" +
                        "|delta=$delta|level=${result.level.name}|cloudLevel=${server.getString("level")}" +
                        "|accuracy=${result.accuracy}|cloudAccuracy=${server.getInt("accuracy")}" +
                        "|completeness=${result.completeness}|cloudCompleteness=${server.getInt("completeness")}",
                )
                assertTrue(
                    "Score parity broken for $name: on-device ${result.score} vs cloud " +
                        "${server.getInt("score")}, tolerance $scoreAbs",
                    delta <= scoreAbs,
                )
                assertEquals(
                    "Verdict mismatch for $name: level on-device ${result.level.name} vs cloud " +
                        server.getString("level"),
                    server.getString("level"),
                    result.level.name,
                )
                assertEquals(
                    "Mastery mismatch for $name",
                    server.getBoolean("isMastery"),
                    result.isMastery,
                )
                evaluated++
            } else {
                // Directives 5 and 30: silence, noise and too-quiet audio must come
                // back "not evaluable", never a fabricated 0/100.
                val result = OnDeviceScorer.score(scoreRequest)
                println(
                    "SCORE_PARITY|$name|onDevice=not-evaluable" +
                        "|cloudHttp=${server.getInt("httpStatus")}|evaluated=${result.evaluated}",
                )
                assertFalse(
                    "$name should be reported as not evaluable, but it was scored " +
                        "${result.score}/100",
                    result.evaluated,
                )
            }
        }
        assertTrue("The golden has no evaluable case", evaluated > 0)
    }
}
