package com.aliflix.app.ui.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

@Composable
fun AskAliflixDescribe(
    text: String,
    onTextChanged: (String) -> Unit,
    mediaType: MediaType,
    onSubmit: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text(
                text = "What are you in the mood for?",
                color = AliflixContentPrimary,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { onTextChanged(it.take(500)) },
                placeholder = {
                    Text(
                        text = if (mediaType == MediaType.MOVIE) {
                            "A visually stunning sci-fi movie after 2015, thoughtful rather than scary…"
                        } else {
                            "A Korean crime series after 2020 about serial killers…"
                        },
                        color = AliflixContentTertiary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                },
                minLines = 7,
                maxLines = 10,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AliflixAccentPrimary,
                    unfocusedBorderColor = AliflixBorderSubtle,
                    focusedContainerColor = AliflixSurfaceElevated,
                    unfocusedContainerColor = AliflixSurfaceElevated.copy(alpha = 0.82f),
                    focusedTextColor = AliflixContentPrimary,
                    unfocusedTextColor = AliflixContentPrimary,
                    cursorColor = AliflixAccentSecondary,
                ),
            )
        }

        AskAliflixStickyCta(
            label = if (loading) "Creating matches…" else "Find matches",
            enabled = text.isNotBlank(),
            loading = loading,
            onClick = onSubmit,
        )
    }
}
