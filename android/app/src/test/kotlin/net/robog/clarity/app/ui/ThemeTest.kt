package net.robog.clarity.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import net.robog.clarity.app.ui.components.maskKey
import net.robog.clarity.app.ui.theme.ClarityPalette
import net.robog.clarity.app.ui.theme.ClarityShapes
import net.robog.clarity.app.ui.theme.clarityTextStyles
import net.robog.clarity.app.ui.theme.clarityTypography

private fun contrast(a: Color, b: Color): Double {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}

class ThemeTest {
    @Test fun lightSchemeIsThePrototypePalette() {
        val c = ClarityPalette.light
        assertEquals(Color(0xFF466660), c.primary)              // --primary
        assertEquals(Color(0xFFFFFFFF), c.onPrimary)            // --onp
        assertEquals(Color(0xFFCDE7DF), c.primaryContainer)     // --pc
        assertEquals(Color(0xFF0E2B26), c.onPrimaryContainer)   // --opc
        assertEquals(Color(0xFFFBF8F4), c.surface)              // --surface
        assertEquals(Color(0xFFFBF8F4), c.background)
        assertEquals(Color(0xFFF5F1EB), c.surfaceContainerLow)  // --scl
        assertEquals(Color(0xFFEFEAE2), c.surfaceContainer)     // --sc
        assertEquals(Color(0xFFE8E2D9), c.surfaceContainerHigh) // --sch
        assertEquals(Color(0xFF1F1D1A), c.onSurface)            // --on
        assertEquals(Color(0xFF54514B), c.onSurfaceVariant)     // --osv
        assertEquals(Color(0xFFD6D0C6), c.outlineVariant)       // --ov
        assertEquals(Color(0xFF7D7972), c.outline)              // --outline
        assertEquals(Color(0xFFDDE6E2), c.secondaryContainer)   // --secc
        assertEquals(Color(0xFFFFDDC4), c.tertiaryContainer)    // --tc
        assertEquals(Color(0xFF3B1F08), c.onTertiaryContainer)  // --otc
        assertEquals(Color(0xFFB3261E), c.error)                // --err
        assertEquals(Color(0xFFF9DEDC), c.errorContainer)       // --errc
        assertEquals(Color(0xFF410E0B), c.onErrorContainer)     // --onerrc
        assertEquals(Color(0xFF33302C), c.inverseSurface)       // --inv
        assertEquals(Color(0xFFF5F0E9), c.inverseOnSurface)     // --oninv
    }

    @Test fun darkSchemeKeepsTheHuesButInvertsTheSurfaces() {
        val light = ClarityPalette.light
        val dark = ClarityPalette.dark
        assertTrue(dark.surface.luminance() < 0.05f && light.surface.luminance() > 0.8f)
        assertTrue(dark.onSurface.luminance() > 0.5f)
        // The primary stays teal: green is the largest-or-equal channel in both schemes.
        for (p in listOf(light.primary, dark.primary)) assertTrue(p.green >= p.red && p.green >= p.blue - 0.02f)
        // The tertiary stays warm: red above blue.
        for (t in listOf(light.tertiaryContainer, dark.tertiaryContainer, dark.tertiary)) assertTrue(t.red > t.blue)
        assertNotEquals(light.primary, dark.primary)
    }

    @Test fun textAndContainersAreReadable() {
        for (scheme in listOf(ClarityPalette.light, ClarityPalette.dark)) {
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 7.0)
            assertTrue(contrast(scheme.onSurfaceVariant, scheme.surface) >= 4.5)
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5)
            assertTrue(contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 7.0)
            assertTrue(contrast(scheme.onTertiaryContainer, scheme.tertiaryContainer) >= 7.0)
            assertTrue(contrast(scheme.onErrorContainer, scheme.errorContainer) >= 4.5)
            assertTrue(contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 7.0)
        }
    }

    @Test fun shapesFollowThePrototype() {
        assertEquals(RoundedCornerShape(24.dp), ClarityShapes.extraLarge) // pill buttons
        assertEquals(RoundedCornerShape(16.dp), ClarityShapes.large)      // cards
        assertEquals(RoundedCornerShape(12.dp), ClarityShapes.medium)     // context boxes
        assertEquals(RoundedCornerShape(8.dp), ClarityShapes.small)       // chips, pills
    }

    @Test fun typographyUsesUiFontForUiAndReadingFontForBodyText() {
        val ui = FontFamily.SansSerif
        val reading = FontFamily.Serif
        val t = clarityTypography(ui, reading)
        assertEquals(reading, t.bodyLarge.fontFamily)   // .rd
        assertEquals(reading, t.bodyMedium.fontFamily)  // .sm
        assertEquals(ui, t.headlineMedium.fontFamily)   // .h
        assertEquals(ui, t.labelLarge.fontFamily)       // buttons
        assertEquals(28.sp, t.headlineMedium.fontSize)
        assertEquals(36.sp, t.headlineMedium.lineHeight)
        assertEquals(16.sp, t.bodyLarge.fontSize)
        assertEquals(14.sp, t.bodyMedium.fontSize)
        assertEquals(12.sp, t.labelMedium.fontSize)
        assertEquals(0.4.sp, t.labelMedium.letterSpacing)

        val x = clarityTextStyles(ui, reading)
        assertEquals(ui, x.agent.fontFamily)
        assertEquals(17.sp, x.agent.fontSize)
        assertEquals(reading, x.reading.fontFamily)
        assertEquals(40.sp, x.hero.fontSize)
        assertEquals(FontStyle.Normal, x.reading.fontStyle ?: FontStyle.Normal)
    }

    @Test fun maskedKeyKeepsThePrefixReadable() {
        assertEquals("sk-ant-api03-••••••••••••••••••••", maskKey("sk-ant-api03-7Qx2Lm9vRt4KpZ8wNc1y"))
        assertEquals("sk-ant", maskKey("sk-ant"))
        assertEquals("", maskKey(""))
    }
}
