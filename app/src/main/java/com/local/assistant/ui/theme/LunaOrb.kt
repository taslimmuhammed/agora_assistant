package com.local.assistant.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What Luna is doing, as her orb shows it. */
enum class LunaOrbMode { IDLE, LISTENING, BUSY }

/**
 * Luna's orb, as the assistant overlay first had it: a dark glass bead that breathes while idle
 * and swells with the voice while listening, with the app's colours rippling out of it whenever
 * she is doing something, and a light circling it while she works or speaks. The overlay, the
 * home screen and the call screen all draw this one.
 *
 * [core]: the bead's size; [area]: how far the rings reach. Strokes grow with [core] so a big orb
 * looks like the overlay's, only larger. [ripples]: rings even while idle, as an invitation to tap.
 */
@Composable
fun LunaOrb(
    mode: LunaOrbMode,
    level: Float,
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    core: Dp = 64.dp,
    area: Dp = 104.dp,
    iconSize: Dp = 26.dp,
    ripples: Boolean = mode != LunaOrbMode.IDLE,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val ripple = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1800, easing = LinearEasing)),
        label = "ripple",
    )
    val breath = transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1300, easing = LinearEasing)),
        label = "spin",
    )
    val swell = animateFloatAsState(
        targetValue = if (mode == LunaOrbMode.LISTENING) 1f + 0.28f * level else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "swell",
    )
    // The overlay's sizes are the measure: 64 dp across, its strokes as they were there.
    val grow = (core / BASE_CORE).coerceAtLeast(1f)

    Box(modifier.size(area), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(area)) {
            val bead = core.toPx() / 2
            val reach = size.minDimension / 2
            if (ripples) {
                for (i in 0 until 2) {
                    val t = (ripple.value + i * 0.5f) % 1f
                    drawCircle(
                        brush = Brush.sweepGradient(Glass.Iris, center),
                        alpha = (1f - t) * 0.5f,
                        radius = bead + (reach - bead) * t,
                        style = Stroke(width = 1.5.dp.toPx() * minOf(grow, 2f)),
                    )
                }
            }
            if (mode == LunaOrbMode.BUSY) {
                rotate(spin.value) {
                    drawCircle(
                        brush = Brush.sweepGradient(listOf(Color.Transparent) + Glass.Iris.take(4), center),
                        radius = bead + 5.dp.toPx() * grow,
                        style = Stroke(width = 2.5.dp.toPx() * minOf(grow, 1.8f), cap = StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(core)
                .graphicsLayer {
                    val scale = if (mode == LunaOrbMode.IDLE) breath.value else swell.value
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF3F3F46), AppColors.Accent), radius = BEAD_LIGHT_PX * core.value / BASE_CORE.value))
                // Dark glass: lit from above, a bright rim.
                .liquidGlass(CircleShape, Color.Transparent, sheen = 0.3f)
                .semantics { contentDescription = label }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = AppColors.OnAccent, modifier = Modifier.size(iconSize)) }
        }
    }
}

private val BASE_CORE = 64.dp

/** The bead's inner light, as the overlay drew it at 64 dp. */
private const val BEAD_LIGHT_PX = 90f
