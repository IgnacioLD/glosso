package com.glosso.app.api

import io.ktor.client.engine.HttpClientEngine

/** The platform's HTTP engine: Android's on Android, Darwin (NSURLSession) on iOS. */
expect fun defaultHttpEngine(): HttpClientEngine
