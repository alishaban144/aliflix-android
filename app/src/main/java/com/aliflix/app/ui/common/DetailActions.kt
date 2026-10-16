package com.aliflix.app.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.ui.theme.*

/** Restrained press spring and state changes adapted from Motion press / Aceternity stateful actions. */
@Composable internal fun DetailIconAction(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = rememberMobileMotionEnabled()
    val scale by animateFloatAsState(if (pressed && motion) .94f else 1f, AliflixMotion.press(), label = "detail-press")
    val color by animateColorAsState(AliflixSurfaceDefaults.color(if (selected) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Elevated), AliflixMotion.selection(), label = "detail-selection")
    Surface(onClick = onClick, interactionSource = interaction, color = color, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(21.dp), tint = if (selected) AliflixAccentSecondary else AliflixContentSecondary)
        }
    }
}

@Composable internal fun DetailPlayAction(label: String, progress: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = rememberMobileMotionEnabled()
    val fontScale = LocalDensity.current.fontScale
    val scale by animateFloatAsState(if (pressed && motion) .96f else 1f, AliflixMotion.press(), label = "detail-play-press")
    Button(onClick, interactionSource = interaction, shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary, contentColor = AliflixContentPrimary),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        modifier = modifier.height(56.dp).semantics { contentDescription = label }.graphicsLayer { scaleX = scale; scaleY = scale }) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val compact = maxWidth < 100.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (compact) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    if (fontScale <= 1.3f) Text(if (progress != null) "Resume" else "Play", fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp)); Spacer(Modifier.width(4.dp))
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(.7f).height(2.dp), color = AliflixContentPrimary,
                    trackColor = AliflixContentPrimary.copy(alpha = .3f))
            }
        }
    }
}
