package net.robog.clarity.app.ui.components

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/*
 * One motion vocabulary, all on the expressive motion scheme's springs (never a default tween):
 * - content loading into a screen: springIn (TaskRows.kt), a staggered rise;
 * - one page replacing another: springSwap, the same rise with a fade;
 * - something opening or closing in place: springEnter and springExit;
 * - a lazy list item arriving: arrivingItem().
 */

/** The page-to-page transition: the new page rises on a spring while it fades in, the old one fades out. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <S> springSwap(rise: Dp = 24.dp): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val fast = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val risePx = with(LocalDensity.current) { rise.roundToPx() }
    return { (fadeIn(effects) + slideInVertically(spatial) { risePx }) togetherWith fadeOut(fast) }
}

/** [springSwap] for the predictive back gesture, which hands over the swipe edge. */
@Composable
fun <S> springSwapPredictive(rise: Dp = 24.dp): AnimatedContentTransitionScope<S>.(Int) -> ContentTransform {
    val swap = springSwap<S>(rise)
    return { swap() }
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
