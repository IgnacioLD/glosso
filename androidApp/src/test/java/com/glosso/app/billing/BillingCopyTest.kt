package com.glosso.app.billing

import com.glosso.app.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The paywall copy must never carry a price or a trial length: both come from
 * Play's [com.android.billingclient.api.ProductDetails] at runtime. A `paywall_*`
 * / `billing_*` string that hardcodes an amount, a duration or a currency symbol
 * fails here. The only numbers allowed are content references like "A1" (a digit
 * directly after a letter); everything else must arrive as a format argument.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BillingCopyTest {

    private val formatArg = Regex("%[0-9]+\\\$[a-zA-Z]")
    private val literalNumber = Regex("(?<![A-Za-z])\\d")
    private val currency = Regex("[$€£¥]")

    @Test
    fun `no paywall or billing string hardcodes a number or currency`() {
        val context = RuntimeEnvironment.getApplication()
        val offenders = R.string::class.java.fields
            .filter { it.name.startsWith("paywall_") || it.name.startsWith("billing_") }
            .mapNotNull { field ->
                val value = context.getString(field.getInt(null)).replace(formatArg, "")
                val bad = literalNumber.containsMatchIn(value) || currency.containsMatchIn(value)
                if (bad) "${field.name} = $value" else null
            }

        assertTrue(
            "billing copy must come from Play, found literals: $offenders",
            offenders.isEmpty(),
        )
    }
}
