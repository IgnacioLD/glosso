package com.glosso.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.platform.currentUiLanguage
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The full attribution list, rendered from the bundled `files/licenses.json`.
 * Nothing here links to the source repository: the texts travel in the artifact.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    // Resources are read off the main thread. The list is composed only once the
    // read is done, so a restored scroll position lands on the full list.
    val loaded by produceState<Result<LicenseCatalog>?>(null) { value = runCatching { loadLicenseCatalog() } }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(stringResource(Res.string.about_licenses_title), onBack)
        val result = loaded ?: return@Column
        val catalog = result.getOrNull()
        val components = catalog?.components.orEmpty()
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = Space.gutter,
                end = Space.gutter,
                top = Space.x8,
                bottom = listBottomPadding(),
            ),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.about_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x16))
            }
            items(components, key = { it.id }) { component ->
                LicenseRow(
                    component = component,
                    text = component.licenseText?.let { catalog?.texts?.get(it) },
                    expanded = component.id in expanded,
                    onToggle = {
                        expanded = if (component.id in expanded) expanded - component.id else expanded + component.id
                    },
                )
            }
        }
    }
}

@Composable
private fun LicenseRow(
    component: LicenseComponent,
    text: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = Space.x12),
    ) {
        Text(
            text = component.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val subtitle = listOfNotNull(component.author, component.licenseName).joinToString(" - ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (expanded) {
            component.usage?.let {
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            component.notice?.let {
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val link = component.licenseTextUrl
            if (text != null) {
                Spacer(Modifier.height(Space.x8))
                Hairline()
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (link != null) {
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = stringResource(Res.string.licenses_full_terms),
                    style = MaterialTheme.typography.bodySmall,
                    color = GlossoColors.accentText,
                    modifier = Modifier
                        .heightIn(min = Sizes.touch)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .clickable { uriHandler.openUri(link) },
                )
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val uiLanguage = currentUiLanguage()
    // Composed only once read, so a restored scroll position lands on the text.
    val loaded by produceState<String?>(null, uiLanguage) {
        value = runCatching { loadPrivacyText(uiLanguage) }.getOrDefault("")
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(stringResource(Res.string.about_privacy_title), onBack)
        val body = loaded ?: return@Column
        MarkdownDocument(
            source = body,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.gutter, vertical = Space.x12)
                .padding(bottom = Space.x24),
        )
    }
}
