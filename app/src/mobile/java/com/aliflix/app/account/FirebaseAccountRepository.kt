package com.aliflix.app.account

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.aliflix.app.R
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class FirebaseAccountRepository(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AccountRepository {
    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow(AccountState(user = auth.currentUser?.toAccountUser()))
    override val state: StateFlow<AccountState> = _state.asStateFlow()
    override val currentFirebaseUser: FirebaseUser? get() = auth.currentUser

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        _state.value = _state.value.copy(
            user = firebaseAuth.currentUser?.toAccountUser(),
            isLoading = false,
        )
    }

    init {
        auth.addAuthStateListener(authListener)
    }

    override suspend fun signInWithGoogle(activity: Activity): AccountActionResult =
        runOperation {
            val credential = requestGoogleFirebaseCredential(activity)
            auth.signInWithCredential(credential).await()
        }

    override suspend fun createEmailAccount(
        email: String,
        password: String,
        displayName: String?,
    ): AccountActionResult = runOperation {
        require(email.isNotBlank()) { "Enter your email address." }
        require(password.length >= 6) { "Use a password with at least 6 characters." }
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val cleanName = displayName?.trim()?.takeIf(String::isNotBlank)
        if (cleanName != null) {
            runCatching {
                result.user?.updateProfile(
                    UserProfileChangeRequest.Builder().setDisplayName(cleanName).build(),
                )?.await()
                result.user?.reload()?.await()
            }
        }
    }

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): AccountActionResult = runOperation {
        require(email.isNotBlank() && password.isNotBlank()) {
            "Enter both your email address and password."
        }
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    override suspend fun sendPasswordResetEmail(email: String): AccountActionResult =
        runOperation(passwordResetEmail = email.trim()) {
            require(email.isNotBlank()) { "Enter the email address for your account." }
            auth.sendPasswordResetEmail(email.trim()).await()
        }

    override suspend fun signOut(): AccountActionResult = runOperation {
        auth.signOut()
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    override suspend fun reauthenticateWithPassword(password: String): AccountActionResult =
        runOperation {
            val user = auth.currentUser ?: error("Sign in before confirming your identity.")
            val email = user.email ?: error("This account does not have an email password.")
            require(password.isNotBlank()) { "Enter your password." }
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        }

    override suspend fun reauthenticateWithGoogle(activity: Activity): AccountActionResult =
        runOperation {
            val user = auth.currentUser ?: error("Sign in before confirming your identity.")
            user.reauthenticate(requestGoogleFirebaseCredential(activity)).await()
        }

    override suspend fun deleteCurrentUser(): AccountActionResult = runOperation(
        defaultErrorMessage = "Account deletion could not be completed. Please try again.",
    ) {
        val user = auth.currentUser ?: error("Sign in before deleting an account.")
        user.delete().await()
        runCatching {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        }
    }

    override fun clearMessage() {
        _state.value = _state.value.copy(
            errorMessage = null,
            passwordResetSentTo = null,
        )
    }

    override fun close() {
        auth.removeAuthStateListener(authListener)
    }

    private suspend fun requestGoogleFirebaseCredential(activity: Activity) =
        GoogleAuthProvider.getCredential(requestGoogleIdToken(activity), null)

    private suspend fun requestGoogleIdToken(activity: Activity): String {
        suspend fun request(authorizedAccountsOnly: Boolean): String {
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(authorizedAccountsOnly)
                .setServerClientId(activity.getString(R.string.default_web_client_id))
                .setAutoSelectEnabled(authorizedAccountsOnly)
                .build()
            val result = credentialManager.getCredential(
                context = activity,
                request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build(),
            )
            val credential = result.credential
            if (
                credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                error("Google did not return a usable sign-in credential.")
            }
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }

        // This is an explicit button press, so show every Google account on the device.
        // An authorized-only request can immediately return NoCredentialException for a
        // perfectly valid first-time user and never present the account chooser.
        return request(authorizedAccountsOnly = false)
    }

    private suspend fun runOperation(
        passwordResetEmail: String? = null,
        defaultErrorMessage: String = "Account sign-in could not be completed. Please try again.",
        block: suspend () -> Unit,
    ): AccountActionResult = operationMutex.withLock {
        _state.value = _state.value.copy(
            isLoading = true,
            errorMessage = null,
            passwordResetSentTo = null,
        )
        try {
            block()
            _state.value = _state.value.copy(
                user = auth.currentUser?.toAccountUser(),
                isLoading = false,
                passwordResetSentTo = passwordResetEmail,
            )
            AccountActionResult(
                succeeded = true,
                message = passwordResetEmail?.let { "Password reset email sent to $it." },
            )
        } catch (error: Exception) {
            val message = humanReadableAuthError(error, defaultErrorMessage)
            _state.value = _state.value.copy(isLoading = false, errorMessage = message)
            AccountActionResult(succeeded = false, message = message)
        }
    }

    private fun humanReadableAuthError(error: Exception, defaultMessage: String): String = when (error) {
        is IllegalArgumentException,
        is IllegalStateException -> error.message ?: "The account request is incomplete."
        is GetCredentialCancellationException -> "Google sign-in was canceled."
        is NoCredentialException -> "No Google account is available on this device."
        is GoogleIdTokenParsingException -> "Google sign-in could not be verified. Please try again."
        is GetCredentialException -> "Google sign-in could not be completed. Please try again."
        is FirebaseNetworkException -> "Check your connection and try again."
        is FirebaseAuthWeakPasswordException -> "Use a stronger password with at least 6 characters."
        is FirebaseAuthUserCollisionException -> "An account already exists for this email address."
        is FirebaseAuthInvalidUserException,
        is FirebaseAuthInvalidCredentialsException -> "The email or password is incorrect."
        is FirebaseAuthRecentLoginRequiredException -> "Please confirm your identity again before continuing."
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a moment and try again."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            else -> defaultMessage
        }
        else -> defaultMessage
    }
}

private fun FirebaseUser.toAccountUser() = AccountUser(
    uid = uid,
    displayName = displayName?.trim()?.takeIf(String::isNotBlank),
    email = email?.trim()?.takeIf(String::isNotBlank),
    photoUrl = photoUrl?.toString(),
    providerIds = providerData.mapNotNull { it.providerId.takeIf(String::isNotBlank) }.toSet(),
)
