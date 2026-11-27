@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui




import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface


import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil.compose.AsyncImage
import com.aliflix.app.HomeUiState
import com.aliflix.app.TvNetworksUiState
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.PersonalMatch
import com.aliflix.app.recommendation.PersonalizationEngine
import com.aliflix.app.ui.home.HomeSkeleton
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBackgroundBase as AliflixBlack
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixBackgroundImmersive
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.absoluteValue
import com.aliflix.app.ui.common.MobileTopSafeArea
import com.aliflix.app.ui.common.aliflixScreenBackground

internal enum class HomeFilter(val label: String) {
    FOR_YOU("For You"),
    MOVIES("Movies"),
    TV("TV Shows"),
    NEW("Categories"),
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    tvNetworks: TvNetworksUiState,
    recent: List<Media>,
    likes: List<Media>,
    playbackProgress: Map<String, PlaybackProgress>,
    onRetry: () -> Unit,
    catalogueStore: com.aliflix.app.ui.discover.DiscoverCatalogueStore? = null,
    onRetryTvNetworks: () -> Unit,
    onOpen: (Media) -> Unit,
    onPlay: (Media) -> Unit,
    onSearch: () -> Unit,
    listState: LazyListState,
    selectedFilter: HomeFilter,
    previousFilter: HomeFilter = HomeFilter.FOR_YOU,
    onSelectFilter: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val movieListState = rememberLazyListState()
    val tvListState = rememberLazyListState()
    val activeListState = when (selectedFilter) { HomeFilter.MOVIES -> movieListState; HomeFilter.TV -> tvListState; else -> listState }
    when {
        state.content != null -> MobileHomeSearchFrame(selectedFilter != HomeFilter.NEW,
            modifier.fillMaxSize().aliflixScreenBackground(),
            header = { Column(Modifier.scrollable(activeListState, Orientation.Vertical, reverseDirection = true)) { MobileTopSafeArea(extraPadding = 0.dp); HomeHeader(onSearch) } },
        ) { headerBottom ->
            AnimatedContent(
                targetState = selectedFilter,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    ((fadeIn(tween(320)) + slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { direction * it / 10 }) togetherWith
                        (fadeOut(tween(AliflixMotion.Content)) + slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { -direction * it / 10 }))
                        .using(androidx.compose.animation.SizeTransform(clip = false))
                },
                label = "home-filter-transition",
            ) { visibleFilter ->
                val filterListState = when (visibleFilter) { HomeFilter.MOVIES -> movieListState; HomeFilter.TV -> tvListState; else -> listState }
                HomeFeed(
                    content = state.content, tvNetworks = tvNetworks, editorialPicks = state.editorialPicks,
                    recent = recent, likes = likes, playbackProgress = playbackProgress, catalogueStore = catalogueStore,
                    onRetryTvNetworks = onRetryTvNetworks, onOpen = onOpen, onPlay = onPlay, onSearch = onSearch,
                    listState = filterListState, selectedFilter = visibleFilter, previousFilter = previousFilter,
                    onSelectFilter = onSelectFilter, modifier = Modifier.fillMaxSize(), controlsOutside = true,
                    pinnedHeaderBottom = { headerBottom }, showFilters = visibleFilter == selectedFilter,
                )
            }
        }
        state.loading -> HomeSkeleton(modifier = modifier)
        else -> ConfigurationError(
            message = state.error ?: "Unable to load Aliflix.",
            onRetry = onRetry,
            modifier = modifier,
        )
    }
}

@Composable
internal fun HomeFeed(
    content: HomeContent,
    tvNetworks: TvNetworksUiState,
    editorialPicks: List<Media>,
    recent: List<Media>,
    likes: List<Media>,
    playbackProgress: Map<String, PlaybackProgress>,
    catalogueStore: com.aliflix.app.ui.discover.DiscoverCatalogueStore? = null,
    onRetryTvNetworks: () -> Unit,
    onOpen: (Media) -> Unit,
    onPlay: (Media) -> Unit,
    onSearch: () -> Unit,
    listState: LazyListState,
    selectedFilter: HomeFilter,
    previousFilter: HomeFilter = HomeFilter.FOR_YOU,
    onSelectFilter: (HomeFilter) -> Unit,
    modifier: Modifier,
    controlsOutside: Boolean = false,
    pinnedHeaderBottom: () -> Float = { 0f },
    showFilters: Boolean = true,
) {
    if (selectedFilter == HomeFilter.NEW && catalogueStore != null) {
        Column(modifier.fillMaxSize().aliflixScreenBackground()) {
            MobileTopSafeArea(extraPadding = 0.dp)
            com.aliflix.app.ui.discover.CategoryBrowser(
                catalogueStore,
                onOpen,
                onBack = { onSelectFilter(previousFilter) },
                onSearch = onSearch,
            )
        }
        return
    }
    var feedTop by remember { mutableFloatStateOf(0f) }
    var insideHeaderBottom by remember { mutableFloatStateOf(0f) }

    val baseRails = remember(content, selectedFilter, likes, tvNetworks.rails) {
        val matchingRails = when (selectedFilter) {
            HomeFilter.FOR_YOU -> content.rails
            HomeFilter.MOVIES -> content.rails
                .map { rail ->
                    rail.copy(items = rail.items.filter { it.type == MediaType.MOVIE })
                }
                .filter { it.items.isNotEmpty() }
            HomeFilter.TV -> content.rails
                .map { rail ->
                    rail.copy(items = rail.items.filter { it.type == MediaType.TV })
                }
                .filter { it.items.isNotEmpty() }
            HomeFilter.NEW -> content.rails.filter {
                "Now" in it.title || "Airing" in it.title || "Trending" in it.title
            }
        }
        val selectedRails = if (matchingRails.isEmpty()) {
            content.rails
        } else {
            matchingRails
        }
        selectedRails
    }
    // Affinity comparisons are CPU work. Score each title once on a worker,
    // instead of running the full matcher repeatedly inside a UI-thread sort.
    val filteredRails = androidx.compose.runtime.produceState(baseRails, baseRails, selectedFilter, likes) {
            value = baseRails
            if (selectedFilter == HomeFilter.FOR_YOU && likes.isNotEmpty()) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val scores = baseRails.flatMap { it.items }.distinctBy(Media::key)
                        .associate { it.key to (PersonalizationEngine.match(it, likes)?.score ?: 0) }
                    baseRails.map { rail -> rail.copy(items = rail.items.sortedByDescending { scores[it.key] ?: 0 }) }
                }
            }
        }.value
    val heroCandidates = remember(filteredRails, content.hero) {
        filteredRails.flatMap { rail -> rail.items }
            .filter { it.backdropPath != null }
            .distinctBy { it.key }
            .take(6)
            .ifEmpty { listOf(content.hero) }
    }
    val heroStartPage = remember(heroCandidates) {
        if (heroCandidates.size > 1) {
            val middle = Int.MAX_VALUE / 2
            middle - middle % heroCandidates.size
        } else {
            0
        }
    }
    val pagerState = rememberPagerState(
        initialPage = heroStartPage,
        pageCount = { if (heroCandidates.size > 1) Int.MAX_VALUE else 1 },
    )
    var automaticHeroTransition by remember { mutableStateOf(false) }
    val heroDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val heroLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(pagerState, heroCandidates, heroDragged) {
        if (heroCandidates.size > 1) {
            while (true) {
                kotlinx.coroutines.delay(7_000L)
                if ((heroDragged || pagerState.isScrollInProgress ||
                    listState.firstVisibleItemIndex > 0 || !heroLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))) continue
                automaticHeroTransition = true
                try {
                pagerState.animateScrollToPage(
                    page = pagerState.currentPage + 1,
                    animationSpec = tween(
                        durationMillis = 1_350,
                        easing = FastOutSlowInEasing,
                    ),
                )
                } finally { automaticHeroTransition = false }
            }
        }
    }

    // The poster is drawn by the hero item below, so it scrolls away with the hero and the rails
    // underneath it sit on this plain Aliflix background. Only the base colour stays out here.
    val feed: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { filterHeight ->
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box {
                val heroPage = pagerState.currentPage
                val heroPageOffset = pagerState.currentPageOffsetFraction
                val heroFeatured = heroCandidates[heroPage % heroCandidates.size]
                // The plane is bounded by the hero box, which scrolls with the list, so the
                // poster only ever occupies the hero and the dark background takes over below.
                CinematicPosterPlane(
                    artwork = heroFeatured.backdropUrl ?: heroFeatured.posterUrl,
                    modifier = Modifier.matchParentSize(),
                )
                if (heroPageOffset != 0f) {
                    val neighbour = heroCandidates[
                        Math.floorMod(
                            heroPage + if (heroPageOffset > 0) 1 else -1,
                            heroCandidates.size,
                        )
                    ]
                    CinematicPosterPlane(
                        artwork = neighbour.backdropUrl ?: neighbour.posterUrl,
                        modifier = Modifier.matchParentSize(),
                        planeAlpha = heroPageOffset.absoluteValue,
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    key = { page -> "$page:${heroCandidates[page % heroCandidates.size].key}" },
                ) { page ->
                    val featured = heroCandidates[page % heroCandidates.size]
                    val pageOffset = (
                        (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    ).absoluteValue.coerceIn(0f, 1f)
                    HeroBanner(
                        item = featured,
                        motionOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction) *
                                (if (automaticHeroTransition) 1f else -1f),
                        activeShot = pagerState.currentPage == page && listState.firstVisibleItemIndex == 0,
                        personalMatch = PersonalizationEngine.match(featured, likes),
                        onPlay = { onPlay(featured) },
                        onInfo = { onOpen(featured) },
                        modifier = Modifier
                            .zIndex(if (pagerState.currentPage == page) 1f else 0f)
                            .then(if (pagerState.currentPage != page) Modifier.clearAndSetSemantics {} else Modifier)
                            .graphicsLayer {
                            // Keep the frame fixed while artwork and typography dissolve separately.
                            translationX = size.width * ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                            alpha = (1f - pageOffset).coerceIn(0f, 1f)
                        },
                    )
                }

                if (!controlsOutside) HomeHeader(
                    onSearch = onSearch,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(AliflixInsets.Top)
                        .onGloballyPositioned { insideHeaderBottom = it.positionInRoot().y + it.size.height },
                )
            }
        }

        item { Spacer(Modifier.fillMaxWidth().height(filterHeight)) }

        if (recent.isNotEmpty() && selectedFilter == HomeFilter.FOR_YOU) {
            item {
                RecentRail(
                    items = recent,
                    playbackProgress = playbackProgress,
                    onOpen = onOpen,
                )
            }
        }

        if (editorialPicks.isNotEmpty() && selectedFilter == HomeFilter.FOR_YOU) {
            item(key = "taste-picks") {
                HomeMediaRail(
                    rail = ContentRail("Picked for you", editorialPicks),
                    onOpen = onOpen,
                    compact = false,
                )
            }
        }

        if (selectedFilter == HomeFilter.NEW && catalogueStore != null) {
            item(key = "categories") {
                com.aliflix.app.ui.discover.CategoryBrowser(
                    catalogueStore,
                    onOpen,
                    onBack = { onSelectFilter(previousFilter) },
                )
            }
        }
        items(if (selectedFilter == HomeFilter.NEW) emptyList() else filteredRails, key = { "${selectedFilter.name}:${it.title}" }) { rail ->
            // A lazy item enters composition on every approach to the viewport. Sliding
            // each rail on entry remeasured the list throughout a fling and the Home handoff.
            HomeMediaRail(rail = rail, onOpen = onOpen, compact = false)
        }
    }
    }
    MobilePinnedFilterLayout(listState,
        headerBottom = { (if (controlsOutside) pinnedHeaderBottom() else insideHeaderBottom-feedTop).coerceAtLeast(0f) },
        bar = { opacity -> MobileFilterBar(selectedFilter, onSelectFilter, opacity, showFilters) },
        feed = feed,
        showChrome = showFilters,
        modifier = modifier.fillMaxSize().background(AliflixBackgroundBase)
            .onGloballyPositioned { feedTop = it.positionInRoot().y })
}

@Composable
private fun TvNetworksStateCard(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    AliflixSurface(
        shape = AliflixCorners.Panel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AliflixSpacing.Content, vertical = AliflixSpacing.Panel),
        level = AliflixSurfaceLevel.Content,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = AliflixAccentSecondary,
                )
                Text("Loading popular TV networks and studios…", color = AliflixContentSecondary, fontSize = 13.sp)
            } else {
                Text(
                    text = error ?: "No TV network rows are available for this region yet.",
                    color = AliflixContentSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onRetry, shape = AliflixCorners.Card, border = null) {
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AliflixSpacing.Content, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        AliflixIconButton(
            onClick = onSearch,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .aliflixSurface(AliflixSurfaceLevel.Chrome, CircleShape),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Shared frame height for the home hero and its stationary, fading artwork.
 */
internal fun homeHeroHeight(fontScale: Float): androidx.compose.ui.unit.Dp =
    556.dp + ((fontScale - 1f).coerceAtLeast(0f) * 170f).coerceAtMost(320f).dp

@Composable
private fun HeroBanner(
    item: Media,
    personalMatch: PersonalMatch?,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    motionOffset: Float = 0f,
    activeShot: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    val heroHeight = homeHeroHeight(fontScale)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clipToBounds(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .graphicsLayer {
                    alpha = (1f - motionOffset.absoluteValue * 1.8f).coerceIn(0f, 1f)
                    translationY = motionOffset.absoluteValue * 28.dp.toPx()
                }
                .padding(horizontal = AliflixSpacing.Panel, vertical = AliflixSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        ) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.displayLarge,
                fontSize = 40.sp,
                lineHeight = 42.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (personalMatch != null) {
                    Box(
                        modifier = Modifier
                            .clip(AliflixCorners.Small)
                            .background(com.aliflix.app.ui.theme.AliflixGlassSelected)
                            .padding(horizontal = AliflixSpacing.Small, vertical = AliflixSpacing.Tiny),
                    ) {
                        Text(
                            text = personalMatch.label,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }
                val displayRating = item.imdbRating ?: item.rating.takeIf { it > 0.0 }
                if (displayRating != null && displayRating > 0.0) {
                    Text(
                        text = if (item.imdbRating != null) {
                            String.format(
                                java.util.Locale.US,
                                "★ %.1f IMDb",
                                displayRating,
                            )
                        } else {
                            String.format(
                                java.util.Locale.US,
                                "★ %.1f",
                                displayRating,
                            )
                        },
                        color = if (item.imdbRating != null) Color(0xFFF5C518) else MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    )
                }
                if (item.year.isNotBlank()) {
                    Text(
                        text = item.year,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            val typeAndGenres = buildList {
                add(if (item.type == MediaType.MOVIE) "Movie" else "Series")
                addAll(item.genres.take(2))
            }.joinToString("  •  ")
            if (typeAndGenres.isNotBlank()) {
                Text(
                    text = typeAndGenres,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = item.overview.ifBlank { "Open details to discover more about this title." },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = AliflixCorners.Card,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                    modifier = Modifier.heightIn(min = 50.dp),
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onInfo,
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = AliflixCorners.Card,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    modifier = Modifier.heightIn(min = 50.dp),
                    border = null,
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("More Info", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MobileFilterBar(selected: HomeFilter, onSelect: (HomeFilter) -> Unit, opacity: () -> Float, visible: Boolean) {
    LazyRow(modifier = Modifier.fillMaxWidth()
        .graphicsLayer { alpha = if (visible) 1f else 0f }
        .then(if (visible) Modifier.testTag("home-filter-bar") else Modifier.clearAndSetSemantics {})
        .drawBehind {
        drawRect(AliflixBackgroundBase.copy(alpha = opacity()))
    }, contentPadding = PaddingValues(horizontal = AliflixSpacing.Content, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small)) {
        items(HomeFilter.entries) { filter ->
            val active = filter == selected
            AliflixChip(selected = active, contentAlignment = Alignment.TopStart,
                contentPadding = PaddingValues(horizontal = AliflixSpacing.Content, vertical = 13.dp),
                modifier = Modifier.heightIn(min = AliflixSpacing.TouchTarget)
                    .selectable(selected = active, enabled = visible, onClick = { onSelect(filter) })) {
                Text(filter.label, color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun HomeMediaRail(
    rail: ContentRail,
    onOpen: (Media) -> Unit,
    compact: Boolean,
    onRemove: ((Media) -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val trending = rail.title.contains("Trending", ignoreCase = true)
    val editorialLandscape = !trending && listOf(
        "Popular",
        "Now Playing",
        "Airing",
        "Documentary",
        "Reality",
    ).any { marker -> rail.title.contains(marker, ignoreCase = true) }

    Column(
        modifier = Modifier.padding(top = if (trending) 30.dp else 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HomeSectionHeader(
            title = rail.title,
            eyebrow = when {
                trending -> "TRENDING NOW"
                editorialLandscape -> "EDITOR'S VIEW"
                else -> null
            },
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = AliflixSpacing.Content),
            horizontalArrangement = Arrangement.spacedBy(if (trending) 10.dp else AliflixSpacing.Medium),
        ) {
            itemsIndexed(rail.items, key = { _, item -> item.key }) { index, item ->
                when {
                    trending -> HomePosterCard(
                        item = item,
                        width = if (compact) 112.dp else 130.dp,
                        rank = index + 1,
                        onClick = { onOpen(item) },
                        onRemove = onRemove?.let { remove -> { remove(item) } },
                        removeDescription = "Remove ${item.title} from ${rail.title}",
                    )
                    editorialLandscape -> HomeLandscapeCard(
                        item = item,
                        compact = compact,
                        onClick = { onOpen(item) },
                        onRemove = onRemove?.let { remove -> { remove(item) } },
                        removeDescription = "Remove ${item.title} from ${rail.title}",
                    )
                    else -> HomePosterCard(
                        item = item,
                        width = if (compact) 112.dp else 128.dp,
                        onClick = { onOpen(item) },
                        onRemove = onRemove?.let { remove -> { remove(item) } },
                        removeDescription = "Remove ${item.title} from ${rail.title}",
                    )
                }
            }
            if (trailingContent != null) item(key = "pagination") { trailingContent() }
        }
    }
}

@Composable
private fun HomePosterCard(
    item: Media,
    width: androidx.compose.ui.unit.Dp,
    rank: Int? = null,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeDescription: String = "Remove ${item.title}",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val posterScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = AliflixMotion.press(),
        label = "home-poster-press",
    )
    Column(
        modifier = Modifier
            .width(width)
            .scale(posterScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(width / 0.68f),
        ) {
            AliflixMediaCard(
                modifier = Modifier
                    .fillMaxSize()
                    ,
            ) {
                ArtworkPlaceholder(title = item.title)
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = "${item.title} poster",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // No scrim is drawn over the poster itself so the artwork keeps its original colours.
                // The badges below each carry their own opaque backing and stay readable.
                val cardRating = item.imdbRating ?: item.rating.takeIf { it > 0.0 }
                if (cardRating != null && cardRating > 0.0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                            .clip(AliflixCorners.Small)
                            .background(AliflixBlack.copy(alpha = 0.78f))
                            .padding(horizontal = 7.dp, vertical = AliflixSpacing.Tiny),
                    ) {
                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "%.1f",
                                cardRating,
                            ),
                            color = if (item.imdbRating != null) Color(0xFFF5C518) else MaterialTheme.colorScheme.tertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (rank != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(AliflixSpacing.Small)
                            .heightIn(min = 30.dp)
                            .clip(AliflixCorners.Small)
                            .background(AliflixBackgroundImmersive.copy(alpha = 0.92f))
                            .padding(horizontal = AliflixSpacing.Small, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "#${rank.toString().padStart(2, '0')}",
                            color = AliflixContentPrimary,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.4.sp,
                        )
                    }
                }
                if (onRemove != null) {
                    AliflixIconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AliflixBlack.copy(alpha = 0.78f)),
                    ) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = removeDescription,
                            tint = AliflixContentPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = item.title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(34.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (item.type == MediaType.MOVIE) "Movie" else "Series",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            if (item.year.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outline),
                )
                Text(
                    text = item.year,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun HomeLandscapeCard(
    item: Media,
    compact: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeDescription: String = "Remove ${item.title}",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = AliflixMotion.Press),
        label = "home-landscape-press",
    )

    AliflixMediaCard(
        modifier = Modifier
            .width(if (compact) 210.dp else 238.dp)
            .aspectRatio(1.68f)
            .scale(cardScale)

            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        ArtworkPlaceholder(title = item.title)
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = "${item.title} artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.18f to Color.Transparent,
                        1f to AliflixBlack.copy(alpha = 0.94f),
                    ),
                ),
        )
        if (onRemove != null) {
            AliflixIconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AliflixSpacing.Small)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(AliflixBlack.copy(alpha = 0.78f)),
            ) {
                Icon(
                    Icons.Rounded.DeleteSweep,
                    contentDescription = removeDescription,
                    tint = AliflixContentPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
        ) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    add(if (item.type == MediaType.MOVIE) "Movie" else "Series")
                    if (item.year.isNotBlank()) add(item.year)
                }.joinToString("  •  "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun HomeSectionHeader(
    title: String,
    eyebrow: String? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
