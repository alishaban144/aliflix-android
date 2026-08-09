@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.Media
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixContentTertiary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary

@Composable
fun AskAliflixResults(
    uiState: AskAliflixUiState,
    onOpenMedia: (Media) -> Unit,
    onEdit: () -> Unit,
    onReset: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val requestSummary = uiState.requestSummary()

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = requestSummary.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            QuerySummaryCard(summary = requestSummary, onEdit = onEdit)
        }

        AnimatedContent(
            targetState = uiState,
            transitionSpec = {
                fadeIn(AskAliflixMotion.smallContentSpec()) togetherWith fadeOut(AskAliflixMotion.smallContentSpec())
            },
            contentKey = { state -> state::class },
            label = "ask-results-state",
            modifier = Modifier.weight(1f),
        ) { state ->
            when (state) {
                is AskAliflixUiState.Interpreting -> AskLoadingState(
                    title = "Understanding your request",
                )

                is AskAliflixUiState.Searching -> AskLoadingState(
                    title = "Finding matches",
                )

                is AskAliflixUiState.Results -> ResultsList(
                    state = state,
                    listState = listState,
                    onOpenMedia = onOpenMedia,
                    onLoadMore = onLoadMore,
                )

                is AskAliflixUiState.Empty -> AskStateMessage(
                    icon = Icons.Rounded.SearchOff,
                    title = "No matches",
                    message = state.message,
                    primaryLabel = "Adjust request",
                    onPrimary = onEdit,
                    secondaryLabel = "Start over",
                    onSecondary = onReset,
                )

                is AskAliflixUiState.SourceUnavailable -> AskStateMessage(
                    icon = Icons.Rounded.Warning,
                    title = "TMDB unavailable",
                    message = state.message,
                    primaryLabel = "Try again",
                    onPrimary = onRetry,
                    secondaryLabel = "Edit request",
                    onSecondary = onEdit,
                )

                is AskAliflixUiState.Error -> AskStateMessage(
                    icon = Icons.Rounded.Warning,
                    title = "Search failed",
                    message = state.message,
                    primaryLabel = "Try again",
                    onPrimary = onRetry,
                    secondaryLabel = "Edit request",
                    onSecondary = onEdit,
                )

                AskAliflixUiState.Editing -> Unit
            }
        }
    }
}

@Composable
private fun QuerySummaryCard(summary: String, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AliflixSurfaceElevated.copy(alpha = 0.82f))
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(AliflixAccentPrimary.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = AliflixAccentSecondary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = summary,
            color = AliflixContentPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.Edit, contentDescription = "Edit request", tint = AliflixAccentSecondary, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun ResultsList(
    state: AskAliflixUiState.Results,
    listState: LazyListState,
    onOpenMedia: (Media) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "YOUR MATCHES",
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.15.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Ranked for this request",
                        color = AliflixContentPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.25).sp,
                    )
                }
                Surface(color = AliflixAccentPrimary.copy(alpha = 0.18f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = "${state.items.size} found",
                        color = AliflixAccentSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    )
                }
            }
        }

        itemsIndexed(state.items, key = { _, item -> item.media.key }) { index, item ->
            ResultCard(
                rank = index + 1,
                item = item,
                onClick = { onOpenMedia(item.media) },
                modifier = Modifier.animateItem(),
            )
        }

        if (state.hasMore || state.loadingMore) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(color = AliflixAccentSecondary, strokeWidth = 2.dp, modifier = Modifier.size(25.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Adding more matches…", color = AliflixContentSecondary, fontSize = 11.sp)
                    } else {
                        OutlinedButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.55f)),
                            contentPadding = PaddingValues(vertical = 14.dp),
                        ) {
                            Text("Show more matches", color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "You’ve reached the end of this shortlist",
                    color = AliflixContentTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        }

        state.loadMoreError?.let { message ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(AliflixError.copy(alpha = 0.11f))
                        .padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = AliflixError, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, color = AliflixError, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(
    rank: Int,
    item: RecommendationCandidate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.982f else 1f,
        AskAliflixMotion.pressSpec(),
        label = "ask-result-press",
    )
    val border by animateColorAsState(
        if (pressed) AliflixAccentPrimary.copy(alpha = 0.72f) else AliflixBorderSubtle,
        AskAliflixMotion.pressSpec(),
        label = "ask-result-border",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        color = AliflixSurfaceElevated.copy(alpha = 0.84f),
        shape = RoundedCornerShape(19.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AskAliflixPoster(
                    media = item.media,
                    modifier = Modifier.size(width = 92.dp, height = 136.dp),
                    cornerRadius = 13.dp,
                )
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(25.dp)
                        .clip(CircleShape)
                        .background(Color(0xE8060810))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$rank", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = item.media.title,
                        color = AliflixContentPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = "Open ${item.media.title}",
                        tint = AliflixContentTertiary,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))

                val metadata = listOfNotNull(
                    item.media.year.takeIf(String::isNotBlank),
                    item.media.runtime.takeIf(String::isNotBlank),
                    if (item.media.type == com.aliflix.app.model.MediaType.MOVIE) "Movie" else "Series",
                )
                Text(
                    metadata.joinToString(" · "),
                    color = AliflixContentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.media.genres.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        item.media.genres.take(2).forEach { genre ->
                            Text(
                                text = genre,
                                color = AliflixContentSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AliflixSurfaceSecondary)
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.media.rating > 0.0) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFFFFC857).copy(alpha = 0.12f))
                                .padding(horizontal = 7.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Star, contentDescription = null, tint = Color(0xFFFFC857), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("TMDB ${"%.1f".format(item.media.rating)}", color = AliflixContentPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    MatchLevelPill(item.explanation)
                }
            }
        }
    }
}

@Composable
private fun MatchLevelPill(level: String) {
    val label = level.takeIf { it in setOf("Exceptional", "Strong", "Relevant", "Broader but still relevant") }
        ?: "Relevant"
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(AliflixAccentPrimary.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(AliflixAccentSecondary))
        Spacer(Modifier.width(5.dp))
        Text(label, color = AliflixAccentSecondary, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun AskLoadingState(title: String) {
    val infinite = rememberInfiniteTransition(label = "ask-loading")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "ask-loading-pulse",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(AliflixAccentPrimary.copy(alpha = pulse), AliflixAccentPrimary.copy(alpha = 0.08f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(17.dp))
        Text(title, color = AliflixContentPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(24.dp))
        repeat(3) { index ->
            LoadingResultCard(alpha = (pulse - index * 0.08f).coerceIn(0.28f, 0.8f))
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun LoadingResultCard(alpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AliflixSurfaceElevated.copy(alpha = 0.72f))
            .border(1.dp, AliflixBorderSubtle.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .padding(10.dp),
    ) {
        Box(Modifier.size(width = 62.dp, height = 92.dp).clip(RoundedCornerShape(10.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha)))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.72f).height(15.dp).clip(RoundedCornerShape(5.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha)))
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth(0.44f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha * 0.8f)))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(0.58f).height(22.dp).clip(RoundedCornerShape(8.dp)).background(AliflixSurfaceSecondary.copy(alpha = alpha * 0.7f)))
        }
    }
}

@Composable
private fun AskStateMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AliflixSurfaceElevated)
                .border(1.dp, AliflixBorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = AliflixAccentSecondary, modifier = Modifier.size(27.dp))
        }
        Spacer(Modifier.height(17.dp))
        Text(title, color = AliflixContentPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(7.dp))
        Text(message, color = AliflixContentSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPrimary,
            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(primaryLabel, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(secondaryLabel, color = AliflixContentPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

private fun AskAliflixUiState.requestSummary(): String = when (this) {
    is AskAliflixUiState.Interpreting -> requestSummary
    is AskAliflixUiState.Searching -> requestSummary
    is AskAliflixUiState.Results -> requestSummary
    is AskAliflixUiState.Empty -> requestSummary
    is AskAliflixUiState.SourceUnavailable -> requestSummary
    is AskAliflixUiState.Error -> requestSummary
    AskAliflixUiState.Editing -> ""
}
