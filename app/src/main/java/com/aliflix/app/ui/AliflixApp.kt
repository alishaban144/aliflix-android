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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.aliflix.app.AliflixViewModel
import com.aliflix.app.DetailUiState
import com.aliflix.app.GenreUiState
import com.aliflix.app.HomeUiState
import com.aliflix.app.SearchMode
import com.aliflix.app.SearchUiState
import com.aliflix.app.data.RamoflixConfig
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.player.WebPlayerScreen
import com.aliflix.app.recommendation.ConstraintRelaxation
import com.aliflix.app.recommendation.PersonalMatch
import com.aliflix.app.recommendation.PersonalizationEngine
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationPreferences
import com.aliflix.app.recommendation.RecommendationQuestion
import com.aliflix.app.recommendation.RecommendationQuestionType
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.recommendation.SemanticModelState
import com.aliflix.app.update.AppUpdateManager
import com.aliflix.app.update.InstallLaunchResult
import com.aliflix.app.update.UpdateCheckResult
import com.aliflix.app.update.UpdateInfo
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixAccentPrimary as AliflixRed
import com.aliflix.app.ui.theme.AliflixAccentSecondary as AliflixIce
import com.aliflix.app.ui.theme.AliflixBackgroundBase as AliflixBlack
import com.aliflix.app.ui.theme.AliflixBackgroundImmersive
import com.aliflix.app.ui.theme.AliflixBorderStrong
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixEditorialWarm
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSuccess as AliflixGreen
import com.aliflix.app.ui.theme.AliflixContentSecondary as AliflixMuted
import com.aliflix.app.ui.theme.AliflixScrimStrong
import com.aliflix.app.ui.theme.AliflixSurfacePrimary as AliflixSurface
import com.aliflix.app.ui.theme.AliflixSurfaceElevated as AliflixSurfaceRaised
import com.aliflix.app.ui.theme.AliflixSurfacePressed
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import com.aliflix.app.ui.theme.AliflixTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class AppTab(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    MY_SPACE("My Space"),
}

private enum class AppScreen {
    HOME,
    SEARCH,
    MY_SPACE,
    DETAIL,
    GENRE_EXPLORE,
}

internal sealed interface MobileDestination {
    data class Root(val tab: AppTab) : MobileDestination

    data class Detail(val item: Media) : MobileDestination

    data class Genre(
        val name: String,
        val mediaType: MediaType,
        val firstVisibleItemIndex: Int = 0,
        val firstVisibleItemScrollOffset: Int = 0,
    ) : MobileDestination
}

internal fun popMobileDestinationStack(
    destinations: List<MobileDestination>,
): List<MobileDestination> =
    if (destinations.size <= 1) destinations else destinations.dropLast(1)

private val MobileDestinationStackSaver = Saver<List<MobileDestination>, String>(
    save = { destinations ->
        JSONArray().apply {
            destinations.forEach { destination ->
                put(
                    when (destination) {
                        is MobileDestination.Root -> JSONObject()
                            .put("kind", "root")
                            .put("tab", destination.tab.name)
                        is MobileDestination.Detail -> JSONObject()
                            .put("kind", "detail")
                            .put("item", destination.item.toJson())
                        is MobileDestination.Genre -> JSONObject()
                            .put("kind", "genre")
                            .put("name", destination.name)
                            .put("mediaType", destination.mediaType.routeName)
                            .put("firstVisibleItemIndex", destination.firstVisibleItemIndex)
                            .put(
                                "firstVisibleItemScrollOffset",
                                destination.firstVisibleItemScrollOffset,
                            )
                    },
                )
            }
        }.toString()
    },
    restore = { encoded ->
        runCatching {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.getJSONObject(index)
                    when (value.getString("kind")) {
                        "root" -> add(
                            MobileDestination.Root(
                                AppTab.entries.firstOrNull {
                                    it.name == value.optString("tab")
                                } ?: AppTab.HOME,
                            ),
                        )
                        "detail" -> add(
                            MobileDestination.Detail(
                                Media.fromJson(value.getJSONObject("item")),
                            ),
                        )
                        "genre" -> add(
                            MobileDestination.Genre(
                                name = value.getString("name"),
                                mediaType = MediaType.from(value.optString("mediaType")),
                                firstVisibleItemIndex =
                                    value.optInt("firstVisibleItemIndex").coerceAtLeast(0),
                                firstVisibleItemScrollOffset =
                                    value.optInt("firstVisibleItemScrollOffset").coerceAtLeast(0),
                            ),
                        )
                    }
                }
            }.let { restored ->
                if (restored.firstOrNull() is MobileDestination.Root) {
                    restored
                } else {
                    listOf(MobileDestination.Root(AppTab.HOME))
                }
            }
        }.getOrDefault(listOf(MobileDestination.Root(AppTab.HOME)))
    },
)

private enum class HomeFilter(val label: String) {
    FOR_YOU("For You"),
    MOVIES("Movies"),
    TV("TV Shows"),
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
    val genre by viewModel.genre.collectAsState()
    val myList by viewModel.myList.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val likes by viewModel.likes.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val aiRecommendationsEnabled by viewModel.aiRecommendationsEnabled.collectAsState()
    val semanticModelState by viewModel.semanticModelState.collectAsState()
    val shouldOfferSemanticModel by viewModel.shouldOfferSemanticModel.collectAsState()

    val playbackPreferences by viewModel.playbackPreferences.collectAsState()
    val ramoflixConfig = playbackPreferences.ramoflixConfig
    val moviepireBaseUrl = playbackPreferences.moviepireBaseUrl
    val dorabyBaseUrl = playbackPreferences.dorabyBaseUrl
    val activity = LocalActivity.current as ComponentActivity
    val updateManager = remember(activity) { AppUpdateManager(activity) }
    val updateScope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf(MobileUpdateUiState()) }
    var urlDialogProvider by remember { mutableStateOf<PlaybackProviderId?>(null) }
    var detailProviderName by rememberSaveable { mutableStateOf<String?>(null) }
    var destinationStack by rememberSaveable(
        stateSaver = MobileDestinationStackSaver,
    ) {
        mutableStateOf<List<MobileDestination>>(
            listOf(MobileDestination.Root(AppTab.HOME)),
        )
    }
    val currentDestination = destinationStack.last()
    val currentDestinationKey = when (currentDestination) {
        is MobileDestination.Root ->
            "${destinationStack.size}:root:${currentDestination.tab.name}"
        is MobileDestination.Detail ->
            "${destinationStack.size}:detail:${currentDestination.item.key}"
        is MobileDestination.Genre ->
            "${destinationStack.size}:genre:${currentDestination.mediaType.name}:" +
                currentDestination.name
    }
    val selectedTab = (destinationStack.first() as MobileDestination.Root).tab
    var playerSelection by remember { mutableStateOf<PlaybackSelection?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    val homeScrollState = rememberLazyListState()
    val searchScrollState = rememberLazyGridState()
    val listScrollState = rememberLazyGridState()
    val favoritesScrollState = rememberLazyGridState()
    val historyScrollState = rememberLazyGridState()
    val genreScrollState = rememberLazyGridState()
    var homeFilterName by rememberSaveable { mutableStateOf(HomeFilter.FOR_YOU.name) }
    var searchMediaFilter by rememberSaveable { mutableStateOf("All") }
    var libraryPage by rememberSaveable { mutableIntStateOf(0) }
    val launchVisible =
        currentDestination is MobileDestination.Root &&
            selectedTab == AppTab.HOME &&
            home.loading &&
            home.content == null
    val requestedDetailProvider = PlaybackProviderId.fromStoredValue(detailProviderName)
    val detailProvider = requestedDetailProvider?.takeIf { provider ->
        detail.item?.let(provider::isAvailableFor) == true
    } ?: playbackPreferences.safeGeneralProvider
    val detailInMyList = detail.item?.let { item ->
        myList.any { saved -> saved.key == item.key }
    } == true
    val detailLiked = detail.item?.let { item ->
        likes.any { saved -> saved.key == item.key }
    } == true

    LaunchedEffect(detail.item?.key) {
        detailProviderName = null
    }

    fun showRoot(tab: AppTab) {
        destinationStack = listOf(MobileDestination.Root(tab))
        viewModel.closeDetails()
        viewModel.closeGenre()
    }

    fun openDetails(item: Media) {
        destinationStack = destinationStack + MobileDestination.Detail(item)
        viewModel.openDetails(item)
    }

    fun captureGenreScrollPosition() {
        val destination = destinationStack.lastOrNull() as? MobileDestination.Genre ?: return
        destinationStack = destinationStack.dropLast(1) + destination.copy(
            firstVisibleItemIndex = genreScrollState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = genreScrollState.firstVisibleItemScrollOffset,
        )
    }

    fun openGenreFromDetails(
        genreName: String,
        mediaType: MediaType,
    ) {
        destinationStack = destinationStack + MobileDestination.Genre(
            name = genreName,
            mediaType = mediaType,
        )
        viewModel.openGenre(genreName, mediaType)
        viewModel.closeDetails()
    }

    fun popDestination() {
        if (destinationStack.size <= 1) return
        destinationStack = popMobileDestinationStack(destinationStack)
        when (val destination = destinationStack.last()) {
            is MobileDestination.Root -> {
                viewModel.closeDetails()
                viewModel.closeGenre()
            }
            is MobileDestination.Detail -> {
                viewModel.closeGenre()
                viewModel.openDetails(destination.item)
            }
            is MobileDestination.Genre -> {
                viewModel.closeDetails()
                if (
                    genre.genre != destination.name ||
                    genre.type != destination.mediaType ||
                    (genre.items.isEmpty() && !genre.loading)
                ) {
                    viewModel.openGenre(destination.name, destination.mediaType)
                }
            }
        }
    }

    LaunchedEffect(currentDestinationKey) {
        when (val destination = currentDestination) {
            is MobileDestination.Root -> Unit
            is MobileDestination.Detail -> {
                if (detail.item?.key != destination.item.key) {
                    viewModel.openDetails(destination.item)
                }
            }
            is MobileDestination.Genre -> {
                if (genre.genre != destination.name || genre.type != destination.mediaType) {
                    viewModel.openGenre(destination.name, destination.mediaType)
                }
                if (
                    genreScrollState.firstVisibleItemIndex !=
                    destination.firstVisibleItemIndex ||
                    genreScrollState.firstVisibleItemScrollOffset !=
                    destination.firstVisibleItemScrollOffset
                ) {
                    genreScrollState.scrollToItem(
                        index = destination.firstVisibleItemIndex,
                        scrollOffset = destination.firstVisibleItemScrollOffset,
                    )
                }
                snapshotFlow {
                    genreScrollState.firstVisibleItemIndex to
                        genreScrollState.firstVisibleItemScrollOffset
                }
                    .distinctUntilChanged()
                    .collect { (index, offset) ->
                        val current =
                            destinationStack.lastOrNull() as? MobileDestination.Genre
                                ?: return@collect
                        if (
                            current.name == destination.name &&
                            current.mediaType == destination.mediaType &&
                            (
                                current.firstVisibleItemIndex != index ||
                                    current.firstVisibleItemScrollOffset != offset
                                )
                        ) {
                            destinationStack = destinationStack.dropLast(1) +
                                current.copy(
                                    firstVisibleItemIndex = index,
                                    firstVisibleItemScrollOffset = offset,
                                )
                        }
                    }
            }
        }
    }

    fun playSelection(
        selection: PlaybackSelection,
        requestedProvider: PlaybackProviderId? = null,
    ) {
        viewModel.markPlayed(selection.media)
        playerSelection = selection.copy(
            source = playbackPreferences.sourceFor(
                media = selection.media,
                requestedProvider = requestedProvider,
            ),
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

    BackHandler(enabled = destinationStack.size > 1 && !playerVisible) {
        if (currentDestination is MobileDestination.Genre) {
            captureGenreScrollPosition()
        }
        popDestination()
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
                if (currentDestination is MobileDestination.Root && !launchVisible) {
                    AliflixBottomBar(
                        selected = selectedTab,
                        onSelect = ::showRoot,
                    )
                }
            },
        ) { padding ->
            val screen = when (currentDestination) {
                is MobileDestination.Detail -> AppScreen.DETAIL
                is MobileDestination.Genre -> AppScreen.GENRE_EXPLORE
                is MobileDestination.Root -> when (selectedTab) {
                    AppTab.HOME -> AppScreen.HOME
                    AppTab.SEARCH -> AppScreen.SEARCH
                    AppTab.MY_SPACE -> AppScreen.MY_SPACE
                }
            }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val enterSpec = tween<IntOffset>(500, easing = FastOutSlowInEasing)
                    val fadeEnterSpec = tween<Float>(500, easing = FastOutSlowInEasing)
                    val exitSpec = tween<IntOffset>(420, easing = FastOutSlowInEasing)
                    val fadeExitSpec = tween<Float>(360, easing = FastOutSlowInEasing)
                    when {
                        targetState == AppScreen.DETAIL || targetState == AppScreen.GENRE_EXPLORE -> {
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
                        initialState == AppScreen.DETAIL || initialState == AppScreen.GENRE_EXPLORE -> {
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
                    AppScreen.GENRE_EXPLORE -> {
                        val destination =
                            currentDestination as? MobileDestination.Genre ?: return@AnimatedContent
                        GenreExploreScreen(
                            genreName = destination.name,
                            mediaType = destination.mediaType,
                            state = genre,
                            onRetry = viewModel::retryGenre,
                            onBack = {
                                captureGenreScrollPosition()
                                popDestination()
                            },
                            onOpen = { item ->
                                captureGenreScrollPosition()
                                openDetails(item)
                            },
                            gridState = genreScrollState,
                            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                        )
                    }

                    AppScreen.DETAIL -> DetailScreen(
                        state = detail,
                        inMyList = detailInMyList,
                        liked = detailLiked,
                        onBack = ::popDestination,
                        onPlay = { item ->
                            playSelection(
                                PlaybackSelection(item),
                                requestedProvider = detailProvider,
                            )
                        },
                        onPlayEpisode = { item, episode ->
                            playSelection(
                                PlaybackSelection(
                                    media = item,
                                    seasonNumber = episode.seasonNumber,
                                    episodeNumber = episode.number,
                                    episodeTitle = episode.title,
                                ),
                                requestedProvider = detailProvider,
                            )
                        },
                        onSelectSeason = viewModel::selectSeason,
                        onToggleMyList = viewModel::toggleMyList,
                        onToggleLike = viewModel::toggleLike,
                        onOpen = ::openDetails,
                        selectedProvider = detailProvider,
                        onSelectProvider = { provider ->
                            detailProviderName = provider.name
                        },
                        personalMatch = detail.item?.let {
                            PersonalizationEngine.match(it, likes)
                        },
                        onOpenGenre = ::openGenreFromDetails,
                    )

                    AppScreen.HOME -> HomeScreen(
                        state = home,
                        recent = recent,
                        likes = likes,
                        onRetry = viewModel::refreshHome,
                        onOpen = ::openDetails,
                        onPlay = ::playMedia,
                        onSearch = { showRoot(AppTab.SEARCH) },
                        listState = homeScrollState,
                        selectedFilter = HomeFilter.entries.firstOrNull { filter ->
                            filter.name == homeFilterName
                        } ?: HomeFilter.FOR_YOU,
                        onSelectFilter = { homeFilterName = it.name },
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

                    AppScreen.SEARCH -> SearchScreen(
                        state = search,
                        recommendationState = recommendation,
                        aiEnabled = aiRecommendationsEnabled,
                        semanticModelState = semanticModelState,
                        shouldOfferSemanticModel = shouldOfferSemanticModel,
                        onQueryChange = viewModel::updateSearch,
                        onModeChange = viewModel::selectSearchMode,
                        onOpen = ::openDetails,
                        onSelectRecommendationType = viewModel::selectRecommendationType,
                        onSubmitRecommendation = viewModel::submitRecommendationText,
                        onSurpriseRecommendation = viewModel::surpriseRecommendation,
                        onAnswerRecommendation = viewModel::answerRecommendation,
                        onShowRecommendationMatches = viewModel::showRecommendationMatches,
                        onPreviousRecommendationStep = viewModel::previousRecommendationStep,
                        onRestartRecommendations = viewModel::restartRecommendations,
                        onRetryRecommendations = viewModel::retryRecommendations,
                        onLoadMoreRecommendations = viewModel::loadMoreRecommendations,
                        onRetryRecommendationPage = viewModel::retryRecommendationPage,
                        onRelaxRecommendation = viewModel::relaxRecommendationConstraint,
                        onDownloadSemanticModel = viewModel::downloadSemanticModel,
                        onDismissSemanticModelOffer = viewModel::dismissSemanticModelOffer,
                        onMoreLikeRecommendation = viewModel::moreLikeRecommendation,
                        onLessLikeRecommendation = viewModel::lessLikeRecommendation,
                        onRecommendationSeen = viewModel::markRecommendationSeen,
                        onCorrectRecommendationPreference =
                            viewModel::correctRecommendationPreference,
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
                        aiRecommendationsEnabled = aiRecommendationsEnabled,
                        onSetAiRecommendationsEnabled =
                            viewModel::setAiRecommendationsEnabled,
                        onResetRecommendationTaste =
                            viewModel::resetRecommendationTaste,
                        semanticModelState = semanticModelState,
                        onDownloadSemanticModel = viewModel::downloadSemanticModel,
                        onDeleteSemanticModel = viewModel::deleteSemanticModel,
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
                AliflixTheme {
                    WebPlayerScreen(
                        selection = selection,
                        visible = playerVisible,
                        controller = playerController,
                        onClose = { playerVisible = false },
                    )
                }
            }
        }

        urlDialogProvider?.let { provider ->
            val currentUrl = when (provider) {
                PlaybackProviderId.RAMOFLIX -> ramoflixConfig.baseUrl
                PlaybackProviderId.MOVIEPIRE -> moviepireBaseUrl
                PlaybackProviderId.DORABY -> dorabyBaseUrl
            }
            MobileProviderUrlDialog(
                providerName = provider.displayName,
                description = "Only change this if the ${provider.displayName} website moves.",
                currentUrl = currentUrl,
                defaultUrl = provider.defaultBaseUrl,
                onSave = { newUrl ->
                    when (provider) {
                        PlaybackProviderId.RAMOFLIX -> viewModel.updateRamoflixUrl(newUrl)
                        PlaybackProviderId.MOVIEPIRE -> viewModel.updateMoviepireUrl(newUrl)
                        PlaybackProviderId.DORABY -> viewModel.updateDorabyUrl(newUrl)
                    }
                    urlDialogProvider = null
                },
                onReset = {
                    when (provider) {
                        PlaybackProviderId.RAMOFLIX -> viewModel.resetRamoflixUrl()
                        PlaybackProviderId.MOVIEPIRE -> viewModel.resetMoviepireUrl()
                        PlaybackProviderId.DORABY -> viewModel.resetDorabyUrl()
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        shape = RoundedCornerShape(24.dp),
        color = AliflixSurfaceRaised.copy(alpha = 0.98f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
        shadowElevation = 18.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 5.dp),
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
                        .height(56.dp)
                        .scale(if (pressed) 0.96f else 1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isSelected) {
                                Brush.horizontalGradient(
                                    listOf(
                                        AliflixAccentPrimary.copy(alpha = 0.28f),
                                        AliflixAccentSecondary.copy(alpha = 0.10f),
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .selectable(
                            selected = isSelected,
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
                        tint = if (isSelected) AliflixContentPrimary else AliflixContentTertiary,
                        modifier = Modifier.size(23.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        color = if (isSelected) AliflixContentPrimary else AliflixContentTertiary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (isSelected) {
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(20.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AliflixAccentPrimary, AliflixAccentSecondary),
                                    ),
                                ),
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
    listState: LazyListState,
    selectedFilter: HomeFilter,
    onSelectFilter: (HomeFilter) -> Unit,
    modifier: Modifier,
) {
    val filtersPinned by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val filteredRails = remember(content, selectedFilter, likes) {
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
    }
        ?: filteredRails.firstNotNullOfOrNull { rail -> rail.items.firstOrNull() }
        ?: content.hero

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBlack),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box {
                AnimatedContent(
                    targetState = hero,
                    contentKey = Media::key,
                    transitionSpec = {
                        val enterMotion = tween<IntOffset>(
                            durationMillis = 440,
                            easing = FastOutSlowInEasing,
                        )
                        val enterFade = tween<Float>(
                            durationMillis = 400,
                            easing = FastOutSlowInEasing,
                        )
                        val exitMotion = tween<IntOffset>(
                            durationMillis = 300,
                            easing = FastOutSlowInEasing,
                        )
                        val exitFade = tween<Float>(
                            durationMillis = 260,
                            easing = FastOutSlowInEasing,
                        )
                        (
                            fadeIn(enterFade) +
                                slideInHorizontally(enterMotion) { fullWidth -> fullWidth / 24 } +
                                scaleIn(enterFade, initialScale = 0.992f)
                            ).togetherWith(
                            fadeOut(exitFade) +
                                slideOutHorizontally(exitMotion) { fullWidth -> -fullWidth / 32 } +
                                scaleOut(exitFade, targetScale = 0.996f),
                        )
                    },
                    label = "home-featured-transition",
                ) { featured ->
                    HeroBanner(
                        item = featured,
                        personalMatch = PersonalizationEngine.match(featured, likes),
                        onPlay = { onPlay(featured) },
                        onInfo = { onOpen(featured) },
                    )
                }
                HomeHeader(
                    onSearch = onSearch,
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
            HomeMediaRail(
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        AliflixBlack.copy(alpha = 0.94f),
                        AliflixBlack.copy(alpha = 0.54f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 2.dp),
        ) {
            AliflixLogoMark(
                modifier = Modifier
                    .width(42.dp)
                    .height(38.dp),
            )
            Spacer(Modifier.width(9.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "ALIFLIX",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.3.sp,
                )
                Text(
                    text = "CINEMA, CURATED",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp,
                )
            }
        }
        IconButton(
            onClick = onSearch,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    CircleShape,
                ),
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

@Composable
private fun AliflixLogoMark(
    modifier: Modifier = Modifier,
) {
    val primary = AliflixAccentPrimary
    val highlight = AliflixAccentSecondary
    Canvas(modifier = modifier) {
        val unit = minOf(size.width, size.height)
        val left = (size.width - unit) / 2f
        val top = (size.height - unit) / 2f
        fun point(x: Float, y: Float) = Offset(
            x = left + unit * x,
            y = top + unit * y,
        )

        drawCircle(
            color = highlight,
            radius = unit * 0.115f,
            center = point(0.25f, 0.66f),
        )

        val shadowBlade = Path().apply {
            moveTo(point(0.59f, 0.19f).x, point(0.59f, 0.19f).y)
            lineTo(point(0.80f, 0.84f).x, point(0.80f, 0.84f).y)
            lineTo(point(0.68f, 0.84f).x, point(0.68f, 0.84f).y)
            lineTo(point(0.56f, 0.47f).x, point(0.56f, 0.47f).y)
            close()
        }
        drawPath(path = shadowBlade, color = primary)

        val lightBlade = Path().apply {
            moveTo(point(0.43f, 0.19f).x, point(0.43f, 0.19f).y)
            lineTo(point(0.59f, 0.19f).x, point(0.59f, 0.19f).y)
            lineTo(point(0.68f, 0.84f).x, point(0.68f, 0.84f).y)
            lineTo(point(0.55f, 0.84f).x, point(0.55f, 0.84f).y)
            close()
        }
        drawPath(
            path = lightBlade,
            brush = Brush.linearGradient(
                colors = listOf(AliflixContentPrimary, highlight),
                start = point(0.43f, 0.19f),
                end = point(0.68f, 0.84f),
            ),
        )
    }
}

@Composable
private fun HeroBanner(
    item: Media,
    personalMatch: PersonalMatch?,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val accessibilityExpansion = (
        (fontScale - 1f).coerceAtLeast(0f) * 170f
    ).coerceAtMost(320f).dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(556.dp + accessibilityExpansion),
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
                            0f to AliflixBlack.copy(alpha = 0.20f),
                            0.42f to Color.Transparent,
                            0.70f to AliflixBlack.copy(alpha = 0.36f),
                            1f to AliflixBlack,
                        ),
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            0f to AliflixBlack.copy(alpha = 0.74f),
                            0.70f to AliflixBlack.copy(alpha = 0.06f),
                        ),
                    )
                },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(26.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Text(
                    text = "ALIFLIX FEATURED",
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.45.sp,
                )
            }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (personalMatch != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.46f),
                                RoundedCornerShape(7.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "${personalMatch.score}% match",
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (item.rating > 0.0) {
                    Text(
                        text = String.format(
                            java.util.Locale.US,
                            "%.1f rated",
                            item.rating,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
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
                    shape = RoundedCornerShape(14.dp),
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
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.82f),
                    ),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    modifier = Modifier.heightIn(min = 50.dp),
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
private fun FilterBar(
    selected: HomeFilter,
    onSelect: (HomeFilter) -> Unit,
    pinned: Boolean,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        AliflixBlack.copy(alpha = 0.99f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    ),
                ),
            )
            .then(
                if (pinned) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                },
            ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(HomeFilter.entries) { filter ->
            val active = filter == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                        },
                    )
                    .border(
                        1.dp,
                        if (active) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.76f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.46f)
                        },
                        RoundedCornerShape(14.dp),
                    )
                    .selectable(
                        selected = active,
                        onClick = { onSelect(filter) },
                    )
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text(
                    text = filter.label,
                    color = if (active) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun HomeMediaRail(
    rail: ContentRail,
    onOpen: (Media) -> Unit,
    compact: Boolean,
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (trending) 10.dp else 12.dp),
        ) {
            itemsIndexed(rail.items, key = { _, item -> item.key }) { index, item ->
                when {
                    trending -> HomePosterCard(
                        item = item,
                        width = if (compact) 112.dp else 130.dp,
                        rank = index + 1,
                        onClick = { onOpen(item) },
                    )
                    editorialLandscape -> HomeLandscapeCard(
                        item = item,
                        compact = compact,
                        onClick = { onOpen(item) },
                    )
                    else -> HomePosterCard(
                        item = item,
                        width = if (compact) 112.dp else 128.dp,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePosterCard(
    item: Media,
    width: androidx.compose.ui.unit.Dp,
    rank: Int? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val posterScale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(14.dp, RoundedCornerShape(15.dp))
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        1.dp,
                        if (pressed) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.68f)
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                        },
                        RoundedCornerShape(15.dp),
                    ),
            ) {
                ArtworkPlaceholder(title = item.title)
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = "${item.title} poster",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.58f to Color.Transparent,
                                1f to AliflixBlack.copy(alpha = 0.78f),
                            ),
                        ),
                )
                if (item.rating > 0.0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(AliflixBlack.copy(alpha = 0.78f))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "%.1f",
                                item.rating,
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (rank != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .heightIn(min = 30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(AliflixBackgroundImmersive.copy(alpha = 0.92f))
                            .border(
                                1.dp,
                                AliflixAccentSecondary.copy(alpha = 0.48f),
                                RoundedCornerShape(9.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 5.dp),
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
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "home-landscape-press",
    )

    Box(
        modifier = Modifier
            .width(if (compact) 210.dp else 238.dp)
            .aspectRatio(1.68f)
            .scale(cardScale)
            .shadow(12.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                if (pressed) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                },
                RoundedCornerShape(16.dp),
            )
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
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
private fun HomeSectionHeader(
    title: String,
    eyebrow: String? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (eyebrow != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Text(
                    text = eyebrow,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.45.sp,
                )
            }
        }
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
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val posterScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "poster-press",
    )
    Column(
        modifier = modifier
            .width(width)
            .scale(posterScale)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(width)
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
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(AliflixRed, Color(0xFFC6071E)),
                            ),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "#$rank",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
        Text(
            text = item.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(34.dp),
        )
        if (item.year.isNotBlank()) {
            Text(
                text = item.year,
                color = AliflixMuted,
                fontSize = 11.sp,
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
        modifier = Modifier.padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HomeSectionHeader(title = "Recently Played", eyebrow = "RETURN TO")
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items, key = { it.key }) { item ->
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val cardScale by animateFloatAsState(
                    targetValue = if (pressed) 0.97f else 1f,
                    animationSpec = tween(110),
                    label = "recent-card-press",
                )
                Box(
                    modifier = Modifier
                        .width(236.dp)
                        .aspectRatio(1.68f)
                        .scale(cardScale)
                        .shadow(12.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            1.dp,
                            if (pressed) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.66f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)
                            },
                            RoundedCornerShape(16.dp),
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onOpen(item) },
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
                                    0.20f to Color.Transparent,
                                    1f to AliflixBlack.copy(alpha = 0.95f),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "RECENT",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.0.sp,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = item.title,
                            color = MaterialTheme.colorScheme.onBackground,
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
    recommendationState: RecommendationUiState,
    aiEnabled: Boolean,
    semanticModelState: SemanticModelState,
    shouldOfferSemanticModel: Boolean,
    onQueryChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onOpen: (Media) -> Unit,
    onSelectRecommendationType: (RecommendationMediaKind) -> Unit,
    onSubmitRecommendation: (String) -> Unit,
    onSurpriseRecommendation: () -> Unit,
    onAnswerRecommendation: (RecommendationQuestion, List<String>) -> Unit,
    onShowRecommendationMatches: () -> Unit,
    onPreviousRecommendationStep: () -> Unit,
    onRestartRecommendations: () -> Unit,
    onRetryRecommendations: () -> Unit,
    onLoadMoreRecommendations: () -> Unit,
    onRetryRecommendationPage: () -> Unit,
    onRelaxRecommendation: (String) -> Unit,
    onDownloadSemanticModel: () -> Unit,
    onDismissSemanticModelOffer: () -> Unit,
    onMoreLikeRecommendation: (Media) -> Unit,
    onLessLikeRecommendation: (Media) -> Unit,
    onRecommendationSeen: (Media) -> Unit,
    onCorrectRecommendationPreference: (String) -> Unit,
    gridState: LazyGridState,
    mediaFilter: String,
    onMediaFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val plotMode = state.mode == SearchMode.PLOT
    val aiMode = state.mode == SearchMode.AI
    val coroutineScope = rememberCoroutineScope()
    val searchModes = remember(aiEnabled) {
        if (aiEnabled) {
            listOf(SearchMode.TITLE, SearchMode.PLOT, SearchMode.AI)
        } else {
            listOf(SearchMode.TITLE, SearchMode.PLOT)
        }
    }
    val pagerState = rememberPagerState(
        initialPage = searchModes.indexOf(state.mode).coerceAtLeast(0),
        pageCount = { searchModes.size },
    )
    var queryValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length),
            ),
        )
    }

    LaunchedEffect(pagerState.currentPage) {
        val targetMode = searchModes.getOrElse(pagerState.currentPage) {
            SearchMode.TITLE
        }
        if (targetMode != state.mode) {
            onModeChange(targetMode)
        }
    }

    LaunchedEffect(state.mode, searchModes) {
        val targetPage = searchModes.indexOf(state.mode).coerceAtLeast(0)
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(state.query) {
        if (state.query != queryValue.text) {
            queryValue = TextFieldValue(
                text = state.query,
                selection = TextRange(state.query.length),
            )
        }
    }
    LaunchedEffect(state.query, state.mode, mediaFilter) {
        gridState.scrollToItem(0)
    }
    val visibleResults = remember(state.results, mediaFilter) {
        when {
            mediaFilter == "Movies" -> state.results.filter { it.type == MediaType.MOVIE }
            mediaFilter == "Series" -> state.results.filter { it.type == MediaType.TV }
            else -> state.results
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to AliflixBackgroundImmersive,
                    0.36f to AliflixBlack,
                    1f to AliflixBlack,
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 6.dp),
    ) {
        Text(
            text = "DISCOVER",
            color = AliflixAccentSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
        )
        Text(
            text = when {
                aiMode -> "What should I watch?"
                plotMode -> "Find it from the story"
                else -> "Search movies & series"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = AliflixContentPrimary,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(AliflixSurface)
                .border(
                    1.dp,
                    AliflixBorderSubtle,
                    RoundedCornerShape(16.dp),
                )
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            searchModes.map { mode ->
                mode to when (mode) {
                    SearchMode.TITLE -> "Title"
                    SearchMode.PLOT -> "Describe"
                    SearchMode.AI -> "AI"
                }
            }.forEachIndexed { index, (mode, label) ->
                val selected = state.mode == mode
                val tabColor by animateColorAsState(
                    targetValue = if (selected) {
                        AliflixSurfacePressed
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    label = "search-mode-color",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("search-mode-${mode.name.lowercase()}")
                        .height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tabColor)
                        .border(
                            width = 1.dp,
                            color = if (selected) {
                                AliflixAccentPrimary.copy(alpha = 0.62f)
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                        ),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (mode == SearchMode.PLOT || mode == SearchMode.AI) {
                        Spacer(Modifier.width(5.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        AliflixAccentPrimary.copy(alpha = 0.34f)
                                    } else {
                                        AliflixAccentPrimary.copy(alpha = 0.18f)
                                    },
                                )
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "BETA",
                                color = if (selected) {
                                    AliflixContentPrimary
                                } else {
                                    AliflixAccentSecondary
                                },
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                            )
                        }
                    }
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .testTag("search-mode-pager"),
            userScrollEnabled = true,
        ) { page ->
            val pageMode = searchModes.getOrElse(page) { SearchMode.TITLE }
            if (pageMode == SearchMode.AI) {
                AliflixAiScreen(
                    state = recommendationState,
                    semanticModelState = semanticModelState,
                    shouldOfferSemanticModel = shouldOfferSemanticModel,
                    onSelectType = onSelectRecommendationType,
                    onSubmit = onSubmitRecommendation,
                    onSurprise = onSurpriseRecommendation,
                    onAnswer = onAnswerRecommendation,
                    onShowMatches = onShowRecommendationMatches,
                    onBack = onPreviousRecommendationStep,
                    onRestart = onRestartRecommendations,
                    onRetry = onRetryRecommendations,
                    onCancel = {
                        onRestartRecommendations()
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                    onDetails = onOpen,
                    onLoadMore = onLoadMoreRecommendations,
                    onRetryPage = onRetryRecommendationPage,
                    onRelax = onRelaxRecommendation,
                    onDownloadSemanticModel = onDownloadSemanticModel,
                    onDismissSemanticModelOffer = onDismissSemanticModelOffer,
                    onMoreLike = onMoreLikeRecommendation,
                    onLessLike = onLessLikeRecommendation,
                    onAlreadySeen = onRecommendationSeen,
                    onCorrectPreference = onCorrectRecommendationPreference,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val pagePlotMode = pageMode == SearchMode.PLOT
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    OutlinedTextField(
                        value = queryValue,
                        onValueChange = { updated ->
                            queryValue = updated
                            onQueryChange(updated.text)
                        },
                        placeholder = {
                            Text(
                                text = if (pagePlotMode) {
                                    "Describe the movie or show..."
                                } else {
                                    "Title, year, or keyword"
                                },
                                color = AliflixContentTertiary,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                        },
                        leadingIcon = if (pagePlotMode) {
                            null
                        } else {
                            {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = AliflixContentSecondary,
                                )
                            }
                        },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        queryValue = TextFieldValue("")
                                        onQueryChange("")
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Clear search",
                                    )
                                }
                            }
                        },
                        singleLine = !pagePlotMode,
                        minLines = if (pagePlotMode) 4 else 1,
                        maxLines = if (pagePlotMode) 6 else 1,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = AliflixContentPrimary,
                            lineHeight = 22.sp,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AliflixSurfaceRaised,
                            unfocusedContainerColor = AliflixSurfaceSecondary,
                            focusedBorderColor = AliflixAccentPrimary.copy(alpha = 0.80f),
                            unfocusedBorderColor = AliflixBorderSubtle,
                            cursorColor = AliflixAccentSecondary,
                            focusedTextColor = AliflixContentPrimary,
                            unfocusedTextColor = AliflixContentPrimary,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (pagePlotMode) 132.dp else 56.dp),
                    )
                }

                when {
                    state.loading -> SearchStatusPanel(
                        title = if (pagePlotMode) {
                            "Comparing your clues"
                        } else {
                            "Searching the catalogue"
                        },
                        message = if (pagePlotMode) {
                            "Checking possible movie and series titles..."
                        } else {
                            "Finding the strongest title matches..."
                        },
                        loading = true,
                        modifier = Modifier.weight(1f),
                    )
                    state.error != null -> SearchStatusPanel(
                        title = "Search unavailable",
                        message = state.error,
                        modifier = Modifier.weight(1f),
                    )
                    state.query.isBlank() -> SearchStatusPanel(
                        title = if (pagePlotMode) {
                            "Describe the story"
                        } else {
                            "What do you want to watch?"
                        },
                        message = "",
                        modifier = Modifier.weight(1f),
                    )
                    state.results.isEmpty() -> SearchStatusPanel(
                        title = if (pagePlotMode) {
                            "No confident estimate"
                        } else {
                            "No matching titles"
                        },
                        message = "",
                        modifier = Modifier.weight(1f),
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("All", "Movies", "Series").forEach { option ->
                                val active = mediaFilter == option
                                Box(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (active) {
                                                AliflixAccentPrimary.copy(alpha = 0.20f)
                                            } else {
                                                AliflixSurfaceSecondary
                                            },
                                        )
                                        .border(
                                            1.dp,
                                            if (active) {
                                                AliflixAccentPrimary.copy(alpha = 0.68f)
                                            } else {
                                                AliflixBorderSubtle
                                            },
                                            RoundedCornerShape(16.dp),
                                        )
                                        .selectable(
                                            selected = active,
                                            onClick = { onMediaFilterChange(option) },
                                        )
                                        .padding(horizontal = 15.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = option,
                                        color = if (active) Color.White else AliflixMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${visibleResults.size} ${if (pagePlotMode) "estimates" else "results"}",
                                color = AliflixMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                            )
                        }
                        if (visibleResults.isEmpty()) {
                            EmptyMessage(
                                title = "No $mediaFilter here",
                                message = "Switch the filter to see the other matching titles.",
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(118.dp),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 2.dp,
                                    bottom = 32.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                items(visibleResults, key = { it.key }) { item ->
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
                }
            }
        }
    }
}

@Composable
private fun AliflixAiScreen(
    state: RecommendationUiState,
    semanticModelState: SemanticModelState,
    shouldOfferSemanticModel: Boolean,
    onSelectType: (RecommendationMediaKind) -> Unit,
    onSubmit: (String) -> Unit,
    onSurprise: () -> Unit,
    onAnswer: (RecommendationQuestion, List<String>) -> Unit,
    onShowMatches: () -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDetails: (Media) -> Unit,
    onLoadMore: () -> Unit,
    onRetryPage: () -> Unit,
    onRelax: (String) -> Unit,
    onDownloadSemanticModel: () -> Unit,
    onDismissSemanticModelOffer: () -> Unit,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onAlreadySeen: (Media) -> Unit,
    onCorrectPreference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var selectedQuestionId by rememberSaveable { mutableStateOf("") }
    var selectedOptions by rememberSaveable { mutableStateOf(setOf<String>()) }
    var adjustVisible by remember { mutableStateOf(false) }
    val typeState = state as? RecommendationUiState.SelectType
    val statePreferences = when (state) {
        RecommendationUiState.Idle -> null
        is RecommendationUiState.SelectType -> state.preferences
        is RecommendationUiState.Discovering -> state.preferences
        is RecommendationUiState.Question -> state.preferences
        is RecommendationUiState.Results -> state.preferences
        is RecommendationUiState.Empty -> state.preferences
        is RecommendationUiState.SourceUnavailable -> state.preferences
        is RecommendationUiState.Relaxation -> state.preferences
        is RecommendationUiState.Error -> state.preferences
    }
    val selectedType = when (statePreferences?.contentType?.value) {
        com.aliflix.app.recommendation.RecommendationContentType.MOVIE ->
            RecommendationMediaKind.MOVIE
        com.aliflix.app.recommendation.RecommendationContentType.TV ->
            RecommendationMediaKind.SERIES
        else -> null
    }
    val questionId = (state as? RecommendationUiState.Question)?.question?.id

    LaunchedEffect(questionId) {
        if (questionId != null && selectedQuestionId != questionId) {
            selectedQuestionId = questionId
            selectedOptions = emptySet()
        }
    }

    Column(
        modifier = modifier,
    ) {
        if (typeState != null) {
            AiTypeSelector(
                selected = selectedType,
                onSelect = onSelectType,
            )
        }
        if (shouldOfferSemanticModel || semanticModelState is SemanticModelState.Downloading) {
            SemanticModelOffer(
                state = semanticModelState,
                onDownload = onDownloadSemanticModel,
                onDismiss = onDismissSemanticModelOffer,
            )
        }
        if (state == RecommendationUiState.Idle || selectedType != null) {
            AiRequestComposer(
                value = input,
                onValueChange = { input = it },
                onSubmit = {
                    input.trim().takeIf(String::isNotBlank)?.let { request ->
                        onSubmit(request)
                        input = ""
                    }
                },
            )
        }

        when (state) {
            RecommendationUiState.Idle -> AiIdleContent(
                onSubmit = onSubmit,
                onSurprise = onSurprise,
                modifier = Modifier.weight(1f),
            )
            is RecommendationUiState.SelectType -> {
                if (selectedType == null) {
                    AiTypePrompt(modifier = Modifier.weight(1f))
                } else {
                    AiIdleContent(
                        onSubmit = onSubmit,
                        onSurprise = onSurprise,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is RecommendationUiState.Discovering -> AiDiscoveringContent(
                preferences = state.preferences,
                onRestart = onRestart,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            is RecommendationUiState.Question -> {
                AiQuestionContent(
                    state = state,
                    selectedOptions = selectedOptions,
                    onToggle = { value ->
                        if (state.question.type == RecommendationQuestionType.SINGLE_SELECT) {
                            onAnswer(state.question, listOf(value))
                        } else {
                            selectedOptions = if (value in selectedOptions) {
                                selectedOptions - value
                            } else {
                                selectedOptions + value
                            }
                        }
                    },
                    onContinue = {
                        if (selectedOptions.isNotEmpty()) {
                            onAnswer(state.question, selectedOptions.toList())
                        }
                    },
                    onShowMatches = onShowMatches,
                    onBack = onBack,
                    onRestart = onRestart,
                    onCancel = onCancel,
                    modifier = Modifier.weight(1f),
                )
            }
            is RecommendationUiState.Results -> AiResultsContent(
                state = state,
                onDetails = onDetails,
                onAnswer = onAnswer,
                onMoreLike = onMoreLike,
                onLessLike = onLessLike,
                onAlreadySeen = onAlreadySeen,
                onCorrectPreference = onCorrectPreference,
                onLoadMore = onLoadMore,
                onRetryPage = onRetryPage,
                onAdjust = { adjustVisible = true },
                onRestart = onRestart,
                modifier = Modifier.weight(1f),
            )
            is RecommendationUiState.Empty -> AiEmptyContent(
                message = state.message,
                options = state.options,
                onRelax = onRelax,
                onRestart = onRestart,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            is RecommendationUiState.SourceUnavailable -> AiSourceUnavailableContent(
                message = state.message,
                canRetry = state.canRetry,
                onRetry = onRetry,
                onRestart = onRestart,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            is RecommendationUiState.Relaxation -> AiRelaxationContent(
                state = state,
                onRelax = onRelax,
                onRestart = onRestart,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
            is RecommendationUiState.Error -> AiErrorContent(
                state = state,
                onRetry = onRetry,
                onRestart = onRestart,
                onCancel = onCancel,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (adjustVisible) {
        var adjustment by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adjustVisible = false },
            containerColor = AliflixSurfaceSecondary,
            title = { Text("Adjust preferences") },
            text = {
                OutlinedTextField(
                    value = adjustment,
                    onValueChange = { adjustment = it },
                    placeholder = { Text("For example: shorter and more recent") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = adjustment.isNotBlank(),
                    onClick = {
                        onSubmit(adjustment)
                        adjustVisible = false
                    },
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { adjustVisible = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SemanticModelOffer(
    state: SemanticModelState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AliflixSurfaceSecondary)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (state is SemanticModelState.Downloading) {
                    "Adding smarter local matching"
                } else {
                    "Improve nuanced matches on this device"
                },
                color = AliflixContentPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (state is SemanticModelState.Downloading) {
                    "${state.progressPercent}% downloaded"
                } else {
                    "Optional 6 MB Google language model. Your request stays private."
                },
                color = AliflixContentTertiary,
                fontSize = 10.sp,
            )
        }
        if (state is SemanticModelState.Downloading) {
            CircularProgressIndicator(
                progress = { state.progressPercent / 100f },
                color = AliflixAccentSecondary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp),
            )
        } else {
            TextButton(onClick = onDownload) {
                Text("Download")
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Not now",
                    tint = AliflixContentTertiary,
                )
            }
        }
    }
}

@Composable
private fun AiTypeSelector(
    selected: RecommendationMediaKind?,
    onSelect: (RecommendationMediaKind) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = "Choose what you want to watch",
            color = AliflixContentPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RecommendationMediaKind.entries.forEach { kind ->
                val active = selected == kind
                OutlinedButton(
                    onClick = { onSelect(kind) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .testTag("ai-type-${kind.name.lowercase()}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (active) {
                            AliflixAccentPrimary.copy(alpha = 0.22f)
                        } else {
                            AliflixSurfaceSecondary
                        },
                        contentColor = if (active) {
                            AliflixContentPrimary
                        } else {
                            AliflixContentSecondary
                        },
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (active) AliflixAccentPrimary else AliflixBorderSubtle,
                    ),
                ) {
                    if (active) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(
                        text = if (kind == RecommendationMediaKind.MOVIE) {
                            "Movie"
                        } else {
                            "Series"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiRequestComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "Tell me what you're in the mood for",
                    color = AliflixContentTertiary,
                    fontSize = 13.sp,
                )
            },
            minLines = 1,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AliflixSurfaceRaised,
                unfocusedContainerColor = AliflixSurfaceSecondary,
                focusedBorderColor = AliflixAccentPrimary,
                unfocusedBorderColor = AliflixBorderSubtle,
                cursorColor = AliflixAccentSecondary,
                focusedTextColor = AliflixContentPrimary,
                unfocusedTextColor = AliflixContentPrimary,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("ai-recommendation-input")
                .heightIn(min = 56.dp),
        )
        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank(),
            modifier = Modifier
                .size(56.dp)
                .testTag("ai-recommendation-submit"),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AliflixAccentPrimary,
                disabledContainerColor = AliflixSurfacePressed,
            ),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "Find recommendations",
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun AiTypePrompt(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Movie or series?",
            color = AliflixContentPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose one to start.",
            color = AliflixContentSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AiIdleContent(
    onSubmit: (String) -> Unit,
    onSurprise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AliflixAccentPrimary.copy(alpha = 0.24f),
                                AliflixSurfaceSecondary,
                            ),
                        ),
                    )
                    .border(1.dp, AliflixBorderStrong, RoundedCornerShape(24.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "ALIFLIX AI · BETA",
                    color = AliflixAccentSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    "Tell me a little. I'll figure out the useful questions.",
                    color = AliflixContentPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 27.sp,
                )
            }
        }
        item {
            Text(
                "Try one",
                color = AliflixContentPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                aiStarterPrompts.forEach { prompt ->
                    AssistChip(
                        onClick = { onSubmit(prompt) },
                        label = { Text(prompt) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = AliflixSurfaceSecondary,
                            labelColor = AliflixContentSecondary,
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = AliflixBorderSubtle,
                        ),
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onSurprise,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    AliflixAccentPrimary.copy(alpha = 0.62f),
                ),
            ) {
                Text("Surprise me", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AiDiscoveringContent(
    preferences: RecommendationPreferences,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 10.dp,
            bottom = 34.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai-results-loading"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Finding matches",
                        color = AliflixContentPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    CircularProgressIndicator(
                        color = AliflixAccentSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
                AiPreferenceSummary(preferences)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRestart) { Text("Restart") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
        items(count = 9, key = { "ai-initial-skeleton:$it" }) {
            AiPosterSkeleton()
        }
    }
}

@Composable
private fun AiQuestionContent(
    state: RecommendationUiState.Question,
    selectedOptions: Set<String>,
    onToggle: (String) -> Unit,
    onContinue: () -> Unit,
    onShowMatches: () -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                state.progressMessage,
                color = AliflixAccentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        item { AiPreferenceSummary(state.preferences) }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(AliflixSurfaceSecondary)
                    .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(22.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    state.question.text,
                    color = AliflixContentPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 27.sp,
                )
                state.question.supportingText?.let {
                    Text(it, color = AliflixContentSecondary, fontSize = 13.sp)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.question.options.forEach { option ->
                        val selected = option.value in selectedOptions
                        AssistChip(
                            onClick = { onToggle(option.value) },
                            label = { Text(option.label) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) {
                                    AliflixAccentPrimary.copy(alpha = 0.30f)
                                } else {
                                    AliflixSurfaceRaised
                                },
                                labelColor = AliflixContentPrimary,
                                leadingIconContentColor = AliflixAccentSecondary,
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = if (selected) {
                                    AliflixAccentPrimary
                                } else {
                                    AliflixBorderSubtle
                                },
                            ),
                        )
                    }
                }
                if (state.question.type == RecommendationQuestionType.MULTI_SELECT) {
                    Button(
                        onClick = onContinue,
                        enabled = selectedOptions.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text("Continue", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onShowMatches,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("ai-show-matches"),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text("Show matches", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack, enabled = state.canGoBack) { Text("Back") }
                Row {
                    TextButton(onClick = onRestart) { Text("Restart") }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun AiResultsContent(
    state: RecommendationUiState.Results,
    onDetails: (Media) -> Unit,
    onAnswer: (RecommendationQuestion, List<String>) -> Unit,
    onMoreLike: (Media) -> Unit,
    onLessLike: (Media) -> Unit,
    onAlreadySeen: (Media) -> Unit,
    onCorrectPreference: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetryPage: () -> Unit,
    onAdjust: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    var feedbackItem by remember { mutableStateOf<Media?>(null) }
    val candidates = remember(state.candidates) {
        state.candidates.distinctBy { it.media.key }
    }
    val nearEnd by remember(gridState, candidates.size) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            candidates.isNotEmpty() && lastVisible >= candidates.lastIndex - 6
        }
    }

    LaunchedEffect(
        nearEnd,
        state.hasMore,
        state.loadingMore,
        state.pageError,
        candidates.size,
    ) {
        if (
            nearEnd &&
            state.hasMore &&
            !state.loadingMore &&
            state.pageError == null
        ) {
            onLoadMore()
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(118.dp),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 34.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai-results-grid"),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Matches",
                        color = AliflixContentPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    if (state.refreshing) {
                        CircularProgressIndicator(
                            color = AliflixAccentSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(20.dp)
                                .testTag("ai-results-refreshing"),
                        )
                    }
                }
                AiPreferenceSummary(
                    preferences = state.preferences,
                    onRemove = onCorrectPreference,
                )
                state.refinementQuestion?.let { question ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(AliflixSurfaceSecondary)
                            .border(
                                1.dp,
                                AliflixBorderSubtle,
                                RoundedCornerShape(18.dp),
                            )
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(
                            text = question.text,
                            color = AliflixContentPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            question.options.forEach { option ->
                                AssistChip(
                                    onClick = {
                                        onAnswer(question, listOf(option.value))
                                    },
                                    label = { Text(option.label) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = AliflixSurfaceRaised,
                                        labelColor = AliflixContentSecondary,
                                    ),
                                    border = AssistChipDefaults.assistChipBorder(
                                        enabled = true,
                                        borderColor = AliflixBorderSubtle,
                                    ),
                                )
                            }
                            AssistChip(
                                onClick = { onAnswer(question, listOf("any")) },
                                label = { Text("Doesn't matter") },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = AliflixSurfaceRaised,
                                    labelColor = AliflixContentSecondary,
                                ),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = AliflixBorderSubtle,
                                ),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onAdjust,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text("Adjust preferences", maxLines = 1)
                    }
                    TextButton(
                        onClick = onRestart,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Restart")
                    }
                }
            }
        }
        if (candidates.isEmpty() && state.refreshing) {
            items(count = 9, key = { "ai-skeleton:$it" }) {
                AiPosterSkeleton()
            }
        }
        items(candidates, key = { "ai:${it.media.key}" }) { candidate ->
            MediaPoster(
                item = candidate.media,
                width = 118.dp,
                onClick = { onDetails(candidate.media) },
                onLongClick = { feedbackItem = candidate.media },
                modifier = Modifier.testTag("ai-result-${candidate.media.key}"),
            )
        }
        if (state.loadingMore) {
            item(
                key = "ai-loading-more",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = AliflixAccentSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Loading more",
                        color = AliflixContentSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        state.pageError?.let { message ->
            item(
                key = "ai-page-error",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = message,
                        color = AliflixContentSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = onRetryPage,
                        modifier = Modifier.testTag("ai-retry-page"),
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }

    feedbackItem?.let { item ->
        AlertDialog(
            onDismissRequest = { feedbackItem = null },
            containerColor = AliflixSurfaceSecondary,
            title = {
                Text(
                    text = item.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            feedbackItem = null
                            onMoreLike(item)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("More like this") }
                    TextButton(
                        onClick = {
                            feedbackItem = null
                            onLessLike(item)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Less like this") }
                    TextButton(
                        onClick = {
                            feedbackItem = null
                            onAlreadySeen(item)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Already seen") }
                }
            },
            confirmButton = {
                TextButton(onClick = { feedbackItem = null }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun AiPosterSkeleton() {
    Column(
        modifier = Modifier.width(118.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(14.dp))
                .background(AliflixSurfaceSecondary)
                .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(14.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(10.dp)
                .clip(CircleShape)
                .background(AliflixSurfacePressed),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.48f)
                .height(8.dp)
                .clip(CircleShape)
                .background(AliflixSurfaceSecondary),
        )
    }
}

@Composable
private fun AiEmptyContent(
    message: String,
    options: List<ConstraintRelaxation>,
    onRelax: (String) -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "No matches with every filter",
                color = AliflixContentPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = message,
                color = AliflixContentSecondary,
                lineHeight = 19.sp,
            )
        }
        items(options, key = { it.id }) { option ->
            OutlinedButton(
                onClick = { onRelax(option.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = option.label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestart) { Text("Restart") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AiSourceUnavailableContent(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Recommendations are temporarily unavailable",
            color = AliflixContentPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AliflixContentSecondary,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) {
                Button(onClick = onRetry) { Text("Try again") }
            }
            OutlinedButton(onClick = onRestart) { Text("Restart") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun AiRelaxationContent(
    state: RecommendationUiState.Relaxation,
    onRelax: (String) -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "No matches with every filter",
                color = AliflixContentPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.message,
                color = AliflixContentSecondary,
                lineHeight = 19.sp,
            )
        }
        items(state.options, key = { it.id }) { option ->
            OutlinedButton(
                onClick = { onRelax(option.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(option.label, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestart) { Text("Restart") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun AiErrorContent(
    state: RecommendationUiState.Error,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "I couldn't finish that lookup",
            color = AliflixContentPrimary,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            state.message,
            color = AliflixContentSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canRetry) {
                Button(onClick = onRetry) { Text("Retry") }
            }
            OutlinedButton(onClick = onRestart) { Text("Restart") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun AiPreferenceSummary(
    preferences: RecommendationPreferences,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null,
) {
    val chips = remember(preferences) { aiPreferenceChips(preferences) }
    if (chips.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        contentPadding = PaddingValues(horizontal = 1.dp),
    ) {
        items(
            items = chips,
            key = { "${it.key}:${it.label}" },
        ) { chip ->
            if (onRemove != null && chip.key != null) {
                AssistChip(
                    onClick = { onRemove(chip.key) },
                    label = {
                        Text(
                            chip.label,
                            maxLines = 1,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove ${chip.label} preference",
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = AliflixAccentPrimary.copy(alpha = 0.16f),
                        labelColor = AliflixContentSecondary,
                        trailingIconContentColor = AliflixContentTertiary,
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = AliflixAccentPrimary.copy(alpha = 0.32f),
                    ),
                )
            } else {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AliflixAccentPrimary.copy(alpha = 0.16f))
                        .border(
                            1.dp,
                            AliflixAccentPrimary.copy(alpha = 0.32f),
                            CircleShape,
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        chip.label,
                        color = AliflixContentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private data class AiPreferenceChip(
    val key: String?,
    val label: String,
)

private fun aiPreferenceChips(
    preferences: RecommendationPreferences,
): List<AiPreferenceChip> = buildList {
    preferences.contentType?.value?.let {
        add(
            AiPreferenceChip(
                key = null,
                label = when (it) {
                    com.aliflix.app.recommendation.RecommendationContentType.MOVIE -> "Movies"
                    com.aliflix.app.recommendation.RecommendationContentType.TV -> "Series"
                    com.aliflix.app.recommendation.RecommendationContentType.EITHER ->
                        "Movies or series"
                },
            ),
        )
    }
    addAll(
        preferences.moods.map {
            AiPreferenceChip("mood:${it.value.name}", it.value.label)
        },
    )
    addAll(
        preferences.includedGenres.map {
            AiPreferenceChip("genre:${it.value}", it.value)
        },
    )
    addAll(
        preferences.semanticFacets.map {
            AiPreferenceChip("facet:${it.value.id}", it.value.label)
        },
    )
    addAll(
        preferences.excludedFacets.map {
            AiPreferenceChip(
                "excluded_facet:${it.value.id}",
                "No ${it.value.label.lowercase()}",
            )
        },
    )
    addAll(
        preferences.unmatchedPreferences
            .filterNot { it.negated }
            .map { AiPreferenceChip("unmatched:${it.text}", it.text) },
    )
    preferences.viewingContext?.let {
        add(AiPreferenceChip("context", it.value.label))
    }
    preferences.runtimeMaximumMinutes?.let {
        add(AiPreferenceChip("runtime_max", "Up to ${it.value} min"))
    }
    preferences.runtimeMinimumMinutes?.let {
        add(AiPreferenceChip("runtime_min", "At least ${it.value} min"))
    }
    preferences.yearMinimum?.let {
        add(AiPreferenceChip("year_min", "${it.value}+"))
    }
    preferences.yearMaximum?.let {
        add(AiPreferenceChip("year_max", "Through ${it.value}"))
    }
    preferences.minimumImdb?.let {
        add(AiPreferenceChip("imdb", "IMDb ${it.value}+"))
    }
    preferences.originalLanguage?.let {
        add(AiPreferenceChip("language", it.value))
    }
    preferences.requiredStatus?.let {
        add(AiPreferenceChip("status", it.value))
    }
    preferences.similarityTitle?.let {
        add(AiPreferenceChip("similarity", "Like ${it.value}"))
    }
}.distinctBy { it.key to it.label }.take(16)

private val aiStarterPrompts = listOf(
    "Something scary under 100 minutes",
    "Funny to watch with friends",
    "Mind-bending but not too long",
    "A strong hidden-gem series",
)

@Composable
private fun SearchStatusPanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(AliflixSurfaceSecondary)
                .border(
                    1.dp,
                    AliflixBorderSubtle,
                    RoundedCornerShape(22.dp),
                )
                .padding(horizontal = 24.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = AliflixAccentSecondary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AliflixAccentSecondary),
                )
            }
            Text(
                text = title,
                color = AliflixContentPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    color = AliflixContentSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PlaybackProviderSelector(
    selectedProvider: PlaybackProviderId,
    onSelectProvider: (PlaybackProviderId) -> Unit,
    modifier: Modifier = Modifier,
    onEditProviderUrl: ((PlaybackProviderId) -> Unit)? = null,
) {
    val providers = PlaybackProviderId.entries.filter(PlaybackProviderId::supportsGeneralPlayback)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AliflixSurfaceSecondary)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "Streaming source",
                    color = AliflixContentPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Used when you activate Play",
                    color = AliflixContentTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(AliflixAccentPrimary.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = "SOURCE",
                    color = AliflixAccentSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
            }
        }

        providers.forEach { provider ->
            val selected = selectedProvider == provider
            val cardShape = RoundedCornerShape(14.dp)
            val cardBackground = if (selected) {
                Brush.horizontalGradient(
                    listOf(
                        AliflixAccentPrimary.copy(alpha = 0.24f),
                        AliflixAccentPrimary.copy(alpha = 0.08f),
                    ),
                )
            } else {
                Brush.horizontalGradient(
                    listOf(
                        AliflixSurfaceRaised,
                        AliflixSurfaceRaised.copy(alpha = 0.75f),
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .clip(cardShape)
                    .background(cardBackground)
                    .border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) {
                            AliflixAccentSecondary.copy(alpha = 0.72f)
                        } else {
                            AliflixBorderSubtle
                        },
                        shape = cardShape,
                    )
                    .selectable(
                        selected = selected,
                        onClick = { onSelectProvider(provider) },
                    )
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (selected) {
                                Brush.linearGradient(
                                    listOf(AliflixAccentPrimary, AliflixAccentSecondary),
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(AliflixSurfacePressed, AliflixSurfaceRaised),
                                )
                            },
                        )
                        .border(
                            1.dp,
                            if (selected) Color.White.copy(alpha = 0.20f) else AliflixBorderSubtle,
                            RoundedCornerShape(11.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = provider.displayName.take(1).uppercase(),
                        color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                    )
                }

                Spacer(Modifier.width(11.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            text = provider.displayName,
                            color = AliflixContentPrimary,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (provider.isBeta) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) {
                                            Color.White.copy(alpha = 0.22f)
                                        } else {
                                            AliflixRed.copy(alpha = 0.22f)
                                        },
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "BETA",
                                    color = if (selected) Color.White else AliflixRed,
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.4.sp,
                                )
                            }
                        }
                    }
                    Text(
                        text = if (selected) "Selected for playback" else "Tap to use this source",
                        color = if (selected) {
                            AliflixAccentSecondary
                        } else {
                            AliflixContentTertiary
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(AliflixAccentPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected provider",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                if (onEditProviderUrl != null) {
                    IconButton(
                        onClick = { onEditProviderUrl(provider) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit ${provider.displayName} URL",
                            tint = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ProviderUrlButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = 0.12f),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Edit,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = AliflixIce,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = AliflixIce,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MobileProviderUrlDialog(
    providerName: String,
    description: String,
    currentUrl: String,
    defaultUrl: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(currentUrl) { mutableStateOf(currentUrl) }
    val keyboard = LocalSoftwareKeyboardController.current
    val normalizedUrl = RamoflixConfig.normalizeBaseUrl(url)
    val invalidUrl = url.isNotBlank() && normalizedUrl == null
    val isCustomUrl = currentUrl.trimEnd('/') != defaultUrl.trimEnd('/')
    val saveUrl = {
        normalizedUrl?.let {
            keyboard?.hide()
            onSave(it)
        }
        Unit
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            color = AliflixSurfaceSecondary,
            contentColor = AliflixContentPrimary,
            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AliflixAccentPrimary.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = AliflixAccentSecondary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "$providerName address",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = description,
                            color = AliflixContentSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trimStart() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Website URL") },
                    placeholder = { Text(defaultUrl, maxLines = 1) },
                    singleLine = true,
                    isError = invalidUrl,
                    supportingText = if (invalidUrl) {
                        { Text("Enter a valid HTTPS website address.") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { saveUrl() }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isCustomUrl) {
                        TextButton(onClick = onReset) {
                            Text("Use default", color = AliflixMuted)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = saveUrl,
                        enabled = normalizedUrl != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
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
            .clip(RoundedCornerShape(16.dp))
            .background(AliflixSurfaceSecondary)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AliflixAccentPrimary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = AliflixAccentSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "App updates",
                color = AliflixContentPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.message.ifBlank {
                    "Check the Aliflix GitHub release for a newer version."
                },
                color = AliflixContentSecondary,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            state.busy -> CircularProgressIndicator(
                color = AliflixAccentSecondary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(25.dp),
            )
            state.downloadedApk != null -> Button(
                onClick = onInstall,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
            ) {
                Text("Install", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            state.available != null -> Button(
                onClick = onDownload,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
            ) {
                Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            else -> OutlinedButton(
                onClick = onCheck,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
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
private fun MobileSettingsDialog(
    generalProvider: PlaybackProviderId,
    onSelectProvider: (PlaybackProviderId) -> Unit,
    onEditProviderUrl: (PlaybackProviderId) -> Unit,
    aiRecommendationsEnabled: Boolean,
    onSetAiRecommendationsEnabled: (Boolean) -> Unit,
    onResetRecommendationTaste: () -> Unit,
    semanticModelState: SemanticModelState,
    onDownloadSemanticModel: () -> Unit,
    onDeleteSemanticModel: () -> Unit,
    updateUi: MobileUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onClearRecent: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showClearConfirmation by remember { mutableStateOf(false) }

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
                    Text("Clear all", color = AliflixError, fontWeight = FontWeight.Bold)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            color = AliflixSurface,
            contentColor = AliflixContentPrimary,
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
            tonalElevation = 10.dp,
            shadowElevation = 24.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AliflixAccentPrimary.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = AliflixAccentSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                            )
                            Text(
                                text = "Playback, providers & system",
                                fontSize = 11.sp,
                                color = AliflixMuted,
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .background(AliflixSurfaceRaised, CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close settings",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "PLAYBACK SOURCE",
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                    PlaybackProviderSelector(
                        selectedProvider = generalProvider,
                        onSelectProvider = onSelectProvider,
                        onEditProviderUrl = onEditProviderUrl,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "UPDATES & VERSION",
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                    MobileUpdatePanel(
                        state = updateUi,
                        onCheck = onCheckForUpdates,
                        onDownload = onDownloadUpdate,
                        onInstall = onInstallUpdate,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SEARCH ASSISTANT",
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(AliflixSurfaceSecondary)
                            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "What should I watch?",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "BETA",
                                        color = AliflixAccentSecondary,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.8.sp,
                                    )
                                }
                                Text(
                                    text = "Show the AI page in Search",
                                    color = AliflixMuted,
                                    fontSize = 10.sp,
                                )
                            }
                            Switch(
                                checked = aiRecommendationsEnabled,
                                onCheckedChange = onSetAiRecommendationsEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = AliflixAccentPrimary,
                                    uncheckedThumbColor = AliflixMuted,
                                    uncheckedTrackColor = AliflixSurfaceRaised,
                                    uncheckedBorderColor = AliflixBorderStrong,
                                ),
                            )
                        }
                        TextButton(
                            onClick = onResetRecommendationTaste,
                            modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text(
                                text = "Reset learned taste",
                                color = AliflixAccentSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Nuanced local matching",
                                    color = AliflixContentPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = when (semanticModelState) {
                                        SemanticModelState.Ready -> "On-device model ready"
                                        SemanticModelState.Unavailable -> "Optional 6 MB download"
                                        SemanticModelState.Corrupt -> "Downloaded model failed validation"
                                        is SemanticModelState.Downloading ->
                                            "${semanticModelState.progressPercent}% downloaded"
                                        is SemanticModelState.Failed ->
                                            "Download unavailable"
                                    },
                                    color = AliflixContentTertiary,
                                    fontSize = 10.sp,
                                )
                            }
                            TextButton(
                                onClick = if (semanticModelState is SemanticModelState.Ready) {
                                    onDeleteSemanticModel
                                } else {
                                    onDownloadSemanticModel
                                },
                                enabled =
                                    semanticModelState !is SemanticModelState.Downloading,
                            ) {
                                Text(
                                    if (semanticModelState is SemanticModelState.Ready) {
                                        "Remove"
                                    } else {
                                        "Download"
                                    },
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "DATA & HISTORY",
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(AliflixSurfaceSecondary)
                            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
                            .clickable { showClearConfirmation = true }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AliflixError.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteSweep,
                                    contentDescription = null,
                                    tint = AliflixError,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = "Clear watch history",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Removes items from history tab",
                                    color = AliflixMuted,
                                    fontSize = 10.sp,
                                )
                            }
                        }
                        Text(
                            text = "Clear",
                            color = AliflixError,
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
    aiRecommendationsEnabled: Boolean,
    onSetAiRecommendationsEnabled: (Boolean) -> Unit,
    onResetRecommendationTaste: () -> Unit,
    semanticModelState: SemanticModelState,
    onDownloadSemanticModel: () -> Unit,
    onDeleteSemanticModel: () -> Unit,
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
    var showSettingsWindow by rememberSaveable { mutableStateOf(false) }
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
                    Text("Clear all", color = AliflixError, fontWeight = FontWeight.Bold)
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

    if (showSettingsWindow) {
        MobileSettingsDialog(
            generalProvider = generalProvider,
            onSelectProvider = onSelectProvider,
            onEditProviderUrl = onEditProviderUrl,
            aiRecommendationsEnabled = aiRecommendationsEnabled,
            onSetAiRecommendationsEnabled = onSetAiRecommendationsEnabled,
            onResetRecommendationTaste = onResetRecommendationTaste,
            semanticModelState = semanticModelState,
            onDownloadSemanticModel = onDownloadSemanticModel,
            onDeleteSemanticModel = onDeleteSemanticModel,
            updateUi = updateUi,
            onCheckForUpdates = onCheckForUpdates,
            onDownloadUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate,
            onClearRecent = onClearRecent,
            onDismiss = { showSettingsWindow = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        AliflixAccentPrimary.copy(alpha = 0.16f),
                        AliflixBackgroundImmersive,
                        AliflixBlack,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "ALIFLIX",
                    color = AliflixAccentSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.7.sp,
                )
                Text(
                    text = "My Space",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            OutlinedButton(
                onClick = { showSettingsWindow = true },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    AliflixBorderStrong,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Open Settings",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Settings",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        val libraryTabs = listOf(
            "My List" to myList.size,
            "Favorites" to likes.size,
            "History" to recent.size,
        )
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AliflixSurfaceSecondary)
                .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            libraryTabs.forEachIndexed { index, (label, count) ->
                val selected = pagerState.currentPage == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) {
                                Brush.horizontalGradient(
                                    listOf(
                                        AliflixAccentPrimary.copy(alpha = 0.42f),
                                        AliflixAccentSecondary.copy(alpha = 0.12f),
                                    ),
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        )
                        .selectable(
                            selected = selected,
                            onClick = { onPageChange(index) },
                        )
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = label,
                        color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = count.toString(),
                        color = if (selected) {
                            AliflixAccentSecondary
                        } else {
                            AliflixContentTertiary
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

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
        columns = GridCells.Adaptive(132.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
                    width = 132.dp,
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
                    tint = AliflixError,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Clear all",
                    color = AliflixError,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(132.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(items, key = { "history:${it.key}" }) { item ->
                Box {
                    MediaPoster(
                        item = item,
                        width = 132.dp,
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
        targetValue = if (inMyList) AliflixAccentPrimary else AliflixSurfaceRaised,
        animationSpec = tween(260),
    )
    val borderColor by animateColorAsState(
        targetValue = if (inMyList) AliflixAccentSecondary else AliflixBorderStrong,
        animationSpec = tween(260),
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
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
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
        targetValue = if (liked) {
            AliflixEditorialWarm.copy(alpha = 0.22f)
        } else {
            AliflixSurfaceRaised
        },
        animationSpec = tween(260),
    )
    val borderColor by animateColorAsState(
        targetValue = if (liked) AliflixEditorialWarm else AliflixBorderStrong,
        animationSpec = tween(260),
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
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        contentPadding = PaddingValues(0.dp),
    ) {
        AnimatedContent(
            targetState = liked,
            transitionSpec = {
                (scaleIn() + fadeIn()).togetherWith(scaleOut() + fadeOut())
            },
            label = "fav-anim",
        ) { isLiked ->
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isLiked) "Unlike" else "Like",
                tint = if (isLiked) AliflixEditorialWarm else AliflixContentPrimary,
                modifier = Modifier.size(21.dp),
            )
        }
    }
}

@Composable
private fun GenreExploreScreen(
    genreName: String,
    mediaType: MediaType,
    state: GenreUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val items = remember(state.items, mediaType) {
        state.items.filter { it.type == mediaType }.distinctBy(Media::key)
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
            .background(
                Brush.verticalGradient(
                    0f to AliflixBackgroundImmersive,
                    0.32f to Color(0xFF0D121A),
                    1f to AliflixBlack,
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AliflixSurfaceSecondary)
                    .border(1.dp, AliflixBorderSubtle, CircleShape),
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
                            .border(
                                1.dp,
                                AliflixAccentPrimary.copy(alpha = 0.38f),
                                CircleShape,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
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
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 40.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
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
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AliflixAccentPrimary),
                        )
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
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                AliflixBorderStrong,
                            ),
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
            start = 16.dp,
            end = 16.dp,
            top = 10.dp,
            bottom = 40.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AliflixSurfaceSecondary)
                        .border(
                            1.dp,
                            AliflixBorderSubtle,
                            RoundedCornerShape(16.dp),
                        ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(AliflixSurfacePressed),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.48f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(AliflixSurfaceSecondary),
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
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private const val GENRE_EXPLORE_PAGE_SIZE = 20

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
    selectedProvider: PlaybackProviderId,
    onSelectProvider: (PlaybackProviderId) -> Unit,
    onOpenGenre: (String, MediaType) -> Unit = { _, _ -> },
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
                    .height(460.dp),
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
                                    0f to AliflixScrimStrong.copy(alpha = 0.34f),
                                    0.48f to Color.Transparent,
                                    1f to AliflixBlack,
                                ),
                            )
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to AliflixBackgroundImmersive.copy(alpha = 0.58f),
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
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AliflixScrimStrong)
                        .border(1.dp, AliflixBorderStrong, CircleShape),
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
                        text = if (item.type == MediaType.MOVIE) {
                            "ALIFLIX MOVIE"
                        } else {
                            "ALIFLIX SERIES"
                        },
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displayMedium,
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
                                .background(AliflixSurfaceSecondary)
                                .border(1.dp, AliflixBorderSubtle, CircleShape)
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Text(label, color = AliflixMuted, fontSize = 12.sp)
                        }
                    }
                }
                RatingsRow(item = item)
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
                            containerColor = AliflixAccentPrimary,
                            contentColor = AliflixContentPrimary,
                        ),
                        shape = RoundedCornerShape(16.dp),
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
                            } + " · " + selectedProvider.displayName,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AnimatedMyListButton(
                        inMyList = inMyList,
                        onClick = { onToggleMyList(item) },
                        modifier = Modifier
                            .height(54.dp)
                            .width(124.dp),
                    )
                    AnimatedFavoriteButton(
                        liked = liked,
                        onClick = { onToggleLike(item) },
                        modifier = Modifier.size(54.dp),
                    )
                }
                PlaybackProviderSelector(
                    selectedProvider = selectedProvider,
                    onSelectProvider = onSelectProvider,
                )
                Text(
                    text = "ABOUT",
                    color = AliflixEditorialWarm,
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
                    color = AliflixContentSecondary,
                )
                if (item.genres.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(item.genres.take(6)) { genre ->
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clip(CircleShape)
                                    .background(AliflixSurfaceSecondary)
                                    .border(1.dp, AliflixBorderStrong, CircleShape)
                                    .clickable { onOpenGenre(genre, item.type) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$genre  ›",
                                    color = AliflixAccentSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
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
                        color = AliflixEditorialWarm,
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
            value = when {
                item.imdbRating != null ->
                    String.format(java.util.Locale.US, "%.1f", item.imdbRating)
                item.imdbRatingState == RatingSourceState.NOT_RATED -> "Not rated"
                else -> "Unavailable"
            },
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
            .background(AliflixSurfaceSecondary)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(12.dp))
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
            .clip(RoundedCornerShape(16.dp))
            .background(AliflixSurfaceSecondary)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(12.dp))
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
                    .background(AliflixContentPrimary.copy(alpha = 0.94f)),
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
                    color = AliflixAccentSecondary,
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
                    listOf(AliflixSurfaceRaised, AliflixBackgroundImmersive),
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
                color = AliflixAccentSecondary.copy(alpha = 0.82f),
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
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo-pulse",
    )
    val glow by animation.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
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
                        AliflixAccentPrimary.copy(alpha = 0.22f),
                        AliflixBackgroundImmersive,
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
                            listOf(AliflixAccentPrimary.copy(alpha = 0.42f), Color.Transparent),
                        ),
                ),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AliflixLogoMark(
                modifier = Modifier
                    .width(132.dp)
                    .height(96.dp)
                    .scale(pulse),
            )
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
                text = "YOUR CINEMA, CURATED",
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
                                listOf(AliflixAccentSecondary, AliflixAccentPrimary),
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
            .background(
                Brush.radialGradient(
                    listOf(
                        AliflixAccentPrimary.copy(alpha = 0.15f),
                        AliflixBlack,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AliflixLogoMark(
            modifier = Modifier
                .width(84.dp)
                .height(68.dp),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Catalogue unavailable",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = message,
            color = AliflixContentSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 148.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Try again")
        }
    }
}

@Composable
private fun EmptyMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AliflixLogoMark(
            modifier = Modifier
                .width(64.dp)
                .height(50.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AliflixContentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
