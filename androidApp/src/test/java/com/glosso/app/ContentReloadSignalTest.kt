package com.glosso.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentReloadSignalTest {

    @Test
    fun `reloads on a target language change and on an interface language change`() = runTest {
        val target = MutableStateFlow("en")
        val ui = MutableStateFlow("en")
        var reloads = 0
        // backgroundScope keeps the collector alive for the whole test.
        backgroundScope.launch {
            contentReloadSignal(target, ui).collect { reloads++ }
        }

        runCurrent()
        assertEquals("initial load", 1, reloads)

        target.value = "fr"
        runCurrent()
        assertEquals("target language change reloads", 2, reloads)

        ui.value = "de"
        runCurrent()
        assertEquals("interface language change reloads", 3, reloads)

        ui.value = "es"
        runCurrent()
        assertEquals("every interface language change reloads", 4, reloads)
    }
}
