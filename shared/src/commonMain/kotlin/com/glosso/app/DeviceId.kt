package com.glosso.app

import com.glosso.app.platform.Lock
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The device identity, decided by the product contract: there are no accounts
 * yet, so a random UUID generated on first launch is who the user is. It is
 * persisted in `glosso_device_id` and sent as `X-Device-Id` on the calls the
 * server keys per user (config, me, scoring and events).
 *
 * It is deliberately opaque and local: it is not a device fingerprint, carries
 * no hardware data and is never derived from anything the user could be
 * identified by outside Glosso.
 */
object DeviceId {

    private const val FILE = "glosso_device"
    private const val KEY = "glosso_device_id"

    private val lock = Lock()

    @Volatile
    private var value: String? = null

    /**
     * Reads the persisted id or creates it once. Safe to call from every entry
     * point: it always re-reads the store, so a second call returns the same id
     * and a cleared store is regenerated instead of keeping a stale value.
     *
     * The first write is a synchronous `commit`, not `apply`: a process killed
     * right after the first launch must still find its id on the next one, or a
     * fresh UUID would look like a new user to the server. [init] holds a lock
     * so two entry points cannot each generate one.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun init(context: PlatformContext) = lock.withLock {
        val store = keyValueStore(context, FILE)
        val existing = store.getString(KEY)?.takeIf { it.isNotBlank() }
        if (existing != null) {
            value = existing
            return@withLock
        }
        val created = Uuid.random().toString()
        value = created
        store.edit(commit = true) { putString(KEY, created) }
    }

    /** The id, or null before [init] (tests without a context). */
    fun valueOrNull(): String? = value
}
