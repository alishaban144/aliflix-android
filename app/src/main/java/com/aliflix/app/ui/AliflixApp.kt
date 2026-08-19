@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.aliflix.app.PersonUiState
import com.aliflix.app.data.RamoflixConfig
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.HomeContent
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaReview
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.RatingSourceState
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.mobileGeneralPlaybackProviders
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.player.WebPlayerScreen
import com.aliflix.app.recommendation.PersonalMatch
import com.aliflix.app.recommendation.PersonalizationEngine
import com.aliflix.app.recommendation.SemanticModelState
import com.aliflix.app.update.AppUpdateManager
import com.aliflix.app.update.InstallLaunchResult
import com.aliflix.app.update.UpdateCheckResult
import com.aliflix.app.update.UpdateInfo
import com.aliflix.app.ui.discover.DiscoverScreen
import com.aliflix.app.ui.discover.currentSessionSuggestionOrder
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
import java.util.Locale
import kotlin.math.absoluteValue
import com.aliflix.app.ui.common.MobileTopSafeArea
import com.aliflix.app.ui.common.aliflixScreenBackground

internal enum class AppTab(val label: String) {
    HOME("Home"),
    SEARCH("Discover"),
    MY_SPACE("My Space"),
}

private enum class AppScreen {
    HOME,
    SEARCH,
    MY_SPACE,
    DETAIL,
    GENRE_EXPLORE,
    PERSON,
}

internal enum class MobileNavigationMotion {
    PUSH,
    POP,
    TAB_FORWARD,
    TAB_BACKWARD,
    REPLACE,
}

internal fun mobileNavigationMotion(
    initialDepth: Int,
    targetDepth: Int,
    initialTab: AppTab,
    targetTab: AppTab,
): MobileNavigationMotion = when {
    targetDepth > initialDepth -> MobileNavigationMotion.PUSH
    targetDepth < initialDepth -> MobileNavigationMotion.POP
    targetTab.ordinal > initialTab.ordinal -> MobileNavigationMotion.TAB_FORWARD
    targetTab.ordinal < initialTab.ordinal -> MobileNavigationMotion.TAB_BACKWARD
    else -> MobileNavigationMotion.REPLACE
}

internal fun mobileAnimatedDetailState(
    targetItem: Media?,
    targetSaveKey: String,
    liveState: DetailUiState,
    snapshots: Map<String, DetailUiState>,
): DetailUiState {
    val snapshot = snapshots[targetSaveKey]
    return when {
        targetItem != null &&
            liveState.item?.key == targetItem.key &&
            (!liveState.loading || snapshot == null) -> liveState
        snapshot != null -> snapshot
        else -> DetailUiState(item = targetItem)
    }
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

    data class Person(
        val creator: MediaCreator,
        val firstVisibleItemIndex: Int = 0,
        val firstVisibleItemScrollOffset: Int = 0,
    ) : MobileDestination
}

private data class MobileAnimatedDestination(
    val screen: AppScreen,
    val saveKey: String,
    val stackDepth: Int,
    val rootTab: AppTab,
    val detailItem: Media? = null,
    val genreName: String? = null,
    val genreMediaType: MediaType? = null,
)

internal fun popMobileDestinationStack(
    destinations: List<MobileDestination>,
): List<MobileDestination> =
    if (destinations.size <= 1) destinations else destinations.dropLast(1)

internal fun shouldReturnHomeOnSystemBack(
    destinations: List<MobileDestination>,
): Boolean {
    val root = destinations.firstOrNull() as? MobileDestination.Root
    return destinations.size > 1 || root?.tab != AppTab.HOME
}

internal fun mobileDestinationSaveKey(
    destinations: List<MobileDestination>,
): String {
    val destination = destinations.last()
    return when (destination) {
        is MobileDestination.Root ->
            "${destinations.size}:root:${destination.tab.name}"
        is MobileDestination.Detail ->
            "${destinations.size}:detail:${destination.item.key}"
        is MobileDestination.Genre ->
            "${destinations.size}:genre:${destination.mediaType.name}:${destination.name}"
        is MobileDestination.Person ->
            "${destinations.size}:person:${destination.creator.tmdbId}"
    }
}

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
                        is MobileDestination.Person -> JSONObject()
                            .put("kind", "person")
                            .put("tmdbId", destination.creator.tmdbId)
                            .put("name", destination.creator.name)
                            .put("profilePath", destination.creator.profilePath)
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
                        "person" -> add(
                            MobileDestination.Person(
                                MediaCreator(
                                    tmdbId = value.getInt("tmdbId"),
                                    name = value.getString("name"),
                                    profilePath = value.optString("profilePath")
                                        .takeIf { it.isNotBlank() && it != "null" },
                                ),
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
    val person by viewModel.person.collectAsState()
    val myList by viewModel.myList.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val likes by viewModel.likes.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val aiRecommendationsEnabled by viewModel.aiRecommendationsEnabled.collectAsState()
    val semanticModelState by viewModel.semanticModelState.collectAsState()
    val shouldOfferSemanticModel by viewModel.shouldOfferSemanticModel.collectAsState()
    val askUiState by viewModel.askUiState.collectAsState()
    val askEditorState by viewModel.askEditorState.collectAsState()

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
    var detailStateSnapshots by remember {
        mutableStateOf<Map<String, DetailUiState>>(emptyMap())
    }
    val currentDestination = destinationStack.last()
    val currentDestinationKey = mobileDestinationSaveKey(destinationStack)
    val destinationStateHolder = rememberSaveableStateHolder()
    val selectedTab = (destinationStack.first() as MobileDestination.Root).tab
    var playerSelection by remember { mutableStateOf<PlaybackSelection?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    val homeScrollState = rememberLazyListState()
    val searchScrollState = rememberLazyGridState()
    val recommendationScrollState = rememberLazyListState()
    val discoverSuggestionOrder = remember { currentSessionSuggestionOrder() }
    val listScrollState = rememberLazyGridState()
    val favoritesScrollState = rememberLazyGridState()
    val historyScrollState = rememberLazyGridState()
    val genreScrollState = rememberLazyGridState()
    val personScrollState = rememberLazyGridState()
    var homeFilterName by rememberSaveable { mutableStateOf(HomeFilter.FOR_YOU.name) }
    var searchMediaFilter by rememberSaveable { mutableStateOf("All") }
    var discoverFocusRequestId by remember { mutableIntStateOf(0) }
    var consumedDiscoverFocusRequestId by remember { mutableIntStateOf(0) }
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
    LaunchedEffect(detail.item?.key) {
        detailProviderName = null
    }

    LaunchedEffect(currentDestinationKey, detail) {
        val destination = currentDestination as? MobileDestination.Detail
            ?: return@LaunchedEffect
        if (!detail.loading && detail.item?.key == destination.item.key) {
            detailStateSnapshots = detailStateSnapshots + (currentDestinationKey to detail)
        }
    }

    fun showRoot(tab: AppTab) {
        destinationStack = listOf(MobileDestination.Root(tab))
        viewModel.closeDetails()
        viewModel.closeGenre()
        viewModel.closePerson()
    }

    fun captureDetailStateSnapshot() {
        val destination = destinationStack.lastOrNull() as? MobileDestination.Detail ?: return
        if (
            detail.item?.key == destination.item.key &&
            (!detail.loading || currentDestinationKey !in detailStateSnapshots)
        ) {
            detailStateSnapshots = detailStateSnapshots + (currentDestinationKey to detail)
        }
    }

    fun openDetails(item: Media) {
        captureDetailStateSnapshot()
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
        captureDetailStateSnapshot()
        destinationStack = destinationStack + MobileDestination.Genre(
            name = genreName,
            mediaType = mediaType,
        )
        viewModel.openGenre(genreName, mediaType)
    }

    fun openCreatorFromDetails(creator: MediaCreator) {
        captureDetailStateSnapshot()
        destinationStack = destinationStack + MobileDestination.Person(creator)
        viewModel.openPerson(creator)
    }

    fun capturePersonScrollPosition() {
        val destination = destinationStack.lastOrNull() as? MobileDestination.Person ?: return
        destinationStack = destinationStack.dropLast(1) + destination.copy(
            firstVisibleItemIndex = personScrollState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = personScrollState.firstVisibleItemScrollOffset,
        )
    }

    fun popDestination() {
        if (destinationStack.size <= 1) return
        when (destinationStack.last()) {
            is MobileDestination.Person -> capturePersonScrollPosition()
            is MobileDestination.Genre -> captureGenreScrollPosition()
            is MobileDestination.Detail -> captureDetailStateSnapshot()
            is MobileDestination.Root -> Unit
        }
        destinationStack = popMobileDestinationStack(destinationStack)
        when (val destination = destinationStack.last()) {
            is MobileDestination.Root -> {
                viewModel.closeDetails()
                viewModel.closeGenre()
                viewModel.closePerson()
            }
            is MobileDestination.Detail -> {
                viewModel.openDetails(destination.item)
            }
            is MobileDestination.Genre -> {
                if (
                    genre.genre != destination.name ||
                    genre.type != destination.mediaType ||
                    (genre.items.isEmpty() && !genre.loading)
                ) {
                    viewModel.openGenre(destination.name, destination.mediaType)
                }
            }
            is MobileDestination.Person -> {
                if (person.creator?.tmdbId != destination.creator.tmdbId) {
                    viewModel.openPerson(destination.creator)
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
            is MobileDestination.Person -> {
                if (person.creator?.tmdbId != destination.creator.tmdbId) {
                    viewModel.openPerson(destination.creator)
                }
                snapshotFlow {
                    personScrollState.firstVisibleItemIndex to
                        personScrollState.firstVisibleItemScrollOffset
                }
                    .distinctUntilChanged()
                    .collect { (index, offset) ->
                        val current =
                            destinationStack.lastOrNull() as? MobileDestination.Person
                                ?: return@collect
                        if (
                            current.creator.tmdbId == destination.creator.tmdbId &&
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

    LaunchedEffect(
        (currentDestination as? MobileDestination.Person)?.creator?.tmdbId,
        person.items.size,
    ) {
        val destination = currentDestination as? MobileDestination.Person
            ?: return@LaunchedEffect
        if (person.items.isNotEmpty()) {
            val safeIndex = destination.firstVisibleItemIndex
                .coerceAtMost(person.items.lastIndex)
            if (
                personScrollState.firstVisibleItemIndex != safeIndex ||
                personScrollState.firstVisibleItemScrollOffset !=
                destination.firstVisibleItemScrollOffset
            ) {
                personScrollState.scrollToItem(
                    index = safeIndex,
                    scrollOffset = destination.firstVisibleItemScrollOffset,
                )
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

    BackHandler(
        enabled = shouldReturnHomeOnSystemBack(destinationStack) && !playerVisible,
    ) {
        if (destinationStack.size > 1) {
            popDestination()
        } else {
            showRoot(AppTab.HOME)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .aliflixScreenBackground()
            .semantics { testTagsAsResourceId = true },
    ) {
        Scaffold(
            containerColor = AliflixBlack,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (currentDestination is MobileDestination.Root && !launchVisible) {
                    AliflixBottomBar(
                        selected = selectedTab,
                        onSelect = { tab ->
                            if (tab == AppTab.SEARCH) {
                                discoverFocusRequestId += 1
                            }
                            showRoot(tab)
                        },
                    )
                }
            },
        ) { padding ->
            val screen = when (currentDestination) {
                is MobileDestination.Detail -> AppScreen.DETAIL
                is MobileDestination.Genre -> AppScreen.GENRE_EXPLORE
                is MobileDestination.Person -> AppScreen.PERSON
                is MobileDestination.Root -> when (selectedTab) {
                    AppTab.HOME -> AppScreen.HOME
                    AppTab.SEARCH -> AppScreen.SEARCH
                    AppTab.MY_SPACE -> AppScreen.MY_SPACE
                }
            }
            val animatedDestination = MobileAnimatedDestination(
                screen = screen,
                saveKey = currentDestinationKey,
                stackDepth = destinationStack.size,
                rootTab = selectedTab,
                detailItem = (currentDestination as? MobileDestination.Detail)?.item,
                genreName = (currentDestination as? MobileDestination.Genre)?.name,
                genreMediaType =
                    (currentDestination as? MobileDestination.Genre)?.mediaType,
            )
            AnimatedContent(
                targetState = animatedDestination,
                transitionSpec = {
                    val motion = mobileNavigationMotion(
                        initialDepth = initialState.stackDepth,
                        targetDepth = targetState.stackDepth,
                        initialTab = initialState.rootTab,
                        targetTab = targetState.rootTab,
                    )
                    val pageSlideSpec = tween<IntOffset>(
                        durationMillis = 360,
                        easing = FastOutSlowInEasing,
                    )
                    val pageFadeSpec = tween<Float>(
                        durationMillis = 300,
                        easing = FastOutSlowInEasing,
                    )
                    // Finite Compose animations inherit Android's animator duration scale,
                    // including an immediate next-frame finish when animations are disabled.
                    val tabSlideSpec = tween<IntOffset>(
                        durationMillis = 280,
                        easing = FastOutSlowInEasing,
                    )
                    val tabFadeSpec = tween<Float>(
                        durationMillis = 240,
                        easing = FastOutSlowInEasing,
                    )
                    when (motion) {
                        MobileNavigationMotion.PUSH -> {
                            (
                                fadeIn(pageFadeSpec) +
                                    slideInHorizontally(pageSlideSpec) { width -> width / 4 } +
                                    scaleIn(pageFadeSpec, initialScale = 0.985f)
                                ).togetherWith(
                                fadeOut(pageFadeSpec) +
                                    slideOutHorizontally(pageSlideSpec) { width -> -width / 12 } +
                                    scaleOut(pageFadeSpec, targetScale = 0.995f),
                            )
                        }
                        MobileNavigationMotion.POP -> {
                            (
                                fadeIn(pageFadeSpec) +
                                    slideInHorizontally(pageSlideSpec) { width -> -width / 12 } +
                                    scaleIn(pageFadeSpec, initialScale = 0.995f)
                                ).togetherWith(
                                fadeOut(pageFadeSpec) +
                                    slideOutHorizontally(pageSlideSpec) { width -> width / 4 } +
                                    scaleOut(pageFadeSpec, targetScale = 0.985f),
                            )
                        }
                        MobileNavigationMotion.TAB_FORWARD -> {
                            (
                                fadeIn(tabFadeSpec) +
                                    slideInHorizontally(tabSlideSpec) { width -> width / 10 }
                                ).togetherWith(
                                fadeOut(tabFadeSpec) +
                                    slideOutHorizontally(tabSlideSpec) { width -> -width / 10 },
                            )
                        }
                        MobileNavigationMotion.TAB_BACKWARD -> {
                            (
                                fadeIn(tabFadeSpec) +
                                    slideInHorizontally(tabSlideSpec) { width -> -width / 10 }
                                ).togetherWith(
                                fadeOut(tabFadeSpec) +
                                    slideOutHorizontally(tabSlideSpec) { width -> width / 10 },
                            )
                        }
                        MobileNavigationMotion.REPLACE ->
                            fadeIn(tabFadeSpec).togetherWith(fadeOut(tabFadeSpec))
                    }
                },
                label = "aliflix-screen",
            ) { targetDestination ->
                destinationStateHolder.SaveableStateProvider(targetDestination.saveKey) {
                    when (targetDestination.screen) {
                    AppScreen.PERSON -> PersonCreditsScreen(
                        state = person,
                        onRetry = viewModel::retryPerson,
                        onBack = {
                            capturePersonScrollPosition()
                            popDestination()
                        },
                        onOpen = { item ->
                            capturePersonScrollPosition()
                            openDetails(item)
                        },
                        gridState = personScrollState,
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

                    AppScreen.GENRE_EXPLORE -> {
                        val genreName = targetDestination.genreName
                        val mediaType = targetDestination.genreMediaType
                        if (genreName != null && mediaType != null) {
                            GenreExploreScreen(
                                genreName = genreName,
                                mediaType = mediaType,
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
                                modifier = Modifier.padding(
                                    bottom = padding.calculateBottomPadding(),
                                ),
                            )
                        }
                    }

                    AppScreen.DETAIL -> {
                        val targetItem = targetDestination.detailItem
                        val targetDetail = mobileAnimatedDetailState(
                            targetItem = targetItem,
                            targetSaveKey = targetDestination.saveKey,
                            liveState = detail,
                            snapshots = detailStateSnapshots,
                        )
                        val targetInMyList = targetItem?.let { item ->
                            myList.any { saved -> saved.key == item.key }
                        } == true
                        val targetLiked = targetItem?.let { item ->
                            likes.any { saved -> saved.key == item.key }
                        } == true
                        DetailScreen(
                            state = targetDetail,
                            inMyList = targetInMyList,
                            liked = targetLiked,
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
                            personalMatch = targetItem?.let {
                                PersonalizationEngine.match(it, likes)
                            },
                            onOpenGenre = ::openGenreFromDetails,
                            onOpenCreator = ::openCreatorFromDetails,
                        )
                    }

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

                    AppScreen.SEARCH -> DiscoverScreen(
                        state = search,
                        recommendationState = recommendation,
                        aiEnabled = aiRecommendationsEnabled,
                        homeContent = home.content,
                        recent = recent,
                        suggestionOrder = discoverSuggestionOrder,
                        semanticModelState = semanticModelState,
                        shouldOfferSemanticModel = shouldOfferSemanticModel,
                        focusRequestId = discoverFocusRequestId.takeIf {
                            it > consumedDiscoverFocusRequestId
                        },
                        onFocusRequestConsumed = { requestId ->
                            consumedDiscoverFocusRequestId = maxOf(
                                consumedDiscoverFocusRequestId,
                                requestId,
                            )
                        },
                        onQueryChange = viewModel::updateSearch,
                        onSubmitSearch = viewModel::submitCatalogueSearch,
                        onSearchTitles = viewModel::searchTitles,
                        onModeChange = viewModel::selectSearchMode,
                        onOpen = ::openDetails,
                        onSelectRecommendationType = viewModel::selectRecommendationType,
                        onSubmitRecommendation = viewModel::submitRecommendationDraft,
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
                        catalogGridState = searchScrollState,
                        recommendationListState = recommendationScrollState,
                        mediaFilter = searchMediaFilter,
                        onMediaFilterChange = { searchMediaFilter = it },
                        askUiState = askUiState,
                        askEditorState = askEditorState,
                        onSubmitAskAliflix = viewModel::submitAskAliflix,
                        onResetAskAliflix = viewModel::resetAskAliflix,
                        onEditAskAliflix = viewModel::editAskAliflix,
                        onSetAskEditorState = viewModel::setAskEditorState,
                        onLoadMoreAskAliflix = viewModel::loadMoreAskAliflix,
                        onRetryAskAliflix = viewModel::retryAskAliflix,
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
                val navigationScale by animateFloatAsState(
                    targetValue = if (pressed) 0.94f else 1f,
                    animationSpec = tween(120, easing = FastOutSlowInEasing),
                    label = "bottom-navigation-press",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .scale(navigationScale)
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
                        ) { onSelect(tab) }
                        .testTag(
                            when (tab) {
                                AppTab.HOME -> "bottom-tab-home"
                                AppTab.SEARCH -> "bottom-tab-discover"
                                AppTab.MY_SPACE -> "bottom-tab-my-space"
                            },
                        ),
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
                        modifier = Modifier
                            .size(23.dp)
                            .then(
                                if (tab == AppTab.SEARCH) {
                                    Modifier.testTag("discover-tab")
                                } else {
                                    Modifier
                                },
                            ),
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
            editorialPicks = state.editorialPicks,
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
    editorialPicks: List<Media>,
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
    val allTitles = remember(content) {
        (listOf(content.hero) + content.rails.flatMap(ContentRail::items))
            .distinctBy(Media::key)
    }
    val tastePicks = remember(editorialPicks, allTitles) {
        val currentYear = java.time.Year.now().value
        editorialPicks
            .ifEmpty {
                allTitles.filter { candidate ->
                    val year = candidate.year.take(4).toIntOrNull()
                    year != null && year in (currentYear - 4)..currentYear && candidate.rating >= 7.0
                }
            }
            .asSequence()
            .filter { candidate ->
                val year = candidate.year.take(4).toIntOrNull()
                year != null && year <= currentYear && candidate.rating > 0.0
            }
            .distinctBy(Media::key)
            .sortedWith(
                compareByDescending<Media>(Media::rating)
                    .thenByDescending { candidate -> candidate.year.take(4).toIntOrNull() ?: 0 }
                    .thenByDescending { candidate -> candidate.tmdbVoteCount ?: 0 },
            )
            .take(20)
            .toList()
    }

    LaunchedEffect(pagerState, heroCandidates) {
        if (heroCandidates.size > 1) {
            while (true) {
                kotlinx.coroutines.delay(7_000L)
                pagerState.animateScrollToPage(
                    page = pagerState.currentPage + 1,
                    animationSpec = tween(
                        durationMillis = 1_350,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .aliflixScreenBackground(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            Box {
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
                        personalMatch = PersonalizationEngine.match(featured, likes),
                        onPlay = { onPlay(featured) },
                        onInfo = { onOpen(featured) },
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - (pageOffset * 0.16f)
                            scaleX = 1f - (pageOffset * 0.018f)
                            scaleY = 1f - (pageOffset * 0.018f)
                        },
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

        if (tastePicks.isNotEmpty() && selectedFilter == HomeFilter.FOR_YOU) {
            item(key = "taste-picks") {
                HomeMediaRail(
                    rail = ContentRail("Picked for you", tastePicks),
                    onOpen = onOpen,
                    compact = false,
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
        horizontalArrangement = Arrangement.End,
    ) {
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
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val accessibilityExpansion = (
        (fontScale - 1f).coerceAtLeast(0f) * 170f
    ).coerceAtMost(320f).dp

    Box(
        modifier = modifier
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
                val cardRating = item.imdbRating ?: item.rating.takeIf { it > 0.0 }
                if (cardRating != null && cardRating > 0.0) {
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
        HomeSectionHeader(title = "Recently played")
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
private fun PlaybackProviderSelector(
    selectedProvider: PlaybackProviderId,
    onSelectProvider: (PlaybackProviderId) -> Unit,
    modifier: Modifier = Modifier,
    onEditProviderUrl: ((PlaybackProviderId) -> Unit)? = null,
) {
    val providers = mobileGeneralPlaybackProviders()

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

                Row(
                    modifier = Modifier.weight(1f),
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
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
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
                        text = "Playback",
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    var sourceExpanded by remember { mutableStateOf(false) }
                    val providers = mobileGeneralPlaybackProviders()
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(AliflixSurfaceSecondary)
                                .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(14.dp))
                                .clickable { sourceExpanded = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Streaming source",
                                    color = AliflixContentPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = generalProvider.displayName,
                                    color = AliflixAccentSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(
                                onClick = { onEditProviderUrl(generalProvider) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Edit,
                                    contentDescription = "Edit URL",
                                    tint = AliflixContentSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "Choose source",
                                tint = AliflixContentSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = sourceExpanded,
                            onDismissRequest = { sourceExpanded = false },
                            modifier = Modifier.background(AliflixSurfaceRaised),
                        ) {
                            providers.forEach { provider ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = provider.displayName,
                                                color = AliflixContentPrimary,
                                                fontWeight = if (provider == generalProvider) {
                                                    FontWeight.Bold
                                                } else FontWeight.Normal,
                                            )
                                            if (provider == generalProvider) {
                                                Icon(
                                                    imageVector = Icons.Filled.Check,
                                                    contentDescription = null,
                                                    tint = AliflixAccentSecondary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        onSelectProvider(provider)
                                        sourceExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Updates",
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
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
                        text = "Search",
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Ask Aliflix",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    text = "Show Aliflix in Discover",
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

                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "History",
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
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
                            Text(
                                text = "Clear watch history",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
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
            .aliflixScreenBackground(),
    ) {
        MobileTopSafeArea()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 0.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
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
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(48.dp)
                            .clickable { onRemove(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove from viewing history",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.78f))
                                .padding(8.dp),
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

@Composable
private fun PersonCreditsScreen(
    state: PersonUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val creator = state.creator
    val items = remember(state.items) { state.items.distinctBy(Media::key) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .aliflixScreenBackground()
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
                        .clip(CircleShape)
                        .border(1.dp, AliflixBorderStrong, CircleShape),
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
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = AliflixAccentPrimary)
            }
            state.error != null -> GenreExploreStatePanel(
                title = "Credits unavailable",
                message = state.error,
                actionLabel = "Try again",
                onAction = onRetry,
                modifier = Modifier.weight(1f),
            )
            items.isEmpty() -> GenreExploreStatePanel(
                title = "No works found",
                message = "No TMDB credits are available.",
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
                    Text(
                        text = "Works",
                        color = AliflixContentPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
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
            .aliflixScreenBackground()
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
    onOpenCreator: (MediaCreator) -> Unit = {},
) {
    val item = state.item ?: return
    val configuration = LocalConfiguration.current
    val detailHeroHeight = if (configuration.screenWidthDp > configuration.screenHeightDp) 360.dp else 500.dp
    val detailListState = rememberLazyListState()
    var overviewExpanded by rememberSaveable(item.key) { mutableStateOf(false) }
    var castExpanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val overview = item.overview.ifBlank { "No overview is available yet." }
    var overviewCanExpand by remember(item.key) { mutableStateOf(false) }
    val visibleCast = if (castExpanded) item.cast else item.cast.take(8)
    LazyColumn(
        state = detailListState,
        modifier = Modifier
            .fillMaxSize()
            .aliflixScreenBackground(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item(key = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(detailHeroHeight),
            ) {
                ArtworkPlaceholder(title = item.title)
                AsyncImage(
                    model = item.backdropUrl ?: item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to AliflixScrimStrong.copy(alpha = 0.50f),
                                    0.38f to Color.Transparent,
                                    0.72f to AliflixBlack.copy(alpha = 0.40f),
                                    1f to AliflixBlack,
                                ),
                            )
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to AliflixBackgroundImmersive.copy(alpha = 0.58f),
                                    0.76f to Color.Transparent,
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
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to previous screen",
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = if (item.type == MediaType.MOVIE) "FEATURE FILM" else "SERIES",
                        color = AliflixAccentSecondary,
                        style = MaterialTheme.typography.labelMedium,
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        personalMatch?.let { match ->
                            DetailMetadataPill(
                                label = "${match.score}% match",
                                contentColor = AliflixGreen,
                                containerColor = AliflixGreen.copy(alpha = 0.15f),
                            )
                        }
                        if (item.status.isNotBlank()) {
                            DetailMetadataPill(
                                label = item.status,
                                contentColor = AliflixAccentSecondary,
                                containerColor = AliflixAccentSecondary.copy(alpha = 0.14f),
                            )
                        }
                        listOf(item.year, item.runtime)
                            .filter(String::isNotBlank)
                            .forEach { label -> DetailMetadataPill(label = label) }
                    }
                }
            }
        }
        item(key = "metadata:${item.key}:${item.runtime}:${item.imdbRating}:${item.rottenTomatoesRating}") {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
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
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AliflixAccentPrimary,
                            contentColor = AliflixContentPrimary,
                        ),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (
                                item.type == MediaType.TV &&
                                state.episodes.isNotEmpty()
                            ) {
                                "Play S${state.selectedSeason} E${state.episodes.first().number}"
                            } else {
                                "Play"
                            } + " \u2022 " + selectedProvider.displayName,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedMyListButton(
                        inMyList = inMyList,
                        onClick = { onToggleMyList(item) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    )
                    AnimatedFavoriteButton(
                        liked = liked,
                        onClick = { onToggleLike(item) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    )
                }
                RatingsRow(item = item)
                PlaybackProviderSelector(
                    selectedProvider = selectedProvider,
                    onSelectProvider = onSelectProvider,
                )
                DetailInfoSection(title = "About") {
                    Column(
                        modifier = Modifier.animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                if (item.creators.isNotEmpty()) {
                    DetailInfoSection(title = "Creators") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(end = 4.dp),
                        ) {
                            items(item.creators, key = { creator -> creator.tmdbId }) { creator ->
                                DetailCreatorCard(
                                    creator = creator,
                                    onClick = { onOpenCreator(creator) },
                                )
                            }
                        }
                    }
                }
                if (item.genres.isNotEmpty()) {
                    DetailInfoSection(title = "Genres") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item.genres.distinct().forEach { genre ->
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .clip(CircleShape)
                                        .background(AliflixSurface)
                                        .border(1.dp, AliflixBorderStrong, CircleShape)
                                        .clickable { onOpenGenre(genre, item.type) }
                                        .padding(start = 14.dp, end = 8.dp),
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
                if (item.originalLanguage.isNotBlank() || item.cast.isNotEmpty()) {
                    DetailInfoSection(title = "Details") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (item.originalLanguage.isNotBlank()) {
                                DetailFact(label = "Original language") {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            if (item.cast.isNotEmpty()) {
                                DetailFact(label = "Cast") {
                                    Column(
                                        modifier = Modifier.animateContentSize(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = visibleCast.joinToString(),
                                            color = AliflixContentSecondary,
                                            fontSize = 14.sp,
                                            lineHeight = 21.sp,
                                        )
                                        if (item.cast.size > 8) {
                                            TextButton(
                                                onClick = { castExpanded = !castExpanded },
                                                modifier = Modifier.heightIn(min = 48.dp),
                                                contentPadding = PaddingValues(horizontal = 0.dp),
                                            ) {
                                                Text(
                                                    text = if (castExpanded) {
                                                        "Show less"
                                                    } else {
                                                        "Show all ${item.cast.size} cast members"
                                                    },
                                                    color = AliflixAccentSecondary,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (item.reviews.isNotEmpty()) {
                    DetailInfoSection(
                        title = "Reviews",
                        badge = {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(AliflixSurfaceRaised)
                                    .border(1.dp, AliflixBorderStrong, CircleShape)
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
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 4.dp),
                        ) {
                            items(item.reviews, key = { it.id }) { review ->
                                DetailReviewCard(review = review)
                            }
                        }
                    }
                }
                if (state.error != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AliflixError.copy(alpha = 0.10f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            AliflixError.copy(alpha = 0.24f),
                        ),
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
                                                append("  \u2022  ")
                                                append(season.episodeCount)
                                                append(" episodes")
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
                item(key = "episodes-loading") {
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
                item(key = "episodes-empty") {
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
                        onPlay = { onPlayEpisode(item, episode) },
                    )
                }
            }
        }

        if (state.loading) {
            item(key = "detail-loading") {
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
            item(key = "recommendations") {
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
private fun DetailMetadataPill(
    label: String,
    contentColor: Color = AliflixContentSecondary,
    containerColor: Color = AliflixScrimStrong.copy(alpha = 0.72f),
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, contentColor.copy(alpha = 0.22f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
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
private fun DetailCreatorCard(
    creator: MediaCreator,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(110),
        label = "detail-creator-press",
    )
    Surface(
        modifier = Modifier
            .widthIn(min = 210.dp, max = 260.dp)
            .heightIn(min = 76.dp)
            .scale(cardScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(18.dp),
        color = AliflixSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AliflixSurfacePressed),
                contentAlignment = Alignment.Center,
            ) {
                if (creator.profileUrl != null) {
                    AsyncImage(
                        model = creator.profileUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AliflixContentTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = creator.name,
                    color = AliflixContentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = AliflixAccentSecondary,
                modifier = Modifier.size(19.dp),
            )
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
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
    }

    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 340.dp)
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = AliflixSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderStrong),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        )
                        .border(1.dp, AliflixBorderStrong, CircleShape),
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
                            .clip(RoundedCornerShape(8.dp))
                            .background(goldColor.copy(alpha = 0.15f))
                            .border(1.dp, goldColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 7.dp, vertical = 4.dp),
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
                color = AliflixContentSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
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
                        fontSize = 12.sp,
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
private fun DetailInfoSection(
    title: String,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(15.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(AliflixAccentSecondary, AliflixAccentPrimary),
                        ),
                    ),
            )
            Text(
                text = title,
                color = AliflixContentPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            badge?.invoke()
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = AliflixSurfaceSecondary,
            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixBorderSubtle),
        ) {
            Box(modifier = Modifier.padding(18.dp)) {
                content()
            }
        }
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
private fun RatingsRow(item: Media) {
    val presentation = externalRatingsPresentation(item)

    AnimatedContent(
        targetState = presentation,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "external-ratings-together",
    ) { ratings ->
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
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
        if (loading) {
            MovingMovieLoader(accent = accent)
        } else {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(180))
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
            .padding(start = 8.dp)
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
    episode: Episode,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(AliflixSurfaceSecondary)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(20.dp))
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
                contentDescription = null,
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
                    contentDescription = "Play episode ${episode.number}: ${episode.title}",
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
                    text = "S${episode.seasonNumber} \u2022 E${episode.number}",
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
                    append("IMDb ")
                    append(
                        episode.imdbRating?.let {
                            String.format(java.util.Locale.US, "%.1f", it)
                        } ?: "Not rated",
                    )
                    append("  \u2022  RT ")
                    append(episode.rottenTomatoesRating?.let { "$it%" } ?: "Not rated")
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
