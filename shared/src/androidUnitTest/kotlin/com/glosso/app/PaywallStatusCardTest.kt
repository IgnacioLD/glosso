package com.glosso.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.glosso.app.billing.SubscriptionPlan
import com.glosso.app.billing.SubscriptionStatus
import com.glosso.app.designsystem.GlossoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trial status card states the trial without a figure unless the server has
 * put a plan on sale. When selling is off, a price Play already returned is a
 * number the paywall must not paint, exactly like the offer card next to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaywallStatusCardTest {

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
    fun `the trial price is not painted when nothing is on sale`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                StatusCard(
                    status = SubscriptionStatus.TRIAL,
                    plan = plan("6,99 €"),
                    sellingEnabled = false,
                )
            }
        }

        rule.onNodeWithText("6,99", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the trial price is painted only when a plan is on sale`() {
        rule.setContent {
            GlossoTheme(darkTheme = true) {
                StatusCard(
                    status = SubscriptionStatus.TRIAL,
                    plan = plan("6,99 €"),
                    sellingEnabled = true,
                )
            }
        }

        rule.onNodeWithText("6,99", substring = true).assertIsDisplayed()
    }
}
