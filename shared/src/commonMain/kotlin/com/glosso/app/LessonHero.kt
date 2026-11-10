package com.glosso.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.OnHero
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Space

/**
 * "Your lesson" as one object wherever it appears (Today, the course): the one
 * filled cobalt surface in the app, so the eye lands on it first. A voice
 * wave drifts behind a kicker, the title and one primary action (a white
 * pill). The body slot carries the state-specific parts (objective, steps,
 * the button). The card itself is not a second tap target: the button in it
 * is the one action.
 */
@Composable
internal fun LessonHeroSurface(
    kicker: String,
    modifier: Modifier = Modifier,
    tags: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = GlossoColors.hero,
        shape = RoundedCornerShape(Radius.xl),
        modifier = modifier.fillMaxWidth(),
    ) {
        OnHero {
            Box(Modifier) {
                VoiceWave(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp)
                        .height(48.dp)
                        .align(Alignment.TopCenter),
                    color = GlossoColors.onHero,
                    alpha = 0.22f,
                )
                Column(Modifier.padding(horizontal = Space.x24, vertical = Space.x24)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(kicker, modifier = Modifier.weight(1f))
                        if (tags != null) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Space.x8),
                                verticalAlignment = Alignment.CenterVertically,
                                content = tags,
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.x72))
                    content()
                }
            }
        }
    }
}

/** The title and objective every hero shares. */
@Composable
internal fun LessonHeroTitle(title: String, objective: String?) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (!objective.isNullOrBlank()) {
        Spacer(Modifier.height(Space.x8))
        Text(
            text = objective,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The lesson steps as segments (done and current filled) with one caption
 * naming where the learner is. Reads "where am I" at once.
 */
@Composable
internal fun LabelledSteps(current: Int, total: Int, caption: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        StepProgress(total = total, current = current)
        Spacer(Modifier.height(Space.x8))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelMedium,
            color = GlossoColors.accentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
