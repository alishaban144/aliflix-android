package com.aliflix.app.ui.discover

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "What should it feel like?",
            color = AliflixContentPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            placeholder = {
                Text(
                    text = if (mediaType == MediaType.MOVIE)
                        "e.g. Dark sci-fi after 2015 with IMDb 7+, no horror"
                    else
                        "e.g. Gritty crime drama series with IMDb 8+, ended",
                    color = AliflixContentTertiary,
                    fontSize = 14.sp
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AliflixAccentPrimary,
                unfocusedBorderColor = AliflixBorderSubtle,
                focusedContainerColor = AliflixSurfaceElevated,
                unfocusedContainerColor = AliflixSurfaceElevated,
                focusedTextColor = AliflixContentPrimary,
                unfocusedTextColor = AliflixContentPrimary
            )
        )

        Spacer(modifier = Modifier.weight(1f))

        AskAliflixStickyCta(
            label = if (loading) "Understanding request…" else "Find matches ✨",
            enabled = text.trim().isNotBlank(),
            loading = loading,
            onClick = onSubmit
        )
    }
}
