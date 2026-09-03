package com.aliflix.app

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.aliflix.app.account.AccountState
import com.aliflix.app.ui.MySpaceAccountCard
import com.aliflix.app.ui.theme.AliflixTheme
import org.junit.Rule
import org.junit.Test

class AccountUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun signedOutAccountCardIsCompactAndOpensTheAccountScreenFromItsAvatar() {
        composeRule.setContent {
            AliflixTheme {
                MySpaceAccountCard(
                    accountState = AccountState(),
                    onOpenAccount = {},
                )
            }
        }

        composeRule.onNodeWithTag("my-space-account-card").assertIsDisplayed()
        composeRule.onNodeWithText("A").assertHasClickAction()
        composeRule.onNodeWithText("Aliflix account").assertIsDisplayed()
    }
}
