package com.glosso.app

import com.glosso.app.download.DownloadIds
import com.glosso.app.download.LocalDownloader
import com.glosso.app.platform.APP_LOCALE_TAGS
import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.rememberPlatformActions
import com.glosso.app.platform.PlatformBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.download.WifiOnlyPreference
import com.glosso.app.resources.*
import com.glosso.engine.model.LanguagePacks
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: ScoringViewModel = scoringViewModel()) {
    var language by remember { mutableStateOf(LanguagePreferences.current.code) }
    val pack = LanguagePacks.byCode(language)
    var accent by remember(language) { mutableStateOf(VoicePreferences.accent) }
    val scorer by vm.state.collectAsState()
    val downloader = LocalDownloader.current
    val downloads by downloader.state.collectAsState()
    val modelItem = downloads.item(DownloadIds.MODEL)
    val modelSize = onDeviceModelSize()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showRecovery by rememberSaveable { mutableStateOf(false) }
    var showModelCompare by rememberSaveable { mutableStateOf(false) }
    val context = currentPlatformContext()
    val actions = rememberPlatformActions()
    val debugTools = LocalDebugTools.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val reminderEnabled by DailyReminder.enabled.collectAsState()
    val wifiOnly by WifiOnlyPreference.enabled.collectAsState()
    val shareUsage by Analytics.enabled.collectAsState()
    var usageErase by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { DailyReminder.ensureInitialized(context) }

    if (showAbout) {
        TrackScreen("about")
        // System back closes the sub-screen, not the whole of Settings.
        PlatformBackHandler { showAbout = false }
        AboutScreen(onBack = { showAbout = false })
        return
    }

    if (showRecovery) {
        TrackScreen("recovery")
        PlatformBackHandler { showRecovery = false }
        FounderRecoveryScreen(onBack = { showRecovery = false })
        return
    }

    if (showModelCompare) {
        PlatformBackHandler { showModelCompare = false }
        debugTools.ModelCompareScreen(onBack = { showModelCompare = false })
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(stringResource(Res.string.settings_title), onBack)
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scrollEdgeFade(scroll)
                .verticalScroll(scroll)
                .padding(horizontal = Space.gutter)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(Space.x12))
            SectionLabel(stringResource(Res.string.settings_target_language))
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.settings_target_language_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            SettingChoice(
                label = stringResource(Res.string.settings_language),
                options = OfferedLanguages.all.map {
                    it.code to languageDisplay(it.code, it.displayName, it.nativeName)
                },
                selected = language,
                onSelect = { code ->
                    OfferedLanguages.orNull(code)?.takeIf { it.code != language }?.let { newPack ->
                        UsageEvents.settingChanged("target_language", newPack.code)
                        LanguagePreferences.set(newPack.code)
                        VoicePreferences.onLanguageChanged(newPack)
                        language = newPack.code
                        accent = VoicePreferences.accent
                    }
                },
            )

            if (actions.appLocaleSupported) {
                SectionBreak()
                SectionLabel(stringResource(Res.string.settings_app_language))
                Spacer(Modifier.height(Space.x8))
                Text(
                    stringResource(Res.string.settings_app_language_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x16))
                var appLocale by remember { mutableStateOf(actions.appLocale()) }
                SettingChoiceList(
                    options = APP_LOCALE_TAGS.map { tag -> tag to appLocaleDisplay(tag) },
                    selected = appLocale,
                    onSelect = { tag ->
                        if (tag != appLocale) UsageEvents.settingChanged("ui_language", tag.ifBlank { "system" })
                        appLocale = tag
                        actions.setAppLocale(tag)
                    },
                )
            } else if (actions.appLocaleInSystemSettings) {
                SectionBreak()
                HubRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(Res.string.settings_app_language),
                    subtitle = stringResource(Res.string.settings_app_language_system),
                    onClick = { actions.openAppSettings() },
                )
            }

            // The voice is the content's, not a setting; only a language with
            // more than one accent leaves the learner something to choose here.
            if (pack.accents.size > 1) {
                SectionBreak()
                SectionLabel(stringResource(Res.string.settings_reference_accent))
                Spacer(Modifier.height(Space.x8))
                Text(
                    stringResource(Res.string.settings_reference_accent_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x16))
                SettingChoice(
                    label = stringResource(Res.string.settings_accent),
                    options = pack.accents.map { it.code to accentDisplay(it.code, it.label) },
                    selected = accent,
                    onSelect = {
                        if (it != accent) UsageEvents.settingChanged("accent", it)
                        accent = it
                        VoicePreferences.set(accent)
                    },
                )
            }

            SectionBreak()
            SectionLabel(stringResource(Res.string.settings_phonetics))
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.settings_phonetics_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x20))
            val showPhonetics by PhoneticsPreferences.visible.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.settings_phonetics_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showPhonetics,
                    onCheckedChange = {
                        PhoneticsPreferences.setVisible(context, it)
                        UsageEvents.settingChanged("phonetics", it)
                    },
                )
            }

            SectionBreak()
            SectionLabel(stringResource(Res.string.settings_translations))
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.settings_translations_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x20))
            val showTranslations by TranslationPreferences.visible.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.settings_translations_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showTranslations,
                    onCheckedChange = {
                        TranslationPreferences.setVisible(context, it)
                        UsageEvents.settingChanged("translations", it)
                    },
                )
            }

            SectionBreak()
            SectionLabel(stringResource(Res.string.settings_reminder))
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.settings_reminder_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x20))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.settings_reminder_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = {
                        DailyReminder.setEnabled(context, it)
                        UsageEvents.settingChanged("reminder", it)
                    },
                )
            }

            SectionBreak()
            SectionLabel(stringResource(Res.string.settings_downloads))
            Spacer(Modifier.height(Space.x8))
            Text(
                stringResource(Res.string.settings_downloads_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x20))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(Res.string.settings_wifi_only),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Space.x2))
                    Text(
                        stringResource(Res.string.settings_wifi_only_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        WifiOnlyPreference.set(context, it)
                        downloader.wifiOnlyChanged()
                        UsageEvents.settingChanged("wifi_only", it)
                    },
                )
            }

            if (LocalAppServices.current.onDevice.supported) {
                SectionBreak()
                SectionLabel(stringResource(Res.string.settings_ondevice))
                Spacer(Modifier.height(Space.x8))
                Text(
                    stringResource(Res.string.settings_ondevice_desc, modelSize),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x20))
                OnDevicePackControl(
                    state = scorer,
                    item = modelItem,
                    sizeText = modelSize,
                    onDownload = {
                        vm.download()
                        requestNotifications()
                    },
                    onRemove = { vm.delete() },
                )
            }

            if (LocalAppConfig.current.usageStatistics) {
                SectionBreak()
                SectionLabel(stringResource(Res.string.settings_usage))
                Spacer(Modifier.height(Space.x8))
                Text(
                    stringResource(Res.string.settings_usage_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x20))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.settings_usage_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = shareUsage, onCheckedChange = { Analytics.setEnabled(context, it) })
                }
                Spacer(Modifier.height(Space.x8))
                QuietTextButton(
                    text = stringResource(Res.string.settings_usage_erase),
                    onClick = { scope.launch { usageErase = Analytics.deleteMyData() } },
                    modifier = Modifier.offset(x = -Space.x12),
                )
                usageErase?.let { erased ->
                    Text(
                        stringResource(if (erased) Res.string.settings_usage_erased else Res.string.settings_usage_erase_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Space.x32))
            if (accountSignIn.isConfigured) {
                Hairline()
                Spacer(Modifier.height(Space.x20))
                SectionLabel(stringResource(Res.string.settings_account))
                Spacer(Modifier.height(Space.x8))
                HubRow(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = stringResource(Res.string.settings_recovery),
                    subtitle = stringResource(Res.string.settings_recovery_subtitle),
                    onClick = { showRecovery = true },
                )
                Spacer(Modifier.height(Space.x32))
            }
            Hairline()
            Spacer(Modifier.height(Space.x20))
            if (debugTools.modelCompareAvailable) {
                SectionLabel("Debug")
                Spacer(Modifier.height(Space.x8))
                HubRow(
                    icon = Icons.AutoMirrored.Outlined.CompareArrows,
                    title = "Comparar modelos",
                    subtitle = "Actual vs fine-tuned; solo en builds de debug",
                    onClick = { showModelCompare = true },
                )
                Spacer(Modifier.height(Space.x20))
                Hairline()
                Spacer(Modifier.height(Space.x20))
            }
            HubRow(
                icon = Icons.Outlined.Info,
                title = stringResource(Res.string.settings_about),
                subtitle = stringResource(Res.string.settings_about_subtitle),
                onClick = { showAbout = true },
            )
            Spacer(Modifier.height(Space.x32))
        }
    }
}

@Composable
internal fun appLocaleDisplay(tag: String): String = when (tag) {
    "" -> stringResource(Res.string.settings_language_system)
    "en" -> stringResource(Res.string.settings_lang_en)
    "es" -> stringResource(Res.string.settings_lang_es)
    "fr" -> stringResource(Res.string.settings_lang_fr)
    "de" -> stringResource(Res.string.settings_lang_de)
    else -> tag
}

/** Localized display names for the language picker, with the native name in brackets. */
@Composable
internal fun languageDisplay(code: String, displayName: String, nativeName: String): String {
    val localized = when (code) {
        "en" -> stringResource(Res.string.settings_lang_en)
        "de" -> stringResource(Res.string.settings_lang_de)
        "es" -> stringResource(Res.string.settings_lang_es)
        "ru" -> stringResource(Res.string.settings_lang_ru)
        "zh" -> stringResource(Res.string.settings_lang_zh)
        else -> displayName
    }
    return if (localized == nativeName) localized else "$localized ($nativeName)"
}

@Composable
internal fun accentDisplay(code: String, label: String): String = when (code) {
    "en-US" -> stringResource(Res.string.settings_accent_us)
    "en-GB" -> stringResource(Res.string.settings_accent_uk)
    else -> label
}

@Composable
private fun SettingChoiceList(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, title) ->
            // Hairlines only between rows: the section break below brings its own.
            if (index > 0) Hairline()
            val active = value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    )
                    .padding(vertical = Space.x12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = GlossoColors.accentText,
                    )
                }
            }
        }
    }
}

/** The one rhythm between settings sections: air, a hairline, air. */
@Composable
private fun SectionBreak() {
    Spacer(Modifier.height(Space.x32))
    Hairline()
    Spacer(Modifier.height(Space.x24))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingChoice(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Space.x8))
    // Pills that size to their label and wrap, left aligned with the rest of the
    // column. A lone option (one language) still reads as a chosen
    // value instead of a tab with nothing beside it.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.x8),
        verticalArrangement = Arrangement.spacedBy(Space.x8),
    ) {
        options.forEach { (value, title) ->
            val active = value == selected
            val shape = RoundedCornerShape(Radius.full)
            Row(
                modifier = Modifier
                    .heightIn(min = Sizes.touch)
                    .clip(shape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = Space.x16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(Sizes.iconSmall),
                    )
                    Spacer(Modifier.width(Space.x8))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
