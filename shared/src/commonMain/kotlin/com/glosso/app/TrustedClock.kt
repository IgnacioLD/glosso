package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.currentTimeMillis
import com.glosso.app.platform.keyValueStore
import com.glosso.app.platform.monotonicMillis
import kotlin.concurrent.Volatile

/**
 * The time the app counts days by. The device clock can be set to any date, which
 * let a learner back-date practice, keep parallel streaks or read "26746 days
 * since your last practice". The server's `Date` header anchors the time, and the
 * monotonic clock (untouched by clock changes) carries it forward while offline.
 * With no anchor yet in this run, the device clock is used, but never earlier than
 * the latest trusted time seen, and not at all once the network has shown that
 * the clock is wrong.
 */
object TrustedClock {

    private const val FILE = "glosso_trusted_clock"
    private const val KEY_FLOOR = "floor_ms"

    /** A device clock this far from trusted time is wrong, not a time zone or drift. */
    const val WRONG_CLOCK_MS: Long = 24L * 60 * 60 * 1000

    private class Anchor(val serverMillis: Long, val monotonic: Long)

    @Volatile
    private var anchor: Anchor? = null

    @Volatile
    private var floorMillis: Long = 0L

    /** A TLS failure on certificate dates: proof the device clock is off, with no server time to use. */
    @Volatile
    private var certificateDateFailure = false

    private var store: KeyValueStore? = null

    fun init(context: PlatformContext) {
        if (store != null) return
        val loaded = keyValueStore(context, FILE)
        store = loaded
        floorMillis = maxOf(floorMillis, loaded.getString(KEY_FLOOR)?.toLongOrNull() ?: 0L)
    }

    /** A server answered with its time: anchor to it, and raise the floor. */
    fun onServerTime(serverMillis: Long) {
        anchor = Anchor(serverMillis, monotonicMillis())
        certificateDateFailure = false
        if (serverMillis > floorMillis) {
            floorMillis = serverMillis
            store?.edit { putString(KEY_FLOOR, serverMillis.toString()) }
        }
    }

    /** A request failed; a certificate rejected on its dates means the device clock is wrong. */
    fun onNetworkFailure(error: Throwable) {
        if (isCertificateDateFailure(error)) certificateDateFailure = true
    }

    // The monotonic clock is read only with an anchor: nothing else needs it.
    fun nowMillis(): Long = anchor.let {
        trustedNow(it?.serverMillis, it?.monotonic, if (it != null) monotonicMillis() else 0, currentTimeMillis(), floorMillis, certificateDateFailure)
    }

    /** True when the device clock is far enough off that network errors are the clock's doing. */
    fun deviceClockLooksWrong(): Boolean = anchor.let {
        clockLooksWrong(it?.serverMillis, it?.monotonic, if (it != null) monotonicMillis() else 0, currentTimeMillis(), floorMillis, certificateDateFailure)
    }

    /**
     * The trusted time: the anchor carried forward by the monotonic clock when there
     * is one; otherwise the device clock, but never before [floor], and the floor
     * itself when certificates have shown the device clock is wrong.
     */
    internal fun trustedNow(
        anchorServer: Long?,
        anchorMonotonic: Long?,
        monotonic: Long,
        device: Long,
        floor: Long,
        certificateDateFailure: Boolean,
    ): Long = when {
        anchorServer != null && anchorMonotonic != null -> anchorServer + (monotonic - anchorMonotonic).coerceAtLeast(0)
        certificateDateFailure && floor > 0 -> floor
        else -> maxOf(device, floor)
    }

    internal fun clockLooksWrong(
        anchorServer: Long?,
        anchorMonotonic: Long?,
        monotonic: Long,
        device: Long,
        floor: Long,
        certificateDateFailure: Boolean,
    ): Boolean {
        if (certificateDateFailure) return true
        if (anchorServer != null && anchorMonotonic != null) {
            val trusted = trustedNow(anchorServer, anchorMonotonic, monotonic, device, floor, false)
            return kotlin.math.abs(device - trusted) > WRONG_CLOCK_MS
        }
        return floor > 0 && device < floor - WRONG_CLOCK_MS
    }

    /**
     * The platform TLS errors for a certificate outside its validity dates: the JVM's
     * CertificateExpired/NotYetValid exceptions, and URLSession's codes -1201
     * (bad date) and -1204 (not yet valid).
     */
    internal fun isCertificateDateFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { cause ->
            val text = "${cause::class.simpleName} ${cause.message}"
            listOf("CertificateExpired", "NotYetValid", "timestamp check failed", "BadDate", "-1201", "-1204")
                .any { it in text }
        }
}
