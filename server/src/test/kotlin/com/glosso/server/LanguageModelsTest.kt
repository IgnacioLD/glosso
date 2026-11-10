package com.glosso.server

import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageModelsTest {

    @Test
    fun `no setting means one model for every language`() {
        assertEquals(emptyMap(), languageModels(null))
        assertEquals(emptyMap(), languageModels("  "))
    }

    @Test
    fun `each listed language names its model file`() {
        assertEquals(
            mapOf("ru" to "base.onnx", "de" to "base.onnx"),
            languageModels("ru=base.onnx, DE = base.onnx"),
        )
    }

    @Test
    fun `an entry without a language, without a file or with a path is ignored`() {
        assertEquals(mapOf("ru" to "base.onnx"), languageModels("=x.onnx,fr=,de=../secret.onnx,ru=base.onnx,es"))
    }
}
