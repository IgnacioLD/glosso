package com.glosso.app

import com.glosso.app.platform.PlatformBackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Space
import com.glosso.app.product.ProductConfigRepository
import com.glosso.app.product.VersionNotice
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > About. Version, the voluntary support link, the product's privacy
 * stance and the full licence catalogue. Both the privacy policy and the attributions are rendered from
 * assets bundled in the APK, so nothing points at the private repository.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val config by ProductConfigRepository.config.collectAsState()
    val appConfig = LocalAppConfig.current

    if (showLicenses) {
        TrackScreen("licenses")
        // System back returns to About, not out of Settings.
        PlatformBackHandler { showLicenses = false }
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    if (showPrivacy) {
        TrackScreen("privacy")
        PlatformBackHandler { showPrivacy = false }
        PrivacyScreen(onBack = { showPrivacy = false })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(stringResource(Res.string.about_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(Space.x12))
            GlossoWordmark()
            Spacer(Modifier.height(Space.x12))
            Text(
                text = stringResource(Res.string.about_version, appConfig.versionName, appConfig.versionCode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val server by ServerPreferences.base.collectAsState()
            if (server != ServerPreferences.defaultBase) {
                Text(
                    text = stringResource(Res.string.about_server, server),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (VersionNotice.from(config, appConfig.versionCode).visible) {
                Spacer(Modifier.height(Space.x32))
                Text(
                    text = stringResource(Res.string.update_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val uriHandler = LocalUriHandler.current
            SupportGlossoSection(onOpen = { runCatching { uriHandler.openUri(it) } })

            Spacer(Modifier.height(Space.x32))
            SectionLabel(stringResource(Res.string.about_privacy_title))
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.about_privacy_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x12))
            AboutLink(stringResource(Res.string.about_privacy_link)) { showPrivacy = true }

            Spacer(Modifier.height(Space.x32))
            SectionLabel(stringResource(Res.string.about_licenses_title))
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.about_licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x12))
            AboutLink(stringResource(Res.string.about_licenses_open)) { showLicenses = true }
            Spacer(Modifier.height(Space.x40))
        }
    }
}

@Composable
internal fun AboutLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = GlossoColors.accentText,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.x12),
    )
}
