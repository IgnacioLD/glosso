package com.glosso.app.platform

import androidx.compose.runtime.Composable

// The web has no on-device scorer (NoOnDeviceScoring), so nothing imports a file.
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (() -> Unit)? = null
