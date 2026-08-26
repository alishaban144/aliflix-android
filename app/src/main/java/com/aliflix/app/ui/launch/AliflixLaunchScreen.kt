package com.aliflix.app.ui.launch

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import com.aliflix.app.ui.common.AliflixLogoGeometry
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal const val LAUNCH_TITLE_START_SECONDS = 0.3f
internal const val LAUNCH_TITLE_REVEAL_SECONDS = 0.58f
internal const val LAUNCH_ROTATOR_START_SECONDS = 0.78f
internal const val LAUNCH_ROTATOR_REVEAL_SECONDS = 0.35f
internal const val LAUNCH_WORD_SEQUENCE_START_SECONDS = 0.95f
internal const val LAUNCH_WORD_SLOT_SECONDS = 0.7f
internal const val LAUNCH_WORD_COUNT = 3
internal const val LAUNCH_REDUCED_MOTION_MIN_SECONDS = 0.65f
internal const val LAUNCH_EXIT_DURATION_SECONDS = 0.3f
internal const val LAUNCH_LOGO_MOTION_RATE = 1.15f
internal const val ALIFLIX_WORDMARK = "ALIFLIX"
internal val ALIFLIX_WORDMARK_HEIGHT_SCALES = listOf(1.055f, 1.035f, 1.017f, 1f, 1.017f, 1.035f, 1.055f)

internal fun isLaunchSequenceComplete(
    elapsedSeconds: Float,
    isReducedMotion: Boolean,
): Boolean = elapsedSeconds >= if (isReducedMotion) {
    LAUNCH_REDUCED_MOTION_MIN_SECONDS
} else {
    LAUNCH_WORD_SEQUENCE_START_SECONDS + LAUNCH_WORD_SLOT_SECONDS * LAUNCH_WORD_COUNT
}

internal fun isLaunchExitReady(
    isHomeReady: Boolean,
    elapsedSeconds: Float,
    isReducedMotion: Boolean,
): Boolean = isHomeReady && isLaunchSequenceComplete(elapsedSeconds, isReducedMotion)

internal fun calculateLaunchExitProgress(
    elapsedSeconds: Float,
    exitStartSeconds: Float,
): Float = if (exitStartSeconds >= 0f) {
    ((elapsedSeconds - exitStartSeconds) / LAUNCH_EXIT_DURATION_SECONDS).coerceIn(0f, 1f)
} else {
    0f
}

object AliflixLaunchTheme {
    val Background = Color(0xFF07080C)
    val BaseFill = Color(0xFF0B0911)
    val WhiteTitle = Color(0xFFF7F5FF)
    val SubtitleMuted = Color(0xFFA7ABBA)

    // Linear heat gradient stops from reference HTML (#aliHeatV2)
    val HeatGradientColors = listOf(
        0.00f to Color(0xFF120D1A),
        0.16f to Color(0xFF251D45),
        0.40f to Color(0xFF6E59D9),
        0.63f to Color(0xFFC5B9FF),
        0.79f to Color(0xFFF7F5FF),
        1.00f to Color(0xFF6E59D9),
    )

    // Radial pulse gradient stops from reference HTML (#aliCoreV2)
    val CorePulseColors = listOf(
        0.00f to Color(0xFFFFFFFF),
        0.17f to Color(0xFFF7F5FF).copy(alpha = 0.96f),
        0.42f to Color(0xFFC5B9FF).copy(alpha = 0.84f),
        0.72f to Color(0xFF6E59D9).copy(alpha = 0.44f),
        1.00f to Color(0xFF6E59D9).copy(alpha = 0.00f),
    )
}

private fun easeOutCubic(x: Float): Float {
    val clamped = x.coerceIn(0f, 1f)
    return 1f - (1f - clamped).pow(3)
}

private fun easeInOut(x: Float): Float {
    val clamped = x.coerceIn(0f, 1f)
    return if (clamped < 0.5f) {
        2f * clamped * clamped
    } else {
        1f - (-2f * clamped + 2f).pow(2) / 2f
    }
}

/**
 * Renders the exact Aliflix Heatmap launch screen and controls the
 * launch -> real Home transition layer.
 */
@Composable
fun AliflixLaunchOverlay(
    isHomeReady: Boolean,
    onLaunchComplete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isReducedMotion = remember {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
            scale == 0f
        } catch (_: Throwable) {
            false
        }
    }

    var startNanos by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableFloatStateOf(0f) }
    var exitStartSeconds by remember { mutableFloatStateOf(-1f) }
    var isDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(isReducedMotion) {
        while (!isDismissed) {
            withFrameNanos { frameTimeNanos ->
                if (startNanos == 0L) {
                    startNanos = frameTimeNanos
                }
                elapsedSeconds = ((frameTimeNanos - startNanos) / 1_000_000_000.0).toFloat()
            }
        }
    }

    val exitReady = isLaunchExitReady(isHomeReady, elapsedSeconds, isReducedMotion)

    LaunchedEffect(exitReady) {
        if (exitReady && !isDismissed && exitStartSeconds < 0f) {
            exitStartSeconds = elapsedSeconds
        }
    }

    val exitProgress = calculateLaunchExitProgress(elapsedSeconds, exitStartSeconds)

    LaunchedEffect(exitProgress) {
        if (exitProgress >= 1f && !isDismissed) {
            isDismissed = true
            onLaunchComplete()
        }
    }

    val easeProgress = easeInOut(exitProgress)

    // Home layer transformations
    val homeAlpha = if (isDismissed) 1f else if (exitStartSeconds >= 0f) easeProgress else 0f
    val homeScale = if (isDismissed) 1f else 1.025f - 0.025f * easeProgress

    // Launch layer transformations
    val launchAlpha = if (isDismissed) 0f else 1f - easeProgress
    val launchScale = 1f + 0.035f * easeProgress

    Box(modifier = modifier.fillMaxSize().background(AliflixLaunchTheme.Background)) {
        // Real Home screen rendered underneath
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = homeAlpha
                    scaleX = homeScale
                    scaleY = homeScale
                },
        ) {
            content()
        }

        // Branded Launch Overlay
        if (!isDismissed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = launchAlpha
                        scaleX = launchScale
                        scaleY = launchScale
                    },
                contentAlignment = Alignment.Center,
            ) {
                AliflixLaunchStage(
                    seq = elapsedSeconds,
                    isReducedMotion = isReducedMotion,
                )
            }
        }
    }
}

@Composable
private fun AliflixLaunchStage(
    seq: Float,
    isReducedMotion: Boolean,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val stageWidth = minOf(screenWidth * 0.76f, 390.dp)
    val logoSize = minOf(screenWidth * 0.66f, 270.dp)

    val titleProgress = if (isReducedMotion) 1f else {
        ((seq - LAUNCH_TITLE_START_SECONDS) / LAUNCH_TITLE_REVEAL_SECONDS).coerceIn(0f, 1f)
    }
    val titleOpacity = titleProgress * 0.98f
    val titleTranslateY = if (isReducedMotion) 0.dp else 8.dp * (1f - easeOutCubic(titleProgress))

    val rotatorProgress = if (isReducedMotion) 1f else {
        ((seq - LAUNCH_ROTATOR_START_SECONDS) / LAUNCH_ROTATOR_REVEAL_SECONDS).coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier
            .width(stageWidth)
            .graphicsLayer { translationY = (-10).dp.toPx() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Heatmap Logo Mark
        AliflixHeatmapLogo(
            timeSeconds = if (isReducedMotion) 0f else seq,
            modifier = Modifier.size(logoSize),
        )

        Spacer(Modifier.height((-8).dp))

        AliflixLaunchWordmark(
            opacity = titleOpacity,
            translationY = titleTranslateY,
            modifier = Modifier
                .clearAndSetSemantics { contentDescription = ALIFLIX_WORDMARK },
        )

        Spacer(Modifier.height(13.dp))

        // MOVIES / SERIES / STORIES rotator
        Box(
            modifier = Modifier
                .height(26.dp)
                .width(210.dp)
                .graphicsLayer { alpha = rotatorProgress },
            contentAlignment = Alignment.Center,
        ) {
            val words = listOf("MOVIES", "SERIES", "STORIES")
            words.forEachIndexed { index, word ->
                val (wordOpacity, wordY) = calculateWordState(
                    seq = seq,
                    wordIndex = index,
                    isReducedMotion = isReducedMotion,
                )
                Text(
                    text = word,
                    color = AliflixLaunchTheme.SubtitleMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.32.em,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = wordOpacity
                            translationY = wordY.dp.toPx()
                            translationX = (0.16f * 12.sp.value).dp.toPx()
                        },
                )
            }
        }
    }
}

@Composable
private fun AliflixLaunchWordmark(
    opacity: Float,
    translationY: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val tracking = with(LocalDensity.current) { (32.sp * 0.34f).toDp() }
    Row(
        modifier = modifier.graphicsLayer {
            alpha = opacity
            this.translationY = translationY.toPx()
        },
        horizontalArrangement = Arrangement.spacedBy(tracking),
        verticalAlignment = Alignment.Top,
    ) {
        ALIFLIX_WORDMARK.forEachIndexed { index, letter ->
            Text(
                text = letter.toString(),
                color = AliflixLaunchTheme.WhiteTitle,
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.graphicsLayer {
                    scaleY = ALIFLIX_WORDMARK_HEIGHT_SCALES[index]
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            )
        }
    }
}

internal fun calculateWordState(
    seq: Float,
    wordIndex: Int,
    isReducedMotion: Boolean,
): Pair<Float, Float> {
    if (isReducedMotion) {
        return if (wordIndex == 0) 1f to 0f else 0f to 8f
    }
    if (seq < LAUNCH_WORD_SEQUENCE_START_SECONDS) return 0f to 8f
    val wordTime = seq - LAUNCH_WORD_SEQUENCE_START_SECONDS
    val sequenceDuration = LAUNCH_WORD_SLOT_SECONDS * LAUNCH_WORD_COUNT
    if (wordTime >= sequenceDuration) {
        return if (wordIndex == LAUNCH_WORD_COUNT - 1) 1f to 0f else 0f to 8f
    }
    val activeIdx = floor(wordTime / LAUNCH_WORD_SLOT_SECONDS).toInt()
    if (wordIndex != activeIdx) return 0f to 8f

    val local = (wordTime % LAUNCH_WORD_SLOT_SECONDS) / LAUNCH_WORD_SLOT_SECONDS
    return when {
        local < 0.18f -> {
            val p = local / 0.18f
            val opacity = p
            val y = 8f * (1f - easeOutCubic(p))
            opacity to y
        }
        local < 0.76f || wordIndex == LAUNCH_WORD_COUNT - 1 -> {
            1f to 0f
        }
        else -> {
            val p = (local - 0.76f) / 0.24f
            val opacity = 1f - p
            val y = -8f * easeInOut(p)
            opacity to y
        }
    }
}

@Composable
fun AliflixHeatmapLogo(
    timeSeconds: Float,
    modifier: Modifier = Modifier,
) {
    val blurPaint = remember {
        Paint().apply {
            isAntiAlias = true
            maskFilter = BlurMaskFilter(4.8f, BlurMaskFilter.Blur.NORMAL)
        }
    }
    val softBandPaint = remember {
        Paint().apply {
            isAntiAlias = true
            maskFilter = BlurMaskFilter(1.1f, BlurMaskFilter.Blur.NORMAL)
        }
    }

    Canvas(modifier = modifier) {
        val unit = minOf(size.width, size.height)
        val left = (size.width - unit) / 2f
        val top = (size.height - unit) / 2f
        val scaleFactor = unit / AliflixLogoGeometry.VIEWBOX_SIZE
        val center = Offset(left + 50f * scaleFactor, top + 50f * scaleFactor)

        val logoPath = AliflixLogoGeometry.createCombinedLogoPath(unit, left, top)

        // Mathematical motion values from reference HTML:
        // sweepX = -104 + ((t * 24) % 194)
        // pulseX = -14 + ((t * 31) % 148)
        // pulseY = 87 - sin(t * 1.55) * 15
        // bandX = -50 + ((t * 27) % 190)
        // breathe = 1 + sin(t * 2.1) * 0.065
        val t = timeSeconds * LAUNCH_LOGO_MOTION_RATE
        val sweepX = -104f + ((t * 24f) % 194f)
        val pulseX = -14f + ((t * 31f) % 148f)
        val pulseY = 87f - sin(t * 1.55f) * 15f
        val bandX = -50f + ((t * 27f) % 190f)
        val breathe = 1f + sin(t * 2.1f) * 0.065f

        val heatBrush = Brush.linearGradient(
            colorStops = AliflixLaunchTheme.HeatGradientColors.toTypedArray(),
            start = Offset(left, top + unit),
            end = Offset(left + unit, top),
        )

        // --- LAYER 1: Blurred Outer Glow (opacity 0.62) ---
        clipPath(logoPath) {
            withTransform({
                translate(left = (sweepX - 4f) * scaleFactor, top = 0f)
                rotate(degrees = -6f, pivot = center)
            }) {
                drawRect(
                    brush = heatBrush,
                    topLeft = Offset(left - 130f * scaleFactor, top - 25f * scaleFactor),
                    size = Size(280f * scaleFactor, 160f * scaleFactor),
                    alpha = 0.62f,
                )
            }

            val glowPulseCenter = Offset(
                left + (pulseX - 2f) * scaleFactor,
                top + (pulseY + 1.5f) * scaleFactor,
            )
            val glowPulseRadius = 40f * (breathe + 0.05f) * scaleFactor
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = AliflixLaunchTheme.CorePulseColors.toTypedArray(),
                    center = glowPulseCenter,
                    radius = maxOf(1f, glowPulseRadius),
                ),
                radius = maxOf(1f, glowPulseRadius),
                center = glowPulseCenter,
                alpha = 0.62f,
            )
        }

        // --- LAYER 2: Base Dark Fill + Main Heat Sweep + Main Core Pulse ---
        clipPath(logoPath) {
            // Dark base fill (#0B0911)
            drawRect(
                color = AliflixLaunchTheme.BaseFill,
                topLeft = Offset(left, top),
                size = Size(unit, unit),
            )

            // Main diagonal heat sweep
            withTransform({
                translate(left = sweepX * scaleFactor, top = 0f)
                rotate(degrees = -6f, pivot = center)
            }) {
                drawRect(
                    brush = heatBrush,
                    topLeft = Offset(left - 130f * scaleFactor, top - 25f * scaleFactor),
                    size = Size(280f * scaleFactor, 160f * scaleFactor),
                )
            }

            // Main bright radial pulse
            val mainPulseCenter = Offset(
                left + pulseX * scaleFactor,
                top + pulseY * scaleFactor,
            )
            val mainPulseRadius = 35f * breathe * scaleFactor
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = AliflixLaunchTheme.CorePulseColors.toTypedArray(),
                    center = mainPulseCenter,
                    radius = maxOf(1f, mainPulseRadius),
                ),
                radius = maxOf(1f, mainPulseRadius),
                center = mainPulseCenter,
            )
        }

        // --- LAYER 3: Travelling White/Lilac Hot Band (opacity ~0.31) ---
        clipPath(logoPath) {
            withTransform({
                translate(left = bandX * scaleFactor, top = 0f)
                rotate(degrees = -18f, pivot = center)
            }) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.304f),
                    topLeft = Offset(left - 44f * scaleFactor, top - 12f * scaleFactor),
                    size = Size(30f * scaleFactor, 145f * scaleFactor),
                    cornerRadius = CornerRadius(11f * scaleFactor, 11f * scaleFactor),
                )
            }
        }
    }
}
