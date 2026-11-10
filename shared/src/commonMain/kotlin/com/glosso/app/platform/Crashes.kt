package com.glosso.app.platform

import com.glosso.app.CrashFrame

/**
 * Calls [onCrash] for an exception nobody caught, before the process ends, then
 * hands it to the handler installed before (which ends the process as usual).
 */
expect fun installUncaughtExceptionHandler(onCrash: (Throwable) -> Unit)

/** The exception's class name without its package; empty for an anonymous class. */
expect fun Throwable.typeName(): String

/** The exception's stack, top first, as class and method names only. */
expect fun Throwable.stackFrames(): List<CrashFrame>
