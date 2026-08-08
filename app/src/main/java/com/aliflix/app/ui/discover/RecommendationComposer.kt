@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.aliflix.app.recommendation.RecommendationUiState
import com.aliflix.app.recommendation.omdb.OmdbGenre
import com.aliflix.app.recommendation.omdb.OmdbRecommendationAnchor
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSort
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSpec
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary
import kotlinx.coroutines.delay

internal val CANONICAL_OMDB_GENRES = listOf(
    "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family",
    "Fantasy", "Film-Noir", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News",
    "Reality-TV", "Romance", "Sci-Fi", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western"
)

internal val YEAR_PRESETS = listOf("Any", "2020+", "2015+", "2010+", "2000+", "Before 2000")
internal val MOVIE_RUNTIME_PRESETS = listOf("Any", "< 90 min", "< 120 min", "90–120 min", "120–150 min", "150+ min")
internal val TV_RUNTIME_PRESETS = listOf("Any", "< 30 min", "30–45 min", "45–60 min", "60+ min")
internal val IMDB_RATING_PRESETS = listOf("Any", "6+", "6.5+", "7+", "7.5+", "8+", "8.5+", "9+")
internal val IMDB_VOTES_PRESETS = listOf("Any", "1,000+", "5,000+", "10,000+", "25,000+", "50,000+", "100,000+", "250,000+")
internal val RT_RATING_PRESETS = listOf("Any", "60%+", "70%+", "80%+", "90%+")
internal val METASCORE_PRESETS = listOf("Any", "50+", "60+", "70+", "80+", "90+")
internal val CONTENT_RATINGS = listOf("G", "PG", "PG-13", "R", "NC-17", "TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14", "TV-MA")
internal val LANGUAGES = listOf("English", "Arabic", "French", "Spanish", "German", "Italian", "Portuguese", "Japanese", "Korean", "Chinese", "Hindi", "Turkish", "Russian")
internal val SERIES_SEASONS_PRESETS = listOf("Any", "1 season", "2+", "3+", "5+", "10+")
internal val SORT_MODES = listOf("Best match", "IMDb rating", "Rotten Tomatoes", "Metascore", "Newest", "Oldest", "Most IMDb votes")

@Composable
internal fun RecommendationComposer(
    state: RecommendationUiState,
    selectedKind: RecommendationMediaKind?,
    onSelectType: (RecommendationMediaKind) -> Unit,
    onSearchTitles: suspend (String) -> List<Media>,
    onSubmitSpec: (OmdbRecommendationSpec) -> Unit,
    onResetSession: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var showComposer by rememberSaveable { mutableStateOf(true) }
    var activeMode by rememberSaveable { mutableIntStateOf(0) } // 0: Describe, 1: Similar to, 2: Build with filters

    // Describe mode state
    var describeText by rememberSaveable { mutableStateOf("") }

    // Similar to state
    var similarQuery by rememberSaveable { mutableStateOf("") }
    var selectedAnchor by remember { mutableStateOf<Media?>(null) }
    var suggestions by remember { mutableStateOf(emptyList<Media>()) }
    var suggestionsLoading by remember { mutableStateOf(false) }

    // Build filters state
    var mediaType by rememberSaveable { mutableStateOf(selectedKind?.mediaType ?: MediaType.MOVIE) }
    var includedGenres by rememberSaveable { mutableStateOf(setOf<String>()) }
    var excludedGenres by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedYearPreset by rememberSaveable { mutableStateOf("Any") }
    var customFromYear by rememberSaveable { mutableStateOf("") }
    var customToYear by rememberSaveable { mutableStateOf("") }
    var selectedRuntimePreset by rememberSaveable { mutableStateOf("Any") }
    var customMinRuntime by rememberSaveable { mutableStateOf("") }
    var customMaxRuntime by rememberSaveable { mutableStateOf("") }
    var selectedImdbPreset by rememberSaveable { mutableStateOf("Any") }
    var selectedVotesPreset by rememberSaveable { mutableStateOf("Any") }
    var selectedRtPreset by rememberSaveable { mutableStateOf("Any") }
    var selectedMetascorePreset by rememberSaveable { mutableStateOf("Any") }
    var selectedContentRatings by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedLanguages by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedSeasonsPreset by rememberSaveable { mutableStateOf("Any") }
    var selectedSortMode by rememberSaveable { mutableStateOf("Best match") }

    // Accordion expanded state (Genres expanded by default)
    var expandedGenres by rememberSaveable { mutableStateOf(true) }
    var expandedExcludedGenres by rememberSaveable { mutableStateOf(false) }
    var expandedYearRuntime by rememberSaveable { mutableStateOf(false) }
    var expandedRatings by rememberSaveable { mutableStateOf(false) }
    var expandedContentLang by rememberSaveable { mutableStateOf(false) }
    var expandedSeasons by rememberSaveable { mutableStateOf(false) }
    var expandedSort by rememberSaveable { mutableStateOf(false) }

    var lastExecutedSpec by remember { mutableStateOf<OmdbRecommendationSpec?>(null) }

    LaunchedEffect(state) {
        if (state is RecommendationUiState.Results) {
            showComposer = false
        }
    }

    // Anchor search debouncer
    LaunchedEffect(similarQuery, selectedAnchor) {
        if (selectedAnchor != null || similarQuery.trim().length < 2) {
            suggestions = emptyList()
            suggestionsLoading = false
            return@LaunchedEffect
        }
        delay(220)
        suggestionsLoading = true
        suggestions = try {
            onSearchTitles(similarQuery.trim()).take(6)
        } catch (_: Throwable) {
            emptyList()
        } finally {
            suggestionsLoading = false
        }
    }

    fun executeReset() {
        describeText = ""
        similarQuery = ""
        selectedAnchor = null
        suggestions = emptyList()
        includedGenres = emptySet()
        excludedGenres = emptySet()
        selectedYearPreset = "Any"
        customFromYear = ""
        customToYear = ""
        selectedRuntimePreset = "Any"
        customMinRuntime = ""
        customMaxRuntime = ""
        selectedImdbPreset = "Any"
        selectedVotesPreset = "Any"
        selectedRtPreset = "Any"
        selectedMetascorePreset = "Any"
        selectedContentRatings = emptySet()
        selectedLanguages = emptySet()
        selectedSeasonsPreset = "Any"
        selectedSortMode = "Best match"
        activeMode = 0
        mediaType = MediaType.MOVIE
        lastExecutedSpec = null
        showComposer = true
        onResetSession()
    }

    fun buildSpecFromFilters(): OmdbRecommendationSpec {
        var minYear: Int? = when (selectedYearPreset) {
            "2020+" -> 2020
            "2015+" -> 2015
            "2010+" -> 2010
            "2000+" -> 2000
            else -> customFromYear.toIntOrNull()
        }
        var maxYear: Int? = when (selectedYearPreset) {
            "Before 2000" -> 1999
            else -> customToYear.toIntOrNull()
        }

        var minRuntime: Int? = when (selectedRuntimePreset) {
            "90–120 min" -> 90
            "120–150 min" -> 120
            "150+ min" -> 150
            "30–45 min" -> 30
            "45–60 min" -> 45
            "60+ min" -> 60
            else -> customMinRuntime.toIntOrNull()
        }
        var maxRuntime: Int? = when (selectedRuntimePreset) {
            "< 90 min" -> 89
            "< 120 min" -> 119
            "90–120 min" -> 120
            "120–150 min" -> 150
            "< 30 min" -> 29
            "30–45 min" -> 45
            "45–60 min" -> 60
            else -> customMaxRuntime.toIntOrNull()
        }

        val minImdb: Double? = when (selectedImdbPreset) {
            "6+" -> 6.0
            "6.5+" -> 6.5
            "7+" -> 7.0
            "7.5+" -> 7.5
            "8+" -> 8.0
            "8.5+" -> 8.5
            "9+" -> 9.0
            else -> null
        }

        val minVotes: Int? = when (selectedVotesPreset) {
            "1,000+" -> 1000
            "5,000+" -> 5000
            "10,000+" -> 10000
            "25,000+" -> 25000
            "50,000+" -> 50000
            "100,000+" -> 100000
            "250,000+" -> 250000
            else -> null
        }

        val minRt: Int? = when (selectedRtPreset) {
            "60%+" -> 60
            "70%+" -> 70
            "80%+" -> 80
            "90%+" -> 90
            else -> null
        }

        val minMeta: Int? = when (selectedMetascorePreset) {
            "50+" -> 50
            "60+" -> 60
            "70+" -> 70
            "80+" -> 80
            "90+" -> 90
            else -> null
        }

        val minSeasons: Int? = when (selectedSeasonsPreset) {
            "1 season" -> 1
            "2+" -> 2
            "3+" -> 3
            "5+" -> 5
            "10+" -> 10
            else -> null
        }
        val maxSeasons: Int? = if (selectedSeasonsPreset == "1 season") 1 else null

        val sort = when (selectedSortMode) {
            "IMDb rating" -> OmdbRecommendationSort.IMDB_RATING
            "Rotten Tomatoes" -> OmdbRecommendationSort.ROTTEN_TOMATOES
            "Metascore" -> OmdbRecommendationSort.METASCORE
            "Newest" -> OmdbRecommendationSort.NEWEST
            "Oldest" -> OmdbRecommendationSort.OLDEST
            "Most IMDb votes" -> OmdbRecommendationSort.MOST_IMDB_VOTES
            else -> OmdbRecommendationSort.BEST_MATCH
        }

        return OmdbRecommendationSpec(
            mediaType = mediaType,
            includedGenres = includedGenres,
            excludedGenres = excludedGenres,
            minimumYear = minYear,
            maximumYear = maxYear,
            minimumRuntimeMinutes = minRuntime,
            maximumRuntimeMinutes = maxRuntime,
            minimumImdbRating = minImdb,
            minimumImdbVotes = minVotes,
            minimumRottenTomatoesRating = minRt,
            minimumMetascore = minMeta,
            contentRatings = selectedContentRatings,
            languages = selectedLanguages,
            minimumSeasons = if (mediaType == MediaType.TV) minSeasons else null,
            maximumSeasons = if (mediaType == MediaType.TV) maxSeasons else null,
            sortMode = sort
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBackgroundBase)
    ) {
        // MANDATORY HEADER: ← Ask Aliflix     BETA       Reset
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AliflixSurfaceElevated,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back to Catalogue",
                            tint = AliflixContentPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ask Aliflix",
                        color = AliflixContentPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AliflixAccentPrimary.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "BETA",
                            color = AliflixAccentPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // MANDATORY Visible Reset Button in Header
                TextButton(onClick = { executeReset() }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset session",
                        tint = AliflixAccentPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        color = AliflixAccentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Compact Summary Bar when results are showing and composer collapsed
        if (state is RecommendationUiState.Results && !showComposer) {
            val spec = lastExecutedSpec ?: buildSpecFromFilters()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { showComposer = true },
                color = AliflixSurfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = spec.summaryLabel(),
                        color = AliflixContentSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit filters",
                            tint = AliflixAccentPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Edit",
                            color = AliflixAccentPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (showComposer) {
            Column(modifier = Modifier.weight(1f)) {
                // Header Mode Tabs: Describe | Similar to | Build with filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Describe", "Similar to", "Build with filters").forEachIndexed { idx, label ->
                        val isSelected = activeMode == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AliflixAccentPrimary else AliflixSurfaceElevated)
                                .clickable { activeMode = idx }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else AliflixContentSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // Media Type Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(MediaType.MOVIE to "Movies", MediaType.TV to "Series").forEach { (type, label) ->
                        val isSelected = mediaType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) AliflixAccentPrimary else AliflixBorderSubtle,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .background(if (isSelected) AliflixAccentPrimary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    mediaType = type
                                    onSelectType(if (type == MediaType.MOVIE) RecommendationMediaKind.MOVIE else RecommendationMediaKind.SERIES)
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) AliflixAccentPrimary else AliflixContentSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Mode Content Body
                Box(modifier = Modifier.weight(1f)) {
                    when (activeMode) {
                        0 -> {
                            // Describe Mode
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                OutlinedTextField(
                                    value = describeText,
                                    onValueChange = { describeText = it },
                                    placeholder = {
                                        Text(
                                            "e.g. action sci fi movies after 2015 rated at least 6 on IMDb",
                                            color = AliflixContentTertiary,
                                            fontSize = 14.sp
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AliflixAccentPrimary,
                                        unfocusedBorderColor = AliflixBorderSubtle,
                                        focusedContainerColor = AliflixSurfaceElevated,
                                        unfocusedContainerColor = AliflixSurfaceElevated,
                                        focusedTextColor = AliflixContentPrimary,
                                        unfocusedTextColor = AliflixContentPrimary
                                    )
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "Quick suggestions:",
                                    color = AliflixContentSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "recent action sci fi movies rated at least 6 on imdb",
                                        "crime drama series written by Vince Gilligan with imdb 8+",
                                        "science fiction but no horror, after 2018, under two hours"
                                    ).forEach { sugg ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(AliflixSurfaceElevated)
                                                .clickable { describeText = sugg }
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = sugg,
                                                color = AliflixContentSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Button(
                                    onClick = {
                                        if (describeText.isNotBlank()) {
                                            val spec = OmdbRecommendationSpec(
                                                mediaType = mediaType,
                                                plotRequirements = listOf(describeText)
                                            )
                                            lastExecutedSpec = spec
                                            onSubmitSpec(spec)
                                            keyboard?.hide()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                                    enabled = describeText.isNotBlank()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Find matches", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        1 -> {
                            // Similar To Mode
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                OutlinedTextField(
                                    value = similarQuery,
                                    onValueChange = {
                                        similarQuery = it
                                        if (selectedAnchor != null) selectedAnchor = null
                                    },
                                    placeholder = {
                                        Text(
                                            "Search title (e.g. Breaking Bad)",
                                            color = AliflixContentTertiary,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Search,
                                            contentDescription = null,
                                            tint = AliflixContentTertiary
                                        )
                                    },
                                    trailingIcon = {
                                        if (suggestionsLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = AliflixAccentPrimary
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AliflixAccentPrimary,
                                        unfocusedBorderColor = AliflixBorderSubtle,
                                        focusedContainerColor = AliflixSurfaceElevated,
                                        unfocusedContainerColor = AliflixSurfaceElevated,
                                        focusedTextColor = AliflixContentPrimary,
                                        unfocusedTextColor = AliflixContentPrimary
                                    )
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                selectedAnchor?.let { anchor ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = AliflixSurfaceElevated,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = anchor.posterPath,
                                                contentDescription = anchor.title,
                                                modifier = Modifier
                                                    .size(48.dp, 72.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(AliflixSurfaceSecondary)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = anchor.title,
                                                    color = AliflixContentPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                anchor.year?.let {
                                                    Text(
                                                        text = "Year: $it",
                                                        color = AliflixContentSecondary,
                                                        fontSize = 12.sp
                                                    )
                                                }
                                            }
                                            IconButton(onClick = { selectedAnchor = null }) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = "Clear anchor",
                                                    tint = AliflixContentTertiary
                                                )
                                            }
                                        }
                                    }
                                } ?: LazyColumn(modifier = Modifier.weight(1f)) {
                                    items(suggestions) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedAnchor = item
                                                    similarQuery = item.title
                                                }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = item.title,
                                                color = AliflixContentPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                            item.year?.let {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "($it)",
                                                    color = AliflixContentTertiary,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        val anchor = selectedAnchor
                                        val anchorTitle = anchor?.title ?: similarQuery.trim()
                                        if (anchorTitle.isNotBlank()) {
                                            val spec = OmdbRecommendationSpec(
                                                mediaType = mediaType,
                                                similarityAnchor = OmdbRecommendationAnchor(
                                                    title = anchorTitle,
                                                    imdbId = anchor?.imdbId,
                                                    mediaType = mediaType
                                                )
                                            )
                                            lastExecutedSpec = spec
                                            onSubmitSpec(spec)
                                            keyboard?.hide()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                                    enabled = selectedAnchor != null || similarQuery.isNotBlank()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Find similar titles ✨", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        2 -> {
                            // Build with Filters Mode
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Selected Filters Top Pill Summary Bar
                                val currentSpec = buildSpecFromFilters()
                                val activePills = mutableListOf<Pair<String, () -> Unit>>()
                                includedGenres.forEach { g -> activePills.add("$g" to { includedGenres = includedGenres - g }) }
                                excludedGenres.forEach { g -> activePills.add("No $g" to { excludedGenres = excludedGenres - g }) }
                                if (selectedYearPreset != "Any") activePills.add(selectedYearPreset to { selectedYearPreset = "Any" })
                                if (selectedRuntimePreset != "Any") activePills.add(selectedRuntimePreset to { selectedRuntimePreset = "Any" })
                                if (selectedImdbPreset != "Any") activePills.add("IMDb $selectedImdbPreset" to { selectedImdbPreset = "Any" })
                                if (selectedRtPreset != "Any") activePills.add("RT $selectedRtPreset" to { selectedRtPreset = "Any" })

                                if (activePills.isNotEmpty()) {
                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(activePills) { (label, onRemove) ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(AliflixAccentPrimary.copy(alpha = 0.2f))
                                                    .clickable { onRemove() }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = label,
                                                        color = AliflixAccentPrimary,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(
                                                        imageVector = Icons.Rounded.Close,
                                                        contentDescription = "Remove",
                                                        tint = AliflixAccentPrimary,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Accordion Sections
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 1. Genres (Included)
                                    item {
                                        FilterSectionHeader(
                                            title = "Genres",
                                            badgeCount = includedGenres.size,
                                            isExpanded = expandedGenres,
                                            onToggle = { expandedGenres = !expandedGenres }
                                        ) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                CANONICAL_OMDB_GENRES.forEach { genre ->
                                                    val isSelected = includedGenres.contains(genre)
                                                    FilterChip(
                                                        label = genre,
                                                        isSelected = isSelected,
                                                        onClick = {
                                                            includedGenres = if (isSelected) includedGenres - genre else includedGenres + genre
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 2. Excluded Genres
                                    item {
                                        FilterSectionHeader(
                                            title = "Exclude genres",
                                            badgeCount = excludedGenres.size,
                                            isExpanded = expandedExcludedGenres,
                                            onToggle = { expandedExcludedGenres = !expandedExcludedGenres }
                                        ) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                CANONICAL_OMDB_GENRES.forEach { genre ->
                                                    val isSelected = excludedGenres.contains(genre)
                                                    FilterChip(
                                                        label = genre,
                                                        isSelected = isSelected,
                                                        onClick = {
                                                            excludedGenres = if (isSelected) excludedGenres - genre else excludedGenres + genre
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 3. Year & Runtime
                                    item {
                                        FilterSectionHeader(
                                            title = "Year & runtime",
                                            badgeCount = (if (selectedYearPreset != "Any") 1 else 0) + (if (selectedRuntimePreset != "Any") 1 else 0),
                                            isExpanded = expandedYearRuntime,
                                            onToggle = { expandedYearRuntime = !expandedYearRuntime }
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Year", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    YEAR_PRESETS.forEach { preset ->
                                                        FilterChip(
                                                            label = preset,
                                                            isSelected = selectedYearPreset == preset,
                                                            onClick = { selectedYearPreset = preset }
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("Runtime", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                val runtimePresets = if (mediaType == MediaType.TV) TV_RUNTIME_PRESETS else MOVIE_RUNTIME_PRESETS
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    runtimePresets.forEach { preset ->
                                                        FilterChip(
                                                            label = preset,
                                                            isSelected = selectedRuntimePreset == preset,
                                                            onClick = { selectedRuntimePreset = preset }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 4. Ratings
                                    item {
                                        FilterSectionHeader(
                                            title = "Ratings",
                                            badgeCount = (if (selectedImdbPreset != "Any") 1 else 0) + (if (selectedRtPreset != "Any") 1 else 0) + (if (selectedMetascorePreset != "Any") 1 else 0),
                                            isExpanded = expandedRatings,
                                            onToggle = { expandedRatings = !expandedRatings }
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("IMDb rating", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    IMDB_RATING_PRESETS.forEach { preset ->
                                                        FilterChip(
                                                            label = preset,
                                                            isSelected = selectedImdbPreset == preset,
                                                            onClick = { selectedImdbPreset = preset }
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("Rotten Tomatoes", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    RT_RATING_PRESETS.forEach { preset ->
                                                        FilterChip(
                                                            label = preset,
                                                            isSelected = selectedRtPreset == preset,
                                                            onClick = { selectedRtPreset = preset }
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("Metascore", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    METASCORE_PRESETS.forEach { preset ->
                                                        FilterChip(
                                                            label = preset,
                                                            isSelected = selectedMetascorePreset == preset,
                                                            onClick = { selectedMetascorePreset = preset }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 5. Content & Language
                                    item {
                                        FilterSectionHeader(
                                            title = "Content rating & language",
                                            badgeCount = selectedContentRatings.size + selectedLanguages.size,
                                            isExpanded = expandedContentLang,
                                            onToggle = { expandedContentLang = !expandedContentLang }
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Content rating", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    CONTENT_RATINGS.forEach { rating ->
                                                        val isSelected = selectedContentRatings.contains(rating)
                                                        FilterChip(
                                                            label = rating,
                                                            isSelected = isSelected,
                                                            onClick = {
                                                                selectedContentRatings = if (isSelected) selectedContentRatings - rating else selectedContentRatings + rating
                                                            }
                                                        )
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text("Language", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    LANGUAGES.forEach { lang ->
                                                        val isSelected = selectedLanguages.contains(lang)
                                                        FilterChip(
                                                            label = lang,
                                                            isSelected = isSelected,
                                                            onClick = {
                                                                selectedLanguages = if (isSelected) selectedLanguages - lang else selectedLanguages + lang
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 6. Series Seasons (Series only)
                                    if (mediaType == MediaType.TV) {
                                        item {
                                            FilterSectionHeader(
                                                title = "Seasons",
                                                badgeCount = if (selectedSeasonsPreset != "Any") 1 else 0,
                                                isExpanded = expandedSeasons,
                                                onToggle = { expandedSeasons = !expandedSeasons }
                                            ) {
                                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    SERIES_SEASONS_PRESETS.forEach { preset ->
                                                        FilterChip(
                                                            label = preset,
                                                            isSelected = selectedSeasonsPreset == preset,
                                                            onClick = { selectedSeasonsPreset = preset }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 7. Sort By
                                    item {
                                        FilterSectionHeader(
                                            title = "Sort by",
                                            badgeCount = if (selectedSortMode != "Best match") 1 else 0,
                                            isExpanded = expandedSort,
                                            onToggle = { expandedSort = !expandedSort }
                                        ) {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                SORT_MODES.forEach { mode ->
                                                    FilterChip(
                                                        label = mode,
                                                        isSelected = selectedSortMode == mode,
                                                        onClick = { selectedSortMode = mode }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // Bottom Find Matches Button
                                Box(modifier = Modifier.padding(16.dp)) {
                                    Button(
                                        onClick = {
                                            val spec = buildSpecFromFilters()
                                            lastExecutedSpec = spec
                                            onSubmitSpec(spec)
                                            keyboard?.hide()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Find matches", fontWeight = FontWeight.Bold)
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
private fun FilterSectionHeader(
    title: String,
    badgeCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = AliflixContentPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (badgeCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(AliflixAccentPrimary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$badgeCount",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AliflixContentSecondary
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (isSelected) AliflixAccentPrimary else AliflixBorderSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .background(if (isSelected) AliflixAccentPrimary.copy(alpha = 0.2f) else AliflixSurfaceSecondary)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) AliflixAccentPrimary else AliflixContentSecondary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}
