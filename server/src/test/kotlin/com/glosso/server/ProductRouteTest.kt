package com.glosso.server

import com.glosso.server.product.ClientConfig
import com.glosso.server.product.InMemoryAccountStore
import com.glosso.server.product.ProductConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `GET /v1/config` and `GET /v1/me`. These lock the config shape (with
 * `no-cache` + ETag), the legacy open-beta keys older app builds need to keep
 * every lesson unlocked, and the identity snapshot.
 */
class ProductRouteTest {

    private fun config(): ProductConfig = ProductConfig(
        flags = mapOf("accounts" to true),
        client = ClientConfig(2210, 2219, false),
    )

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
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body.getValue("flags").jsonObject.getValue("accounts").jsonPrimitive.boolean)
        val versions = body.getValue("client").jsonObject
        assertEquals("2210", versions.getValue("minimumSupportedVersion").jsonPrimitive.content)
        assertEquals("2219", versions.getValue("recommendedVersion").jsonPrimitive.content)
        assertFalse("plans" in body || "pricing" in body || "limits" in body, body.toString())
    }

    @Test
    fun `config keeps the open beta shape older apps unlock content with`() = testApplication {
        application {
            module(emptyAssets(), adminToken = null, analytics = null, productConfig = config())
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val body = Json.parseToJsonElement(client.get("/v1/config").bodyAsText()).jsonObject

        assertEquals("BETA", body.getValue("mode").jsonPrimitive.content)
        assertEquals(true, body.getValue("beta").jsonObject.getValue("active").jsonPrimitive.boolean)
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
    fun `me identifies the device and reports only its linked accounts`() = testApplication {
        val store = InMemoryAccountStore()
        application {
            module(emptyAssets(), adminToken = null, analytics = null, accountStore = store, productConfig = config())
        }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/v1/me") { header("X-Device-Id", "some-device") }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("linkedAccounts"), body.keys)
        assertTrue(body.getValue("linkedAccounts").jsonArray.isEmpty())
        assertNotNull(store.identityOf("some-device"))
    }

    @Test
    fun `me requires a device id`() = testApplication {
        application {
            module(
                emptyAssets(),
                adminToken = null,
                analytics = null,
                accountStore = InMemoryAccountStore(),
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
}
