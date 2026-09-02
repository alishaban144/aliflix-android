package com.aliflix.app.account

import android.app.Activity
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionCoordinatorTest {
    @Test
    fun successfulDeletionStopsSyncDeletesAuthThenClearsLocalUserScope() = runTest {
        val events = mutableListOf<String>()
        val services = services(events = events)

        val result = services.deleteCurrentAccount()

        assertTrue(result.succeeded)
        assertEquals(listOf("cloud", "auth", "finish"), events)
    }

    @Test
    fun cloudFailureDoesNotDeleteTheFirebaseUser() = runTest {
        val events = mutableListOf<String>()
        val services = services(
            events = events,
            cloudResult = AccountActionResult(false, "Cloud deletion failed."),
        )

        val result = services.deleteCurrentAccount()

        assertFalse(result.succeeded)
        assertEquals(listOf("cloud"), events)
    }

    @Test
    fun authFailureResumesSyncSoPartialCloudDeletionCanBeReconciled() = runTest {
        val events = mutableListOf<String>()
        val services = services(
            events = events,
            authResult = AccountActionResult(false, "Confirm your identity again."),
        )

        val result = services.deleteCurrentAccount()

        assertFalse(result.succeeded)
        assertEquals(listOf("cloud", "auth", "resume"), events)
    }

    private fun services(
        events: MutableList<String>,
        cloudResult: AccountActionResult = AccountActionResult(true),
        authResult: AccountActionResult = AccountActionResult(true),
    ) = AccountServices(
        accountRepository = FakeAccountRepository(events, authResult),
        syncRepository = FakeSyncRepository(events, cloudResult),
    )
}

private class FakeAccountRepository(
    private val events: MutableList<String>,
    private val deletionResult: AccountActionResult,
) : AccountRepository {
    private val mutableState = MutableStateFlow(
        AccountState(user = AccountUser("user-a", "A", "a@example.com", null, setOf("password"))),
    )
    override val state: StateFlow<AccountState> = mutableState
    override val currentFirebaseUser: FirebaseUser? = null
    override suspend fun signInWithGoogle(activity: Activity) = unused()
    override suspend fun createEmailAccount(email: String, password: String, displayName: String?) = unused()
    override suspend fun signInWithEmail(email: String, password: String) = unused()
    override suspend fun sendPasswordResetEmail(email: String) = unused()
    override suspend fun signOut() = unused()
    override suspend fun reauthenticateWithPassword(password: String) = unused()
    override suspend fun reauthenticateWithGoogle(activity: Activity) = unused()
    override suspend fun deleteCurrentUser(): AccountActionResult {
        events += "auth"
        return deletionResult
    }
    override fun clearMessage() = Unit
    override fun close() = Unit

    private fun unused() = AccountActionResult(false, "Unused in this test.")
}

private class FakeSyncRepository(
    private val events: MutableList<String>,
    private val cloudResult: AccountActionResult,
) : AccountSyncRepository {
    private val mutableState = MutableStateFlow<AccountSyncState>(AccountSyncState.Synced)
    override val state: StateFlow<AccountSyncState> = mutableState
    override fun retry() = Unit
    override suspend fun deleteCloudAccountData(uid: String): AccountActionResult {
        events += "cloud"
        return cloudResult
    }
    override suspend fun finishAccountDeletion(uid: String) {
        events += "finish"
    }
    override suspend fun resumeAfterFailedAccountDeletion(uid: String) {
        events += "resume"
    }
    override fun close() = Unit
}
