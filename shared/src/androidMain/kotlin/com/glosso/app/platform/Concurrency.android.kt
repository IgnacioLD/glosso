package com.glosso.app.platform

actual fun Lock(): Lock = MonitorLock()

private class MonitorLock : Lock {
    override fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
