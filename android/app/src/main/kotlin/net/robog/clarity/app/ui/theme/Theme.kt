package net.robog.clarity.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/**
 * Shapes from the prototype: 24 dp pill buttons ([Shapes.extraLarge]), 16 dp cards ([Shapes.large]),
 * 12 dp context boxes ([Shapes.medium]), 8 dp chips and pills ([Shapes.small]).
 */
val ClarityShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** The speech bubble corners: 20 dp with a 4 dp tail at the bottom end (`.you`, `.live`). */
val BubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 4.dp, bottomStart = 20.dp)

/** Always the prototype palette; no dynamic colour, so the app looks the same on every phone. */
@Composable
fun ClarityTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val text = remember { clarityTextStyles(ClarityFonts.ui, ClarityFonts.reading) }
    val typography = remember { clarityTypography(ClarityFonts.ui, ClarityFonts.reading) }
    CompositionLocalProvider(LocalClarityText provides text) {
        MaterialTheme(
            colorScheme = if (darkTheme) ClarityPalette.dark else ClarityPalette.light,
            typography = typography,
            shapes = ClarityShapes,
            content = content,
        )
    }
}

/** Prototype text roles for the current theme. */
val MaterialTheme.clarityText: ClarityTextStyles
    @Composable get() = LocalClarityText.current
