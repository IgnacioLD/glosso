package com.glosso.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.glosso.app.api.AccountState
import com.glosso.app.api.ProductConfig
import com.glosso.app.billing.VersionNotice
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Space
import com.glosso.app.product.formatBetaEndDate
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The honest beta card, shown while the open beta is on. It says three things
 * and nothing more: the beta is open, the course is free until the date the
 * server publishes (never a date written in the app), and what being a Beta
 * Founder means. It never promises "always free": after the beta there will be
 * plans, and the copy does not pretend otherwise.
 *
 * The date is formatted from `beta.endDate`; when the server has not published
 * one yet, the copy says "during the beta" instead of inventing a date.
 */
@Composable
internal fun BetaBanner(
    config: ProductConfig,
    account: AccountState?,
    modifier: Modifier = Modifier,
) {
    if (!config.isBeta && !config.beta.active) return

    val endDate = formatBetaEndDate(config.beta.endDate)
    val founderBenefit = config.beta.founderEnabled || account?.betaFounder == true

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(Space.x12))
                Text(
                    text = stringResource(Res.string.beta_open_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(Space.x8))
            Text(
                text = if (endDate != null) {
                    stringResource(Res.string.beta_free_until, endDate)
                } else {
                    stringResource(Res.string.beta_free_during)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (founderBenefit) {
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = if (account?.betaFounder == true) {
                        stringResource(Res.string.beta_founder_you_are)
                    } else {
                        stringResource(Res.string.beta_founder_note)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The soft "a newer version is out" strip. It is a notice, not a gate: during
 * the beta nothing blocks on a version, so it is dismissible and the app keeps
 * working exactly as before.
 */
@Composable
internal fun UpdateNotice(
    notice: VersionNotice,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!notice.visible) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter, vertical = Space.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Space.x12))
            Text(
                text = stringResource(Res.string.update_notice),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            QuietTextButton(stringResource(Res.string.update_notice_dismiss), onClick = onDismiss)
        }
    }
}
