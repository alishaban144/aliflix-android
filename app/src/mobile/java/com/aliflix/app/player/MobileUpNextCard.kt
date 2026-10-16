package com.aliflix.app.player

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Episode
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*

/** A compact end card; the original playback-driven ring retains its exact timing. */
@Composable
internal fun MobileUpNextCard(episode: Episode, progress: Float, onPlay: () -> Unit,
    onCredits: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 340.dp).animateContentSize(spring(stiffness = 420f)),
        shape = RoundedCornerShape(24.dp), color = AliflixSurfaceDefaults.color(AliflixSurfaceLevel.Elevated)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(width = 78.dp, height = 72.dp).clip(RoundedCornerShape(16.dp))
                    .background(AliflixAccentPrimary.copy(alpha = .09f))) {
                    AsyncImage(episode.stillUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .35f)))))
                    Text("S${episode.seasonNumber} E${episode.number}", Modifier.align(Alignment.BottomStart).padding(7.dp),
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Next episode", color = AliflixAccentSecondary, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp)
                    Text(episode.title, color = AliflixContentPrimary, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        val ring by animateFloatAsState(progress, tween(600, easing = FastOutSlowInEasing), label = "next-episode-ring")
                        CircularProgressIndicator(progress = { ring }, color = AliflixAccentPrimary,
                            trackColor = Color.White.copy(alpha = .12f), modifier = Modifier.size(40.dp), strokeWidth = 2.dp)
                        Box(Modifier.size(30.dp).background(AliflixAccentPrimary.copy(alpha = .16f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "Play next episode", tint = AliflixAccentSecondary, modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
            TextButton(onClick = onCredits, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Watch credits", color = AliflixContentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
