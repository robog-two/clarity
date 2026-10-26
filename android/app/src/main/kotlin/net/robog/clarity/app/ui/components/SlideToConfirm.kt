package net.robog.clarity.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Pure: whether a slide that ended at [offset] of [max] confirms (the last 15% of the track). */
fun slideConfirms(offset: Float, max: Float): Boolean = max > 0f && offset >= max * 0.85f

/**
 * Slide the knob from left to right to confirm. Let go early and it springs back. Screen readers
 * get it as a plain button. [resetKey] puts the knob back at the start (for example after the
 * request failed).
 */
@Composable
fun SlideToConfirm(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetKey: Any? = null,
) {
    val knob = 48.dp
    val pad = 4.dp
    val scope = rememberCoroutineScope()
    val offset = remember(resetKey) { Animatable(0f) }
    var done by remember(resetKey) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(knob + pad * 2)
            .background(if (enabled) colors.primaryContainer else colors.surfaceContainerHighest, CircleShape)
            .semantics {
                role = Role.Button
                if (enabled && !done) onClick(label = text) { done = true; onConfirm(); true }
            },
    ) {
        val max = with(LocalDensity.current) { (maxWidth - knob - pad * 2).toPx() }.coerceAtLeast(0f)
        Text(
            text,
            Modifier.align(Alignment.Center).padding(start = knob),
            color = if (enabled) colors.onPrimaryContainer else colors.onSurface.copy(alpha = .38f),
            style = MaterialTheme.typography.labelLarge,
        )
        Box(
            Modifier
                .padding(pad)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(knob)
                .background(if (enabled) colors.primary else colors.onSurface.copy(alpha = .12f), CircleShape)
                .pointerInput(enabled, done, max) {
                    if (!enabled || done) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (slideConfirms(offset.value, max)) {
                                    done = true
                                    offset.animateTo(max)
                                    onConfirm()
                                } else {
                                    offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, dx ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + dx).coerceIn(0f, max)) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = .38f),
            )
        }
    }
}
