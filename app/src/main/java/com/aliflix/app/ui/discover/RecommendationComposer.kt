@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationQuestion
import com.aliflix.app.recommendation.RecommendationRequestDraft
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class AskFilterGroup(val title: String, val options: List<String>)

internal val MOVIE_GENRES = listOf(
    "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family",
    "Fantasy", "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "TV Movie",
    "Thriller", "War", "Western",
)
internal val TV_GENRES = listOf(
    "Action & Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Kids",
    "Mystery", "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics", "Western",
)
internal val MOODS = listOf(
    "Dark", "Gritty", "Bleak", "Tense", "Suspenseful", "Scary", "Creepy", "Atmospheric",
    "Mind-bending", "Emotional", "Heartwarming", "Warm", "Funny", "Feel-good", "Relaxing",
    "Melancholic", "Romantic", "Hopeful", "Uplifting", "Intense", "Fast-paced", "Slow-burn",
    "Quirky", "Satirical", "Serious", "Epic",
)
internal val THEMES = listOf(
    "Investigation", "Murder mystery", "Serial killer", "Revenge", "Survival", "Heist",
    "Organized crime", "Conspiracy", "Coming of age", "Friendship", "Betrayal", "Family conflict",
    "Political intrigue", "Espionage", "Prison", "Courtroom", "Police investigation", "Detective",
    "Time travel", "Time loop", "Alternate reality", "Parallel universe", "Artificial intelligence",
    "Robots", "Space exploration", "Alien contact", "Dystopian", "Post-apocalyptic", "Supernatural",
    "Ghosts", "Demons", "Witchcraft", "Magic", "Psychic abilities", "Superpowers", "Vampires",
    "Werewolves", "Zombies", "Monsters", "Disaster", "Road trip", "Sports", "Music", "School",
    "Medical", "Historical",
)
internal val CHARACTERS = listOf(
    "Child protagonist", "Teen protagonist", "Adult protagonist", "Older protagonist", "Female lead",
    "Male lead", "Ensemble cast", "Family", "Detective", "Police officer", "Criminal", "Scientist",
    "Doctor", "Teacher", "Student", "Soldier", "Spy", "Superhero", "Antihero", "Villain protagonist",
)
internal val SETTINGS = listOf(
    "Space", "Future", "Medieval", "Historical", "Modern day", "Small town", "Big city", "Countryside",
    "School", "University", "Workplace", "Hospital", "Prison", "Courtroom", "Military", "Ocean",
    "Island", "Wilderness", "Desert", "Snow", "Underground", "Alternate world", "Post-apocalyptic world",
)
internal val ERAS = listOf(
    "2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "Before 1960", "Recent", "Custom range",
)
internal val MOVIE_RUNTIMES = listOf(
    "Under 80 min", "Under 90 min", "90–105 min", "105–120 min", "Under 2h", "2–2.5h", "2.5h+", "Custom maximum",
)
internal val TV_RUNTIMES = listOf(
    "Under 25 min", "Under 30 min", "30–45 min", "Under 45 min", "45–60 min", "60+ min",
)
internal val RATINGS = listOf(
    "IMDb 6+", "IMDb 6.5+", "IMDb 7+", "IMDb 7.5+", "IMDb 8+", "IMDb 8.5+", "TMDB 6+", "TMDB 7+", "TMDB 8+",
)
internal val LANGUAGES = listOf(
    "English", "Arabic", "French", "Spanish", "German", "Italian", "Portuguese", "Japanese", "Korean",
    "Chinese", "Hindi", "Turkish", "Russian", "Swedish", "Danish", "Norwegian",
)
internal val SERIES_STATUS = listOf("Ended", "Returning", "Miniseries", "Any")
internal val DISCOVERY_STYLES = listOf("Popular", "Hidden gem", "Less mainstream", "Cult favorite", "Highly rated")
internal val EXCLUSIONS = listOf(
    "Animation", "Horror", "Comedy", "Romance", "Musical", "Documentary", "Reality", "Supernatural",
    "Graphic violence", "Gore", "Jump scares", "Heavy romance", "War", "Crime",
)

internal fun askFilterGroups(kind: RecommendationMediaKind?): List<AskFilterGroup> = buildList {
    add(AskFilterGroup("Genre", if (kind == RecommendationMediaKind.SERIES) TV_GENRES else MOVIE_GENRES))
    add(AskFilterGroup("Mood & tone", MOODS))
    add(AskFilterGroup("Story & themes", THEMES))
    add(AskFilterGroup("Characters", CHARACTERS))
    add(AskFilterGroup("Setting", SETTINGS))
    add(AskFilterGroup("Era", ERAS))
    add(AskFilterGroup("Runtime", if (kind == RecommendationMediaKind.SERIES) TV_RUNTIMES else MOVIE_RUNTIMES))
    add(AskFilterGroup("Rating", RATINGS))
    add(AskFilterGroup("Language", LANGUAGES))
    if (kind == RecommendationMediaKind.SERIES) add(AskFilterGroup("Series status", SERIES_STATUS))
    add(AskFilterGroup("Discovery style", DISCOVERY_STYLES))
    add(AskFilterGroup("Exclude", EXCLUSIONS))
}

@Composable
internal fun RecommendationComposer(
    state: RecommendationUiState,
    selectedKind: RecommendationMediaKind?,
    onSelectType: (RecommendationMediaKind) -> Unit,
    onSearchTitles: suspend (String) -> List<Media>,
    onSubmit: (RecommendationRequestDraft) -> Unit,
    onAnswer: (RecommendationQuestion, List<String>) -> Unit,
    onRestart: () -> Unit,
    onRelax: (String) -> Unit,
    onRetry: () -> Unit,
    onShowMatches: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showComposer by rememberSaveable { mutableStateOf(true) }
    var activeMode by rememberSaveable { mutableIntStateOf(0) }
    var describeText by rememberSaveable { mutableStateOf("") }
    var similarQuery by rememberSaveable { mutableStateOf("") }
    var selectedAnchor by remember { mutableStateOf<Media?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<Media>()) }
    var suggestionsLoading by remember { mutableStateOf(false) }
    var activeRefinements by rememberSaveable { mutableStateOf(setOf<String>()) }

    LaunchedEffect(state) { if (state is RecommendationUiState.Results) showComposer = false }
    LaunchedEffect(similarQuery, selectedAnchor, selectedKind) {
        if (selectedAnchor != null || similarQuery.trim().length < 2) {
            suggestions = emptyList()
            suggestionsLoading = false
            return@LaunchedEffect
        }
        delay(220)
        suggestionsLoading = true
        suggestions = try {
            val preferredType = if (selectedKind == RecommendationMediaKind.MOVIE) MediaType.MOVIE else MediaType.TV
            onSearchTitles(similarQuery.trim())
                .sortedBy { if (it.type == preferredType) 0 else 1 }
                .distinctBy(Media::key)
                .take(5)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            emptyList()
        } finally {
            suggestionsLoading = false
        }
    }

    val filterGroups = remember(selectedKind) { askFilterGroups(selectedKind) }
    val mediaType = if (selectedKind == RecommendationMediaKind.MOVIE) MediaType.MOVIE else MediaType.TV
    val builtPrompt = when (activeMode) {
        0 -> describeText.trim()
        1 -> selectedAnchor?.let { "${if (selectedKind == RecommendationMediaKind.MOVIE) "Movies" else "Series"} similar to ${it.title}" }.orEmpty()
        else -> activeRefinements.joinToString(", ")
    }
    val builtDraft = remember(activeMode, describeText, selectedAnchor, activeRefinements, selectedKind, filterGroups) {
        if (activeMode == 0) {
            RecommendationRequestDraft(mediaType = mediaType, freeText = describeText.trim())
        } else if (activeMode == 1) {
            RecommendationRequestDraft(mediaType = mediaType, similarityTitle = selectedAnchor?.title, freeText = "")
        } else {
            val selectedIn: (String) -> List<String> = { name ->
                val values = filterGroups.firstOrNull { it.title == name }?.options.orEmpty()
                activeRefinements.filter { it in values }
            }
            val semantic = selectedIn("Mood & tone") + selectedIn("Story & themes") +
                selectedIn("Characters") + selectedIn("Setting")
            val rating = selectedIn("Rating").firstOrNull()
            RecommendationRequestDraft(
                mediaType = mediaType,
                genres = selectedIn("Genre"),
                moods = selectedIn("Mood & tone"),
                themes = selectedIn("Story & themes") + selectedIn("Characters") + selectedIn("Setting"),
                yearRule = selectedIn("Era").firstOrNull(),
                runtimeRule = selectedIn("Runtime").firstOrNull(),
                minimumImdb = rating?.takeIf { it.startsWith("IMDb ") }?.substringAfter("IMDb ")?.removeSuffix("+")?.toDoubleOrNull(),
                language = selectedIn("Language").firstOrNull(),
                status = selectedIn("Series status").firstOrNull()?.takeUnless { it == "Any" },
                exclusions = selectedIn("Exclude"),
                freeText = (semantic + selectedIn("Discovery style") + rating.orEmpty()).filter(String::isNotBlank).joinToString(", "),
            )
        }
    }
    val canSubmit = selectedKind != null && builtPrompt.isNotBlank() && state !is RecommendationUiState.Discovering

    if (!showComposer) {
        Row(
            modifier = modifier.fillMaxWidth().background(AliflixSurfaceSecondary, RoundedCornerShape(20.dp))
                .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (selectedKind == RecommendationMediaKind.MOVIE) "Movies" else "Series", color = AliflixAccentSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(builtPrompt.ifBlank { "Recommendation search" }, color = AliflixContentPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { showComposer = true }) { Icon(Icons.Rounded.Edit, "Edit request", tint = AliflixContentSecondary) }
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to Catalogue", tint = AliflixContentPrimary)
            }
            Text(
                "Ask Aliflix",
                color = AliflixContentPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Surface(color = AliflixAccentPrimary.copy(alpha = .14f), shape = RoundedCornerShape(6.dp)) {
                Text("BETA", color = AliflixAccentPrimary, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }

        if (state is RecommendationUiState.Error) RecommendationErrorState(state, onRetry)
        else if (state is RecommendationUiState.Empty) RecommendationEmptyState(state, onRelax)
        else if (state is RecommendationUiState.SourceUnavailable) RecommendationUnavailableState(state, onRetry)
        else if (state is RecommendationUiState.Relaxation) RecommendationRelaxationState(state, onRelax)
        else if (state is RecommendationUiState.Question) RecommendationQuestionState(state, onAnswer)
        else {
            SegmentedMediaControl(selectedKind, onSelectType)
            ModeControl(activeMode) {
                keyboard?.hide()
                activeMode = it
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("ask-active-workspace")) {
                AnimatedContent(
                    targetState = activeMode,
                    transitionSpec = {
                        val enter = slideInHorizontally(DiscoverMotion.standard()) { if (targetState > initialState) it / 5 else -it / 5 } + fadeIn(DiscoverMotion.standard())
                        val exit = slideOutHorizontally(DiscoverMotion.standard()) { if (targetState > initialState) -it / 6 else it / 6 } + fadeOut(DiscoverMotion.standard())
                        enter togetherWith exit
                    },
                    label = "ask-mode-content",
                    modifier = Modifier.fillMaxSize(),
                ) { mode ->
                    when (mode) {
                        0 -> DescribeWorkspace(describeText) { describeText = it }
                        1 -> SimilarWorkspace(
                            query = similarQuery,
                            selected = selectedAnchor,
                            suggestions = suggestions,
                            loading = suggestionsLoading,
                            onQueryChange = { similarQuery = it; selectedAnchor = null },
                            onSelect = {
                                keyboard?.hide()
                                selectedAnchor = it
                                similarQuery = it.title
                            },
                            onChange = { selectedAnchor = null; suggestions = emptyList() },
                        )
                        else -> FilterWorkspace(filterGroups, activeRefinements) { option ->
                            activeRefinements = if (option in activeRefinements) activeRefinements - option else activeRefinements + option
                        }
                    }
                }
            }
            Button(
                onClick = { onSubmit(builtDraft) }, enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(56.dp).testTag("ask-find-matches"),
                colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary, disabledContainerColor = AliflixSurfaceSecondary),
                shape = RoundedCornerShape(20.dp),
            ) {
                if (state is RecommendationUiState.Discovering) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Find matches", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable private fun SegmentedMediaControl(selectedKind: RecommendationMediaKind?, onSelect: (RecommendationMediaKind) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).background(AliflixSurfaceSecondary, RoundedCornerShape(20.dp)).padding(4.dp)) {
        RecommendationMediaKind.entries.forEach { kind ->
            val selected = kind == selectedKind
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp))
                    .background(if (selected) AliflixAccentPrimary.copy(alpha = .24f) else Color.Transparent)
                    .clickable { onSelect(kind) }.testTag(if (kind == RecommendationMediaKind.MOVIE) "discover-type-movie" else "discover-type-series"),
                contentAlignment = Alignment.Center,
            ) { Text(if (kind == RecommendationMediaKind.MOVIE) "Movies" else "Series", color = if (selected) AliflixContentPrimary else AliflixContentSecondary, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable private fun ModeControl(active: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp).background(AliflixSurfaceSecondary, RoundedCornerShape(18.dp)).padding(4.dp)) {
        listOf("Describe", "Similar to", "Build with filters").forEachIndexed { index, label ->
            val color by animateColorAsState(if (active == index) AliflixSurfaceElevated else Color.Transparent, label = "mode-color")
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(color).clickable { onSelect(index) }.testTag("ask-mode-$index"), contentAlignment = Alignment.Center) {
                Text(label, color = if (active == index) AliflixContentPrimary else AliflixContentSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable private fun DescribeWorkspace(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Describe the story, mood, characters or limits", color = AliflixContentSecondary, fontSize = 13.sp)
        OutlinedTextField(
            value = value, onValueChange = onChange,
            placeholder = { Text("A tense supernatural story with a teen protagonist…", color = AliflixContentTertiary) },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("discover-search-field"), shape = RoundedCornerShape(16.dp),
            colors = askFieldColors(),
        )
    }
}

@Composable private fun SimilarWorkspace(
    query: String,
    selected: Media?,
    suggestions: List<Media>,
    loading: Boolean,
    onQueryChange: (String) -> Unit,
    onSelect: (Media) -> Unit,
    onChange: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Find something similar to", color = AliflixContentPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        AnimatedContent(targetState = selected, transitionSpec = { (fadeIn() + expandVertically()) togetherWith (fadeOut() + shrinkVertically()) }, label = "anchor-morph") { anchor ->
            if (anchor == null) {
                OutlinedTextField(
                    value = query, onValueChange = onQueryChange, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                    placeholder = { Text("Search for a movie or series…", color = AliflixContentTertiary) },
                    modifier = Modifier.fillMaxWidth().testTag("similar-title-search"), shape = RoundedCornerShape(16.dp), colors = askFieldColors(),
                )
            } else {
                Surface(color = AliflixSurfaceSecondary, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("similar-selected-anchor")) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(anchor.posterUrl, anchor.title, Modifier.size(width = 48.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                            Text(anchor.title, color = AliflixContentPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (anchor.type == MediaType.MOVIE) "Movie" else "Series"}${anchor.year.take(4).takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = AliflixContentSecondary, fontSize = 12.sp)
                        }
                        Button(onClick = onChange, colors = ButtonDefaults.buttonColors(containerColor = AliflixSurfaceElevated)) { Text("Change") }
                    }
                }
            }
        }
        AnimatedVisibility(visible = selected == null && suggestions.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LazyColumn(Modifier.fillMaxWidth().testTag("similar-suggestions"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(suggestions, key = Media::key) { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(item) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(item.posterUrl, item.title, Modifier.size(width = 36.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp)); Column {
                            Text(item.title, color = AliflixContentPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (item.type == MediaType.MOVIE) "Movie" else "Series"}${item.year.take(4).takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", color = AliflixContentSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FilterWorkspace(groups: List<AskFilterGroup>, selected: Set<String>, onToggle: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("ask-filter-browser"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selected.isNotEmpty()) {
            item("selected") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Selected filters", color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        selected.sorted().forEach { option -> FilterChip(option, true) { onToggle(option) } }
                    }
                }
            }
        }
        items(groups, key = AskFilterGroup::title) { group ->
            var expanded by rememberSaveable(group.title) { mutableStateOf(group.title == "Genre") }
            val count = group.options.count(selected::contains)
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(group.title, color = AliflixContentPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (count > 0) Surface(color = AliflixAccentPrimary.copy(alpha = .18f), shape = RoundedCornerShape(10.dp)) { Text("$count", color = AliflixAccentPrimary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)) }
                    Spacer(Modifier.width(6.dp)); Icon(if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, "Toggle ${group.title}", tint = AliflixContentSecondary)
                }
                AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        if (group.title == "Era") Text("Recent: Released within the last 5 years", color = AliflixContentTertiary, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            group.options.forEach { option -> FilterChip(option, option in selected) { onToggle(option) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) AliflixAccentPrimary.copy(alpha = .2f) else AliflixSurfaceSecondary, label = "filter-bg")
    val border by animateColorAsState(if (selected) AliflixAccentPrimary else AliflixBorderSubtle, label = "filter-border")
    Row(Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(background).border(1.dp, border, RoundedCornerShape(17.dp)).clickable(onClick = onClick).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(selected) { Icon(Icons.Rounded.Check, null, tint = AliflixAccentPrimary, modifier = Modifier.size(15.dp)) }
        if (selected) Spacer(Modifier.width(4.dp)); Text(text, color = AliflixContentPrimary, fontSize = 12.sp)
    }
}

@Composable private fun askFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = AliflixSurfaceSecondary, unfocusedContainerColor = AliflixSurfaceSecondary,
    focusedBorderColor = AliflixAccentPrimary, unfocusedBorderColor = AliflixBorderSubtle,
    focusedTextColor = AliflixContentPrimary, unfocusedTextColor = AliflixContentPrimary,
)

@Composable private fun RecommendationQuestionState(state: RecommendationUiState.Question, onAnswer: (RecommendationQuestion, List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(state.question.text, color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        state.question.options.forEach { option -> Button(onClick = { onAnswer(state.question, listOf(option.value)) }, modifier = Modifier.fillMaxWidth()) { Text(option.label) } }
    }
}
@Composable private fun RecommendationErrorState(state: RecommendationUiState.Error, onRetry: () -> Unit) = MessageState("An error occurred", state.message, state.canRetry, onRetry)
@Composable private fun RecommendationUnavailableState(state: RecommendationUiState.SourceUnavailable, onRetry: () -> Unit) = MessageState("Source unavailable", state.message, state.canRetry, onRetry)
@Composable private fun MessageState(title: String, message: String, retry: Boolean, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text(title, color = AliflixError, fontWeight = FontWeight.Bold); Text(message, color = AliflixContentSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)); if (retry) Button(onClick = onRetry) { Text("Retry") } }
}
@Composable private fun RecommendationEmptyState(state: RecommendationUiState.Empty, onRelax: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text("No matches", color = AliflixContentPrimary, fontWeight = FontWeight.Bold); Text(state.message, color = AliflixContentSecondary, textAlign = TextAlign.Center); state.options.forEach { Button(onClick = { onRelax(it.id) }) { Text(it.label) } } }
}
@Composable private fun RecommendationRelaxationState(state: RecommendationUiState.Relaxation, onRelax: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { Text("Too specific", color = AliflixContentPrimary, fontWeight = FontWeight.Bold); Text(state.message, color = AliflixContentSecondary, textAlign = TextAlign.Center); state.options.forEach { Button(onClick = { onRelax(it.id) }) { Text(it.label) } } }
}
