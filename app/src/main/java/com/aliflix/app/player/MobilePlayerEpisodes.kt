package com.aliflix.app.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Episode

private val PanelBackground = Color(0xFF10131A)
private val CardBackground = Color(0xFF181C26)
private val AliflixPurple = Color(0xFF6E59D9)

/**
 * Episodes overlay matching requirements:
 * - Landscape: right-side panel (max 360dp or 40% screen width), rounded left corners 20dp,
 *   dark Aliflix surface, season selector, scrollable list, current episode highlighted with #6E59D9.
 * - Portrait: modal bottom sheet (70-80% height).
 */
@Composable
internal fun MobileEpisodesOverlay(
    visible: Boolean,
    isLandscape: Boolean,
    episodes: List<Episode>,
    currentSeason: Int?,
    currentEpisode: Int?,
    onSelectEpisode: (Episode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val seasons = remember(episodes) {
        episodes.map { it.seasonNumber }.distinct().sorted()
    }
    var selectedSeason by remember(currentSeason, seasons) {
        mutableIntStateOf(currentSeason ?: seasons.firstOrNull() ?: 1)
    }
    val filteredEpisodes = remember(episodes, selectedSeason) {
        episodes.filter { it.seasonNumber == selectedSeason }.sortedBy { it.number }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        if (isLandscape) {
            // Landscape right-side panel
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInHorizontally { it },
                exit = fadeOut() + slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.40f)
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .background(PanelBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    EpisodesContent(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        onSeasonChange = { selectedSeason = it },
                        episodes = filteredEpisodes,
                        currentSeason = currentSeason,
                        currentEpisode = currentEpisode,
                        onSelectEpisode = {
                            onSelectEpisode(it)
                            onDismiss()
                        },
                        onClose = onDismiss,
                    )
                }
            }
        } else {
            // Portrait modal bottom sheet (70-80% height)
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.78f)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                        .background(PanelBackground)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    EpisodesContent(
                        seasons = seasons,
                        selectedSeason = selectedSeason,
                        onSeasonChange = { selectedSeason = it },
                        episodes = filteredEpisodes,
                        currentSeason = currentSeason,
                        currentEpisode = currentEpisode,
                        onSelectEpisode = {
                            onSelectEpisode(it)
                            onDismiss()
                        },
                        onClose = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodesContent(
    seasons: List<Int>,
    selectedSeason: Int,
    onSeasonChange: (Int) -> Unit,
    episodes: List<Episode>,
    currentSeason: Int?,
    currentEpisode: Int?,
    onSelectEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "EPISODES",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Season selector (if more than 1 season)
        if (seasons.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(seasons) { seasonNum ->
                    val isSelected = seasonNum == selectedSeason
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) AliflixPurple else CardBackground)
                            .clickable { onSeasonChange(seasonNum) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = "Season $seasonNum",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Episodes scrollable list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(episodes, key = { "s${it.seasonNumber}e${it.number}" }) { episode ->
                val isCurrent = episode.seasonNumber == currentSeason && episode.number == currentEpisode
                EpisodeCard(
                    episode = episode,
                    isCurrent = isCurrent,
                    onClick = { onSelectEpisode(episode) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) AliflixPurple.copy(alpha = 0.22f) else CardBackground)
            .border(
                width = if (isCurrent) 1.5.dp else 1.dp,
                color = if (isCurrent) AliflixPurple else Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail or placeholder
        Box(
            modifier = Modifier
                .size(width = 90.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!episode.stillUrl.isNullOrBlank()) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(AliflixPurple),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Episode info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Episode ${episode.number}",
                color = if (isCurrent) AliflixPurple else Color.White.copy(alpha = 0.70f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = episode.title.ifBlank { "Episode ${episode.number}" },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.runtime.isNotBlank()) {
                Text(
                    text = episode.runtime,
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
