package com.glosso.app.platform

/**
 * A small, named store of preferences: SharedPreferences on Android (the file
 * name is [keyValueStore]'s `name`, so existing installs keep their data) and one
 * NSUserDefaults suite per name on iOS. Only the types the app stores are here.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun contains(key: String): Boolean

    /**
     * Applies the edits in [block] together. By default they are written in the
     * background; [commit] writes them before returning, for the few values that
     * must survive a process killed right after (the device id, a crash).
     * As on Android, a [Editor.clear] runs before the other edits of the block.
     */
    fun edit(commit: Boolean = false, block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
        fun clear()
    }
}

/** The store called [name] (the SharedPreferences file name on Android). */
expect fun keyValueStore(context: PlatformContext, name: String): KeyValueStore
