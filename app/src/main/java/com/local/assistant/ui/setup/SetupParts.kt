package com.local.assistant.ui.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.assistant.ui.theme.AppColors
import com.local.assistant.ui.theme.Glass
import com.local.assistant.ui.theme.GlassShapes
import com.local.assistant.ui.theme.glass
import kotlinx.coroutines.delay

/**
 * The pieces the first-run pages (permissions, setup) share: the main button, the way things rise
 * into place, and the small pictures of a chat they show.
 */

/** A beat of the setup pages' entrance: each piece rises this long after the one before. */
private const val BEAT_MS = 110L

internal val SceneShape = RoundedCornerShape(28.dp)
internal val TitleStyle = TextStyle(fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
internal val BodyStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val SceneText = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
private val SceneSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

/** The way on: a dark pill, the app's main button, that swells under the finger. */
@Composable
internal fun NextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.AutoMirrored.Outlined.ArrowForward,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press")
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(52.dp)
            .widthIn(min = 112.dp)
            .clip(GlassShapes.Pill)
            .background(Glass.Mine)
            .border(1.dp, Color.White.copy(alpha = 0.18f), GlassShapes.Pill)
            .clickable(interactionSource = press, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "next") { words ->
            Text(words, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
        if (icon != null) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/** Fades [content] in and lifts it into place [beat] beats after its page comes up. */
@Composable
internal fun Rise(playing: Boolean, beat: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing || progress.value > 0f) return@LaunchedEffect
        delay(beat * BEAT_MS)
        progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = (1f - progress.value) * 18.dp.toPx()
        },
        content = content,
    )
}

/** An icon on a tinted disc, the starter tiles' bead. */
@Composable
internal fun Bead(icon: ImageVector, accent: Color, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(size * 0.56f))
    }
}


/** The user's own words: the dark bubble. */
@Composable
internal fun Mine(text: String) {
    Text(
        text,
        style = SceneText,
        color = Color.White,
        modifier = Modifier
            .widthIn(max = 250.dp)
            .clip(GlassShapes.Mine)
            .background(Glass.Mine)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** What was done, as the chip under an answer shows it, with its one action. */
@Composable
internal fun Chip(text: String, action: String, icon: ImageVector, accent: Color) {
    Row(
        Modifier.glass(RoundedCornerShape(14.dp), Glass.Raised).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = SceneSmall, color = AppColors.TextPrimary)
        Spacer(Modifier.width(10.dp))
        Text(action, style = SceneSmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextPrimary)
    }
}
