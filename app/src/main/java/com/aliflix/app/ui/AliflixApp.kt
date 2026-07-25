@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.aliflix.app.AliflixViewModel
import com.aliflix.app.DetailUiState
import com.aliflix.app.HomeUiState
import com.aliflix.app.SearchUiState
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.player.WebPlayerScreen
import com.aliflix.app.recommendation.PersonalMatch
import com.aliflix.app.recommendation.PersonalizationEngine
import com.aliflix.app.update.AppUpdateManager
import com.aliflix.app.update.InstallLaunchResult
import com.aliflix.app.update.UpdateCheckResult
import com.aliflix.app.update.UpdateInfo
import com.aliflix.app.ui.theme.AliflixBlack
import com.aliflix.app.ui.theme.AliflixGreen
import com.aliflix.app.ui.theme.AliflixIce
import com.aliflix.app.ui.theme.AliflixMuted
import com.aliflix.app.ui.theme.AliflixRed
import com.aliflix.app.ui.theme.AliflixSurface
import com.aliflix.app.ui.theme.AliflixSurfaceRaised
import kotlinx.coroutines.launch
import java.io.File

private enum class AppTab(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    MY_SPACE("My Space"),
}

private enum class AppScreen {
    HOME,
    SEARCH,
    MY_SPACE,
    DETAIL,
}

private enum class HomeFilter(val label: String) {
    FOR_YOU("For You"),
    MOVIES("Movies"),
    TV("TV Shows"),
    ANIME("Anime"),
    NEW("New & Popular"),
}

private data class MobileUpdateUiState(
    val busy: Boolean = false,
    val message: String = "",
    val progress: Int? = null,
    val available: UpdateInfo? = null,
    val downloadedApk: File? = null,
)

@Composable
fun AliflixApp(
    viewModel: AliflixViewModel,
    playerController: WebPlayerController,
) {
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val myList by viewModel.myList.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val likes by viewModel.likes.collectAsState()

    val playbackPreferences by viewModel.playbackPreferences.collectAsState()
    val ramoflixConfig = playbackPreferences.ramoflixConfig
    val movies67BaseUrl = playbackPreferences.movies67BaseUrl
    val activity = LocalActivity.current as ComponentActivity
    val updateManager = remember(activity) { AppUpdateManager(activity) }
    val updateScope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf(MobileUpdateUiState()) }
    var urlDialogProvider by remember { mutableStateOf<PlaybackProviderId?>(null) }

    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    val selectedTab = AppTab.valueOf(selectedTabName)
    var playerSelection by remember { mutableStateOf<PlaybackSelection?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    val homeScrollState = rememberLazyListState()
    val searchScrollState = rememberLazyGridState()
    val listScrollState = rememberLazyGridState()
    val favoritesScrollState = rememberLazyGridState()
    val historyScrollState = rememberLazyGridState()
    var homeFilterName by rememberSaveable { mutableStateOf(HomeFilter.FOR_YOU.name) }
    var searchMediaFilter by rememberSaveable { mutableStateOf("All") }
    var libraryPage by rememberSaveable { mutableIntStateOf(0) }
    val launchVisible =
        selectedTab == AppTab.HOME && detail.item == null && home.loading && home.content == null

    fun openDetails(item: Media) {
        viewModel.openDetails(item)
    }

    fun playSelection(selection: PlaybackSelection) {
        viewModel.markPlayed(selection.media)
        playerSelection = selection.copy(
            source = playbackPreferences.sourceFor(selection.media),
        )
        playerVisible = true
    }

    fun playMedia(item: Media) = playSelection(PlaybackSelection(item))

    fun playEpisode(item: Media, episode: Episode) = playSelection(
        PlaybackSelection(
            media = item,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.number,
            episodeTitle = episode.title,
        ),
    )

    fun checkForUpdates() {
        if (updateUi.busy) return
        updateUi = updateUi.copy(
            busy = true,
            message = "Checking GitHub for updates...",
            progress = null,
        )
        updateScope.launch {
            updateUi = when (val result = updateManager.checkForUpdate()) {
                is UpdateCheckResult.Available -> MobileUpdateUiState(
                    message = "Version ${result.info.versionName} is available.",
                    available = result.info,
                )
                is UpdateCheckResult.UpToDate -> MobileUpdateUiState(
                    message = "Aliflix ${result.versionName} is up to date.",
                )
                is UpdateCheckResult.Error -> MobileUpdateUiState(
                    message = result.message,
                )
            }
        }
    }

    fun installDownloadedUpdate() {
        val apk = updateUi.downloadedApk ?: return
        val message = when (updateManager.launchInstaller(apk)) {
            InstallLaunchResult.INSTALLER_OPENED ->
                "Confirm the update in Android's installer."
            InstallLaunchResult.PERMISSION_REQUIRED ->
                "Allow installs from Aliflix, then return and tap Install update."
            InstallLaunchResult.FAILED ->
                "Android could not open the update installer."
        }
        updateUi = updateUi.copy(message = message)
    }

    fun downloadUpdate() {
        val info = updateUi.available ?: return
        if (updateUi.busy) return
        updateUi = updateUi.copy(
            busy = true,
            progress = 0,
            message = "Downloading ${info.versionName}...",
        )
        updateScope.launch {
            updateManager.download(info) { progress ->
                updateUi = updateUi.copy(
                    progress = progress,
                    message = "Downloading ${info.versionName}... $progress%",
                )
            }.fold(
                onSuccess = { apk ->
                    updateUi = updateUi.copy(
                        busy = false,
                        progress = 100,
                        downloadedApk = apk,
                        message = "Download verified. Ready to install.",
                    )
                    installDownloadedUpdate()
                },
                onFailure = { error ->
                    updateUi = updateUi.copy(
                        busy = false,
                        progress = null,
                        message = error.message ?: "The update download failed.",
                    )
                },
            )
        }
    }

    BackHandler(enabled = detail.item != null && !playerVisible) {
        viewModel.closeDetails()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AliflixBlack),
    ) {
        Scaffold(
            containerColor = AliflixBlack,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (detail.item == null && !launchVisible) {
                    AliflixBottomBar(
                        selected = selectedTab,
                        onSelect = { selectedTabName = it.name },
                    )
                }
            },
        ) { padding ->
            val screen = when {
                detail.item != null -> AppScreen.DETAIL
                selectedTab == AppTab.HOME -> AppScreen.HOME
                selectedTab == AppTab.SEARCH -> AppScreen.SEARCH
                else -> AppScreen.MY_SPACE
            }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val enterSpec = tween<IntOffset>(500, easing = FastOutSlowInEasing)
                    val fadeEnterSpec = tween<Float>(500, easing = FastOutSlowInEasing)
                    val exitSpec = tween<IntOffset>(420, easing = FastOutSlowInEasing)
                    val fadeExitSpec = tween<Float>(360, easing = FastOutSlowInEasing)
                    when {
                        targetState == AppScreen.DETAIL -> {
                            (
                                fadeIn(fadeEnterSpec) +
                                    slideInHorizontally(enterSpec) { it / 7 } +
                                    scaleIn(fadeEnterSpec, initialScale = 0.975f)
                                ).togetherWith(
                                fadeOut(fadeExitSpec) +
                                    slideOutHorizontally(exitSpec) { -it / 18 } +
                                    scaleOut(fadeExitSpec, targetScale = 0.99f),
                            )
                        }
                        initialState == AppScreen.DETAIL -> {
                            (
                                fadeIn(fadeEnterSpec) +
                                    slideInHorizontally(enterSpec) { -it / 12 } +
                                    scaleIn(fadeEnterSpec, initialScale = 0.99f)
                                ).togetherWith(
                                fadeOut(fadeExitSpec) +
                                    slideOutHorizontally(exitSpec) { it / 7 } +
                                    scaleOut(fadeExitSpec, targetScale = 0.975f),
                            )
                        }
                        targetState.ordinal > initialState.ordinal -> {
                            (
                                fadeIn(fadeEnterSpec) +
                                    slideInHorizontally(enterSpec) { it / 10 }
                                ).togetherWith(
                                fadeOut(fadeExitSpec) +
                                    slideOutHorizontally(exitSpec) { -it / 12 },
                            )
                        }
                        else -> {
                            (
                                fadeIn(fadeEnterSpec) +
                                    slideInHorizontally(enterSpec) { -it / 10 }
                                ).togetherWith(
                                fadeOut(fadeExitSpec) +
                                    slideOutHorizontally(exitSpec) { it / 12 },
                            )
                        }
                    }
                },
                label = "aliflix-screen",
            ) { target ->
                when (target) {
                    AppScreen.DETAIL -> DetailScreen(
                        state = detail,
                        inMyList = detail.item?.let(viewModel::isInMyList) == true,
                        liked = detail.item?.let(viewModel::isLiked) == true,
                        onBack = viewModel::closeDetails,
                        onPlay = ::playMedia,
                        onPlayEpisode = ::playEpisode,
                        onSelectSeason = viewModel::selectSeason,
                        onToggleMyList = viewModel::toggleMyList,
                        onToggleLike = viewModel::toggleLike,
                        onOpen = ::openDetails,
                        generalProvider = playbackPreferences.safeGeneralProvider,
                        onSelectProvider = viewModel::selectGeneralPlaybackProvider,
                        personalMatch = detail.item?.let {
                            PersonalizationEngine.match(it, likes)
                        },
                    )

                    AppScreen.HOME -> HomeScreen(
                        state = home,
                        recent = recent,
                        likes = likes,
                        onRetry = viewModel::refreshHome,
                        onOpen = ::openDetails,
                        onPlay = ::playMedia,
                        onSearch = { selectedTabName = AppTab.SEARCH.name },
                        onEditProviderUrl = {
                            urlDialogProvider = playbackPreferences.safeGeneralProvider
                        },
                        listState = homeScrollState,
                        selectedFilter = HomeFilter.valueOf(homeFilterName),
                        onSelectFilter = { homeFilterName = it.name },
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

                    AppScreen.SEARCH -> SearchScreen(
                        state = search,
                        onQueryChange = viewModel::updateSearch,
                        onOpen = ::openDetails,
                        gridState = searchScrollState,
                        mediaFilter = searchMediaFilter,
                        onMediaFilterChange = { searchMediaFilter = it },
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

                    AppScreen.MY_SPACE -> MySpaceScreen(
                        myList = myList,
                        likes = likes,
                        recent = recent,
                        onOpen = ::openDetails,
                        onRemoveRecent = viewModel::removeRecent,
                        onClearRecent = viewModel::clearRecent,
                        listGridState = listScrollState,
                        favoritesGridState = favoritesScrollState,
                        historyGridState = historyScrollState,
                        page = libraryPage,
                        onPageChange = { libraryPage = it },
                        generalProvider = playbackPreferences.safeGeneralProvider,
                        onSelectProvider = viewModel::selectGeneralPlaybackProvider,
                        onEditProviderUrl = { provider ->
                            urlDialogProvider = provider
                        },
                        updateUi = updateUi,
                        onCheckForUpdates = ::checkForUpdates,
                        onDownloadUpdate = ::downloadUpdate,
                        onInstallUpdate = ::installDownloadedUpdate,
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = playerSelection != null && playerVisible,
            enter = fadeIn(tween(500, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(500, easing = FastOutSlowInEasing)) { it / 10 } +
                scaleIn(tween(500, easing = FastOutSlowInEasing), initialScale = 0.985f),
            exit = fadeOut(tween(420, easing = FastOutSlowInEasing)) +
                slideOutVertically(tween(500, easing = FastOutSlowInEasing)) { it / 10 } +
                scaleOut(tween(420, easing = FastOutSlowInEasing), targetScale = 0.985f),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f),
        ) {
            playerSelection?.let { selection ->
                WebPlayerScreen(
                    selection = selection,
                    visible = playerVisible,
                    controller = playerController,
                    onClose = { playerVisible = false },
                )
            }
        }

        urlDialogProvider?.let { provider ->
            val isRamoflix = provider == PlaybackProviderId.RAMOFLIX
            ProviderUrlDialog(
                providerName = provider.displayName,
                description = if (isRamoflix) {
                    "Change this address only if the Ramoflix domain moves."
                } else {
                    "Change this address if 67 Movies moves to a new domain. " +
                        "The default domain uses the optimized direct player."
                },
                currentUrl = if (isRamoflix) {
                    ramoflixConfig.baseUrl
                } else {
                    movies67BaseUrl
                },
                defaultUrl = provider.defaultBaseUrl,
                onSave = {
                    if (isRamoflix) {
                        viewModel.updateRamoflixUrl(it)
                    } else {
                        viewModel.updateMovies67Url(it)
                    }
                    urlDialogProvider = null
                },
                onReset = {
                    if (isRamoflix) {
                        viewModel.resetRamoflixUrl()
                    } else {
                        viewModel.resetMovies67Url()
                    }
                    urlDialogProvider = null
                },
                onDismiss = { urlDialogProvider = null },
            )
        }
    }
}

@Composable
private fun AliflixBottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .shadow(30.dp, RoundedCornerShape(30.dp), ambientColor = Color.Black)
            .clip(RoundedCornerShape(30.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFA11141B), Color(0xFA17131A), Color(0xFA11141B)),
                ),
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(30.dp))
            .padding(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val isSelected = selected == tab
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp)
                        .scale(if (pressed) 0.95f else 1f)
                        .clip(RoundedCornerShape(23.dp))
                        .background(
                            if (isSelected) {
                                Brush.verticalGradient(
                                    listOf(
                                        AliflixRed.copy(alpha = 0.25f),
                                        AliflixRed.copy(alpha = 0.08f),
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = when (tab) {
                            AppTab.HOME -> {
                                if (isSelected) Icons.Filled.Home else Icons.Outlined.Home
                            }
                            AppTab.SEARCH -> {
                                if (isSelected) Icons.Filled.Search else Icons.Outlined.Search
                            }
                            AppTab.MY_SPACE -> {
                                if (isSelected) Icons.Filled.Person else Icons.Outlined.Person
                            }
                        },
                        contentDescription = tab.label,
                        tint = if (isSelected) Color.White else Color(0xFF767B87),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        color = if (isSelected) Color.White else Color(0xFF767B87),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (isSelected) {
                        Spacer(Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(AliflixRed),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: HomeUiState,
    recent: List<Media>,
    likes: List<Media>,
    onRetry: () -> Unit,
    onOpen: (Media) -> Unit,
    onPlay: (Media) -> Unit,
    onSearch: () -> Unit,
    onEditProviderUrl: () -> Unit,
    listState: LazyListState,
    selectedFilter: HomeFilter,
    onSelectFilter: (HomeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> LoadingScreen(modifier)
        state.error != null || state.content == null -> ConfigurationError(
            message = state.error ?: "Unable to load Aliflix.",
            onRetry = onRetry,
            modifier = modifier,
        )
        else -> HomeFeed(
            content = state.content,
            recent = recent,
            likes = likes,
            onOpen = onOpen,
            onPlay = onPlay,
            onSearch = onSearch,
            onEditProviderUrl = onEditProviderUrl,
            listState = listState,
            selectedFilter = selectedFilter,
            onSelectFilter = onSelectFilter,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeFeed(
    content: HomeContent,
    recent: List<Media>,
    likes: List<Media>,
    onOpen: (Media) -> Unit,
    onPlay: (Media) -> Unit,
    onSearch: () -> Unit,
    onEditProviderUrl: () -> Unit,
    listState: LazyListState,
    selectedFilter: HomeFilter,
    onSelectFilter: (HomeFilter) -> Unit,
    modifier: Modifier,
) {
    val filtersPinned by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val filteredRails = remember(content, selectedFilter, likes) {
        val selectedRails = when (selectedFilter) {
            HomeFilter.FOR_YOU -> content.rails
            HomeFilter.MOVIES -> content.rails.map { rail ->
                rail.copy(items = rail.items.filter { it.type == MediaType.MOVIE })
            }.filter { it.items.isNotEmpty() }
            HomeFilter.TV -> content.rails.map { rail ->
                rail.copy(items = rail.items.filter { it.type == MediaType.TV })
            }.filter { it.items.isNotEmpty() }
            HomeFilter.ANIME -> content.rails
                .filter { rail -> rail.title.contains("Anime", ignoreCase = true) }
                .map { rail ->
                    rail.copy(items = rail.items.filter(Media::isJapaneseAnime))
                }
                .filter { rail -> rail.items.isNotEmpty() }
            HomeFilter.NEW -> content.rails.filter {
                "Now" in it.title || "Airing" in it.title || "Trending" in it.title
            }
        }.ifEmpty { content.rails }
        if (selectedFilter == HomeFilter.FOR_YOU && likes.isNotEmpty()) {
            selectedRails.map { rail ->
                rail.copy(
                    items = rail.items.sortedByDescending { item ->
                        PersonalizationEngine.match(item, likes)?.score ?: 0
                    },
                )
            }
        } else {
            selectedRails
        }
    }
    val hero = filteredRails.firstNotNullOfOrNull { rail ->
        rail.items.firstOrNull { it.backdropPath != null }
    } ?: content.hero

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Box {
                HeroBanner(
                    item = hero,
                    personalMatch = PersonalizationEngine.match(hero, likes),
                    onPlay = { onPlay(hero) },
                    onInfo = { onOpen(hero) },
                )
                HomeHeader(
                    onSearch = onSearch,
                    onEditProviderUrl = onEditProviderUrl,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars),
                )
            }
        }

        stickyHeader {
            FilterBar(
                selected = selectedFilter,
                onSelect = onSelectFilter,
                pinned = filtersPinned,
            )
        }

        if (recent.isNotEmpty() && selectedFilter == HomeFilter.FOR_YOU) {
            item {
                RecentRail(
                    items = recent,
                    onOpen = onOpen,
                )
            }
        }

        items(filteredRails, key = { it.title }) { rail ->
            MediaRail(
                rail = rail,
                onOpen = onOpen,
                compact = false,
            )
        }
    }
}

@Composable
private fun HomeHeader(
    onSearch: () -> Unit,
    onEditProviderUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                ),
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onEditProviderUrl)
                .padding(vertical = 4.dp, horizontal = 4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(AliflixRed, Color(0xFFB70B35)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "A",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "ALIFLIX",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.6.sp,
            )
        }
        IconButton(
            onClick = onSearch,
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = "Search",
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun HeroBanner(
    item: Media,
    personalMatch: PersonalMatch?,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(510.dp),
    ) {
        ArtworkPlaceholder(title = item.title)
        AsyncImage(
            model = item.backdropUrl ?: item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.18f),
                            0.46f to Color.Transparent,
                            1f to AliflixBlack,
                        ),
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.54f),
                            0.72f to Color.Transparent,
                        ),
                    )
                },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AliflixRed)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "SPOTLIGHT",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                }
                Text(
                    text = "SELECTED FOR TONIGHT",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.displayLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (personalMatch != null) {
                    Text(
                        text = "${personalMatch.score}% Match",
                        color = AliflixGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
                if (item.year.isNotBlank()) Text(item.year, color = Color.White.copy(alpha = 0.75f))
                Text(
                    text = if (item.type == MediaType.MOVIE) "Movie" else "Series",
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            Text(
                text = item.overview.ifBlank { "Open details for the full English synopsis." },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.82f),
                lineHeight = 20.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 13.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onInfo,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.13f),
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
                ) {
                    Icon(Icons.Rounded.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("More Info", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    pinned: Boolean,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(AliflixBlack.copy(alpha = 0.96f))
            .then(
                if (pinned) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                },
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HomeFilter.entries) { filter ->
            val active = filter == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (active) Color.White else Color.White.copy(alpha = 0.06f),
                    )
                    .border(
                        1.dp,
                        if (active) Color.White else Color.White.copy(alpha = 0.10f),
                        CircleShape,
                    )
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = filter.label,
                    color = if (active) Color.Black else Color.White.copy(alpha = 0.82f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun MediaRail(
    rail: ContentRail,
    onOpen: (Media) -> Unit,
    compact: Boolean,
) {
    val ranked = rail.title.contains("Trending", ignoreCase = true) ||
        rail.title.contains("Popular", ignoreCase = true)
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(
            title = rail.title,
            eyebrow = if (ranked) "WHAT'S HOT" else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(rail.items, key = { _, item -> item.key }) { index, item ->
                MediaPoster(
                    item = item,
                    width = if (compact) 112.dp else 126.dp,
                    rank = if (ranked && index < 10) index + 1 else null,
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
private fun MediaPoster(
    item: Media,
    width: androidx.compose.ui.unit.Dp,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val posterScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "poster-press",
    )
    Column(
        modifier = Modifier
            .width(if (rank == null) width else width + 20.dp)
            .scale(posterScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .align(Alignment.End)
                .aspectRatio(0.68f)
                .shadow(12.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(AliflixSurfaceRaised),
        ) {
            ArtworkPlaceholder(title = item.title)
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (item.type == MediaType.MOVIE) "MOVIE" else "SERIES",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
            if (rank != null) {
                Text(
                    text = rank.toString().padStart(2, '0'),
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            }
        }
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (rank == null) 0.dp else 20.dp),
        )
        if (item.year.isNotBlank()) {
            Text(
                text = item.year,
                color = AliflixMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = if (rank == null) 0.dp else 20.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    eyebrow: String? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (eyebrow != null) {
            Text(
                text = eyebrow,
                color = AliflixRed,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RecentRail(
    items: List<Media>,
    onOpen: (Media) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(title = "Recently Played", eyebrow = "PICK UP THE STORY")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.key }) { item ->
                Box(
                    modifier = Modifier
                        .width(210.dp)
                        .aspectRatio(1.62f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AliflixSurfaceRaised)
                        .clickable { onOpen(item) },
                ) {
                    ArtworkPlaceholder(title = item.title)
                    AsyncImage(
                        model = item.backdropUrl ?: item.posterUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.25f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.90f),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = item.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (item.type == MediaType.MOVIE) "Movie" else "Series",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 11.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    mediaFilter: String,
    onMediaFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var queryValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length),
            ),
        )
    }
    LaunchedEffect(state.query) {
        if (state.query != queryValue.text) {
            queryValue = TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length),
            )
        }
    }
    val visibleResults = remember(state.results, mediaFilter) {
        when (mediaFilter) {
            "Movies" -> state.results.filter { it.type == MediaType.MOVIE }
            "Series" -> state.results.filter { it.type == MediaType.TV }
            else -> state.results
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF101D29), AliflixBlack, AliflixBlack),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp),
    ) {
        Text(
            text = "DISCOVER",
            color = AliflixRed,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.7.sp,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "Find your next story",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextField(
            value = queryValue,
            onValueChange = { updated ->
                queryValue = updated
                onQueryChange(updated.text)
            },
            placeholder = { Text("Movies, series, anime…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            queryValue = TextFieldValue("")
                            onQueryChange("")
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White.copy(alpha = 0.10f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AliflixRed)
            }
            state.error != null -> EmptyMessage(
                title = "Search unavailable",
                message = state.error,
            )
            state.query.isBlank() -> EmptyMessage(
                title = "Everything starts with a title",
                message = "Search the complete movie and series catalogue.",
            )
            state.results.isEmpty() -> EmptyMessage(
                title = "No matches",
                message = "Try another title or keyword.",
            )
            else -> Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf("All", "Movies", "Series").forEach { option ->
                        val active = mediaFilter == option
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (active) Color.White else Color.White.copy(alpha = 0.06f),
                                )
                                .clickable { onMediaFilterChange(option) }
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = option,
                                color = if (active) Color.Black else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${visibleResults.size}",
                        color = AliflixMuted,
                        fontSize = 12.sp,
                    )
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(108.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(visibleResults, key = { it.key }) { item ->
                        MediaPoster(
                            item = item,
                            width = 108.dp,
                            onClick = { onOpen(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackProviderSelector(
    selectedProvider: PlaybackProviderId,
    animeOnly: Boolean,
    onSelectProvider: (PlaybackProviderId) -> Unit,
    modifier: Modifier = Modifier,
    onEditProviderUrl: ((PlaybackProviderId) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PLAYBACK SOURCE",
                    color = AliflixRed,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = if (animeOnly) {
                        "Japanese anime always streams with Miruro"
                    } else {
                        "Choose the service used when you press Play"
                    },
                    color = AliflixMuted,
                    fontSize = 11.sp,
                )
            }
        }

        if (animeOnly) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(AliflixRed.copy(alpha = 0.22f))
                    .border(
                        1.dp,
                        AliflixRed.copy(alpha = 0.55f),
                        RoundedCornerShape(13.dp),
                    )
                    .padding(horizontal = 13.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Miruro · Japanese Anime",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    PlaybackProviderId.RAMOFLIX,
                    PlaybackProviderId.MOVIES_67,
                ).forEach { provider ->
                    val selected = selectedProvider == provider
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                if (selected) AliflixRed else Color.Black.copy(alpha = 0.22f),
                            )
                            .border(
                                1.dp,
                                if (selected) {
                                    AliflixRed
                                } else {
                                    Color.White.copy(alpha = 0.10f)
                                },
                                RoundedCornerShape(13.dp),
                            )
                            .clickable { onSelectProvider(provider) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = provider.displayName,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (onEditProviderUrl != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            onEditProviderUrl(PlaybackProviderId.RAMOFLIX)
                        },
                    ) {
                        Text(
                            text = "Edit Ramoflix URL",
                            color = AliflixIce,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    TextButton(
                        onClick = {
                            onEditProviderUrl(PlaybackProviderId.MOVIES_67)
                        },
                    ) {
                        Text(
                            text = "Edit 67 URL",
                            color = AliflixIce,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MobileUpdatePanel(
    state: MobileUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AliflixRed.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = AliflixRed,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "App updates",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.message.ifBlank {
                    "Check the Aliflix GitHub release for a newer version."
                },
                color = AliflixMuted,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            state.busy -> CircularProgressIndicator(
                color = AliflixRed,
                strokeWidth = 3.dp,
                modifier = Modifier.size(25.dp),
            )
            state.downloadedApk != null -> Button(
                onClick = onInstall,
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AliflixRed),
            ) {
                Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            state.available != null -> Button(
                onClick = onDownload,
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AliflixRed),
            ) {
                Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            else -> OutlinedButton(
                onClick = onCheck,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text("Check", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MySpaceScreen(
    myList: List<Media>,
    likes: List<Media>,
    recent: List<Media>,
    onOpen: (Media) -> Unit,
    onRemoveRecent: (Media) -> Unit,
    onClearRecent: () -> Unit,
    listGridState: LazyGridState,
    favoritesGridState: LazyGridState,
    historyGridState: LazyGridState,
    page: Int,
    onPageChange: (Int) -> Unit,
    generalProvider: PlaybackProviderId,
    onSelectProvider: (PlaybackProviderId) -> Unit,
    onEditProviderUrl: (PlaybackProviderId) -> Unit,
    updateUi: MobileUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = page.coerceIn(0, 2),
        pageCount = { 3 },
    )
    var showClearConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(page) {
        if (pagerState.settledPage != page) {
            pagerState.animateScrollToPage(page)
        }
    }
    LaunchedEffect(pagerState.settledPage) {
        if (page != pagerState.settledPage) {
            onPageChange(pagerState.settledPage)
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear viewing history?") },
            text = {
                Text(
                    "This removes every title from History and resets the history part " +
                        "of your personalized match scores. My List is not affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearRecent()
                    },
                ) {
                    Text("Clear all", color = AliflixRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            },
            containerColor = AliflixSurfaceRaised,
            titleContentColor = Color.White,
            textContentColor = AliflixMuted,
            shape = RoundedCornerShape(24.dp),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF251017), Color(0xFF0B0D12), AliflixBlack),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp),
    ) {
        Text(
            text = "MY SPACE",
            color = AliflixRed,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.7.sp,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = when (pagerState.currentPage) {
                0 -> "Curated by genre"
                1 -> "Your favorites"
                else -> "Viewing history"
            },
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PlaybackProviderSelector(
            selectedProvider = generalProvider,
            animeOnly = false,
            onSelectProvider = onSelectProvider,
            onEditProviderUrl = onEditProviderUrl,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        MobileUpdatePanel(
            state = updateUi,
            onCheck = onCheckForUpdates,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("My List", "Favorites", "History").forEachIndexed { index, label ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) {
                                Brush.horizontalGradient(
                                    listOf(
                                        AliflixRed.copy(alpha = 0.38f),
                                        Color.White.copy(alpha = 0.11f),
                                    ),
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .clickable { onPageChange(index) }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else AliflixMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        Text(
            text = "Swipe left or right to switch",
            color = AliflixMuted.copy(alpha = 0.72f),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            beyondViewportPageCount = 1,
        ) { targetPage ->
            if (targetPage == 0) {
                GenreOrganizedList(
                    items = myList,
                    onOpen = onOpen,
                    gridState = listGridState,
                )
            } else if (targetPage == 1) {
                GenreOrganizedList(
                    items = likes,
                    onOpen = onOpen,
                    gridState = favoritesGridState,
                    emptyTitle = "No favorites yet",
                    emptyMessage = "Tap the heart on a title to add it here and tune Match scores.",
                )
            } else {
                HistoryCollection(
                    items = recent,
                    onOpen = onOpen,
                    onRemove = onRemoveRecent,
                    onClear = { showClearConfirmation = true },
                    gridState = historyGridState,
                )
            }
        }
    }
}

@Composable
private fun GenreOrganizedList(
    items: List<Media>,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    emptyTitle: String = "Nothing saved yet",
    emptyMessage: String = "Add titles from their details screen to build your space.",
) {
    if (items.isEmpty()) {
        EmptyMessage(
            title = emptyTitle,
            message = emptyMessage,
        )
        return
    }
    val genreGroups = remember(items) {
        items
            .groupBy { media ->
                media.genres.firstOrNull()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: "Other discoveries"
            }
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<Media>>> { it.value.size }
                    .thenBy { it.key },
            )
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        genreGroups.forEach { (genre, genreItems) ->
            item(
                key = "genre:$genre",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(22.dp)
                            .clip(CircleShape)
                            .background(AliflixRed),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = genre,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = genreItems.size.toString(),
                        color = AliflixMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.07f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            items(genreItems, key = { "saved:${it.key}" }) { item ->
                MediaPoster(
                    item = item,
                    width = 108.dp,
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
private fun HistoryCollection(
    items: List<Media>,
    onOpen: (Media) -> Unit,
    onRemove: (Media) -> Unit,
    onClear: () -> Unit,
    gridState: LazyGridState,
) {
    if (items.isEmpty()) {
        EmptyMessage(
            title = "Your history is quiet",
            message = "Titles appear here after you open the player.",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${items.size} ${if (items.size == 1) "title" else "titles"}",
                color = AliflixMuted,
                fontSize = 12.sp,
            )
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(
                    Icons.Rounded.DeleteSweep,
                    contentDescription = null,
                    tint = AliflixRed,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Clear all",
                    color = AliflixRed,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(items, key = { "history:${it.key}" }) { item ->
                Box {
                    MediaPoster(
                        item = item,
                        width = 108.dp,
                        onClick = { onOpen(item) },
                    )
                    IconButton(
                        onClick = { onRemove(item) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.75f)),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove from viewing history",
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(
    state: DetailUiState,
    inMyList: Boolean,
    liked: Boolean,
    personalMatch: PersonalMatch?,
    onBack: () -> Unit,
    onPlay: (Media) -> Unit,
    onPlayEpisode: (Media, Episode) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleMyList: (Media) -> Unit,
    onToggleLike: (Media) -> Unit,
    onOpen: (Media) -> Unit,
    generalProvider: PlaybackProviderId,
    onSelectProvider: (PlaybackProviderId) -> Unit,
) {
    val item = state.item ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(440.dp),
            ) {
                ArtworkPlaceholder(title = item.title)
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.12f),
                                    0.55f to Color.Transparent,
                                    1f to AliflixBlack,
                                ),
                            )
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = 0.42f),
                                    0.8f to Color.Transparent,
                                ),
                            )
                        },
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(16.dp)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "ALIFLIX FEATURE",
                        color = AliflixRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Black,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (personalMatch != null) {
                        Text(
                            text = "${personalMatch.score}% Match",
                            color = AliflixGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                    listOf(
                        item.year,
                        if (item.type == MediaType.MOVIE) "Movie" else "Series",
                    ).filter { it.isNotBlank() }.forEach { label ->
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.07f))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(label, color = AliflixMuted, fontSize = 12.sp)
                        }
                    }
                }
                RatingsRow(item = item)
                PlaybackProviderSelector(
                    selectedProvider = generalProvider,
                    animeOnly = item.isJapaneseAnime,
                    onSelectProvider = onSelectProvider,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val firstEpisode = state.episodes.firstOrNull()
                            if (item.type == MediaType.TV && firstEpisode != null) {
                                onPlayEpisode(item, firstEpisode)
                            } else {
                                onPlay(item)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AliflixRed,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (
                                item.type == MediaType.TV &&
                                state.episodes.isNotEmpty()
                            ) {
                                "Play S${state.selectedSeason} E${state.episodes.first().number}"
                            } else {
                                "Play"
                            } + " · " + if (item.isJapaneseAnime) {
                                PlaybackProviderId.MIRURO.displayName
                            } else {
                                generalProvider.displayName
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = { onToggleMyList(item) },
                        modifier = Modifier
                            .height(54.dp)
                            .width(112.dp),
                        shape = RoundedCornerShape(17.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color.White.copy(alpha = 0.16f),
                        ),
                    ) {
                        Icon(
                            if (inMyList) Icons.Filled.Check else Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (inMyList) "Saved" else "My List")
                    }
                    OutlinedButton(
                        onClick = { onToggleLike(item) },
                        modifier = Modifier
                            .size(54.dp),
                        shape = RoundedCornerShape(17.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (liked) AliflixRed.copy(alpha = 0.72f)
                            else Color.White.copy(alpha = 0.16f),
                        ),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(
                            if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (liked) "Unlike" else "Like",
                            tint = if (liked) AliflixRed else Color.White,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Text(
                    text = "ABOUT",
                    color = AliflixRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    text = item.overview.ifBlank {
                        "An English plot summary is not available from the catalogue yet."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 23.sp,
                    color = Color.White.copy(alpha = 0.88f),
                )
                if (item.genres.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(item.genres.take(4)) { genre ->
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(AliflixSurfaceRaised)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(genre, color = AliflixIce, fontSize = 11.sp)
                            }
                        }
                    }
                }
                if (item.cast.isNotEmpty()) {
                    Text(
                        text = "Starring  ${item.cast.joinToString()}",
                        color = AliflixMuted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.error != null) {
                    Text(
                        text = "Some details could not be refreshed.",
                        color = AliflixMuted,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        if (item.type == MediaType.TV && !state.loading) {
            item {
                Column(
                    modifier = Modifier.padding(top = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "WATCH THE STORY",
                        color = AliflixRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    Text(
                        text = "Episodes",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.seasons, key = { it.number }) { season ->
                            val selected = season.number == state.selectedSeason
                            AssistChip(
                                onClick = { onSelectSeason(season.number) },
                                label = {
                                    Text(
                                        buildString {
                                            append("Season ")
                                            append(season.number)
                                            if (season.episodeCount > 0) {
                                                append("  ·  ")
                                                append(season.episodeCount)
                                            }
                                        },
                                    )
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selected) AliflixRed else AliflixSurfaceRaised,
                                    labelColor = Color.White,
                                ),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = if (selected) AliflixRed
                                    else Color.White.copy(alpha = 0.10f),
                                ),
                            )
                        }
                    }
                }
            }

            if (state.episodesLoading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AliflixRed)
                    }
                }
            } else if (state.episodes.isEmpty()) {
                item {
                    Text(
                        text = "Episode information is unavailable for this season.",
                        color = AliflixMuted,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                    )
                }
            } else {
                items(
                    items = state.episodes,
                    key = { episode -> "${episode.seasonNumber}:${episode.number}" },
                ) { episode ->
                    EpisodeRow(
                        episode = episode,
                        show = item,
                        onPlay = { onPlayEpisode(item, episode) },
                    )
                }
            }
        }

        if (state.loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = AliflixRed)
                }
            }
        } else if (state.recommendations.isNotEmpty()) {
            item {
                MediaRail(
                    rail = ContentRail("More Like This", state.recommendations),
                    onOpen = onOpen,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun RatingsRow(item: Media) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RatingPill(
            source = "IMDb",
            value = item.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) }
                ?: "Not rated",
            accent = Color(0xFFF5C518),
            darkText = true,
        )
        RatingPill(
            source = "Tomatometer",
            value = item.rottenTomatoesRating?.let { "$it%" } ?: "Not rated",
            accent = Color(0xFFFA3A45),
            darkText = false,
        )
    }
}

@Composable
private fun RatingPill(
    source: String,
    value: String,
    accent: Color,
    darkText: Boolean,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AliflixSurfaceRaised)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .background(accent)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Text(
                text = source,
                color = if (darkText) Color.Black else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Text(
            text = value,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    show: Media,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AliflixSurfaceRaised)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .clickable(onClick = onPlay)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(AliflixSurface),
            ) {
            ArtworkPlaceholder(title = episode.title)
            AsyncImage(
                model = episode.stillUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.90f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.Black,
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Text(
                    text = episode.number.toString().padStart(2, '0'),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "S${episode.seasonNumber} · E${episode.number}",
                    color = AliflixRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                )
                if (episode.runtime.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = episode.runtime,
                        color = AliflixMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            Text(
                text = episode.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp,
            )
            Text(
                text = episode.overview.ifBlank {
                    "English episode summary is not available yet."
                },
                color = Color.White.copy(alpha = 0.66f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("SHOW RATINGS  ·  IMDb ")
                    append(
                        show.imdbRating?.let {
                            String.format(java.util.Locale.US, "%.1f", it)
                        } ?: "Not rated",
                    )
                    append("  ·  RT ")
                    append(show.rottenTomatoesRating?.let { "$it%" } ?: "Not rated")
                },
                color = AliflixMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.25.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ArtworkPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF24151D), Color(0xFF10141C)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: "A",
                color = AliflixRed.copy(alpha = 0.78f),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "ALIFLIX",
                color = Color.White.copy(alpha = 0.36f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
            )
        }
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    val animation = rememberInfiniteTransition(label = "launch")
    val pulse by animation.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo-pulse",
    )
    val glow by animation.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo-glow",
    )
    val progress by animation.animateFloat(
        initialValue = 0.08f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "launch-progress",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF35101C),
                        Color(0xFF110C12),
                        AliflixBlack,
                    ),
                    radius = 1_050f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(pulse)
                .alpha(glow)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(AliflixRed.copy(alpha = 0.45f), Color.Transparent),
                    ),
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .shadow(30.dp, RoundedCornerShape(28.dp), ambientColor = AliflixRed)
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFFFF3157), AliflixRed, Color(0xFF9F0929)),
                        ),
                    )
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(28.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "A",
                    color = Color.White,
                    fontSize = 58.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "ALIFLIX",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 5.5.sp,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "CURATING YOUR SPACE",
                color = AliflixMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.8.sp,
            )
            Spacer(Modifier.height(27.dp))
            Box(
                modifier = Modifier
                    .width(156.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFFFF6C83), AliflixRed),
                            ),
                        ),
                )
            }
        }
        Text(
            text = "MOVIES  •  SERIES  •  STORIES",
            color = Color.White.copy(alpha = 0.32f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 26.dp),
        )
    }
}

@Composable
private fun ConfigurationError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "ALIFLIX",
            color = AliflixRed,
            fontSize = 36.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Catalogue unavailable",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            color = AliflixMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onRetry) {
            Text("Try Again")
        }
    }
}

@Composable
private fun EmptyMessage(
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AliflixMuted,
            textAlign = TextAlign.Center,
        )
    }
}
