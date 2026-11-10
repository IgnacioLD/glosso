package com.glosso.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.glosso.app.billing.SubscriptionPlan
import com.glosso.app.designsystem.GlossoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * With `pricing: null` the server has not put a plan on sale, so the paywall must
 * not paint any figure, not even one Play already returned. The offer (and only
 * it) is what carries the price, so hiding it is what keeps the "not on sale"
 * copy free of numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallOfferTest {

    @get:Rule
    val rule = createComposeRule()

    private fun plan(price: String) = SubscriptionPlan(
        productId = "me.shirobyte42.glosso.pro",
        title = "Glosso Pro",
        description = "The full course",
        offerToken = "offer-token",
        basePlanId = "base",
        offerId = null,
        priceFormatted = price,
        recurringPeriodIso = "P1M",
        freeTrialPeriod = null,
    )

    @Test
    fun `no price is painted when the server has nothing on sale`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                PaywallOffer(plan = plan("6,99 €"), loading = false, sellingEnabled = false)
            }
        }

        rule.onNodeWithText("6,99", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the Play price is painted only when a plan is on sale`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                PaywallOffer(plan = plan("6,99 €"), loading = false, sellingEnabled = true)
            }
        }

        rule.onNodeWithText("6,99", substring = true).assertIsDisplayed()
    }
}
