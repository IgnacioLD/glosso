package com.glosso.server

import com.glosso.server.api.BillingVerifyRequest
import com.glosso.server.api.PubSubMessage
import com.glosso.server.api.PubSubPushEnvelope
import com.glosso.server.billing.BillingConfig
import com.glosso.server.billing.BillingService
import com.glosso.server.billing.NotificationType
import com.glosso.server.billing.PlayLineItem
import com.glosso.server.billing.PlaySubscription
import com.glosso.server.billing.PlaySubscriptionsClient
import com.glosso.server.product.BetaConfig
import com.glosso.server.product.ClientConfig
import com.glosso.server.product.EntitlementService
import com.glosso.server.product.FounderBenefitConfig
import com.glosso.server.product.InMemoryEntitlementStore
import com.glosso.server.product.PlanSource
import com.glosso.server.product.ProductConfig
import com.glosso.server.product.ProductMode
import com.glosso.server.product.StoredEntitlement
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GET /v1/config` and `GET /v1/me`: the server is the authority for the phase,
 * the beta dates, the plan and the Beta Founder status. These lock the exact
 * config shape (with `no-cache` + ETag) and the identity rules, including that a
 * founder keeps the status when the plan changes.
 */
class ProductRouteTest {

    private val packageName = "me.shirobyte42.glosso"
    private val productId = "com.glosso.app.pro"
    private val future = System.currentTimeMillis() + 86_400_000L

    private class FakePlayClient(
        private val purchase: PlaySubscription,
    ) : PlaySubscriptionsClient {
        override val configured: Boolean = true
        override suspend fun getSubscription(packageName: String, purchaseToken: String): PlaySubscription =
            purchase
    }

    private fun billing(
        rtdnSecret: String? = null,
        state: String = "SUBSCRIPTION_STATE_ACTIVE",
        expiry: Long? = future,
        autoRenewing: Boolean = true,
    ): BillingService = BillingService(
        client = FakePlayClient(
            PlaySubscription(
                state = state,
                lineItems = listOf(PlayLineItem(productId, expiry, autoRenewing = autoRenewing, offerId = null)),
                cancelTimeMillis = null,
                acknowledgeState = "ACKNOWLEDGED",
            ),
        ),
        config = BillingConfig(
            packageName = packageName,
            productId = productId,
            trialOfferId = null,
            cacheTtlMillis = 600_000,
            rtdnSecret = rtdnSecret,
            rtdnSubscription = null,
        ),
    )

    private fun personalized(userId: UUID, tokenHash: String? = null): StoredEntitlement =
        StoredEntitlement(
            userId = userId,
            plan = "PERSONALIZED",
            status = "ACTIVE",
            source = "manual",
            expiresAt = null,
            startedAt = Instant.parse("2026-03-01T00:00:00Z"),
            autoRenew = false,
            provider = null,
            providerTokenHash = tokenHash,
        )

    private fun canonicalHash(raw: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun notification(type: Int): PubSubPushEnvelope = PubSubPushEnvelope(
        message = PubSubMessage(
            data = Base64.getEncoder().encodeToString(
                (
                    """{"version":"1.0","packageName":"$packageName","subscriptionNotification":""" +
                        """{"version":"1.0","notificationType":$type,"purchaseToken":"purchase-token",""" +
                        """"subscriptionId":"$productId"}}"""
                    ).toByteArray(),
            ),
        ),
    )

    private fun config(
        mode: ProductMode = ProductMode.BETA,
        endDate: Instant = ProductConfig.DEFAULT_END_DATE,
        founderEnabled: Boolean = true,
        founderUntil: Instant = ProductConfig.DEFAULT_FOUNDER_UNTIL,
        founderBenefitKind: String = ProductConfig.PRO_LIFETIME,
    ): ProductConfig = ProductConfig(
        mode = mode,
        beta = BetaConfig(
            endDate = endDate,
            founderEnabled = founderEnabled,
            founderBenefit = FounderBenefitConfig(
                kind = founderBenefitKind,
                value = null,
                until = null,
                label = ProductConfig.DEFAULT_FOUNDER_LABEL,
            ),
            founderUntil = founderUntil,
        ),
        flags = emptyMap(),
        client = ClientConfig(2210, 2219, false),
    )

    private companion object {
        val PAST: Instant = Instant.parse("2020-01-01T00:00:00Z")
        val FUTURE: Instant = Instant.parse("2099-12-31T23:59:59Z")
    }

    private fun emptyAssets(): File = Files.createTempDirectory("glosso-product-assets").toFile()

    @Test
    fun `config is served with no-cache and an etag`() = testApplication {
        application {
            module(emptyAssets(), adminToken = null, analytics = null, productConfig = config())
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/v1/config")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertFalse(response.headers[HttpHeaders.ETag].isNullOrBlank())
        val body = response.bodyAsText()
        assertTrue(body.contains("\"mode\":\"BETA\""), body)
        assertTrue(body.contains("\"limits\":null"), body)
        assertTrue(body.contains("\"pricing\":null"), body)
        assertTrue(body.contains("\"forceUpdate\":false"), body)
    }

    @Test
    fun `config revalidates with the etag`() = testApplication {
        application {
            module(emptyAssets(), adminToken = null, analytics = null, productConfig = config())
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val etag = client.get("/v1/config").headers[HttpHeaders.ETag]
        assertNotNull(etag)

        val revalidated = client.get("/v1/config") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, revalidated.status)
    }

    @Test
    fun `the first identification marks a beta founder, permanently`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body = client.get("/v1/me") { header("X-Device-Id", "founder-device") }.bodyAsText()

        assertTrue(body.contains("\"plan\": \"PRO\""), body)
        assertTrue(body.contains("\"status\": \"NONE\""), body)
        assertTrue(body.contains("\"betaFounder\": true"), body)
        assertTrue(body.contains("\"betaFounderSince\": \""), body)
        assertTrue(body.contains("\"betaFounderBenefit\": \"PRO_LIFETIME\""), body)
        assertEquals(true, store.identityOf("founder-device")?.betaFounder)

        // A second call must not recompute it away.
        val again = client.get("/v1/me") { header("X-Device-Id", "founder-device") }.bodyAsText()
        assertTrue(again.contains("\"betaFounder\": true"), again)
    }

    @Test
    fun `a user created earlier in the beta is still marked a founder`() = testApplication {
        val store = InMemoryEntitlementStore()
        store.upsertUser("early-device", Instant.parse("2026-03-01T00:00:00Z"))
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body = client.get("/v1/me") { header("X-Device-Id", "early-device") }.bodyAsText()

        assertTrue(body.contains("\"betaFounder\": true"), body)
        assertEquals(true, store.identityOf("early-device")?.betaFounder)
    }

    @Test
    fun `a user from the beta is a founder even once the mode is FREE`() = testApplication {
        val store = InMemoryEntitlementStore()
        store.upsertUser("transition-device", Instant.parse("2026-03-01T00:00:00Z"))
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                productConfig = config(
                    mode = ProductMode.FREE,
                    endDate = Instant.parse("2026-06-01T00:00:00Z"),
                ),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body = client.get("/v1/me") { header("X-Device-Id", "transition-device") }.bodyAsText()

        assertTrue(body.contains("\"betaFounder\": true"), body)
        assertEquals(true, store.identityOf("transition-device")?.betaFounder)
    }

    @Test
    fun `a founder does not lose the status when the plan becomes PRO`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(),
                entitlementStore = store,
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get("/v1/me") { header("X-Device-Id", "upgrade-device") }

        val verify = client.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            header("X-Device-Id", "upgrade-device")
            setBody(
                BillingVerifyRequest(
                    productId = productId,
                    purchaseToken = "purchase-token",
                    packageName = packageName,
                    deviceId = "upgrade-device",
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, verify.status)

        val me = client.get("/v1/me") { header("X-Device-Id", "upgrade-device") }.bodyAsText()
        assertTrue(me.contains("\"plan\": \"PRO\""), me)
        assertTrue(me.contains("\"betaFounder\": true"), me)
        assertEquals("PRO", store.entitlementOf("upgrade-device")?.plan)
    }

    @Test
    fun `a revoked RTDN denies entitlement in the store, not only in the cache`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(rtdnSecret = "rtdn-secret"),
                entitlementStore = store,
                // Past the beta and the founder window: this is about the
                // subscription row, not about founder or beta access.
                productConfig = config(endDate = PAST, founderUntil = PAST),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val verify = client.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                BillingVerifyRequest(
                    productId = productId,
                    purchaseToken = "purchase-token",
                    packageName = packageName,
                    deviceId = "refund-device",
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, verify.status)
        assertEquals("PRO", store.entitlementOf("refund-device")?.plan)

        val webhook = client.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer rtdn-secret")
            setBody(notification(NotificationType.REVOKED))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)

        assertEquals("FREE", store.entitlementOf("refund-device")?.plan)
        val me = client.get("/v1/me") { header("X-Device-Id", "refund-device") }.bodyAsText()
        assertTrue(me.contains("\"plan\": \"FREE\""), me)
        assertTrue(me.contains("\"status\": \"NONE\""), me)
    }

    @Test
    fun `a paid verify does not downgrade a personalized plan`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(),
                entitlementStore = store,
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        val identity = store.upsertUser("personalized-device", Instant.parse("2026-03-01T00:00:00Z")).identity
        store.upsertEntitlement(
            StoredEntitlement(
                userId = identity.id,
                plan = "PERSONALIZED",
                status = "ACTIVE",
                source = "manual",
                expiresAt = null,
                startedAt = Instant.parse("2026-03-01T00:00:00Z"),
                autoRenew = false,
                provider = null,
                providerTokenHash = null,
            ),
        )

        val verify = client.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                BillingVerifyRequest(
                    productId = productId,
                    purchaseToken = "purchase-token",
                    packageName = packageName,
                    deviceId = "personalized-device",
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, verify.status)
        assertEquals("PERSONALIZED", store.entitlementOf("personalized-device")?.plan)
    }

    @Test
    fun `a non-entitled verify does not downgrade a personalized plan`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(
                    state = "SUBSCRIPTION_STATE_EXPIRED",
                    expiry = System.currentTimeMillis() - 1_000L,
                ),
                entitlementStore = store,
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        val identity = store.upsertUser("personalized-device", Instant.parse("2026-03-01T00:00:00Z")).identity
        store.upsertEntitlement(personalized(identity.id))

        val verify = client.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                BillingVerifyRequest(
                    productId = productId,
                    purchaseToken = "expired-token",
                    packageName = packageName,
                    deviceId = "personalized-device",
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, verify.status)
        assertTrue(verify.bodyAsText().contains("\"entitled\": false"), verify.bodyAsText())
        assertEquals("PERSONALIZED", store.entitlementOf("personalized-device")?.plan)
        assertEquals("manual", store.entitlementOf("personalized-device")?.source)
        val me = client.get("/v1/me") { header("X-Device-Id", "personalized-device") }.bodyAsText()
        assertTrue(me.contains("\"plan\": \"PERSONALIZED\""), me)
    }

    @Test
    fun `a revoked RTDN does not revoke a personalized grant`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(rtdnSecret = "rtdn-secret"),
                entitlementStore = store,
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        val identity = store.upsertUser("personalized-device", Instant.parse("2026-03-01T00:00:00Z")).identity
        // A manual grant that a Play verify linked to this purchase: a refund of
        // that purchase must not remove the personalization.
        store.upsertEntitlement(personalized(identity.id, tokenHash = canonicalHash("purchase-token")))

        val webhook = client.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer rtdn-secret")
            setBody(notification(NotificationType.REVOKED))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)

        assertEquals("PERSONALIZED", store.entitlementOf("personalized-device")?.plan)
        assertEquals("ACTIVE", store.entitlementOf("personalized-device")?.status)
        val me = client.get("/v1/me") { header("X-Device-Id", "personalized-device") }.bodyAsText()
        assertTrue(me.contains("\"plan\": \"PERSONALIZED\""), me)
    }

    @Test
    fun `a revoked RTDN revokes the same token on every device`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(rtdnSecret = "rtdn-secret"),
                entitlementStore = store,
                // Past the beta and the founder window: this is about the
                // subscription row, not about founder or beta access.
                productConfig = config(endDate = PAST, founderUntil = PAST),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devices = listOf("device-a", "device-b")

        // The same purchase token is verified from two installs; the migration
        // deliberately keeps the token index non-unique for multi-device.
        for (device in devices) {
            val verify = client.post("/v1/billing/verify") {
                contentType(ContentType.Application.Json)
                setBody(
                    BillingVerifyRequest(
                        productId = productId,
                        purchaseToken = "purchase-token",
                        packageName = packageName,
                        deviceId = device,
                    ),
                )
            }
            assertEquals(HttpStatusCode.OK, verify.status)
            assertEquals("PRO", store.entitlementOf(device)?.plan)
        }

        // A refund is not per-device: every row with that token must be revoked,
        // not just the first one the by-token lookup happens to return.
        val webhook = client.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer rtdn-secret")
            setBody(notification(NotificationType.REVOKED))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)

        for (device in devices) {
            assertEquals("FREE", store.entitlementOf(device)?.plan)
            val me = client.get("/v1/me") { header("X-Device-Id", device) }.bodyAsText()
            assertTrue(me.contains("\"plan\": \"FREE\""), me)
            assertTrue(me.contains("\"status\": \"NONE\""), me)
        }
    }

    @Test
    fun `no new founders once the founder window closes`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                productConfig = config(mode = ProductMode.FREE, endDate = PAST, founderUntil = PAST),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body = client.get("/v1/me") { header("X-Device-Id", "late-device") }.bodyAsText()

        assertTrue(body.contains("\"betaFounder\": false"), body)
        assertEquals(false, store.identityOf("late-device")?.betaFounder)
    }

    @Test
    fun `a closed founder window does not mark founders even in beta mode`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                productConfig = config(mode = ProductMode.BETA, founderUntil = PAST),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.get("/v1/me") { header("X-Device-Id", "late-beta-device") }

        assertEquals(false, store.identityOf("late-beta-device")?.betaFounder)
    }

    @Test
    fun `an alta after the beta but inside the founder window is a founder`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                // The beta is long over; the founder window (its own key) is not.
                productConfig = config(mode = ProductMode.FREE, endDate = PAST, founderUntil = FUTURE),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val me = client.get("/v1/me") { header("X-Device-Id", "production-device") }.bodyAsText()

        assertTrue(me.contains("\"betaFounder\": true"), me)
        assertTrue(me.contains("\"plan\": \"PRO\""), me)
        assertTrue(me.contains("\"betaAccess\": false"), me)
        assertEquals(true, store.identityOf("production-device")?.betaFounder)
    }

    @Test
    fun `me requires a device id`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = InMemoryEntitlementStore(),
                productConfig = config(),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/v1/me")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `me is unavailable without a store`() = testApplication {
        application {
            module(emptyAssets(), adminToken = null, analytics = null, productConfig = config())
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/v1/me") { header("X-Device-Id", "no-store") }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
    }

    @Test
    fun `an expired paid plan falls back to the founder PRO content without touching the status`() {
        val store = InMemoryEntitlementStore()
        val service = EntitlementService(store, config(), clock = { Instant.parse("2026-06-01T00:00:00Z") })

        val identity = service.identify("service-device")
        assertTrue(identity.betaFounder)
        store.upsertEntitlement(
            StoredEntitlement(
                userId = identity.id,
                plan = "PRO",
                status = "ACTIVE",
                source = "play",
                expiresAt = Instant.parse("2026-01-01T00:00:00Z"),
                startedAt = Instant.parse("2025-01-01T00:00:00Z"),
                autoRenew = false,
                provider = "google_play",
                providerTokenHash = "hash",
            ),
        )

        val me = service.me("service-device")
        assertEquals("PRO", me.plan)
        assertEquals("NONE", me.status)
        assertEquals(null, me.expiresAt)
        assertTrue(me.betaFounder)
    }

    @Test
    fun `a beta founder gets PRO content for free and never PERSONALIZED`() {
        val store = InMemoryEntitlementStore()
        val service = EntitlementService(store, config(), clock = { Instant.parse("2026-06-01T00:00:00Z") })

        assertTrue(service.identify("founder-device").betaFounder)
        val founder = service.me("founder-device")
        assertEquals("PRO", founder.plan)
        assertEquals("NONE", founder.status)
        assertEquals(null, founder.expiresAt)
        assertTrue(founder.betaFounder)

        // No entitlement and no founder window: stays FREE.
        val afterBeta = EntitlementService(
            store,
            config(endDate = PAST, founderUntil = PAST),
            clock = { Instant.parse("2026-06-01T00:00:00Z") },
        )
        val standard = afterBeta.me("standard-device")
        assertEquals("FREE", standard.plan)
        assertEquals("NONE", standard.status)
        assertFalse(standard.betaFounder)
    }

    @Test
    fun `the founder status survives the switch to FREE`() {
        val store = InMemoryEntitlementStore()
        val beta = EntitlementService(
            store,
            config(mode = ProductMode.BETA, endDate = Instant.parse("2026-06-01T00:00:00Z")),
            clock = { Instant.parse("2026-05-01T00:00:00Z") },
        )
        assertTrue(beta.identify("history-device").betaFounder)

        val free = EntitlementService(
            store,
            config(mode = ProductMode.FREE, endDate = Instant.parse("2026-06-01T00:00:00Z")),
            clock = { Instant.parse("2027-01-01T00:00:00Z") },
        )
        val me = free.me("history-device")
        assertTrue(me.betaFounder)
        assertEquals("PRO", me.plan)
    }

    @Test
    fun `plan and status never contradict each other`() {
        val store = InMemoryEntitlementStore()
        val service = EntitlementService(store, config(founderEnabled = false), clock = { Instant.parse("2026-06-01T00:00:00Z") })

        val identity = service.identify("contradiction-device")
        store.upsertEntitlement(
            StoredEntitlement(
                userId = identity.id,
                plan = "FREE",
                status = "ACTIVE",
                source = "manual",
                expiresAt = Instant.parse("2027-01-01T00:00:00Z"),
                startedAt = Instant.parse("2026-06-01T00:00:00Z"),
                autoRenew = false,
                provider = null,
                providerTokenHash = null,
            ),
        )

        val me = service.me("contradiction-device")
        assertEquals("FREE", me.plan)
        assertEquals("NONE", me.status)
        assertEquals(null, me.expiresAt)
    }

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `beta served contract - everything unlocked and the founder marked permanently`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                entitlementStore = store,
                // The live defaults: beta and founder window both until 2027-12-31.
                productConfig = ProductConfig.fromEnv(emptyMap()),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val config = json(client.get("/v1/config").bodyAsText())
        assertEquals("BETA", config.text("mode"))
        val beta = config.getValue("beta").jsonObject
        assertTrue(beta.bool("active"))
        assertEquals("2027-12-31T23:59:59Z", beta.text("endDate"))
        assertEquals("2027-12-31T23:59:59Z", beta.text("founderUntil"))
        assertEquals("PRO_LIFETIME", beta.getValue("founderBenefit").jsonObject.text("kind"))
        assertTrue(config.getValue("flags").jsonObject.bool("beta_banner"))
        assertFalse(config.getValue("flags").jsonObject.bool("pro"))
        assertEquals(
            setOf("pronunciation_analysis", "exercises", "analytics", "personalization", "personalized_learning"),
            config.getValue("plans").jsonObject.getValue("FREE").jsonObject.keys,
        )

        val me = json(client.get("/v1/me") { header("X-Device-Id", "contract-device") }.bodyAsText())
        assertTrue(me.bool("betaFounder"))
        assertTrue(me.bool("betaAccess"))
        assertEquals("PRO", me.text("plan"))
        assertEquals("LIFETIME", me.text("planSource"))
        assertEquals("NONE", me.text("status"))
        assertTrue(me["expiresAt"] is JsonNull)
        val entitlements = me.getValue("entitlements").jsonObject
        assertEquals("UNLIMITED", entitlements.text("pronunciation_analysis"))
        assertEquals("UNLIMITED", entitlements.text("exercises"))
        assertEquals("ADVANCED", entitlements.text("analytics"))
        assertEquals("FULL", entitlements.text("personalization"))
        assertTrue(entitlements.bool("personalized_learning"))
        assertTrue(me["subscription"] is JsonNull)

        // Permanent: recomputing with a closed window never clears it.
        val later = EntitlementService(
            store,
            config(mode = ProductMode.FREE, endDate = PAST, founderUntil = PAST),
        ).me("contract-device")
        assertTrue(later.betaFounder)
        assertEquals("PRO", later.plan)
        assertEquals(PlanSource.LIFETIME, later.planSource)
    }

    @Test
    fun `during the beta a non-founder gets full access too`() {
        val store = InMemoryEntitlementStore()
        val now = Instant.parse("2026-10-01T00:00:00Z")
        val beta = EntitlementService(store, config(founderEnabled = false), clock = { now })

        val me = beta.me("standard-device")
        assertFalse(me.betaFounder)
        assertEquals("FREE", me.plan)
        assertTrue(me.betaAccess)
        assertEquals(ProductConfig.DEFAULT_PLANS.getValue("PERSONALIZED"), me.entitlements)

        // Past the end date the same user falls back to the FREE plan's access.
        val after = EntitlementService(
            store,
            config(founderEnabled = false),
            clock = { Instant.parse("2028-01-01T00:00:00Z") },
        ).me("standard-device")
        assertFalse(after.betaAccess)
        assertEquals(ProductConfig.DEFAULT_PLANS.getValue("FREE"), after.entitlements)
    }

    @Test
    fun `the beta closes by date even if the mode was never switched`() {
        val config = config(mode = ProductMode.BETA, endDate = Instant.parse("2027-12-31T23:59:59Z"))

        val open = json(config.toJson(Instant.parse("2027-12-31T23:59:59Z")))
        assertEquals("BETA", open.text("mode"))
        assertTrue(open.getValue("beta").jsonObject.bool("active"))
        assertTrue(open.getValue("flags").jsonObject.bool("beta_banner"))

        val closed = json(config.toJson(Instant.parse("2028-01-01T00:00:00Z")))
        assertEquals("FREE", closed.text("mode"))
        assertFalse(closed.getValue("beta").jsonObject.bool("active"))
        assertFalse(closed.getValue("flags").jsonObject.bool("beta_banner"))
        assertNotEquals(open, closed)
    }

    @Test
    fun `the founder PRO is a lifetime grant, not a subscription, and survives config changes`() {
        val store = InMemoryEntitlementStore()
        val now = Instant.parse("2027-06-01T00:00:00Z")
        EntitlementService(store, config(), clock = { now }).identify("lifetime-device")
        val grant = store.grantsOf("lifetime-device").single()
        assertEquals("PRO", grant.plan)
        assertEquals(EntitlementService.BETA_FOUNDER_SOURCE, grant.source)

        // After the beta, with another benefit configured and the window closed,
        // the founder keeps PRO for life: the grant is persisted, not recomputed.
        val later = EntitlementService(
            store,
            config(mode = ProductMode.FREE, endDate = PAST, founderUntil = PAST, founderBenefitKind = "discount_percent"),
            clock = { Instant.parse("2031-01-01T00:00:00Z") },
        ).me("lifetime-device")
        assertEquals("PRO", later.plan)
        assertEquals(PlanSource.LIFETIME, later.planSource)
        assertEquals("NONE", later.status)
        assertEquals(null, later.expiresAt)
        assertEquals(null, later.subscription)
        assertEquals(ProductConfig.DEFAULT_PLANS.getValue("PRO"), later.entitlements)
    }

    @Test
    fun `another founder benefit grants no plan`() {
        val store = InMemoryEntitlementStore()
        val service = EntitlementService(
            store,
            config(mode = ProductMode.FREE, endDate = PAST, founderUntil = FUTURE, founderBenefitKind = "discount_percent"),
            clock = { Instant.parse("2027-06-01T00:00:00Z") },
        )

        val me = service.me("discount-founder")
        assertTrue(me.betaFounder)
        assertEquals("FREE", me.plan)
        assertEquals(PlanSource.FREE, me.planSource)
        assertTrue(store.grantsOf("discount-founder").isEmpty())
    }

    @Test
    fun `a founder that subscribes to PERSONALIZED gets it and keeps the lifetime PRO`() {
        val store = InMemoryEntitlementStore()
        val now = Instant.parse("2028-06-01T00:00:00Z")
        val service = EntitlementService(
            store,
            config(mode = ProductMode.FREE, endDate = PAST, founderUntil = FUTURE),
            clock = { now },
        )
        val identity = service.identify("upgrading-founder")
        store.upsertEntitlement(
            StoredEntitlement(
                userId = identity.id,
                plan = "PERSONALIZED",
                status = "ACTIVE",
                source = "play",
                expiresAt = Instant.parse("2028-07-01T00:00:00Z"),
                startedAt = now,
                autoRenew = true,
                provider = "google_play",
                providerTokenHash = "hash",
            ),
        )

        val subscribed = service.me("upgrading-founder")
        assertEquals("PERSONALIZED", subscribed.plan)
        assertEquals(PlanSource.SUBSCRIPTION, subscribed.planSource)
        assertEquals("ACTIVE", subscribed.status)
        assertTrue(subscribed.betaFounder)
        assertEquals(listOf("PRO"), subscribed.lifetimeGrants.map { it.plan })

        // The subscription lapses: back to the lifetime PRO, never to FREE.
        val lapsed = EntitlementService(
            store,
            config(mode = ProductMode.FREE, endDate = PAST, founderUntil = FUTURE),
            clock = { Instant.parse("2028-08-01T00:00:00Z") },
        ).me("upgrading-founder")
        assertEquals("PRO", lapsed.plan)
        assertEquals(PlanSource.LIFETIME, lapsed.planSource)
        assertEquals("NONE", lapsed.status)
        assertTrue(lapsed.betaFounder)
    }

    @Test
    fun `transition after the beta - new FREE, founder PRO for life, subscribers keep their plan`() {
        val store = InMemoryEntitlementStore()
        store.upsertUser("beta-user", Instant.parse("2027-03-01T00:00:00Z"))
        val after = EntitlementService(
            store,
            config(
                mode = ProductMode.FREE,
                endDate = Instant.parse("2027-12-31T23:59:59Z"),
                founderUntil = Instant.parse("2027-12-31T23:59:59Z"),
            ),
            clock = { Instant.parse("2028-02-01T00:00:00Z") },
        )

        val founder = after.me("beta-user")
        assertTrue(founder.betaFounder)
        assertEquals("PRO", founder.plan)
        assertFalse(founder.betaAccess)

        val newcomer = after.me("new-user")
        assertFalse(newcomer.betaFounder)
        assertEquals("FREE", newcomer.plan)
        assertEquals(ProductConfig.DEFAULT_PLANS.getValue("FREE"), newcomer.entitlements)
    }

    @Test
    fun `me exposes the subscription behind a paid plan`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(),
                entitlementStore = store,
                productConfig = config(endDate = PAST, founderUntil = PAST),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        client.post("/v1/billing/verify") {
            contentType(ContentType.Application.Json)
            setBody(BillingVerifyRequest(productId, "purchase-token", packageName, deviceId = "sub-device"))
        }
        val me = json(client.get("/v1/me") { header("X-Device-Id", "sub-device") }.bodyAsText())
        assertEquals("PRO", me.text("plan"))
        assertEquals("ACTIVE", me.text("status"))
        assertFalse(me.bool("betaAccess"))
        val subscription = me.getValue("subscription").jsonObject
        assertEquals("google_play", subscription.text("provider"))
        assertEquals("play", subscription.text("source"))
        assertTrue(subscription.bool("autoRenew"))
        assertEquals("ADVANCED", me.getValue("entitlements").jsonObject.text("analytics"))
        assertTrue(store.entitlementOf("sub-device")!!.autoRenew)
    }

    @Test
    fun `a cancelled subscription stops auto-renewing but stays entitled until expiry`() = testApplication {
        val store = InMemoryEntitlementStore()
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                billing = billing(
                    rtdnSecret = "rtdn-secret",
                    state = "SUBSCRIPTION_STATE_CANCELED",
                    autoRenewing = false,
                ),
                entitlementStore = store,
                productConfig = config(endDate = PAST, founderUntil = PAST),
            )
        }
        val client = createClient { install(ContentNegotiation) { json() } }
        // Joined after the (closed) founder window: a plain subscriber.
        val identity = store.upsertUser("cancel-device", Instant.parse("2025-01-01T00:00:00Z")).identity
        store.upsertEntitlement(
            StoredEntitlement(
                userId = identity.id,
                plan = "PRO",
                status = "ACTIVE",
                source = "play",
                expiresAt = Instant.ofEpochMilli(future),
                startedAt = PAST,
                autoRenew = true,
                provider = "google_play",
                providerTokenHash = canonicalHash("purchase-token"),
            ),
        )

        val webhook = client.post("/v1/billing/rtdn") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer rtdn-secret")
            setBody(notification(NotificationType.CANCELED))
        }
        assertEquals(HttpStatusCode.OK, webhook.status)

        val row = store.entitlementOf("cancel-device")!!
        assertEquals("CANCELED", row.status)
        assertFalse(row.autoRenew)
        val me = json(client.get("/v1/me") { header("X-Device-Id", "cancel-device") }.bodyAsText())
        assertEquals("PRO", me.text("plan"))
        assertEquals("CANCELED", me.text("status"))
        assertFalse(me.getValue("subscription").jsonObject.bool("autoRenew"))
    }
}
