package com.aliflix.app.account

import android.app.Activity
import android.app.Application
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.data.PlaybackProviderRepository
import com.aliflix.app.recommendation.RecommendationStore
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun createAccountServices(
    application: Application,
    libraryStore: LibraryStore,
    playbackRepository: PlaybackProviderRepository,
    recommendationStore: RecommendationStore,
    scope: CoroutineScope,
): AccountServices = AccountServices(
    accountRepository = TvAccountRepository,
    syncRepository = TvAccountSyncRepository,
)

private object TvAccountRepository : AccountRepository {
    private val _state = MutableStateFlow(AccountState())
    override val state: StateFlow<AccountState> = _state.asStateFlow()
    override val currentFirebaseUser: FirebaseUser? = null

    override suspend fun signInWithGoogle(activity: Activity) = unavailable()
    override suspend fun createEmailAccount(email: String, password: String, displayName: String?) = unavailable()
    override suspend fun signInWithEmail(email: String, password: String) = unavailable()
    override suspend fun sendPasswordResetEmail(email: String) = unavailable()
    override suspend fun signOut() = AccountActionResult(succeeded = true)
    override suspend fun reauthenticateWithPassword(password: String) = unavailable()
    override suspend fun reauthenticateWithGoogle(activity: Activity) = unavailable()
    override fun clearMessage() = Unit
    override fun close() = Unit

    private fun unavailable() = AccountActionResult(
        succeeded = false,
        message = "Accounts are not available on Android TV yet.",
    )
}

private object TvAccountSyncRepository : AccountSyncRepository {
    private val _state = MutableStateFlow<AccountSyncState>(AccountSyncState.SignedOut)
    override val state: StateFlow<AccountSyncState> = _state.asStateFlow()
    override fun retry() = Unit
    override fun close() = Unit
}
