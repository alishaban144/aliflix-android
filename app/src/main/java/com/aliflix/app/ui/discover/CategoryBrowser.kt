package com.aliflix.app.ui.discover

import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.common.AliflixSurface

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Media
import com.aliflix.app.ui.HomeMediaRail
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CategoryBrowser(
    store: DiscoverCatalogueStore,
    onOpen: (Media) -> Unit,
    onBack: () -> Unit = {},
    onSearch: (() -> Unit)? = null,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        failed = false
        try {
            val rows = store.categories().getJSONArray("categories")
            categories = (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
                Triple("genre:${row.getString("type")}:${row.getInt("id")}", row.getString("name"), row.getString("type"))
            } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { failed = true }
    }
    BackHandler(enabled = selected != null) { selected = null }
    BackHandler(enabled = selected == null) { onBack() }
    val base = selected
    LaunchedEffect(base) { if (base != null) store.load(base, "", "All") }
    Column(Modifier.fillMaxSize()) {
        CategoryHeader(
            title = if (base == null) "Categories" else selectedName,
            onBack = { if (base == null) onBack() else selected = null },
            onSearch = onSearch,
        )
        AnimatedContent(
            targetState = base ?: "categories",
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val spec = tween<Float>(280, easing = FastOutSlowInEasing)
                if (targetState == "categories") {
                    fadeIn(spec) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 8 } togetherWith
                        fadeOut(tween(140)) + slideOutHorizontally(tween(AliflixMotion.Selection)) { -it / 10 }
                } else {
                    fadeIn(spec) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 5 } togetherWith
                        fadeOut(tween(140)) + slideOutHorizontally(tween(AliflixMotion.Selection)) { -it / 12 }
                }
            },
            label = "category-browser-transition",
        ) { visibleBase ->
            if (visibleBase == "categories") {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = AliflixSpacing.Large)) {
                    if (failed) item { TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Retry categories") } }
                    if (categories.isEmpty() && !failed) {
                        items(4) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ShimmerBox(Modifier.weight(1f).height(82.dp).clip(AliflixCorners.Panel))
                                ShimmerBox(Modifier.weight(1f).height(82.dp).clip(AliflixCorners.Panel))
                            }
                        }
                    }
                    categories.groupBy { it.third }.forEach { (type, entries) ->
                        item { Text(if (type == "movie") "Movies" else "Series", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) }
                        items(entries.chunked(2), key = { pair -> pair.joinToString("|") { it.first } }) { pair ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { (key, name, _) ->
                                    val (accent, icon) = genreVisualStyle(name)
                                    AliflixSurface(
                                        onClick = { selected = key; selectedName = name },
                                        modifier = Modifier.weight(1f).height(86.dp),
                                        shape = AliflixCorners.Panel,
                                        level = AliflixSurfaceLevel.Content,
                                    ) {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(accent.copy(alpha = 0.24f), Color.Transparent),
                                                    ),
                                                )
                                                .padding(14.dp),
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = accent.copy(alpha = 0.42f),
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .size(34.dp),
                                            )
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AliflixContentPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.align(Alignment.TopStart).padding(end = 26.dp),
                                            )
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                CategorySections(store, visibleBase, onOpen)
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String, onBack: () -> Unit, onSearch: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = AliflixSpacing.Content, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AliflixIconButton(onClick = onBack, modifier = Modifier.size(48.dp).clip(CircleShape)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to previous screen", tint = AliflixContentPrimary)
        }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onSearch != null) AliflixIconButton(onClick = onSearch, modifier = Modifier.size(48.dp).clip(CircleShape)
            .aliflixSurface(AliflixSurfaceLevel.Chrome, CircleShape)) {
            Icon(Icons.Outlined.Search, "Search", tint = AliflixContentPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CategorySections(
    store: DiscoverCatalogueStore,
    base: String,
    onOpen: (Media) -> Unit,
) {
    val root = store.session(base, "", "All")
    val sections = root.sections
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = AliflixSpacing.Large)) {
        if (sections.isEmpty()) {
            item {
                when {
                    root.error != null -> TextButton(onClick = { scope.launch { store.load(base, "", "All", force = true) } }, modifier = Modifier.padding(16.dp)) { Text("Retry category") }
                    root.updatedAt == 0L -> Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        repeat(2) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ShimmerBox(Modifier.width(150.dp).height(18.dp).clip(CircleShape))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    repeat(3) {
                                        ShimmerBox(Modifier.weight(1f).aspectRatio(2f / 3f).clip(AliflixCorners.Card))
                                    }
                                }
                            }
                        }
                    }
                    else -> Text("Preparing category sections…", modifier = Modifier.padding(20.dp), color = AliflixContentSecondary)
                }
            }
        }
        items(sections, key = { it.first }) { (id, name) ->
            BrowseRail(store = store, key = "$base:$id", title = name, onOpen = onOpen)
        }
    }
}

@Composable
private fun BrowseRail(
    store: DiscoverCatalogueStore,
    key: String,
    title: String,
    onOpen: (Media) -> Unit,
    excludedTmdbIds: Set<Int> = emptySet(),
    autoLoad: Boolean = true,
) {
    val session = store.session(key, "", "All")
    var retry by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, retry, autoLoad, excludedTmdbIds) {
        if (autoLoad || retry > 0) store.load(key, "", "All", force = retry > 0, excludedTmdbIds = excludedTmdbIds)
    }
    val scope = rememberCoroutineScope()
    if (session.items.isNotEmpty()) Column {
        HomeMediaRail(ContentRail(title, session.items), onOpen, compact = false,
            trailingContent = if (session.hasMore || session.error != null) ({
                AliflixSurface(
                    onClick = { scope.launch { store.load(key, "", "All", more = true, excludedTmdbIds = excludedTmdbIds) } },
                    enabled = !session.loading, shape = AliflixCorners.Panel,
                    modifier = Modifier.width(128.dp).height(190.dp),
                    level = AliflixSurfaceLevel.Content,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (session.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else Text(if (session.error != null) "Retry" else "Load more", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }) else null,
        )
    } else if (session.loading || session.updatedAt == 0L && session.error == null) Row(Modifier.padding(16.dp)) {
        Text(title, Modifier.weight(1f))
        CircularProgressIndicator(Modifier.size(20.dp))
    } else if (session.error != null) TextButton(onClick = { retry++ }) { Text("$title · Retry") }
    else Text("$title · No titles", modifier = Modifier.padding(16.dp), color = AliflixContentTertiary)
}

private fun genreVisualStyle(name: String): Pair<Color, ImageVector> {
    val lower = name.lowercase(java.util.Locale.ROOT)
    return when {
        "sci" in lower -> Color(0xFF6E59D9) to Icons.Rounded.RocketLaunch
        "horror" in lower -> Color(0xFFD94A5A) to Icons.Rounded.NightsStay
        "comedy" in lower -> Color(0xFFF1B967) to Icons.Rounded.SentimentVerySatisfied
        "action" in lower || "adventure" in lower -> Color(0xFFE87A45) to Icons.Rounded.LocalFireDepartment
        "thriller" in lower || "mystery" in lower -> Color(0xFF7A6BE6) to Icons.Rounded.Visibility
        "crime" in lower -> Color(0xFFC95A72) to Icons.Rounded.Gavel
        "romance" in lower -> Color(0xFFE66D96) to Icons.Rounded.Favorite
        "animation" in lower || "anime" in lower -> Color(0xFF4FB3E8) to Icons.Rounded.Animation
        "documentary" in lower -> Color(0xFF54C78F) to Icons.Rounded.Public
        "fantasy" in lower -> Color(0xFF9C72F2) to Icons.Rounded.AutoAwesome
        "family" in lower || "kids" in lower -> Color(0xFF63D69A) to Icons.Rounded.FamilyRestroom
        "drama" in lower -> Color(0xFF6D8BE8) to Icons.Rounded.TheaterComedy
        "war" in lower || "politics" in lower -> Color(0xFFB87654) to Icons.Rounded.Shield
        "history" in lower -> Color(0xFFC49E63) to Icons.Rounded.AccountBalance
        "music" in lower -> Color(0xFFD964B8) to Icons.Rounded.MusicNote
        "western" in lower -> Color(0xFFD49257) to Icons.Rounded.Explore
        else -> AliflixAccentPrimary to Icons.Rounded.Movie
    }
}
