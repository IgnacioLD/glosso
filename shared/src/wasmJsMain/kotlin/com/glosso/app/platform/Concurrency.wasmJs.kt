package com.glosso.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// A browser page runs Kotlin on one thread, so there is nothing to exclude.
actual fun Lock(): Lock = NoLock

private object NoLock : Lock {
    override fun <T> withLock(block: () -> T): T = block()
}

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default
