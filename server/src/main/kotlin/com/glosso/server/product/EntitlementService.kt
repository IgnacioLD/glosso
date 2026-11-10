package com.glosso.server.product

import com.glosso.server.api.LifetimeGrantDto
import com.glosso.server.api.MeResponse
import com.glosso.server.api.SubscriptionDto
import com.glosso.server.billing.EntitlementStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject

/** Where the effective plan comes from. */
enum class PlanSource { LIFETIME, SUBSCRIPTION, FREE }

/** The outcome of [EntitlementService.signInWithGoogle]. */
sealed interface GoogleSignInResult {
    /** Signed in; [moved] is true when the account came from another device. */
    data class SignedIn(val me: MeState, val moved: Boolean) : GoogleSignInResult

    /** The token is not a valid Google ID token for this server and device. */
    data object InvalidToken : GoogleSignInResult

    /** This device's account is already linked to a different Google account. */
    data object Conflict : GoogleSignInResult
}

/** The server-computed identity snapshot served by `GET /v1/me`. */
data class MeState(
    val plan: String,
    val status: String,
    val betaFounder: Boolean,
    val betaFounderSince: Instant?,
    val betaFounderBenefit: String?,
    val expiresAt: Instant?,
    val betaFounderUntil: Instant? = null,
    val planSource: PlanSource = PlanSource.FREE,
    val lifetimeGrants: List<LifetimeGrant> = emptyList(),
    val betaAccess: Boolean = false,
    val entitlements: JsonObject = JsonObject(emptyMap()),
    val subscription: StoredEntitlement? = null,
    val linkedAccounts: List<String> = emptyList(),
)

private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

fun MeState.toResponse(): MeResponse = MeResponse(
    plan = plan,
    status = status,
    betaFounder = betaFounder,
    betaFounderSince = betaFounderSince?.let(ISO::format),
    betaFounderBenefit = betaFounderBenefit,
    expiresAt = expiresAt?.let(ISO::format),
    betaFounderUntil = betaFounderUntil?.let(ISO::format),
    planSource = planSource.name,
    lifetimeGrants = lifetimeGrants.map { LifetimeGrantDto(it.plan, it.source, ISO.format(it.grantedAt)) },
    betaAccess = betaAccess,
    entitlements = entitlements,
    subscription = subscription?.let {
        SubscriptionDto(
            status = it.status,
            provider = it.provider,
            source = it.source,
            startedAt = ISO.format(it.startedAt),
            expiresAt = it.expiresAt?.let(ISO::format),
            autoRenew = it.autoRenew,
        )
    },
    linkedAccounts = linkedAccounts,
)

/**
 * Decides identity, Beta Founder status and the effective plan. The client never
 * writes any of it; it only caches what the server answers.
 *
 * Beta Founder is permanent: marked once, when the founder flag is enabled and
 * the alta happened on or before the founder window end
 * ([BetaConfig.founderUntil]). The window is independent of the beta end date
 * and of the current mode. The status is never cleared or recomputed when the
 * plan changes, the beta ends or the window closes.
 *
 * With the `PRO_LIFETIME` benefit a founder also gets a lifetime grant of PRO:
 * a one-time entitlement with no expiry, renewal or grace period, persisted in
 * `glosso.lifetime_grants` so a later config change cannot take it away. It
 * never grants `PERSONALIZED`. Subscriptions are a separate, orthogonal
 * mechanism: the effective plan is the highest of the in-force subscription and
 * the lifetime grants.
 *
 * While the free beta is open every device, founder or not, gets full access
 * ([MeState.betaAccess] and the top plan's entitlements); [MeState.plan] still
 * says what the user keeps once the beta ends.
 */
class EntitlementService(
    private val store: EntitlementStore,
    private val config: ProductConfig,
    private val clock: () -> Instant = Instant::now,
    /** Keys sign-in nonces; null disables Sign in with Google. */
    private val accountSecret: AccountSecret? = null,
    /** Verifies Google ID tokens; null disables Sign in with Google. */
    private val googleVerifier: GoogleIdTokenVerifier? = null,
) {
    /** Sign in with Google needs a client id to verify against and the nonce secret. */
    val googleSignInEnabled: Boolean get() = accountSecret != null && googleVerifier != null

    fun identify(deviceId: String): UserIdentity {
        val now = clock()
        val upsert = store.upsertUser(deviceId, now)
        var identity = upsert.identity
        if (!identity.betaFounder && config.isFounderAlta(identity.createdAt)) {
            store.markBetaFounder(
                userId = identity.id,
                since = identity.createdAt,
                until = config.beta.founderBenefit.until,
                benefit = config.beta.founderBenefit.kind,
            )
            identity = identity.copy(
                betaFounder = true,
                betaFounderSince = identity.createdAt,
                betaFounderUntil = config.beta.founderBenefit.until,
                betaFounderBenefit = config.beta.founderBenefit.kind,
            )
            if (config.beta.founderBenefit.grantsProLifetime) {
                store.grantLifetime(identity.id, "PRO", BETA_FOUNDER_SOURCE, identity.createdAt)
            }
        }
        return identity
    }

    /**
     * A fresh nonce for Sign in with Google on [deviceId]. The app hands it to
     * Credential Manager, Google signs it into the ID token, and
     * [signInWithGoogle] only accepts a token carrying a nonce issued for the
     * same device, so a token cannot be replayed from another device.
     */
    fun googleSignInNonce(deviceId: String): String {
        val secret = checkNotNull(accountSecret) { "sign-in is not configured" }
        return secret.signInNonce(deviceId, clock())
    }

    /**
     * Signs [deviceId] in with a Google ID token chosen in the system account
     * picker. The first sign-in links the Google account to this device's
     * account; a sign-in on another device moves the account there, so the
     * Beta Founder status and the lifetime PRO follow the person, not the
     * phone. Only the token's opaque `sub` is kept.
     */
    fun signInWithGoogle(deviceId: String, idToken: String): GoogleSignInResult {
        val secret = checkNotNull(accountSecret) { "sign-in is not configured" }
        val verifier = checkNotNull(googleVerifier) { "sign-in is not configured" }
        val google = verifier.verify(idToken) ?: return GoogleSignInResult.InvalidToken
        val nonce = google.nonce ?: return GoogleSignInResult.InvalidToken
        if (!secret.isValidSignInNonce(nonce, deviceId, clock())) return GoogleSignInResult.InvalidToken
        // The device row must exist (and be marked founder if due) before it
        // can be linked or merged into an account.
        identify(deviceId)
        return when (val result = store.signInWithExternalAccount(GOOGLE_PROVIDER, google.subject, deviceId, clock())) {
            ExternalSignIn.Conflict -> GoogleSignInResult.Conflict
            is ExternalSignIn.SignedIn -> GoogleSignInResult.SignedIn(me(deviceId), result.moved)
        }
    }

    fun me(deviceId: String): MeState {
        val identity = identify(deviceId)
        val now = clock()
        val grants = founderGrants(identity)
        val stored = store.entitlement(identity.id)
        val entitled = stored != null && stored.isInForce(now)
        val subscriptionPlan = stored?.plan?.takeIf { entitled && it in PAID_PLANS }
        val lifetimePlan = grants.map { it.plan }.filter { it in PAID_PLANS }.maxByOrNull(::planRank)
        // A lifetime plan wins a tie: permanent access is what the user has,
        // and a PRO subscription on top of a founder PRO adds nothing to show.
        val planSource = when {
            subscriptionPlan != null && planRank(subscriptionPlan) > planRank(lifetimePlan) -> PlanSource.SUBSCRIPTION
            lifetimePlan != null -> PlanSource.LIFETIME
            else -> PlanSource.FREE
        }
        val plan = when (planSource) {
            PlanSource.SUBSCRIPTION -> subscriptionPlan!!
            PlanSource.LIFETIME -> lifetimePlan!!
            PlanSource.FREE -> "FREE"
        }
        // Plan and status must agree: a paid status only ever comes with the
        // subscription that grants the plan. A lifetime plan has no renewal,
        // so it reports NONE with no expiry; the subscription, if any, is still
        // described in [MeState.subscription].
        val status = when {
            planSource == PlanSource.SUBSCRIPTION -> stored!!.status
            planSource == PlanSource.FREE && stored?.status == EntitlementStatus.PENDING.name ->
                EntitlementStatus.PENDING.name
            else -> EntitlementStatus.NONE.name
        }
        return MeState(
            plan = plan,
            status = status,
            betaFounder = identity.betaFounder,
            betaFounderSince = identity.betaFounderSince,
            betaFounderBenefit = identity.betaFounderBenefit,
            expiresAt = if (planSource == PlanSource.SUBSCRIPTION) stored!!.expiresAt else null,
            betaFounderUntil = identity.betaFounderUntil,
            planSource = planSource,
            lifetimeGrants = grants,
            betaAccess = config.betaActiveAt(now),
            entitlements = config.entitlementsFor(plan, now),
            subscription = stored?.takeIf { subscriptionPlan != null },
            linkedAccounts = store.linkedProviders(identity.id),
        )
    }

    /**
     * The user's lifetime grants, making sure a founder under the
     * `PRO_LIFETIME` benefit has one. Idempotent, so it also heals founders
     * marked before the grant existed or while another benefit was configured.
     */
    private fun founderGrants(identity: UserIdentity): List<LifetimeGrant> {
        val grants = store.lifetimeGrants(identity.id)
        val owed = identity.betaFounder &&
            config.beta.founderBenefit.grantsProLifetime &&
            grants.none { it.source == BETA_FOUNDER_SOURCE }
        if (!owed) return grants
        val grantedAt = identity.betaFounderSince ?: identity.createdAt
        store.grantLifetime(identity.id, "PRO", BETA_FOUNDER_SOURCE, grantedAt)
        return store.lifetimeGrants(identity.id)
    }

    companion object {
        const val BETA_FOUNDER_SOURCE = "beta_founder"

        const val GOOGLE_PROVIDER = "google"
    }
}
