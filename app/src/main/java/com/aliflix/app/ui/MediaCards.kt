@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui




import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import com.aliflix.app.ui.common.*


import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.model.ContentRail
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.common.AliflixLogoMark
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixAccentPrimary as AliflixRed
import com.aliflix.app.ui.theme.AliflixBackgroundImmersive
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentSecondary as AliflixMuted
import com.aliflix.app.ui.theme.AliflixSurfaceElevated as AliflixSurfaceRaised
import java.util.Locale

@Composable
internal fun MediaRail(
    rail: ContentRail,
    onOpen: (Media) -> Unit,
    compact: Boolean,
) {
    val ranked = rail.title.contains("Trending", ignoreCase = true) ||
        rail.title.contains("Popular", ignoreCase = true)
    Column(
        modifier = Modifier.padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
    ) {
        SectionHeader(
            title = rail.title,
            eyebrow = if (ranked) "WHAT'S HOT" else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = AliflixSpacing.Content),
            horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Medium),
        ) {
            itemsIndexed(rail.items, key = { _, item -> item.key }) { index, item ->
                MediaPoster(
                    item = item,
                    width = if (compact) 112.dp else 126.dp,
                    rank = if (ranked && index < 10) index + 1 else null,
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
internal fun MediaPoster(
    item: Media,
    width: androidx.compose.ui.unit.Dp,
    rank: Int? = null,
    showLibraryMetadata: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val posterScale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = tween(AliflixMotion.Press),
        label = "poster-press",
    )
    Column(
        modifier = modifier
            .width(width)
            .scale(posterScale)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                },
            ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AliflixMediaCard(
            modifier = Modifier
                .width(width)
                .aspectRatio(0.68f)
                ,
        ) {
            ArtworkPlaceholder(title = item.title)
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // No wash over the poster itself, so the artwork keeps its original colours. Every badge
            // below carries its own opaque backing and stays readable without one.
            if (!showLibraryMetadata) Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = AliflixSpacing.Tiny),
            ) {
                Text(
                    text = if (item.type == MediaType.MOVIE) "MOVIE" else "SERIES",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
            if (showLibraryMetadata) {
                Box(Modifier.align(Alignment.TopStart).padding(7.dp).clip(AliflixCorners.Small)
                    .background(Color.Black.copy(alpha=.79f)).width(IntrinsicSize.Max).heightIn(min=22.dp).widthIn(min=44.dp)
                    .padding(horizontal=7.dp,vertical=3.dp),contentAlignment=Alignment.Center) {
                    Text(if(item.type==MediaType.MOVIE)"MOVIE" else "SERIES",fontSize=8.sp,lineHeight=10.sp,letterSpacing=0.sp,fontWeight=FontWeight.SemiBold,
                        maxLines=1,softWrap=false,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                        modifier=Modifier.fillMaxWidth(),overflow=TextOverflow.Ellipsis,
                        style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)))
                }
                val posterFacts = listOfNotNull(
                    item.rating.takeIf { it > 0.0 }?.let { "★ %.1f".format(Locale.ROOT, it) },
                    item.runtime.trim().takeIf(String::isNotBlank)?.let(::compactLibraryRuntime),
                )
                if (posterFacts.isNotEmpty()) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .padding(start = 7.dp, end = 7.dp, bottom = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny,Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        posterFacts.forEachIndexed { index, value ->
                            Box(Modifier.then(if(posterFacts.size>1)Modifier.weight(if(index==posterFacts.lastIndex)1.3f else 1f)else Modifier.widthIn(min=44.dp,max=68.dp)).heightIn(min=22.dp)
                                .clip(AliflixCorners.Small).background(Color.Black.copy(alpha=.79f))
                                .padding(horizontal=3.dp,vertical=3.dp),contentAlignment=Alignment.Center) {
                            Text(
                                text = value,
                                color = if (index == 0 && item.rating > 0.0) AliflixAccentSecondary else Color.White,
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                                letterSpacing = 0.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)),
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            }
                        }
                    }
                }
            }
            if (rank != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(AliflixCorners.Small)
                        .background(
                            Brush.horizontalGradient(
                                listOf(AliflixRed, Color(0xFFC6071E)),
                            ),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "#$rank",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }
        Text(
            text = item.title + if (showLibraryMetadata && item.year.isNotBlank()) "  ·  ${item.year}" else "",
            color = AliflixContentPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (showLibraryMetadata) Modifier else Modifier.height(34.dp),
        )
        if (item.year.isNotBlank() && !showLibraryMetadata) {
            Text(
                text = item.year,
                color = AliflixMuted,
                fontSize = 11.sp,
            )
        }

    }
}

@Composable
private fun SectionHeader(
    title: String,
    eyebrow: String? = null,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun shouldShowPlaybackProgressRing(progress: PlaybackProgress?): Boolean =
    progress != null && (progress.positionSeconds > 0.0 || progress.completed)

@Composable
internal fun PlaybackProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(AliflixSpacing.Tiny),
    ) {
        drawCircle(
            color = Color.White.copy(alpha = 0.20f),
            style = Stroke(width = 2.6.dp.toPx()),
        )
        drawArc(
            color = AliflixAccentSecondary,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            style = Stroke(width = 2.6.dp.toPx()),
        )
    }
}

internal data class RecentPlaybackPresentation(
    val progress: PlaybackProgress,
    val episodeDescription: String?,
)

/** Resolves a recent card to the latest exact movie/episode progress entry for that title. */
internal fun recentPlaybackPresentation(
    item: Media,
    playbackProgress: Map<String, PlaybackProgress>,
): RecentPlaybackPresentation? {
    val progress = playbackProgress.values
        .asSequence()
        .filter { candidate ->
            candidate.media.key == item.key &&
                candidate.positionSeconds > 0.0 &&
                candidate.durationSeconds > 0.0 &&
                (
                    item.type != MediaType.TV ||
                        (candidate.seasonNumber != null && candidate.episodeNumber != null)
                    )
        }
        .maxWithOrNull(
            compareBy<PlaybackProgress>(PlaybackProgress::updatedAtMillis)
                .thenBy(PlaybackProgress::positionSeconds),
        ) ?: return null
    val episodeDescription = if (item.type == MediaType.TV) {
        val season = progress.seasonNumber ?: return null
        val episode = progress.episodeNumber ?: return null
        buildString {
            append("S")
            append(season.toString().padStart(2, '0'))
            append("  •  E")
            append(episode.toString().padStart(2, '0'))
            progress.episodeTitle
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { title ->
                    append("  •  ")
                    append(title)
                }
        }
    } else {
        null
    }
    return RecentPlaybackPresentation(progress, episodeDescription)
}

@Composable
internal fun ArtworkPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(AliflixSurfaceRaised, AliflixBackgroundImmersive),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AliflixSpacing.Tiny),
        ) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: "A",
                color = AliflixAccentSecondary.copy(alpha = 0.82f),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "ALIFLIX",
                color = Color.White.copy(alpha = 0.36f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
            )
        }
    }
}

@Composable
internal fun EmptyMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AliflixLogoMark(
            modifier = Modifier
                .width(64.dp)
                .height(50.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            color = AliflixContentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
