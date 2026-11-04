package net.robog.clarity.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import net.robog.clarity.app.ui.theme.Space

/*
 * The grouped-card style from Today's "Doing" list, used for every list in the app: each row is its
 * own segment on surfaceContainer, the first and last with big outer corners, the ones between with
 * small corners, a Small gap between segments, no shadow and no divider.
 */

/** Collects the segments of a [CardGroup]. */
class CardGroupScope {
    internal val segments = mutableListOf<@Composable (Shape) -> Unit>()

    /** One segment. [content] gets the shape for its place in the group; hand it to [SegmentCard]. */
    fun segment(content: @Composable (Shape) -> Unit) {
        segments += content
    }
}

/** A group of segments. Segments sit [Space.Small] apart; groups are [Space.Large] apart. */
@Composable
fun CardGroup(modifier: Modifier = Modifier, build: CardGroupScope.() -> Unit) {
    val scope = CardGroupScope().apply(build)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        scope.segments.forEachIndexed { i, segment -> segment(groupShape(i, scope.segments.size)) }
    }
}

/** A group over a list: [row] draws item `i` in the shape for its place. */
@Composable
fun <T> CardGroup(items: List<T>, modifier: Modifier = Modifier, row: @Composable (item: T, shape: Shape, index: Int) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.Small)) {
        items.forEachIndexed { i, item -> row(item, groupShape(i, items.size), i) }
    }
}

/**
 * One segment: a surfaceContainer card with [shape]. The ripple and touch area follow the shape.
 * Content is padded [Space.Medium] on all sides.
 */
@Composable
fun SegmentCard(
    shape: Shape,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    minHeight: Dp = Dp.Unspecified,
    /** Other input (a toggle), applied inside the shape so its ripple is clipped to it. */
    interaction: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = shape) {
        Box(
            Modifier
                .clip(shape)
                .let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
                .then(interaction)
                .heightIn(min = minHeight)
                .padding(Space.Medium),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}
