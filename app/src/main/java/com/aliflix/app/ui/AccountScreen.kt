package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.account.AccountActionResult
import com.aliflix.app.account.AccountState
import com.aliflix.app.account.AccountSyncState
import com.aliflix.app.ui.common.MobileTopSafeArea
import com.aliflix.app.ui.common.aliflixScreenBackground
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderStrong
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSuccess
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import kotlinx.coroutines.launch

@Composable
internal fun MySpaceAccountCard(
    accountState: AccountState,
    syncState: AccountSyncState,
    notice: String?,
    onContinueWithGoogle: () -> Unit,
    onEmailAccount: () -> Unit,
    onManage: () -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sync = accountSyncPresentation(syncState)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("my-space-account-card")
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(22.dp)),
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(
                    name = accountState.displayName ?: accountState.email,
                    size = 36,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = accountState.displayName
                            ?: accountState.email
                            ?: "Aliflix account",
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                    )
                    Text(
                        text = if (accountState.isSignedIn) sync.label else
                            "Optional cloud backup",
                        color = when {
                            sync.isError -> AliflixError
                            accountState.isSignedIn && syncState == AccountSyncState.Synced ->
                                AliflixSuccess
                            else -> AliflixContentSecondary
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (!accountState.isSignedIn) {
                PrimaryAccountButton(
                    text = "Continue with Google",
                    loading = accountState.isLoading,
                    onClick = onContinueWithGoogle,
                    modifier = Modifier
                        .testTag("account-continue-google")
                        .heightIn(min = 42.dp),
                )
                TextButton(
                    onClick = onEmailAccount,
                    enabled = !accountState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("account-email-entry")
                        .heightIn(min = 36.dp),
                ) {
                    Icon(Icons.Rounded.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use email instead", fontWeight = FontWeight.Bold)
                }
            } else {
                accountState.email?.takeIf { it != accountState.displayName }?.let { email ->
                    Text(email, color = AliflixContentSecondary, fontSize = 12.sp)
                }
                sync.detail?.let { detail ->
                    Text(detail, color = AliflixContentTertiary, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onSync,
                        enabled = !accountState.isLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onManage,
                        enabled = !accountState.isLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Manage account", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    onClick = onSignOut,
                    enabled = !accountState.isLoading,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Sign out", color = AliflixContentSecondary, fontWeight = FontWeight.Bold)
                }
            }
            notice?.let { message ->
                InlineAccountMessage(message = message, isError = false)
            }
            accountState.errorMessage?.let { error ->
                InlineAccountMessage(message = error, isError = true)
            }
        }
    }
}

@Composable
internal fun AccountScreen(
    route: AccountRoute,
    accountState: AccountState,
    syncState: AccountSyncState,
    onBack: () -> Unit,
    onNavigate: (AccountRoute) -> Unit,
    onAuthenticated: () -> Unit,
    onAccountEnded: (String) -> Unit,
    onGoogle: suspend () -> AccountActionResult,
    onCreate: suspend (String, String, String?) -> AccountActionResult,
    onSignIn: suspend (String, String) -> AccountActionResult,
    onResetPassword: suspend (String) -> AccountActionResult,
    onSignOut: suspend () -> AccountActionResult,
    onReauthenticateGoogle: suspend () -> AccountActionResult,
    onReauthenticatePassword: suspend (String) -> AccountActionResult,
    onDeleteAccount: suspend () -> AccountActionResult,
    onSync: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var working by rememberSaveable(route) { mutableStateOf(false) }
    var localMessage by rememberSaveable(route) { mutableStateOf<String?>(null) }
    var localError by rememberSaveable(route) { mutableStateOf<String?>(null) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showPasswordReauthentication by remember { mutableStateOf(false) }
    val busy = working || accountState.isLoading

    LaunchedEffect(route) {
        onClearMessage()
    }
    LaunchedEffect(accountState.isSignedIn, route) {
        if (accountState.isSignedIn && route != AccountRoute.MANAGE && route != AccountRoute.FORGOT_PASSWORD) {
            onAuthenticated()
        }
    }

    fun runAction(action: suspend () -> AccountActionResult, onSuccess: (AccountActionResult) -> Unit = {}) {
        if (busy) return
        scope.launch {
            working = true
            localError = null
            localMessage = null
            val result = action()
            working = false
            if (result.succeeded) onSuccess(result) else localError = result.message
        }
    }

    fun deleteAfter(action: suspend () -> AccountActionResult) {
        runAction(action) { reauthResult ->
            if (!reauthResult.succeeded) return@runAction
            scope.launch {
                working = true
                val deletion = onDeleteAccount()
                working = false
                if (deletion.succeeded) {
                    onAccountEnded(deletion.message ?: "Your account was deleted.")
                } else {
                    localError = deletion.message
                }
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDeleteConfirmation = false },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = AliflixError) },
            title = { Text("Delete your Aliflix account?") },
            text = {
                Text(
                    "This permanently deletes your Firebase account and its cloud-synced " +
                        "My List, favorites, recent history, profile, and settings. " +
                        "Aliflix will return to optional, signed-out local mode.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showDeleteConfirmation = false
                        if ("google.com" in accountState.user?.providerIds.orEmpty()) {
                            deleteAfter(onReauthenticateGoogle)
                        } else {
                            showPasswordReauthentication = true
                        }
                    },
                ) {
                    Text("Continue", color = AliflixError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
            containerColor = AliflixSurfaceElevated,
            titleContentColor = AliflixContentPrimary,
            textContentColor = AliflixContentSecondary,
            shape = RoundedCornerShape(24.dp),
        )
    }

    if (showPasswordReauthentication) {
        PasswordReauthenticationDialog(
            busy = busy,
            onDismiss = { showPasswordReauthentication = false },
            onConfirm = { password ->
                showPasswordReauthentication = false
                deleteAfter { onReauthenticatePassword(password) }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("account-screen-${route.name.lowercase()}")
            .aliflixScreenBackground(),
    ) {
        MobileTopSafeArea()
        AccountTopBar(
            title = when (route) {
                AccountRoute.SIGN_IN -> "Sign in"
                AccountRoute.CREATE_ACCOUNT -> "Create account"
                AccountRoute.FORGOT_PASSWORD -> "Reset password"
                AccountRoute.MANAGE -> "Manage account"
            },
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (route) {
                AccountRoute.SIGN_IN -> SignInForm(
                    busy = busy,
                    onGoogle = { runAction(onGoogle) },
                    onSignIn = { email, password ->
                        runAction(action = { onSignIn(email, password) })
                    },
                    onCreate = { onNavigate(AccountRoute.CREATE_ACCOUNT) },
                    onForgot = { onNavigate(AccountRoute.FORGOT_PASSWORD) },
                    onLocalError = { localError = it },
                )
                AccountRoute.CREATE_ACCOUNT -> CreateAccountForm(
                    busy = busy,
                    onGoogle = { runAction(onGoogle) },
                    onCreate = { email, password, name ->
                        runAction(action = { onCreate(email, password, name) })
                    },
                    onSignIn = { onNavigate(AccountRoute.SIGN_IN) },
                    onLocalError = { localError = it },
                )
                AccountRoute.FORGOT_PASSWORD -> ForgotPasswordForm(
                    busy = busy,
                    onSubmit = { email ->
                        runAction(
                            action = { onResetPassword(email) },
                            onSuccess = {
                                localMessage = "If an account can receive email, a reset link is on its way."
                            },
                        )
                    },
                    onLocalError = { localError = it },
                )
                AccountRoute.MANAGE -> ManageAccountContent(
                    accountState = accountState,
                    syncState = syncState,
                    busy = busy,
                    onSync = onSync,
                    onSignOut = {
                        runAction(
                            action = onSignOut,
                            onSuccess = {
                                onAccountEnded("Signed out. Aliflix remains fully available on this device.")
                            },
                        )
                    },
                    onDelete = { showDeleteConfirmation = true },
                )
            }

            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AliflixAccentSecondary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text("Working…", color = AliflixContentSecondary, fontSize = 12.sp)
                }
            }
            localMessage?.let { InlineAccountMessage(it, isError = false) }
            (localError ?: accountState.errorMessage)?.let {
                InlineAccountMessage(it, isError = true)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ColumnScope.SignInForm(
    busy: Boolean,
    onGoogle: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onCreate: () -> Unit,
    onForgot: () -> Unit,
    onLocalError: (String?) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf(AccountFormErrors()) }
    val focusManager = LocalFocusManager.current
    fun submit() {
        errors = validateAccountForm(AccountRoute.SIGN_IN, email, password)
        onLocalError(listOfNotNull(errors.email, errors.password).firstOrNull())
        if (errors.isValid) {
            focusManager.clearFocus()
            onSignIn(email.trim(), password)
        }
    }
    AccountIntro(
        title = "Your Aliflix, on every device",
        body = "Signing in adds cloud backup and sync. Watching, search, and your local library work without an account.",
    )
    PrimaryAccountButton("Continue with Google", busy, onGoogle)
    AccountDivider()
    AccountEmailField(email, { email = it; errors = errors.copy(email = null) }, errors.email, busy)
    AccountPasswordField(
        value = password,
        onValueChange = { password = it; errors = errors.copy(password = null) },
        label = "Password",
        error = errors.password,
        enabled = !busy,
        imeAction = ImeAction.Done,
        onDone = ::submit,
    )
    Button(
        onClick = ::submit,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
    ) { Text("Sign in", fontWeight = FontWeight.Bold) }
    TextButton(onClick = onForgot, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Forgot password?", color = AliflixAccentSecondary)
    }
    TextButton(onClick = onCreate, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("New to Aliflix? Create account", color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ColumnScope.CreateAccountForm(
    busy: Boolean,
    onGoogle: () -> Unit,
    onCreate: (String, String, String?) -> Unit,
    onSignIn: () -> Unit,
    onLocalError: (String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf(AccountFormErrors()) }
    val focusManager = LocalFocusManager.current
    fun submit() {
        errors = validateAccountForm(AccountRoute.CREATE_ACCOUNT, email, password, confirmation)
        onLocalError(listOfNotNull(errors.email, errors.password, errors.confirmation).firstOrNull())
        if (errors.isValid) {
            focusManager.clearFocus()
            onCreate(email.trim(), password, name.trim().takeIf(String::isNotBlank))
        }
    }
    AccountIntro(
        title = "Back up what you love",
        body = "Create an optional account to sync My List, favorites, recent history, playback settings, and Ask Aliflix preferences.",
    )
    PrimaryAccountButton("Continue with Google", busy, onGoogle)
    AccountDivider()
    AccountTextField(
        value = name,
        onValueChange = { name = it },
        label = "Display name (optional)",
        enabled = !busy,
        leadingIcon = { Icon(Icons.Rounded.AccountCircle, contentDescription = null) },
    )
    AccountEmailField(email, { email = it; errors = errors.copy(email = null) }, errors.email, busy)
    AccountPasswordField(
        password,
        { password = it; errors = errors.copy(password = null) },
        "Password",
        errors.password,
        !busy,
    )
    AccountPasswordField(
        confirmation,
        { confirmation = it; errors = errors.copy(confirmation = null) },
        "Confirm password",
        errors.confirmation,
        !busy,
        imeAction = ImeAction.Done,
        onDone = ::submit,
    )
    Button(
        onClick = ::submit,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
    ) { Text("Create account", fontWeight = FontWeight.Bold) }
    TextButton(onClick = onSignIn, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Already have an account? Sign in", color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ColumnScope.ForgotPasswordForm(
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onLocalError: (String?) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    fun submit() {
        error = validateAccountForm(AccountRoute.FORGOT_PASSWORD, email).email
        onLocalError(error)
        if (error == null) {
            focusManager.clearFocus()
            onSubmit(email.trim())
        }
    }
    AccountIntro(
        title = "Reset your password",
        body = "Enter your account email. Firebase will send a secure reset link if the address is eligible.",
    )
    AccountEmailField(email, { email = it; error = null }, error, busy, onDone = ::submit)
    Button(
        onClick = ::submit,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
    ) { Text("Send reset link", fontWeight = FontWeight.Bold) }
}

@Composable
private fun ManageAccountContent(
    accountState: AccountState,
    syncState: AccountSyncState,
    busy: Boolean,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    val user = accountState.user
    val sync = accountSyncPresentation(syncState)
    AccountPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(user?.displayName ?: user?.email, 58)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user?.displayName ?: "Aliflix account",
                    color = AliflixContentPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                )
                user?.email?.let { Text(it, color = AliflixContentSecondary, fontSize = 12.sp) }
            }
        }
        AccountInfoRow("Sign-in method", accountProviderLabel(user?.providerIds.orEmpty()))
        AccountInfoRow("Cloud sync", sync.label, valueColor = if (sync.isError) AliflixError else AliflixSuccess)
        sync.detail?.let { Text(it, color = AliflixContentTertiary, fontSize = 11.sp, lineHeight = 16.sp) }
        OutlinedButton(
            onClick = onSync,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
        ) {
            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sync now", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onSignOut, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out", color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        }
    }
    AccountPanel(borderColor = AliflixError.copy(alpha = 0.42f)) {
        Text("Delete account", color = AliflixError, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Permanently remove this account and its Aliflix cloud data. Guest and unrelated local app data are not deleted.",
            color = AliflixContentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        OutlinedButton(
            onClick = onDelete,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixError.copy(alpha = 0.75f)),
        ) {
            Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = AliflixError, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Delete account and cloud data", color = AliflixError, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PasswordReauthenticationDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Confirm your identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter your account password before permanent deletion.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !busy,
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotBlank()) onConfirm(password) }),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (visible) "Hide password" else "Show password",
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && password.isNotBlank(), onClick = { onConfirm(password) }) {
                Text("Delete permanently", color = AliflixError, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
        containerColor = AliflixSurfaceElevated,
        titleContentColor = AliflixContentPrimary,
        textContentColor = AliflixContentSecondary,
        shape = RoundedCornerShape(24.dp),
    )
}

@Composable
private fun AccountTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AliflixContentPrimary)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun AccountIntro(title: String, body: String) {
    Text(title, color = AliflixContentPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black)
    Text(body, color = AliflixContentSecondary, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable
private fun PrimaryAccountButton(
    text: String,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = !loading,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
        } else {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.width(9.dp))
            Text(text, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun AccountDivider() {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(AliflixBorderSubtle))
        Text("or", color = AliflixContentTertiary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(AliflixBorderSubtle))
    }
}

@Composable
private fun AccountEmailField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    busy: Boolean,
    onDone: (() -> Unit)? = null,
) = AccountTextField(
    value = value,
    onValueChange = onValueChange,
    label = "Email",
    enabled = !busy,
    error = error,
    keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Email,
        imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
    ),
    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
    leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
)

@Composable
private fun AccountPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    enabled: Boolean,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AccountTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        enabled = enabled,
        error = error,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password",
                )
            }
        },
    )
}

@Composable
private fun AccountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    error: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { message -> { Text(message) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(15.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AliflixAccentSecondary,
            unfocusedBorderColor = AliflixBorderStrong,
            focusedTextColor = AliflixContentPrimary,
            unfocusedTextColor = AliflixContentPrimary,
            focusedLabelColor = AliflixAccentSecondary,
            cursorColor = AliflixAccentSecondary,
        ),
    )
}

@Composable
private fun AccountPanel(
    borderColor: Color = AliflixBorderSubtle,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(22.dp)),
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content,
        )
    }
}

@Composable
private fun AccountInfoRow(label: String, value: String, valueColor: Color = AliflixContentPrimary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AliflixContentTertiary, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

@Composable
private fun AccountAvatar(name: String?, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        AliflixAccentPrimary.copy(alpha = 0.8f),
                        AliflixAccentSecondary.copy(alpha = 0.62f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name
                ?.trim()
                ?.firstOrNull { character -> character.isLetterOrDigit() }
                ?.uppercase()
                ?: "A",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = (size * 0.4f).sp,
        )
    }
}

@Composable
private fun InlineAccountMessage(message: String, isError: Boolean) {
    Text(
        text = message,
        color = if (isError) AliflixError else AliflixSuccess,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                (if (isError) AliflixError else AliflixSuccess).copy(alpha = 0.1f),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
