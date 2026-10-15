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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.aliflix.app.ui.theme.*

/** Native port of Deepak Chandwani's Liquid Toggle (21st.dev, bundled/673.html).
 * Keep the reference's two scaling circles, colors, 500ms easing
 * and goo filter. A null callback lets a labelled row own the single action.
 * The detached dot is intentionally omitted. All new mobile boolean settings use this component. */
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
        if (motion && resumed) tween(500, easing = CubicBezierEasing(.4f, 0f, .2f, 1f)) else snap(), label = "liquid-toggle")
    val density = LocalDensity.current.density
    val goo = remember(density) {
        if (android.os.Build.VERSION.SDK_INT >= 31) liquidGooEffect(2f * density) else null
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.defaultMinSize(minWidth = 52.dp, minHeight = 48.dp)
        .then(if (onCheckedChange == null) Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        } else Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(52.dp, 48.dp).graphicsLayer { scaleX = if (rtl) -1f else 1f; alpha = if (enabled) 1f else .45f }) {
            val unit = size.width / 52f
            drawRoundRect(lerp(Color(0xFFD2D6E9), Color(0xFF275EFE), progress),
                topLeft = Offset(0f, 8f * unit), size = Size(size.width, 32f * unit), cornerRadius = CornerRadius(16f * unit))
        }
        Canvas(Modifier.size(52.dp, 48.dp).graphicsLayer {
            scaleX = if (rtl) -1f else 1f
            alpha = if (enabled) 1f else .45f
            renderEffect = goo
        }) {
            val unit = size.width / 52f
            drawCircle(Color.White, 10f * (1f - progress) * unit, Offset((16f + 12f * progress) * unit, 24f * unit))
            drawCircle(Color.White, 10f * progress * unit, Offset((24f + 12f * progress) * unit, 24f * unit))
        }
    }
}

@androidx.annotation.RequiresApi(31)
private fun liquidGooEffect(radius: Float): RenderEffect {
    val blur = android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.DECAL)
    val goo = android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(floatArrayOf(
        1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,18f,-7f*255f,
    )), blur)
    return android.graphics.RenderEffect.createBlendModeEffect(goo,
        android.graphics.RenderEffect.createOffsetEffect(0f, 0f), android.graphics.BlendMode.SRC_ATOP).asComposeRenderEffect()
}
