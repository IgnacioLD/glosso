package com.glosso.app

import com.glosso.app.platform.ScreenshotProtection
import com.glosso.app.platform.currentPlatformActivity
import com.glosso.app.platform.rememberPlatformActions
import com.glosso.app.platform.PlatformBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.glosso.app.billing.AccessPolicy
import com.glosso.app.billing.ProductAccess
import com.glosso.app.billing.SubscriptionPlan
import com.glosso.app.billing.SubscriptionStatus
import com.glosso.app.billing.offerLine
import com.glosso.app.billing.periodFrequency
import com.glosso.app.billing.trialLabel
import com.glosso.app.designsystem.Elevation
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Motion
import com.glosso.app.designsystem.Radius
import com.glosso.app.designsystem.Sizes
import com.glosso.app.designsystem.Space
import com.glosso.app.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The paywall. It shows exactly what Play returned (trial length and price), one
 * primary action, a restore control, a way to manage or cancel in Google Play and
 * the required legal note. It never invents a price or a trial length.
 *
 * Drawn as an in-app overlay, not a dialog: a visible scrim to tap, a grabber to
 * drag down, an explicit close button and a back press that always closes the
 * sheet first, never the lesson or the app underneath.
 */
@Composable
fun PaywallSheet(
    vm: SubscriptionViewModel = subscriptionViewModel(),
    /** What the server says this device may buy; nothing is sold while it is off. */
    productAccess: ProductAccess = ProductAccess.Unknown,
    /** The paid level the learner reached, when the wall is the CEFR frontier. */
    nextLevel: String? = null,
    onDismiss: () -> Unit,
) {
    val status by vm.status.collectAsState()
    val plan by vm.plan.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val activity = currentPlatformActivity()
    val actions = rememberPlatformActions()
    val config = LocalAppConfig.current
    val entitled = AccessPolicy.isEntitled(status, productAccess)

    // Usage statistics: which wall was seen (a CEFR frontier or Pro in general).
    val source = if (nextLevel != null) "level" else "general"
    LaunchedEffect(Unit) { Analytics.track("paywall_viewed", mapOf("source" to source)) }

    // Back closes the paywall first, never the lesson or the app behind it.
    PlatformBackHandler(enabled = true) { onDismiss() }

    val dismissPx = with(LocalDensity.current) { 140.dp.toPx() }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val renderOffset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else 0f,
        animationSpec = tween(if (dragging) 0 else 220, easing = Motion.standard),
        label = "paywallOffset",
    )
    // The paywall shows the offer, price and trial. Mark the window secure only
    // while it is on screen, so its contents cannot be captured by a screenshot
    // or shown in the recents thumbnail; the practice screens stay capturable.
    ScreenshotProtection()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(Res.string.close),
                    onClick = onDismiss,
                ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
            shadowElevation = Elevation.sheet,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .offset { IntOffset(0, renderOffset.roundToInt().coerceAtLeast(0)) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter)
                    .navigationBarsPadding()
                    .padding(bottom = Space.x24),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                                },
                                onDragEnd = {
                                    if (dragOffset > dismissPx) {
                                        onDismiss()
                                    } else {
                                        dragOffset = 0f
                                        dragging = false
                                    }
                                },
                                onDragCancel = {
                                    dragOffset = 0f
                                    dragging = false
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(Radius.full))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(Res.string.library_pro_title))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.paywall_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Space.x12))
                Text(
                    text = if (nextLevel != null) {
                        stringResource(Res.string.paywall_headline_next_level)
                    } else {
                        stringResource(Res.string.paywall_headline)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = if (nextLevel != null) {
                        stringResource(Res.string.paywall_body_next_level, nextLevel)
                    } else {
                        stringResource(Res.string.paywall_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PaywallOffer(
                    plan = plan,
                    loading = loading,
                    sellingEnabled = productAccess.sellingEnabled,
                )
                Spacer(Modifier.height(Space.x16))

                BenefitRow(stringResource(Res.string.paywall_benefit_course))
                BenefitRow(stringResource(Res.string.paywall_benefit_offline))
                BenefitRow(stringResource(Res.string.paywall_benefit_practice))

                Spacer(Modifier.height(Space.x20))
                StatusCard(
                    status = status,
                    plan = plan,
                    sellingEnabled = productAccess.sellingEnabled,
                    productAccess = productAccess,
                )

                error?.let {
                    Spacer(Modifier.height(Space.x12))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(Space.x20))
                if (!entitled && productAccess.sellingEnabled) {
                    PrimaryButton(
                        text = subscribeLabel(plan),
                        onClick = {
                            Analytics.track("pro_cta_tapped", mapOf("source" to source))
                            activity?.let { vm.subscribe(it) }
                        },
                        enabled = plan != null && status != SubscriptionStatus.PENDING,
                    )
                } else if (!entitled) {
                    // The server has not put a plan on sale: show no price and no
                    // purchase, and say why instead of inviting a buy that cannot
                    // happen. Never invents a number.
                    Text(
                        text = stringResource(Res.string.paywall_not_for_sale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(Space.x8))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    QuietTextButton(stringResource(Res.string.paywall_restore), onClick = { vm.refresh() })
                }

                if (status != SubscriptionStatus.NONE) {
                    Spacer(Modifier.height(Space.x4))
                    Text(
                        text = stringResource(Res.string.paywall_manage),
                        style = MaterialTheme.typography.labelLarge,
                        color = GlossoColors.accentText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Sizes.touch)
                            .wrapContentHeight(Alignment.CenterVertically)
                            .clickable { actions.openSubscriptionManagement(productId(plan, config)) }
                            .padding(vertical = Space.x8),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(Space.x12))
                Hairline()
                Spacer(Modifier.height(Space.x12))
                Text(
                    text = legalNote(plan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun subscribeLabel(plan: SubscriptionPlan?): String {
    if (plan == null) return stringResource(Res.string.paywall_cta_default)
    plan.trialLabel()?.let { return stringResource(Res.string.paywall_cta_trial, it) }
    if (plan.priceFormatted.isNotBlank()) {
        return stringResource(Res.string.paywall_cta_price, plan.priceFormatted)
    }
    return stringResource(Res.string.paywall_cta_default)
}

/**
 * The offer block, and the only place a price can be drawn. It is shown only when
 * the server has put a plan on sale: with a null price there must be no figure on
 * screen, not even one Play already returned, so the paywall keeps the honest
 * "not on sale" line and nothing that reads like a price next to it.
 */
@Composable
internal fun PaywallOffer(
    plan: SubscriptionPlan?,
    loading: Boolean,
    sellingEnabled: Boolean,
) {
    if (!sellingEnabled) return
    Spacer(Modifier.height(Space.x20))
    OfferCard(plan = plan, loading = loading)
}

@Composable
private fun OfferCard(plan: SubscriptionPlan?, loading: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.l),
        border = cardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.x16)) {
            Text(
                text = plan?.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.paywall_default_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Space.x8))
            val line = plan?.offerLine()?.takeIf { it.isNotBlank() }
            Text(
                text = when {
                    line != null -> line
                    loading -> stringResource(Res.string.paywall_price_loading)
                    else -> stringResource(Res.string.paywall_price_unavailable)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            plan?.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Space.x4))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(Space.x12))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun StatusCard(
    status: SubscriptionStatus,
    plan: SubscriptionPlan?,
    sellingEnabled: Boolean,
    productAccess: ProductAccess = ProductAccess.Unknown,
) {
    val message: String = when {
        productAccess.lifetimeGrant != null -> {
            if (productAccess.betaFounder) {
                stringResource(Res.string.entitlement_lifetime_founder)
            } else {
                stringResource(Res.string.entitlement_lifetime_pro)
            }
        }
        status == SubscriptionStatus.NONE -> return
        status == SubscriptionStatus.TRIAL -> {
            // The price is a figure, so it is painted only when the server has
            // actually put a plan on sale. With selling off, even a price Play
            // returned would read like an offer next to the "not on sale" line,
            // so the trial status is stated without any number.
            val price = plan?.priceFormatted?.takeIf { it.isNotBlank() }
            if (sellingEnabled && price != null) {
                stringResource(Res.string.paywall_status_trial, price)
            } else {
                stringResource(Res.string.paywall_status_trial_no_price)
            }
        }

        status == SubscriptionStatus.ACTIVE -> stringResource(Res.string.paywall_status_active)
        status == SubscriptionStatus.GRACE -> stringResource(Res.string.paywall_status_grace)
        status == SubscriptionStatus.CANCELED -> stringResource(Res.string.paywall_status_canceled)
        status == SubscriptionStatus.PENDING -> stringResource(Res.string.paywall_status_pending)
        else -> return
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.m),
        border = BorderStroke(1.dp, GlossoColors.success.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Space.x16), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun legalNote(plan: SubscriptionPlan?): String {
    val frequency = plan?.let { periodFrequency(it.recurringPeriodIso) }
    val base = if (frequency != null) {
        stringResource(Res.string.paywall_legal, frequency)
    } else {
        stringResource(Res.string.paywall_legal_no_frequency)
    }
    val trial = plan?.trialLabel()
    val trialWarning = trial?.let { stringResource(Res.string.paywall_legal_trial, it) }
    // The sentences carry no leading or trailing whitespace and are joined with
    // a plain space, so the note can never read "...canceles.La prueba...".
    return listOfNotNull(
        base,
        trialWarning?.takeIf { it.isNotBlank() },
        stringResource(Res.string.paywall_legal_manage),
    ).joinToString(" ")
}

private fun productId(plan: SubscriptionPlan?, config: AppConfig): String =
    plan?.productId?.takeIf { it.isNotBlank() } ?: config.subscriptionProductId
