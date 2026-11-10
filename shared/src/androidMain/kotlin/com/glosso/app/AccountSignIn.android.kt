package com.glosso.app

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

actual val accountSignIn: AccountSignIn get() = GoogleSignIn

/**
 * Sign in with Google through Android Credential Manager: the system shows the
 * Google accounts already on the device and the learner picks one. No password
 * and no form, and only the identity (an ID token with `sub`) is requested:
 * no Gmail, no Drive, no other scope.
 */
object GoogleSignIn : AccountSignIn {

    /** The build's OAuth server client id, set by the app at startup. Blank hides the option. */
    @Volatile
    var serverClientId: String = ""

    override val isConfigured: Boolean get() = serverClientId.isNotBlank()

    // Lint does not see the TYPE_GOOGLE_ID_TOKEN_CREDENTIAL check below (a Kotlin
    // companion constant) and reports the documented pattern as a misuse.
    @SuppressLint("CredentialManagerSignInWithGoogle")
    override suspend fun pickAccount(context: Context, nonce: String): AccountPick {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            // Every account on the device, not only ones used with Glosso before.
            .setFilterByAuthorizedAccounts(false)
            // An explicit tap always shows the choice, like a launcher sign-in.
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                AccountPick.Picked(google.idToken, google.id)
            } else {
                AccountPick.Failed
            }
        } catch (_: GetCredentialCancellationException) {
            AccountPick.Cancelled
        } catch (_: NoCredentialException) {
            AccountPick.NoAccount
        } catch (_: GetCredentialException) {
            AccountPick.Failed
        } catch (_: GoogleIdTokenParsingException) {
            AccountPick.Failed
        }
    }
}
