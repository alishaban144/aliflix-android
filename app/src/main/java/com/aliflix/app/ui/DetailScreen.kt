@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui



import com.aliflix.app.model.PlaybackProviderId

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.DetailUiState
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaReview
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.recommendation.PersonalMatch
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixBorderStrong
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixEditorialWarm
import com.aliflix.app.ui.theme.AliflixSuccess as AliflixGreen
import com.aliflix.app.ui.theme.AliflixContentSecondary as AliflixMuted
import com.aliflix.app.ui.theme.AliflixSurfacePressed
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

@Composable
private fun AnimatedMyListButton(
    inMyList: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scaleState by remember { mutableFloatStateOf(1f) }
    val animatedScale by animateFloatAsState(
        targetValue = scaleState,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        finishedListener = { scaleState = 1f },
    )
    val containerColor by animateColorAsState(
        targetValue = AliflixSurfaceDefaults.color(if (inMyList) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content),
        animationSpec = AliflixMotion.selection(),
    )

    Button(
        onClick = {
            scaleState = 1.10f
            onClick()
        },
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            },
        shape = AliflixCorners.Card,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
        ),
        border = null,
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        AnimatedContent(
            targetState = inMyList,
            transitionSpec = {
                (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut())
            },
            label = "my-list-anim",
        ) { isSaved ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isSaved) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isSaved) "Saved" else "My List",
                    maxLines = 1,
                    softWrap = false,
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AnimatedFavoriteButton(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scaleState by remember { mutableFloatStateOf(1f) }
    val animatedScale by animateFloatAsState(
        targetValue = scaleState,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        finishedListener = { scaleState = 1f },
    )
    val containerColor by animateColorAsState(
        targetValue = AliflixSurfaceDefaults.color(if (liked) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content),
        animationSpec = AliflixMotion.selection(),
    )

    Button(
        onClick = {
            scaleState = 1.12f
            onClick()
        },
        modifier = modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            },
        shape = AliflixCorners.Card,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
        ),
        border = null,
        contentPadding = PaddingValues(0.dp),
    ) {
        AnimatedContent(
            targetState = liked,
            transitionSpec = {
                (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut())
            },
            label = "fav-anim",
        ) { isLiked ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isLiked) AliflixEditorialWarm else AliflixContentPrimary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (isLiked) "Liked" else "Like",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val DETAIL_OVERVIEW_COLLAPSED_LINES = 5

internal fun shouldShowOverviewExpansion(
    isExpanded: Boolean,
    lineCount: Int,
    hasVisualOverflow: Boolean,
): Boolean =
    if (isExpanded) {
        lineCount > DETAIL_OVERVIEW_COLLAPSED_LINES
    } else {
        hasVisualOverflow
    }

/** The series play button names the exact season and episode it opens, so the label never
 * disagrees with the episode the button actually starts. Movies carry no episode to name. */
internal fun detailCtaText(
    item: Media,
    mainEpisode: Episode?,
    isPartiallyWatched: Boolean,
): String {
    val action = if (isPartiallyWatched) "Resume" else "Play"
    if (item.type != MediaType.TV) return action
    val episode = mainEpisode ?: return action
    return "$action S${episode.seasonNumber}E${episode.number}"
}

/**
 * The hero item's height on the details screen.
 *
 * The hero is the first item in the list and the list no longer carries the status bar padding, so
 * the inset is absorbed here instead. That lets the poster plane live inside this item and still
 * start at the top of the screen, behind the status bar, which is where the stationary plane drew
 * it: the frame reaches the same line as before, and every item below it keeps its position.
 */
internal fun detailHeroFrame(
    heroTopInset: androidx.compose.ui.unit.Dp,
    heroHeight: androidx.compose.ui.unit.Dp,
): androidx.compose.ui.unit.Dp = heroTopInset + heroHeight

@Composable
internal fun DetailScreen(
    state: DetailUiState,
    inMyList: Boolean,
    liked: Boolean,
    playbackProgress: Map<String, PlaybackProgress>,
    personalMatch: PersonalMatch?,
    onBack: () -> Unit,
    onPlay: (Media) -> Unit,
    onPlayEpisode: (Media, Episode) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleMyList: (Media) -> Unit,
    onToggleLike: (Media) -> Unit,
    onOpen: (Media) -> Unit,
    selectedProvider: PlaybackProvider = PlaybackProviderId.MOVIEPIRE,
    onSelectProvider: (PlaybackProvider) -> Unit = {},
    onOpenGenre: (String, MediaType) -> Unit = { _, _ -> },
    onOpenCreator: (MediaCreator) -> Unit = {},
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    onScrollPositionChanged: (Int, Int) -> Unit = { _, _ -> },
    inHistory: Boolean = false,
    onDeleteHistory: (Media) -> Unit = {},
    preloadEnabled: Boolean = false,
    onOpenKeyword: ((com.aliflix.app.model.MediaKeyword) -> Unit)? = null,
) {
    val item = state.item ?: return
    val latestEpisodeProgress = playbackProgress.values
        .filter { progress -> progress.media.id == item.id && progress.media.type == MediaType.TV }
        .maxByOrNull(PlaybackProgress::updatedAtMillis)
    val mainEpisode = latestEpisodeProgress?.let { progress ->
        state.episodes.firstOrNull {
            it.seasonNumber == progress.seasonNumber && it.number == progress.episodeNumber
        } ?: Episode(
            seasonNumber = progress.seasonNumber ?: state.selectedSeason,
            number = progress.episodeNumber ?: 1,
            title = progress.episodeTitle.orEmpty().ifBlank { "Episode ${progress.episodeNumber ?: 1}" },
        )
    } ?: state.episodes.firstOrNull()
    com.aliflix.app.player.DetailPlaybackPreload(
        item, mainEpisode ?: if(item.type == MediaType.TV) Episode(state.selectedSeason,1,"Episode 1") else null,
        state.episodes, enabled = preloadEnabled,
    )
    val configuration = LocalConfiguration.current
    val detailHeroHeight = if (configuration.screenWidthDp > configuration.screenHeightDp) 360.dp else (configuration.screenHeightDp * 0.53f).coerceIn(300f, 460f).dp
    // The hero item absorbs this inset, so the poster inside it still starts behind the status bar.
    val detailHeroTopInset = AliflixInsets.Top.asPaddingValues()
        .calculateTopPadding()
    val detailListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    )
    LaunchedEffect(detailListState) {
        snapshotFlow { detailListState.firstVisibleItemIndex to detailListState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                onScrollPositionChanged(index, offset)
            }
    }
    var overviewExpanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val overview = item.overview.ifBlank { "No overview is available yet." }
    var overviewCanExpand by remember(item.key) { mutableStateOf(false) }
    // The poster is drawn by the hero item below, so it scrolls away with the hero and the sections
    // underneath it sit on this plain Aliflix background. Only the base colour stays out here.
    Box(Modifier.fillMaxSize().background(AliflixBackgroundBase)) {
    LazyColumn(
        state = detailListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item(key = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(detailHeroFrame(detailHeroTopInset, detailHeroHeight)),
            ) {
                // The plane fills the frame from the top of the screen, exactly where the
                // stationary plane used to start it, and scrolls away with this item.
                CinematicPosterPlane(
                    artwork = item.backdropUrl ?: item.posterUrl,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(top = detailHeroTopInset),
                ) {
                HistoryReminder(
                    itemKey = item.key, inHistory = inHistory, onDelete = { onDeleteHistory(item) },
                    modifier = Modifier.padding(start = 76.dp, top = 16.dp, end = 16.dp),
                )
                AliflixIconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(AliflixSpacing.Content)
                        .size(48.dp)
                        .clip(CircleShape),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to previous screen",
                        tint = AliflixContentPrimary,
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
                ) {
                    Text(
                        text = if (item.type == MediaType.MOVIE) "MOVIE" else "SERIES",
                        color = if (item.type == MediaType.MOVIE) AliflixAccentSecondary else Color(0xFF9DCECE),
                        modifier = Modifier.clip(AliflixCorners.Small).background(if (item.type == MediaType.MOVIE) AliflixAccentSecondary.copy(alpha = .12f) else Color(0x189DCECE)).padding(horizontal = AliflixSpacing.Small, vertical = AliflixSpacing.Tiny),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        text = item.title,
                        color = AliflixContentPrimary,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Black,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        personalMatch?.let { match ->
                            DetailMetadataPill(
                                label = match.label,
                                contentColor = AliflixGreen,
                            )
                        }
                        if (item.status.isNotBlank()) {
                            DetailMetadataPill(
                                label = item.status,
                                contentColor = AliflixAccentSecondary,
                            )
                        }
                        listOf(item.year, item.runtime, item.genres.firstOrNull().orEmpty())
                            .filter(String::isNotBlank)
                            .forEach { label -> DetailMetadataPill(label = label) }
                    }
                }
                }
            }
        }
        item(key = "metadata:${item.key}:${item.runtime}:${item.imdbRating}:${item.rottenTomatoesRating}") {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DetailCinematicActionPanel(
                    item = item,
                    mainEpisode = mainEpisode,
                    inMyList = inMyList,
                    liked = liked,
                    latestProgress = if (item.type == MediaType.TV) latestEpisodeProgress else playbackProgress[playbackProgressKey(PlaybackSelection(item))],
                    onPlay = onPlay,
                    onPlayEpisode = onPlayEpisode,
                    onToggleMyList = onToggleMyList,
                    onToggleLike = onToggleLike,
                )
                DetailInfoSection(title = "About") {
                    Column(
                        modifier = Modifier.animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
                    ) {
                        Text(
                            text = overview,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 24.sp,
                            color = AliflixContentSecondary,
                            maxLines = if (overviewExpanded) {
                                Int.MAX_VALUE
                            } else {
                                DETAIL_OVERVIEW_COLLAPSED_LINES
                            },
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { layoutResult ->
                                overviewCanExpand = shouldShowOverviewExpansion(
                                    isExpanded = overviewExpanded,
                                    lineCount = layoutResult.lineCount,
                                    hasVisualOverflow = layoutResult.hasVisualOverflow,
                                )
                            },
                        )
                        if (overviewCanExpand) {
                            TextButton(
                                onClick = { overviewExpanded = !overviewExpanded },
                                modifier = Modifier.heightIn(min = 48.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(
                                    text = if (overviewExpanded) "Show less" else "Read more",
                                    color = AliflixAccentSecondary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (overviewExpanded) {
                                        Icons.Rounded.ExpandLess
                                    } else {
                                        Icons.Rounded.ExpandMore
                                    },
                                    contentDescription = null,
                                    tint = AliflixAccentSecondary,
                                )
                            }
                        }
                    }
                }
                if (item.genres.isNotEmpty()) {
                    DetailInfoSection(title = "Genres") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
                        ) {
                            item.genres.distinct().forEach { genre ->
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .aliflixSurface()
                                        .clickable { onOpenGenre(genre, item.type) }
                                        .padding(start = 14.dp, end = AliflixSpacing.Small),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = genre,
                                        color = AliflixContentPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = AliflixAccentSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (item.cast.isNotEmpty() || item.castPeople.isNotEmpty() || item.creators.isNotEmpty()) {
                    DetailInfoSection(title = "Cast") {
                        DetailCastRow(item = item, onOpenCreator = onOpenCreator)
                    }
                }
                InlineTrailerSection(item)
                if (item.originalLanguage.isNotBlank() || item.omdbAwards != null) {
                    DetailInfoSection(title = "Details") {
                        Column(verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Content)) {
                            if (item.originalLanguage.isNotBlank()) {
                                DetailFact(label = "Original language") {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = displayLanguageName(item.originalLanguage),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = AliflixContentPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = item.originalLanguage.uppercase(Locale.ROOT),
                                            color = AliflixContentTertiary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            item.omdbAwards?.let { DetailAwardsCard(it) }
                        }
                    }
                }
                if (item.reviews.isNotEmpty()) {
                    DetailInfoSection(
                        title = "Reviews",
                        badge = {
                            Box(
                                modifier = Modifier
                                    .aliflixSurface()
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "${item.reviews.size}",
                                    color = AliflixAccentSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                    ) {
                        DetailReviewsCarousel(reviews = item.reviews)
                    }
                }
                if (item.keywords.isNotEmpty() && onOpenKeyword != null) DetailInfoSection(title = "Keywords") {
                    CompactKeywords(item.keywords.distinctBy { it.id }, onOpenKeyword)
                }
                if (state.error != null) {
                    AliflixSurface(
                        shape = AliflixCorners.Card,
                        level = AliflixSurfaceLevel.Critical,
                    ) {
                        Text(
                            text = "Some extended details could not be refreshed.",
                            color = AliflixContentSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
        }

        if (item.type == MediaType.TV && !state.loading) {
            item(key = "episodes-header") {
                Column(
                    modifier = Modifier.padding(top = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = AliflixSpacing.Content),
                        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
                    ) {
                        items(state.seasons.distinctBy { it.number }, key = { it.number }) { season ->
                            val selected = season.number == state.selectedSeason
                            TextButton(
                                onClick = { onSelectSeason(season.number) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                shape = AliflixCorners.Small,
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (selected) AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Selected) else Color.Transparent,
                                    contentColor = if (selected) Color.White else AliflixContentSecondary,
                                ),
                                contentPadding = PaddingValues(horizontal = AliflixSpacing.Content),
                            ) {
                                Text("S${season.number}", fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            if (state.episodesLoading) {
                item(key = "episodes-loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AliflixAccentPrimary)
                    }
                }
            } else if (state.episodes.isEmpty()) {
                item(key = "episodes-empty") {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                        Text("Episode information is unavailable for this season.", color = AliflixMuted)
                        TextButton(onClick = { onSelectSeason(state.selectedSeason) }) { Text("Reload episodes") }
                    }
                }
            } else {
                items(
                    items = state.episodes.distinctBy { it.seasonNumber to it.number },
                    key = { episode -> "${episode.seasonNumber}:${episode.number}" },
                ) { episode ->
                    EpisodeRow(
                        modifier = if(rememberMobileMotionEnabled()) Modifier.animateItem(fadeInSpec=tween(220),fadeOutSpec=tween(120)) else Modifier,
                        media = item,
                        episode = episode,
                        current = mainEpisode?.let { it.seasonNumber == episode.seasonNumber && it.number == episode.number } == true,
                        progress = playbackProgress[playbackProgressKey(
                            PlaybackSelection(
                                media = item,
                                seasonNumber = episode.seasonNumber,
                                episodeNumber = episode.number,
                            ),
                        )],
                        onPlay = { onPlayEpisode(item, episode) },
                    )
                }
            }
        }

        if (state.recommendations.isNotEmpty()) {
            item(key = "recommendations") {
                MediaRail(
                    rail = ContentRail("More Like This", state.recommendations),
                    onOpen = onOpen,
                    compact = true,
                )
            }
        } else if (state.loading) {
            item(key = "detail-loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AliflixAccentPrimary)
                }
            }
        }
    }
    }
}

@Composable
private fun DetailMetadataPill(
    label: String,
    contentColor: Color = AliflixContentSecondary,
) {
    AliflixPill {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = contentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailFact(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = AliflixContentTertiary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        content()
    }
}

@Composable
private fun DetailCastRow(item: Media, onOpenCreator: (MediaCreator) -> Unit) {
    val people = remember(item.castPeople, item.cast, item.creators) {
        val actors = item.castPeople + item.cast.filter { name ->
            item.castPeople.none { it.name.equals(name, ignoreCase = true) }
        }.map { name -> item.creators.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MediaCreator(0, name) }
        // Creators lead the row so director/writer credits stay ahead of guest cameos.
        (item.creators + actors).filter { it.name.isNotBlank() }
            .distinctBy { if (it.tmdbId > 0) "id:${it.tmdbId}" else "name:${it.name.lowercase(Locale.ROOT)}" }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium)) {
        items(people, key = { if (it.tmdbId > 0) "id:${it.tmdbId}" else "name:${it.name.lowercase(Locale.ROOT)}" }) { person ->
            val creator = item.creators.firstOrNull {
                (person.tmdbId > 0 && it.tmdbId == person.tmdbId) || it.name.equals(person.name, ignoreCase = true)
            }
            val roleLabel = (creator?.role ?: person.role)?.takeIf { it.isNotBlank() }
            Column(
                modifier = Modifier.width(72.dp).then(
                    if (creator != null) Modifier.clickable { onOpenCreator(creator) } else Modifier,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(52.dp).aliflixSurface(shape = CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, null, tint = AliflixContentTertiary, modifier = Modifier.size(24.dp))
                    (person.profileUrl ?: creator?.profileUrl)?.let { portrait ->
                        AsyncImage(portrait, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Text(person.name, color = AliflixContentPrimary, fontSize = 11.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
                if (creator != null) Text((roleLabel ?: "CREATOR").uppercase(Locale.ROOT), color = AliflixContentSecondary, fontSize = 8.sp,
                    lineHeight = 10.sp, letterSpacing = .5.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                else if (roleLabel != null) Text(roleLabel, color = AliflixContentSecondary, fontSize = 9.sp,
                    lineHeight = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DetailReviewsCarousel(
    reviews: List<MediaReview>,
    modifier: Modifier = Modifier,
) {
    if (reviews.isEmpty()) return

    if (reviews.size == 1) {
        DetailReviewCard(
            review = reviews.first(),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { reviews.size })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
    ) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            DetailReviewCard(
                review = reviews[page],
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            reviews.indices.forEach { index ->
                val isSelected = pagerState.currentPage == index
                val dotWidth by animateDpAsState(
                    targetValue = if (isSelected) 18.dp else 6.dp,
                    animationSpec = tween(AliflixMotion.Content),
                    label = "review-dot-width",
                )
                val dotColor by animateColorAsState(
                    targetValue = if (isSelected) AliflixAccentSecondary else AliflixBorderStrong,
                    animationSpec = tween(AliflixMotion.Content),
                    label = "review-dot-color",
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(dotWidth)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }
    }
}

@Composable
private fun DetailReviewCard(
    review: MediaReview,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(review.id) { mutableStateOf(false) }
    val cleanContent = remember(review.content) {
        review.content
            .replace(Regex("(?m)^#+\\s*"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("(?i)\\.{2,}\\s*read the rest\\.?"), "")
            .replace(Regex("(?i)read the rest\\.?"), "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    AliflixSurface(
        modifier = modifier.animateContentSize(AliflixMotion.content()),
        shape = AliflixCorners.Card,
        alpha = AliflixAlpha.Reviews,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    AliflixAccentSecondary.copy(alpha = 0.25f),
                                    AliflixSurfacePressed,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (review.avatarUrl != null) {
                        AsyncImage(
                            model = review.avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = review.displayName.take(1).uppercase(Locale.ROOT),
                            color = AliflixAccentSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = review.displayName,
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    review.displayDate?.let { date ->
                        Text(
                            text = date,
                            color = AliflixContentTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }

                if (review.rating != null && review.rating > 0.0) {
                    val goldColor = Color(0xFFF5C518)
                    Box(
                        modifier = Modifier
                            .clip(AliflixCorners.Small)
                            .background(goldColor.copy(alpha = 0.15f))
                            .padding(horizontal = 7.dp, vertical = AliflixSpacing.Tiny),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "★",
                                color = goldColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text = if (review.rating % 1.0 == 0.0) {
                                    "${review.rating.toInt()}/10"
                                } else {
                                    String.format(Locale.US, "%.1f", review.rating)
                                },
                                color = goldColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                }
            }

            Text(
                text = cleanContent,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 24.sp,
                color = AliflixContentSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )

            if (cleanContent.length > 160) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(
                        text = if (expanded) "Show less" else "Read full review",
                        color = AliflixAccentSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = AliflixAccentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailInfoSection(
    title: String,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            badge?.invoke()
        }
        // Keep the native trailer surface un-clipped; no nested card or decorative border.
        Box(Modifier.fillMaxWidth()) { content() }
    }
}

private fun displayLanguageName(code: String): String {
    val normalized = code.trim()
    if (normalized.isBlank()) return ""
    return runCatching {
        Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.getDefault())
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: normalized.uppercase(Locale.ROOT)
}

@Composable
private fun DetailCinematicActionPanel(
    item: Media,
    mainEpisode: Episode?,
    inMyList: Boolean,
    liked: Boolean,
    latestProgress: PlaybackProgress?,
    onPlay: (Media) -> Unit,
    onPlayEpisode: (Media, Episode) -> Unit,
    onToggleMyList: (Media) -> Unit,
    onToggleLike: (Media) -> Unit,
) {
    val progressRatio = latestProgress?.progressFraction?.toFloat() ?: 0f
    val isPartiallyWatched = latestProgress?.resumeEligible == true

    val ctaText = detailCtaText(item, mainEpisode, isPartiallyWatched)

    AliflixSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = AliflixCorners.Panel,
        level = AliflixSurfaceLevel.Content,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Top row: CTA (weight 1) + Liked + My List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (item.type == MediaType.TV && mainEpisode != null) {
                            onPlayEpisode(item, mainEpisode)
                        } else {
                            onPlay(item)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AliflixAccentPrimary,
                        contentColor = AliflixContentPrimary,
                    ),
                    shape = AliflixCorners.Card,
                    contentPadding = PaddingValues(horizontal = AliflixSpacing.Small, vertical = 6.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = ctaText,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isPartiallyWatched) {
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { progressRatio },
                                modifier = Modifier
                                    .fillMaxWidth(0.72f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.35f),
                            )
                        }
                    }
                }

                com.aliflix.app.downloads.DownloadButton(item)

                // Compact Liked button
                AliflixSurface(
                    onClick = { onToggleLike(item) },
                    modifier = Modifier.size(48.dp),
                    shape = AliflixCorners.Card,
                    level = if (liked) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (liked) "Liked" else "Like",
                            tint = if (liked) AliflixAccentSecondary else AliflixContentSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Compact My List button
                AliflixSurface(
                    onClick = { onToggleMyList(item) },
                    modifier = Modifier.size(48.dp),
                    shape = AliflixCorners.Card,
                    level = if (inMyList) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (inMyList) Icons.Filled.Check else Icons.Filled.Add,
                            contentDescription = if (inMyList) "In My List" else "Add to My List",
                            tint = if (inMyList) AliflixAccentSecondary else AliflixContentSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Subtle divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(AliflixBorderSubtle.copy(alpha = 0.6f)),
            )

            // Bottom row: Ratings strip
            DetailRatingsStrip(
                item = item,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DetailRatingsStrip(
    item: Media,
    modifier: Modifier = Modifier,
) {
    val presentation = externalRatingsPresentation(item)
    Row(
        modifier = modifier
            .aliflixSurface(alpha = AliflixAlpha.Ratings)
            .padding(horizontal = AliflixSpacing.Small, vertical = AliflixSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // IMDb
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFF5C518))
                    .padding(horizontal = AliflixSpacing.Tiny, vertical = 1.5.dp),
            ) {
                Text(
                    text = "IMDb",
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            if (presentation.loading || presentation.imdb == "Loading...") {
                AbstractRatingLoader(accentColor = Color(0xFFF5C518))
            } else {
                Text(
                    text = presentation.imdb,
                    color = AliflixContentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .background(AliflixBorderSubtle),
        )

        // Rotten Tomatoes
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🍅",
                fontSize = 10.sp,
            )
            Spacer(modifier = Modifier.width(5.dp))
            if (presentation.loading || presentation.rottenTomatoes == "Loading...") {
                AbstractRatingLoader(accentColor = Color(0xFFFA3A45))
            } else {
                Text(
                    text = presentation.rottenTomatoes,
                    color = AliflixContentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .background(AliflixBorderSubtle),
        )

        // TMDB
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF01B4E4))
                    .padding(horizontal = AliflixSpacing.Tiny, vertical = 1.5.dp),
            ) {
                Text(
                    text = "TMDB",
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            if (presentation.loading || presentation.tmdb == "Loading...") {
                AbstractRatingLoader(accentColor = Color(0xFF01B4E4))
            } else {
                Text(
                    text = presentation.tmdb,
                    color = AliflixContentPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AbstractRatingLoader(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "rating-loader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(0, 1, 2).forEach { index ->
            val factor = ((kotlin.math.sin(phase + index * 1.1) + 1.0) / 2.0).toFloat()
            val barHeight = 3.5.dp + (6.5.dp * factor)
            val alpha = 0.35f + 0.65f * factor
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(accentColor.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun RatingsRow(item: Media) {
    val presentation = externalRatingsPresentation(item)

    AnimatedContent(
        targetState = presentation,
        transitionSpec = { fadeIn(tween(AliflixMotion.Content)) togetherWith fadeOut(tween(AliflixMotion.Selection)) },
        label = "external-ratings-together",
    ) { ratings ->
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                9.dp,
                Alignment.CenterHorizontally,
            ),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            RatingPill(
                source = "IMDb",
                value = ratings.imdb,
                accent = Color(0xFFF5C518),
                darkText = true,
                loading = ratings.loading,
            )
            RatingPill(
                source = "Tomatometer",
                value = ratings.rottenTomatoes,
                accent = Color(0xFFFA3A45),
                darkText = false,
                loading = ratings.loading,
            )
            RatingPill(
                source = "TMDB",
                value = ratings.tmdb,
                accent = Color(0xFF01B4E4),
                darkText = true,
                loading = ratings.loading,
            )
        }
    }
}

internal data class ExternalRatingsPresentation(
    val imdb: String,
    val rottenTomatoes: String,
    val tmdb: String,
    val loading: Boolean,
)

internal fun externalRatingsPresentation(item: Media): ExternalRatingsPresentation {
    if (!externalRatingsReady(item)) {
        return ExternalRatingsPresentation(
            imdb = "Loading...",
            rottenTomatoes = "Loading...",
            tmdb = "Loading...",
            loading = true,
        )
    }

    return ExternalRatingsPresentation(
        imdb = when {
            item.imdbRating != null -> String.format(java.util.Locale.US, "%.1f", item.imdbRating)
            item.imdbRatingState == RatingSourceState.NOT_RATED -> "Not rated"
            else -> "Unavailable"
        },
        rottenTomatoes = when (item.rottenTomatoesState) {
            RatingSourceState.VERIFIED,
            RatingSourceState.STALE -> item.rottenTomatoesRating?.let { "$it%" } ?: "Unavailable"
            RatingSourceState.NOT_RATED -> "Not rated"
            else -> "Unavailable"
        },
        tmdb = if (item.rating > 0.0) {
            String.format(java.util.Locale.US, "%.1f", item.rating)
        } else {
            "Not rated"
        },
        loading = false,
    )
}

internal fun externalRatingsReady(item: Media): Boolean {
    val terminalStates = setOf(
        RatingSourceState.VERIFIED,
        RatingSourceState.STALE,
        RatingSourceState.NOT_RATED,
        RatingSourceState.UNAVAILABLE,
    )
    val imdbReady = item.imdbRating != null || item.imdbRatingState in terminalStates
    val rottenTomatoesReady = item.rottenTomatoesRating != null || item.rottenTomatoesState in terminalStates
    return imdbReady && rottenTomatoesReady
}

@Composable
private fun RatingPill(
    source: String,
    value: String,
    accent: Color,
    darkText: Boolean,
    loading: Boolean = false,
) {
    Row(
        modifier = Modifier
            .aliflixSurface(shape = AliflixCorners.Small)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(accent)
                .padding(horizontal = AliflixSpacing.Small, vertical = 7.dp),
        ) {
            Text(
                text = source,
                color = if (darkText) Color.Black else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
            )
        }
        if (loading) {
            MovingMovieLoader(accent = accent)
        } else {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    fadeIn(tween(AliflixMotion.Selection)) togetherWith fadeOut(tween(AliflixMotion.Selection))
                },
                label = "rating-pill-animation",
            ) { targetValue ->
                Text(
                    text = targetValue,
                    color = if (targetValue == "—") AliflixContentTertiary else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MovingMovieLoader(accent: Color) {
    val transition = rememberInfiniteTransition(label = "rating-movie-loader")
    val travel by transition.animateFloat(
        initialValue = -5f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "rating-movie-travel",
    )
    Box(
        modifier = Modifier
            .padding(start = AliflixSpacing.Small)
            .width(28.dp)
            .height(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = "Loading rating",
            tint = accent,
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { translationX = travel },
        )
    }
}

@Composable
private fun EpisodeRow(
    media: Media,
    episode: Episode,
    progress: PlaybackProgress?,
    current: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(media.key, episode.seasonNumber, episode.number) { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f, tween(200), label = "episodeDisclosure",
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = AliflixSpacing.Medium, vertical = 5.dp)
            .aliflixSurface(
                level = if (current) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content,
                alpha = AliflixAlpha.Episodes,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = if (expanded) "Collapse episode" else "Expand episode",
                    onClick = { expanded = !expanded },
                )
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.width(116.dp).aspectRatio(16f / 9f)
                    .aliflixSurface(shape = AliflixCorners.Small)
                    .clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "Play episode ${episode.number}: ${episode.title}",
                        onClick = onPlay,
                    ),
            ) {
                ArtworkPlaceholder(title = episode.title)
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape)
                        .background(AliflixContentPrimary.copy(alpha = 0.94f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, "Play episode ${episode.number}", tint = Color.Black, modifier = Modifier.size(18.dp))
                }
                if (shouldShowPlaybackProgressRing(progress)) {
                    PlaybackProgressRing(
                        progress!!.progressFraction.toFloat(),
                        Modifier.align(Alignment.BottomEnd).padding(AliflixSpacing.Tiny),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "S${episode.seasonNumber} \u2022 E${episode.number}",
                    color = AliflixAccentSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                )
                Text(
                    episode.title,
                    color = AliflixContentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (episode.runtime.isNotBlank()) {
                        Text(episode.runtime, color = AliflixMuted, fontSize = 12.sp)
                    }
                    episode.imdbRating?.takeIf { it > 0.0 }?.let { rating ->
                        Text(
                            text = "IMDb ${String.format(java.util.Locale.US, "%.1f", rating)}",
                            color = Color(0xFFF5C518),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = if (expanded) AliflixAccentSecondary else AliflixMuted,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevronRotation },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(220)),
            exit = androidx.compose.animation.shrinkVertically(tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(160)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (episode.overview.isNotBlank()) {
                    Text(
                        episode.overview,
                        color = AliflixContentSecondary,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AliflixContentPrimary, contentColor = AliflixBackgroundBase),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    com.aliflix.app.downloads.DownloadButton(media, episode)
                }
            }
        }
    }
}

internal fun playerEpisodesFor(
    detail: DetailUiState,
    selectedEpisode: Episode,
): List<Episode> {
    val loaded = detail.episodes.filter { it.seasonNumber == selectedEpisode.seasonNumber }
    if (loaded.isNotEmpty()) return loaded
    val episodeCount = detail.seasons
        .firstOrNull { it.number == selectedEpisode.seasonNumber }
        ?.episodeCount
        ?.coerceAtLeast(selectedEpisode.number)
        ?: selectedEpisode.number
    return (1..episodeCount).map { number ->
        if (number == selectedEpisode.number) {
            selectedEpisode
        } else {
            Episode(
                seasonNumber = selectedEpisode.seasonNumber,
                number = number,
                title = "Episode $number",
            )
        }
    }
}

internal data class EpisodeRatingsPresentation(
    val imdb: String,
)

internal fun episodeRatingsPresentation(episode: Episode): EpisodeRatingsPresentation =
    EpisodeRatingsPresentation(
        imdb = when {
            episode.imdbRating != null -> String.format(
                java.util.Locale.US,
                "%.1f",
                episode.imdbRating,
            )
            episode.imdbRatingState == RatingSourceState.NOT_RATED -> "Not rated"
            episode.imdbRatingState == RatingSourceState.UNAVAILABLE -> "Unavailable"
            else -> "Loading"
        },
    )

@Composable
private fun EpisodeRatingPill(
    source: String,
    value: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .clip(AliflixCorners.Small)
            .background(accent.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = source,
            color = accent,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = value,
            color = AliflixContentSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
