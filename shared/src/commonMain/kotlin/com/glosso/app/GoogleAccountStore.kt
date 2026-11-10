package com.glosso.app

import com.glosso.app.platform.KeyValueStore
import com.glosso.app.platform.PlatformContext
import com.glosso.app.platform.keyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Google account this device signed in with, kept on the device only so
 * Settings can say which one it is. The server never receives or stores the
 * email; it keeps only Google's opaque account id.
 */
object GoogleAccountStore {
    private const val FILE = "glosso_google_account"
    private const val KEY_EMAIL = "email"

    private var prefs: KeyValueStore? = null

    private val _email = MutableStateFlow<String?>(null)
    val email: StateFlow<String?> = _email.asStateFlow()

    fun init(context: PlatformContext) {
        val loaded = keyValueStore(context, FILE)
        prefs = loaded
        _email.value = loaded.getString(KEY_EMAIL)
    }

    fun signedIn(email: String) {
        _email.value = email
        prefs?.edit { putString(KEY_EMAIL, email) }
    }

    /**
     * Forgets the email once `/v1/me` stops reporting a Google link: the
     * account moved to another phone (this one is a new anonymous user now),
     * so saying "Linked to ..." here would be false.
     */
    fun reconcile(linkedAccounts: List<String>?) {
        if (!isUnlinked(linkedAccounts) || _email.value == null) return
        _email.value = null
        prefs?.edit { remove(KEY_EMAIL) }
    }

    /** Null means the server did not say, which is not evidence of an unlink. */
    fun isUnlinked(linkedAccounts: List<String>?): Boolean =
        linkedAccounts != null && GOOGLE_PROVIDER !in linkedAccounts

    private const val GOOGLE_PROVIDER = "google"
}
