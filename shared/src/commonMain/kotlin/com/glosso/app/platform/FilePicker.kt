package com.glosso.app.platform

import androidx.compose.runtime.Composable
import okio.Source

/** A file the learner picked with the system picker; each [open] reads it from the start. */
fun interface PickedFile {
    fun open(): Source
}

/**
 * Launches the system file picker for one or more files and hands them to
 * [onPicked] (never with an empty list). No storage permission: on Android it is
 * the Storage Access Framework. Null where the platform offers no picker yet.
 */
@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (() -> Unit)?
