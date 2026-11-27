@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui




import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.aliflix.app.account.AccountState
import com.aliflix.app.data.RamoflixConfig
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.SubtitleLanguage
import com.aliflix.app.recommendation.RecommendationAiModel
import com.aliflix.app.update.UpdateInfo
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixAccentSecondary as AliflixIce
import com.aliflix.app.ui.theme.AliflixBackgroundBase as AliflixBlack
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixContentSecondary as AliflixMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.aliflix.app.ui.common.MobileTopSafeArea
import com.aliflix.app.downloads.downloadAtmosphere

internal data class MobileUpdateUiState(
    val busy: Boolean = false,
    val message: String = "",
    val progress: Int? = null,
    val available: UpdateInfo? = null,
    val downloadedApk: File? = null,
)

@Composable
internal fun RecentRail(
    items: List<Media>,
    playbackProgress: Map<String, PlaybackProgress>,
    onOpen: (Media) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HomeSectionHeader(title = "Recently played")
        LazyRow(
            contentPadding = PaddingValues(horizontal = AliflixSpacing.Content),
            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        ) {
            items(items, key = { it.key }) { item ->
                val progressPresentation = recentPlaybackPresentation(item, playbackProgress)
                val progress = progressPresentation?.progress
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val cardScale by animateFloatAsState(
                    targetValue = if (pressed) 0.97f else 1f,
                    animationSpec = tween(AliflixMotion.Press),
                    label = "recent-card-press",
                )
                AliflixMediaCard(
                    modifier = Modifier
                        .width(236.dp)
                        .aspectRatio(1.68f)
                        .scale(cardScale)

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
                            .clip(AliflixCorners.Small)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f))
                            .padding(horizontal = AliflixSpacing.Small, vertical = 5.dp),
                    ) {
                        Text(
                            text = "RECENT",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.0.sp,
                        )
                    }
                    if (progress != null) {
                        val remainingMinutes = ((progress.durationSeconds - progress.positionSeconds).coerceAtLeast(0.0) / 60.0).toInt()
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(10.dp)
                                .clip(AliflixCorners.Small)
                                .background(Color.Black.copy(alpha = 0.72f))
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = when {
                                    progress.completed -> "Watched"
                                    remainingMinutes >= 1 -> "${remainingMinutes}m left"
                                    else -> "${(progress.progressFraction * 100).toInt().coerceIn(1, 99)}%"
                                },
                                color = AliflixAccentSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = 13.dp, end = 10.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
                        ) {
                            Text(
                                text = item.title,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = progressPresentation?.episodeDescription ?: buildList {
                                    add(if (item.type == MediaType.MOVIE) "Movie" else "Series")
                                    if (item.year.isNotBlank()) add(item.year)
                                }.joinToString("  •  "),
                                color = if (progressPresentation?.episodeDescription != null) {
                                    AliflixAccentSecondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontSize = 11.sp,
                                fontWeight = if (progressPresentation?.episodeDescription != null) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AliflixAccentPrimary.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (progress != null && progress.progressFraction > 0.0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.5.dp)
                                .background(Color.White.copy(alpha = 0.16f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress.progressFraction.toFloat().coerceIn(0.03f, 1f))
                                    .height(3.5.dp)
                                    .background(AliflixAccentSecondary),
                            )
                        }
                    }
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
        shape = AliflixCorners.Small,
        contentPadding = PaddingValues(horizontal = AliflixSpacing.Small),
        border = null,
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
internal fun MobileProviderUrlDialog(
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
        AliflixSheet(
            modifier = Modifier
                .padding(horizontal = AliflixSpacing.Large)
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            contentColor = AliflixContentPrimary) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Content),
        ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(AliflixCorners.Small)
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
        colors = AliflixSurfaceDefaults.textFieldColors(),
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
                        shape = AliflixCorners.Card,
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
            .aliflixSurface(shape = AliflixCorners.Card)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(AliflixCorners.Small)
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
            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    color = AliflixContentSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                contentPadding = PaddingValues(horizontal = AliflixSpacing.Medium, vertical = 7.dp),
                border = null,
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MySpaceScreen(
    myList: List<Media>,
    likes: List<Media>,
    recent: List<Media>,
    accountState: AccountState,
    onOpenEmailAccount: () -> Unit,
    onManageAccount: () -> Unit,
    onOpen: (Media) -> Unit,
    onRemoveRecent: (Media) -> Unit,
    onClearRecent: () -> Unit,
    listGridState: LazyGridState,
    favoritesGridState: LazyGridState,
    historyGridState: LazyGridState,
    page: Int,
    onPageChange: (Int) -> Unit,
    generalProvider: PlaybackProvider,
    onSelectProvider: (PlaybackProvider) -> Unit,
    onEditProviderUrl: (PlaybackProvider) -> Unit,
    aiRecommendationsEnabled: Boolean,
    onSetAiRecommendationsEnabled: (Boolean) -> Unit,
    recommendationAiModel: RecommendationAiModel,
    onSetRecommendationAiModel: (RecommendationAiModel) -> Unit,
    preferredSubtitleLanguage: SubtitleLanguage,
    onSelectPreferredSubtitleLanguage: (SubtitleLanguage) -> Unit,
    autoDisplaySubtitles: Boolean,
    onSetAutoDisplaySubtitles: (Boolean) -> Unit,
    updateUi: MobileUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onCollapseFractionChange: (Float) -> Unit = {},
    initiallyOpenSettings: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(
        initialPage = page.coerceIn(0, 3),
        pageCount = { 4 },
    )
    var showSettingsWindow by rememberSaveable { mutableStateOf(initiallyOpenSettings) }
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
            text = null,
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
            containerColor = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated),
            titleContentColor = Color.White,
            textContentColor = AliflixMuted,
            shape = AliflixCorners.Chrome,
        )
    }

    if (showSettingsWindow) {
        MobileSettingsDialog(
            accountState = accountState,
            onOpenAccount = {
                showSettingsWindow = false
                if (accountState.isSignedIn) onManageAccount() else onOpenEmailAccount()
            },
            generalProvider = generalProvider,
            onSelectProvider = onSelectProvider,
            onEditProviderUrl = onEditProviderUrl,
            aiRecommendationsEnabled = aiRecommendationsEnabled,
            onSetAiRecommendationsEnabled = onSetAiRecommendationsEnabled,
            recommendationAiModel = recommendationAiModel,
            onSetRecommendationAiModel = onSetRecommendationAiModel,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            onSelectPreferredSubtitleLanguage = onSelectPreferredSubtitleLanguage,
            autoDisplaySubtitles = autoDisplaySubtitles,
            onSetAutoDisplaySubtitles = onSetAutoDisplaySubtitles,
            updateUi = updateUi,
            onCheckForUpdates = onCheckForUpdates,
            onDownloadUpdate = onDownloadUpdate,
            onInstallUpdate = onInstallUpdate,
            onClearRecent = onClearRecent,
            onDismiss = { showSettingsWindow = false },
        )
    }

    val chrome = rememberLibraryChrome(pagerState.settledPage, onCollapseFractionChange)
    Column(
        modifier = modifier.nestedScroll(chrome.nestedScrollConnection)
            .fillMaxSize()
            .downloadAtmosphere(),
    ) {
        MobileTopSafeArea(extraPadding = 0.dp)
        LibraryChrome(
            fraction = { chrome.state.collapsedFraction },
            onHeightChanged = { height ->
                val limit = -height.toFloat()
                chrome.state.heightOffsetLimit = limit
                chrome.state.heightOffset = chrome.state.heightOffset.coerceIn(limit, 0f)
            },
        ) { Column {
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AliflixSpacing.Content, end = AliflixSpacing.Medium, top = AliflixSpacing.Tiny, bottom = 6.dp),
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
                shape = AliflixCorners.Card,
                contentPadding = PaddingValues(horizontal = AliflixSpacing.Medium),
                border = null,
            ) {
                Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Settings",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                AnimatedVisibility((updateUi.available != null || updateUi.downloadedApk != null),
                    enter = fadeIn(), exit = fadeOut()) {
                    Icon(
                        Icons.Rounded.SystemUpdate,
                        contentDescription = "App update available",
                        tint = AliflixAccentSecondary,
                        modifier = Modifier.padding(start = 7.dp).size(18.dp),
                    )
                }
            }
        }
        MySpaceAccountCard(
            accountState = accountState,
            onOpenAccount = {
                if (accountState.isSignedIn) onManageAccount() else onOpenEmailAccount()
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        val libraryTabs = listOf(
            "My List" to myList.size,
            "Favorites" to likes.size,
            "History" to recent.size,
            "Downloads" to com.aliflix.app.downloads.downloadCount(),
        )
        MobileLibraryTabs(libraryTabs, pagerState.currentPage, onPageChange)
        } }
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
                    emptyTitle = "Your watchlist is empty",
                    emptyMessage = "Tap + on any movie or series to save it here for later.",
                    useGenreSections = true,
                )
            } else if (targetPage == 1) {
                GenreOrganizedList(
                    items = likes,
                    onOpen = onOpen,
                    gridState = favoritesGridState,
                    emptyTitle = "No favorites yet",
                    emptyMessage = "Tap the heart on titles you love to tune Ask Aliflix to your taste.",
                )
            } else if (targetPage == 3) {
                com.aliflix.app.downloads.DownloadsSection()
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
    emptyMessage: String = "",
    useGenreSections: Boolean = false,
) {
    if (items.isEmpty()) {
        GroupedGenreList(items, onOpen, gridState, emptyTitle, emptyMessage)
        return
    }
    var mediaFilter by rememberSaveable(emptyTitle) { mutableStateOf("All") }
    var sortMode by rememberSaveable(emptyTitle) { mutableStateOf("Default") }
    val filteredItems = remember(items, mediaFilter, sortMode) {
        val typed = when (mediaFilter) {
            "Movies" -> items.filter { it.type == MediaType.MOVIE }
            "Series" -> items.filter { it.type == MediaType.TV }
            else -> items
        }
        when (sortMode) {
            "Top Rated" -> typed.sortedByDescending { it.imdbRating ?: it.rating }
            "A–Z" -> typed.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
            else -> typed
        }
    }
    if (!useGenreSections) {
        Column(Modifier.fillMaxSize()) {
            if (items.size >= 3) LibraryFilterBar(
                selectedType = mediaFilter,
                onSelectType = { mediaFilter = it },
                sortMode = sortMode,
                onCycleSort = {
                    sortMode = when (sortMode) {
                        "Default" -> "Top Rated"
                        "Top Rated" -> "A–Z"
                        else -> "Default"
                    }
                },
            )
            GroupedGenreList(filteredItems, onOpen, gridState, emptyTitle, emptyMessage)
        }
        return
    }
    val groups = remember(filteredItems) { groupMyListByMainGenre(filteredItems) }
    val sectionIndices = remember(groups) { libraryGenreSectionIndices(groups) }
    val genres = remember(groups) { groups.filter { it.key.isNotEmpty() } }
    val scope = rememberCoroutineScope()
    val activeGenre by remember(groups, gridState) {
        derivedStateOf {
            sectionIndices.entries.lastOrNull { it.value <= gridState.firstVisibleItemIndex }?.key
        }
    }
    val genreTabs = rememberLazyListState()
    val motionEnabled = rememberMobileMotionEnabled()
    LaunchedEffect(activeGenre, genres, genreTabs.isScrollInProgress) {
        val index = genres.indexOfFirst { it.key == activeGenre }
        if (index >= 0 && !genreTabs.isScrollInProgress &&
            genreTabs.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            genreTabs.scrollToItem(index)
        }
    }
    var scrollToGenre by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Column(Modifier.fillMaxSize()) {
        if (items.size >= 3) LibraryFilterBar(
            selectedType = mediaFilter,
            onSelectType = { mediaFilter = it },
            sortMode = sortMode,
            onCycleSort = {
                sortMode = when (sortMode) {
                    "Default" -> "Top Rated"
                    "Top Rated" -> "A–Z"
                    else -> "Default"
                }
            },
        )
        if (filteredItems.isEmpty()) {
            EmptyMessage(title = "No matching titles", message = "Switch the filter back to All to see everything saved.")
        } else Row(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (genres.isNotEmpty()) {
            LazyColumn(
                state = genreTabs,
                contentPadding = PaddingValues(4.dp, 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.width(114.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AliflixContentPrimary.copy(alpha = .045f))
                    .selectableGroup(),
            ) {
                items(genres, key = { it.key }) { group ->
                    MobileLibraryGenre(group.key, group.value.size, activeGenre == group.key) {
                        scrollToGenre?.cancel()
                        scrollToGenre = scope.launch {
                            val index = sectionIndices.getValue(group.key)
                            if (motionEnabled) gridState.animateScrollToItem(index) else gridState.scrollToItem(index)
                        }
                    }
                }
            }
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
            groups.forEach { group ->
                if (group.key.isNotEmpty()) item(key = "genre:${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(group.key, color = AliflixContentPrimary, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                items(group.value, key = { "saved:${it.key}" }) { item ->
                    MediaPoster(item = item, width = 132.dp, showLibraryMetadata = true,
                        onClick = { onOpen(item) })
                }
            }
        }
    }
    }
}

@Composable
private fun LibraryFilterBar(
    selectedType: String,
    onSelectType: (String) -> Unit,
    sortMode: String,
    onCycleSort: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("All", "Movies", "Series").forEach { option ->
            val active = option == selectedType
            AliflixChip(
                selected = active,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = Modifier.clickable { onSelectType(option) },
            ) {
                Text(
                    text = option,
                    color = if (active) AliflixAccentSecondary else AliflixContentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        AliflixChip(
            selected = sortMode != "Default",
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            modifier = Modifier.clickable(onClick = onCycleSort),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Sort, null, tint = AliflixAccentSecondary, modifier = Modifier.size(13.dp))
                Text(sortMode, color = AliflixContentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GroupedGenreList(
    items: List<Media>,
    onOpen: (Media) -> Unit,
    gridState: LazyGridState,
    emptyTitle: String = "Nothing saved yet",
    emptyMessage: String = "",
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
        contentPadding = PaddingValues(start = AliflixSpacing.Content, end = AliflixSpacing.Content, top = 6.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Panel),
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
                            .padding(horizontal = AliflixSpacing.Small, vertical = AliflixSpacing.Tiny),
                    )
                }
            }
            items(genreItems, key = { "saved:${it.key}" }) { item ->
                MediaPoster(
                    item = item,
                    width = 132.dp,
                    showLibraryMetadata = true,
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
    val removalScope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(setOf<String>()) }
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
                .padding(horizontal = AliflixSpacing.Content, vertical = AliflixSpacing.Tiny),
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
            contentPadding = PaddingValues(start = AliflixSpacing.Content, end = AliflixSpacing.Content, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(items, key = { "history:${it.key}" }) { item ->
                val disappearing by animateFloatAsState(if (item.key in removing) 0f else 1f, tween(260), label = "history removal")
                Box(Modifier.graphicsLayer { alpha = disappearing; scaleX = .88f + .12f * disappearing; scaleY = scaleX; rotationZ = (1f - disappearing) * -3f }.animateItem(fadeInSpec = tween(AliflixMotion.Content), fadeOutSpec = tween(280), placementSpec = spring(stiffness = 280f))) {
                    MediaPoster(
                        item = item,
                        width = 132.dp,
                        showLibraryMetadata = true,
                        onClick = { onOpen(item) },
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(48.dp)
                            .clickable(enabled = item.key !in removing) {
                                removing = removing + item.key
                                removalScope.launch { delay(280); onRemove(item); removing = removing - item.key }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove from viewing history",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.78f))
                                .padding(AliflixSpacing.Small),
                        )
                    }
                }
            }
        }
    }
}
