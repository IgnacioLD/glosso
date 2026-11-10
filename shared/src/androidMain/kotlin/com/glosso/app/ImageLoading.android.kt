package com.glosso.app

import coil3.Uri
import coil3.fetch.Fetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.glosso.app.platform.PlatformContext

internal actual fun coilContext(context: PlatformContext): coil3.PlatformContext = context

internal actual fun imageNetworkFetcher(): Fetcher.Factory<Uri> = OkHttpNetworkFetcherFactory()
