package com.glosso.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountSignInTest {

    @Test
    fun `each ending pick has its sign_in result`() {
        assertNull(AccountPick.Picked("token", "a@b.c").signInResult)
        assertEquals("cancelled", AccountPick.Cancelled.signInResult)
        assertEquals("no_account", AccountPick.NoAccount.signInResult)
        assertEquals("failed", AccountPick.Failed.signInResult)
    }
}
