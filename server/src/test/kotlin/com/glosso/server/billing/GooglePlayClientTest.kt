package com.glosso.server.billing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real Play client and the service-account token flow against a
 * local HTTP stub, so RS256 signing, token caching, URL building and JSON mapping
 * are all covered without ever touching Google.
 */
class GooglePlayClientTest {

    private lateinit var server: HttpServer
    private var port = 0
    private val tokenRequests = AtomicInteger(0)
    private val playRequests = AtomicInteger(0)

    private val packageName = "me.shirobyte42.glosso"
    private val purchaseToken = "abc.def-ghi"
    private val futureInstant = Instant.parse("2030-01-01T00:00:00Z")

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            tokenRequests.incrementAndGet()
            exchange.requestBody.readBytes()
            val body = """{"access_token":"stub-access-token","expires_in":3600,"token_type":"Bearer"}"""
            respond(exchange, 200, body)
        }
        server.createContext("/androidpublisher") { exchange ->
            playRequests.incrementAndGet()
            val key = exchange.requestURI.path
            when {
                key.endsWith("/tokens/missing") -> respond(exchange, 404, """{"error":{"code":404}}""")
                key.contains("boom") -> respond(exchange, 500, """{"error":{"code":500}}""")
                key.startsWith("/androidpublisher/v3/applications/$packageName/purchases/subscriptionsv2/tokens/") ->
                    respond(exchange, 200, subscriptionJson())
                else -> respond(exchange, 500, """{"error":{"code":500}}""")
            }
        }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun subscriptionJson(): String = """
        {
          "kind": "androidpublisher#subscriptionPurchaseV2",
          "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
          "acknowledgementState": "ACKNOWLEDGED",
          "lineItems": [
            {
              "productId": "com.glosso.app.pro",
              "expiryTime": "2030-01-01T00:00:00Z",
              "autoRenewingPlan": {"autoRenewEnabled": true},
              "offerDetails": {"basePlanId": "monthly", "offerId": "free-month"}
            }
          ]
        }
    """.trimIndent()

    private fun serviceAccountJson(): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded)
        val escaped = pem.replace("\n", "\\n")
        return """
            {
              "type": "service_account",
              "client_email": "glosso-billing@example.iam.gserviceaccount.com",
              "private_key": "$escaped",
              "token_uri": "http://127.0.0.1:$port/token"
            }
        """.trimIndent()
    }

    private fun client(): GooglePlaySubscriptionsClient {
        val key = ServiceAccountKey.fromJson(serviceAccountJson())
        val provider = ServiceAccountTokenProvider(key)
        return GooglePlaySubscriptionsClient(provider, baseUrl = "http://127.0.0.1:$port")
    }

    @Test
    fun `maps a Play subscription and reuses the access token`(): Unit = runBlocking {
        val client = client()

        val first = client.getSubscription(packageName, purchaseToken)
        val second = client.getSubscription(packageName, purchaseToken)

        assertEquals("SUBSCRIPTION_STATE_ACTIVE", first?.state)
        assertEquals("com.glosso.app.pro", first?.lineItems?.single()?.productId)
        assertEquals(futureInstant.toEpochMilli(), first?.lineItems?.single()?.expiryTimeMillis)
        assertTrue(first?.lineItems?.single()?.autoRenewing == true)
        assertEquals("free-month", first?.lineItems?.single()?.offerId)
        assertEquals(first, second)
        assertEquals(2, playRequests.get())
        assertEquals(1, tokenRequests.get(), "the access token must be reused")
    }

    @Test
    fun `an unknown token comes back as null, not an error`(): Unit = runBlocking {
        val client = client()

        val result = client.getSubscription(packageName, "missing")

        assertNull(result)
    }

    @Test
    fun `a Play outage surfaces as an exception so the caller can fail closed`(): Unit = runBlocking {
        val client = client()

        assertFailsWith<PlayApiException> {
            client.getSubscription(packageName, "boom")
        }
    }

    @Test
    fun `service account JSON must carry a client email and key`() {
        assertFailsWith<IllegalArgumentException> {
            ServiceAccountKey.fromJson("""{"client_email":"","private_key":""}""")
        }
    }
}
