package com.glosso.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/** The Ko-fi page for voluntary donations; blank hides every "Support Glosso" entry. */
const val KOFI_URL = ""

/**
 * The "Support Glosso" row: Glosso is free and open source, and a coffee helps
 * keep the servers running. It opens [url] in the browser and renders nothing
 * while [url] is blank.
 */
@Composable
internal fun SupportGlossoRow(modifier: Modifier = Modifier, url: String = KOFI_URL) {
    if (url.isBlank()) return
    val uriHandler = LocalUriHandler.current
    HubRow(
        icon = Icons.Outlined.LocalCafe,
        title = stringResource(Res.string.support_title),
        subtitle = stringResource(Res.string.support_subtitle),
        onClick = { runCatching { uriHandler.openUri(url) } },
        modifier = modifier,
    )
}

/** About's "Support Glosso" section, in the screen's own label, line and link form; hidden while [url] is blank. */
@Composable
internal fun SupportGlossoSection(onOpen: (String) -> Unit, url: String = KOFI_URL) {
    if (url.isBlank()) return
    Column {
        Spacer(Modifier.height(Space.x32))
        SectionLabel(stringResource(Res.string.support_title))
        Spacer(Modifier.height(Space.x8))
        Text(
            text = stringResource(Res.string.support_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Space.x12))
        AboutLink(stringResource(Res.string.support_link)) { onOpen(url) }
    }
}
