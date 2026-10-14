package com.aliflix.app.ui.common

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.*

/** Controlled switch. A null callback lets a labelled parent row own the single toggle action. */
@Composable
internal fun AliflixLiquidToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val motion = rememberMobileMotionEnabled()
    val resumed = rememberMobileResumed()
    val progress by animateFloatAsState(if (checked) 1f else 0f,
        if (motion && resumed) tween(500, easing = FastOutSlowInEasing) else snap(), label = "liquid-toggle")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
        .then(if (onCheckedChange == null) Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        } else Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(52.dp, 48.dp).graphicsLayer { scaleX = if (rtl) -1f else 1f }) {
            val unit = size.width / 52f
            drawRoundRect(lerp(AliflixSurfaceElevated, AliflixAccentPrimary, progress).copy(alpha = if (enabled) 1f else .45f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, 8f * unit),
                size = androidx.compose.ui.geometry.Size(size.width, 32f * unit), cornerRadius = CornerRadius(16f * unit))
            // A single GPU-drawn thumb stretches gently through the transition.
            // No software bitmap, pixel readback, blur or decorative droplet.
            val stretch = 3f * kotlin.math.sin(progress.coerceIn(0f,1f) * kotlin.math.PI).toFloat()
            val thumbWidth = 20f + stretch
            val centerX = 16f + 20f * progress
            drawRoundRect(Color.White.copy(alpha = if (enabled) 1f else .45f),
                topLeft = androidx.compose.ui.geometry.Offset((centerX-thumbWidth/2f)*unit,14f*unit),
                size = androidx.compose.ui.geometry.Size(thumbWidth*unit,20f*unit),
                cornerRadius = CornerRadius(10f*unit))
        }
    }
}
