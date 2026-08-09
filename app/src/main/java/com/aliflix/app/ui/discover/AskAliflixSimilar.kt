package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

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
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Similar to",
                color = AliflixContentPrimary,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )

            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = selectedAnchor,
                transitionSpec = {
                    (fadeIn(AskAliflixMotion.smallContentSpec())) togetherWith
                        fadeOut(AskAliflixMotion.smallContentSpec())
                },
                label = "ask-similar-anchor",
                modifier = Modifier.weight(1f),
            ) { anchor ->
                if (anchor == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChanged,
                            placeholder = {
                                Text("Search movies or series", color = AliflixContentTertiary, fontSize = 14.sp)
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = AliflixAccentSecondary)
                            },
                            trailingIcon = {
                                when {
                                    suggestionsLoading -> CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = AliflixAccentSecondary,
                                    )
                                    query.isNotEmpty() -> IconButton(onClick = { onQueryChanged("") }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Clear title search", tint = AliflixContentTertiary)
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(17.dp),
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

                        Spacer(Modifier.height(10.dp))
                        when {
                            query.trim().length < 2 -> SimilarHint()
                            !suggestionsLoading && suggestions.isEmpty() -> SimilarEmptySearch()
                            else -> LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(suggestions, key = { it.key }) { item ->
                                    SimilarSuggestion(item = item, onClick = { onAnchorSelected(item) })
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Surface(
                            color = AliflixSurfaceElevated.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.35f)),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AskAliflixPoster(
                                    media = anchor,
                                    modifier = Modifier.size(width = 78.dp, height = 116.dp),
                                    cornerRadius = 12.dp,
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        anchor.title,
                                        color = AliflixContentPrimary,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        listOfNotNull(
                                            if (anchor.type == MediaType.MOVIE) "Movie" else "Series",
                                            anchor.year.takeIf(String::isNotBlank),
                                        ).joinToString(" · "),
                                        color = AliflixContentSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                IconButton(onClick = { onAnchorSelected(null) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Choose another title", tint = AliflixContentTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }

        AskAliflixStickyCta(
            label = if (loading) {
                "Finding matches…"
            } else if (outputMediaType == MediaType.MOVIE) {
                "Find similar movies"
            } else {
                "Find similar series"
            },
            enabled = selectedAnchor != null,
            loading = loading,
            onClick = onSubmit,
        )
    }
}

@Composable
private fun SimilarSuggestion(item: Media, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(AliflixSurfaceElevated.copy(alpha = 0.74f))
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AskAliflixPoster(item, Modifier.size(width = 48.dp, height = 70.dp), 8.dp)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = AliflixContentPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(
                    if (item.type == MediaType.MOVIE) "Movie" else "Series",
                    item.year.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                color = AliflixContentSecondary,
                fontSize = 11.sp,
            )
        }
        Text("Select", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun SimilarHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier.size(50.dp).clip(CircleShape).background(AliflixSurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = AliflixAccentSecondary)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SimilarEmptySearch() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No matching title found", color = AliflixContentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
