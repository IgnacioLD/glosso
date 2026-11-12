package com.glosso.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.glosso.app.download.Downloader
import com.glosso.app.download.LocalDownloader
import com.glosso.app.platform.Connectivity
import com.glosso.app.platform.PlatformContext

/**
 * What the shared ViewModels and screens are built from: the platform pieces
 * the host (the Android Activity, later the iOS view controller) owns. One per
 * process; [ProvideAppServices] hands it to the UI, and each ViewModel takes it
 * in its constructor instead of reaching for an `Application`.
 */
class AppServices(
    /** The application's context, for the stores that take one. */
    val context: PlatformContext,
    val config: AppConfig,
    val connectivity: Connectivity,
    /** The optional on-device scorer ([NoOnDeviceScoring] where there is none). */
    val onDevice: OnDeviceScoring,
    val downloader: Downloader,
)

/** The process's [AppServices], provided by [ProvideAppServices]. */
val LocalAppServices = staticCompositionLocalOf<AppServices> { error("LocalAppServices is not provided") }

/** Provides [services], its [AppConfig] and its [Downloader] to [content]. */
@Composable
fun ProvideAppServices(services: AppServices, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAppServices provides services,
        LocalAppConfig provides services.config,
        LocalDownloader provides services.downloader,
        content = content,
    )
}
