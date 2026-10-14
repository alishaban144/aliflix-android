package com.aliflix.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import kotlin.math.floor

internal const val LUMA_SPIN_SECONDS = 2.5f
private val lumaEase = CubicBezierEasing(.25f, .1f, .25f, 1f) // CSS default `ease`, per interval.
private val lumaInsets = listOf(
    floatArrayOf(0f,35f,35f,0f), floatArrayOf(0f,35f,0f,0f),
    floatArrayOf(35f,35f,0f,0f), floatArrayOf(35f,0f,0f,0f),
    floatArrayOf(35f,0f,0f,35f), floatArrayOf(0f,0f,0f,35f),
    floatArrayOf(0f,0f,35f,35f), floatArrayOf(0f,0f,35f,0f),
)
internal fun lumaSpinRect(seconds: Float, second: Boolean = false): Rect {
    val phase = (((seconds / LUMA_SPIN_SECONDS + if (second) .5f else 0f) % 1f) + 1f) % 1f * 8f
    val index = floor(phase).toInt().coerceIn(0,7)
    val p = lumaEase.transform(phase - index)
    val a = lumaInsets[index]; val b = lumaInsets[(index + 1) % 8]
    fun inset(i: Int) = a[i] + (b[i] - a[i]) * p
    return Rect(inset(3), inset(0), 65f - inset(1), 65f - inset(2))
}

/** Native version of Ritik Singh's Luma Spin: two inset outlines, never a rotating ring. */
@Composable
internal fun AliflixLumaSpin(modifier: Modifier = Modifier, color: Color = AliflixAccentSecondary) {
    val motion = rememberMobileMotionEnabled()
    val seconds = rememberMobileAnimationTime(motion && rememberMobileResumed())
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
        val unit = size.minDimension / 65f
        val origin = Offset((size.width - 65f * unit) / 2f, (size.height - 65f * unit) / 2f)
        repeat(2) { index ->
            val rect = lumaSpinRect(if (motion) seconds else 0f, index == 1)
            // CSS inset shadow is wholly inside the box; inset the stroke by half its width.
            drawRoundRect(color, origin + Offset((rect.left + 1.5f) * unit, (rect.top + 1.5f) * unit),
                Size((rect.width - 3f) * unit, (rect.height - 3f) * unit),
                CornerRadius(50f * unit), style = Stroke(3f * unit))
        }
    }
}
