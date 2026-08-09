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
import com.aliflix.app.recommendation.RecommendationMediaKind
import com.aliflix.app.recommendation.CatalogDiscoverySpec
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAliflixFilters(
    spec: CatalogDiscoverySpec,
    onSpecChanged: (CatalogDiscoverySpec) -> Unit,
    onSubmit: () -> Unit,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    // Accordion expand states
    var expGenres by rememberSaveable { mutableStateOf(true) }
    var expExcludedGenres by rememberSaveable { mutableStateOf(false) }
    var expYearRuntime by rememberSaveable { mutableStateOf(false) }
    var expRatings by rememberSaveable { mutableStateOf(false) }

    // Active pill builder
    val activePills = rememberActivePills(spec, onSpecChanged)

    val isFilterActive = spec.includedGenres.isNotEmpty() ||
        spec.excludedGenres.isNotEmpty() ||
        spec.yearMinimum != null ||
        spec.yearMaximum != null ||
        spec.runtimeMinimumMinutes != null ||
        spec.runtimeMaximumMinutes != null ||
        spec.minimumImdb != null ||
        spec.minimumRottenTomatoes != null ||
        spec.originalLanguage != null

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
                    badgeCount = (if (spec.yearMinimum != null || spec.yearMaximum != null) 1 else 0) +
                        (if (spec.runtimeMinimumMinutes != null || spec.runtimeMaximumMinutes != null) 1 else 0),
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
                                val isSel = spec.yearMinimum == bounds.first && spec.yearMaximum == bounds.second
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = {
                                        onSpecChanged(spec.copy(yearMinimum = bounds.first, yearMaximum = bounds.second))
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Runtime", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        val runtimePresets = if (spec.mediaKind == RecommendationMediaKind.SERIES) {
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
                                val isSel = spec.runtimeMinimumMinutes == bounds.first && spec.runtimeMaximumMinutes == bounds.second
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = {
                                        onSpecChanged(spec.copy(runtimeMinimumMinutes = bounds.first, runtimeMaximumMinutes = bounds.second))
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
                    badgeCount = (if (spec.minimumImdb != null) 1 else 0) +
                        (if (spec.minimumRottenTomatoes != null) 1 else 0),
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
                                val isSel = spec.minimumImdb == rating
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = { onSpecChanged(spec.copy(minimumImdb = rating)) }
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
                                val isSel = spec.minimumRottenTomatoes == rt
                                AskAliflixChip(
                                    label = label,
                                    isSelected = isSel,
                                    onClick = { onSpecChanged(spec.copy(minimumRottenTomatoes = rt)) }
                                )
                            }
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
    spec: CatalogDiscoverySpec,
    onSpecChanged: (CatalogDiscoverySpec) -> Unit
): List<Pair<String, () -> Unit>> {
    val list = mutableListOf<Pair<String, () -> Unit>>()
    spec.includedGenres.forEach { g ->
        list.add(g to { onSpecChanged(spec.copy(includedGenres = spec.includedGenres - g)) })
    }
    spec.excludedGenres.forEach { g ->
        list.add("No $g" to { onSpecChanged(spec.copy(excludedGenres = spec.excludedGenres - g)) })
    }
    if (spec.yearMinimum != null || spec.yearMaximum != null) {
        val label = when {
            spec.yearMinimum != null && spec.yearMaximum != null -> "${spec.yearMinimum}\u2013${spec.yearMaximum}"
            spec.yearMinimum != null -> "${spec.yearMinimum}+"
            else -> "Before ${spec.yearMaximum!! + 1}"
        }
        list.add(label to { onSpecChanged(spec.copy(yearMinimum = null, yearMaximum = null)) })
    }
    if (spec.runtimeMinimumMinutes != null || spec.runtimeMaximumMinutes != null) {
        val label = when {
            spec.runtimeMinimumMinutes != null && spec.runtimeMaximumMinutes != null -> "${spec.runtimeMinimumMinutes}\u2013${spec.runtimeMaximumMinutes} min"
            spec.runtimeMinimumMinutes != null -> "${spec.runtimeMinimumMinutes}+ min"
            else -> "< ${spec.runtimeMaximumMinutes!! + 1} min"
        }
        list.add(label to { onSpecChanged(spec.copy(runtimeMinimumMinutes = null, runtimeMaximumMinutes = null)) })
    }
    if (spec.minimumImdb != null) {
        list.add("IMDb ${spec.minimumImdb}+" to { onSpecChanged(spec.copy(minimumImdb = null)) })
    }
    if (spec.minimumRottenTomatoes != null) {
        list.add("RT ${spec.minimumRottenTomatoes}%+" to { onSpecChanged(spec.copy(minimumRottenTomatoes = null)) })
    }
    if (spec.originalLanguage != null) {
        list.add(spec.originalLanguage to { onSpecChanged(spec.copy(originalLanguage = null)) })
    }
    return list
}
