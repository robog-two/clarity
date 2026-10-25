package net.robog.clarity.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Material 3 colour roles taken from the prototype's `.app` CSS variables
 * (`--primary`, `--pc`, `--surface`, ... in the prototype .dc.html files).
 * The dark scheme keeps the same hues (warm neutral, teal primary, peach tertiary).
 */
object ClarityPalette {
    // Light, one entry per prototype variable.
    val surface = Color(0xFFFBF8F4)          // --surface
    val surfaceContainerLow = Color(0xFFF5F1EB) // --scl
    val surfaceContainer = Color(0xFFEFEAE2) // --sc
    val surfaceContainerHigh = Color(0xFFE8E2D9) // --sch
    val onSurface = Color(0xFF1F1D1A)        // --on
    val onSurfaceVariant = Color(0xFF54514B) // --osv
    val outlineVariant = Color(0xFFD6D0C6)   // --ov
    val outline = Color(0xFF7D7972)          // --outline
    val primary = Color(0xFF466660)          // --primary
    val onPrimary = Color(0xFFFFFFFF)        // --onp
    val primaryContainer = Color(0xFFCDE7DF) // --pc
    val onPrimaryContainer = Color(0xFF0E2B26) // --opc
    val secondaryContainer = Color(0xFFDDE6E2) // --secc
    val tertiaryContainer = Color(0xFFFFDDC4) // --tc
    val onTertiaryContainer = Color(0xFF3B1F08) // --otc
    val error = Color(0xFFB3261E)            // --err
    val errorContainer = Color(0xFFF9DEDC)   // --errc
    val onErrorContainer = Color(0xFF410E0B) // --onerrc
    val inverseSurface = Color(0xFF33302C)   // --inv
    val inverseOnSurface = Color(0xFFF5F0E9) // --oninv

    val light: ColorScheme = lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = Color(0xFF4A635D),
        onSecondary = Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = Color(0xFF05201B),
        tertiary = Color(0xFF7A4B26),
        onTertiary = Color.White,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        error = error,
        onError = Color.White,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceContainerHigh,
        onSurfaceVariant = onSurfaceVariant,
        surfaceDim = Color(0xFFDDD8CF),
        surfaceBright = surface,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = Color(0xFFE2DCD2),
        outline = outline,
        outlineVariant = outlineVariant,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        inversePrimary = Color(0xFFAFCBC3),
        scrim = Color.Black,
    )

    val dark: ColorScheme = darkColorScheme(
        primary = Color(0xFFAFCBC3),
        onPrimary = Color(0xFF14332D),
        primaryContainer = Color(0xFF2E4D47),
        onPrimaryContainer = primaryContainer,
        secondary = Color(0xFFB1CCC4),
        onSecondary = Color(0xFF1C352F),
        secondaryContainer = Color(0xFF334A44),
        onSecondaryContainer = secondaryContainer,
        tertiary = Color(0xFFF1BC94),
        onTertiary = Color(0xFF462A10),
        tertiaryContainer = Color(0xFF5E3C1F),
        onTertiaryContainer = tertiaryContainer,
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = errorContainer,
        background = Color(0xFF141311),
        onBackground = Color(0xFFE8E2D9),
        surface = Color(0xFF141311),
        onSurface = Color(0xFFE8E2D9),
        surfaceVariant = Color(0xFF3A3731),
        onSurfaceVariant = Color(0xFFCDC6BC),
        surfaceDim = Color(0xFF141311),
        surfaceBright = Color(0xFF3B3835),
        surfaceContainerLowest = Color(0xFF0F0E0C),
        surfaceContainerLow = Color(0xFF1C1B18),
        surfaceContainer = Color(0xFF21201C),
        surfaceContainerHigh = Color(0xFF2B2926),
        surfaceContainerHighest = Color(0xFF363330),
        outline = Color(0xFF979089),
        outlineVariant = Color(0xFF4A4741),
        inverseSurface = Color(0xFFE8E2D9),
        inverseOnSurface = Color(0xFF33302C),
        inversePrimary = primary,
        scrim = Color.Black,
    )
}
