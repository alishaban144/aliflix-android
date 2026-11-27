package com.aliflix.app.ui.home

import com.aliflix.app.ui.common.AliflixCorners

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixSurfaceElevated

@Composable
fun HomeSkeleton(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "home-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-pulse",
    )

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBackgroundBase)
            .verticalScroll(rememberScrollState()),
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarTop + 12.dp, start = 18.dp, end = 18.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .alpha(alpha)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .alpha(alpha)
                    .background(AliflixSurfaceElevated),
            )
        }

        // Full-bleed Hero Banner Area matching real Home hero height
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(470.dp)
                .alpha(alpha)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF221B3B).copy(alpha = 0.60f),
                            Color(0xFF171B24).copy(alpha = 0.85f),
                            AliflixBackgroundBase,
                        ),
                    ),
                ),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AliflixAccentPrimary.copy(alpha = 0.5f)),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.22f)),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .width(280.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .width(106.dp)
                            .height(50.dp)
                            .clip(AliflixCorners.Card)
                            .background(AliflixAccentPrimary.copy(alpha = 0.75f)),
                    )
                    Box(
                        modifier = Modifier
                            .width(124.dp)
                            .height(50.dp)
                            .clip(AliflixCorners.Card)
                            .background(AliflixSurfaceElevated),
                    )
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(AliflixCorners.Card)
                            .background(AliflixSurfaceElevated),
                    )
                }
            }
        }

        // Filter Pills Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(alpha),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(78.dp, 74.dp, 86.dp, 96.dp).forEach { pillWidth ->
                Box(
                    modifier = Modifier
                        .width(pillWidth)
                        .height(42.dp)
                        .clip(AliflixCorners.Card)
                        .background(AliflixSurfaceElevated),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Rails
        repeat(2) { _ ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 22.dp),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .width(156.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .alpha(alpha)
                        .background(Color.White.copy(alpha = 0.18f)),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    repeat(3) { _ ->
                        Box(
                            modifier = Modifier
                                .width(126.dp)
                                .height(186.dp)
                                .clip(AliflixCorners.Card)
                                .alpha(alpha)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFF1F2430),
                                            Color(0xFF171B24),
                                            Color(0xFF10131A),
                                        ),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
    }
}
