package net.robog.clarity.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource

/**
 * One of the hand-inked drawings (`R.drawable.drawing_*`), tinted to whatever the UI around it needs.
 * The drawings carry no colour of their own. They are decorative, so screen readers skip them.
 *
 * A section uses either drawings or Material icons, never both.
 */
@Composable
fun Drawing(
    @DrawableRes res: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Image(painterResource(res), contentDescription = null, modifier = modifier, colorFilter = ColorFilter.tint(tint))
}
