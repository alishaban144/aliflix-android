package com.aliflix.app

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.aliflix.app.account.AccountState
import com.aliflix.app.account.AccountSyncState
import com.aliflix.app.ui.MySpaceAccountCard
import com.aliflix.app.ui.theme.AliflixTheme
import org.junit.Rule
import org.junit.Test

class AccountUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signedOutAccountCardIsCompactAndOffersBothSignInPaths() {
        composeRule.setContent {
            AliflixTheme {
                MySpaceAccountCard(
                    accountState = AccountState(),
                    syncState = AccountSyncState.SignedOut,
                    notice = null,
                    onContinueWithGoogle = {},
                    onEmailAccount = {},
                    onManage = {},
                    onSync = {},
                    onSignOut = {},
                )
            }
        }

        composeRule.onNodeWithTag("my-space-account-card").assertIsDisplayed()
        composeRule.onNodeWithTag("account-continue-google").assertHasClickAction()
        composeRule.onNodeWithTag("account-email-entry").assertHasClickAction()
        composeRule.onNodeWithText("Use email instead").assertIsDisplayed()
    }
}
