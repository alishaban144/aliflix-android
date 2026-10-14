package com.aliflix.app.ui.common

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.aliflix.app.ui.theme.*

internal data class LiquidToggleGeometry(val leftX: Float, val leftRadius: Float, val rightX: Float, val rightRadius: Float)
internal fun liquidToggleGeometry(progress: Float): LiquidToggleGeometry {
    val p = progress.coerceIn(0f, 1f)
    return LiquidToggleGeometry(16f + 12f * p, 10f * (1f - p), 24f + 12f * p, 10f * p)
}

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
    val goo = remember { LiquidToggleMask() }
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
            goo.draw(progress, if (checked) 1f else 0f)
            drawIntoCanvas { canvas ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = if (enabled) 255 else 115 }
                canvas.nativeCanvas.drawBitmap(goo.output, null,
                    android.graphics.RectF(0f, 0f, size.width, size.height), paint)
            }
        }
    }
}

/** The reference's 52x32 SVG circles, Gaussian blur (sigma 2), and alpha matrix 18/-7. */
private class LiquidToggleMask {
    private val scale = 3f
    private val blurred = Bitmap.createBitmap(156, 144, Bitmap.Config.ARGB_8888)
    val output: Bitmap = Bitmap.createBitmap(156, 144, Bitmap.Config.ARGB_8888)
    private val blurCanvas = android.graphics.Canvas(blurred)
    private val pixels = IntArray(156 * 144)
    private var lastProgress = Float.NaN
    private var lastDrop = Float.NaN
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        maskFilter = BlurMaskFilter(2.598f * scale, BlurMaskFilter.Blur.NORMAL)
    }
    fun draw(progress: Float, drop: Float) {
        if (progress == lastProgress && drop == lastDrop) return
        lastProgress = progress
        lastDrop = drop
        blurred.eraseColor(android.graphics.Color.TRANSPARENT)
        output.eraseColor(android.graphics.Color.TRANSPARENT)
        val g = liquidToggleGeometry(progress)
        blurCanvas.drawCircle(g.leftX * scale, 24f * scale, g.leftRadius * scale, blurPaint)
        blurCanvas.drawCircle(g.rightX * scale, 24f * scale, g.rightRadius * scale, blurPaint)
        if (drop > 0f) blurCanvas.drawCircle(35f * scale, 7f * scale, 2.5f * scale, blurPaint)
        // Apply the SVG alpha matrix explicitly. Some device rasterizers dither a
        // large negative ColorMatrix alpha offset into transparent pixels.
        blurred.getPixels(pixels,0,156,0,0,156,144)
        pixels.indices.forEach { i ->
            val alpha = ((pixels[i] ushr 24) * 18 - 7 * 255).coerceIn(0,255)
            pixels[i] = if (alpha == 0) 0 else (alpha shl 24) or 0x00FFFFFF
        }
        output.setPixels(pixels,0,156,0,0,156,144)
    }
}
