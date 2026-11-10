package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSettingTest {

    private fun valid(input: String, debug: Boolean = false) =
        (checkServerUrl(input, debug) as? ServerUrlCheck.Valid)?.url

    @Test
    fun `addresses are normalized`() {
        assertEquals("https://glosso.example.org", valid("glosso.example.org"))
        assertEquals("https://glosso.example.org", valid("  HTTPS://Glosso.Example.org/// "))
        assertEquals("https://example.org:8443/glosso", valid("https://example.org:8443/glosso/"))
        assertEquals("http://10.0.2.2:8080", valid("http://10.0.2.2:8080/", debug = true))
    }

    @Test
    fun `malformed addresses are refused`() {
        listOf(
            "", "   ", "ftp://example.org", "https://", "https://exa mple.org", "https://user@example.org",
            "https://example.org/?q=1", "https://example.org/#x", "https://example.org:0", "https://example.org:70000",
            "https://.example.org", "https://example..org", "javascript:alert(1)",
        ).forEach { assertEquals(ServerUrlCheck.Invalid, checkServerUrl(it, allowCleartext = true), it) }
    }

    @Test
    fun `plain http needs a debug build and a local host`() {
        assertEquals(ServerUrlCheck.HttpsRequired, checkServerUrl("http://10.0.2.2:8080", allowCleartext = false))
        assertEquals(ServerUrlCheck.HttpsRequired, checkServerUrl("http://example.org", allowCleartext = true))
        assertEquals(ServerUrlCheck.HttpsRequired, checkServerUrl("http://8.8.8.8", allowCleartext = true))
        listOf("localhost", "127.0.0.1", "10.0.2.2", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.20", "[::1]")
            .forEach { assertTrue(isLocalHost(it), it) }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "11.0.0.1", "256.1.1.1", "localhost.example.org", "10.0.2")
            .forEach { assertFalse(isLocalHost(it), it) }
    }

    @Test
    fun `the choice persists and falls back to the default`() {
        val store = MemoryStore()
        ServerPreferences.init(store, "https://api.example.org/")
        assertEquals("https://api.example.org", ServerPreferences.base.value)
        assertFalse(ServerPreferences.isCustom)

        ServerPreferences.set("https://mine.example.org")
        assertEquals("https://mine.example.org", store.values["base_url"])
        ServerPreferences.init(store, "https://api.example.org")
        assertEquals("https://mine.example.org", ServerPreferences.base.value)
        assertTrue(ServerPreferences.isCustom)

        ServerPreferences.set(null)
        assertNull(store.values["base_url"])
        assertEquals("https://api.example.org", ServerPreferences.base.value)

        // Choosing the default by address stores nothing, so a new default in a later build applies.
        ServerPreferences.set("https://api.example.org")
        assertNull(store.values["base_url"])
    }

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, Any>()
        override fun getString(key: String): String? = values[key] as? String
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override fun contains(key: String): Boolean = key in values
        override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
            object : KeyValueStore.Editor {
                override fun putString(key: String, value: String) { values[key] = value }
                override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
                override fun clear() = values.clear()
            }.block()
        }
    }
}
