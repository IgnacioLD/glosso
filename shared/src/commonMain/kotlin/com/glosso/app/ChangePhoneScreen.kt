package com.glosso.app

import com.glosso.app.platform.Connectivity
import com.glosso.app.platform.currentPlatformContext
import com.glosso.app.platform.rememberConnectivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.glosso.app.api.ApiStatusException
import com.glosso.app.designsystem.GlossoColors
import com.glosso.app.designsystem.Space
import com.glosso.app.product.ProductConfigRepository
import com.glosso.app.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * "Change phone": carry this device's account to a new phone.
 *
 * The only way is Sign in with Google: the learner picks one of the Google
 * accounts already on the phone (no password, no form) and does the same on the
 * new phone. The server keeps only Google's opaque account id.
 */
@Composable
fun ChangePhoneScreen(onBack: () -> Unit) {
    val context = currentPlatformContext()
    val connectivity = rememberConnectivity()
    val scope = rememberCoroutineScope()
    val googleEmail by GoogleAccountStore.email.collectAsState()

    var signingIn by remember { mutableStateOf(false) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleSuccess by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SubHeader(stringResource(Res.string.recovery_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .navigationBarsPadding(),
        ) {
            Spacer(Modifier.height(Space.x12))
            Text(
                text = stringResource(Res.string.recovery_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (accountSignIn.isConfigured) {
                Spacer(Modifier.height(Space.x32))
                SectionLabel(stringResource(Res.string.google_section))
                Spacer(Modifier.height(Space.x8))
                val linkedEmail = googleEmail
                Text(
                    text = if (linkedEmail != null) {
                        stringResource(Res.string.google_linked, linkedEmail)
                    } else {
                        stringResource(Res.string.google_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Space.x16))
                PrimaryButton(
                    text = stringResource(Res.string.google_continue),
                    loading = signingIn,
                    onClick = {
                        scope.launch {
                            signingIn = true
                            googleError = null
                            googleSuccess = false
                            val nonce = runCatching { appApi.googleSignInNonce() }.getOrElse {
                                googleError = networkOrServerError(connectivity)
                                signingIn = false
                                return@launch
                            }
                            when (val pick = accountSignIn.pickAccount(context, nonce)) {
                                is AccountPick.Picked ->
                                    runCatching { appApi.signInWithGoogle(pick.idToken) }
                                        .onSuccess {
                                            trackSignIn(SignInResult.SUCCESS)
                                            GoogleAccountStore.signedIn(pick.email)
                                            googleSuccess = true
                                            ProductConfigRepository.refresh()
                                        }
                                        .onFailure {
                                            trackSignIn(SignInResult.SERVER_ERROR)
                                            googleError = googleSignInError(connectivity, it)
                                        }
                                AccountPick.Cancelled -> trackSignIn(SignInResult.CANCELLED)
                                AccountPick.NoAccount -> {
                                    trackSignIn(SignInResult.NO_ACCOUNT)
                                    googleError = getString(Res.string.google_error_no_account)
                                }
                                AccountPick.Failed -> {
                                    trackSignIn(SignInResult.FAILED)
                                    googleError = getString(Res.string.google_error_failed)
                                }
                            }
                            signingIn = false
                        }
                    },
                )
                Spacer(Modifier.height(Space.x8))
                Text(
                    text = stringResource(Res.string.google_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (googleSuccess) {
                    Spacer(Modifier.height(Space.x12))
                    StatusMessage(stringResource(Res.string.google_success), GlossoColors.accentText)
                }
                googleError?.let { message ->
                    Spacer(Modifier.height(Space.x12))
                    StatusMessage(message, MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(Space.x40))
        }
    }
}

/**
 * A result or an error, announced by TalkBack as soon as it appears and
 * scrolled into view: it shows up below the button, often past the fold.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusMessage(text: String, color: androidx.compose.ui.graphics.Color) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(text) { bringIntoView.bringIntoView() }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .bringIntoViewRequester(bringIntoView)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** 409: this phone already belongs to another Google account; 401: a refused sign-in. */
private suspend fun googleSignInError(connectivity: Connectivity, failure: Throwable): String =
    when ((failure as? ApiStatusException)?.status) {
        409 -> getString(Res.string.google_error_conflict)
        401 -> getString(Res.string.google_error_failed)
        else -> networkOrServerError(connectivity)
    }

/** The outcome of one Google sign-in, never the account. */
private fun trackSignIn(result: String) = Analytics.track("sign_in", mapOf("result" to result))
