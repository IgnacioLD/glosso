package com.glosso.server

import com.glosso.engine.drills.DrillBankLoader
import com.glosso.engine.model.ContentKind
import com.glosso.engine.model.ContentManifest
import com.glosso.engine.model.DrillPlanModel
import com.glosso.engine.model.PhonemeStatModel
import com.glosso.engine.model.PhoneticProfileModel
import com.glosso.engine.model.PronunciationFeedback
import com.glosso.engine.model.WordBank
import com.glosso.server.api.DrillRequest
import com.glosso.server.api.ScoreRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A minimal German and a minimal Russian bank through the real path: authored
 * JSON without IPA plus the lexicon `tools/content/phonemize.py` writes, compiled
 * by `ContentPublisher`, then served and scored by the application. The lexicon
 * entries are espeak-ng 1.52 output for these words.
 */
class GermanRussianContentTest {

    private class Bank(
        val language: String,
        val lexicon: String,
        val drills: String,
        val words: String,
        val firstDrillIpa: String,
        val weakPhone: String,
        val confusedWith: String,
        val weakDrillId: String,
    )

    private val german = Bank(
        language = "de",
        lexicon = """
            meine mˈaɪnə
            brüder bɾˈyːdɜ
            bruder bɾˈuːdɜ
            üben ˈyːbən
            früh frˈyː
            ich ɪç
            möchte mˈœçtə
            nicht nˈɪçt
            in ˈɪn
            die diː
            kirche kˈɪɾçə
            kirsche kˈɪɾʃə
            mütter mˈʏtɜ
        """.trimIndent(),
        drills = """
            {"version":1,"language":"de","drills":[
              {"id":"de.ue.u.a1.001","language":"de","level":"A1","theme":"family",
               "target":"yː","contrast":"uː","ruleId":"de.ue.u","focus":["Brüder","Bruder"],
               "text":"Meine Brüder üben früh."},
              {"id":"de.ich.sch.a1.001","language":"de","level":"A1","theme":"family",
               "target":"ç","contrast":"ʃ","ruleId":"de.ich.sch","focus":["Kirche","Kirsche"],
               "text":"Ich möchte nicht in die Kirche."}
            ]}
        """.trimIndent(),
        words = """
            {"version":1,"language":"de","words":[
              {"id":"de.word.bruder","language":"de","level":"A1","theme":"family","lemma":"bruder",
               "translations":{"es":"hermano"},"examples":["Mein Bruder ist hier."]}
            ]}
        """.trimIndent(),
        firstDrillIpa = "mˈaɪnə bɾˈyːdɜ ˈyːbən frˈyː",
        weakPhone = "yː",
        confusedWith = "uː",
        weakDrillId = "de.ue.u.a1.001",
    )

    private val russian = Bank(
        language = "ru",
        lexicon = """
            мать mˈɑtʲ
            мат mˈɑt
            хочет xˈotʃit
            читать tʃitˈɑtʲ
            ты tˈy
            хочешь xˈotʃiʃ
            быть bytʲ
            дома dˈoma
            молоко mʌɭʌkˈo
        """.trimIndent(),
        drills = """
            {"version":1,"language":"ru","drills":[
              {"id":"ru.soft.hard.a1.001","language":"ru","level":"A1","theme":"family",
               "target":"tʲ","contrast":"t","ruleId":"ru.soft.hard","focus":["мать","мат"],
               "text":"Мать хочет читать."},
              {"id":"ru.y.i.a1.001","language":"ru","level":"A1","theme":"family",
               "target":"y","ruleId":"ru.y.i","focus":["быть"],
               "text":"Ты хочешь быть дома?"}
            ]}
        """.trimIndent(),
        words = """
            {"version":1,"language":"ru","words":[
              {"id":"ru.word.mat","language":"ru","level":"A1","theme":"family","lemma":"мать",
               "translations":{"es":"madre"},"examples":["Моя мать дома."]}
            ]}
        """.trimIndent(),
        firstDrillIpa = "mˈɑtʲ xˈotʃit tʃitˈɑtʲ",
        weakPhone = "y",
        confusedWith = "i",
        weakDrillId = "ru.y.i.a1.001",
    )

    /** Authors the bank, runs the publisher and returns its `dist` output. */
    private fun publish(bank: Bank): File {
        val content = Files.createTempDirectory("glosso-${bank.language}-content").toFile()
        File(content, "${bank.language}.lexicon").writeText(bank.lexicon)
        File(content, "drills").mkdirs()
        File(content, "drills/a1.json").writeText(bank.drills)
        File(content, "words").mkdirs()
        File(content, "words/a1.json").writeText(bank.words)
        com.glosso.server.tools.main(arrayOf("--content", content.path, "--language", bank.language))
        return File(content, "dist")
    }

    /** English flat at the root (legacy layout) plus the published language bank. */
    private fun assets(bank: Bank): File {
        val dist = publish(bank)
        val dir = Files.createTempDirectory("glosso-${bank.language}-assets").toFile()
        File(dir, "cmudict.dict").writeText(";;; minimal test dictionary\nTHINK  TH IH1 NG K\n")
        val scoped = File(dir, "content/${bank.language}")
        scoped.mkdirs()
        File(scoped, "${bank.language}.lexicon").writeText(bank.lexicon)
        File(dist, "drills").copyRecursively(File(scoped, "drills"))
        File(dist, "manifest.json").copyTo(File(scoped, "manifest.json"))
        return dir
    }

    private fun assertPublishes(bank: Bank) {
        val dist = publish(bank)

        val drills = DrillBankLoader.parse(File(dist, "drills/a1.json").readText()).drills
        assertEquals(2, drills.size, "every authored drill must pass validation")
        assertTrue(drills.all { it.language == bank.language })
        assertEquals(bank.firstDrillIpa, drills.first().ipa, "IPA is filled from the lexicon")
        assertEquals(listOf(drills.first().target), drills.first().targets)

        val words = DrillBankLoader.json.decodeFromString(WordBank.serializer(), File(dist, "words/a1.json").readText())
        assertEquals(1, words.words.size)
        assertTrue(words.words.single().ipa.isNotBlank())

        val manifest = DrillBankLoader.json.decodeFromString(
            ContentManifest.serializer(),
            File(dist, "manifest.json").readText(),
        )
        assertEquals(bank.language, manifest.language)
        assertEquals(2, manifest.banks.single { it.kind == ContentKind.DRILL }.count)
        assertEquals(1, manifest.banks.single { it.kind == ContentKind.WORD }.count)
    }

    @Test
    fun `a minimal german bank publishes`() = assertPublishes(german)

    @Test
    fun `a minimal russian bank publishes`() = assertPublishes(russian)

    private fun assertPlansFromPublishedBank(bank: Bank) = testApplication {
        application { module(assets(bank)) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val weak = PhoneticProfileModel(
            stats = listOf(
                PhonemeStatModel(bank.weakPhone, attempts = 5, correct = 0, errors = mapOf(bank.confusedWith to 5)),
            ),
        )

        val response = client.post("/v1/drills") {
            contentType(ContentType.Application.Json)
            setBody(DrillRequest(profile = weak, language = bank.language))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val planned = response.body<DrillPlanModel>().drills.map { it.id }
        assertTrue(bank.weakDrillId in planned, "expected ${bank.weakDrillId} in $planned")
    }

    @Test
    fun `the server plans drills from the published german bank`() = assertPlansFromPublishedBank(german)

    @Test
    fun `the server plans drills from the published russian bank`() = assertPlansFromPublishedBank(russian)

    private suspend fun io.ktor.client.HttpClient.score(text: String, actualIpa: String, language: String) =
        post("/v1/score") {
            contentType(ContentType.Application.Json)
            setBody(ScoreRequest(text = text, actualIpa = actualIpa, language = language))
        }

    @Test
    fun `german is scored from its lexicon with the german rules`() = testApplication {
        application { module(assets(german)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Mütter said as Mutter: the reference comes from the lexicon, not the client.
        val wrong = client.score("Mütter", "mʊtɜ", "de")
        assertEquals(HttpStatusCode.OK, wrong.status)
        val feedback = wrong.body<PronunciationFeedback>()
        assertTrue(feedback.score < 100)
        assertTrue(feedback.errors.any { it.ruleId == "de.ue.short.u" }, "errors: ${feedback.errors}")

        // The model's /y/ for espeak's /ʏ/ is the same sound.
        assertEquals(100, client.score("Mütter", "mytɜ", "de").body<PronunciationFeedback>().score)
    }

    @Test
    fun `russian is scored from its lexicon with the russian rules`() = testApplication {
        application { module(assets(russian)) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // мать said as мат: the soft consonant came out hard.
        val wrong = client.score("мать", "mɑt", "ru")
        assertEquals(HttpStatusCode.OK, wrong.status)
        val feedback = wrong.body<PronunciationFeedback>()
        assertTrue(feedback.score < 100)
        assertTrue(feedback.errors.any { it.ruleId == "ru.soft.hard" }, "errors: ${feedback.errors}")

        assertEquals(100, client.score("мать", "mɑtʲ", "ru").body<PronunciationFeedback>().score)
        // хочет: the model's raw Ч token matches the folded reference.
        assertEquals(100, client.score("хочет", "xotʃʲit", "ru").body<PronunciationFeedback>().score)
    }
}
