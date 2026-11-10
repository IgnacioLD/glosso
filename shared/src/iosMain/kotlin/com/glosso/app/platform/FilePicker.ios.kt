package com.glosso.app.platform

import androidx.compose.runtime.Composable

// iOS has no on-device scorer yet (NoOnDeviceScoring), so nothing imports a file.
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (() -> Unit)? = null
