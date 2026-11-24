package com.glosso.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import com.glosso.engine.model.LanguagePack
import org.jetbrains.compose.resources.stringResource

/**
 * Points the whole app at another course: content, voices and scoring follow
 * [LanguagePreferences.code]. The one place that does it, so Settings and the
 * header switcher cannot drift apart.
 */
internal fun switchCourse(pack: LanguagePack) {
    if (pack.code == LanguagePreferences.code.value) return
    UsageEvents.settingChanged("target_language", pack.code)
    LanguagePreferences.set(pack.code)
    VoicePreferences.onLanguageChanged(pack)
}

/**
 * The course being studied, as a header button that opens the list of courses.
 * Someone learning two languages switches from the first screen instead of
 * digging into Settings; with a single course on offer there is nothing to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CourseSwitcher() {
    val packs = OfferedLanguages.all
    if (packs.size < 2) return
    val current by LanguagePreferences.code.collectAsState()
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .heightIn(min = Sizes.touch)
            .clickable { open = true }
            .padding(vertical = Space.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = current.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = GlossoColors.accentText,
        )
        Spacer(Modifier.width(Space.x2))
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = stringResource(Res.string.settings_target_language),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = { open = false },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter)
                .padding(bottom = Space.x24),
        ) {
            SectionLabel(stringResource(Res.string.settings_target_language))
            Spacer(Modifier.height(Space.x8))
            packs.forEach { pack ->
                val selected = pack.code == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = {
                                switchCourse(pack)
                                open = false
                            },
                        )
                        .padding(vertical = Space.x16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = languageDisplay(pack),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Hairline()
            }
        }
    }
}
