package net.robog.clarity.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.robog.clarity.app.ui.theme.Space
import net.robog.clarity.app.ui.theme.clarityText

/*
 * The one layout of every screen. A screen says what it shows (a title, a body) and never how it is
 * laid out or how it moves: the header, the gutters, the gaps, the insets, the pull to refresh, the
 * pinned footer and the staggered arrival all live here. DesignSystemTest fails a screen that
 * scrolls, pads for the status bar or animates by hand.
 */

/** Where a screen sits: a tab root, or something opened from another screen. */
enum class ScreenLevel {
    /** A tab root (Today, Goods, Held, Settings home, Ask): the hero title. */
    Top,

    /** Opened from another screen: [ScreenTitle]. */
    Sub,
}

/** How a screen's content sits in the viewport. */
enum class ScreenArrangement {
    /** Packed under the header (most screens). */
    Top,

    /**
     * A full-screen moment (an incoming call, a welcome): the header text is centered and, when the
     * content is shorter than the viewport, the header and body are centered together vertically
     * between the top inset and the footer. Body children keep their own alignment. It still
     * scrolls when taller.
     */
    Centered,
}

/** A screen's title at its [level]'s size. */
@Composable
fun ScreenHeading(text: String, level: ScreenLevel, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    when (level) {
        ScreenLevel.Top -> Text(text, modifier.semantics { heading() }, textAlign = textAlign, style = MaterialTheme.clarityText.heroTitle)
        ScreenLevel.Sub -> ScreenTitle(text, modifier, textAlign)
    }
}

/** How tall the [ClarityScreen] `art` slot is. */
private val ArtHeight = 88.dp

/**
 * The header, in this order and only these pieces: step bar, back link, kicker, title. [art] is an
 * illustration at the end of the title row, centered on the title and decorative (no description).
 */
@Composable
private fun headerPieces(
    title: String?,
    level: ScreenLevel,
    kicker: String?,
    backLabel: String?,
    onBack: (() -> Unit)?,
    stepSegments: Int?,
    centered: Boolean = false,
    art: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
): List<@Composable () -> Unit> = buildList {
    val align = if (centered) TextAlign.Center else null
    val fill = if (centered) Modifier.fillMaxWidth() else Modifier
    if (stepSegments != null) add { StepBar(stepSegments) }
    if (backLabel != null && onBack != null) add { BackLink(backLabel, onBack) }
    if (kicker != null) add { Kicker(kicker, fill, textAlign = align) }
    if (!title.isNullOrBlank()) add {
        if (art == null) {
            ScreenHeading(title, level, fill, align)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.Medium), verticalAlignment = Alignment.CenterVertically) {
                ScreenHeading(title, level, Modifier.weight(1f))
                Box(Modifier.height(ArtHeight).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { art() }
            }
        }
    }
    if (subtitle != null) add { Hint(subtitle, fill, textAlign = align) }
}

/**
 * A scrolling screen. Everything in [body] arrives with the header in one staggered rise, so a
 * block added to a screen animates without being told to. [footer] is pinned under the scroll (the
 * primary button). [onRefresh] adds pull to refresh. [art] is the screen's illustration, at the end
 * of the title row. [arrangement] centers a full-screen moment, and [gutter] is the side margin.
 */
@Composable
fun ClarityScreen(
    title: String?,
    modifier: Modifier = Modifier,
    level: ScreenLevel = ScreenLevel.Sub,
    kicker: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    /** The onboarding step bar's filled segments. */
    stepSegments: Int? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    art: (@Composable () -> Unit)? = null,
    arrangement: ScreenArrangement = ScreenArrangement.Top,
    gutter: Dp = ScreenGutter,
    subtitle: String? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable () -> Unit,
) = ClarityScreen(
    title, modifier, level, kicker, backLabel, onBack, stepSegments, horizontalAlignment, art, arrangement, gutter, subtitle,
    phase = Unit, refreshing = refreshing, onRefresh = onRefresh, footer = footer,
) { body() }

/**
 * [ClarityScreen] for a body that shows one of several [phase]s (loading, ready, empty). When the
 * phase changes the body swaps with [PhaseSwap] and its stagger plays again. [body] gets the phase
 * it is drawing, so the old one fades out showing what it was.
 */
@Composable
fun <P> ClarityScreen(
    title: String?,
    modifier: Modifier = Modifier,
    level: ScreenLevel = ScreenLevel.Sub,
    kicker: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    stepSegments: Int? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    art: (@Composable () -> Unit)? = null,
    arrangement: ScreenArrangement = ScreenArrangement.Top,
    gutter: Dp = ScreenGutter,
    subtitle: String? = null,
    phase: P,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable (P) -> Unit,
) {
    val centered = arrangement == ScreenArrangement.Centered
    val align = horizontalAlignment
    val header = headerPieces(title, level, kicker, backLabel, onBack, stepSegments, centered, art, subtitle)
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        Refreshable(refreshing, onRefresh, Modifier.weight(1f)) {
            val content: @Composable (Dp) -> Unit = { viewport ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = Space.Large),
                ) {
                    if (centered) {
                        CenteredColumn(
                            Modifier.fillMaxWidth(),
                            minHeight = (viewport - Space.Large * 2).coerceAtLeast(0.dp),
                            header = { StaggeredColumn(Modifier.fillMaxWidth(), align) { header.forEach { it() } } },
                            body = {
                                PhaseSwap(phase, Modifier.fillMaxWidth(), label = "screen phase") { shown ->
                                    StaggeredColumn(Modifier.fillMaxWidth(), align, startIndex = header.size, replayKey = shown) { body(shown) }
                                }
                            },
                        )
                    } else {
                        StaggeredColumn(Modifier.fillMaxWidth(), align) {
                            header.forEach { it() }
                            PhaseSwap(phase, Modifier.fillMaxWidth().staggerExempt(), label = "screen phase") { shown ->
                                StaggeredColumn(Modifier.fillMaxWidth(), align, startIndex = header.size, replayKey = shown) { body(shown) }
                            }
                        }
                    }
                }
            }
            if (centered) BoxWithConstraints(Modifier.fillMaxSize()) { content(maxHeight) } else content(0.dp)
        }
        if (footer != null) {
            Column(
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = Space.Medium, bottom = Space.Medium),
                verticalArrangement = Arrangement.spacedBy(Space.Medium),
            ) { footer() }
        }
    }
}

/** The header and the body as one block, centered vertically; at least [minHeight] tall. */
@Composable
private fun CenteredColumn(modifier: Modifier, minHeight: Dp, header: @Composable () -> Unit, body: @Composable () -> Unit) {
    Layout({ header(); body() }, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val head = measurables[0].measure(loose)
        val main = measurables[1].measure(loose)
        val gap = Space.Medium.roundToPx()
        val used = head.height + gap + main.height
        val height = maxOf(minHeight.roundToPx(), used)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(head.width, main.width)
        layout(width, height) {
            val top = (height - used) / 2
            head.place((width - head.width) / 2, top)
            main.place((width - main.width) / 2, top + head.height + gap)
        }
    }
}

/**
 * A long list: the same header, gutters and insets as [ClarityScreen] on a LazyColumn. On the first
 * load the header and the first items rise in one stagger. Items that scroll in later do not animate.
 * Set [StaggeredListScope.gap] in [content] to change the space under the items that follow
 * (grouped rows sit [Space.Small] apart); it starts at [itemSpacing]. [overlay] draws over the list
 * (a floating button, a snackbar) and [bottomClearance] keeps the end of the list clear of it.
 */
@Composable
fun ClarityListScreen(
    title: String?,
    modifier: Modifier = Modifier,
    level: ScreenLevel = ScreenLevel.Sub,
    kicker: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    art: (@Composable () -> Unit)? = null,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    itemSpacing: Dp = Space.Medium,
    bottomClearance: Dp = 0.dp,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: StaggeredListScope.() -> Unit,
) {
    val header = headerPieces(title, level, kicker, backLabel, onBack, stepSegments = null, art = art)
    val clock = rememberStaggerClock(Unit)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Refreshable(refreshing, onRefresh, Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = Space.Large, bottom = Space.Large + bottomClearance),
                ) {
                    val scope = StaggeredListScope(this, clock)
                    scope.gap = Space.Medium
                    header.forEachIndexed { i, piece -> scope.item(key = "header-$i") { piece() } }
                    scope.gap = itemSpacing
                    scope.content()
                }
            }
            if (footer != null) {
                Column(
                    Modifier.fillMaxWidth().padding(start = ScreenGutter, end = ScreenGutter, top = Space.Medium, bottom = Space.Medium),
                    verticalArrangement = Arrangement.spacedBy(Space.Medium),
                ) { footer() }
            }
        }
        overlay()
    }
}

/** Pull to refresh around [content] when [onRefresh] is given; otherwise just a box. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Refreshable(refreshing: Boolean, onRefresh: (() -> Unit)?, modifier: Modifier, content: @Composable () -> Unit) {
    if (onRefresh == null) {
        Box(modifier) { content() }
        return
    }
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = pull,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter))
        },
    ) { content() }
}
