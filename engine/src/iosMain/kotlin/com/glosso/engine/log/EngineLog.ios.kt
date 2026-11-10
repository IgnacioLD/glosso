package com.glosso.engine.log

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fputs
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
internal actual fun printErrorLine(line: String) {
    fputs(line + "\n", stderr)
}
