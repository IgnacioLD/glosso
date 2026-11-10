package com.glosso.app.platform

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.FileNotFoundException
import okio.source

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (() -> Unit)? {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            latest(
                uris.map { uri ->
                    PickedFile { resolver.openInputStream(uri)?.source() ?: throw FileNotFoundException(uri.toString()) }
                },
            )
        }
    }
    // The model has no registered MIME type, so every file is offered.
    return { launcher.launch(arrayOf("*/*")) }
}
