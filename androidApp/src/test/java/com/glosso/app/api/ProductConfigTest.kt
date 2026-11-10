package com.glosso.app.api

import com.glosso.app.billing.AccessPolicy
import com.glosso.app.billing.ProductPlan
import com.glosso.app.product.formatBetaEndDate
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The frozen contract from docs/PRODUCT-CONFIG-CONTRACT.md, decoded into the
 * client model. The server owns every value here; these tests pin that the app
 * reads them as published and derives the same decisions the contract promises.
 */
class ProductConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val contract = """
        {
          "schema": 1,
          "mode": "BETA",
          "beta": {
            "active": true,
            "endDate": "2026-12-31T23:59:59Z",
            "founderEnabled": true,
            "founderBenefit": { "kind": "discount_percent", "value": null, "until": null, "label": "Ventaja Beta Founder" }
          },
          "plans": {
            "FREE":         { "pronunciation_analysis": "LIMITED", "exercises": "LIMITED", "analytics": "BASIC",   "personalization": "NONE" },
            "PRO":          { "pronunciation_analysis": "UNLIMITED", "exercises": "EXTENDED", "analytics": "ADVANCED", "personalization": "NONE" },
            "PERSONALIZED": { "pronunciation_analysis": "UNLIMITED", "exercises": "UNLIMITED", "analytics": "ADVANCED", "personalization": "FULL" }
          },
          "limits": { "FREE": { "pronunciation_analysis_per_day": 20, "history_days": 30 } },
          "pricing": {
            "PRO":          { "monthly": null, "yearly": null, "currency": null },
            "PERSONALIZED": { "monthly": null, "yearly": null, "currency": null }
          },
          "flags": { "beta_banner": true, "pro": false, "personalized": false, "accounts": false },
          "client": { "minimumSupportedVersion": 2210, "recommendedVersion": 2219, "forceUpdate": false }
        }
    """.trimIndent()

    private lateinit var previousLocale: Locale
    private lateinit var previousZone: TimeZone

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        // A timezone far ahead of UTC must not push 23:59:59Z into the next day.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousZone)
    }

    private fun config() = json.decodeFromString<ProductConfig>(contract)

    @Test
    fun `the contract decodes field by field`() {
        val config = config()
        assertEquals(1, config.schema)
        assertTrue(config.isBeta)
        assertTrue(config.beta.active)
        assertEquals("2026-12-31T23:59:59Z", config.beta.endDate)
        assertTrue(config.beta.founderEnabled)
        assertEquals(setOf("FREE", "PRO", "PERSONALIZED"), config.plans.keys)
        assertEquals("FULL", config.plans.getValue("PERSONALIZED").personalization)
        assertEquals(20, config.limits?.getValue("FREE")?.pronunciationAnalysisPerDay)
        assertEquals(30, config.limits?.getValue("FREE")?.historyDays)
        assertNull(config.pricingOf("PRO")?.monthly)
        assertFalse(config.flag("pro"))
        assertFalse("a missing flag is false", config.flag("does_not_exist"))
        assertEquals(2210, config.client.minimumSupportedVersion)
        assertEquals(2219, config.client.recommendedVersion)
    }

    @Test
    fun `the beta is free and sells nothing while pricing is null`() {
        val access = AccessPolicy.productAccess(config(), AccountState(plan = "FREE"))
        assertTrue(access.betaFree)
        assertTrue("the whole course is usable in the open beta", access.entitled)
        assertFalse("a null price is never for sale", access.sellingEnabled)
        assertEquals(ProductPlan.FREE, access.plan)
    }

    @Test
    fun `after the beta a published price enables selling and the server plan authorises`() {
        val paid = ProductConfig(
            mode = ProductConfig.MODE_FREE,
            flags = mapOf("pro" to true),
            pricing = mapOf("PRO" to PlanPricing(monthly = 4.99, currency = "EUR")),
        )
        val access = AccessPolicy.productAccess(paid, AccountState(plan = "PRO", betaFounder = true))
        assertFalse(access.betaFree)
        assertTrue(access.sellingEnabled)
        assertEquals(ProductPlan.PRO, access.plan)
        assertTrue(access.entitled)
    }

    @Test
    fun `no price means no selling even out of beta`() {
        val noPrice = ProductConfig(
            mode = ProductConfig.MODE_FREE,
            flags = mapOf("pro" to true),
            pricing = mapOf("PRO" to PlanPricing(monthly = null, yearly = null, currency = null)),
        )
        assertFalse(AccessPolicy.productAccess(noPrice, AccountState(plan = "FREE")).sellingEnabled)
    }

    @Test
    fun `an unknown plan code never becomes paid`() {
        assertEquals(ProductPlan.FREE, ProductPlan.fromWire(null))
        assertEquals(ProductPlan.FREE, ProductPlan.fromWire("bogus"))
        assertEquals(ProductPlan.PERSONALIZED, ProductPlan.fromWire("personalized"))
    }

    @Test
    fun `the version notice warns without ever blocking`() {
        val config = config()
        val current = AccessPolicy.versionNotice(config, currentVersion = 2219)
        assertFalse(current.visible)

        val outdated = AccessPolicy.versionNotice(config, currentVersion = 2210)
        assertTrue(outdated.outdated)
        assertFalse("2210 is exactly the minimum, not below it", outdated.belowMinimum)

        val below = AccessPolicy.versionNotice(config, currentVersion = 2000)
        assertTrue(below.belowMinimum)
        assertTrue(below.visible)
    }

    @Test
    fun `the end date is formatted from the server value and never invented`() {
        assertEquals("December 31, 2026", formatBetaEndDate("2026-12-31T23:59:59Z"))
        assertNull(formatBetaEndDate(null))
        assertNull(formatBetaEndDate(""))
        assertNull("garbage is not a date", formatBetaEndDate("not-a-date"))
    }

    @Test
    fun `the fallback keeps the beta open when the server never answers`() {
        val access = AccessPolicy.productAccess(ProductConfig.BetaFallback, null)
        assertTrue(access.betaFree)
        assertTrue(access.entitled)
        assertFalse(access.sellingEnabled)
        assertNull(formatBetaEndDate(ProductConfig.BetaFallback.beta.endDate))
    }

    /**
     * The literal body `GET /v1/config` serves today (PR #164). It differs from
     * [contract] in the two fields that are still undecided: `limits` and
     * `pricing` are JSON `null`, not objects. The mock used by the E2E never
     * produced this shape, so the app decoded the mock fine and crashed on the
     * real server. The fixture is the real bytes.
     */
    private val serverConfigBody: String by lazy { fixture("server-product-config.json") }

    /** The literal body `GET /v1/me` serves today: the benefit travels as a plain string. */
    private val serverMeBody: String by lazy { fixture("server-me.json") }

    @Test
    fun `the real server config body with null limits and pricing decodes whole`() {
        val config = json.decodeFromString<ProductConfig>(serverConfigBody)
        assertEquals(1, config.schema)
        assertTrue(config.isBeta)
        assertTrue(config.beta.active)
        assertEquals("2026-12-31T23:59:59Z", config.beta.endDate)
        assertEquals("Ventaja Beta Founder", config.beta.founderBenefit?.label)
        assertEquals(setOf("FREE", "PRO", "PERSONALIZED"), config.plans.keys)
        assertNull("limits is null while undecided", config.limits)
        assertNull("pricing is null while undecided", config.pricing)
        assertNull(config.pricingOf("PRO"))
        assertTrue(config.flag("beta_banner"))
        assertFalse("a missing flag is still false", config.flag("pro"))
        assertEquals(2210, config.client.minimumSupportedVersion)
    }

    @Test
    fun `the real server me body decodes betaFounderBenefit as a string`() {
        val account = json.decodeFromString<AccountState>(serverMeBody)
        assertEquals("FREE", account.plan)
        assertTrue(account.betaFounder)
        assertEquals("Ventaja Beta Founder", account.betaFounderBenefit)
    }

    @Test
    fun `the real config body keeps the beta free and sells nothing`() {
        val account = json.decodeFromString<AccountState>(serverMeBody)
        val access = AccessPolicy.productAccess(json.decodeFromString<ProductConfig>(serverConfigBody), account)
        assertTrue(access.betaFree)
        assertTrue("the whole course is usable", access.entitled)
        assertFalse("null pricing never sells", access.sellingEnabled)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }
}
