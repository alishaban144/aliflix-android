package com.aliflix.app.ui

import com.aliflix.app.account.AccountSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountUiModelTest {
    @Test
    fun signInRequiresAValidEmailAndPassword() {
        val invalid = validateAccountForm(
            route = AccountRoute.SIGN_IN,
            email = "not-an-email",
            password = "",
        )
        assertFalse(invalid.isValid)
        assertEquals("Enter a valid email address.", invalid.email)
        assertEquals("Enter your password.", invalid.password)

        assertTrue(
            validateAccountForm(
                route = AccountRoute.SIGN_IN,
                email = "person@example.com",
                password = "secret",
            ).isValid,
        )
    }

    @Test
    fun accountCreationRequiresSixCharactersAndMatchingConfirmation() {
        val errors = validateAccountForm(
            route = AccountRoute.CREATE_ACCOUNT,
            email = "person@example.com",
            password = "short",
            confirmation = "different",
        )
        assertEquals("Use at least 6 characters.", errors.password)
        assertEquals("Passwords do not match.", errors.confirmation)

        val valid = validateAccountForm(
            route = AccountRoute.CREATE_ACCOUNT,
            email = "person@example.com",
            password = "secret1",
            confirmation = "secret1",
        )
        assertTrue(valid.isValid)
    }

    @Test
    fun passwordResetValidatesOnlyTheEmail() {
        val result = validateAccountForm(
            route = AccountRoute.FORGOT_PASSWORD,
            email = "person@example.com",
        )
        assertTrue(result.isValid)
        assertNull(result.password)
        assertNull(result.confirmation)
    }

    @Test
    fun offlineSyncFailureIsPresentedAsDeferredRatherThanDataLoss() {
        val presentation = accountSyncPresentation(
            AccountSyncState.Error(
                "Cloud sync is offline. Your changes remain safely on this device.",
            ),
        )
        assertEquals("Offline — will sync when possible", presentation.label)
        assertTrue(presentation.isError)
    }

    @Test
    fun providerLabelsDoNotExposeFirebaseIdentifiers() {
        assertEquals("Google", accountProviderLabel(setOf("google.com")))
        assertEquals("Email and password", accountProviderLabel(setOf("password")))
        assertEquals(
            "Google and email",
            accountProviderLabel(setOf("google.com", "password")),
        )
    }
}
