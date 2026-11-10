package com.glosso.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.Space
import com.glosso.app.designsystem.levelColor
import com.glosso.app.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Parses "word=meaning" options, dropping anything malformed so a bad row can
 * never crash the board.
 */
internal fun parseMatchPairs(options: List<String>): List<Pair<String, String>> =
    options.mapNotNull { option ->
        val at = option.indexOf('=')
        if (at <= 0 || at == option.lastIndex) {
            null
        } else {
            option.substring(0, at).trim() to option.substring(at + 1).trim()
        }
    }.filter { it.first.isNotBlank() && it.second.isNotBlank() }

/**
 * Pairs a word with its meaning: tap a word, then its meaning. A right pair
 * settles into brass; a wrong one flashes softly and clears, never shouting. It
 * works for any number of pairs.
 */
@Composable
internal fun MatchBoard(
    options: List<String>,
    resetKey: Any,
    enabled: Boolean,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairs = remember(options) { parseMatchPairs(options) }
    var words by remember(options, resetKey) { mutableStateOf(pairs.map { it.first }.shuffled()) }
    var meanings by remember(options, resetKey) { mutableStateOf(pairs.map { it.second }.shuffled()) }
    var matchedWords by remember(options, resetKey) { mutableStateOf(emptySet<String>()) }
    var matchedMeanings by remember(options, resetKey) { mutableStateOf(emptySet<String>()) }
    var selectedWord by remember(options, resetKey) { mutableStateOf<String?>(null) }
    var selectedMeaning by remember(options, resetKey) { mutableStateOf<String?>(null) }
    var wrongWord by remember(options, resetKey) { mutableStateOf<String?>(null) }
    var wrongMeaning by remember(options, resetKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(wrongWord, wrongMeaning) {
        if (wrongWord != null || wrongMeaning != null) {
            delay(550)
            wrongWord = null
            wrongMeaning = null
        }
    }

    fun resolve() {
        val word = selectedWord ?: return
        val meaning = selectedMeaning ?: return
        selectedWord = null
        selectedMeaning = null
        if (pairs.any { it.first == word && it.second == meaning }) {
            val next = matchedWords + word
            matchedWords = next
            matchedMeanings = matchedMeanings + meaning
            if (next.size == pairs.size) onComplete()
        } else {
            wrongWord = word
            wrongMeaning = meaning
        }
    }

    if (pairs.size < 2) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.x12),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.x8),
        ) {
            words.forEach { word ->
                MatchToken(
                    text = word,
                    matched = word in matchedWords,
                    selected = selectedWord == word,
                    wrong = wrongWord == word,
                    enabled = enabled,
                    onClick = {
                        if (word !in matchedWords) {
                            selectedWord = if (selectedWord == word) null else word
                            if (selectedMeaning != null) resolve()
                        }
                    },
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.x8),
        ) {
            meanings.forEach { meaning ->
                MatchToken(
                    text = meaning,
                    matched = meaning in matchedMeanings,
                    selected = selectedMeaning == meaning,
                    wrong = wrongMeaning == meaning,
                    enabled = enabled,
                    onClick = {
                        if (meaning !in matchedMeanings) {
                            selectedMeaning = if (selectedMeaning == meaning) null else meaning
                            if (selectedWord != null) resolve()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MatchToken(
    text: String,
    matched: Boolean,
    selected: Boolean,
    wrong: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // Three states that must never be confused: the token in hand (gold), a pair
    // already settled (the success tone, stepped back) and a miss (error).
    val good = levelColor("MASTERED")
    val container = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        matched -> good.copy(alpha = 0.10f)
        wrong -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        matched -> good.copy(alpha = 0.45f)
        wrong -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        matched -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        enabled = enabled && !matched,
        color = container,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Space.x12, vertical = Space.x12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
            if (matched) {
                Spacer(Modifier.width(Space.x8))
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(Res.string.match_matched),
                    tint = good,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
