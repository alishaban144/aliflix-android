@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui

import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore


import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.MobilePlaybackProvider

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*

import androidx.compose.runtime.DisposableEffect

import androidx.activity.ComponentActivity
import androidx.lifecycle.repeatOnLifecycle
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.aliflix.app.AliflixViewModel
import com.aliflix.app.DetailUiState
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.player.WebPlayerScreen
import com.aliflix.app.recommendation.PersonalizationEngine
import com.aliflix.app.update.AppUpdateManager
import com.aliflix.app.update.InstallLaunchResult
import com.aliflix.app.update.UpdateCheckResult
import com.aliflix.app.ui.discover.DiscoverScreen
import com.aliflix.app.ui.common.AliflixLogoMark
import com.aliflix.app.ui.launch.AliflixLaunchOverlay
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBackgroundBase as AliflixBlack
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
    ACCOUNT,
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

    data class Detail(
        val item: Media,
        val firstVisibleItemIndex: Int = 0,
        val firstVisibleItemScrollOffset: Int = 0,
    ) : MobileDestination

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

    data class DiscoverCategory(val category: String, val filter: String, val title: String = "") : MobileDestination

    data class Account(val route: AccountRoute) : MobileDestination
}

private data class MobileAnimatedDestination(
    val screen: AppScreen,
    val saveKey: String,
    val stackDepth: Int,
    val rootTab: AppTab,
    val detailItem: Media? = null,
    val detailScrollIndex: Int = 0,
    val detailScrollOffset: Int = 0,
    val discoverCategory: String? = null,
    val discoverTitle: String = "",
    val discoverFilter: String = "All",
    val genreName: String? = null,
    val genreMediaType: MediaType? = null,
    val accountRoute: AccountRoute? = null,
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
        is MobileDestination.DiscoverCategory -> "${destinations.size}:discover:${destination.category}:${destination.filter}"
        is MobileDestination.Account ->
            "${destinations.size}:account:${destination.route.name}"
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
                            .put("firstVisibleItemIndex", destination.firstVisibleItemIndex)
                            .put(
                                "firstVisibleItemScrollOffset",
                                destination.firstVisibleItemScrollOffset,
                            )
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
                        is MobileDestination.DiscoverCategory -> JSONObject().put("kind", "discover")
                            .put("category", destination.category).put("filter", destination.filter).put("title", destination.title)
                        is MobileDestination.Account -> JSONObject()
                            .put("kind", "account")
                            .put("route", destination.route.name)
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
                                item = Media.fromJson(value.getJSONObject("item")),
                                firstVisibleItemIndex =
                                    value.optInt("firstVisibleItemIndex").coerceAtLeast(0),
                                firstVisibleItemScrollOffset =
                                    value.optInt("firstVisibleItemScrollOffset").coerceAtLeast(0),
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
                        "discover" -> add(MobileDestination.DiscoverCategory(value.getString("category"), value.optString("filter", "All"), value.optString("title")))
                        "account" -> add(
                            MobileDestination.Account(
                                AccountRoute.entries.firstOrNull {
                                    it.name == value.optString("route")
                                } ?: AccountRoute.SIGN_IN,
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

@Composable
fun AliflixApp(
    viewModel: AliflixViewModel,
    playerController: WebPlayerController,
    initialMedia: Media? = null,
    assistantAction: JSONObject? = null,
    onInitialDetailClosed: (() -> Unit)? = null,
) {
    val home by viewModel.home.collectAsState()
    val pickedForYou by viewModel.pickedForYou.collectAsState()
    val tvNetworks by viewModel.tvNetworks.collectAsState()
    val search by viewModel.search.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val genre by viewModel.genre.collectAsState()
    val person by viewModel.person.collectAsState()
    val myList by viewModel.myList.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val savedLikes by viewModel.likes.collectAsState()
    val matchMetadata by viewModel.personalizationMetadata.collectAsState()
    val likes = savedLikes.map { matchMetadata[it.key] ?: it }
    val aiRecommendationsEnabled by viewModel.aiRecommendationsEnabled.collectAsState()
    val recommendationAiModel by viewModel.recommendationAiModel.collectAsState()
    val askUiState by viewModel.askUiState.collectAsState()
    val askEditorState by viewModel.askEditorState.collectAsState()
    val accountState by viewModel.accountState.collectAsState()
    val accountSyncState by viewModel.accountSyncState.collectAsState()
    val playbackProgress by viewModel.playbackProgressStore.entries.collectAsState()

    val playbackPreferences by viewModel.playbackPreferences.collectAsState()
    val ramoflixConfig = playbackPreferences.ramoflixConfig
    val moviepireBaseUrl = playbackPreferences.moviepireBaseUrl
    val dorabyBaseUrl = playbackPreferences.dorabyBaseUrl
    val activity = LocalActivity.current as ComponentActivity
    val updateManager = remember(activity) { AppUpdateManager(activity) }
    val updateScope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf(MobileUpdateUiState()) }
    var urlDialogProvider by remember { mutableStateOf<PlaybackProvider?>(null) }
    var detailProviderName by rememberSaveable { mutableStateOf<String?>(null) }
    val sessionPreferences = remember(activity) { activity.getSharedPreferences("mobile-session", android.content.Context.MODE_PRIVATE) }
    var destinationStack by rememberSaveable(
        stateSaver = MobileDestinationStackSaver,
    ) {
        mutableStateOf<List<MobileDestination>>(
            listOfNotNull(MobileDestination.Root(AppTab.HOME), initialMedia?.let { MobileDestination.Detail(it) }),
        )
    }
    var detailStateSnapshots by remember {
        mutableStateOf<Map<String, DetailUiState>>(emptyMap())
    }
    val detailScrollPositions = remember {
        mutableStateMapOf<String, Pair<Int, Int>>()
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
    var askAliflixActive by remember { mutableStateOf(false) }
    var assistantAskRequest by remember { mutableStateOf<Int?>(null) }
    var libraryCollapseFraction by remember { mutableFloatStateOf(0f) }
    val listScrollState = rememberLazyGridState()
    val favoritesScrollState = rememberLazyGridState()
    val historyScrollState = rememberLazyGridState()
    val genreScrollState = rememberLazyGridState()
    val personScrollState = rememberLazyGridState()
    var homeFilterName by rememberSaveable { mutableStateOf(HomeFilter.FOR_YOU.name) }
    var previousHomeFilterName by rememberSaveable { mutableStateOf(HomeFilter.FOR_YOU.name) }
    var searchMediaFilter by rememberSaveable { mutableStateOf("All") }
    var discoverFocusRequestId by remember { mutableIntStateOf(0) }
    var consumedDiscoverFocusRequestId by remember { mutableIntStateOf(0) }
    var libraryPage by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(libraryPage) { sessionPreferences.edit().putInt("libraryPage", libraryPage).apply() }
    DisposableEffect(activity) {
        val listener = androidx.core.util.Consumer<android.content.Intent> { intent ->
            if (intent.getBooleanExtra("openDownloads", false)) {
                destinationStack = listOf(MobileDestination.Root(AppTab.MY_SPACE)); libraryPage = 3
            }
        }
        activity.addOnNewIntentListener(listener)
        if (activity.intent.getBooleanExtra("openDownloads", false)) {
            destinationStack = listOf(MobileDestination.Root(AppTab.MY_SPACE)); libraryPage = 3
            activity.intent.removeExtra("openDownloads")
        }
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
    var accountNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var launchCompleted by rememberSaveable { mutableStateOf(false) }
    val skipLaunchPresentation = remember { launchCompleted || initialMedia != null || assistantAction != null }
    LaunchedEffect(launchCompleted, recent.take(3).map { it.key }) {
        if (launchCompleted) viewModel.preloadRecentlyWatchedEpisodes()
    }
    LaunchedEffect(launchCompleted, myList.map { it.key }.toSet(), recent.map { it.key }.toSet(), likes.map { it.key }.toSet()) {
        if (launchCompleted) viewModel.refreshLibraryMetadata()
    }
    val isHomeReady = com.aliflix.app.ui.launch.isLaunchHomeReady(home.loading, home.content != null, home.error != null)
    LaunchedEffect(launchCompleted, isHomeReady) {
        if (launchCompleted && isHomeReady && initialMedia == null && assistantAction == null)
            activity.reportFullyDrawn()
    }
    val requestedDetailProvider = PlaybackProvider.fromStoredValue(detailProviderName)
    val detailProvider = requestedDetailProvider?.takeIf { provider ->
        detail.item?.let(provider::isAvailableFor) == true
    } ?: playbackPreferences.effectiveGeneralProvider
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
        if (tab == AppTab.HOME) {
            homeFilterName = HomeFilter.FOR_YOU.name
            previousHomeFilterName = HomeFilter.FOR_YOU.name
            updateScope.launch { homeScrollState.scrollToItem(0) }
        }
        destinationStack = listOf(MobileDestination.Root(tab))
        viewModel.closeDetails()
        viewModel.closeGenre()
        viewModel.closePerson()
    }

    fun captureDetailStateSnapshot() {
        val destination = destinationStack.lastOrNull() as? MobileDestination.Detail ?: return
        val savedScroll = detailScrollPositions[currentDestinationKey]
        if (savedScroll != null && (savedScroll.first != destination.firstVisibleItemIndex || savedScroll.second != destination.firstVisibleItemScrollOffset)) {
            destinationStack = destinationStack.dropLast(1) + destination.copy(
                firstVisibleItemIndex = savedScroll.first,
                firstVisibleItemScrollOffset = savedScroll.second,
            )
        }
        if (
            detail.item?.key == destination.item.key &&
            (!detail.loading || currentDestinationKey !in detailStateSnapshots)
        ) {
            detailStateSnapshots = detailStateSnapshots + (currentDestinationKey to detail)
        }
    }

    DisposableEffect(activity) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                captureDetailStateSnapshot()

            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    fun openDetails(item: Media) {
        captureDetailStateSnapshot()
        destinationStack = destinationStack + MobileDestination.Detail(item)
        viewModel.openDetails(item)
    }

    fun openAccount(route: AccountRoute) {
        viewModel.clearAccountMessage()
        destinationStack = destinationStack + MobileDestination.Account(route)
    }

    fun navigateAccount(route: AccountRoute) {
        viewModel.clearAccountMessage()
        destinationStack = destinationStack.dropLast(1) + MobileDestination.Account(route)
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
        if (initialMedia != null && destinationStack.size == 2 && onInitialDetailClosed != null) {
            onInitialDetailClosed(); return
        }
        when (destinationStack.last()) {
            is MobileDestination.Person -> capturePersonScrollPosition()
            is MobileDestination.Genre -> captureGenreScrollPosition()
            is MobileDestination.Detail -> captureDetailStateSnapshot()
            is MobileDestination.DiscoverCategory -> Unit
            is MobileDestination.Account -> Unit
            is MobileDestination.Root -> Unit
        }
        destinationStack = popMobileDestinationStack(destinationStack)
        val destination = destinationStack.last()
        val destinationKey = mobileDestinationSaveKey(destinationStack)
        when (destination) {
            is MobileDestination.Root -> {
                viewModel.closeDetails()
                viewModel.closeGenre()
                viewModel.closePerson()
            }
            is MobileDestination.Detail -> {
                if (destinationKey !in detailStateSnapshots && detail.item?.key != destination.item.key) {
                    viewModel.openDetails(destination.item)
                }
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
            is MobileDestination.DiscoverCategory -> Unit
            is MobileDestination.Account -> Unit
        }
    }

    LaunchedEffect(currentDestinationKey) {
        when (val destination = currentDestination) {
            is MobileDestination.Root -> Unit
            is MobileDestination.Detail -> {
                if (currentDestinationKey !in detailStateSnapshots && detail.item?.key != destination.item.key) {
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
            is MobileDestination.DiscoverCategory -> Unit
            is MobileDestination.Account -> Unit
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
        requestedProvider: PlaybackProvider? = null,
    ) {
        val unfinished = if (selection.media.type == MediaType.TV && selection.seasonNumber == null && selection.episodeNumber == null)
            viewModel.playbackProgressStore.entries.value.values.filter { it.media.key == selection.media.key &&
                it.positionSeconds > 0 && it.positionSeconds < it.durationSeconds && !it.explicitlyRestarted }
                .maxByOrNull { it.updatedAtMillis } else null
        val resumed = unfinished?.let { selection.copy(seasonNumber = it.seasonNumber, episodeNumber = it.episodeNumber, episodeTitle = it.episodeTitle) }
            ?: viewModel.playbackProgressStore.resumeSelection(selection)
        val prepared = resumed.copy(
            source = playbackPreferences.sourceFor(
                media = selection.media,
                requestedProvider = requestedProvider,
            ),
        )
        com.aliflix.app.player.withPlaybackRoomConsent(activity, prepared) {
            viewModel.markPlayed(selection.media)
            playerSelection = prepared
            com.aliflix.app.player.claimDetailPreload(prepared)
            com.aliflix.app.player.launchNativeSelection(
                activity, prepared,
                playbackPreferences.preferredSubtitleLanguage.code,
                playbackPreferences.autoDisplaySubtitles,
            )
        }
    }

    fun playMedia(item: Media) = playSelection(PlaybackSelection(item))

    var assistantActionHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(assistantAction) {
        val command=assistantAction?:return@LaunchedEffect
        if(assistantActionHandled)return@LaunchedEffect
        assistantActionHandled=true
        when(command.optString("action")) {
            "open_screen"->when(command.optString("screen")) {
                "home"->showRoot(AppTab.HOME)
                "discover"->showRoot(AppTab.SEARCH)
                "ask"->{showRoot(AppTab.SEARCH);assistantAskRequest=1}
                "account"->openAccount(if(accountState.isSignedIn)AccountRoute.MANAGE else AccountRoute.SIGN_IN)
                else->{showRoot(AppTab.MY_SPACE);libraryPage=when(command.optString("screen")){"favorites"->1;"history"->2;"downloads"->3;else->0}}
            }
            "search"->{showRoot(AppTab.SEARCH);viewModel.selectSearchMode(com.aliflix.app.SearchMode.TITLE);viewModel.updateSearch(command.optString("query"));viewModel.submitCatalogueSearch(command.optString("query"))}
            "play"->initialMedia?.let{playSelection(PlaybackSelection(it,seasonNumber=command.optInt("season").takeIf{v->v>0},episodeNumber=command.optInt("episode").takeIf{v->v>0}))}
            "similar" -> initialMedia?.let { media ->
                com.aliflix.app.ui.discover.openAskConversation(activity, media)
            }
        }
    }

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

    val updateLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(updateManager, updateLifecycle) {

        var lastCheck = 0L
        updateLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!updateUi.busy && (lastCheck == 0L || now - lastCheck >= 4 * 60 * 60 * 1000L)) {
                    lastCheck = now
                    val result = updateManager.checkForUpdate()
                    if (result is UpdateCheckResult.Available && !updateUi.busy)
                        updateUi = updateUi.copy(available = result.info)
                }
                kotlinx.coroutines.delay(60_000)
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
        enabled = (shouldReturnHomeOnSystemBack(destinationStack)||assistantAction!=null) && !playerVisible,
    ) {
        if(assistantAction!=null&&destinationStack.size==1&&onInitialDetailClosed!=null) {
            onInitialDetailClosed()
        } else if (destinationStack.size > 1) {
            popDestination()
        } else {
            showRoot(AppTab.HOME)
        }
    }

    AliflixLaunchOverlay(
        isHomeReady = isHomeReady,
        onLaunchComplete = { launchCompleted = true },
        skip = skipLaunchPresentation,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .aliflixScreenBackground()
                .semantics { testTagsAsResourceId = true },
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (currentDestination is MobileDestination.Root && !askAliflixActive) {
                        Box(Modifier.windowInsetsPadding(AliflixInsets.Bottom)) {
                        LibraryChrome(
                            fraction = { if (selectedTab == AppTab.MY_SPACE) libraryCollapseFraction else 0f },
                            slideUp = false,
                        ) {
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
                        }
                    }
                },
            ) { padding ->
            val screen = when (currentDestination) {
                is MobileDestination.Detail -> AppScreen.DETAIL
                is MobileDestination.DiscoverCategory -> AppScreen.GENRE_EXPLORE
                is MobileDestination.Genre -> AppScreen.GENRE_EXPLORE
                is MobileDestination.Person -> AppScreen.PERSON
                is MobileDestination.Account -> AppScreen.ACCOUNT
                is MobileDestination.Root -> when (selectedTab) {
                    AppTab.HOME -> AppScreen.HOME
                    AppTab.SEARCH -> AppScreen.SEARCH
                    AppTab.MY_SPACE -> AppScreen.MY_SPACE
                }
            }
            val currentDetailDestination = currentDestination as? MobileDestination.Detail
            val savedDetailScroll = detailScrollPositions[currentDestinationKey]
            val animatedDestination = MobileAnimatedDestination(
                screen = screen,
                saveKey = currentDestinationKey,
                stackDepth = destinationStack.size,
                rootTab = selectedTab,
                detailItem = currentDetailDestination?.item,
                detailScrollIndex = savedDetailScroll?.first ?: currentDetailDestination?.firstVisibleItemIndex ?: 0,
                detailScrollOffset = savedDetailScroll?.second ?: currentDetailDestination?.firstVisibleItemScrollOffset ?: 0,
                discoverCategory = (currentDestination as? MobileDestination.DiscoverCategory)?.category,
                discoverTitle = (currentDestination as? MobileDestination.DiscoverCategory)?.title.orEmpty(),
                discoverFilter = (currentDestination as? MobileDestination.DiscoverCategory)?.filter ?: "All",
                genreName = (currentDestination as? MobileDestination.Genre)?.name,
                genreMediaType =
                    (currentDestination as? MobileDestination.Genre)?.mediaType,
                accountRoute = (currentDestination as? MobileDestination.Account)?.route,
            )
            AnimatedContent(
                targetState = animatedDestination,
                // Scroll/metadata changes update this destination; only navigation creates
                // another SaveableStateProvider. Concurrent copies of one key crash Compose.
                contentKey = { it.saveKey },
                transitionSpec = {
                    val motion = mobileNavigationMotion(
                        initialDepth = initialState.stackDepth,
                        targetDepth = targetState.stackDepth,
                        initialTab = initialState.rootTab,
                        targetTab = targetState.rootTab,
                    )
                    val pageSlideSpec = tween<IntOffset>(
                        durationMillis = AliflixMotion.Expressive,
                        easing = FastOutSlowInEasing,
                    )
                    val pageFadeSpec = tween<Float>(
                        durationMillis = AliflixMotion.Navigation,
                        easing = FastOutSlowInEasing,
                    )
                    // Finite Compose animations inherit Android's animator duration scale,
                    // including an immediate next-frame finish when animations are disabled.
                    val tabSlideSpec = tween<IntOffset>(
                        durationMillis = AliflixMotion.Navigation,
                        easing = FastOutSlowInEasing,
                    )
                    val tabFadeSpec = tween<Float>(
                        durationMillis = AliflixMotion.Content,
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
                    AppScreen.ACCOUNT -> AccountScreen(
                        route = targetDestination.accountRoute ?: AccountRoute.SIGN_IN,
                        accountState = accountState,
                        syncState = accountSyncState,
                        shelves = {
                            val activityShelves = remember(activity) {
                                com.aliflix.app.data.ActivityShelves(activity)
                            }
                            var trailers by remember(activityShelves) {
                                mutableStateOf(activityShelves.read("trailers"))
                            }
                            var deletingWatched by remember { mutableStateOf<Media?>(null) }
                            deletingWatched?.let { media ->
                                DeleteWatchedDialog(media,
                                    onDismiss = { deletingWatched = null },
                                    onDelete = { viewModel.removeWatched(media); deletingWatched = null })
                            }
                            val watched = playbackProgress.values.filter {
                                it.completed || it.progressFraction >= com.aliflix.app.data.PLAYBACK_WATCHED_FRACTION
                            }
                                .sortedByDescending { it.updatedAtMillis }
                                .map { it.media }
                                .distinctBy { it.key }
                            listOf(
                                ContentRail("Trailers you watched", trailers),
                                ContentRail("Recently watched", watched),
                            ).forEach { rail ->
                                if (rail.items.isNotEmpty()) {
                                    val onRemove = when (rail.title) {
                                        "Trailers you watched" -> { media: Media ->
                                            activityShelves.remove("trailers", media)
                                            trailers = activityShelves.read("trailers")
                                        }
                                        "Recently watched" -> { media: Media ->
                                            deletingWatched = media
                                        }
                                        else -> null
                                    }
                                    HomeMediaRail(
                                        rail = rail,
                                        onOpen = ::openDetails,
                                        compact = false,
                                        onRemove = onRemove,
                                    )
                                }
                            }
                        },
                        onBack = ::popDestination,
                        onNavigate = ::navigateAccount,
                        onAuthenticated = {
                            accountNotice = null
                            showRoot(AppTab.MY_SPACE)
                        },
                        onAccountEnded = { message ->
                            accountNotice = message
                            showRoot(AppTab.MY_SPACE)
                        },
                        onCreate = { email, password, displayName ->
                            viewModel.createEmailAccount(email, password, displayName)
                        },
                        onSignIn = viewModel::signInWithEmail,
                        onGoogleSignIn = { viewModel.signInWithGoogle(activity) },
                        onResetPassword = viewModel::sendPasswordResetEmail,
                        onSignOut = viewModel::signOutAccount,
                        onReauthenticateGoogle = {
                            viewModel.reauthenticateAccountWithGoogle(activity)
                        },
                        onReauthenticatePassword =
                            viewModel::reauthenticateAccountWithPassword,
                        onDeleteAccount = viewModel::deleteCurrentAccount,
                        onRename = viewModel::updateAccountDisplayName,
                        onSync = viewModel::retryAccountSync,
                        onClearMessage = viewModel::clearAccountMessage,
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

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
                        if (targetDestination.discoverCategory != null) {
                            com.aliflix.app.ui.discover.DiscoverCategoryScreen(
                                category = targetDestination.discoverCategory, filter = targetDestination.discoverFilter,
                                title = targetDestination.discoverTitle,
                                store = viewModel.discoverCatalogue, onBack = ::popDestination, onOpen = ::openDetails,
                                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                            )
                        } else if (genreName != null && mediaType != null) {
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
                            preloadEnabled = targetDestination.saveKey == mobileDestinationSaveKey(destinationStack),
                            inMyList = targetInMyList,
                            liked = targetLiked,
                            playbackProgress = playbackProgress,
                            onBack = ::popDestination,
                            onPlay = { item ->
                                playSelection(
                                    PlaybackSelection(
                                        media = item,
                                        availableEpisodes = targetDetail.episodes,
                                    ),
                                    requestedProvider = null,
                                )
                            },
                            onPlayEpisode = { item, episode ->
                                playSelection(
                                    PlaybackSelection(
                                        media = item,
                                        seasonNumber = episode.seasonNumber,
                                        episodeNumber = episode.number,
                                        episodeTitle = episode.title,
                                        availableEpisodes = playerEpisodesFor(
                                            detail = targetDetail,
                                            selectedEpisode = episode,
                                        ),
                                    ),
                                    requestedProvider = null,
                                )
                            },
                            onSelectSeason = viewModel::selectSeason,
                            onToggleMyList = viewModel::toggleMyList,
                            onToggleLike = viewModel::toggleLike,
                            onOpen = ::openDetails,
                            inHistory = targetItem?.let { target -> recent.any { it.key == target.key } } == true,
                            onOpenKeyword = { keyword ->
                                destinationStack = destinationStack + MobileDestination.DiscoverCategory(
                                    "keyword:${targetItem!!.type.routeName}:${keyword.id}", "All", keyword.name,
                                )
                            },
                            onDeleteHistory = viewModel::removeRecent,
                            personalMatch = targetItem?.let {
                                PersonalizationEngine.match(it, likes)
                            },
                            onOpenGenre = ::openGenreFromDetails,
                            onOpenCreator = ::openCreatorFromDetails,
                            initialFirstVisibleItemIndex = targetDestination.detailScrollIndex,
                            initialFirstVisibleItemScrollOffset = targetDestination.detailScrollOffset,
                            onScrollPositionChanged = { index, offset ->
                                detailScrollPositions[targetDestination.saveKey] = index to offset
                            },
                        )
                    }

                    AppScreen.HOME -> HomeScreen(
                        state = home.copy(editorialPicks = pickedForYou),
                        tvNetworks = tvNetworks,
                        recent = recent,
                        likes = likes,
                        playbackProgress = playbackProgress,
                        onRetry = viewModel::refreshHome,
                        catalogueStore = viewModel.discoverCatalogue,
                        onRetryTvNetworks = { viewModel.loadTvNetworks(force = true) },
                        onOpen = ::openDetails,
                        onPlay = ::playMedia,
                        onSearch = { showRoot(AppTab.SEARCH) },
                        listState = homeScrollState,
                        selectedFilter = HomeFilter.entries.firstOrNull { filter ->
                            filter.name == homeFilterName
                        } ?: HomeFilter.FOR_YOU,
                        previousFilter = HomeFilter.entries.firstOrNull { filter ->
                            filter.name == previousHomeFilterName
                        } ?: HomeFilter.FOR_YOU,
                        onSelectFilter = {
                            if (it != HomeFilter.NEW) previousHomeFilterName = it.name
                            homeFilterName = it.name
                        },
                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                    )

                    AppScreen.SEARCH -> DiscoverScreen(
                        askOpenRequestId = assistantAskRequest,
                        onAskOpenRequestConsumed = { assistantAskRequest=null },
                        state = search,
                        catalogueStore = viewModel.discoverCatalogue,
                        onPerson = ::openCreatorFromDetails,
                        onCategory = { category -> destinationStack = destinationStack + MobileDestination.DiscoverCategory(category, searchMediaFilter) },
                        onAccount = { openAccount(if (accountState.isSignedIn) AccountRoute.MANAGE else AccountRoute.SIGN_IN) },
                        aiEnabled = aiRecommendationsEnabled || assistantAction?.optString("action")=="similar" || assistantAction?.optString("screen")=="ask",
                        homeContent = home.content,
                        recent = recent,
                        personalizedPicks = pickedForYou,
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
                        onSearchCompanies = viewModel::searchCompanies,
                        onModeChange = viewModel::selectSearchMode,
                        onOpen = ::openDetails,
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
                        onRefineAskAliflix = viewModel::refineAskAliflix,
                        onToggleHideMySpaceAskAliflix = viewModel::toggleAskHideMySpaceTitles,
                        myListKeys = myList.mapTo(linkedSetOf(), Media::key),
                        mySpaceKeys = (myList + recent + likes).mapTo(linkedSetOf(), Media::key),
                        onToggleMyList = viewModel::toggleMyList,
                        onAskVisibilityChanged = { askAliflixActive = it },
                        catalogBottomPadding = padding.calculateBottomPadding(),
                    )

                    AppScreen.MY_SPACE -> MySpaceScreen(
                        initiallyOpenSettings = assistantAction?.optString("screen")=="settings",
                        onCollapseFractionChange = { libraryCollapseFraction = it },
                        myList = myList,
                        likes = likes,
                        recent = recent,
                        accountState = accountState,
                        onOpenEmailAccount = { openAccount(AccountRoute.SIGN_IN) },
                        onManageAccount = { openAccount(AccountRoute.MANAGE) },
                        onOpen = ::openDetails,
                        onRemoveRecent = viewModel::removeRecent,
                        onClearRecent = viewModel::clearRecent,
                        listGridState = listScrollState,
                        favoritesGridState = favoritesScrollState,
                        historyGridState = historyScrollState,
                        page = libraryPage,
                        onPageChange = { libraryPage = it },
                        generalProvider = playbackPreferences.effectiveGeneralProvider,
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
                        recommendationAiModel = recommendationAiModel,
                        onSetRecommendationAiModel = viewModel::setRecommendationAiModel,
                        preferredSubtitleLanguage = playbackPreferences.preferredSubtitleLanguage,
                        onSelectPreferredSubtitleLanguage =
                            viewModel::selectPreferredSubtitleLanguage,
                        autoDisplaySubtitles = playbackPreferences.autoDisplaySubtitles,
                        onSetAutoDisplaySubtitles = viewModel::setAutoDisplaySubtitles,
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
                        onSelectEpisode = { episode ->
                            playSelection(
                                selection.copy(
                                    seasonNumber = episode.seasonNumber,
                                    episodeNumber = episode.number,
                                    episodeTitle = episode.title,
                                ),
                                requestedProvider = selection.source.identity,
                            )
                        },
                        preferredSubtitleLanguage = playbackPreferences.preferredSubtitleLanguage,
                        autoDisplayPreferredSubtitles = playbackPreferences.autoDisplaySubtitles,
                    )
                }
            }
        }

        urlDialogProvider?.let { provider ->
            val currentUrl = when (provider) {
                MobilePlaybackProvider.FILMO, MobilePlaybackProvider.ANIWORLD, MobilePlaybackProvider.SERIENSTREAM, MobilePlaybackProvider.FLIXER, PlaybackProviderId.CINEJOY, MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY,
                MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO -> provider.defaultBaseUrl
                PlaybackProviderId.MIRURO -> provider.defaultBaseUrl
                PlaybackProviderId.ANIKURO -> provider.defaultBaseUrl
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
                        MobilePlaybackProvider.FILMO, MobilePlaybackProvider.ANIWORLD, MobilePlaybackProvider.SERIENSTREAM, MobilePlaybackProvider.FLIXER, PlaybackProviderId.CINEJOY, MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY,
                        MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO -> Unit
                        PlaybackProviderId.MIRURO -> Unit
                        PlaybackProviderId.ANIKURO -> Unit
                        PlaybackProviderId.RAMOFLIX -> viewModel.updateRamoflixUrl(newUrl)
                        PlaybackProviderId.MOVIEPIRE -> viewModel.updateMoviepireUrl(newUrl)
                        PlaybackProviderId.DORABY -> viewModel.updateDorabyUrl(newUrl)
                    }
                    urlDialogProvider = null
                },
                onReset = {
                    when (provider) {
                        MobilePlaybackProvider.FILMO, MobilePlaybackProvider.ANIWORLD, MobilePlaybackProvider.SERIENSTREAM, MobilePlaybackProvider.FLIXER, PlaybackProviderId.CINEJOY, MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY,
                        MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO -> Unit
                        PlaybackProviderId.MIRURO -> Unit
                        PlaybackProviderId.ANIKURO -> Unit
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
}

@Composable
private fun AliflixBottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
) {
    AliflixBottomChrome(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
    ) {
            AppTab.entries.forEach { tab ->
                val isSelected = selected == tab
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val navigationScale by animateFloatAsState(
                    targetValue = if (pressed) 0.94f else 1f,
                    animationSpec = tween(AliflixMotion.Press, easing = FastOutSlowInEasing),
                    label = "bottom-navigation-press",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .scale(navigationScale)
                        .clip(AliflixCorners.Card)
                        .aliflixSelection(isSelected)
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
                                if (isSelected) Icons.Filled.Explore else Icons.Outlined.Explore
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

                }
            }
        }
    }
}

@Composable
internal fun ConfigurationError(
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
            .windowInsetsPadding(AliflixInsets.Top)
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
            shape = AliflixCorners.Card,
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Try again")
        }
    }
}
