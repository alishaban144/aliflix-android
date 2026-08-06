@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

    var activeRefinements by rememberSaveable { mutableStateOf(setOf<String>()) }
    var similarToTitle by rememberSaveable { mutableStateOf("") }

    val builtPrompt = buildString {
        append(text.trim())
        if (activeRefinements.isNotEmpty()) {
            if (isNotEmpty()) append(", ")
            append(activeRefinements.joinToString(", "))
        }
        if (similarToTitle.isNotBlank()) {
            if (isNotEmpty()) append(". ")
            append("Similar to ${similarToTitle.trim()}")
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
            Text("What should we find?", color = AliflixContentPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)

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
                            .clickable { onSelectType(kind) },
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

            // Prompt field
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Describe the story, mood, or limits...", color = AliflixContentTertiary) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp, max = 150.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AliflixSurfaceSecondary,
                    unfocusedContainerColor = AliflixSurfaceSecondary,
                    focusedBorderColor = AliflixAccentPrimary,
                    unfocusedBorderColor = AliflixBorderSubtle,
                    focusedTextColor = AliflixContentPrimary,
                    unfocusedTextColor = AliflixContentPrimary,
                )
            )

            // Active Refinements Chips
            if (activeRefinements.isNotEmpty() || similarToTitle.isNotBlank()) {
                Column {
                    Text("Selected refinements:", color = AliflixContentSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        activeRefinements.forEach { ref ->
                            InputChip(
                                selected = true,
                                onClick = { activeRefinements = activeRefinements - ref },
                                label = { Text(ref) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = AliflixSurfaceElevated,
                                    selectedLabelColor = AliflixContentPrimary
                                ),
                                border = InputChipDefaults.inputChipBorder(borderColor = AliflixBorderStrong, enabled = true, selected = true)
                            )
                        }
                        if (similarToTitle.isNotBlank()) {
                            InputChip(
                                selected = true,
                                onClick = { similarToTitle = "" },
                                label = { Text("Similar to: $similarToTitle") },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = AliflixSurfaceElevated,
                                    selectedLabelColor = AliflixContentPrimary
                                ),
                                border = InputChipDefaults.inputChipBorder(borderColor = AliflixBorderStrong, enabled = true, selected = true)
                            )
                        }
                    }
                }
            }

            // Refinement Tools
            Text("Make it more specific", color = AliflixContentPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tools = listOf("Dark", "Under 2 hours", "No animation", "After 2015", "Suspenseful")
                tools.forEach { tool ->
                    AssistChip(
                        onClick = {
                            if (activeRefinements.contains(tool)) {
                                activeRefinements = activeRefinements - tool
                            } else {
                                activeRefinements = activeRefinements + tool
                            }
                        },
                        label = { Text(tool) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = AliflixSurfaceSecondary,
                            labelColor = AliflixContentPrimary
                        ),
                        border = AssistChipDefaults.assistChipBorder(borderColor = AliflixBorderSubtle, enabled = true)
                    )
                }
            }

            // Start with an idea (Recipes)
            if (text.isBlank() && activeRefinements.isEmpty() && similarToTitle.isBlank()) {
                Text("Start with an idea", color = AliflixContentPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecipeCard("Hidden gem", "Less obvious, well-rated choices") {
                        activeRefinements = activeRefinements + "Highly rated hidden gem"
                    }
                    RecipeCard("Similar to a title", "Find something like what you already love") {
                        similarToTitle = "Inception" // Example starter
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
                        Text("Find genuine matches", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipeCard(title: String, desc: String, onClick: () -> Unit) {
    Surface(
        color = AliflixSurfaceSecondary,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = AliflixContentPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(desc, color = AliflixContentSecondary, fontSize = 12.sp)
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
