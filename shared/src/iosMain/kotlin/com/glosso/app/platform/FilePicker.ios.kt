package com.glosso.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import okio.FileSystem
import okio.ForwardingSource
import okio.Path.Companion.toPath
import okio.Source
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/**
 * The Files picker, opening the files in place: the model is hundreds of MB, so
 * copying it into the app's inbox first (`asCopy`) would need twice the space.
 */
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (() -> Unit)? {
    val controller = LocalUIViewController.current
    val latest by rememberUpdatedState(onPicked)
    // UIKit holds the delegate weakly; remembering it keeps it alive while the picker is up.
    val delegate = remember { PickerDelegate { latest(it) } }
    return {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem))
        picker.allowsMultipleSelection = true
        picker.delegate = delegate
        controller.presentViewController(picker, animated = true, completion = null)
    }
}

private class PickerDelegate(
    private val onPicked: (List<PickedFile>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val files = didPickDocumentsAtURLs.filterIsInstance<NSURL>().map { url -> PickedFile { openScoped(url) } }
        if (files.isNotEmpty()) onPicked(files)
    }
}

/** A file outside the sandbox is readable only between start and stop of its security scope. */
private fun openScoped(url: NSURL): Source {
    val path = url.path ?: error("Picked file has no path: $url")
    val scoped = url.startAccessingSecurityScopedResource()
    val source = try {
        FileSystem.SYSTEM.source(path.toPath())
    } catch (e: Exception) {
        if (scoped) url.stopAccessingSecurityScopedResource()
        throw e
    }
    return object : ForwardingSource(source) {
        override fun close() {
            try {
                super.close()
            } finally {
                if (scoped) url.stopAccessingSecurityScopedResource()
            }
        }
    }
}
