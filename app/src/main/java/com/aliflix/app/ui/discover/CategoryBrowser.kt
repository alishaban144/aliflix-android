package com.aliflix.app.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Media
import com.aliflix.app.ui.HomeMediaRail
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CategoryBrowser(store: DiscoverCatalogueStore, onOpen: (Media) -> Unit) {
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
    BackHandler(selected != null) { selected = null }
    val base = selected
    val session = base?.let { store.session(it, "", "All") }
    LaunchedEffect(base) { if (base != null) store.load(base, "", "All") }
    androidx.compose.runtime.key(base ?: "categories") {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (selected == null) {
            if (failed) item { TextButton(onClick = { retry++ }) { Text("Retry categories") } }
            if (categories.isEmpty() && !failed) item { CircularProgressIndicator(Modifier.padding(20.dp).size(24.dp)) }
            categories.groupBy { it.third }.forEach { (type, entries) ->
                item { Text(if (type == "movie") "Movies" else "Series", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }
                items(entries.chunked(2)) { pair ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (key, name, _) ->
                            Surface(onClick = { selected = key; selectedName = name }, modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                                shape = RoundedCornerShape(16.dp), color = AliflixGlassSelected) {
                                Text(name, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            item { TextButton(onClick = { selected = null }) { Text("‹  $selectedName") } }
            item(key = "popular") { BrowseRail(store, base!!, "Popular", onOpen) }
            items(session!!.sections.filterNot { it.first == "popular" }, key = { it.first }) { (id, name) ->
                BrowseRail(store, "$base:$id", name, onOpen)
            }
        }
    }
    }
}

@Composable
private fun BrowseRail(store: DiscoverCatalogueStore, key: String, title: String, onOpen: (Media) -> Unit) {
    val session = store.session(key, "", "All")
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, retry) { store.load(key, "", "All", force = retry > 0) }
    val scope = rememberCoroutineScope()
    if (session.items.isNotEmpty()) Column {
        HomeMediaRail(ContentRail(title, session.items), onOpen, compact = false)
        if (session.hasMore) TextButton(enabled = !session.loading, onClick = { scope.launch { store.load(key, "", "All", more = true) } }) {
            Text(if (session.loading) "Loading" else "Load more")
        }
    }
    else if (session.loading) Row(Modifier.padding(16.dp)) { Text(title, Modifier.weight(1f)); CircularProgressIndicator(Modifier.size(20.dp)) }
    else if (session.error != null) TextButton(onClick = { retry++ }) { Text("$title · Retry") }
}
