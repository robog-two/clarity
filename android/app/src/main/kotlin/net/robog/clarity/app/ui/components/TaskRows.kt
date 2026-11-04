package net.robog.clarity.app.ui.components

import androidx.compose.ui.semantics.clearAndSetSemantics
import net.robog.clarity.app.ui.theme.Space
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import net.robog.clarity.app.ui.theme.ClarityPalette
import net.robog.clarity.app.ui.theme.clarityText

/*
 * The task rows first made for Today's "I'm doing": a contained group of rows with segmented corners,
 * a shape on the left that says the state, a title and detail, and either a status word or the
 * Peach "Review" button on the right. Today, the call's "Your Morning" and Held all use them.
 */

/** Segmented-list corners: round outer ends, tight inner joins. */
fun groupShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    return RoundedCornerShape(
        topStart = if (index == 0) outer else inner,
        topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner,
        bottomEnd = if (index == count - 1) outer else inner,
    )
}

/** One row of a task group. [onClick] makes the whole row tappable. */
@Composable
fun TaskRow(
    title: String,
    detail: String,
    shape: Shape,
    glyph: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = shape) {
        Row(
            (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 72.dp)
                .padding(start = Space.Medium, end = Space.Medium, top = Space.Medium, bottom = Space.Medium),
            horizontalArrangement = Arrangement.spacedBy(Space.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            glyph()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
                Text(title, style = MaterialTheme.clarityText.chat)
                Text(sentenceCase(detail), style = MaterialTheme.clarityText.chatSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

/**
 * A compact row for Today's Inactive fold: a small still mark (one shape per state), the title and
 * only a relative time. The state is spoken, not written. Tapping opens the task's Agent's Path.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompactTaskRow(
    title: String,
    /** A relative time ("3 days ago"), or empty when the row has none. */
    time: String,
    /** What the row's mark means, spoken with the time ("Failed, 3 days ago"). */
    state: String,
    shape: Shape,
    mark: RoundedPolygon,
    markFill: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spoken = listOf(title, state, time).filter { it.isNotBlank() }.joinToString(", ")
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainerLow, contentColor = colors.onSurfaceVariant, shape = shape) {
        Row(
            (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .clearAndSetSemantics { contentDescription = spoken }
                .heightIn(min = 48.dp)
                .padding(start = Space.Medium, end = Space.Medium, top = Space.Small, bottom = Space.Small),
            horizontalArrangement = Arrangement.spacedBy(Space.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(14.dp).background(markFill, mark.toShape()))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (time.isNotBlank()) Text(time, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** A still state shape in a task row's 44dp glyph slot. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TaskShape(shape: RoundedPolygon, fill: Color) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(28.dp).background(fill, shape.toShape())) }
}

/** The quiet status word on the right of a settled row. */
@Composable
fun TaskStatus(text: String) {
    Text(sentenceCase(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The Peach action on a row that needs the person. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
/** [about] names the item (its title) so a screen reader tells the "Review" buttons apart. */
fun ReviewButton(onClick: () -> Unit, about: String? = null) {
    Button(
        onClick = onClick,
        modifier = if (about == null) Modifier else Modifier.semantics { contentDescription = "Review $about" },
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = ClarityPalette.Peach, contentColor = ClarityPalette.Charcoal),
    ) { Text("Review") }
}

/**
 * Staggered arrival: each block rises [rise] and fades in on an expressive spring, [index] steps
 * after the first. Previews and screenshots show the settled layout.
 */
@Composable
fun Modifier.springIn(index: Int, rise: Dp = 24.dp, stagger: Long = 45L): Modifier {
    if (LocalInspectionMode.current) return this
    val progress = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(stagger * index)
        progress.animateTo(1f, spatial)
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise.toPx()
    }
}
