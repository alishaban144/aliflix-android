package com.aliflix.app.account

import android.app.Activity
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.StateFlow

interface AccountRepository : AutoCloseable {
    val state: StateFlow<AccountState>
    val currentFirebaseUser: FirebaseUser?
    val uid: String? get() = state.value.uid
    val displayName: String? get() = state.value.displayName
    val email: String? get() = state.value.email
    val photoUrl: String? get() = state.value.photoUrl

    suspend fun signInWithGoogle(activity: Activity): AccountActionResult
    suspend fun createEmailAccount(
        email: String,
        password: String,
        displayName: String? = null,
    ): AccountActionResult
    suspend fun signInWithEmail(email: String, password: String): AccountActionResult
    suspend fun sendPasswordResetEmail(email: String): AccountActionResult
    suspend fun signOut(): AccountActionResult
    suspend fun reauthenticateWithPassword(password: String): AccountActionResult
    suspend fun reauthenticateWithGoogle(activity: Activity): AccountActionResult
    fun clearMessage()
}

interface AccountSyncRepository : AutoCloseable {
    val state: StateFlow<AccountSyncState>
    fun retry()
}

data class AccountServices(
    val accountRepository: AccountRepository,
    val syncRepository: AccountSyncRepository,
) : AutoCloseable {
    override fun close() {
        syncRepository.close()
        accountRepository.close()
    }
}
