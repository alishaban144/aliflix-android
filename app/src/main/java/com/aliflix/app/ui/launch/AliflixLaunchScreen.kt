package com.aliflix.app.ui.launch

import android.animation.ValueAnimator
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import com.aliflix.app.ui.common.rememberMobileAnimationClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.core.CubicBezierEasing
import com.aliflix.app.ui.common.rememberMobileMotionEnabled
import com.aliflix.app.ui.common.rememberMobileResumed
import com.aliflix.app.ui.common.rememberMobileAnimationTime
import com.aliflix.app.ui.common.AliflixLogoMark
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
import kotlinx.coroutines.isActive
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val launchLayerEase = CubicBezierEasing(.22f,.61f,.36f,1f)

internal const val LAUNCH_TITLE_START_SECONDS = 0.18f
internal const val LAUNCH_TITLE_REVEAL_SECONDS = 0.72f
internal const val LAUNCH_ROTATOR_START_SECONDS = 0.94f
internal const val LAUNCH_ROTATOR_REVEAL_SECONDS = 0.12f
internal const val LAUNCH_WORD_SEQUENCE_START_SECONDS = 1.08f
internal const val LAUNCH_WORD_SLOT_SECONDS = 0.42f
internal const val LAUNCH_WORD_COUNT = 3
internal const val LAUNCH_FINAL_WORD_SLOT_SECONDS = 0.36f
internal const val LAUNCH_BRAND_REVEAL_SECONDS = 0.90f
internal const val LAUNCH_WORD_FADE_SECONDS = 0.1f
internal const val LAUNCH_SEQUENCE_SECONDS = LAUNCH_WORD_SEQUENCE_START_SECONDS +
    LAUNCH_WORD_SLOT_SECONDS * (LAUNCH_WORD_COUNT - 1) + LAUNCH_FINAL_WORD_SLOT_SECONDS
internal const val LAUNCH_HOME_PREPARE_SECONDS = 0f
internal const val LAUNCH_NETWORK_LIMIT_SECONDS = LAUNCH_SEQUENCE_SECONDS
internal const val LAUNCH_REDUCED_NETWORK_LIMIT_SECONDS = 0.3f

internal fun launchBrandProgress(seconds: Float, reducedMotion: Boolean): Float =
    if (reducedMotion) 1f else launchLayerEase.transform((seconds / LAUNCH_BRAND_REVEAL_SECONDS).coerceIn(0f,1f))

internal fun isLaunchContentPreparationReady(seconds: Float, reducedMotion: Boolean): Boolean =
    reducedMotion || seconds >= LAUNCH_HOME_PREPARE_SECONDS
internal const val LAUNCH_REDUCED_MOTION_MIN_SECONDS = 0.15f
internal const val LAUNCH_EXIT_DURATION_SECONDS = 0.30f
internal const val LAUNCH_LOGO_MOTION_RATE = 1.15f
internal const val ALIFLIX_WORDMARK = "ALIFLIX"
internal val ALIFLIX_WORDMARK_HEIGHT_SCALES = listOf(1.055f, 1.035f, 1.017f, 1f, 1.017f, 1.035f, 1.055f)

internal fun isLaunchSequenceComplete(
    elapsedSeconds: Float,
    isReducedMotion: Boolean,
): Boolean = elapsedSeconds >= if (isReducedMotion) {
    LAUNCH_REDUCED_MOTION_MIN_SECONDS
} else {
    LAUNCH_SEQUENCE_SECONDS
}

/** Cached Home is already usable while its background refresh is still running. */
internal fun isLaunchHomeReady(loading: Boolean, hasContent: Boolean, hasError: Boolean): Boolean =
    hasContent || (!loading && hasError)

internal fun isLaunchExitReady(
    isHomeReady: Boolean,
    elapsedSeconds: Float,
    isReducedMotion: Boolean,
): Boolean = isLaunchSequenceComplete(elapsedSeconds, isReducedMotion) &&
    (isHomeReady || elapsedSeconds >= if (isReducedMotion) LAUNCH_REDUCED_NETWORK_LIMIT_SECONDS else LAUNCH_NETWORK_LIMIT_SECONDS)

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
    val isReducedMotion = !rememberMobileMotionEnabled()
    val resumed = rememberMobileResumed()
    var clockFinished by remember { mutableStateOf(false) }
    var homeFrameReady by remember { mutableStateOf(false) }
    val clock = rememberMobileAnimationClock(resumed && homeFrameReady && !clockFinished, infinite = false)
    var exitStartSeconds by remember { mutableFloatStateOf(-1f) }
    var isDismissed by remember { mutableStateOf(false) }
    val latestComplete by androidx.compose.runtime.rememberUpdatedState(onLaunchComplete)
    LaunchedEffect(Unit) {
        // First composition/layout happens behind the opaque launch layer before
        // its clock starts. No expensive cold composition during the exit fade.
        withFrameNanos { }
        withFrameNanos { }
        homeFrameReady = true
    }

    // Observe milestones, not every frame. Home composes independently from the launch clock.
    LaunchedEffect(isHomeReady, isReducedMotion, homeFrameReady) {
        if (homeFrameReady && !isDismissed) {
            snapshotFlow { isLaunchExitReady(isHomeReady, clock.value, isReducedMotion) }.first { it }
            if (exitStartSeconds < 0f) exitStartSeconds = clock.value
        }
    }
    LaunchedEffect(exitStartSeconds) {
        if (exitStartSeconds >= 0f) {
            snapshotFlow { calculateLaunchExitProgress(clock.value, exitStartSeconds) >= 1f }.first { it }
            isDismissed = true
            clockFinished = true
            latestComplete()
        }
    }
    val exit: () -> Float = { easeInOut(calculateLaunchExitProgress(clock.value, exitStartSeconds)) }

    Box(modifier = modifier.fillMaxSize().background(AliflixLaunchTheme.Background)) {
        // Real Home screen rendered underneath
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // A zero-alpha layer skips drawing entirely. Warm Home's
                    // display lists/textures underneath the opaque launch layer.
                    alpha = if (isDismissed) 1f else if (exitStartSeconds >= 0f) kotlin.math.sqrt(exit()) else .001f
                    val scale = if (isDismissed || isReducedMotion) 1f else 1.018f - .018f * exit()
                    scaleX = scale
                    scaleY = scale
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
                        alpha = (1f - exit()).pow(2)
                        val scale = if (isReducedMotion) 1f else 1f + .025f * exit()
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center,
            ) {
                AliflixLaunchStage(
                    clock = clock,
                    isReducedMotion = isReducedMotion,
                )
            }
        }
    }
}

@Composable
private fun AliflixLaunchStage(
    clock: State<Float>,
    isReducedMotion: Boolean,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val stageWidth = minOf(screenWidth * 0.76f, 390.dp)
    val logoSize = minOf(screenWidth * 0.66f, 270.dp)

    val titleProgress = { if (isReducedMotion) 1f else
        ((clock.value - LAUNCH_TITLE_START_SECONDS) / LAUNCH_TITLE_REVEAL_SECONDS).coerceIn(0f, 1f) }

    Column(
        modifier = Modifier
            .width(stageWidth)
            .graphicsLayer {
                val brand = launchBrandProgress(clock.value,isReducedMotion)
                translationY = (-10f + 8f * (1f-brand)).dp.toPx()
                val camera = 1.08f - .08f * brand
                scaleX = camera; scaleY = camera
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Three staggered poster panels assemble the canonical artwork on one shared clock.
        PosterRevealLogo(clock, isReducedMotion, Modifier.size(logoSize))

        Spacer(Modifier.height(0.dp))

        AliflixLaunchWordmark(
            opacity = 1f,
            translationY = 0.dp,
            modifier = Modifier.fillMaxWidth()
                .graphicsLayer {
                    alpha = if (isReducedMotion) 1f else launchLayerEase.transform(titleProgress())
                }
                .drawWithContent {
                    clipRect(bottom = size.height * launchLayerEase.transform(titleProgress())) { this@drawWithContent.drawContent() }
                }
                .clearAndSetSemantics { contentDescription = ALIFLIX_WORDMARK },
        )

        Spacer(Modifier.height(13.dp))

        // MOVIES / SERIES / STORIES rotator
        Box(
            modifier = Modifier
                .height(26.dp)
                .width(210.dp)
                .graphicsLayer { alpha = if (isReducedMotion) 1f else
                    ((clock.value - LAUNCH_ROTATOR_START_SECONDS) / LAUNCH_ROTATOR_REVEAL_SECONDS).coerceIn(0f,1f) },
            contentAlignment = Alignment.Center,
        ) {
            val words = listOf("MOVIES", "SERIES", "STORIES")
            words.forEachIndexed { index, word ->
                Text(
                    text = word,
                    color = AliflixLaunchTheme.SubtitleMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.32.em,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            val (wordOpacity, wordY) = calculateWordState(clock.value, index, isReducedMotion)
                            alpha = wordOpacity
                            translationY = wordY.dp.toPx()
                            val p = if (isReducedMotion) 1f else launchLayerEase.transform(
                                ((clock.value - LAUNCH_WORD_SEQUENCE_START_SECONDS - index * LAUNCH_WORD_SLOT_SECONDS) / LAUNCH_WORD_FADE_SECONDS).coerceIn(0f, 1f))
                            scaleX = .94f + .06f * p; scaleY = scaleX
                            rotationX = if (isReducedMotion) 0f else 12f * (1f - p)
                            cameraDistance = 16f * density
                            translationX = (0.16f * 12.sp.value).dp.toPx()
                        },
                )
            }
        }
    }
}

@Composable
private fun PosterRevealLogo(clock: State<Float>, reducedMotion: Boolean, modifier: Modifier) {
    val resources = LocalContext.current.resources
    val geometry = remember(resources) { AliflixLogoGeometry.load(resources) }
    Canvas(modifier) {
        val unit = minOf(size.width, size.height)
        val left = (size.width - unit) / 2
        val top = (size.height - unit) / 2
        repeat(3) { panel ->
            val progress = if (reducedMotion) 1f else launchLayerEase.transform(
                ((clock.value - panel * .08f) / .66f).coerceIn(0f, 1f))
            val x = left + unit * panel / 3f
            clipRect(left = x, right = x + unit / 3f + .5f, top = top + unit * (1f - progress), bottom = top + unit) {
                withTransform({
                    translate(top = unit * .10f * (1f - progress))
                    scale(1.08f - .08f * progress, 1.08f - .08f * progress, pivot = center)
                }) { geometry.draw(this, unit, left, top) }
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
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val desiredPx = remember(measurer, density) { ALIFLIX_WORDMARK.sumOf { letter -> measurer.measure(letter.toString(), TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)).size.width } }
        val trackingPx = with(density) { (32.sp*.34f).toPx() }
        val fit = (with(density) { maxWidth.toPx() } / (desiredPx + trackingPx*6)).coerceAtMost(1f)
        val fontSize = 32.sp * fit
        val tracking = with(density) { (fontSize * .34f).toDp() }
        Row(Modifier.graphicsLayer { alpha = opacity; this.translationY = translationY.toPx() },
            horizontalArrangement = Arrangement.spacedBy(tracking), verticalAlignment = Alignment.Top) {
            ALIFLIX_WORDMARK.forEachIndexed { index, letter ->
                Text(letter.toString(), color = AliflixLaunchTheme.WhiteTitle, fontSize = fontSize,
                    fontWeight = FontWeight.ExtraBold, modifier = Modifier.graphicsLayer {
                        scaleY = ALIFLIX_WORDMARK_HEIGHT_SCALES[index]
                        transformOrigin = TransformOrigin(.5f,0f)
                    })
            }
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
    val finalStart = LAUNCH_WORD_SLOT_SECONDS * (LAUNCH_WORD_COUNT - 1)
    val activeIndex = if (wordTime >= finalStart) LAUNCH_WORD_COUNT - 1 else
        floor(wordTime / LAUNCH_WORD_SLOT_SECONDS).toInt()
    if (wordIndex != activeIndex) return 0f to 8f
    val localSeconds = wordTime - LAUNCH_WORD_SLOT_SECONDS * activeIndex
    return when {
        localSeconds < LAUNCH_WORD_FADE_SECONDS -> {
            val p = (localSeconds / LAUNCH_WORD_FADE_SECONDS).coerceIn(0f,1f)
            p to 8f * (1f-easeOutCubic(p))
        }
        activeIndex == LAUNCH_WORD_COUNT - 1 || localSeconds <= LAUNCH_WORD_SLOT_SECONDS - LAUNCH_WORD_FADE_SECONDS -> 1f to 0f
        else -> {
            val p = ((localSeconds - LAUNCH_WORD_SLOT_SECONDS + LAUNCH_WORD_FADE_SECONDS) / LAUNCH_WORD_FADE_SECONDS).coerceIn(0f,1f)
            (1f-p) to -8f * easeInOut(p)
        }
    }
}

@Composable
fun AnimatedAliflixHeatmapLogo(
    modifier: Modifier = Modifier,
) {
    val reducedMotion = remember { !ValueAnimator.areAnimatorsEnabled() }
    var startNanos by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (startNanos == 0L) {
                    startNanos = frameTimeNanos
                }
                elapsedSeconds = ((frameTimeNanos - startNanos) / 1_000_000_000.0).toFloat()
            }
        }
    }

    AliflixHeatmapLogo(
        timeSeconds = if (reducedMotion) 0f else elapsedSeconds,
        modifier = modifier,
    )
}

@Composable
fun AliflixHeatmapLogo(
    timeSeconds: Float,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val geometry = remember(resources) { AliflixLogoGeometry.load(resources) }

    Canvas(modifier = modifier) {
        val unit = minOf(size.width, size.height)
        val left = (size.width - unit) / 2f
        val top = (size.height - unit) / 2f
        val scaleFactor = unit / AliflixLogoGeometry.VIEWBOX_SIZE
        val center = Offset(left + 50f * scaleFactor, top + 50f * scaleFactor)

        val logoPath = geometry.createCombinedLogoPath(unit, left, top)
        // Show the canonical three-color vector at every animation frame, not only at t=0.
        // Moving light effects below are translucent overlays, not replacement fills.
        geometry.draw(this, unit, left, top)
        if (timeSeconds <= 0f) return@Canvas

        // Mathematical motion values from reference HTML, offset to the original
        // artwork's illuminated lilac phase without replacing its geometry.
        val t = (timeSeconds + 4.4f) * LAUNCH_LOGO_MOTION_RATE
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

        // --- LAYER 1: Subtle moving glow over the canonical three flat colors ---
        clipPath(logoPath) {
            withTransform({
                translate(left = (sweepX - 4f) * scaleFactor, top = 0f)
                rotate(degrees = -6f, pivot = center)
            }) {
                drawRect(
                    brush = heatBrush,
                    topLeft = Offset(left - 130f * scaleFactor, top - 25f * scaleFactor),
                    size = Size(280f * scaleFactor, 160f * scaleFactor),
                    alpha = 0.12f,
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
                alpha = 0.12f,
            )
        }

        // --- LAYER 2: Preserve the original rear/front/dot colors beneath moving light ---
        clipPath(logoPath) {
            // Main diagonal heat sweep
            withTransform({
                translate(left = sweepX * scaleFactor, top = 0f)
                rotate(degrees = -6f, pivot = center)
            }) {
                drawRect(
                    brush = heatBrush,
                    topLeft = Offset(left - 130f * scaleFactor, top - 25f * scaleFactor),
                    size = Size(280f * scaleFactor, 160f * scaleFactor),
                    alpha = 0.16f,
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
                alpha = 0.18f,
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
