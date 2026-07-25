package com.aliflix.app.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.aliflix.app.AliflixViewModel
import com.aliflix.app.BuildConfig
import com.aliflix.app.DetailUiState
import com.aliflix.app.HomeUiState
import com.aliflix.app.SearchUiState
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.player.WebPlayerController
import com.aliflix.app.player.WebPlayerScreen
import com.aliflix.app.update.AppUpdateManager
import com.aliflix.app.update.InstallLaunchResult
import com.aliflix.app.update.UpdateCheckResult
import com.aliflix.app.update.UpdateInfo
import com.aliflix.app.ui.theme.AliflixBlack
import com.aliflix.app.ui.theme.AliflixMuted
import com.aliflix.app.ui.theme.AliflixRed
import com.aliflix.app.ui.theme.AliflixSurface
import com.aliflix.app.ui.theme.AliflixSurfaceRaised
import kotlinx.coroutines.launch
import java.io.File

private enum class TvDestination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SEARCH("Search", Icons.Default.Search),
    MY_ALIFLIX("My Space", Icons.Rounded.Favorite),
}

private data class TvUpdateUiState(
    val busy: Boolean = false,
    val message: String = "",
    val progress: Int? = null,
    val available: UpdateInfo? = null,
    val downloadedApk: File? = null,
)

/**
 * Dedicated 10-foot interface for the TV flavor. Every interactive surface has
 * a visible focus state and can be reached with only a D-pad and Back button.
 */
@Composable
fun AliflixTvApp(
    viewModel: AliflixViewModel,
    playerController: WebPlayerController,
) {
    val home by viewModel.home.collectAsState()
    val search by viewModel.search.collectAsState()
    val detail by viewModel.detail.collectAsState()
    val myList by viewModel.myList.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val likes by viewModel.likes.collectAsState()
    val ramoflixConfig by viewModel.ramoflixConfig.collectAsState()
    val activity = LocalActivity.current as ComponentActivity
    val updateManager = remember(activity) { AppUpdateManager(activity) }
    val updateScope = rememberCoroutineScope()

    var destinationName by rememberSaveable { mutableStateOf(TvDestination.HOME.name) }
    val destination = TvDestination.valueOf(destinationName)
    var playerSelection by remember { mutableStateOf<PlaybackSelection?>(null) }
    var playerVisible by remember { mutableStateOf(false) }
    var updateUi by remember { mutableStateOf(TvUpdateUiState()) }
    val navFocusRequesters = remember {
        TvDestination.entries.associateWith { FocusRequester() }
    }

    fun open(item: Media) = viewModel.openDetails(item)
    fun play(selection: PlaybackSelection) {
        viewModel.markPlayed(selection.media)
        playerSelection = selection.copy(ramoflixConfig = ramoflixConfig)
        playerVisible = true
    }
    fun playMedia(item: Media) = play(PlaybackSelection(item))
    fun playEpisode(item: Media, episode: Episode) = play(
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
            message = "Checking GitHub for updates…",
            progress = null,
        )
        updateScope.launch {
            updateUi = when (val result = updateManager.checkForUpdate()) {
                is UpdateCheckResult.Available -> TvUpdateUiState(
                    message = "Version ${result.info.versionName} is available.",
                    available = result.info,
                )
                is UpdateCheckResult.UpToDate -> TvUpdateUiState(
                    message = "Aliflix TV ${result.versionName} is up to date.",
                )
                is UpdateCheckResult.Error -> TvUpdateUiState(
                    message = result.message,
                )
            }
        }
    }
    fun installDownloadedUpdate() {
        val apk = updateUi.downloadedApk ?: return
        val message = when (updateManager.launchInstaller(apk)) {
            InstallLaunchResult.INSTALLER_OPENED ->
                "Confirm the update in Android’s installer."
            InstallLaunchResult.PERMISSION_REQUIRED ->
                "Allow installs from Aliflix TV, then return and choose Install update again."
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
            message = "Downloading ${info.versionName}…",
        )
        updateScope.launch {
            updateManager.download(info) { progress ->
                updateUi = updateUi.copy(
                    progress = progress,
                    message = "Downloading ${info.versionName}… $progress%",
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

    LaunchedEffect(destination, detail.item?.key, playerVisible) {
        if (detail.item == null && !playerVisible) {
            navFocusRequesters.getValue(destination).requestFocus()
        }
    }

    BackHandler(enabled = detail.item != null && !playerVisible) {
        viewModel.closeDetails()
    }
    BackHandler(
        enabled = detail.item == null &&
            destination != TvDestination.HOME &&
            !playerVisible,
    ) {
        destinationName = TvDestination.HOME.name
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AliflixBlack),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (detail.item == null) {
                TvNavigation(
                    selected = destination,
                    onSelect = { destinationName = it.name },
                    focusRequesters = navFocusRequesters,
                )
            }

            when {
                detail.item != null -> TvDetailScreen(
                    state = detail,
                    inMyList = detail.item?.let(viewModel::isInMyList) == true,
                    onBack = viewModel::closeDetails,
                    onPlay = ::playMedia,
                    onPlayEpisode = ::playEpisode,
                    onSelectSeason = viewModel::selectSeason,
                    onToggleMyList = viewModel::toggleMyList,
                    onOpen = ::open,
                )

                destination == TvDestination.HOME -> TvHomeScreen(
                    state = home,
                    recent = recent,
                    onRetry = viewModel::refreshHome,
                    onOpen = ::open,
                    onPlay = ::playMedia,
                )

                destination == TvDestination.SEARCH -> TvSearchScreen(
                    state = search,
                    onQueryChange = viewModel::updateSearch,
                    onOpen = ::open,
                )

                else -> TvLibraryScreen(
                    myList = myList,
                    likes = likes,
                    recent = recent,
                    onOpen = ::open,
                    updateUi = updateUi,
                    onCheckForUpdates = ::checkForUpdates,
                    onDownloadUpdate = ::downloadUpdate,
                    onInstallUpdate = ::installDownloadedUpdate,
                )
            }
        }

        AnimatedVisibility(
            visible = playerSelection != null && playerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(50f),
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
    }
}

@Composable
private fun TvNavigation(
    selected: TvDestination,
    onSelect: (TvDestination) -> Unit,
    focusRequesters: Map<TvDestination, FocusRequester>,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.94f), AliflixBlack.copy(alpha = 0.82f)),
                ),
            )
            .padding(horizontal = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AliflixRed),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
        }
        Text(
            text = "ALIFLIX",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.width(24.dp))
        TvDestination.entries.forEach { item ->
            TvNavButton(
                destination = item,
                selected = selected == item,
                onClick = { onSelect(item) },
                modifier = Modifier.focusRequester(focusRequesters.getValue(item)),
            )
        }
    }
}

@Composable
private fun TvNavButton(
    destination: TvDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(19.dp),
        )
        Text(
            destination.label,
            color = if (focused) Color.Black else Color.White,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun TvHomeScreen(
    state: HomeUiState,
    recent: List<Media>,
    onRetry: () -> Unit,
    onOpen: (Media) -> Unit,
    onPlay: (Media) -> Unit,
) {
    when {
        state.loading && state.content == null -> TvLoading("Loading Aliflix")
        state.content == null -> TvError(
            message = state.error ?: "The catalogue could not be loaded.",
            onRetry = onRetry,
        )
        else -> {
            val content = state.content
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                item {
                    TvHero(
                        item = content.hero,
                        onPlay = { onPlay(content.hero) },
                        onDetails = { onOpen(content.hero) },
                    )
                }
                if (recent.isNotEmpty()) {
                    item {
                        TvMediaRail("Continue watching", recent, onOpen)
                    }
                }
                items(content.rails, key = { it.title }) { rail ->
                    if (rail.items.isNotEmpty()) {
                        TvMediaRail(rail.title, rail.items, onOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHero(
    item: Media,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(350.dp),
    ) {
        AsyncImage(
            model = item.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to AliflixBlack,
                        0.52f to AliflixBlack.copy(alpha = 0.48f),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to AliflixBlack,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.56f)
                .padding(start = 46.dp, top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 40.sp,
                lineHeight = 43.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item.year.isNotBlank()) TvMeta(item.year)
                TvMeta(if (item.type == MediaType.TV) "Series" else "Movie")
                if (item.rating > 0) TvMeta("★ %.1f".format(item.rating))
            }
            Text(
                text = item.overview,
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 16.sp,
                lineHeight = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvActionButton("Play", Icons.Default.PlayArrow, primary = true, onClick = onPlay)
                TvActionButton("Details", Icons.Rounded.Movie, onClick = onDetails)
            }
        }
    }
}

@Composable
private fun TvMeta(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.84f),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun TvMediaRail(
    title: String,
    items: List<Media>,
    onOpen: (Media) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 46.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 46.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(items, key = { it.key }) { item ->
                TvPosterCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun TvPosterCard(
    item: Media,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(148.dp)
            .scale(if (focused) 1.08f else 1f)
            .zIndex(if (focused) 2f else 0f)
            .onFocusChanged { focused = it.isFocused }
            .shadow(if (focused) 18.dp else 0.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(AliflixSurface)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(AliflixSurfaceRaised),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    item.year,
                    if (item.type == MediaType.TV) "Series" else "Movie",
                ).filter(String::isNotBlank).joinToString(" • "),
                color = AliflixMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TvSearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Media) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 46.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text("Search", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
        var searchFocused by remember { mutableStateOf(false) }
        TextField(
            value = state.query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Movies, shows, anime…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = AliflixSurfaceRaised,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.White,
                focusedLeadingIconColor = Color.Black,
                unfocusedLeadingIconColor = Color.White,
                focusedPlaceholderColor = Color.DarkGray,
                unfocusedPlaceholderColor = AliflixMuted,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .onFocusChanged { searchFocused = it.isFocused }
                .border(
                    if (searchFocused) 3.dp else 0.dp,
                    if (searchFocused) AliflixRed else Color.Transparent,
                    RoundedCornerShape(12.dp),
                ),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AliflixRed)
            }
            state.query.isBlank() -> TvHint("Press OK on the search box to use the TV keyboard or voice input.")
            state.results.isEmpty() -> TvHint(
                state.error ?: "No matches for “${state.query}”.",
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.results, key = { it.key }) { item ->
                    TvPosterCard(
                        item = item,
                        onClick = { onOpen(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvLibraryScreen(
    myList: List<Media>,
    likes: List<Media>,
    recent: List<Media>,
    onOpen: (Media) -> Unit,
    updateUi: TvUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val hasContent = myList.isNotEmpty() || likes.isNotEmpty() || recent.isNotEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 26.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 46.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    "My Space",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                )
                TvUpdatePanel(
                    state = updateUi,
                    onCheck = onCheckForUpdates,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                )
            }
        }
        if (!hasContent) {
            item {
                TvHint("Your library is empty. Add titles to My List from a details screen.")
            }
        }
        if (myList.isNotEmpty()) item { TvMediaRail("My List", myList, onOpen) }
        if (likes.isNotEmpty()) item { TvMediaRail("Favorites", likes, onOpen) }
        if (recent.isNotEmpty()) item { TvMediaRail("Recently played", recent, onOpen) }
    }
}

@Composable
private fun TvUpdatePanel(
    state: TvUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AliflixSurfaceRaised)
            .border(
                1.dp,
                Color.White.copy(alpha = 0.09f),
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 20.dp, vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.SystemUpdate,
            contentDescription = null,
            tint = AliflixRed,
            modifier = Modifier.size(32.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "App updates",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                state.message.ifBlank {
                    "Installed version ${BuildConfig.VERSION_NAME}"
                },
                color = AliflixMuted,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.available?.notes?.takeIf(String::isNotBlank)?.let { notes ->
                Text(
                    notes,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            state.busy -> {
                CircularProgressIndicator(
                    progress = { (state.progress ?: 0) / 100f },
                    color = AliflixRed,
                    trackColor = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp),
                )
            }
            state.downloadedApk != null -> TvActionButton(
                "Install update",
                Icons.Rounded.SystemUpdate,
                primary = true,
                onClick = onInstall,
            )
            state.available != null -> TvActionButton(
                "Download update",
                Icons.Rounded.SystemUpdate,
                primary = true,
                onClick = onDownload,
            )
            else -> TvActionButton(
                "Check for updates",
                Icons.Rounded.Refresh,
                onClick = onCheck,
            )
        }
    }
}

@Composable
private fun TvDetailScreen(
    state: DetailUiState,
    inMyList: Boolean,
    onBack: () -> Unit,
    onPlay: (Media) -> Unit,
    onPlayEpisode: (Media, Episode) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onToggleMyList: (Media) -> Unit,
    onOpen: (Media) -> Unit,
) {
    val item = state.item ?: return
    val detailFocus = remember(item.key) { FocusRequester() }

    LaunchedEffect(item.key) {
        detailFocus.requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = item.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(AliflixBlack, AliflixBlack.copy(alpha = 0.58f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, AliflixBlack.copy(alpha = 0.45f), AliflixBlack),
                    ),
                ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.64f)
                        .padding(start = 46.dp, top = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TvActionButton(
                        label = "Back",
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        compact = true,
                        onClick = onBack,
                        modifier = Modifier.focusRequester(detailFocus),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 42.sp,
                        lineHeight = 45.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (item.year.isNotBlank()) TvMeta(item.year)
                        TvMeta(if (item.type == MediaType.TV) "Series" else "Movie")
                        if (item.rating > 0) TvMeta("★ %.1f".format(item.rating))
                        item.genres.take(2).forEach { TvMeta(it) }
                    }
                    Text(
                        item.overview,
                        color = Color.White.copy(alpha = 0.86f),
                        fontSize = 16.sp,
                        lineHeight = 23.sp,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TvActionButton(
                            "Play",
                            Icons.Default.PlayArrow,
                            primary = true,
                            onClick = { onPlay(item) },
                        )
                        TvActionButton(
                            if (inMyList) "In My List" else "My List",
                            if (inMyList) Icons.Default.Check else Icons.Default.Add,
                            onClick = { onToggleMyList(item) },
                        )
                    }
                    if (state.loading) {
                        CircularProgressIndicator(
                            color = AliflixRed,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }

            if (item.type == MediaType.TV && state.seasons.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text(
                            "Episodes",
                            color = Color.White,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 46.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 46.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                        ) {
                            items(state.seasons, key = { it.number }) { season ->
                                TvTextButton(
                                    label = season.title,
                                    selected = season.number == state.selectedSeason,
                                    onClick = { onSelectSeason(season.number) },
                                )
                            }
                        }
                    }
                }
                item {
                    if (state.episodesLoading) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(130.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = AliflixRed)
                        }
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 46.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(state.episodes, key = { "${it.seasonNumber}:${it.number}" }) { episode ->
                                TvEpisodeCard(
                                    episode = episode,
                                    onClick = { onPlayEpisode(item, episode) },
                                )
                            }
                        }
                    }
                }
            }

            if (state.recommendations.isNotEmpty()) {
                item { TvMediaRail("More like this", state.recommendations, onOpen) }
            }
        }
    }
}

@Composable
private fun TvEpisodeCard(
    episode: Episode,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width(360.dp)
            .height(116.dp)
            .scale(if (focused) 1.05f else 1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else AliflixSurfaceRaised)
            .border(
                if (focused) 3.dp else 1.dp,
                if (focused) Color.White else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = episode.stillUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(152.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(AliflixSurface),
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "${episode.number}. ${episode.title}",
                color = if (focused) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.runtime.isNotBlank()) {
                Text(
                    episode.runtime,
                    color = if (focused) Color.DarkGray else AliflixMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun TvActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .scale(if (focused) 1.06f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    primary -> AliflixRed
                    else -> Color.White.copy(alpha = 0.16f)
                },
            )
            .border(
                if (focused) 3.dp else 0.dp,
                if (focused) AliflixRed else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 14.dp else 20.dp,
                vertical = if (compact) 9.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (focused) Color.Black else Color.White,
            modifier = Modifier.size(if (compact) 17.dp else 20.dp),
        )
        Text(
            label,
            color = if (focused) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 13.sp else 15.sp,
        )
    }
}

@Composable
private fun TvTextButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (focused) Color.Black else Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> AliflixRed
                    else -> AliflixSurfaceRaised
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun TvLoading(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = AliflixRed)
        Spacer(Modifier.height(15.dp))
        Text(label, color = AliflixMuted)
    }
}

@Composable
private fun TvError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = Color.White, fontSize = 18.sp)
        Spacer(Modifier.height(18.dp))
        TvActionButton("Retry", Icons.Rounded.Refresh, primary = true, onClick = onRetry)
    }
}

@Composable
private fun TvHint(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = AliflixMuted,
            fontSize = 17.sp,
            modifier = Modifier.padding(32.dp),
        )
    }
}
