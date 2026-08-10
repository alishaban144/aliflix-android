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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Star
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
import com.aliflix.app.recommendation.RecommendationMediaKind
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

    val genres = if (spec.mediaKind == RecommendationMediaKind.SERIES) ASK_TMDB_TV_GENRES else ASK_TMDB_MOVIE_GENRES
    val activePills = activeFilterPills(spec, onSpecChanged)
    val activeCount = activePills.size
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

        AnimatedVisibility(
            visible = activePills.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(activePills, key = { it.label }) { pill ->
                    Row(
                        modifier = Modifier
                            .animateItem()
                            .clip(RoundedCornerShape(14.dp))
                            .background(AliflixAccentPrimary.copy(alpha = 0.18f))
                            .border(1.dp, AliflixAccentPrimary.copy(alpha = 0.48f), RoundedCornerShape(14.dp))
                            .clickable(onClick = pill.onRemove)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(pill.label, color = AliflixContentPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Icon(Icons.Rounded.Close, contentDescription = "Remove ${pill.label}", tint = AliflixAccentSecondary, modifier = Modifier.size(13.dp))
                    }
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
                    badgeCount = spec.includedGenres.size,
                    expanded = genresOpen,
                    onToggle = { genresOpen = !genresOpen },
                ) {
                    FilterChipGrid(
                        values = genres,
                        selected = spec.includedGenres,
                        onToggle = { genre ->
                            val next = spec.includedGenres.toggle(genre)
                            onSpecChanged(spec.copy(includedGenres = next, excludedGenres = spec.excludedGenres - genre))
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
                            onSpecChanged(spec.copy(excludedGenres = next, includedGenres = spec.includedGenres - genre))
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
                                onClick = { onSpecChanged(spec.copy(originalLanguage = option.code)) },
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
                                    onSpecChanged(spec.copy(countries = next))
                                },
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
        }

        AskAliflixStickyCta(
            label = if (loading) "Loading…" else "Show matches",
            enabled = hasFilters,
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

private data class FilterPill(val label: String, val onRemove: () -> Unit)
private data class FilterOption(val label: String, val code: String?)

private fun activeFilterPills(spec: CatalogDiscoverySpec, onChange: (CatalogDiscoverySpec) -> Unit): List<FilterPill> = buildList {
    spec.includedGenres.forEach { genre -> add(FilterPill(genre) { onChange(spec.copy(includedGenres = spec.includedGenres - genre)) }) }
    spec.excludedGenres.forEach { genre -> add(FilterPill("No $genre") { onChange(spec.copy(excludedGenres = spec.excludedGenres - genre)) }) }
    if (spec.yearMinimum != null || spec.yearMaximum != null) {
        val label = when {
            spec.yearMinimum != null && spec.yearMaximum != null -> "${spec.yearMinimum}–${spec.yearMaximum}"
            spec.yearMinimum != null -> "${spec.yearMinimum}+"
            else -> "Before ${spec.yearMaximum!! + 1}"
        }
        add(FilterPill(label) { onChange(spec.copy(yearMinimum = null, yearMaximum = null)) })
    }
    if (spec.runtimeMinimumMinutes != null || spec.runtimeMaximumMinutes != null) {
        val label = when {
            spec.runtimeMinimumMinutes != null && spec.runtimeMaximumMinutes != null -> "${spec.runtimeMinimumMinutes}–${spec.runtimeMaximumMinutes} min"
            spec.runtimeMinimumMinutes != null -> "${spec.runtimeMinimumMinutes}+ min"
            else -> "Under ${spec.runtimeMaximumMinutes!! + 1} min"
        }
        add(FilterPill(label) { onChange(spec.copy(runtimeMinimumMinutes = null, runtimeMaximumMinutes = null)) })
    }
    spec.minimumTmdb?.let { rating -> add(FilterPill("TMDB $rating+") { onChange(spec.copy(minimumTmdb = null)) }) }
    spec.originalLanguage?.let { code ->
        val label = ASK_LANGUAGES.firstOrNull { it.code == code }?.label ?: code.uppercase()
        add(FilterPill(label) { onChange(spec.copy(originalLanguage = null)) })
    }
    spec.countries.forEach { code ->
        val label = ASK_COUNTRIES.firstOrNull { it.code == code }?.label ?: code
        add(FilterPill(label) { onChange(spec.copy(countries = spec.countries - code)) })
    }
}

private fun CatalogDiscoverySpec.clearAskFilters() = copy(
    includedGenres = emptyList(),
    excludedGenres = emptyList(),
    runtimeMinimumMinutes = null,
    runtimeMaximumMinutes = null,
    yearMinimum = null,
    yearMaximum = null,
    minimumTmdb = null,
    originalLanguage = null,
    countries = emptyList(),
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

private val ASK_TMDB_MOVIE_GENRES = listOf(
    "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
    "History", "Horror", "Music", "Mystery", "Romance", "Science Fiction", "TV Movie", "Thriller", "War", "Western",
)

private val ASK_TMDB_TV_GENRES = listOf(
    "Action & Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Kids", "Mystery",
    "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics", "Western",
)
