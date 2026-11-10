package com.glosso.app.platform

import android.content.Context
import android.content.SharedPreferences

actual fun keyValueStore(context: PlatformContext, name: String): KeyValueStore =
    SharedPreferencesStore((context.applicationContext ?: context).getSharedPreferences(name, Context.MODE_PRIVATE))

/** A [KeyValueStore] over one SharedPreferences file, with its exact semantics. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun edit(commit: Boolean, block: KeyValueStore.Editor.() -> Unit) {
        val editor = prefs.edit()
        object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) {
                editor.putString(key, value)
            }

            override fun putBoolean(key: String, value: Boolean) {
                editor.putBoolean(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }

            override fun clear() {
                editor.clear()
            }
        }.block()
        if (commit) editor.commit() else editor.apply()
    }
}
