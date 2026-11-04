package net.robog.clarity.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.theme.Space

/*
 * One motion vocabulary, all on the expressive motion scheme's springs. This is the only file that
 * may use Compose's enter and exit APIs (DesignSystemTest fails on any other file that does):
 * - content arriving on a screen: StaggeredColumn, which ClarityScreen builds in, so no screen opts in;
 * - one page replacing another: springSwap, a fade (the content's own stagger supplies the rise);
 * - a swap inside a screen (loading to ready, a knob, a badge): PhaseSwap;
 * - something opening or closing in place: springEnter and springExit;
 * - a lazy list item arriving: arrivingItem().
 */

/** The page-to-page transition: the new page fades in on the effects spring and the old one fades out. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <S> springSwap(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fast = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val size = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    return { (fadeIn(effects) togetherWith fadeOut(fast)).using(SizeTransform(clip = false) { _, _ -> size }) }
}

/** [springSwap] for the predictive back gesture, which hands over the swipe edge. */
@Composable
fun <S> springSwapPredictive(): AnimatedContentTransitionScope<S>.(Int) -> ContentTransform {
    val swap = springSwap<S>()
    return { swap() }
}

/**
 * A swap inside a screen: [content] for the current [target] fades in as the old one fades out, and
 * the stagger of whatever it holds plays again. Use it for loading to ready, a knob changing state,
 * a badge changing its value, tab to tab. Never an AnimatedContent or Crossfade.
 */
@Composable
fun <T> PhaseSwap(target: T, modifier: Modifier = Modifier, label: String = "phase", content: @Composable (T) -> Unit) {
    AnimatedContent(target, modifier, transitionSpec = springSwap<T>(), label = label) { content(it) }
}

/** Something opening in place (a fold, a card): grows on a spring and fades in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun springEnter(): EnterTransition {
    val size = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    return fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandVertically(size)
}

/** Something closing in place. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun springExit(): ExitTransition {
    val size = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    return fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + shrinkVertically(size)
}

/** Something opening or closing in place: [content] grows and fades in while [visible], and folds away after. */
@Composable
fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = springEnter(), exit = springExit()) { content() }
}

/** [content] fades in and out in place while [visible], taking no space of its own (a scrim). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Fade(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) { content() }
}

/**
 * For a lazy list item: fades in as it arrives and glides when the items around it move, on the
 * expressive motion scheme's springs, so new messages settle in instead of popping.
 */
@Composable
fun LazyItemScope.arrivingItem(): Modifier = with(MaterialTheme.motionScheme) {
    Modifier.animateItem(fadeInSpec = defaultEffectsSpec(), placementSpec = defaultSpatialSpec(), fadeOutSpec = fastEffectsSpec())
}

// ---------- The stagger ----------

private const val StepNanos = 45_000_000L
private const val MaxStaggered = 8
private const val Settled = Long.MAX_VALUE / 4

/**
 * One clock for a whole screen's arrival. It only ticks while the last child is still settling and
 * is read inside graphics layers, so frames redraw layers and never recompose anything.
 */
@Stable
class StaggerClock internal constructor(
    private val spec: VectorizedFiniteAnimationSpec<AnimationVector1D>,
    private val settleNanos: Long,
    running: Boolean,
) {
    internal var elapsed by mutableLongStateOf(if (running) 0L else Settled)

    internal val endNanos get() = MaxStaggered * StepNanos + settleNanos

    /** 0 before child [index] starts, 1 once it has settled; a little over 1 on the spring's overshoot. */
    fun progress(index: Int): Float {
        val t = elapsed - minOf(index, MaxStaggered) * StepNanos
        if (t <= 0L) return 0f
        if (t >= settleNanos) return 1f
        return spec.getValueFromNanos(t, Zero, One, Zero).value
    }

    private companion object {
        val Zero = AnimationVector1D(0f)
        val One = AnimationVector1D(1f)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberStaggerClock(key: Any?): StaggerClock {
    val inspection = LocalInspectionMode.current
    // Keyed on the spec itself (a value that equals the last one), so a recomposition never restarts the clock.
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val clock = remember(key, spec, inspection) {
        val vectorized = spec.vectorize(Float.VectorConverter)
        val settle = vectorized.getDurationNanos(AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f))
        StaggerClock(vectorized, settle, running = !inspection)
    }
    // Previews and screenshots show the settled layout and run no clock.
    if (!inspection) {
        LaunchedEffect(clock) {
            val start = withFrameNanos { it }
            while (true) {
                val t = withFrameNanos { it } - start
                if (t >= clock.endNanos) break
                clock.elapsed = t
            }
            clock.elapsed = Settled
        }
    }
    return clock
}

/** Marks a child of a [StaggeredColumn] that is not staggered itself (it holds a stagger of its own). */
fun Modifier.staggerExempt(): Modifier = this.then(StaggerExempt)

private object StaggerExempt : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = StaggerExempt
}

/**
 * A column whose direct children rise 24dp and fade in one after another on the motion scheme's
 * spatial spring, 45ms apart. The children are spaced [Space.Medium]. Nothing is passed per child,
 * so a new block on a screen animates by being there. Changing [replayKey] plays it again.
 * [startIndex] is where in the order the first child sits, for a body that follows a header.
 */
@Composable
fun StaggeredColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    startIndex: Int = 0,
    replayKey: Any? = Unit,
    content: @Composable () -> Unit,
) {
    val clock = rememberStaggerClock(replayKey)
    Layout(content, modifier) { measurables, constraints ->
        val gap = Space.Medium.roundToPx()
        val rise = 24.dp.toPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0)
        var order = startIndex
        val orders = measurables.map { if (it.parentData === StaggerExempt) -1 else order++ }
        var total = 0
        placeables.forEach { p -> if (p.height > 0) total += (if (total > 0) gap else 0) + p.height }
        layout(width, constraints.constrainHeight(total)) {
            var y = 0
            placeables.forEachIndexed { i, p ->
                if (p.height == 0) {
                    p.place(0, y)
                    return@forEachIndexed
                }
                if (y > 0) y += gap
                val x = horizontalAlignment.align(p.width, width, layoutDirection)
                val index = orders[i]
                if (index < 0) p.place(x, y) else p.placeWithLayer(x, y) {
                    val progress = clock.progress(index)
                    alpha = progress.coerceIn(0f, 1f)
                    translationY = (1f - progress) * rise
                }
                y += p.height
            }
        }
    }
}

/** For a lazy item: the same rise and fade as [StaggeredColumn], by its place in the list. */
internal fun Modifier.staggered(clock: StaggerClock, index: Int): Modifier = graphicsLayer {
    val progress = clock.progress(index)
    alpha = progress.coerceIn(0f, 1f)
    translationY = (1f - progress) * 24.dp.toPx()
}

/**
 * A lazy list scope whose first items arrive staggered. Items that scroll in later do not animate,
 * since the clock has settled by then. Every item also gets [arrivingItem], so rows added or moved
 * after the first load fade in and glide.
 */
class StaggeredListScope internal constructor(
    private val delegate: LazyListScope,
    private val staggerClock: StaggerClock,
) : LazyListScope by delegate {
    private var next = 0

    /** The space under the items added from now on. */
    var gap: Dp = Space.Medium

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val index = next++
        val under = gap
        val arrival = staggerClock
        delegate.item(key, contentType) { Box(arrivingItem().staggered(arrival, index).padding(bottom = under)) { content() } }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val base = next
        next += count
        val under = gap
        val arrival = staggerClock
        delegate.items(count, key, contentType) { i -> Box(arrivingItem().staggered(arrival, base + i).padding(bottom = under)) { itemContent(i) } }
    }
}
