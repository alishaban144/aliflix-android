@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    
    Column(
        modifier = modifier
            .background(AliflixSurfacePrimary, RoundedCornerShape(24.dp))
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Type selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecommendationMediaKind.entries.forEach { kind ->
                val selected = kind == selectedKind
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) AliflixAccentPrimary.copy(alpha=0.2f) else AliflixSurfaceSecondary)
                        .clickable { onSelectType(kind) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (kind == RecommendationMediaKind.MOVIE) "Movies" else "Series",
                        color = if (selected) AliflixContentPrimary else AliflixContentSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        // Text Input
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("What are you looking for?") },
            trailingIcon = {
                IconButton(onClick = { if (text.isNotBlank()) { onSubmit(text); text = "" } }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Submit", tint = AliflixContentPrimary)
                }
            },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AliflixSurfaceSecondary,
                unfocusedContainerColor = AliflixSurfaceSecondary,
                focusedBorderColor = AliflixAccentPrimary,
                unfocusedBorderColor = AliflixBorderSubtle,
                cursorColor = AliflixAccentSecondary,
                focusedTextColor = AliflixContentPrimary,
                unfocusedTextColor = AliflixContentPrimary,
            )
        )
        
        // Persistent question view
        if (state is RecommendationUiState.Question) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.question.text, color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.question.options.forEach { option ->
                        AssistChip(
                            onClick = { onAnswer(state.question, listOf(option.value)) },
                            label = { Text(option.label) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = AliflixSurfaceSecondary,
                                labelColor = AliflixContentPrimary
                            ),
                            border = AssistChipDefaults.assistChipBorder(borderColor = AliflixBorderSubtle, enabled = true)
                        )
                    }
                }
            }
        }
    }
}
