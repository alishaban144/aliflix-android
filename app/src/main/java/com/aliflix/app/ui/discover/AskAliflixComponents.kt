package com.aliflix.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.ImageDecoderDecoder
import com.aliflix.app.R
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
    zoom: Float = 2.05f,
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val animatedImageLoader = remember(context) {
        ImageLoader.Builder(context.applicationContext)
            .components { add(ImageDecoderDecoder.Factory()) }
            .build()
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black)
            .border(
                width = 1.dp,
                color = AliflixAccentSecondary.copy(alpha = 0.24f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = R.drawable.ask_aliflix_orb,
            imageLoader = animatedImageLoader,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                },
        )
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
