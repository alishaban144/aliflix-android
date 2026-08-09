package com.aliflix.app.ui.discover

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

@Composable
fun AskAliflixModeSelector(
    selectedMode: Int,
    onModeSelected: (Int) -> Unit,
    selectedMediaType: MediaType,
    onMediaTypeSelected: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Segmented Media Type Control (Movies | Series)
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AliflixSurfaceElevated)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(MediaType.MOVIE to "Movies", MediaType.TV to "Series").forEach { (type, label) ->
                val isSelected = selectedMediaType == type
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) AliflixAccentPrimary else Color.Transparent,
                    animationSpec = AskAliflixMotion.chipSpec(),
                    label = "media-type-bg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else AliflixContentSecondary,
                    animationSpec = AskAliflixMotion.chipSpec(),
                    label = "media-type-fg"
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .clickable { onMediaTypeSelected(type) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Segmented Mode Rail (Describe | Similar | Filters)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(AliflixSurfaceElevated)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf("Describe", "Similar", "Filters").forEachIndexed { idx, label ->
                val isSelected = selectedMode == idx
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) AliflixAccentPrimary else Color.Transparent,
                    animationSpec = AskAliflixMotion.chipSpec(),
                    label = "mode-rail-bg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else AliflixContentSecondary,
                    animationSpec = AskAliflixMotion.chipSpec(),
                    label = "mode-rail-fg"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bgColor)
                        .clickable { onModeSelected(idx) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
