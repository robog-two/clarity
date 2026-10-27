package net.robog.clarity.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The brand palette mapped onto Material 3 colour roles.
 *
 * Four brand colours:
 * - [Charcoal] is the page in dark mode and the text in light mode.
 * - [Bone] is the text in dark mode; a lighter tint of it is the page in light mode.
 * - [Sage] is the secondary surface ([ColorScheme.secondaryContainer]): single cells, the selected tab, whole views.
 * - [Peach] is the accent: primary action, focus and selection.
 *
 * Peach is too light to read as text on a light page, so in light mode it is [ColorScheme.primaryContainer]
 * (filled buttons and highlights) and a deep burnt peach is [ColorScheme.primary] for text and icons.
 * In dark mode Peach is [ColorScheme.primary] itself.
 */
object ClarityPalette {
    val Charcoal = Color(0xFF343434)
    val Bone = Color(0xFFECE7DF)
    val Sage = Color(0xFF7F9C96)
    val Peach = Color(0xFFFFBE85)

    /** Near-black with a hint of sage, for text on [Sage]. */
    private val OnSage = Color(0xFF121716)

    val light: ColorScheme = lightColorScheme(
        // Peach: deep tone for text and icons, the brand peach as the filled container.
        primary = Color(0xFF8A4C14),
        onPrimary = Color.White,
        primaryContainer = Peach,
        onPrimaryContainer = Color(0xFF2B1604),
        inversePrimary = Peach,
        // Sage: deep tone for text, the brand sage as the surface.
        secondary = Color(0xFF4B6661),
        onSecondary = Color.White,
        secondaryContainer = Sage,
        onSecondaryContainer = OnSage,
        // A pale sage for quieter highlights (info boxes, chips).
        tertiary = Color(0xFF4B6661),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFC8DDD8),
        onTertiaryContainer = Color(0xFF14231F),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        // Bone neutrals, from lightest to darkest.
        background = Color(0xFFF6F2EC),
        onBackground = Charcoal,
        surface = Color(0xFFF6F2EC),
        onSurface = Charcoal,
        surfaceVariant = Color(0xFFE3DDD3),
        onSurfaceVariant = Color(0xFF5E5B56),
        surfaceBright = Color(0xFFF6F2EC),
        surfaceDim = Color(0xFFDAD4CA),
        surfaceContainerLowest = Color(0xFFFCFAF7),
        surfaceContainerLow = Color(0xFFF1EDE6),
        surfaceContainer = Bone,
        surfaceContainerHigh = Color(0xFFE6E0D7),
        surfaceContainerHighest = Color(0xFFE0D9CF),
        outline = Color(0xFF85817A),
        outlineVariant = Color(0xFFD3CCC1),
        inverseSurface = Charcoal,
        inverseOnSurface = Bone,
        scrim = Color.Black,
    )

    val dark: ColorScheme = darkColorScheme(
        // Peach is the accent as-is; Charcoal reads on it at 7.7:1.
        primary = Peach,
        onPrimary = Charcoal,
        primaryContainer = Color(0xFF5A3A1F),
        onPrimaryContainer = Color(0xFFFFDCC2),
        inversePrimary = Color(0xFF8A4C14),
        // Sage as the surface; a lighter sage for sage-coloured text on Charcoal.
        secondary = Color(0xFFA3BDB7),
        onSecondary = Charcoal,
        secondaryContainer = Sage,
        onSecondaryContainer = OnSage,
        tertiary = Color(0xFFA3BDB7),
        onTertiary = Charcoal,
        tertiaryContainer = Color(0xFF34443F),
        onTertiaryContainer = Color(0xFFD3E4E0),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        // Charcoal neutrals: containers step lighter, as Material 3 dark schemes do.
        background = Charcoal,
        onBackground = Bone,
        surface = Charcoal,
        onSurface = Bone,
        surfaceVariant = Color(0xFF4A4A4A),
        onSurfaceVariant = Color(0xFFC4BFB7),
        surfaceBright = Color(0xFF555555),
        surfaceDim = Color(0xFF2A2A2A),
        surfaceContainerLowest = Color(0xFF2A2A2A),
        surfaceContainerLow = Color(0xFF3A3A3A),
        surfaceContainer = Color(0xFF3F3F3F),
        surfaceContainerHigh = Color(0xFF474747),
        surfaceContainerHighest = Color(0xFF505050),
        outline = Color(0xFF9A968F),
        outlineVariant = Color(0xFF545250),
        inverseSurface = Bone,
        inverseOnSurface = Charcoal,
        scrim = Color.Black,
    )
}
