package com.glosso.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSRecursiveLock

actual fun Lock(): Lock = FoundationLock()

private class FoundationLock : Lock {
    private val lock = NSRecursiveLock()

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO
