package com.glosso.server.billing

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class AutoRenewingPlanDto(val autoRenewEnabled: Boolean = false)

@Serializable
private data class OfferDetailsDto(val basePlanId: String? = null, val offerId: String? = null)

@Serializable
private data class PlayLineItemDto(
    val productId: String = "",
    val expiryTime: String? = null,
    val autoRenewingPlan: AutoRenewingPlanDto? = null,
    val offerDetails: OfferDetailsDto? = null,
)

@Serializable
private data class CanceledContextDto(val cancelTime: String? = null)

@Serializable
private data class SubscriptionPurchaseV2Dto(
    val subscriptionState: String = "",
    val lineItems: List<PlayLineItemDto> = emptyList(),
    val canceledContext: CanceledContextDto? = null,
    val acknowledgementState: String? = null,
)

/**
 * Real Play Developer API client: `purchases.subscriptionsv2.get`, authenticated
 * with a service account access token.
 *
 * A 400 or 404 means Play does not know the token (or it does not belong to the
 * package), which is a verdict, not an outage: it is returned as null and mapped
 * to "not entitled". Every other non-2xx is thrown, so the caller fails closed.
 */
class GooglePlaySubscriptionsClient(
    private val tokenProvider: ServiceAccountTokenProvider,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) : PlaySubscriptionsClient {

    override val configured: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getSubscription(packageName: String, purchaseToken: String): PlaySubscription? {
        val accessToken = tokenProvider.accessToken()
        val url = listOf(
            baseUrl.trimEnd('/'),
            "androidpublisher/v3/applications",
            enc(packageName),
            "purchases/subscriptionsv2/tokens",
            enc(purchaseToken),
        ).joinToString("/")

        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = withContext(Dispatchers.IO) {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        }
        if (response.statusCode() == 400 || response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) {
            throw PlayApiException(
                "Play subscriptionsv2.get failed (${response.statusCode()})",
                response.statusCode(),
            )
        }
        return json.decodeFromString<SubscriptionPurchaseV2Dto>(response.body()).toPlaySubscription()
    }

    private fun SubscriptionPurchaseV2Dto.toPlaySubscription() = PlaySubscription(
        state = subscriptionState,
        lineItems = lineItems.map { line ->
            PlayLineItem(
                productId = line.productId,
                expiryTimeMillis = line.expiryTime.toEpochMillis(),
                autoRenewing = line.autoRenewingPlan?.autoRenewEnabled ?: false,
                offerId = line.offerDetails?.offerId,
            )
        },
        cancelTimeMillis = canceledContext?.cancelTime.toEpochMillis(),
        acknowledgeState = acknowledgementState,
    )

    private fun String?.toEpochMillis(): Long? =
        this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    companion object {
        const val DEFAULT_BASE_URL = "https://androidpublisher.googleapis.com"
    }
}
