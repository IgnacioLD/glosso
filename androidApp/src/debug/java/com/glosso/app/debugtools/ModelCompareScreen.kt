package com.glosso.app.debugtools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glosso.app.BuildConfig
import com.glosso.app.Hairline
import com.glosso.app.LanguagePreferences
import com.glosso.app.OnDeviceScorer
import com.glosso.app.PrimaryButton
import com.glosso.app.ProgressLine
import com.glosso.app.QuietTextButton
import com.glosso.app.SectionLabel
import com.glosso.app.SubHeader
import com.glosso.app.appApi
import com.glosso.app.audio.AudioTranscoder
import com.glosso.app.audio.AndroidRecorder
import com.glosso.app.designsystem.Space
import com.glosso.app.formatBytes
import com.glosso.engine.model.DrillModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True in debug builds; the release variant returns false and ships no comparator. */
internal fun modelCompareAvailable(): Boolean = !BuildConfig.STORE_SCREENSHOTS

private const val PREFERRED_VOICE = "en-GB-female"

/**
 * Debug-only side-by-side comparison of the shipped and fine-tuned recognizers on
 * real content. Pick a phrase (its TTS audio is decoded on device) or record one,
 * then send the same audio through both models and read transcription, PER, score
 * and the delta. Never part of a release build.
 */
@Composable
internal fun ModelCompareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scorer by OnDeviceScorer.state.collectAsState()

    var items by remember { mutableStateOf<List<DrillModel>>(emptyList()) }
    var selected by remember { mutableStateOf<DrillModel?>(null) }
    var audio by remember { mutableStateOf<ByteArray?>(null) }
    var audioLabel by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<AndroidRecorder?>(null) }
    var ftPresent by remember { mutableStateOf(ModelComparison.ftPresent(context)) }
    var ftProgress by remember { mutableStateOf<Float?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<CompareOutcome?>(null) }

    fun loadItem(item: DrillModel) {
        selected = item
        audio = null
        outcome = null
        error = null
        audioLabel = ""
        val key = item.audio.keys.firstOrNull { it == PREFERRED_VOICE }
            ?: item.audio.keys.firstOrNull { !it.endsWith(":slow") }
            ?: item.audio.keys.firstOrNull()
        val path = key?.let { item.audio[it] }
        if (path == null) {
            error = "Este item no trae audio TTS"
            return
        }
        scope.launch {
            busy = "Cargando audio TTS"
            try {
                val bytes = appApi.assetBytes(path)
                val wav = withContext(Dispatchers.IO) { AudioTranscoder.toWav16k(context, bytes) }
                if (wav == null) {
                    error = "No se pudo decodificar el audio TTS"
                } else {
                    audio = wav
                    audioLabel = "TTS $key"
                }
            } catch (e: Exception) {
                error = "Fallo cargando el audio: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { appApi.drills(limit = 12) }
            .onSuccess { items = it }
            .onFailure { error = "No se pudo cargar el contenido: ${it.message}" }
    }

    fun toggleRecord() {
        error = null
        if (recording) {
            val active = recorder
            recording = false
            recorder = null
            val wav = runCatching { active?.stop() }.getOrNull()
            if (wav != null && wav.isNotEmpty()) {
                outcome = null
                audio = wav
                audioLabel = "Grabacion"
            }
        } else {
            try {
                val fresh = AndroidRecorder()
                fresh.start()
                recorder = fresh
                recording = true
                outcome = null
            } catch (e: Exception) {
                error = "Microfono no disponible: ${e.message}"
            }
        }
    }

    fun compare() {
        val wav = audio ?: return
        val item = selected ?: return
        if (busy != null || recording) return
        outcome = null
        error = null
        scope.launch {
            busy = "Preparando"
            try {
                outcome = ModelComparison.compare(
                    context = context,
                    audioWav = wav,
                    text = item.text,
                    referenceIpa = item.ipa,
                    language = LanguagePreferences.current.code,
                    onStatus = { status -> busy = status },
                )
            } catch (e: Exception) {
                error = "Fallo comparando: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(title = "Comparar modelos", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
        ) {
            Spacer(Modifier.height(Space.x8))
            Text(
                "Herramienta de desarrollo. El modelo fine-tuned se descarga bajo demanda; " +
                    "nunca viaja dentro del APK.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.x20))
            SectionLabel("Modelos")
            Spacer(Modifier.height(Space.x8))
            ModelStatusRow(
                name = "Actual",
                detail = if (scorer.present) formatBytes(scorer.bytes) else "no descargado",
                ready = scorer.present,
            )
            ModelStatusRow(
                name = "Fine-tuned",
                detail = if (ftPresent) formatBytes(ModelComparison.ftBytes(context)) else "no descargado",
                ready = ftPresent,
            )
            ftProgress?.let {
                Spacer(Modifier.height(Space.x8))
                ProgressLine(it)
                Spacer(Modifier.height(Space.x4))
                Text(
                    "Descargando fine-tuned ${(it * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(Space.x12))
            if (!scorer.present) {
                PrimaryButton(
                    text = "Descargar modelo actual (633 MB)",
                    loading = scorer.downloading,
                    onClick = { scope.launch { OnDeviceScorer.download() } },
                )
            }
            if (!ftPresent) {
                Spacer(Modifier.height(Space.x12))
                PrimaryButton(
                    text = "Descargar modelo fine-tuned (633 MB)",
                    loading = ftProgress != null,
                    onClick = {
                        scope.launch {
                            ftProgress = 0f
                            ModelComparison.downloadFt(context, appApi) { ftProgress = it }
                            ftPresent = ModelComparison.ftPresent(context)
                            ftProgress = null
                        }
                    },
                )
            }

            Spacer(Modifier.height(Space.x24))
            SectionLabel("Frase de referencia")
            Spacer(Modifier.height(Space.x8))
            if (items.isEmpty()) {
                Text(
                    "Cargando contenido...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items.forEach { item ->
                val isSelected = selected?.id == item.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { loadItem(item) }
                        .padding(vertical = Space.x12),
                ) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        item.ipa,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Hairline()
            }

            Spacer(Modifier.height(Space.x24))
            SectionLabel("Audio")
            Spacer(Modifier.height(Space.x8))
            Text(
                if (audioLabel.isBlank()) "Elige una frase o graba tu voz" else "Fuente: $audioLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x12))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x12)) {
                QuietTextButton(
                    text = if (recording) "Parar" else "Grabar",
                    onClick = { toggleRecord() },
                )
            }

            Spacer(Modifier.height(Space.x24))
            PrimaryButton(
                text = "Comparar con los dos modelos",
                enabled = audio != null && selected != null && scorer.present && ftPresent && busy == null && !recording,
                loading = busy != null,
                onClick = { compare() },
            )
            busy?.let {
                Spacer(Modifier.height(Space.x12))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            error?.let {
                Spacer(Modifier.height(Space.x12))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            outcome?.let { result ->
                Spacer(Modifier.height(Space.x32))
                Hairline(gold = true)
                Spacer(Modifier.height(Space.x20))
                SectionLabel("Resultado")
                Spacer(Modifier.height(Space.x8))
                Text(
                    result.referenceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "ref: ${result.referenceIpa}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x16))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.x16)) {
                    ModelResultColumn(result.base, Modifier.weight(1f))
                    ModelResultColumn(result.ft, Modifier.weight(1f))
                }
                Spacer(Modifier.height(Space.x20))
                WinnerBanner(result)
            }
            Spacer(Modifier.height(Space.x40))
        }
    }
}

@Composable
private fun ModelStatusRow(name: String, detail: String, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (ready) "listo - $detail" else detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelResultColumn(run: ModelRun, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(Space.x12),
    ) {
        Text(
            run.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x8))
        if (run.failed) {
            Text(
                "sin resultado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        Text(
            run.transcription.ifBlank { "(vacio)" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Space.x8))
        MetricLine("PER", run.per?.let { "${(it * 100).format1()}%" })
        MetricLine("Puntuacion", run.score?.toString())
        MetricLine("Precision", run.accuracy?.let { "$it%" })
        MetricLine("Completitud", run.completeness?.let { "$it%" })
        MetricLine("Nivel", run.level)
    }
}

@Composable
private fun MetricLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value ?: "-",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun WinnerBanner(result: CompareOutcome) {
    val base = result.base
    val ft = result.ft
    val text = when {
        base.failed && ft.failed -> "Ningun modelo devolvio resultado."
        base.failed -> "Gana Fine-tuned: el modelo actual no devolvio resultado."
        ft.failed -> "Gana Actual: el fine-tuned no devolvio resultado."
        base.score == null || ft.score == null -> "Sin comparacion posible."
        else -> {
            val scoreDelta = ft.score - base.score
            // Positive means the fine-tuned model is closer to the reference.
            val perDeltaPp = ((base.per ?: 0.0) - (ft.per ?: 0.0)) * 100
            val who = when {
                scoreDelta > 0 -> "Gana Fine-tuned"
                scoreDelta < 0 -> "Gana Actual"
                else -> "Empate en puntuacion"
            }
            val perNote = when {
                perDeltaPp > 0.05 -> "el fine-tuned transcribe mas cerca de la referencia"
                perDeltaPp < -0.05 -> "el modelo actual transcribe mas cerca de la referencia"
                else -> "mismo PER"
            }
            val scoreText = if (scoreDelta > 0) "+$scoreDelta" else "$scoreDelta"
            "$who: $scoreText pts de puntuacion y ${perDeltaPp.format1()} pp de PER; $perNote."
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

private fun Double.format1(): String {
    val scaled = kotlin.math.round(this * 10).toInt()
    return "${scaled / 10}.${kotlin.math.abs(scaled % 10)}"
}
