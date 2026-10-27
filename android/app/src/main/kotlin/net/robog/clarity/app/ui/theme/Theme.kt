package net.robog.clarity.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
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

/** Always the brand palette ([ClarityPalette]); no dynamic colour, so the app looks the same on every phone. */
/** Motion uses Material 3 Expressive springs, so components and screens move with a little bounce. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClarityTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // One family everywhere: Material components take body text from the theme, and a serif body
    // mixed with sans UI inside the same field, dialog or row. Pass ClarityFonts.reading to bring it back.
    val text = remember { clarityTextStyles(ClarityFonts.ui, ClarityFonts.ui) }
    val typography = remember { clarityTypography(ClarityFonts.ui, ClarityFonts.ui) }
    CompositionLocalProvider(LocalClarityText provides text) {
        MaterialTheme(
            colorScheme = if (darkTheme) ClarityPalette.dark else ClarityPalette.light,
            motionScheme = MotionScheme.expressive(),
            typography = typography,
            shapes = ClarityShapes,
            content = content,
        )
    }
}

/** Prototype text roles for the current theme. */
val MaterialTheme.clarityText: ClarityTextStyles
    @Composable get() = LocalClarityText.current
