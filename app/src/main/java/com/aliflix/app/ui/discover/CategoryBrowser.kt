package com.aliflix.app.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    AnimatedContent(
        targetState = base ?: "categories",
        transitionSpec = {
            val spec = tween<Float>(280, easing = FastOutSlowInEasing)
            if (targetState == "categories") {
                fadeIn(spec) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 8 } togetherWith
                    fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { -it / 10 }
            } else {
                fadeIn(spec) + slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 5 } togetherWith
                    fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { -it / 12 }
            }
        },
        label = "category-browser-transition",
    ) { visibleBase ->
        if (visibleBase == "categories") {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (failed) item { TextButton(onClick = { retry++ }) { Text("Retry categories") } }
                if (categories.isEmpty() && !failed) item { CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp)) }
                categories.groupBy { it.third }.forEach { (type, entries) ->
                    item { Text(if (type == "movie") "Movies" else "Series", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
                    items(entries.chunked(2), key = { pair -> pair.joinToString("|") { it.first } }) { pair ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { (key, name, _) ->
                                Surface(onClick = { selected = key; selectedName = name }, modifier = Modifier.weight(1f).height(104.dp),
                                    shape = RoundedCornerShape(22.dp), color = Color.Transparent,
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))) {
                                    val tones = listOf(Color(0xFF51437B), Color(0xFF285D67), Color(0xFF704954), Color(0xFF3C5279))
                                    val tone = tones[(key.hashCode() and Int.MAX_VALUE) % tones.size]
                                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tone.copy(alpha = .65f), AliflixBackgroundBase)))) {
                                        Text(name, modifier = Modifier.align(Alignment.BottomStart).padding(18.dp), style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            CategorySections(store, visibleBase, selectedName, onOpen, onBack = { selected = null })
        }
    }
}

@Composable
private fun CategorySections(
    store: DiscoverCatalogueStore,
    base: String,
    selectedName: String,
    onOpen: (Media) -> Unit,
    onBack: () -> Unit,
) {
    val root = store.session(base, "", "All")
    val sections = root.sections
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("‹  $selectedName")
            }
        }
        if (sections.isEmpty()) {
            item {
                when {
                    root.error != null -> TextButton(onClick = { scope.launch { store.load(base, "", "All", force = true) } }) { Text("Retry category") }
                    root.updatedAt == 0L -> CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp))
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
                Surface(
                    onClick = { scope.launch { store.load(key, "", "All", more = true, excludedTmdbIds = excludedTmdbIds) } },
                    enabled = !session.loading, shape = RoundedCornerShape(20.dp), color = AliflixGlassIdle,
                    modifier = Modifier.width(128.dp).height(190.dp),
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
