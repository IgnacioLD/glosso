package com.glosso.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun Lock(): Lock = MonitorLock()

private class MonitorLock : Lock {
    override fun <T> withLock(block: () -> T): T = synchronized(this, block)
}

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO
