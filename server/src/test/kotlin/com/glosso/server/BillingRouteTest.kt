package com.glosso.server

import com.glosso.server.api.BillingVerifyRequest
import com.glosso.server.api.PubSubMessage
import com.glosso.server.api.PubSubPushEnvelope
import com.glosso.server.billing.BillingConfig
import com.glosso.server.billing.BillingService
import com.glosso.server.billing.NotificationType
import com.glosso.server.billing.PlayApiException
import com.glosso.server.billing.PlayLineItem
import com.glosso.server.billing.PlaySubscription
import com.glosso.server.billing.PlaySubscriptionsClient
import com.glosso.server.billing.UnavailablePlaySubscriptionsClient
import com.glosso.server.product.InMemoryEntitlementStore
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the server billing endpoints against a fake Play API. The real API is
 * never called, so these lock the mapping, the cache behaviour, the fail-closed
 * answer when Play (or the config) is unavailable, and the RTDN flow. See
 * docs/BILLING-E2E.md.
 */
class BillingRouteTest {

    private val packageName = "me.shirobyte42.glosso"
    private val productId = "com.glosso.app.pro"
    private val token = "purchase-token"
    private val rtdnSecret = "rtdn-secret"
    private val future = System.currentTimeMillis() + 86_400_000L

    private class FakePlayClient(
        @Volatile var purchase: PlaySubscription?,
        @Volatile var failure: PlayApiException? = null,
    ) : PlaySubscriptionsClient {
        override val configured: Boolean = true
        val calls = AtomicInteger(0)

        override suspend fun getSubscription(packageName: String, purchaseToken: String): PlaySubscription? {
            calls.incrementAndGet()
            failure?.let { throw it }
            return purchase
        }
    }

    private fun purchase(
        state: String,
        expiry: Long? = future,
        product: String = productId,
    ) = PlaySubscription(
        state = state,
        lineItems = listOf(PlayLineItem(product, expiry, autoRenewing = true, offerId = null)),
        cancelTimeMillis = null,
        acknowledgeState = "ACKNOWLEDGED",
    )

    private fun service(
        client: PlaySubscriptionsClient,
        secret: String? = rtdnSecret,
        subscription: String? = null,
        cacheTtlMillis: Long = 600_000,
    ) = BillingService(
        client = client,
        config = BillingConfig(
            packageName = packageName,
            productId = productId,
            trialOfferId = null,
            cacheTtlMillis = cacheTtlMillis,
            rtdnSecret = secret,
            rtdnSubscription = subscription,
        ),
    )

    private fun request(purchaseToken: String = token, packageName: String = this.packageName) =
        BillingVerifyRequest(productId = productId, purchaseToken = purchaseToken, packageName = packageName)

    private fun emptyAssets(): File = Files.createTempDirectory("glosso-billing-assets").toFile()

    private fun envelope(subscriptionJson: String): PubSubPushEnvelope = PubSubPushEnvelope(
        message = PubSubMessage(data = Base64.getEncoder().encodeToString(subscriptionJson.toByteArray())),
        subscription = "projects/glosso/subscriptions/glosso-rtdn",
    )

    private fun notification(type: Int, purchaseToken: String = token): String =
        """{"version":"1.0","packageName":"$packageName","subscriptionNotification":""" +
            """{"version":"1.0","notificationType":$type,"purchaseToken":"$purchaseToken","subscriptionId":"$productId"}}"""

    private fun canonicalHash(raw: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `valid active subscription is entitled`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"entitled\": true"), body)
        assertTrue(body.contains("\"status\": \"ACTIVE\""), body)
        assertTrue(body.contains("\"expiresAtMillis\": $future"), body)
    }

    @Test
    fun `expired subscription is not entitled`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_EXPIRED", expiry = future - 7 * 86_400_000L))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val body = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }.bodyAsText()

        assertTrue(body.contains("\"entitled\": false"), body)
        assertTrue(body.contains("\"status\": \"NONE\""), body)
    }

    @Test
    fun `revoked purchase is not entitled`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_EXPIRED", expiry = null))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val body = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }.bodyAsText()

        assertTrue(body.contains("\"entitled\": false"), body)
        assertTrue(body.contains("\"status\": \"NONE\""), body)
    }

    @Test
    fun `unknown token is not entitled, not an error`() = testApplication {
        val fake = FakePlayClient(purchase = null)
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"entitled\": false"))
    }

    @Test
    fun `a verified verdict is served from cache without asking Play again`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        repeat(2) {
            json.post("/v1/billing/verify") {
                contentType(ContentType.Application.Json)
                setBody(request())
            }
        }

        assertEquals(1, fake.calls.get())
    }

    @Test
    fun `a cache TTL of zero always asks Play`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application {
            module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake, cacheTtlMillis = 0))
        }
        val json = createClient { install(ContentNegotiation) { json() } }

        repeat(2) {
            json.post("/v1/billing/verify") {
                contentType(ContentType.Application.Json)
                setBody(request())
            }
        }

        assertEquals(2, fake.calls.get())
    }

    @Test
    fun `without a service account verification says it cannot verify, never Pro`() = testApplication {
        val billing = BillingService(
            client = UnavailablePlaySubscriptionsClient,
            config = BillingConfig(packageName, productId, null, 600_000, rtdnSecret, null),
        )
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = billing) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains("entitled"))
    }

    @Test
    fun `when Play fails the server rejects instead of granting Pro`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"), failure = PlayApiException("boom", 500))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `a request for another package is refused`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request(packageName = "com.glosso.app"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, fake.calls.get())
    }

    @Test
    fun `rtdn without a secret is closed`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application {
            module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake, secret = null))
        }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            setBody(envelope(notification(NotificationType.CANCELED)))
        }

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `rtdn with the wrong secret is unauthorized`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer wrong")
            setBody(envelope(notification(NotificationType.CANCELED)))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `rtdn ignores a token in the query string, the header is the only way`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        // A secret in the URL ends up in every proxy access log; it must not
        // authenticate even when it is correct (SEC-08).
        val response = json.post("/v1/billing/rtdn?token=$rtdnSecret") {
            contentType(ContentType.Application.Json)
            setBody(envelope(notification(NotificationType.CANCELED)))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `rtdn invalidates the cache and refreshes the state`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }
        assertEquals(1, fake.calls.get())

        // Play now reports the subscription as expired; the RTDN must pick it up.
        fake.purchase = purchase("SUBSCRIPTION_STATE_EXPIRED", expiry = null)
        val webhook = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $rtdnSecret")
            setBody(envelope(notification(NotificationType.EXPIRED)))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)
        assertEquals(2, fake.calls.get())

        val body = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }.bodyAsText()
        assertTrue(body.contains("\"entitled\": false"), body)
        assertEquals(2, fake.calls.get())
    }

    @Test
    fun `a revoked notification denies entitlement without calling Play`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }

        val webhook = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $rtdnSecret")
            setBody(envelope(notification(NotificationType.REVOKED)))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)

        val body = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(request())
        }.bodyAsText()
        assertTrue(body.contains("\"entitled\": false"), body)
        assertEquals(1, fake.calls.get())
    }

    @Test
    fun `a token with surrounding spaces stores the canonical hash so RTDN can revoke`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = service(fake),
                entitlementStore = store,
            )
        }
        val json = createClient { install(ContentNegotiation) { json() } }

        // The client sends a token padded with spaces; BillingService validates
        // its trim(), so the persisted hash must be that same canonical token or
        // the RTDN lookup below misses the row and a refund never revokes it.
        val padded = request(purchaseToken = "  $token  ").copy(deviceId = "device-1")
        val verified = json.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(padded)
        }
        assertEquals(HttpStatusCode.OK, verified.status)
        assertTrue(verified.bodyAsText().contains("\"entitled\": true"))
        assertEquals(canonicalHash(token), store.entitlementOf("device-1")?.providerTokenHash)

        // The RTDN carries the canonical token; it has to find and revoke the row.
        val webhook = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $rtdnSecret")
            setBody(envelope(notification(NotificationType.REVOKED)))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)
        val revoked = store.entitlementOf("device-1")
        assertEquals("NONE", revoked?.status)
        assertEquals("FREE", revoked?.plan)
    }

    @Test
    fun `rtdn with an unreadable payload is rejected`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val response = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $rtdnSecret")
            setBody(PubSubPushEnvelope(PubSubMessage(data = "!!not-base64!!")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `rtdn for another package is rejected`() = testApplication {
        val fake = FakePlayClient(purchase("SUBSCRIPTION_STATE_ACTIVE"))
        application { module(emptyAssets(), adminToken = null, analytics = null, billing = service(fake)) }
        val json = createClient { install(ContentNegotiation) { json() } }

        val otherPackage = """{"packageName":"com.example.other","testNotification":{"version":"1.0"}}"""
        val response = json.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $rtdnSecret")
            setBody(envelope(otherPackage))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
