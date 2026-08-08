package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary

@Composable
fun AskAliflixSimilar(
    query: String,
    onQueryChanged: (String) -> Unit,
    selectedAnchor: Media?,
    onAnchorSelected: (Media?) -> Unit,
    suggestions: List<Media>,
    suggestionsLoading: Boolean,
    outputMediaType: MediaType,
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
            text = "Start with a title",
            color = AliflixContentPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (selectedAnchor == null) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                placeholder = {
                    Text(
                        text = "Search title (e.g. Breaking Bad)",
                        color = AliflixContentTertiary,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = AliflixContentTertiary
                    )
                },
                trailingIcon = {
                    if (suggestionsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AliflixAccentPrimary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
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

            if (query.trim().isNotBlank() && suggestions.isEmpty() && !suggestionsLoading) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select a title from search results below",
                    color = AliflixContentTertiary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(suggestions, key = { it.imdbId ?: "tmdb:${it.id}" }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onAnchorSelected(item)
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = item.posterPath,
                            contentDescription = item.title,
                            modifier = Modifier
                                .size(36.dp, 54.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(AliflixSurfaceSecondary)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                color = AliflixContentPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${if (item.type == MediaType.MOVIE) "Movie" else "Series"}${item.year?.let { " · $it" } ?: ""}",
                                color = AliflixContentSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        } else {
            // Selected Anchor Compact Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AliflixSurfaceElevated,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = selectedAnchor.posterPath,
                        contentDescription = selectedAnchor.title,
                        modifier = Modifier
                            .size(48.dp, 72.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AliflixSurfaceSecondary)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = selectedAnchor.title,
                            color = AliflixContentPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${if (selectedAnchor.type == MediaType.MOVIE) "Movie" else "Series"}${selectedAnchor.year?.let { " · $it" } ?: ""}",
                            color = AliflixAccentPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(onClick = { onAnchorSelected(null) }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear anchor",
                            tint = AliflixContentTertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        AskAliflixStickyCta(
            label = if (loading) "Finding similar…" else "Find similar ✨",
            enabled = selectedAnchor != null,
            loading = loading,
            onClick = onSubmit
        )
    }
}
