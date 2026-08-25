@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.recommendation.AnimationFilter
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.RecommendationSort
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary

@Composable
fun AskAliflixFilters(
    spec: CatalogDiscoverySpec,
    onSpecChanged: (CatalogDiscoverySpec) -> Unit,
    onSubmit: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    var genresOpen by rememberSaveable { mutableStateOf(true) }
    var exclusionsOpen by rememberSaveable { mutableStateOf(false) }
    var yearRuntimeOpen by rememberSaveable { mutableStateOf(false) }
    var ratingOpen by rememberSaveable { mutableStateOf(false) }
    var regionOpen by rememberSaveable { mutableStateOf(false) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }

    val genres = askTmdbGenres(spec.mediaKind).map(AskTmdbGenre::name)
    val genreChoices = askGenreChoices(spec.mediaKind)
    val selectedGenreChoices = spec.includedGenres.filterNot { it == "Animation" } +
        listOfNotNull(spec.animationFilter?.label)
    val activeCount = selectedFilterCount(spec)
    val hasFilters = activeCount > 0

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Filters",
                    color = AliflixContentPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.4).sp,
                )
                if (hasFilters) {
                    Text("$activeCount selected", color = AliflixContentTertiary, fontSize = 11.sp)
                }
            }
            AnimatedVisibility(visible = hasFilters, enter = fadeIn(), exit = fadeOut()) {
                TextButton(onClick = { onSpecChanged(spec.clearAskFilters()) }) {
                    Text("Clear all", color = AliflixAccentSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                FilterSection(
                    title = "Genres",
                    icon = Icons.Rounded.Movie,
                    badgeCount = spec.includedGenres.size + if (spec.animationFilter != null) 1 else 0,
                    expanded = genresOpen,
                    onToggle = { genresOpen = !genresOpen },
                ) {
                    FilterSubheading("TMDB GENRES")
                    FilterChipGrid(
                        values = genreChoices,
                        selected = selectedGenreChoices,
                        onToggle = { choice ->
                            val animationChoice = AnimationFilter.entries.firstOrNull { it.label == choice }
                            if (animationChoice != null) {
                                val next = animationChoice.takeUnless { spec.animationFilter == animationChoice }
                                onSpecChanged(spec.copy(
                                    animationFilter = next,
                                    includedGenres = spec.includedGenres - "Animation",
                                    excludedGenres = spec.excludedGenres - "Animation",
                                    originalLanguage = if (next != null) null else spec.originalLanguage,
                                    countries = if (next != null) emptyList() else spec.countries,
                                ))
                            } else {
                                val next = spec.includedGenres.toggle(choice)
                                onSpecChanged(spec.copy(
                                    includedGenres = next,
                                    excludedGenres = spec.excludedGenres - choice,
                                ))
                            }
                        },
                    )
                }
            }

            item {
                FilterSection(
                    title = "Avoid genres",
                    icon = Icons.Rounded.Close,
                    badgeCount = spec.excludedGenres.size,
                    expanded = exclusionsOpen,
                    onToggle = { exclusionsOpen = !exclusionsOpen },
                ) {
                    FilterChipGrid(
                        values = genres,
                        selected = spec.excludedGenres,
                        onToggle = { genre ->
                            val next = spec.excludedGenres.toggle(genre)
                            onSpecChanged(spec.copy(
                                excludedGenres = next,
                                includedGenres = spec.includedGenres - genre,
                                animationFilter = if (genre == "Animation") null else spec.animationFilter,
                            ))
                        },
                    )
                }
            }

            item {
                FilterSection(
                    title = "Year & runtime",
                    icon = Icons.Rounded.CalendarMonth,
                    badgeCount = listOf(
                        spec.yearMinimum != null || spec.yearMaximum != null,
                        spec.runtimeMinimumMinutes != null || spec.runtimeMaximumMinutes != null,
                    ).count { it },
                    expanded = yearRuntimeOpen,
                    onToggle = { yearRuntimeOpen = !yearRuntimeOpen },
                ) {
                    FilterSubheading("RELEASE YEAR")
                    FilterPresetGrid(
                        presets = listOf(
                            "Any" to (null to null),
                            "2020+" to (2020 to null),
                            "2015+" to (2015 to null),
                            "2010+" to (2010 to null),
                            "2000+" to (2000 to null),
                            "Before 2000" to (null to 1999),
                        ),
                        selected = spec.yearMinimum to spec.yearMaximum,
                        onSelect = { (min, max) -> onSpecChanged(spec.copy(yearMinimum = min, yearMaximum = max)) },
                    )
                    Spacer(Modifier.height(13.dp))
                    FilterSubheading(if (spec.mediaKind == RecommendationMediaKind.SERIES) "EPISODE RUNTIME" else "RUNTIME")
                    val runtimePresets = if (spec.mediaKind == RecommendationMediaKind.SERIES) {
                        listOf(
                            "Any" to (null to null), "Under 30 min" to (null to 29), "30–45 min" to (30 to 45),
                            "45–60 min" to (45 to 60), "60+ min" to (60 to null),
                        )
                    } else {
                        listOf(
                            "Any" to (null to null), "Under 90 min" to (null to 89), "90–120 min" to (90 to 120),
                            "120–150 min" to (120 to 150), "150+ min" to (150 to null),
                        )
                    }
                    FilterPresetGrid(
                        presets = runtimePresets,
                        selected = spec.runtimeMinimumMinutes to spec.runtimeMaximumMinutes,
                        onSelect = { (min, max) -> onSpecChanged(spec.copy(runtimeMinimumMinutes = min, runtimeMaximumMinutes = max)) },
                    )
                }
            }

            item {
                FilterSection(
                    title = "TMDB rating",
                    icon = Icons.Rounded.Star,
                    badgeCount = if (spec.minimumTmdb != null) 1 else 0,
                    expanded = ratingOpen,
                    onToggle = { ratingOpen = !ratingOpen },
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("Any" to null, "6+" to 6.0, "6.5+" to 6.5, "7+" to 7.0, "7.5+" to 7.5, "8+" to 8.0, "8.5+" to 8.5).forEach { (label, value) ->
                            AskAliflixChip(label, spec.minimumTmdb == value, { onSpecChanged(spec.copy(minimumTmdb = value)) })
                        }
                    }
                }
            }

            item {
                FilterSection(
                    title = "Language & country",
                    icon = Icons.Rounded.Language,
                    badgeCount = (if (spec.originalLanguage != null) 1 else 0) + spec.countries.size,
                    expanded = regionOpen,
                    onToggle = { regionOpen = !regionOpen },
                ) {
                    FilterSubheading("ORIGINAL LANGUAGE")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ASK_LANGUAGES.forEach { option ->
                            AskAliflixChip(
                                label = option.label,
                                isSelected = spec.originalLanguage == option.code,
                                onClick = {
                                    onSpecChanged(spec.copy(
                                        originalLanguage = option.code,
                                        includedGenres = if (spec.animationFilter != null) {
                                            (spec.includedGenres + "Animation").distinct()
                                        } else {
                                            spec.includedGenres
                                        },
                                        animationFilter = null,
                                    ))
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                    FilterSubheading("ORIGIN COUNTRY")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ASK_COUNTRIES.forEach { option ->
                            val selected = if (option.code == null) spec.countries.isEmpty() else option.code in spec.countries
                            AskAliflixChip(
                                label = option.label,
                                isSelected = selected,
                                onClick = {
                                    val next = if (option.code == null) emptyList() else spec.countries.toggle(option.code)
                                    onSpecChanged(spec.copy(
                                        countries = next,
                                        includedGenres = if (spec.animationFilter != null) {
                                            (spec.includedGenres + "Animation").distinct()
                                        } else {
                                            spec.includedGenres
                                        },
                                        animationFilter = null,
                                    ))
                                },
                            )
                        }
                    }
                }
            }

            item {
                val availableSorts = RecommendationSort.entries.filter {
                    it != RecommendationSort.RUNTIME_SHORT_TO_LONG || spec.mediaKind == RecommendationMediaKind.MOVIE
                }
                FilterSection(
                    title = "Sort by",
                    icon = Icons.Rounded.Tune,
                    badgeCount = if (spec.sortBy == RecommendationSort.MOST_POPULAR) 0 else 1,
                    expanded = sortOpen,
                    onToggle = { sortOpen = !sortOpen },
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        availableSorts.forEach { sort ->
                            AskAliflixChip(
                                label = sort.label,
                                isSelected = spec.sortBy == sort,
                                onClick = { onSpecChanged(spec.copy(sortBy = sort)) },
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }

        AskAliflixStickyCta(
            label = if (loading) "Loading…" else "Show matches",
            enabled = !loading,
            loading = loading,
            onClick = onSubmit,
        )
    }
}

@Composable
private fun FilterSection(
    title: String,
    icon: ImageVector,
    badgeCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        AskAliflixMotion.chipSpec(),
        label = "ask-filter-chevron",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(AskAliflixMotion.smallContentSpec()),
        color = AliflixSurfaceElevated.copy(alpha = 0.78f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (expanded) AliflixAccentPrimary.copy(alpha = 0.36f) else AliflixBorderSubtle,
        ),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(AliflixAccentPrimary.copy(alpha = 0.28f), AliflixSurfaceSecondary)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = AliflixAccentSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    color = AliflixContentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(AliflixAccentPrimary)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$badgeCount", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = AliflixContentSecondary,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Spacer(Modifier.height(13.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun FilterChipGrid(values: List<String>, selected: List<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.forEach { value -> AskAliflixChip(value, value in selected, { onToggle(value) }) }
    }
}

@Composable
private fun FilterPresetGrid(
    presets: List<Pair<String, Pair<Int?, Int?>>>,
    selected: Pair<Int?, Int?>,
    onSelect: (Pair<Int?, Int?>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        presets.forEach { (label, value) -> AskAliflixChip(label, selected == value, { onSelect(value) }) }
    }
}

@Composable
private fun FilterSubheading(text: String) {
    Text(
        text = text,
        color = AliflixContentTertiary,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.9.sp,
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

private data class FilterOption(val label: String, val code: String?)

internal fun CatalogDiscoverySpec.askFilterSummary(): String {
    val values = buildList {
        animationFilter?.let { add(it.label) }
        if (includedGenres.isNotEmpty()) add(includedGenres.joinToString(", "))
        if (excludedGenres.isNotEmpty()) add("Avoid ${excludedGenres.joinToString(", ")}")
        when {
            yearMinimum != null && yearMaximum != null -> add("Years $yearMinimum-$yearMaximum")
            yearMinimum != null -> add("Years $yearMinimum+")
            yearMaximum != null -> add("Through $yearMaximum")
        }
        when {
            runtimeMinimumMinutes != null && runtimeMaximumMinutes != null ->
                add("Runtime $runtimeMinimumMinutes-$runtimeMaximumMinutes min")
            runtimeMinimumMinutes != null -> add("Runtime $runtimeMinimumMinutes+ min")
            runtimeMaximumMinutes != null -> add("Runtime up to $runtimeMaximumMinutes min")
        }
        minimumTmdb?.let { add("TMDB ${it.toString().removeSuffix(".0")}+") }
        requiredStatus?.let { add(it) }
        originalLanguage?.let { code ->
            add(ASK_LANGUAGES.firstOrNull { it.code == code }?.label ?: code.uppercase())
        }
        if (countries.isNotEmpty()) {
            add(countries.joinToString(", ") { code ->
                ASK_COUNTRIES.firstOrNull { it.code == code }?.label ?: code
            })
        }
        add("Sort: ${sortBy.label}")
    }
    return values.joinToString(" / ").ifBlank { "No filters selected" }
}

private fun selectedFilterCount(spec: CatalogDiscoverySpec): Int =
    spec.includedGenres.size + spec.excludedGenres.size + (if (spec.animationFilter != null) 1 else 0) +
        listOf(
            spec.yearMinimum != null || spec.yearMaximum != null,
            spec.runtimeMinimumMinutes != null || spec.runtimeMaximumMinutes != null,
            spec.minimumTmdb != null,
            spec.originalLanguage != null,
            spec.requiredStatus != null,
            spec.sortBy != RecommendationSort.MOST_POPULAR,
        ).count { it } + spec.countries.size

private fun CatalogDiscoverySpec.clearAskFilters() = copy(
    includedGenres = emptyList(),
    excludedGenres = emptyList(),
    runtimeMinimumMinutes = null,
    runtimeMaximumMinutes = null,
    yearMinimum = null,
    yearMaximum = null,
    minimumTmdb = null,
    originalLanguage = null,
    animationFilter = null,
    requiredStatus = null,
    countries = emptyList(),
    sortBy = RecommendationSort.MOST_POPULAR,
)

private fun <T> List<T>.toggle(value: T): List<T> = if (value in this) this - value else this + value

private val ASK_LANGUAGES = listOf(
    FilterOption("Any", null), FilterOption("English", "en"), FilterOption("Korean", "ko"),
    FilterOption("Japanese", "ja"), FilterOption("Hindi", "hi"), FilterOption("Spanish", "es"),
    FilterOption("French", "fr"), FilterOption("German", "de"), FilterOption("Chinese", "zh"),
)

private val ASK_COUNTRIES = listOf(
    FilterOption("Any", null), FilterOption("United States", "US"), FilterOption("South Korea", "KR"),
    FilterOption("Japan", "JP"), FilterOption("India", "IN"), FilterOption("United Kingdom", "GB"),
    FilterOption("France", "FR"), FilterOption("Germany", "DE"),
)

internal data class AskTmdbGenre(val id: Int, val name: String)

internal fun askTmdbGenres(mediaKind: RecommendationMediaKind): List<AskTmdbGenre> =
    if (mediaKind == RecommendationMediaKind.SERIES) ASK_TMDB_TV_GENRES else ASK_TMDB_MOVIE_GENRES

internal fun askGenreChoices(mediaKind: RecommendationMediaKind): List<String> =
    askTmdbGenres(mediaKind).flatMap { genre ->
        if (genre.name == "Animation") AnimationFilter.entries.map(AnimationFilter::label) else listOf(genre.name)
    }

private val ASK_TMDB_MOVIE_GENRES = listOf(
    AskTmdbGenre(28, "Action"), AskTmdbGenre(12, "Adventure"), AskTmdbGenre(16, "Animation"),
    AskTmdbGenre(35, "Comedy"), AskTmdbGenre(80, "Crime"), AskTmdbGenre(99, "Documentary"),
    AskTmdbGenre(18, "Drama"), AskTmdbGenre(10751, "Family"), AskTmdbGenre(14, "Fantasy"),
    AskTmdbGenre(36, "History"), AskTmdbGenre(27, "Horror"), AskTmdbGenre(10402, "Music"),
    AskTmdbGenre(9648, "Mystery"), AskTmdbGenre(10749, "Romance"), AskTmdbGenre(878, "Science Fiction"),
    AskTmdbGenre(10770, "TV Movie"), AskTmdbGenre(53, "Thriller"), AskTmdbGenre(10752, "War"),
    AskTmdbGenre(37, "Western"),
)

private val ASK_TMDB_TV_GENRES = listOf(
    AskTmdbGenre(10759, "Action & Adventure"), AskTmdbGenre(16, "Animation"), AskTmdbGenre(35, "Comedy"),
    AskTmdbGenre(80, "Crime"), AskTmdbGenre(99, "Documentary"), AskTmdbGenre(18, "Drama"),
    AskTmdbGenre(10751, "Family"), AskTmdbGenre(10762, "Kids"), AskTmdbGenre(9648, "Mystery"),
    AskTmdbGenre(10763, "News"), AskTmdbGenre(10764, "Reality"), AskTmdbGenre(10765, "Sci-Fi & Fantasy"),
    AskTmdbGenre(10766, "Soap"), AskTmdbGenre(10767, "Talk"), AskTmdbGenre(10768, "War & Politics"),
    AskTmdbGenre(37, "Western"),
)
