package com.glosso.server.tools

import java.io.File
import java.util.Base64
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.glosso.engine.phonetics.PhoneticComparator
import com.glosso.engine.phonetics.Wav2Vec2PhonemeRecognizer

/**
 * Offline quantitative report: runs the shipped recognizer and a fine-tuned one
 * over a sample of real content (TTS audio + authored reference IPA) and prints,
 * per item, the phoneme error rate and the engine score of both models.
 *
 * This is the number that decides whether the fine-tuned model is better. It uses
 * the same engine the app and the cloud use, so the PER and the score are exactly
 * what a learner would get, not a reimplementation.
 *
 * It is a standalone tool, never wired into the running server. Usage:
 *
 *   java -cp "lib/<all jars>" com.glosso.server.tools.ModelCompareReportKt \
 *     --base-model base.onnx --base-vocab vocab.json \
 *     --ft-model ft.onnx --ft-vocab vocab.json \
 *     --manifest sample.json --audio-dir wav --out REPORT.md
 */
@Serializable
data class ReportItem(
    val id: String,
    val kind: String = "",
    val text: String,
    /** Authored reference pronunciation (IPA) from content.db. */
    val reference: String,
    /** Name of the 16 kHz mono WAV file inside --audio-dir. */
    val audio: String,
)

private data class Verdict(
    val transcription: String,
    val per: Double,
    val score: Int,
    val accuracy: Int,
    val completeness: Int,
)

private class Args(args: Array<String>) {
    private val map: Map<String, String> = args
        .toList()
        .chunked(2)
        .filter { it.size == 2 && it[0].startsWith("--") }
        .associate { it[0].removePrefix("--") to it[1] }

    fun require(key: String): String = map[key] ?: run {
        System.err.println("Missing --$key")
        exitProcess(2)
    }
}

private val MANIFEST_JSON = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    val parsed = Args(args)
    val baseModel = File(parsed.require("base-model"))
    val baseVocab = File(parsed.require("base-vocab"))
    val ftModel = File(parsed.require("ft-model"))
    val ftVocab = File(parsed.require("ft-vocab"))
    val manifestFile = File(parsed.require("manifest"))
    val audioDir = File(parsed.require("audio-dir"))
    val outFile = File(parsed.require("out"))

    val items: List<ReportItem> =
        MANIFEST_JSON.decodeFromString(manifestFile.readText())

    val base = Wav2Vec2PhonemeRecognizer(baseModel, baseVocab).also { it.initialize() }
    val ft = Wav2Vec2PhonemeRecognizer(ftModel, ftVocab).also { it.initialize() }
    check(base.state == com.glosso.engine.phonetics.ModelState.READY) {
        "base model failed to load: ${base.error}"
    }
    check(ft.state == com.glosso.engine.phonetics.ModelState.READY) { "ft model failed to load: ${ft.error}" }

    val rows = mutableListOf<Row>()
    for (item in items) {
        val wav = File(audioDir, item.audio)
        if (!wav.isFile) {
            System.err.println("Missing audio for ${item.id}: ${wav.path}")
            continue
        }
        val base64 = Base64.getEncoder().encodeToString(wav.readBytes())
        val baseVerdict = score(base, base64, item)
        val ftVerdict = score(ft, base64, item)
        rows.add(Row(item, baseVerdict, ftVerdict))
    }

    val markdown = render(rows)
    outFile.parentFile?.mkdirs()
    outFile.writeText(markdown)
    println(markdown)
}

private class Row(val item: ReportItem, val base: Verdict, val ft: Verdict)

private fun score(
    recognizer: Wav2Vec2PhonemeRecognizer,
    audioBase64: String,
    item: ReportItem,
): Verdict {
    val recognition = recognizer.recognizeDetailed(audioBase64)
        ?: return Verdict("", Double.NaN, -1, -1, -1)
    val per = PhoneticComparator.phonemeErrorRate(item.reference, recognition.ipa)
    val confidences = PhoneticComparator.phoneConfidencesOf(recognition.phones)
    val result = PhoneticComparator.calculateScoringResult(
        text = item.text,
        expected = item.reference,
        actual = recognition.ipa,
        language = "en",
        actualConfidence = confidences,
    )
    return Verdict(recognition.ipa, per, result.score, result.accuracy, result.completeness)
}

private fun render(rows: List<Row>): String {
    val scored = rows.filter { !it.base.per.isNaN() && !it.ft.per.isNaN() }
    val basePer = scored.map { it.base.per }
    val ftPer = scored.map { it.ft.per }
    val baseScore = scored.map { it.base.score }
    val ftScore = scored.map { it.ft.score }
    val ftBetter = scored.count { it.ft.per < it.base.per - 1e-9 }
    val baseBetter = scored.count { it.ft.per > it.base.per + 1e-9 }
    val tied = scored.size - ftBetter - baseBetter

    return buildString {
        append("# Comparacion de reconocedores sobre contenido real\n\n")
        append("Ambos modelos corren con el mismo motor (`:engine`) sobre el audio TTS del\n")
        append("contenido del VPS y la IPA de referencia del propio contenido. PER =\n")
        append("distancia de Levenshtein entre la referencia y la transcripcion, dividida por\n")
        append("el numero de fonemas de la referencia. Puntuacion = la que veria el alumno.\n\n")
        append("| item | kind | ref | actual | ft | PER actual | PER ft | score actual | score ft | gana |\n")
        append("|---|---|---|---|---|---:|---:|---:|---:|---|\n")
        for (row in rows) {
            val perBase = row.base.per.percent()
            val perFt = row.ft.per.percent()
            val winner = winner(row)
            append("| ${row.item.id} | ${row.item.kind} | `${row.item.reference}` | ")
            append("`${row.base.transcription}` | `${row.ft.transcription}` | ")
            append("$perBase | $perFt | ${row.base.score.formatScore()} | ${row.ft.score.formatScore()} | $winner |\n")
        }
        append("\n## Resumen\n\n")
        append("- items evaluados: ${scored.size} de ${rows.size}\n")
        append("- PER medio actual: ${basePer.averageDouble().percent()}\n")
        append("- PER medio fine-tuned: ${ftPer.averageDouble().percent()}\n")
        append("- Puntuacion media actual: ${baseScore.averageInt().round1()}\n")
        append("- Puntuacion media fine-tuned: ${ftScore.averageInt().round1()}\n")
        append("- items donde gana el fine-tuned (menor PER): $ftBetter\n")
        append("- items donde gana el actual (menor PER): $baseBetter\n")
        append("- empates de PER: $tied\n")
        append("\n**Veredicto**: ${verdict(basePer.averageDouble(), ftPer.averageDouble(), baseScore.averageInt(), ftScore.averageInt())}\n")
    }
}

private fun winner(row: Row): String {
    if (row.base.per.isNaN()) return "actual sin resultado"
    if (row.ft.per.isNaN()) return "ft sin resultado"
    return when {
        row.ft.per < row.base.per - 1e-9 -> "**ft**"
        row.ft.per > row.base.per + 1e-9 -> "actual"
        row.ft.score > row.base.score -> "**ft** (score)"
        row.ft.score < row.base.score -> "actual (score)"
        else -> "empate"
    }
}

private fun verdict(basePer: Double, ftPer: Double, baseScore: Double, ftScore: Double): String {
    val perDelta = (basePer - ftPer) * 100
    val scoreDelta = ftScore - baseScore
    return if (perDelta > 0.05 || scoreDelta > 0.5) {
        "el fine-tuned mejora: ${perDelta.round1()} pp menos de PER y ${scoreDelta.round1()} pts mas de puntuacion."
    } else if (perDelta < -0.05 || scoreDelta < -0.5) {
        "el modelo actual aguanta o gana: ${(-perDelta).round1()} pp de PER y ${(-scoreDelta).round1()} pts a su favor."
    } else {
        "empate tecnico: la diferencia no es significativa sobre esta muestra."
    }
}

private fun Double.percent(): String = if (isNaN()) "-" else "${(this * 100).round1()}%"
private fun Int.formatScore(): String = if (this < 0) "-" else toString()
private fun Double.round1(): String = (kotlin.math.round(this * 10) / 10).toString()
private fun List<Double>.averageDouble(): Double = if (isEmpty()) Double.NaN else sum() / size
private fun List<Int>.averageInt(): Double = if (isEmpty()) Double.NaN else sum().toDouble() / size
