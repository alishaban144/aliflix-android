package com.aliflix.app.ui

import com.aliflix.app.account.AccountSyncState

internal enum class AccountRoute {
    SIGN_IN,
    CREATE_ACCOUNT,
    FORGOT_PASSWORD,
    MANAGE,
}

internal data class AccountFormErrors(
    val email: String? = null,
    val password: String? = null,
    val confirmation: String? = null,
) {
    val isValid: Boolean
        get() = email == null && password == null && confirmation == null
}

internal fun validateAccountForm(
    route: AccountRoute,
    email: String,
    password: String = "",
    confirmation: String = "",
): AccountFormErrors {
    val cleanEmail = email.trim()
    val emailError = when {
        cleanEmail.isBlank() -> "Enter your email address."
        !EMAIL_PATTERN.matches(cleanEmail) -> "Enter a valid email address."
        else -> null
    }
    if (route == AccountRoute.FORGOT_PASSWORD) {
        return AccountFormErrors(email = emailError)
    }
    val passwordError = when {
        password.isBlank() -> "Enter your password."
        route == AccountRoute.CREATE_ACCOUNT && password.length < 6 ->
            "Use at least 6 characters."
        else -> null
    }
    val confirmationError = when {
        route != AccountRoute.CREATE_ACCOUNT -> null
        confirmation.isBlank() -> "Confirm your password."
        confirmation != password -> "Passwords do not match."
        else -> null
    }
    return AccountFormErrors(
        email = emailError,
        password = passwordError,
        confirmation = confirmationError,
    )
}

internal data class AccountSyncPresentation(
    val label: String,
    val detail: String? = null,
    val isError: Boolean = false,
)

internal fun accountSyncPresentation(state: AccountSyncState): AccountSyncPresentation =
    when (state) {
        AccountSyncState.SignedOut -> AccountSyncPresentation("Not syncing")
        AccountSyncState.Syncing -> AccountSyncPresentation(
            label = "Syncing",
            detail = "Keeping your My Space and settings up to date.",
        )
        AccountSyncState.Synced -> AccountSyncPresentation(
            label = "Synced",
            detail = "Your cloud backup is up to date.",
        )
        is AccountSyncState.Error -> AccountSyncPresentation(
            label = if (state.message.contains("offline", ignoreCase = true)) {
                "Offline — will sync when possible"
            } else {
                "Sync needs attention"
            },
            detail = state.message,
            isError = true,
        )
    }

internal fun accountProviderLabel(providerIds: Set<String>): String = when {
    "google.com" in providerIds && "password" in providerIds -> "Google and email"
    "google.com" in providerIds -> "Google"
    "password" in providerIds -> "Email and password"
    else -> "Firebase Authentication"
}

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
