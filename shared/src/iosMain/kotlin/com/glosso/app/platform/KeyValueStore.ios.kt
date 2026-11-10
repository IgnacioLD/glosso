package com.glosso.app.platform

import platform.Foundation.NSUserDefaults

actual fun keyValueStore(context: PlatformContext, name: String): KeyValueStore =
    UserDefaultsStore(name, NSUserDefaults(suiteName = name))

/** One NSUserDefaults suite per store name, so stores never share keys. */
private class UserDefaultsStore(
    private val name: String,
    private val defaults: NSUserDefaults,
) : KeyValueStore {

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    override fun contains(key: String): Boolean = defaults.objectForKey(key) != null

    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        var clear = false
        val edits = mutableListOf<() -> Unit>()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                edits += { defaults.setObject(value, forKey = key) }
            }

            override fun putBoolean(key: String, value: Boolean) {
                edits += { defaults.setBool(value, forKey = key) }
            }

            override fun remove(key: String) {
                edits += { defaults.removeObjectForKey(key) }
            }

            override fun clear() {
                clear = true
            }
        }.block()
        // As SharedPreferences: a clear runs first, whatever its place in the block.
        if (clear) defaults.removePersistentDomainForName(name)
        edits.forEach { it() }
        if (commit) defaults.synchronize()
    }
}
