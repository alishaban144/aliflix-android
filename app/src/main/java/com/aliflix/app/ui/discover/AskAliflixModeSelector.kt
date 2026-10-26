package com.aliflix.app.ui.discover

import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary

private data class AskModeTab(
    val label: String,
    val icon: ImageVector?,
)

@Composable
fun AskAliflixModeSelector(
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
    selectedMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = AliflixSpacing.Content, end = AliflixSpacing.Content, top = 14.dp, bottom = AliflixSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AliflixSegmentedControl(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
        ) {
            MediaType.entries.forEach { type ->
                val selected = selectedMediaType == type
                val label = if (type == MediaType.MOVIE) "Movies" else "Series"
                val icon = if (type == MediaType.MOVIE) Icons.Rounded.Movie else Icons.Rounded.Tv
                val background by animateColorAsState(
                    if (selected) AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Selected) else Color.Transparent,
                    AskAliflixMotion.chipSpec(),
                    label = "ask-media-background",
                )
                val foreground by animateColorAsState(
                    if (selected) Color.White else AliflixContentSecondary,
                    AskAliflixMotion.chipSpec(),
                    label = "ask-media-foreground",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(AliflixCorners.Small)
                        .background(background)
                        .semantics {
                            role = Role.RadioButton
                            this.selected = selected
                        }
                        .clickable { onMediaTypeSelected(type) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = label,
                        color = foreground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Small),
        ) {
            listOf(
                AskModeTab("Describe", Icons.Rounded.Edit),
                AskModeTab("Similar", Icons.Rounded.Search),
                AskModeTab("Filters", Icons.Rounded.Tune),
            ).forEachIndexed { index, tab ->
                AskModeButton(
                    tab = tab,
                    selected = selectedMode == index,
                    onClick = { onModeSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AskModeButton(
    tab: AskModeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        AskAliflixMotion.pressSpec(),
        label = "ask-mode-press",
    )
    val background by animateColorAsState(
        AliflixSurfaceDefaults.color(if (selected) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content),
        AskAliflixMotion.chipSpec(),
        label = "ask-mode-background",
    )
    val foreground by animateColorAsState(
        if (selected) AliflixContentPrimary else AliflixContentSecondary,
        AskAliflixMotion.chipSpec(),
        label = "ask-mode-foreground",
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = 52.dp)
            .clip(AliflixCorners.Card)
            .background(background)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = AliflixSpacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            tab.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) AliflixAccentSecondary else foreground,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = tab.label,
                color = foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
