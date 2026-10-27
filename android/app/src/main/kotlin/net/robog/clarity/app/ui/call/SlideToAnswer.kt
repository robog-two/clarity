package net.robog.clarity.app.ui.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What letting go of the knob does. */
enum class SlideOutcome { Answer, Decline, None }

/** Pure: the outcome of a slide that ended at [offset] from the centre, out of [max] either way (the last 20% of each side acts). */
fun slideOutcome(offset: Float, max: Float): SlideOutcome = when {
    max <= 0f -> SlideOutcome.None
    offset >= max * 0.8f -> SlideOutcome.Answer
    offset <= -max * 0.8f -> SlideOutcome.Decline
    else -> SlideOutcome.None
}

/**
 * The incoming-call control: a knob in the middle of a pill track. Slide it right to answer or left
 * to decline; let go early and it springs back. The knob is an Expressive "cookie" shape that turns
 * slowly while it waits, and the track and knob take on the answer or decline colour as it moves.
 * Screen readers get Answer and Decline as actions.
 *
 * [initialProgress] (-1 to 1) places the knob for previews.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SlideToAnswer(
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initialProgress: Float = 0f,
) {
    val knob = 72.dp
    val pad = 8.dp
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val spring = remember { MotionScheme.expressive().defaultSpatialSpec<Float>() }
    val colors = MaterialTheme.colorScheme
    var done by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(SlideOutcome.None) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(knob + pad * 2)
            .semantics {
                contentDescription = "Incoming call. Slide right to answer, left to decline."
                if (enabled && !done) {
                    customActions = listOf(
                        CustomAccessibilityAction("Answer") { done = true; onAnswer(); true },
                        CustomAccessibilityAction("Decline") { done = true; onDecline(); true },
                    )
                }
            },
    ) {
        val max = with(LocalDensity.current) { ((maxWidth - knob) / 2 - pad).toPx() }.coerceAtLeast(0f)
        val offset = remember(max) { Animatable(initialProgress * max) }
        val progress = if (max > 0f) (offset.value / max).coerceIn(-1f, 1f) else 0f
        val toward = if (progress >= 0f) colors.primary else colors.error
        val towardContainer = if (progress >= 0f) colors.primaryContainer else colors.errorContainer

        // The track: neutral at rest, tinting toward the side the knob is heading.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (enabled) lerp(colors.surfaceContainerHigh, towardContainer, abs(progress)) else colors.surfaceContainerHighest,
                    CircleShape,
                ),
        )
        Row(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both labels fade as the knob moves, so it never runs into one; the track colour shows the way.
            val labels = 1f - abs(progress) * 2f
            EndLabel(Icons.Filled.CallEnd, "Decline", visible = labels, enabled = enabled)
            EndLabel(Icons.Filled.Call, "Answer", visible = labels, enabled = enabled)
        }

        // The knob: a turning cookie that swells while held.
        val turn by rememberInfiniteTransition(label = "knob turn").animateFloat(
            0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
        )
        val scale by animateFloatAsState(if (dragging) 1.12f else 1f, MotionScheme.expressive().fastSpatialSpec(), label = "knob scale")
        val knobColor by animateColorAsState(
            when {
                !enabled -> colors.onSurface.copy(alpha = .12f)
                abs(progress) < 0.05f -> colors.primary
                else -> lerp(colors.primary, toward, abs(progress))
            },
            label = "knob colour",
        )
        val shape = MaterialShapes.Cookie9Sided.toShape()
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(knob)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .pointerInput(enabled, done, max) {
                    if (!enabled || done) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            scope.launch {
                                when (slideOutcome(offset.value, max)) {
                                    SlideOutcome.Answer -> { done = true; haptics.performHapticFeedback(HapticFeedbackType.Confirm); offset.animateTo(max, spring); onAnswer() }
                                    SlideOutcome.Decline -> { done = true; haptics.performHapticFeedback(HapticFeedbackType.Confirm); offset.animateTo(-max, spring); onDecline() }
                                    SlideOutcome.None -> { armed = SlideOutcome.None; offset.animateTo(0f, spring) }
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            armed = SlideOutcome.None
                            scope.launch { offset.animateTo(0f, spring) }
                        },
                    ) { change, dx ->
                        change.consume()
                        scope.launch {
                            offset.snapTo((offset.value + dx).coerceIn(-max, max))
                            // A tick when letting go would now act.
                            val now = slideOutcome(offset.value, max)
                            if (now != armed) {
                                if (now != SlideOutcome.None) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                armed = now
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = if (enabled && !dragging) turn else 0f }.background(knobColor, shape))
            Icon(
                if (progress < -0.3f) Icons.Filled.CallEnd else Icons.Filled.Call,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = when {
                    !enabled -> colors.onSurface.copy(alpha = .38f)
                    progress < -0.3f -> colors.onError
                    else -> colors.onPrimary
                },
            )
        }
    }
}

@Composable
private fun EndLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, visible: Float, enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Column(
        Modifier.graphicsLayer { alpha = visible.coerceIn(0f, 1f) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
