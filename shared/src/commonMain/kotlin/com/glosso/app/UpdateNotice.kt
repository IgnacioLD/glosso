package com.glosso.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.glosso.app.designsystem.Space
import com.glosso.app.product.VersionNotice
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The soft "a newer version is out" strip. It is a notice, not a gate: nothing
 * blocks on a version, so it is dismissible and the app keeps working exactly
 * as before.
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
