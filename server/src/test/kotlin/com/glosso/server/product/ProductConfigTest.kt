package com.glosso.server.product

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The environment is the single authority for the frozen config names and their
 * safe defaults. These lock the defaults, the overrides and the exact served
 * shape (including the nulls that mean "not decided yet").
 */
class ProductConfigTest {

    @Test
    fun `defaults are the open beta with no priced benefit`() {
        val config = ProductConfig.fromEnv(emptyMap())

        assertEquals(ProductMode.BETA, config.mode)
        assertEquals(ProductConfig.DEFAULT_END_DATE, config.beta.endDate)
        assertEquals(Instant.parse("2027-12-31T23:59:59Z"), config.beta.endDate)
        assertEquals(Instant.parse("2027-12-31T23:59:59Z"), config.beta.founderUntil)
        assertTrue(config.beta.founderBenefit.grantsProLifetime)
        assertTrue(config.beta.founderEnabled)
        assertEquals("PRO_LIFETIME", config.beta.founderBenefit.kind)
        assertNull(config.beta.founderBenefit.value)
        assertNull(config.beta.founderBenefit.until)
        assertEquals("Glosso Pro gratis para siempre", config.beta.founderBenefit.label)
        assertEquals(2210, config.client.minimumSupportedVersion)
        assertEquals(2219, config.client.recommendedVersion)
        assertEquals(false, config.client.forceUpdate)
        assertEquals(true, config.flagsAt(Instant.parse("2026-10-01T00:00:00Z"))["beta_banner"])
        assertEquals(false, config.flags["pro"])
        assertEquals(ProductConfig.DEFAULT_PLANS, config.plans)
        assertNull(config.limits)
        assertNull(config.pricing)
    }

    @Test
    fun `the environment overrides every frozen name`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_PRODUCT_MODE" to "FREE",
                "GLOSSO_BETA_END_DATE" to "2025-01-02T03:04:05Z",
                "GLOSSO_BETA_FOUNDER_ENABLED" to "false",
                "GLOSSO_BETA_FOUNDER_BENEFIT_KIND" to "lifetime_percent",
                "GLOSSO_BETA_FOUNDER_BENEFIT_VALUE" to "30",
                "GLOSSO_BETA_FOUNDER_BENEFIT_LABEL" to "Precio fundador",
                "GLOSSO_BETA_FOUNDER_UNTIL" to "2027-06-30T23:59:59Z",
                "GLOSSO_MIN_CLIENT_VERSION" to "2300",
                "GLOSSO_RECOMMENDED_CLIENT_VERSION" to "2400",
                "GLOSSO_FORCE_UPDATE" to "true",
                "GLOSSO_FLAG_PRO" to "true",
                "GLOSSO_FLAG_SOMETHING_NEW" to "true",
            ),
        )

        assertEquals(ProductMode.FREE, config.mode)
        assertEquals(Instant.parse("2025-01-02T03:04:05Z"), config.beta.endDate)
        assertEquals(false, config.beta.founderEnabled)
        assertEquals("lifetime_percent", config.beta.founderBenefit.kind)
        assertEquals(30, config.beta.founderBenefit.value)
        assertNull(config.beta.founderBenefit.until)
        assertFalse(config.beta.founderBenefit.grantsProLifetime)
        assertEquals("Precio fundador", config.beta.founderBenefit.label)
        assertEquals(Instant.parse("2027-06-30T23:59:59Z"), config.beta.founderUntil)
        assertEquals(2300, config.client.minimumSupportedVersion)
        assertEquals(2400, config.client.recommendedVersion)
        assertEquals(true, config.client.forceUpdate)
        assertEquals(true, config.flags["pro"])
        assertEquals(true, config.flags["something_new"])
        assertEquals(false, config.flagsAt(Instant.parse("2024-01-01T00:00:00Z"))["beta_banner"])
    }

    @Test
    fun `an unparseable date falls back to the safe default`() {
        val config = ProductConfig.fromEnv(
            mapOf("GLOSSO_BETA_END_DATE" to "not-a-date", "GLOSSO_BETA_FOUNDER_UNTIL" to "2027"),
        )
        assertEquals(ProductConfig.DEFAULT_END_DATE, config.beta.endDate)
        assertEquals(ProductConfig.DEFAULT_FOUNDER_UNTIL, config.beta.founderUntil)
    }

    @Test
    fun `the beta end date and the founder window are independent`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_BETA_END_DATE" to "2027-06-30T23:59:59Z",
                "GLOSSO_BETA_FOUNDER_UNTIL" to "2027-12-31T23:59:59Z",
            ),
        )
        assertFalse(config.betaActiveAt(Instant.parse("2027-09-01T00:00:00Z")))
        assertTrue(config.isFounderAlta(Instant.parse("2027-09-01T00:00:00Z")))
        assertTrue(config.isFounderAlta(Instant.parse("2027-12-31T23:59:59Z")))
        assertFalse(config.isFounderAlta(Instant.parse("2028-01-01T00:00:00Z")))

        // And the other way round: the founder window can close before the beta.
        val early = ProductConfig.fromEnv(mapOf("GLOSSO_BETA_FOUNDER_UNTIL" to "2027-01-31T23:59:59Z"))
        assertTrue(early.betaActiveAt(Instant.parse("2027-03-01T00:00:00Z")))
        assertFalse(early.isFounderAlta(Instant.parse("2027-03-01T00:00:00Z")))
        val disabled = ProductConfig.fromEnv(mapOf("GLOSSO_BETA_FOUNDER_ENABLED" to "false"))
        assertFalse(disabled.isFounderAlta(Instant.parse("2026-10-01T00:00:00Z")))
    }

    @Test
    fun `blank variables from docker compose count as unset`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_PRODUCT_MODE" to "",
                "GLOSSO_BETA_END_DATE" to "",
                "GLOSSO_BETA_FOUNDER_UNTIL" to " ",
                "GLOSSO_BETA_FOUNDER_BENEFIT_KIND" to "",
                "GLOSSO_BETA_FOUNDER_BENEFIT_LABEL" to "",
                "GLOSSO_FLAG_BETA_BANNER" to "",
                "GLOSSO_FLAG_PRO" to "",
                "GLOSSO_PLANS_JSON" to "",
            ),
        )
        assertEquals(ProductMode.BETA, config.mode)
        assertEquals(ProductConfig.DEFAULT_END_DATE, config.beta.endDate)
        assertEquals(ProductConfig.DEFAULT_FOUNDER_UNTIL, config.beta.founderUntil)
        assertEquals("PRO_LIFETIME", config.beta.founderBenefit.kind)
        assertEquals("Glosso Pro gratis para siempre", config.beta.founderBenefit.label)
        assertEquals(true, config.flagsAt(Instant.parse("2026-10-01T00:00:00Z"))["beta_banner"])
        assertEquals(false, config.flags["pro"])
        assertEquals(ProductConfig.DEFAULT_PLANS, config.plans)
    }

    @Test
    fun `the recommended version never sits below the minimum`() {
        val config = ProductConfig.fromEnv(
            mapOf("GLOSSO_MIN_CLIENT_VERSION" to "2300", "GLOSSO_RECOMMENDED_CLIENT_VERSION" to "2250"),
        )
        assertEquals(2300, config.client.recommendedVersion)
    }

    @Test
    fun `plan entitlements are overridden key by key and never lose a key`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_PLANS_JSON" to
                    """{"FREE":{"exercises":"STANDARD","offline_packs":false},""" +
                    """"PRO":{"personalized_learning":true},"ENTERPRISE":{"exercises":"ALL"}}""",
            ),
        )
        val free = config.plans.getValue("FREE")
        assertEquals("STANDARD", free.getValue("exercises").jsonPrimitive.content)
        assertEquals(false, free.getValue("offline_packs").jsonPrimitive.boolean)
        assertEquals("LIMITED", free.getValue("pronunciation_analysis").jsonPrimitive.content)
        assertEquals(true, config.plans.getValue("PRO").getValue("personalized_learning").jsonPrimitive.boolean)
        assertEquals(ProductConfig.DEFAULT_PLANS.getValue("PERSONALIZED"), config.plans.getValue("PERSONALIZED"))
        assertEquals(setOf("FREE", "PRO", "PERSONALIZED"), config.plans.keys)

        val broken = ProductConfig.fromEnv(mapOf("GLOSSO_PLANS_JSON" to "[1,2]"))
        assertEquals(ProductConfig.DEFAULT_PLANS, broken.plans)
    }

    @Test
    fun `limits and pricing can be fixed from configuration`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_LIMITS_JSON" to
                    """{"FREE":{"pronunciation_analysis_per_day":20,"history_days":30}}""",
                "GLOSSO_PRICING_JSON" to
                    """{"PRO":{"monthly":null,"yearly":null,"currency":"EUR"}}""",
            ),
        )

        assertNotNull(config.limits)
        assertNotNull(config.pricing)
        val body = config.toJson()
        assertTrue(body.contains("\"pronunciation_analysis_per_day\":20"), body)
        assertTrue(body.contains("\"history_days\":30"), body)
        assertTrue(body.contains("\"currency\":\"EUR\""), body)
    }

    @Test
    fun `an unparseable limits or pricing blob stays null`() {
        val config = ProductConfig.fromEnv(
            mapOf(
                "GLOSSO_LIMITS_JSON" to "{not json",
                "GLOSSO_PRICING_JSON" to "   ",
            ),
        )
        assertNull(config.limits)
        assertNull(config.pricing)
    }

    @Test
    fun `the served JSON is the contract shape`() {
        val json = Json.parseToJsonElement(
            ProductConfig.fromEnv(emptyMap()).toJson(Instant.parse("2026-10-01T00:00:00Z")),
        ).jsonObject

        assertEquals(1, json.getValue("schema").jsonPrimitive.int)
        assertEquals("BETA", json.getValue("mode").jsonPrimitive.content)

        val beta = json.getValue("beta").jsonObject
        assertEquals(true, beta.getValue("active").jsonPrimitive.boolean)
        assertEquals("2027-12-31T23:59:59Z", beta.getValue("endDate").jsonPrimitive.content)
        assertEquals(true, beta.getValue("founderEnabled").jsonPrimitive.boolean)
        val benefit = beta.getValue("founderBenefit").jsonObject
        assertEquals("PRO_LIFETIME", benefit.getValue("kind").jsonPrimitive.content)
        assertTrue(benefit.getValue("value") is JsonNull)
        assertTrue(benefit.getValue("until") is JsonNull)
        assertEquals("Glosso Pro gratis para siempre", benefit.getValue("label").jsonPrimitive.content)
        assertEquals("2027-12-31T23:59:59Z", beta.getValue("founderUntil").jsonPrimitive.content)

        val plans = json.getValue("plans").jsonObject
        assertEquals(setOf("FREE", "PRO", "PERSONALIZED"), plans.keys)
        assertEquals(
            "LIMITED",
            plans.getValue("FREE").jsonObject.getValue("pronunciation_analysis").jsonPrimitive.content,
        )
        assertEquals("FULL", plans.getValue("PERSONALIZED").jsonObject.getValue("personalization").jsonPrimitive.content)
        assertEquals(false, plans.getValue("PRO").jsonObject.getValue("personalized_learning").jsonPrimitive.boolean)
        assertEquals(true, plans.getValue("PERSONALIZED").jsonObject.getValue("personalized_learning").jsonPrimitive.boolean)

        assertTrue(json.getValue("limits") is JsonNull)
        assertTrue(json.getValue("pricing") is JsonNull)

        val flags = json.getValue("flags").jsonObject
        assertEquals(true, flags.getValue("beta_banner").jsonPrimitive.boolean)
        assertEquals(false, flags.getValue("pro").jsonPrimitive.boolean)

        val client = json.getValue("client").jsonObject
        assertEquals(2210, client.getValue("minimumSupportedVersion").jsonPrimitive.int)
        assertEquals(2219, client.getValue("recommendedVersion").jsonPrimitive.int)
        assertEquals(false, client.getValue("forceUpdate").jsonPrimitive.boolean)
    }
}
