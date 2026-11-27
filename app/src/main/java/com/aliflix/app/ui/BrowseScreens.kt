@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui




import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*


import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.runtime.mutableStateOf
import com.aliflix.app.ui.discover.ShimmerBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.GenreUiState
import com.aliflix.app.PersonUiState
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSurfacePressed
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import com.aliflix.app.ui.common.aliflixScreenBackground

@Composable
internal fun PersonCreditsScreen(
    state: PersonUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val creator = state.creator
    val allItems = remember(state.items) { state.items.distinctBy(Media::key) }
    val hasMovies = remember(allItems) { allItems.any { it.type == MediaType.MOVIE } }
    val hasSeries = remember(allItems) { allItems.any { it.type == MediaType.TV } }
    var typeFilter by rememberSaveable(creator?.tmdbId) { mutableStateOf("All") }
    var sortMode by rememberSaveable(creator?.tmdbId) { mutableStateOf("Popular") }
    val items = remember(allItems, typeFilter, sortMode) {
        val typed = when (typeFilter) {
            "Movies" -> allItems.filter { it.type == MediaType.MOVIE }
            "Series" -> allItems.filter { it.type == MediaType.TV }
            else -> allItems
        }
        when (sortMode) {
            "Top Rated" -> typed.sortedByDescending { it.imdbRating ?: it.rating }
            "Newest" -> typed.sortedByDescending { it.year.toIntOrNull() ?: 0 }
            else -> typed
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .aliflixScreenBackground()
            .windowInsetsPadding(AliflixInsets.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = AliflixSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AliflixIconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = AliflixContentPrimary,
                )
            }
            Spacer(Modifier.width(14.dp))
            if (creator?.profileUrl != null) {
                AsyncImage(
                    model = creator.profileUrl,
                    contentDescription = creator.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = creator?.name ?: "Creator",
                    color = AliflixContentPrimary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.loading && state.error == null) {
                    Text(
                        text = "${items.size} works",
                        color = AliflixContentTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        when {
            state.loading -> GenreLoadingGrid(
                mediaLabel = "works",
                modifier = Modifier.weight(1f),
            )
            state.error != null -> GenreExploreStatePanel(
                title = "Credits unavailable",
                message = state.error,
                actionLabel = "Try again",
                onAction = onRetry,
                modifier = Modifier.weight(1f),
            )
            allItems.isEmpty() -> GenreExploreStatePanel(
                title = "No works found",
                message = "No TMDB credits are available.",
                modifier = Modifier.weight(1f),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = AliflixSpacing.Content,
                    end = AliflixSpacing.Content,
                    top = 10.dp,
                    bottom = 40.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Panel),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Works",
                                color = AliflixContentPrimary,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            AliflixChip(
                                selected = sortMode != "Popular",
                                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
                                modifier = Modifier.clickable {
                                    sortMode = when (sortMode) {
                                        "Popular" -> "Top Rated"
                                        "Top Rated" -> "Newest"
                                        else -> "Popular"
                                    }
                                },
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Rounded.Sort, null, tint = AliflixAccentSecondary, modifier = Modifier.size(14.dp))
                                    Text(sortMode, color = AliflixContentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (hasMovies && hasSeries) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(listOf("All", "Movies", "Series")) { option ->
                                    val active = typeFilter == option
                                    AliflixChip(
                                        selected = active,
                                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp),
                                        modifier = Modifier.clickable { typeFilter = option },
                                    ) {
                                        Text(
                                            text = option,
                                            color = if (active) AliflixAccentSecondary else AliflixContentSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                items(items, key = { item -> "person:${item.key}" }) { item ->
                    MediaPoster(
                        item = item,
                        width = 118.dp,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }

}

@Composable
internal fun GenreExploreScreen(
    genreName: String,
    mediaType: MediaType,
    state: GenreUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    var sortMode by rememberSaveable(genreName, mediaType) { mutableStateOf("Default") }
    val items = remember(state.items, mediaType, sortMode) {
        val base = state.items.filter { it.type == mediaType }.distinctBy(Media::key)
        when (sortMode) {
            "Top Rated" -> base.sortedByDescending { it.imdbRating ?: it.rating }
            "Newest" -> base.sortedByDescending { it.year.toIntOrNull() ?: 0 }
            else -> base
        }
    }
    val mediaLabel = when (mediaType) {
        MediaType.MOVIE -> "Movies"
        MediaType.TV -> "TV Series"
    }
    val itemNoun = if (mediaType == MediaType.MOVIE) "movies" else "series"
    var visibleCount by rememberSaveable(genreName, mediaType) {
        mutableIntStateOf(GENRE_EXPLORE_PAGE_SIZE)
    }
    val visibleItems = remember(items, visibleCount) {
        items.take(visibleCount)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .aliflixScreenBackground()
            .windowInsetsPadding(AliflixInsets.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = AliflixSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AliflixIconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AliflixAccentPrimary.copy(alpha = 0.16f))
                            .padding(horizontal = AliflixSpacing.Small, vertical = AliflixSpacing.Tiny),
                    ) {
                        Text(
                            text = mediaLabel.uppercase(),
                            color = AliflixAccentSecondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.9.sp,
                        )
                    }
                    if (!state.loading && state.error == null && items.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${items.size} titles",
                            color = AliflixContentTertiary,
                            fontSize = 11.sp,
                        )
                    }
                }
                Text(
                    text = genreName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = AliflixContentPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            state.loading -> GenreLoadingGrid(
                mediaLabel = mediaLabel,
                modifier = Modifier.weight(1f),
            )
            state.error != null -> GenreExploreStatePanel(
                title = "$mediaLabel unavailable",
                message = state.error,
                actionLabel = "Try again",
                onAction = onRetry,
                modifier = Modifier.weight(1f),
            )
            items.isEmpty() -> GenreExploreStatePanel(
                title = "No $itemNoun found",
                message = "There are no $itemNoun available for $genreName right now.",
                modifier = Modifier.weight(1f),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(118.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = AliflixSpacing.Content,
                    end = AliflixSpacing.Content,
                    top = 10.dp,
                    bottom = 40.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Panel),
                modifier = Modifier.weight(1f),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$mediaLabel in $genreName",
                                color = AliflixContentPrimary,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Showing ${visibleItems.size} of ${items.size}",
                                color = AliflixContentTertiary,
                                fontSize = 11.sp,
                            )
                        }
                        AliflixChip(
                            selected = sortMode != "Default",
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
                            modifier = Modifier.clickable {
                                sortMode = when (sortMode) {
                                    "Default" -> "Top Rated"
                                    "Top Rated" -> "Newest"
                                    else -> "Default"
                                }
                            },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.Sort, null, tint = AliflixAccentSecondary, modifier = Modifier.size(14.dp))
                                Text(sortMode, color = AliflixContentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                items(visibleItems, key = { "genre:${it.key}" }) { item ->
                    MediaPoster(
                        item = item,
                        width = 118.dp,
                        onClick = { onOpen(item) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (visibleItems.size < items.size) {
                        OutlinedButton(
                            onClick = {
                                visibleCount = minOf(
                                    items.size,
                                    visibleCount + GENRE_EXPLORE_PAGE_SIZE,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            shape = AliflixCorners.Card,
                            border = null,
                        ) {
                            Text(
                                text = "Show ${minOf(
                                    GENRE_EXPLORE_PAGE_SIZE,
                                    items.size - visibleItems.size,
                                )} more",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreLoadingGrid(
    mediaLabel: String,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        contentPadding = PaddingValues(
            start = AliflixSpacing.Content,
            end = AliflixSpacing.Content,
            top = 10.dp,
            bottom = 40.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Panel),
        modifier = modifier,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    color = AliflixAccentPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Loading $mediaLabel",
                    color = AliflixContentSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        items(count = 9) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(AliflixCorners.Card),
                )
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(10.dp)
                        .clip(CircleShape),
                )
                ShimmerBox(
                    modifier = Modifier
                        .fillMaxWidth(0.48f)
                        .height(8.dp)
                        .clip(CircleShape),
                )
            }
        }
    }
}

@Composable
private fun GenreExploreStatePanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (onAction == null) {
                        AliflixContentTertiary
                    } else {
                        AliflixError
                    },
                ),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            color = AliflixContentPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AliflixContentSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AliflixAccentPrimary,
                ),
                shape = AliflixCorners.Card,
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private const val GENRE_EXPLORE_PAGE_SIZE = 20
