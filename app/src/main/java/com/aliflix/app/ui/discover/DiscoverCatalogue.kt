package com.aliflix.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.MediaCreator
import com.aliflix.app.recommendation.RecommendationAiClient
import com.aliflix.app.recommendation.V3CatalogMedia
import com.aliflix.app.ui.common.MobileTopSafeArea
import com.aliflix.app.ui.common.aliflixScreenBackground
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val discoveryCategories = linkedMapOf("trending" to "Trending", "new" to "New", "top-rated" to "Top Rated",
    "mind-bending" to "Mind-bending", "feel-good" to "Feel-good", "dark" to "Dark", "fast-paced" to "Fast-paced")

internal class CatalogueSession {
    var items by mutableStateOf<List<Media>>(emptyList())
    var people by mutableStateOf<List<MediaCreator>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(true)
    var nextPage = 1
    var updatedAt = 0L
}

/** ViewModel-owned results survive tab/destination changes; bounded cache and one request per session. */
internal class DiscoverCatalogueStore(private val client: RecommendationAiClient) {
    private val sessions = linkedMapOf<String, CatalogueSession>()
    fun session(category: String?, query: String, filter: String): CatalogueSession {
        val key = "$category:${query.trim()}:$filter"
        return sessions.getOrPut(key) {
            if (sessions.size >= 40) sessions.remove(sessions.keys.first())
            CatalogueSession()
        }
    }
    suspend fun load(category: String?, query: String, filter: String, more: Boolean = false, force: Boolean = false) {
        val session = session(category, query, filter)
        if (session.loading || (more && !session.hasMore)) return
        if (!more && !force && System.currentTimeMillis() - session.updatedAt < 900_000) return
        session.loading = true; session.error = null
        try {
            val json = client.cataloguePage(category, query, filter, if (more) session.nextPage else 1)
            val results = json.optJSONArray("results")
            val items = (0 until (results?.length() ?: 0)).map { index ->
                val item = V3CatalogMedia.fromJson(results!!.getJSONObject(index))
                Media(id = item.tmdbId, type = MediaType.from(item.mediaType), title = item.title,
                    overview = item.overview.orEmpty(), posterPath = item.posterPath, backdropPath = item.backdropPath,
                    year = item.releaseDate?.take(4).orEmpty(), rating = item.tmdbRating ?: 0.0,
                    tmdbVoteCount = item.tmdbVoteCount, genres = item.genres, originalLanguage = item.originalLanguage.orEmpty())
            }
            val rawPeople = json.optJSONArray("people")
            val people = (0 until (rawPeople?.length() ?: 0)).map { index -> rawPeople!!.getJSONObject(index).let {
                MediaCreator(it.getInt("tmdbId"), it.getString("name"), it.optString("profilePath").takeIf { value -> value.startsWith("/") })
            } }
            // Refresh keeps existing item order and loaded pages, preserving the visible anchor.
            session.items = if (more || session.items.isNotEmpty()) (session.items + items).distinctBy(Media::key) else items
            session.people = if (more || session.people.isNotEmpty()) (session.people + people).distinctBy(MediaCreator::tmdbId) else people
            if (more || session.nextPage == 1) {
                session.nextPage = json.getInt("page") + 1
                session.hasMore = json.optBoolean("hasMore")
            }
            session.updatedAt = System.currentTimeMillis()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { session.error = "Catalogue unavailable" }
        finally { session.loading = false }
    }
}

@Composable
internal fun DiscoverCatalogueContent(store: DiscoverCatalogueStore, query: String, filter: String,
    onOpen: (Media) -> Unit, onPerson: (MediaCreator) -> Unit, onCategory: (String) -> Unit,
    modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
    val category = if (query.isBlank()) "trending" else null
    val session = store.session(category, query, filter)
    val scope = rememberCoroutineScope()
    LaunchedEffect(query, filter) { if (query.isNotBlank()) delay(280); store.load(category, query, filter) }
    LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), modifier = modifier,
        contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("header", span = { GridItemSpan(maxLineSpan) }) { header() }
        if (query.isBlank()) {
            item("explore", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().clickable { onCategory("trending") }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Explore", tint = AliflixAccentSecondary)
                }
            }
            item("explore-posters", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(session.items.take(16), key = Media::key) { item ->
                        AsyncImage(item.posterUrl, item.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(118.dp).height(128.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpen(item) })
                    }
                }
            }
        } else {
            items(session.items, key = Media::key) { item -> DiscoverPosterCard(item, onOpen, Modifier.padding(horizontal = 8.dp)) }
            items(session.people, key = { "person:${it.tmdbId}" }) { person ->
                Column(Modifier.padding(horizontal = 8.dp).clickable { onPerson(person) }) {
                    AsyncImage(person.profileUrl, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape))
                    Text(person.name, maxLines = 2, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (session.hasMore && session.items.isNotEmpty()) item("more", span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { scope.launch { store.load(null, query, filter, more = true) } }, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
            }
        }
        if (session.loading || session.error != null || (session.updatedAt > 0 && session.items.isEmpty() && session.people.isEmpty())) {
            item("status", span = { GridItemSpan(maxLineSpan) }) { CatalogueStatus(session) { store.load(category, query, filter, force = true) } }
        }
    }
}

@Composable
private fun CatalogueStatus(session: CatalogueSession, onRetry: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (session.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        else if (session.error != null) {
            Text(session.error!!, color = AliflixContentSecondary)
            TextButton(onClick = { scope.launch { onRetry() } }) { Text("Retry") }
        } else if (session.items.isEmpty() && session.people.isEmpty()) Text("No results", color = AliflixContentSecondary)
    }
}

@Composable
internal fun CatalogueGrid(store: DiscoverCatalogueStore, category: String?, query: String, filter: String,
    onOpen: (Media) -> Unit, onPerson: (MediaCreator) -> Unit, modifier: Modifier = Modifier, header: @Composable () -> Unit = {}) {
    val session = store.session(category, query, filter)
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var waiting by remember(session) { mutableStateOf(session.updatedAt == 0L) }
    LaunchedEffect(category, query, filter) {
        try {
            if (category == null) delay(280)
            store.load(category, query, filter)
        } finally { waiting = false }
    }
    Column(modifier) {
        if (!waiting && session.items.isEmpty() && session.people.isEmpty()) {
            CatalogueStatus(session) { store.load(category, query, filter, force = true) }
        }
        LazyVerticalGrid(state = grid, columns = GridCells.Adaptive(112.dp), modifier = Modifier.weight(1f).testTag("discover-catalogue-results"),
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item("header", span = { GridItemSpan(maxLineSpan) }) { header() }
            if ((waiting || session.loading) && session.items.isEmpty() && session.people.isEmpty()) {
                items(9, key = { "skeleton:$it" }) {
                    ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
                }
            }
                if (session.items.isNotEmpty()) item("titles-label", span = { GridItemSpan(maxLineSpan) }) { Text("Titles", color = AliflixContentPrimary, style = MaterialTheme.typography.titleMedium) }
            items(session.items, key = Media::key) { item -> DiscoverPosterCard(item, onOpen) }
            if (session.people.isNotEmpty()) {
                item("people-label", span = { GridItemSpan(maxLineSpan) }) { Text("People", color = AliflixContentPrimary, style = MaterialTheme.typography.titleMedium) }
                items(session.people, key = { "person:${it.tmdbId}" }) { person ->
                    Column(Modifier.clickable { onPerson(person) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsyncImage(person.profileUrl, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(AliflixSurfaceSecondary))
                        Text(person.name, color = AliflixContentPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (session.items.isNotEmpty() || session.people.isNotEmpty()) item("next", span = { GridItemSpan(maxLineSpan) }) {
                if (session.error != null || session.loading) CatalogueStatus(session) { store.load(category, query, filter, more = true) }
                else if (session.hasMore) TextButton(onClick = { scope.launch { store.load(category, query, filter, more = true) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load more") }
            }
        }
    }
}

@Composable
internal fun DiscoverCategoryScreen(category: String, filter: String, store: DiscoverCatalogueStore,
    onBack: () -> Unit, onOpen: (Media) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().aliflixScreenBackground()) {
        MobileTopSafeArea()
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AliflixContentPrimary) }
            Text(discoveryCategories[category].orEmpty(), color = AliflixContentPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        CatalogueGrid(store, category, "", filter, onOpen, {}, Modifier.weight(1f))
    }
}
