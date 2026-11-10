package com.glosso.app

import coil3.Uri
import coil3.fetch.Fetcher
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.glosso.app.platform.PlatformContext

internal actual fun coilContext(context: PlatformContext): coil3.PlatformContext = coil3.PlatformContext.INSTANCE

internal actual fun imageNetworkFetcher(): Fetcher.Factory<Uri> = KtorNetworkFetcherFactory()
