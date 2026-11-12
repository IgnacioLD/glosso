package com.glosso.app.platform

import kotlinx.browser.localStorage

actual fun keyValueStore(context: PlatformContext, name: String): KeyValueStore = LocalStorageStore(name)

/**
 * One key prefix per store name in `localStorage`, so stores never share keys.
 * `localStorage` writes synchronously, so every edit is already a commit.
 */
internal class LocalStorageStore(name: String) : KeyValueStore {
    private val prefix = "$name/"

    override fun getString(key: String): String? = localStorage.getItem(prefix + key)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        localStorage.getItem(prefix + key)?.toBooleanStrictOrNull() ?: default

    override fun contains(key: String): Boolean = localStorage.getItem(prefix + key) != null

    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        var clear = false
        val edits = mutableListOf<() -> Unit>()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                edits += { localStorage.setItem(prefix + key, value) }
            }

            override fun putBoolean(key: String, value: Boolean) {
                edits += { localStorage.setItem(prefix + key, value.toString()) }
            }

            override fun remove(key: String) {
                edits += { localStorage.removeItem(prefix + key) }
            }

            override fun clear() {
                clear = true
            }
        }.block()
        // As SharedPreferences: a clear runs first, whatever its place in the block.
        if (clear) {
            (0 until localStorage.length).mapNotNull { localStorage.key(it) }
                .filter { it.startsWith(prefix) }
                .forEach { localStorage.removeItem(it) }
        }
        edits.forEach { it() }
    }
}
