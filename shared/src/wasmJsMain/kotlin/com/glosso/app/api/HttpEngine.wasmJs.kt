package com.glosso.app.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

actual fun defaultHttpEngine(): HttpClientEngine = Js.create {}
