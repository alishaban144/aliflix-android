package com.aliflix.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixContentPrimary

object AliflixLogoGeometry {
    const val VIEWBOX_SIZE = 100f

    // Reference SVG values: viewBox="0 0 100 100"
    // Circle: cx=25, cy=66, r=11.5
    const val CIRCLE_CX = 25f
    const val CIRCLE_CY = 66f
    const val CIRCLE_RADIUS = 11.5f

    // Shadow Blade: (59, 19) -> (80, 84) -> (68, 84) -> (56, 47)
    val SHADOW_BLADE_POINTS = listOf(
        59f to 19f,
        80f to 84f,
        68f to 84f,
        56f to 47f,
    )

    // Light Blade: (43, 19) -> (59, 19) -> (68, 84) -> (55, 84)
    val LIGHT_BLADE_POINTS = listOf(
        43f to 19f,
        59f to 19f,
        68f to 84f,
        55f to 84f,
    )

    fun createCombinedLogoPath(unit: Float, left: Float = 0f, top: Float = 0f): Path {
        val scale = unit / VIEWBOX_SIZE
        return Path().apply {
            addOval(
                Rect(
                    center = Offset(left + CIRCLE_CX * scale, top + CIRCLE_CY * scale),
                    radius = CIRCLE_RADIUS * scale,
                ),
            )
            moveTo(left + SHADOW_BLADE_POINTS[0].first * scale, top + SHADOW_BLADE_POINTS[0].second * scale)
            for (i in 1 until SHADOW_BLADE_POINTS.size) {
                lineTo(left + SHADOW_BLADE_POINTS[i].first * scale, top + SHADOW_BLADE_POINTS[i].second * scale)
            }
            close()

            moveTo(left + LIGHT_BLADE_POINTS[0].first * scale, top + LIGHT_BLADE_POINTS[0].second * scale)
            for (i in 1 until LIGHT_BLADE_POINTS.size) {
                lineTo(left + LIGHT_BLADE_POINTS[i].first * scale, top + LIGHT_BLADE_POINTS[i].second * scale)
            }
            close()
        }
    }
}

@Composable
fun AliflixLogoMark(
    modifier: Modifier = Modifier,
    primaryColor: Color = AliflixAccentPrimary,
    highlightColor: Color = AliflixAccentSecondary,
) {
    Canvas(modifier = modifier) {
        val unit = minOf(size.width, size.height)
        val left = (size.width - unit) / 2f
        val top = (size.height - unit) / 2f
        val scale = unit / AliflixLogoGeometry.VIEWBOX_SIZE

        fun point(x: Float, y: Float) = Offset(
            x = left + x * scale,
            y = top + y * scale,
        )

        drawCircle(
            color = highlightColor,
            radius = AliflixLogoGeometry.CIRCLE_RADIUS * scale,
            center = point(AliflixLogoGeometry.CIRCLE_CX, AliflixLogoGeometry.CIRCLE_CY),
        )

        val shadowBlade = Path().apply {
            moveTo(point(59f, 19f).x, point(59f, 19f).y)
            lineTo(point(80f, 84f).x, point(80f, 84f).y)
            lineTo(point(68f, 84f).x, point(68f, 84f).y)
            lineTo(point(56f, 47f).x, point(56f, 47f).y)
            close()
        }
        drawPath(path = shadowBlade, color = primaryColor)

        val lightBlade = Path().apply {
            moveTo(point(43f, 19f).x, point(43f, 19f).y)
            lineTo(point(59f, 19f).x, point(59f, 19f).y)
            lineTo(point(68f, 84f).x, point(68f, 84f).y)
            lineTo(point(55f, 84f).x, point(55f, 84f).y)
            close()
        }
        drawPath(
            path = lightBlade,
            brush = Brush.linearGradient(
                colors = listOf(AliflixContentPrimary, highlightColor),
                start = point(43f, 19f),
                end = point(68f, 84f),
            ),
        )
    }
}
