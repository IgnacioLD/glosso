package com.glosso.app.billing

import androidx.compose.runtime.Composable
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Localized wording for the billing periods. The ISO values come from Play; only
 * the human form lives here, in the interface language.
 */

/** Human label for an ISO-8601 period, e.g. "1 mes", "3 months". */
@Composable
fun periodLabel(iso: String?): String? {
    val parts = BillingPeriods.parts(iso) ?: return null
    val plural = when (parts.unit) {
        PeriodUnit.DAY -> Res.plurals.billing_period_days
        PeriodUnit.WEEK -> Res.plurals.billing_period_weeks
        PeriodUnit.MONTH -> Res.plurals.billing_period_months
        PeriodUnit.YEAR -> Res.plurals.billing_period_years
    }
    return pluralStringResource(plural, parts.count, parts.count)
}

/** Frequency phrase for a recurring charge, e.g. "al mes", "every 3 months". */
@Composable
fun periodFrequency(iso: String?): String? {
    val parts = BillingPeriods.parts(iso) ?: return null
    val plural = when (parts.unit) {
        PeriodUnit.DAY -> Res.plurals.billing_freq_days
        PeriodUnit.WEEK -> Res.plurals.billing_freq_weeks
        PeriodUnit.MONTH -> Res.plurals.billing_freq_months
        PeriodUnit.YEAR -> Res.plurals.billing_freq_years
    }
    return pluralStringResource(plural, parts.count, parts.count)
}

/** The free-trial length in words, or null when the offer has no trial. */
@Composable
fun SubscriptionPlan.trialLabel(): String? = periodLabel(freeTrialPeriod)

/**
 * The honest one-liner the paywall shows, built only from Play's own price and
 * period: "1 mes gratis, luego 6,99 € al mes".
 */
@Composable
fun SubscriptionPlan.offerLine(): String {
    val trial = periodLabel(freeTrialPeriod)
    val frequency = periodFrequency(recurringPeriodIso)
    val price = priceFormatted.ifBlank { null }
    val paid = price?.let {
        if (frequency != null) {
            stringResource(Res.string.billing_price_with_frequency, it, frequency)
        } else {
            it
        }
    }
    return when {
        hasFreeTrial && paid != null ->
            stringResource(Res.string.billing_offer_with_trial, trial.orEmpty(), paid)

        hasFreeTrial -> stringResource(Res.string.billing_offer_trial_only, trial.orEmpty())
        paid != null -> paid
        else -> ""
    }
}
