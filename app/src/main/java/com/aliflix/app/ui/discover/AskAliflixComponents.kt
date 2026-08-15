package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixEditorialWarm
import com.aliflix.app.ui.theme.AliflixBackgroundBase
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary

@Composable
fun AskAliflixOrbAnimation(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "ask-intelligence-core")
    val shellRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_800, easing = LinearEasing),
        ),
        label = "ask-core-shell-flow",
    )
    val innerRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 17_600, easing = LinearEasing),
        ),
        label = "ask-core-inner-flow",
    )
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ask-core-breath",
    )
    val semanticModifier = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }

    Canvas(modifier = modifier.then(semanticModifier)) {
        val diameter = size.minDimension
        val coreCenter = Offset(
            x = center.x,
            y = center.y + diameter * 0.025f,
        )
        val pulse = 0.985f + breath * 0.025f
        val shellWidth = (diameter * 0.024f).coerceAtLeast(1.1f)
        val shellDrift = kotlin.math.sin(Math.toRadians(shellRotation.toDouble())).toFloat() * 2.4f
        val innerDrift = kotlin.math.sin(Math.toRadians(innerRotation.toDouble())).toFloat() * 3.2f

        fun outerShell(expansion: Float = 0f): Path {
            val d = diameter
            val x = coreCenter.x
            val y = coreCenter.y
            return Path().apply {
                moveTo(x - d * (0.090f + expansion), y - d * (0.276f + expansion))
                cubicTo(
                    x - d * 0.026f,
                    y - d * (0.332f + expansion),
                    x + d * 0.040f,
                    y - d * 0.267f,
                    x + d * 0.112f,
                    y - d * (0.258f + expansion),
                )
                cubicTo(
                    x + d * (0.254f + expansion),
                    y - d * 0.224f,
                    x + d * (0.345f + expansion),
                    y - d * 0.076f,
                    x + d * (0.318f + expansion),
                    y + d * 0.112f,
                )
                cubicTo(
                    x + d * 0.287f,
                    y + d * (0.238f + expansion),
                    x + d * 0.102f,
                    y + d * (0.348f + expansion),
                    x - d * 0.075f,
                    y + d * (0.330f + expansion),
                )
                cubicTo(
                    x - d * (0.226f + expansion),
                    y + d * 0.306f,
                    x - d * (0.335f + expansion),
                    y + d * 0.158f,
                    x - d * (0.326f + expansion),
                    y - d * 0.018f,
                )
                cubicTo(
                    x - d * 0.316f,
                    y - d * (0.170f + expansion),
                    x - d * 0.206f,
                    y - d * 0.264f,
                    x - d * (0.090f + expansion),
                    y - d * (0.276f + expansion),
                )
                close()
            }
        }

        fun innerCurrent(): Path {
            val d = diameter
            val x = coreCenter.x
            val y = coreCenter.y
            return Path().apply {
                moveTo(x - d * 0.205f, y - d * 0.132f)
                cubicTo(
                    x - d * 0.267f,
                    y + d * 0.010f,
                    x - d * 0.184f,
                    y + d * 0.165f,
                    x - d * 0.040f,
                    y + d * 0.213f,
                )
                cubicTo(
                    x + d * 0.102f,
                    y + d * 0.254f,
                    x + d * 0.238f,
                    y + d * 0.114f,
                    x + d * 0.213f,
                    y - d * 0.023f,
                )
                cubicTo(
                    x + d * 0.193f,
                    y - d * 0.130f,
                    x + d * 0.096f,
                    y - d * 0.184f,
                    x + d * 0.028f,
                    y - d * 0.151f,
                )
            }
        }

        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.50f to Color(0xFF7E5CFF).copy(alpha = 0.035f + breath * 0.018f),
                    0.74f to Color(0xFF14E8FF).copy(alpha = 0.10f + breath * 0.025f),
                    1f to Color.Transparent,
                ),
                center = coreCenter,
                radius = diameter * 0.47f,
            ),
            radius = diameter * 0.47f,
            center = coreCenter,
        )

        withTransform({
            rotate(degrees = shellDrift, pivot = coreCenter)
            scale(scaleX = pulse, scaleY = pulse, pivot = coreCenter)
        }) {
            drawPath(
                path = outerShell(),
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.48f to Color(0xFF7E67FF).copy(alpha = 0.025f),
                        0.78f to Color(0xFF18DFF4).copy(alpha = 0.075f + breath * 0.018f),
                        1f to Color.Transparent,
                    ),
                    center = coreCenter,
                    radius = diameter * 0.39f,
                ),
            )
            drawPath(
                path = outerShell(0.026f),
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.13f to Color(0xFF28E7FF).copy(alpha = 0.07f),
                        0.44f to Color(0xFF34F0FF).copy(alpha = 0.16f),
                        0.70f to Color(0xFF736AFF).copy(alpha = 0.08f),
                        1f to Color.Transparent,
                    ),
                    center = coreCenter,
                ),
                style = Stroke(
                    width = diameter * 0.078f,
                    cap = StrokeCap.Round,
                ),
            )
            drawPath(
                path = outerShell(),
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFB1FBFF).copy(alpha = 0.74f),
                        0.11f to Color(0xFF28E9FF).copy(alpha = 0.92f),
                        0.42f to Color(0xFF11DDF4).copy(alpha = 0.69f),
                        0.69f to Color(0xFF7183FF).copy(alpha = 0.52f),
                        0.84f to Color(0xFF20E7FF).copy(alpha = 0.84f),
                        1f to Color(0xFFB1FBFF).copy(alpha = 0.74f),
                    ),
                    center = coreCenter,
                ),
                style = Stroke(
                    width = shellWidth,
                    cap = StrokeCap.Round,
                ),
            )
            val crest = Path().apply {
                moveTo(coreCenter.x - diameter * 0.096f, coreCenter.y - diameter * 0.276f)
                cubicTo(
                    coreCenter.x - diameter * 0.012f,
                    coreCenter.y - diameter * 0.332f,
                    coreCenter.x + diameter * 0.024f,
                    coreCenter.y - diameter * 0.258f,
                    coreCenter.x + diameter * 0.118f,
                    coreCenter.y - diameter * 0.258f,
                )
            }
            drawPath(
                path = crest,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF35E9FF).copy(alpha = 0.34f),
                        Color(0xFFE9FEFF),
                        Color(0xFF1BE8FA).copy(alpha = 0.78f),
                    ),
                    start = Offset(coreCenter.x - diameter * 0.10f, coreCenter.y - diameter * 0.31f),
                    end = Offset(coreCenter.x + diameter * 0.15f, coreCenter.y - diameter * 0.26f),
                ),
                style = Stroke(width = shellWidth * 0.72f, cap = StrokeCap.Round),
            )
        }

        withTransform({
            rotate(degrees = innerDrift, pivot = coreCenter)
            scale(
                scaleX = 0.99f + breath * 0.018f,
                scaleY = 1.01f - breath * 0.014f,
                pivot = coreCenter,
            )
        }) {
            drawPath(
                path = innerCurrent(),
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF806BFF).copy(alpha = 0.22f),
                        Color(0xFF24E5FF).copy(alpha = 0.17f),
                        Color.Transparent,
                    ),
                    center = coreCenter,
                ),
                style = Stroke(width = diameter * 0.062f, cap = StrokeCap.Round),
            )
            drawPath(
                path = innerCurrent(),
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFFC3F8FF).copy(alpha = 0.84f),
                        0.22f to Color(0xFF7F72FF).copy(alpha = 0.92f),
                        0.60f to Color(0xFFB76DFF).copy(alpha = 0.82f),
                        1f to Color(0xFF27E7F9).copy(alpha = 0.74f),
                    ),
                    start = Offset(coreCenter.x - diameter * 0.24f, coreCenter.y - diameter * 0.16f),
                    end = Offset(coreCenter.x + diameter * 0.24f, coreCenter.y + diameter * 0.12f),
                ),
                style = Stroke(
                    width = (diameter * 0.020f).coerceAtLeast(1f),
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
}

@Composable
fun AskAliflixSparkMark(
    modifier: Modifier = Modifier,
    animated: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "ask-spark-mark")
    val rotation = if (animated) {
        transition.animateFloat(
            initialValue = -3.5f,
            targetValue = 3.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ask-spark-rotation",
        ).value
    } else {
        0f
    }
    val scale = if (animated) {
        transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_250),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ask-spark-scale",
        ).value
    } else {
        1f
    }

    Canvas(
        modifier = modifier.graphicsLayer {
            rotationZ = rotation
            scaleX = scale
            scaleY = scale
        },
    ) {
        fun sparkle(cx: Float, cy: Float, rx: Float, ry: Float): Path = Path().apply {
            val pinchX = rx * 0.18f
            val pinchY = ry * 0.18f
            moveTo(cx, cy - ry)
            cubicTo(cx + pinchX, cy - pinchY, cx + rx - pinchX, cy - pinchY, cx + rx, cy)
            cubicTo(cx + rx - pinchX, cy + pinchY, cx + pinchX, cy + ry - pinchY, cx, cy + ry)
            cubicTo(cx - pinchX, cy + ry - pinchY, cx - rx + pinchX, cy + pinchY, cx - rx, cy)
            cubicTo(cx - rx + pinchX, cy - pinchY, cx - pinchX, cy - ry + pinchY, cx, cy - ry)
            close()
        }

        drawCircle(
            color = AliflixAccentPrimary.copy(alpha = 0.15f),
            radius = size.minDimension * 0.47f,
            center = center,
        )
        drawPath(
            path = sparkle(
                cx = size.width * 0.48f,
                cy = size.height * 0.53f,
                rx = size.width * 0.27f,
                ry = size.height * 0.35f,
            ),
            brush = Brush.linearGradient(
                colors = listOf(Color.White, AliflixAccentSecondary, AliflixAccentPrimary),
            ),
        )
        drawPath(
            path = sparkle(
                cx = size.width * 0.78f,
                cy = size.height * 0.25f,
                rx = size.width * 0.10f,
                ry = size.height * 0.13f,
            ),
            color = AliflixEditorialWarm,
        )
        drawPath(
            path = sparkle(
                cx = size.width * 0.20f,
                cy = size.height * 0.77f,
                rx = size.width * 0.065f,
                ry = size.height * 0.085f,
            ),
            color = Color.White.copy(alpha = 0.88f),
        )
    }
}

@Composable
fun AskAliflixBetaBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(AliflixAccentPrimary.copy(alpha = 0.18f))
            .border(1.dp, AliflixAccentSecondary.copy(alpha = 0.48f), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "BETA",
            color = AliflixAccentSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.7.sp,
        )
    }
}

@Composable
fun AskAliflixChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = AskAliflixMotion.pressSpec(),
        label = "ask-chip-scale",
    )
    val background by animateColorAsState(
        targetValue = if (isSelected) AliflixAccentPrimary.copy(alpha = 0.20f) else AliflixSurfaceSecondary.copy(alpha = 0.82f),
        animationSpec = AskAliflixMotion.chipSpec(),
        label = "ask-chip-background",
    )
    val border by animateColorAsState(
        targetValue = if (isSelected) AliflixAccentPrimary else AliflixBorderSubtle,
        animationSpec = AskAliflixMotion.chipSpec(),
        label = "ask-chip-border",
    )
    val foreground by animateColorAsState(
        targetValue = if (isSelected) AliflixContentPrimary else AliflixContentSecondary,
        animationSpec = AskAliflixMotion.chipSpec(),
        label = "ask-chip-foreground",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
            }
            AnimatedVisibility(visible = isSelected, enter = fadeIn(), exit = fadeOut()) {
                Row {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = AliflixAccentSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
            }
            Text(
                text = label,
                color = foreground,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun AskAliflixStickyCta(
    label: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.975f else 1f,
        animationSpec = AskAliflixMotion.pressSpec(),
        label = "ask-cta-scale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        AliflixBackgroundBase.copy(alpha = 0.94f),
                        AliflixBackgroundBase,
                    )
                )
            )
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled && !loading,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = AliflixAccentPrimary,
                contentColor = Color.White,
                disabledContainerColor = AliflixSurfaceElevated,
                disabledContentColor = AliflixContentSecondary.copy(alpha = 0.45f),
            ),
            shape = RoundedCornerShape(17.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                AskAliflixSparkMark(
                    modifier = Modifier.size(20.dp),
                    animated = false,
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            if (!loading) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun AskAliflixPoster(
    media: Media,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(
                        AliflixAccentPrimary.copy(alpha = 0.36f),
                        AliflixSurfaceElevated,
                    )
                )
            )
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = media.title.firstOrNull()?.uppercase() ?: "A",
            color = AliflixAccentSecondary.copy(alpha = 0.7f),
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        AsyncImage(
            model = media.posterUrl,
            contentDescription = "${media.title} poster",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
