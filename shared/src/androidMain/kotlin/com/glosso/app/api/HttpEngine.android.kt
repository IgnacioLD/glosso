package com.glosso.app.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.android.Android

actual fun defaultHttpEngine(): HttpClientEngine = Android.create {}
