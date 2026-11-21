package com.aliflix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.common.AliflixCorners
import com.aliflix.app.ui.common.AliflixSpacing
import com.aliflix.app.ui.common.AliflixSurface
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary

private val AcademyGold = Color(0xFFF4D391)
private val academyAward = Regex(
    """(?:^|(?<=[.!;])\s+)((?:Won|Nominated\s+for)\s+(?:\d+|an?|one)\s+(?:Oscars?|Academy Awards?))(?=[.!;,]|$)""",
    RegexOption.IGNORE_CASE,
)
private val awardCounts = Regex(
    """(?:^|(?<=[.!;])\s+)(Another\s+)?([\d,]+)\s+wins?\s+(?:&|and)\s+([\d,]+)\s+nominations?(\s+total)?\.?$""",
    RegexOption.IGNORE_CASE,
)

internal data class DetailAwardCounts(val wins: String, val nominations: String, val qualifier: String)

internal data class DetailAwardsSummary(
    val academy: String?,
    val summary: String?,
    val counts: DetailAwardCounts?,
)

internal fun detailAwardsSummary(raw: String): DetailAwardsSummary {
    val text = raw.trim()
    val academy = academyAward.find(text)
    val remainder = if (academy == null) text else {
        val before = text.take(academy.range.first).trimEnd()
        val after = text.drop(academy.range.last + 1).trimStart(' ', '.', '!', ';', ',')
        listOf(before, after).filter(String::isNotEmpty).joinToString(" ")
    }
    val counts = awardCounts.find(remainder)
    val summary = if (counts == null) remainder else remainder.take(counts.range.first).trimEnd()
    return DetailAwardsSummary(
        academy = academy?.groupValues?.get(1),
        summary = summary.takeIf(String::isNotEmpty),
        counts = counts?.let {
            val (other, wins, nominations, total) = it.destructured
            val qualifier = when {
                other.isNotEmpty() -> "Other"
                total.isNotEmpty() -> "Total"
                else -> ""
            }
            DetailAwardCounts(wins, nominations, qualifier)
        },
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun DetailAwardsCard(awards: String, modifier: Modifier = Modifier, focusable: Boolean = false) {
    val details = remember(awards) { detailAwardsSummary(awards) }
    val hasAcademyAward = details.academy != null
    val accent = if (hasAcademyAward) AcademyGold else AliflixAccentSecondary
    var focused by remember { mutableStateOf(false) }
    AliflixSurface(
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = focusable)
            .border(
                if (focused) 2.dp else 1.dp,
                accent.copy(alpha = if (focused) 1f else 0.20f),
                AliflixCorners.Card,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent)))
                .padding(AliflixSpacing.Content),
            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(accent.copy(alpha = 0.13f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
            ) {
                Text(
                    "Awards",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.labelLarge,
                    color = AliflixContentPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                details.academy?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
                }
                details.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasAcademyAward) AliflixContentSecondary else AliflixContentPrimary,
                    )
                }
                details.counts?.let { counts ->
                    FlowRow(
                        modifier = Modifier.padding(vertical = AliflixSpacing.Tiny),
                        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
                    ) {
                        AwardMetric(counts.wins, "Wins", counts.qualifier)
                        AwardMetric(counts.nominations, "Nominations", counts.qualifier)
                    }
                }
                Text("OMDb", style = MaterialTheme.typography.labelSmall, color = AliflixContentSecondary)
            }
        }
    }
}

@Composable
private fun AwardMetric(count: String, label: String, qualifier: String) {
    val description = if (qualifier.isEmpty()) label else "$qualifier ${label.lowercase()}"
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(count, style = MaterialTheme.typography.titleMedium, color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.labelMedium, color = AliflixContentSecondary)
    }
}
