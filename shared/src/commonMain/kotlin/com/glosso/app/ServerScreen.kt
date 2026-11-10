package com.glosso.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Space
import com.glosso.app.download.LocalDownloader
import com.glosso.app.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Settings > Server: point the app at a self-hosted Glosso server, or back at
 * the default. An address is used only after [probeServer] confirms it is a
 * Glosso server; [ServerSwitch] then drops what belonged to the previous one.
 */
@Composable
fun ServerScreen(onBack: () -> Unit) {
    val services = LocalAppServices.current
    val downloader = LocalDownloader.current
    val debug = LocalAppConfig.current.debug
    val scope = rememberCoroutineScope()
    val base by ServerPreferences.base.collectAsState()
    val custom = base != ServerPreferences.defaultBase
    var input by rememberSaveable { mutableStateOf(if (custom) base else "") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }

    fun switchTo(url: String?) {
        scope.launch {
            working = true
            error = null
            saved = null
            ServerSwitch.apply(url, downloader, services.onDevice)
            saved = getString(Res.string.server_saved, ServerPreferences.base.value)
            working = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(stringResource(Res.string.server_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Spacer(Modifier.height(Space.x12))
            Text(
                text = stringResource(Res.string.server_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x24))
            SectionLabel(stringResource(Res.string.server_in_use))
            Spacer(Modifier.height(Space.x8))
            Text(
                text = base,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(if (custom) Res.string.server_custom else Res.string.server_default),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.x24))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                singleLine = true,
                enabled = !working,
                isError = error != null,
                label = { Text(stringResource(Res.string.server_address)) },
                placeholder = { Text("https://glosso.example.org") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.small,
                colors = glossoTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.x8))
            Text(
                text = stringResource(Res.string.server_switch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.x16))
            PrimaryButton(
                text = stringResource(Res.string.server_check_and_use),
                loading = working,
                enabled = input.isNotBlank() && input.trim().trimEnd('/') != base,
                onClick = {
                    scope.launch {
                        saved = null
                        val check = checkServerUrl(input, allowCleartext = debug)
                        if (check !is ServerUrlCheck.Valid) {
                            error = getString(
                                if (check == ServerUrlCheck.HttpsRequired) Res.string.server_error_https
                                else Res.string.server_error_invalid,
                            )
                            return@launch
                        }
                        input = check.url
                        working = true
                        error = null
                        when (probeServer(check.url)) {
                            ServerProbe.OK -> switchTo(check.url)
                            ServerProbe.UNREACHABLE -> error = getString(Res.string.server_error_unreachable)
                            ServerProbe.NOT_GLOSSO -> error = getString(Res.string.server_error_not_glosso)
                        }
                        if (error != null) working = false
                    }
                },
            )
            if (custom) {
                Spacer(Modifier.height(Space.x8))
                QuietTextButton(
                    text = stringResource(Res.string.server_use_default),
                    onClick = {
                        if (!working) {
                            input = ""
                            switchTo(null)
                        }
                    },
                    modifier = Modifier.offset(x = -Space.x12),
                )
            }
            saved?.let {
                Spacer(Modifier.height(Space.x12))
                StatusMessage(it, GlossoColors.accentText)
            }
            error?.let {
                Spacer(Modifier.height(Space.x12))
                StatusMessage(it, MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Space.x40))
        }
    }
}
