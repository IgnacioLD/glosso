package com.glosso.app

import com.glosso.app.product.ProductConfigRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The old phone must stop saying "Linked to ..." once the account moved away:
 * `/v1/me` then reports no Google link and the local email is forgotten. A
 * server that does not report links at all changes nothing.
 */
class GoogleAccountStoreTest {

    private fun linkedAccounts(body: String): List<String>? =
        checkNotNull(ProductConfigRepository.decodeAccount(body)).linkedAccounts

    @Test
    fun `the email is forgotten when the account is no longer linked here`() {
        GoogleAccountStore.signedIn("learner@example.com")

        GoogleAccountStore.reconcile(linkedAccounts("""{"plan":"FREE","linkedAccounts":[]}"""))

        assertNull(GoogleAccountStore.email.value)
    }

    @Test
    fun `the email stays while the link is reported or not reported at all`() {
        GoogleAccountStore.signedIn("learner@example.com")

        GoogleAccountStore.reconcile(linkedAccounts("""{"plan":"PRO","linkedAccounts":["google"]}"""))
        assertEquals("learner@example.com", GoogleAccountStore.email.value)

        GoogleAccountStore.reconcile(linkedAccounts("""{"plan":"PRO"}"""))
        assertEquals("learner@example.com", GoogleAccountStore.email.value)
    }
}
