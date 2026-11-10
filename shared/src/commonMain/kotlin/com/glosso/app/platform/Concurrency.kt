package com.glosso.app.platform

/** A mutual-exclusion lock for state touched from several threads (a JVM monitor on Android). */
interface Lock {
    fun <T> withLock(block: () -> T): T
}

expect fun Lock(): Lock
