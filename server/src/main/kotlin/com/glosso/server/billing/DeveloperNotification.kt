package com.glosso.server.billing

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The DeveloperNotification Google publishes to Pub/Sub for Play RTDN. Only the
 * fields needed to identify the purchase and the change are modelled; unknown
 * members are ignored so new notification kinds do not break decoding.
 */
@Serializable
data class DeveloperNotification(
    val version: String? = null,
    val packageName: String? = null,
    val eventTimeMillis: String? = null,
    val subscriptionNotification: SubscriptionNotification? = null,
    val testNotification: TestNotification? = null,
    val voidedPurchaseNotification: VoidedPurchaseNotification? = null,
)

@Serializable
data class SubscriptionNotification(
    val version: String? = null,
    val notificationType: Int = 0,
    val purchaseToken: String? = null,
    val subscriptionId: String? = null,
)

@Serializable
data class TestNotification(val version: String? = null)

@Serializable
data class VoidedPurchaseNotification(
    val purchaseToken: String? = null,
    val orderId: String? = null,
    val productType: Int = 0,
    val refundType: Int = 0,
)

/**
 * The RTDN subscription notification types the server reacts to. The rest are
 * acknowledged but need no special handling: invalidating the cache and reading
 * the fresh state from Play is correct for all of them.
 */
object NotificationType {
    const val RECOVERED = 1
    const val RENEWED = 2
    const val CANCELED = 3
    const val PURCHASED = 4
    const val ON_HOLD = 5
    const val IN_GRACE_PERIOD = 6
    const val RESTARTED = 7
    const val PRICE_CHANGE_CONFIRMED = 8
    const val DEFERRED = 9
    const val PAUSED = 10
    const val PAUSE_SCHEDULE_CHANGED = 11
    const val REVOKED = 12
    const val EXPIRED = 13
}

/** Decoding for the base64 payload inside a Pub/Sub push message. */
object RtdnDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decodes the `message.data` field (base64 DeveloperNotification JSON).
     * Returns null when it is not valid base64 or not valid JSON, so the caller
     * can answer with a clear 400 instead of crashing.
     */
    fun decode(base64Data: String): DeveloperNotification? = runCatching {
        val bytes = Base64.getDecoder().decode(base64Data)
        json.decodeFromString<DeveloperNotification>(String(bytes, Charsets.UTF_8))
    }.getOrNull()
}
