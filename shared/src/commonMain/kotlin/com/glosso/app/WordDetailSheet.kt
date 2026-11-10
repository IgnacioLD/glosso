package com.glosso.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.WordModel
import com.glosso.engine.model.displayPhonetic
import org.jetbrains.compose.resources.stringResource

/**
 * The word a learner tapped in a reading: its IPA, its meaning and its own audio,
 * in the same card the practice step uses. It is a bottom overlay so opening a
 * word never reflows the paragraph being read, and the scrim closes it.
 */
@Composable
internal fun WordDetailSheet(
    word: WordModel,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(GlossoColors.scrim)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = stringResource(Res.string.close),
                    onClick = onDismiss,
                ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
            border = cardBorder(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Space.gutter, vertical = Space.x16),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(Res.string.stories_vocabulary))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(Res.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Space.x4))
                DrillCard(
                    text = word.lemma,
                    ipa = word.displayPhonetic,
                    translation = uiTranslation(word.translations),
                    focus = emptyList(),
                    hasAudio = word.audio.isNotEmpty(),
                    onPlay = if (word.audio.isNotEmpty()) onPlay else null,
                )
            }
        }
    }
}
