@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.recommendation.*
import com.aliflix.app.ui.theme.*

@Composable
internal fun RecommendationComposer(
    state: RecommendationUiState,
    selectedKind: RecommendationMediaKind?,
    onSelectType: (RecommendationMediaKind) -> Unit,
    onSubmit: (String) -> Unit,
    onAnswer: (RecommendationQuestion, List<String>) -> Unit,
    onRestart: () -> Unit,
    onRelax: (String) -> Unit,
    onRetry: () -> Unit,
    onShowMatches: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable { mutableStateOf("") }
    var showComposer by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(state) {
        if (state is RecommendationUiState.Results) {
            showComposer = false
        }
    }

    if (!showComposer && state is RecommendationUiState.Results) {
        // Compact summary view for Results state
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(AliflixSurfaceSecondary, RoundedCornerShape(20.dp))
                .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectedKind == RecommendationMediaKind.MOVIE) "Movies" else "Series",
                    color = AliflixAccentSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = text.ifBlank { "Recommendation search" },
                    color = AliflixContentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { showComposer = true }) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit request", tint = AliflixContentSecondary)
            }
        }
        return
    }

    var activeMode by rememberSaveable { mutableIntStateOf(0) }
    var describeText by rememberSaveable { mutableStateOf("") }
    var similarToTitle by rememberSaveable { mutableStateOf("") }
    var similarToDiff by rememberSaveable { mutableStateOf("") }
    var activeRefinements by rememberSaveable { mutableStateOf(setOf<String>()) }

    val builtPrompt = buildString {
        when (activeMode) {
            0 -> {
                append(describeText.trim())
            }
            1 -> {
                if (similarToTitle.isNotBlank()) {
                    val kindText = if (selectedKind == RecommendationMediaKind.MOVIE) "movie" else "series"
                    append("A $kindText similar to ${similarToTitle.trim()}")
                    if (similarToDiff.isNotBlank()) {
                        append(", but ${similarToDiff.trim()}")
                    }
                    append(".")
                }
            }
            2 -> {
                if (activeRefinements.isNotEmpty()) {
                    val kindText = if (selectedKind == RecommendationMediaKind.MOVIE) "movie" else "series"
                    append("A $kindText ")
                    append(activeRefinements.joinToString(", "))
                    append(".")
                }
            }
        }
    }

    val canSubmit = selectedKind != null && builtPrompt.isNotBlank() && state !is RecommendationUiState.Discovering

    Column(
        modifier = modifier
            .background(AliflixSurfacePrimary, RoundedCornerShape(24.dp))
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (state is RecommendationUiState.Error) {
            RecommendationErrorState(state, onRetry)
        } else if (state is RecommendationUiState.Empty) {
            RecommendationEmptyState(state, onRelax)
        } else if (state is RecommendationUiState.SourceUnavailable) {
            RecommendationUnavailableState(state, onRetry)
        } else if (state is RecommendationUiState.Relaxation) {
            RecommendationRelaxationState(state, onRelax)
        } else if (state is RecommendationUiState.Question) {
            RecommendationQuestionState(state, onAnswer)
        } else {
            // Main Builder UI
            Text("Ask Aliflix", color = AliflixContentPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            // Media Selector Segmented Control
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(AliflixSurfaceSecondary, RoundedCornerShape(24.dp))
                    .padding(4.dp)
            ) {
                RecommendationMediaKind.entries.forEach { kind ->
                    val selected = kind == selectedKind
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) AliflixAccentPrimary.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { onSelectType(kind) }
                            .testTag(if (kind == RecommendationMediaKind.MOVIE) "discover-type-movie" else "discover-type-series"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (kind == RecommendationMediaKind.MOVIE) "Movies" else "Series",
                            color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Mode Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(AliflixSurfaceSecondary, RoundedCornerShape(20.dp))
                    .padding(4.dp)
            ) {
                listOf("Describe", "Similar to", "Build with filters").forEachIndexed { index, modeText ->
                    val selected = activeMode == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) AliflixSurfaceElevated else Color.Transparent)
                            .clickable { activeMode = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = modeText,
                            color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Mode Content
            when (activeMode) {
                0 -> {
                    Text("Describe the story, mood, characters or limits", color = AliflixContentSecondary, fontSize = 12.sp)
                    OutlinedTextField(
                        value = describeText,
                        onValueChange = { describeText = it },
                        placeholder = { Text("Example: A teenager develops dangerous psychic abilities, without animation", color = AliflixContentTertiary, fontSize = 14.sp) },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 150.dp)
                            .testTag("discover-search-field"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AliflixSurfaceSecondary,
                            unfocusedContainerColor = AliflixSurfaceSecondary,
                            focusedBorderColor = AliflixAccentPrimary,
                            unfocusedBorderColor = AliflixBorderSubtle,
                            focusedTextColor = AliflixContentPrimary,
                            unfocusedTextColor = AliflixContentPrimary,
                        )
                    )
                }
                1 -> {
                    OutlinedTextField(
                        value = similarToTitle,
                        onValueChange = { similarToTitle = it },
                        label = { Text("Title you already like") },
                        placeholder = { Text("Enter a movie or series title", color = AliflixContentTertiary) },
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AliflixSurfaceSecondary,
                            unfocusedContainerColor = AliflixSurfaceSecondary,
                            focusedBorderColor = AliflixAccentPrimary,
                            unfocusedBorderColor = AliflixBorderSubtle,
                            focusedTextColor = AliflixContentPrimary,
                            unfocusedTextColor = AliflixContentPrimary,
                        )
                    )
                    OutlinedTextField(
                        value = similarToDiff,
                        onValueChange = { similarToDiff = it },
                        label = { Text("What should be different?") },
                        placeholder = { Text("For example: shorter, darker, less violent", color = AliflixContentTertiary) },
                        shape = RoundedCornerShape(16.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AliflixSurfaceSecondary,
                            unfocusedContainerColor = AliflixSurfaceSecondary,
                            focusedBorderColor = AliflixAccentPrimary,
                            unfocusedBorderColor = AliflixBorderSubtle,
                            focusedTextColor = AliflixContentPrimary,
                            unfocusedTextColor = AliflixContentPrimary,
                        )
                    )
                }
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val filterGroups = mapOf(
                            "Mood" to listOf("Dark", "Funny", "Warm", "Tense", "Mind-bending", "Emotional", "Relaxing", "Suspenseful"),
                            "Genre" to listOf("Action", "Comedy", "Crime", "Drama", "Fantasy", "Horror", "Mystery", "Romance", "Science Fiction", "Thriller"),
                            "Length" to if (selectedKind == RecommendationMediaKind.MOVIE) {
                                listOf("Under 90 minutes", "Under 2 hours", "Over 2 hours")
                            } else {
                                listOf("Short episodes", "Under 45-minute episodes", "Long episodes")
                            },
                            "Era" to listOf("Recent", "After 2015", "2000s", "1990s", "Classic"),
                            "Avoid" to listOf("No animation", "No horror", "No graphic violence", "No romance", "No comedy", "No supernatural elements"),
                            "Rating" to listOf("IMDb 7+", "IMDb 8+", "Highly rated", "Hidden gem")
                        )
                        filterGroups.forEach { (groupTitle, options) ->
                            Text(groupTitle, color = AliflixContentPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                options.forEach { option ->
                                    val isSelected = activeRefinements.contains(option)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            if (isSelected) activeRefinements = activeRefinements - option
                                            else activeRefinements = activeRefinements + option
                                        },
                                        label = { Text(option) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = AliflixSurfaceSecondary,
                                            selectedContainerColor = AliflixAccentPrimary.copy(alpha = 0.2f),
                                            labelColor = AliflixContentPrimary,
                                            selectedLabelColor = AliflixAccentPrimary
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = AliflixBorderSubtle,
                                            selectedBorderColor = AliflixAccentPrimary,
                                            enabled = true,
                                            selected = isSelected
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Request Preview
            if (builtPrompt.isNotBlank()) {
                Surface(
                    color = AliflixSurfaceSecondary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Your request", color = AliflixContentSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(builtPrompt, color = AliflixContentPrimary, fontSize = 14.sp)
                    }
                }
            }

            // Main Action Button
            Button(
                onClick = { onSubmit(builtPrompt) },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AliflixAccentPrimary,
                    disabledContainerColor = AliflixSurfaceSecondary,
                    contentColor = AliflixContentPrimary,
                    disabledContentColor = AliflixContentTertiary
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                if (state is RecommendationUiState.Discovering) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AliflixContentPrimary)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Text("Find matches", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// Separate state UI components

@Composable
private fun RecommendationQuestionState(state: RecommendationUiState.Question, onAnswer: (RecommendationQuestion, List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(state.question.text, color = AliflixContentPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.question.options.forEach { option ->
                Button(
                    onClick = { onAnswer(state.question, listOf(option.value)) },
                    colors = ButtonDefaults.buttonColors(containerColor = AliflixSurfaceSecondary, contentColor = AliflixContentPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(option.label)
                }
            }
        }
    }
}

@Composable
private fun RecommendationErrorState(state: RecommendationUiState.Error, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("An error occurred", color = AliflixError, fontWeight = FontWeight.Bold)
        Text(state.message, color = AliflixContentSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        if (state.canRetry) {
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun RecommendationEmptyState(state: RecommendationUiState.Empty, onRelax: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("No Matches", color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        Text(state.message, color = AliflixContentSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        state.options.forEach { opt ->
            Button(onClick = { onRelax(opt.id) }) { Text(opt.label) }
        }
    }
}

@Composable
private fun RecommendationUnavailableState(state: RecommendationUiState.SourceUnavailable, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Source Unavailable", color = AliflixError, fontWeight = FontWeight.Bold)
        Text(state.message, color = AliflixContentSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        if (state.canRetry) {
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun RecommendationRelaxationState(state: RecommendationUiState.Relaxation, onRelax: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Too Specific", color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        Text(state.message, color = AliflixContentSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        state.options.forEach { opt ->
            Button(onClick = { onRelax(opt.id) }) { Text(opt.label) }
        }
    }
}
