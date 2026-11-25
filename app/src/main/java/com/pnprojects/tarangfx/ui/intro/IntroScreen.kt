package com.pnprojects.tarangfx.ui.intro

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.JakartaSans
import kotlin.math.PI
import kotlin.math.sin

/** Accessibility label of the intro; UI tests wait for it to disappear. */
const val INTRO_DESCRIPTION = "TarangFX. Made with love at PN-projects by TeamPrabodhNandini"

private const val TOTAL_MS = 2900
private val Violet = Color(0xFF7E57C2)
private val TITLE = listOf("T", "a", "r", "a", "n", "g", "F", "X")
private val CREDIT_WORDS = listOf("Made", "with", "♥", "at", "PN-projects")

/**
 * Animated launch intro. "Tarang" means *wave*: flowing layered waves, the wordmark building up
 * letter by letter, then the credit line word by word with a beating heart and a shimmer on the
 * team name. One [Animatable] timeline (0..1) drives every element, so all motion stays in sync.
 * Tap anywhere to skip; motion is replaced by a short static card when system animations are off.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val finish by rememberUpdatedState(onFinished)
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val timeline = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reducedMotion) kotlinx.coroutines.delay(1200)
        else timeline.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        finish()
    }
    val t = timeline.value
    val ms = t * TOTAL_MS

    val loop = rememberInfiniteTransition(label = "intro")
    val phase by loop.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "wavePhase",
    )
    // Heartbeat: two quick beats, then rest.
    val beat by loop.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0; 1.28f at 120; 1f at 260; 1.18f at 380; 1f at 560
            },
            RepeatMode.Restart,
        ),
        label = "heartbeat",
    )
    val shimmer by loop.animateFloat(-1f, 2f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "shimmer")

    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
            .semantics(mergeDescendants = true) { contentDescription = INTRO_DESCRIPTION }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Waves(amplitudeIn = window(ms, 0f, 900f), phase = if (reducedMotion) 0f else phase)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Wordmark: letters rise and fade in with a stagger; "FX" in the accent colour.
            Row {
                TITLE.forEachIndexed { i, ch ->
                    val p = FastOutSlowInEasing.transform(window(ms, 150f + i * 80f, 350f))
                    Text(
                        ch,
                        style = TextStyle(
                            fontFamily = JakartaSans, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp,
                            color = if (i >= 6) AppColors.Crimson else AppColors.TextPrimary, letterSpacing = 0.5.sp,
                        ),
                        modifier = Modifier.graphicsLayer {
                            alpha = p
                            translationY = (1f - p) * 28.dp.toPx()
                            val s = 0.85f + 0.15f * p
                            scaleX = s; scaleY = s
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "OFFLINE AUDIO STUDIO",
                style = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    letterSpacing = 4.sp, color = AppColors.TextSecondary),
                modifier = Modifier.graphicsLayer { alpha = window(ms, 900f, 500f) },
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CREDIT_WORDS.forEachIndexed { i, word ->
                    val p = FastOutSlowInEasing.transform(window(ms, 1000f + i * 120f, 380f))
                    val isHeart = word == "♥"
                    Text(
                        word,
                        style = TextStyle(
                            fontFamily = JakartaSans,
                            fontWeight = if (word == "PN-projects") FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (isHeart) 18.sp else 15.sp,
                            color = if (isHeart) AppColors.Crimson else AppColors.TextPrimary.copy(alpha = 0.9f),
                        ),
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .graphicsLayer {
                                alpha = p
                                translationY = (1f - p) * 14.dp.toPx()
                                val s = if (isHeart) p * beat else 1f
                                scaleX = s; scaleY = s
                            },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val teamIn = FastOutSlowInEasing.transform(window(ms, 1650f, 450f))
            val shimmerBrush = Brush.linearGradient(
                // Light stops only, so the name stays readable on the dark canvas at every frame.
                0f to Color(0xFFFF8FB1), 0.42f to Color(0xFFFF5C8F), 0.5f to Color.White, 0.58f to Color(0xFFFF5C8F), 1f to Color(0xFFC5B3F0),
                start = Offset(shimmer * 600f - 300f, 0f), end = Offset(shimmer * 600f + 300f, 0f),
            )
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AppColors.TextSecondary, fontWeight = FontWeight.Medium)) { append("by ") }
                    withStyle(SpanStyle(brush = shimmerBrush, fontWeight = FontWeight.ExtraBold)) { append("TeamPrabodhNandini") }
                },
                style = TextStyle(fontFamily = JakartaSans, fontSize = 17.sp, letterSpacing = 0.3.sp),
                modifier = Modifier.graphicsLayer {
                    alpha = teamIn
                    translationY = (1f - teamIn) * 18.dp.toPx()
                },
            )
        }
    }
}

/** 0..1 progress of an element that starts at [startMs] and lasts [durMs] on the intro timeline. */
private fun window(ms: Float, startMs: Float, durMs: Float): Float = ((ms - startMs) / durMs).coerceIn(0f, 1f)

/** Three layered sine waves with a crimson -> violet gradient over a soft radial glow. */
@Composable
private fun Waves(amplitudeIn: Float, phase: Float) {
    val density = LocalDensity.current
    val stroke = with(density) { 2.5.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val cy = size.height * 0.5f
        drawCircle(
            Brush.radialGradient(listOf(AppColors.Crimson.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width / 2f, cy), radius = size.minDimension * 0.75f),
            radius = size.minDimension * 0.75f, center = Offset(size.width / 2f, cy),
        )
        val gradient = Brush.horizontalGradient(listOf(AppColors.Crimson, Violet, AppColors.Crimson))
        val layers = listOf(
            Triple(70f, 1.6f, 0.55f),   // amplitude (dp), cycles across the width, alpha
            Triple(48f, 2.4f, 0.35f),
            Triple(30f, 3.3f, 0.22f),
        )
        val ease = FastOutSlowInEasing.transform(amplitudeIn)
        layers.forEachIndexed { k, (ampDp, cycles, alpha) ->
            val amp = with(density) { ampDp.dp.toPx() } * ease
            val path = Path()
            val step = 4f
            var x = 0f
            while (x <= size.width) {
                val u = x / size.width
                // Envelope tapers the wave at both edges so it "floats" in the middle.
                val env = sin(PI.toFloat() * u)
                val y = cy + amp * env * sin(2f * PI.toFloat() * cycles * u + phase * (1f + 0.35f * k) + k * 1.3f)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            drawPath(path, gradient, alpha = alpha * (0.3f + 0.7f * ease), style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}
