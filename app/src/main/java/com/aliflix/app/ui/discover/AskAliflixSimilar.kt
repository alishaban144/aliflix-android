package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    selectedAnchors: List<Media>,
    onAddAnchor: (Media) -> Unit,
    onRemoveAnchor: (Media) -> Unit,
    suggestions: List<Media>,
    suggestionsLoading: Boolean,
    outputMediaType: MediaType,
    onSubmit: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = if (selectedAnchors.size > 1) "Movie Fusion" else "Similar to",
                color = AliflixContentPrimary,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )

            Spacer(Modifier.height(10.dp))

            if (selectedAnchors.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(selectedAnchors, key = { it.key }) { anchor ->
                        SelectedAnchorChip(
                            anchor = anchor,
                            onRemove = { onRemoveAnchor(anchor) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (selectedAnchors.size < 4) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    placeholder = {
                        Text(
                            if (selectedAnchors.isEmpty()) "Search movies or series" else "Add another title to blend (up to 4)",
                            color = AliflixContentTertiary,
                            fontSize = 14.sp,
                        )
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
                                Icon(Icons.Rounded.Close, contentDescription = "Clear search", tint = AliflixContentTertiary)
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
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
                    query.trim().length < 2 && selectedAnchors.isEmpty() -> SimilarHint()
                    query.trim().length < 2 && selectedAnchors.isNotEmpty() -> {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Add up to ${4 - selectedAnchors.size} more titles to find their blended intersection.",
                                color = AliflixContentSecondary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                    !suggestionsLoading && suggestions.isEmpty() -> SimilarEmptySearch()
                    else -> LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(suggestions, key = { it.key }) { item ->
                            val alreadySelected = selectedAnchors.any { it.key == item.key }
                            SimilarSuggestion(
                                item = item,
                                isSelected = alreadySelected,
                                onClick = {
                                    if (!alreadySelected) {
                                        onAddAnchor(item)
                                        keyboard?.hide()
                                    }
                                },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Maximum of 4 titles selected for blending.",
                        color = AliflixContentSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        AskAliflixStickyCta(
            label = when {
                loading -> "Finding matches…"
                selectedAnchors.isEmpty() -> "Select a title to start"
                selectedAnchors.size == 1 -> if (outputMediaType == MediaType.MOVIE) "Find similar movies" else "Find similar series"
                else -> if (outputMediaType == MediaType.MOVIE) "Blend ${selectedAnchors.size} titles for movies" else "Blend ${selectedAnchors.size} titles for series"
            },
            enabled = selectedAnchors.isNotEmpty(),
            loading = loading,
            onClick = onSubmit,
        )
    }
}

@Composable
private fun SelectedAnchorChip(
    anchor: Media,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = AliflixSurfaceElevated.copy(alpha = 0.9f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AskAliflixPoster(
                media = anchor,
                modifier = Modifier.size(width = 28.dp, height = 40.dp),
                cornerRadius = 6.dp,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.width(130.dp)) {
                Text(
                    text = anchor.title,
                    color = AliflixContentPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        if (anchor.type == MediaType.MOVIE) "Movie" else "Series",
                        anchor.year.takeIf(String::isNotBlank),
                    ).joinToString(" · "),
                    color = AliflixContentSecondary,
                    fontSize = 10.sp,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove title",
                    tint = AliflixContentTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SimilarSuggestion(
    item: Media,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(if (isSelected) AliflixSurfaceElevated.copy(alpha = 0.4f) else AliflixSurfaceElevated.copy(alpha = 0.74f))
            .border(1.dp, if (isSelected) AliflixAccentPrimary.copy(alpha = 0.3f) else AliflixBorderSubtle, RoundedCornerShape(15.dp))
            .clickable(enabled = !isSelected, onClick = onClick)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AskAliflixPoster(item, Modifier.size(width = 48.dp, height = 70.dp), 8.dp)
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (isSelected) AliflixContentSecondary else AliflixContentPrimary,
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
        Text(
            if (isSelected) "Added" else "Add +",
            color = if (isSelected) AliflixContentTertiary else AliflixAccentSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
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
            Text(
                text = "Search a movie or series to start",
                color = AliflixContentSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SimilarEmptySearch() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No matching title found", color = AliflixContentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
