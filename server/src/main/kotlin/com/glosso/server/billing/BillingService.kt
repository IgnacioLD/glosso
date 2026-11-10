package com.glosso.server.billing

import com.glosso.server.api.BillingVerifyRequest
import com.glosso.server.api.BillingVerifyResponse
import com.glosso.server.api.PubSubPushEnvelope
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.security.MessageDigest
import java.util.Base64

private val logger = KotlinLogging.logger("GlossoBilling")

/**
 * Server-side billing configuration. Everything here is owned by the deployment
 * (environment variables on the VPS), never committed; see docs/BILLING-E2E.md.
 */
data class BillingConfig(
    /** Play package the server validates. */
    val packageName: String,
    /** The single Pro subscription this server grants. */
    val productId: String,
    /** Offer id of the 1-month-free trial, so ACTIVE can be reported as TRIAL. */
    val trialOfferId: String?,
    /** How long a verified verdict is trusted before Play is asked again. */
    val cacheTtlMillis: Long,
    /** Shared secret Pub/Sub must present on the RTDN webhook; null disables it. */
    val rtdnSecret: String?,
    /** Expected Pub/Sub push subscription path, checked when set. */
    val rtdnSubscription: String?,
)

sealed interface VerifyOutcome {
    data class Verdict(
        val response: BillingVerifyResponse,
        /** Play's `autoRenewing`, kept out of the wire response so it can be persisted. */
        val autoRenew: Boolean = false,
    ) : VerifyOutcome
    data class Invalid(val message: String) : VerifyOutcome
    data class Unavailable(val message: String) : VerifyOutcome
}

sealed interface RtdnOutcome {
    /**
     * The notification was authentic and processed. A test notification, or a
     * payload with no subscription token, leaves [purchaseToken] and
     * [entitlement] null; otherwise the caller persists the new state (a refund
     * must deny entitlement even before the client re-verifies).
     */
    data class Accepted(
        val purchaseToken: String? = null,
        val entitlement: Entitlement? = null,
    ) : RtdnOutcome
    data class Rejected(val message: String) : RtdnOutcome
}

/**
 * Verifies Play purchases against the Play Developer API and caches the verdict.
 *
 * The client is never trusted: without a successful Play lookup the answer is
 * "not entitled" (or a 503 when the server itself cannot verify), never Pro.
 * RTDN notifications invalidate the cache and refresh the state immediately, so
 * a refund, cancellation or hold is reflected without waiting for the TTL.
 */
class BillingService(
    private val client: PlaySubscriptionsClient,
    val config: BillingConfig,
    private val cache: SubscriptionCache = SubscriptionCache(config.cacheTtlMillis),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    /** True when a service account is configured, so Play can be queried. */
    val verifyEnabled: Boolean get() = client.configured

    /** True when a shared secret is configured, so the RTDN webhook can run. */
    val rtdnEnabled: Boolean get() = !config.rtdnSecret.isNullOrBlank()

    suspend fun verify(request: BillingVerifyRequest): VerifyOutcome {
        val token = request.purchaseToken.trim()
        if (token.isEmpty()) return VerifyOutcome.Invalid("purchaseToken is required")
        if (request.packageName.isBlank() || request.packageName != config.packageName) {
            return VerifyOutcome.Invalid("packageName must be ${config.packageName}")
        }
        if (request.productId.isBlank() || request.productId != config.productId) {
            return VerifyOutcome.Invalid("productId must be ${config.productId}")
        }
        if (!client.configured) {
            return VerifyOutcome.Unavailable(
                "Server-side purchase verification is not configured. See docs/BILLING-E2E.md.",
            )
        }

        cache.get(token)?.let { return VerifyOutcome.Verdict(it.toResponse(), it.autoRenew) }

        val entitlement = try {
            val purchase = client.getSubscription(config.packageName, token)
            SubscriptionVerifier.verify(purchase, request.productId, config.trialOfferId, clock())
        } catch (e: BillingNotConfiguredException) {
            return VerifyOutcome.Unavailable(e.message ?: "Verification is not configured.")
        } catch (e: PlayApiException) {
            log("Play verification failed: ${e.message}")
            return VerifyOutcome.Unavailable(
                "Could not verify the purchase with Google Play right now. Try again later.",
            )
        }
        cache.put(token, entitlement)
        return VerifyOutcome.Verdict(entitlement.toResponse(), entitlement.autoRenew)
    }

    /** Constant-time check of the shared secret Pub/Sub must present. */
    fun rtdnAuthorized(provided: String?): Boolean {
        val secret = config.rtdnSecret ?: return false
        if (provided.isNullOrEmpty()) return false
        return MessageDigest.isEqual(provided.toByteArray(), secret.toByteArray())
    }

    suspend fun handleRtdn(envelope: PubSubPushEnvelope): RtdnOutcome {
        if (!rtdnEnabled) return RtdnOutcome.Rejected("RTDN is not configured")
        if (!config.rtdnSubscription.isNullOrBlank() &&
            envelope.subscription != null &&
            envelope.subscription != config.rtdnSubscription
        ) {
            return RtdnOutcome.Rejected("unexpected Pub/Sub subscription")
        }

        val notification = RtdnDecoder.decode(envelope.message.data)
            ?: return RtdnOutcome.Rejected("message.data is not a valid DeveloperNotification")
        if (!notification.packageName.isNullOrBlank() &&
            notification.packageName != config.packageName
        ) {
            return RtdnOutcome.Rejected("notification is for package ${notification.packageName}")
        }
        // A test notification proves the topic is wired; acknowledge it.
        if (notification.testNotification != null) return RtdnOutcome.Accepted()

        val subscription = notification.subscriptionNotification
        // Same normalization as verify: the cache and the persisted hash must
        // key purchases identically even if Play ever pads the token.
        val token = (subscription?.purchaseToken
            ?: notification.voidedPurchaseNotification?.purchaseToken)
            ?.trim()
            ?: return RtdnOutcome.Accepted()

        // A revoked or voided purchase loses entitlement at once, without waiting
        // for Play: this is the fail-closed path for refunds.
        val revoked = subscription?.notificationType == NotificationType.REVOKED ||
            notification.voidedPurchaseNotification != null
        if (revoked) {
            val denied = Entitlement(EntitlementStatus.NONE, false, null)
            cache.put(token, denied)
            log("RTDN revoked/voided purchase; cached not entitled")
            return RtdnOutcome.Accepted(purchaseToken = token, entitlement = denied)
        }

        cache.invalidate(token)
        var refreshed: Entitlement? = null
        if (client.configured) {
            runCatching {
                val purchase = client.getSubscription(config.packageName, token)
                val entitlement = SubscriptionVerifier.verify(purchase, config.productId, config.trialOfferId, clock())
                cache.put(token, entitlement)
                refreshed = entitlement
                log(
                    "RTDN type=${subscription?.notificationType?.let(::notificationName) ?: "voided"} " +
                        "refreshed to ${entitlement.status}",
                )
            }.onFailure { log("RTDN refresh failed: ${it.message}") }
        }
        return RtdnOutcome.Accepted(purchaseToken = token, entitlement = refreshed)
    }

    private fun Entitlement.toResponse() = BillingVerifyResponse(
        entitled = entitled,
        status = status.name,
        // A signed offline entitlement token needs an asymmetric keypair (private
        // on the server, public in the app) and is a separate step: see
        // docs/BILLING-E2E.md. The live verdict and its expiry are returned now.
        entitlementToken = null,
        expiresAtMillis = expiresAtMillis,
    )

    companion object {
        private const val DEFAULT_PACKAGE = "me.shirobyte42.glosso"
        private const val DEFAULT_PRODUCT_ID = "com.glosso.app.pro"
        private const val DEFAULT_CACHE_TTL_SECONDS = 600L

        /**
         * Builds the service from the environment. Never throws: a missing or
         * broken service account leaves verification disabled, and the endpoint
         * answers "cannot verify" rather than granting Pro.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): BillingService {
            val config = BillingConfig(
                packageName = env["GLOSSO_PLAY_PACKAGE"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PACKAGE,
                productId = env["GLOSSO_PLAY_PRODUCT_ID"]?.takeIf { it.isNotBlank() } ?: DEFAULT_PRODUCT_ID,
                trialOfferId = env["GLOSSO_PLAY_TRIAL_OFFER_ID"]?.takeIf { it.isNotBlank() },
                cacheTtlMillis = (env["GLOSSO_BILLING_CACHE_TTL_SECONDS"]?.toLongOrNull()
                    ?.coerceIn(0, 86_400) ?: DEFAULT_CACHE_TTL_SECONDS) * 1000,
                rtdnSecret = env["GLOSSO_RTDN_SECRET"]?.takeIf { it.isNotBlank() },
                rtdnSubscription = env["GLOSSO_RTDN_SUBSCRIPTION"]?.takeIf { it.isNotBlank() },
            )

            val client = serviceAccountJson(env)?.let { text ->
                runCatching {
                    GooglePlaySubscriptionsClient(ServiceAccountTokenProvider(ServiceAccountKey.fromJson(text)))
                }.onFailure {
                    logger.error(it) { "Invalid Play service account; verification disabled" }
                }.getOrNull()
            }
            if (client == null) {
                logger.info {
                    "Play verification disabled: set GLOSSO_PLAY_SERVICE_ACCOUNT_JSON (or _BASE64)"
                }
            }
            if (config.rtdnSecret == null) {
                logger.info { "RTDN webhook disabled: set GLOSSO_RTDN_SECRET" }
            }
            return BillingService(
                client = client ?: UnavailablePlaySubscriptionsClient,
                config = config,
                log = { logger.info { it } },
            )
        }

        private fun serviceAccountJson(env: Map<String, String>): String? {
            env["GLOSSO_PLAY_SERVICE_ACCOUNT_JSON"]?.takeIf { it.isNotBlank() }?.let { path ->
                val file = File(path)
                if (file.isFile) return file.readText()
                logger.error {
                    "GLOSSO_PLAY_SERVICE_ACCOUNT_JSON points to a missing file; verification disabled"
                }
                return null
            }
            return env["GLOSSO_PLAY_SERVICE_ACCOUNT_JSON_BASE64"]?.takeIf { it.isNotBlank() }?.let { encoded ->
                runCatching { String(Base64.getDecoder().decode(encoded)) }
                    .onFailure { logger.error(it) { "GLOSSO_PLAY_SERVICE_ACCOUNT_JSON_BASE64 is not valid base64" } }
                    .getOrNull()
            }
        }
    }
}

private fun notificationName(type: Int): String = when (type) {
    NotificationType.RECOVERED -> "RECOVERED"
    NotificationType.RENEWED -> "RENEWED"
    NotificationType.CANCELED -> "CANCELED"
    NotificationType.PURCHASED -> "PURCHASED"
    NotificationType.ON_HOLD -> "ON_HOLD"
    NotificationType.IN_GRACE_PERIOD -> "IN_GRACE_PERIOD"
    NotificationType.RESTARTED -> "RESTARTED"
    NotificationType.PRICE_CHANGE_CONFIRMED -> "PRICE_CHANGE_CONFIRMED"
    NotificationType.DEFERRED -> "DEFERRED"
    NotificationType.PAUSED -> "PAUSED"
    NotificationType.PAUSE_SCHEDULE_CHANGED -> "PAUSE_SCHEDULE_CHANGED"
    NotificationType.REVOKED -> "REVOKED"
    NotificationType.EXPIRED -> "EXPIRED"
    else -> "UNKNOWN($type)"
}
