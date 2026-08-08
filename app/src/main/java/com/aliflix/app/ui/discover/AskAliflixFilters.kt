@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.omdb.OmdbGenre
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSort
import com.aliflix.app.recommendation.omdb.OmdbRecommendationSpec
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAliflixFilters(
    spec: OmdbRecommendationSpec,
    onSpecChanged: (OmdbRecommendationSpec) -> Unit,
    onSubmit: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    // Accordion expand states
    var expGenres by rememberSaveable { mutableStateOf(true) }
    var expExcludedGenres by rememberSaveable { mutableStateOf(false) }
    var expYearRuntime by rememberSaveable { mutableStateOf(false) }
    var expRatings by rememberSaveable { mutableStateOf(false) }
    var expContentLang by rememberSaveable { mutableStateOf(false) }
    var expSeasons by rememberSaveable { mutableStateOf(false) }
    var expSort by rememberSaveable { mutableStateOf(false) }

    // Active pill builder
    val activePills = rememberActivePills(spec, onSpecChanged)

    val isFilterActive = spec.includedGenres.isNotEmpty() ||
        spec.excludedGenres.isNotEmpty() ||
        spec.minimumYear != null ||
        spec.maximumYear != null ||
        spec.minimumRuntimeMinutes != null ||
        spec.maximumRuntimeMinutes != null ||
        spec.minimumImdbRating != null ||
        spec.minimumImdbVotes != null ||
        spec.minimumRottenTomatoesRating != null ||
        spec.minimumMetascore != null ||
        spec.contentRatings.isNotEmpty() ||
        spec.languages.isNotEmpty() ||
        spec.minimumSeasons != null ||
        spec.maximumSeasons != null

    Column(modifier = modifier.fillMaxSize()) {
        // Selected Filter Pills Horizontal Strip
        AnimatedVisibility(
            visible = activePills.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(activePills, key = { it.first }) { (label, onRemove) ->
                    Box(
                        modifier = Modifier
                            .animateItem()
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
                                contentDescription = "Remove filter",
                                tint = AliflixAccentPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Filter Categories Accordion List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Genres
            item {
                FilterCategoryHeader(
                    title = "Genres",
                    badgeCount = spec.includedGenres.size,
                    isExpanded = expGenres,
                    onToggle = { expGenres = !expGenres }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CANONICAL_OMDB_GENRES.forEach { genre ->
                            val isSel = spec.includedGenres.contains(genre)
                            AskAliflixChip(
                                label = genre,
                                isSelected = isSel,
                                onClick = {
                                    val next = if (isSel) spec.includedGenres - genre else spec.includedGenres + genre
                                    onSpecChanged(spec.copy(includedGenres = next))
                                }
                            )
                        }
                    }
                }
            }

            // 2. Exclude Genres
            item {
                FilterCategoryHeader(
                    title = "Exclude genres",
                    badgeCount = spec.excludedGenres.size,
                    isExpanded = expExcludedGenres,
                    onToggle = { expExcludedGenres = !expExcludedGenres }
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CANONICAL_OMDB_GENRES.forEach { genre ->
                            val isSel = spec.excludedGenres.contains(genre)
                            AskAliflixChip(
                                label = genre,
                                isSelected = isSel,
                                onClick = {
                                    val next = if (isSel) spec.excludedGenres - genre else spec.excludedGenres + genre
                                    onSpecChanged(spec.copy(excludedGenres = next))
                                }
                            )
                        }
                    }
                }
            }

            // 3. Year & Runtime
            item {
                FilterCategoryHeader(
                    title = "Year & runtime",
                    badgeCount = (if (spec.minimumYear != null || spec.maximumYear != null) 1 else 0) +
                        (if (spec.minimumRuntimeMinutes != null || spec.maximumRuntimeMinutes != null) 1 else 0),
                    isExpanded = expYearRuntime,
                    onToggle = { expYearRuntime = !expYearRuntime }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Release year", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val yearPresets = listOf(
                                "Any" to (null to null),
                                "2020+" to (2020 to null),
                                "2015+" to (2015 to null),
                                "2010+" to (2010 to null),
                                "2000+" to (2000 to null),
                                "Before 2000" to (null to 1999)
                            )
                            yearPresets.forEach { (label, bounds) ->
                                val isSel = spec.minimumYear == bounds.first && spec.maximumYear == bounds.second
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = {
                                        onSpecChanged(spec.copy(minimumYear = bounds.first, maximumYear = bounds.second))
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Runtime", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        val runtimePresets = if (spec.mediaType == MediaType.TV) {
                            listOf(
                                "Any" to (null to null),
                                "< 30 min" to (null to 29),
                                "30–45 min" to (30 to 45),
                                "45–60 min" to (45 to 60),
                                "60+ min" to (60 to null)
                            )
                        } else {
                            listOf(
                                "Any" to (null to null),
                                "< 90 min" to (null to 89),
                                "< 120 min" to (null to 119),
                                "90–120 min" to (90 to 120),
                                "120–150 min" to (120 to 150),
                                "150+ min" to (150 to null)
                            )
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            runtimePresets.forEach { (label, bounds) ->
                                val isSel = spec.minimumRuntimeMinutes == bounds.first && spec.maximumRuntimeMinutes == bounds.second
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = {
                                        onSpecChanged(spec.copy(minimumRuntimeMinutes = bounds.first, maximumRuntimeMinutes = bounds.second))
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 4. Ratings
            item {
                FilterCategoryHeader(
                    title = "Ratings",
                    badgeCount = (if (spec.minimumImdbRating != null) 1 else 0) +
                        (if (spec.minimumRottenTomatoesRating != null) 1 else 0) +
                        (if (spec.minimumMetascore != null) 1 else 0),
                    isExpanded = expRatings,
                    onToggle = { expRatings = !expRatings }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("IMDb rating", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Any" to null,
                                "6+" to 6.0,
                                "6.5+" to 6.5,
                                "7+" to 7.0,
                                "7.5+" to 7.5,
                                "8+" to 8.0,
                                "8.5+" to 8.5,
                                "9+" to 9.0
                            ).forEach { (label, rating) ->
                                val isSel = spec.minimumImdbRating == rating
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = { onSpecChanged(spec.copy(minimumImdbRating = rating)) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Rotten Tomatoes", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Any" to null,
                                "60%+" to 60,
                                "70%+" to 70,
                                "80%+" to 80,
                                "90%+" to 90
                            ).forEach { (label, rt) ->
                                val isSel = spec.minimumRottenTomatoesRating == rt
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = { onSpecChanged(spec.copy(minimumRottenTomatoesRating = rt)) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Metascore", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Any" to null,
                                "50+" to 50,
                                "60+" to 60,
                                "70+" to 70,
                                "80+" to 80,
                                "90+" to 90
                            ).forEach { (label, meta) ->
                                val isSel = spec.minimumMetascore == meta
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = { onSpecChanged(spec.copy(minimumMetascore = meta)) }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Content & Language
            item {
                FilterCategoryHeader(
                    title = "Content rating & language",
                    badgeCount = spec.contentRatings.size + spec.languages.size,
                    isExpanded = expContentLang,
                    onToggle = { expContentLang = !expContentLang }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Content rating", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CONTENT_RATINGS.forEach { rating ->
                                val isSel = spec.contentRatings.contains(rating)
                                AskAliflixChip(
                                    label = rating,
                                    isSelected = isSel,
                                    onClick = {
                                        val next = if (isSel) spec.contentRatings - rating else spec.contentRatings + rating
                                        onSpecChanged(spec.copy(contentRatings = next))
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Language", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LANGUAGES.forEach { lang ->
                                val isSel = spec.languages.contains(lang)
                                AskAliflixChip(
                                    label = lang,
                                    isSelected = isSel,
                                    onClick = {
                                        val next = if (isSel) spec.languages - lang else spec.languages + lang
                                        onSpecChanged(spec.copy(languages = next))
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Series Seasons (Series only)
            if (spec.mediaType == MediaType.TV) {
                item {
                    FilterCategoryHeader(
                        title = "Seasons",
                        badgeCount = if (spec.minimumSeasons != null || spec.maximumSeasons != null) 1 else 0,
                        isExpanded = expSeasons,
                        onToggle = { expSeasons = !expSeasons }
                    ) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Any" to (null to null),
                                "1 season" to (1 to 1),
                                "2+" to (2 to null),
                                "3+" to (3 to null),
                                "5+" to (5 to null),
                                "10+" to (10 to null)
                            ).forEach { (label, bounds) ->
                                val isSel = spec.minimumSeasons == bounds.first && spec.maximumSeasons == bounds.second
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = { onSpecChanged(spec.copy(minimumSeasons = bounds.first, maximumSeasons = bounds.second)) }
                                )
                            }
                        }
                    }
                }
            }

            // 7. Sort By
            item {
                FilterCategoryHeader(
                    title = "Sort by",
                    badgeCount = if (spec.sortMode != OmdbRecommendationSort.BEST_MATCH) 1 else 0,
                    isExpanded = expSort,
                    onToggle = { expSort = !expSort }
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "Best match" to OmdbRecommendationSort.BEST_MATCH,
                            "IMDb rating" to OmdbRecommendationSort.IMDB_RATING,
                            "Rotten Tomatoes" to OmdbRecommendationSort.ROTTEN_TOMATOES,
                            "Metascore" to OmdbRecommendationSort.METASCORE,
                            "Newest" to OmdbRecommendationSort.NEWEST,
                            "Oldest" to OmdbRecommendationSort.OLDEST,
                            "Most IMDb votes" to OmdbRecommendationSort.MOST_IMDB_VOTES
                        ).forEach { (label, sort) ->
                            val isSel = spec.sortMode == sort
                            AskAliflixChip(
                                label = label,
                                isSelected = isSel,
                                onClick = { onSpecChanged(spec.copy(sortMode = sort)) }
                            )
                        }
                    }
                }
            }
        }

        // Sticky Primary CTA
        AskAliflixStickyCta(
            label = if (loading) "Finding matches…" else "Find matches",
            enabled = isFilterActive,
            loading = loading,
            onClick = onSubmit
        )
    }
}

@Composable
private fun FilterCategoryHeader(
    title: String,
    badgeCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = AskAliflixMotion.chipSpec(),
        label = "chevron-rotate"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = AskAliflixMotion.smallContentSpec()),
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(10.dp)
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
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = AliflixContentSecondary,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation }
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun rememberActivePills(
    spec: OmdbRecommendationSpec,
    onSpecChanged: (OmdbRecommendationSpec) -> Unit
): List<Pair<String, () -> Unit>> {
    val list = mutableListOf<Pair<String, () -> Unit>>()
    spec.includedGenres.forEach { g ->
        list.add(g to { onSpecChanged(spec.copy(includedGenres = spec.includedGenres - g)) })
    }
    spec.excludedGenres.forEach { g ->
        list.add("No $g" to { onSpecChanged(spec.copy(excludedGenres = spec.excludedGenres - g)) })
    }
    if (spec.minimumYear != null || spec.maximumYear != null) {
        val label = when {
            spec.minimumYear != null && spec.maximumYear != null -> "${spec.minimumYear}–${spec.maximumYear}"
            spec.minimumYear != null -> "${spec.minimumYear}+"
            else -> "Before ${spec.maximumYear!! + 1}"
        }
        list.add(label to { onSpecChanged(spec.copy(minimumYear = null, maximumYear = null)) })
    }
    if (spec.minimumRuntimeMinutes != null || spec.maximumRuntimeMinutes != null) {
        val label = when {
            spec.minimumRuntimeMinutes != null && spec.maximumRuntimeMinutes != null -> "${spec.minimumRuntimeMinutes}–${spec.maximumRuntimeMinutes} min"
            spec.minimumRuntimeMinutes != null -> "${spec.minimumRuntimeMinutes}+ min"
            else -> "< ${spec.maximumRuntimeMinutes!! + 1} min"
        }
        list.add(label to { onSpecChanged(spec.copy(minimumRuntimeMinutes = null, maximumRuntimeMinutes = null)) })
    }
    if (spec.minimumImdbRating != null) {
        list.add("IMDb ${spec.minimumImdbRating}+" to { onSpecChanged(spec.copy(minimumImdbRating = null)) })
    }
    if (spec.minimumRottenTomatoesRating != null) {
        list.add("RT ${spec.minimumRottenTomatoesRating}%+" to { onSpecChanged(spec.copy(minimumRottenTomatoesRating = null)) })
    }
    if (spec.minimumMetascore != null) {
        list.add("Meta ${spec.minimumMetascore}+" to { onSpecChanged(spec.copy(minimumMetascore = null)) })
    }
    spec.contentRatings.forEach { cr ->
        list.add(cr to { onSpecChanged(spec.copy(contentRatings = spec.contentRatings - cr)) })
    }
    spec.languages.forEach { lang ->
        list.add(lang to { onSpecChanged(spec.copy(languages = spec.languages - lang)) })
    }
    if (spec.minimumSeasons != null || spec.maximumSeasons != null) {
        val label = if (spec.minimumSeasons == 1 && spec.maximumSeasons == 1) "1 season" else "${spec.minimumSeasons}+ seasons"
        list.add(label to { onSpecChanged(spec.copy(minimumSeasons = null, maximumSeasons = null)) })
    }
    return list
}
