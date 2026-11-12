package com.glosso.app.platform

import kotlinx.coroutines.CoroutineDispatcher

/** A mutual-exclusion lock for state touched from several threads (a JVM monitor on Android). */
interface Lock {
    fun <T> withLock(block: () -> T): T
}

expect fun Lock(): Lock

/** Where blocking file and network work runs: `Dispatchers.IO`, or the one thread a browser has. */
expect val ioDispatcher: CoroutineDispatcher
