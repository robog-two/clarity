package net.robog.clarity.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The app's whole spacing budget: three values. Every padding, gap and spacer uses one of them
 * (SpacingBudgetTest fails on a raw dp value). Sizes, touch targets, corners and strokes are not spacing.
 */
object Space {
    /** Inside one thing: title to subtitle, between the segments of a group, chip to chip. */
    val Small = 4.dp

    /** Screen gutter, padding inside cards and rows, between stacked items, icon to text. */
    val Medium = 16.dp

    /** Between groups and sections, above a section title, below the app bar. */
    val Large = 32.dp
}
