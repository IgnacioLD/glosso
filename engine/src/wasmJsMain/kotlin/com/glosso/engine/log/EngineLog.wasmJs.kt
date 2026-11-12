package com.glosso.engine.log

internal actual fun printErrorLine(line: String) {
    consoleError(line)
}

private fun consoleError(line: String): Unit = js("console.error(line)")
