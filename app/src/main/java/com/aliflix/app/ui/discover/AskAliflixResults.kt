package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationCandidate
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBackgroundBase
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBackgroundBase)
    ) {
        val requestSummary = when (uiState) {
            is AskAliflixUiState.Interpreting -> uiState.requestSummary
            is AskAliflixUiState.Searching -> uiState.requestSummary
            is AskAliflixUiState.Results -> uiState.requestSummary
            is AskAliflixUiState.Empty -> uiState.requestSummary
            is AskAliflixUiState.SourceUnavailable -> uiState.requestSummary
            is AskAliflixUiState.Error -> uiState.requestSummary
            else -> ""
        }

        // Compact Request Strip (48dp height)
        if (requestSummary.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onEdit() },
                color = AliflixSurfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = requestSummary,
                        color = AliflixContentSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit query or filters",
                            tint = AliflixAccentPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Edit",
                            color = AliflixAccentPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Main Content Area
        Box(modifier = Modifier.weight(1f)) {
            when (uiState) {
                is AskAliflixUiState.Interpreting -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = AliflixAccentPrimary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Understanding your request…",
                            color = AliflixContentPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is AskAliflixUiState.Searching -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = AliflixAccentPrimary,
                            trackColor = AliflixSurfaceElevated
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Finding verified matches…",
                            color = AliflixContentSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(4) {
                                SkeletonCard()
                            }
                        }
                    }
                }

                is AskAliflixUiState.Results -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.items,
                            key = { _, item -> item.media.key }
                        ) { _, item ->
                            ResultCard(item = item, onClick = { onOpenMedia(item.media) })
                        }

                        if (uiState.hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (uiState.loadingMore) {
                                        CircularProgressIndicator(
                                            color = AliflixAccentPrimary,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        OutlinedButton(
                                            onClick = onLoadMore,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Load more matches", color = AliflixContentPrimary, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                        uiState.loadMoreError?.let { message ->
                            item {
                                Text(
                                    text = message,
                                    color = AliflixError,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                is AskAliflixUiState.Empty -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.message,
                            color = AliflixContentPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onEdit) {
                                Text("Edit filters", color = AliflixContentPrimary)
                            }
                            Button(
                                onClick = onReset,
                                colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary)
                            ) {
                                Text("Reset workspace")
                            }
                        }
                    }
                }

                is AskAliflixUiState.SourceUnavailable -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.message,
                            color = AliflixContentSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary)
                        ) {
                            Text("Retry search")
                        }
                    }
                }

                is AskAliflixUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = uiState.message,
                            color = AliflixError,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary)
                        ) {
                            Text("Retry")
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun ResultCard(
    item: RecommendationCandidate,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.media.posterPath,
                contentDescription = item.media.title,
                modifier = Modifier
                    .size(54.dp, 81.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AliflixSurfaceSecondary)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.media.title,
                    color = AliflixContentPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val yearRuntimeStr = buildString {
                    item.media.year.takeIf { it.isNotBlank() }?.let { append(it) }
                    item.media.runtime.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }
                if (yearRuntimeStr.isNotBlank()) {
                    Text(
                        text = yearRuntimeStr,
                        color = AliflixContentTertiary,
                        fontSize = 12.sp
                    )
                }

                val genresStr = item.media.genres.take(3).joinToString(" · ")
                if (genresStr.isNotBlank()) {
                    Text(
                        text = genresStr,
                        color = AliflixContentSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Ratings + Match Reason
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.media.rating.takeIf { it > 0.0 }?.let { rating ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "TMDB $rating",
                                color = AliflixContentPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Text(
                        text = item.explanation,
                        color = AliflixAccentPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SkeletonCard() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(95.dp),
        color = AliflixSurfaceElevated,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(54.dp, 81.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AliflixSurfaceSecondary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AliflixSurfaceSecondary)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.4f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AliflixSurfaceSecondary)
                )
            }
        }
    }
}
