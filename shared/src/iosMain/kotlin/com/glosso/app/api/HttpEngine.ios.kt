package com.glosso.app.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

actual fun defaultHttpEngine(): HttpClientEngine = Darwin.create {}
