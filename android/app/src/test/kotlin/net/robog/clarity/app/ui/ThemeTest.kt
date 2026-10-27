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
    @Test fun brandColoursSitInTheirMaterialRoles() {
        val light = ClarityPalette.light
        val dark = ClarityPalette.dark
        // Charcoal page and Bone text in dark; flipped in light (page is a lighter tint of Bone).
        assertEquals(ClarityPalette.Charcoal, dark.surface)
        assertEquals(ClarityPalette.Charcoal, dark.background)
        assertEquals(ClarityPalette.Bone, dark.onSurface)
        assertEquals(ClarityPalette.Charcoal, light.onSurface)
        assertEquals(ClarityPalette.Bone, light.surfaceContainer)
        // Peach is the accent: primary in dark, the filled primary container in light.
        assertEquals(ClarityPalette.Peach, dark.primary)
        assertEquals(ClarityPalette.Peach, light.primaryContainer)
        // Sage is the secondary surface in both.
        assertEquals(ClarityPalette.Sage, light.secondaryContainer)
        assertEquals(ClarityPalette.Sage, dark.secondaryContainer)
    }

    @Test fun darkSchemeInvertsTheSurfacesAndKeepsTheHues() {
        val light = ClarityPalette.light
        val dark = ClarityPalette.dark
        assertTrue(dark.surface.luminance() < 0.05f && light.surface.luminance() > 0.8f)
        assertTrue(dark.onSurface.luminance() > 0.5f)
        // The primary stays peach: red above green above blue in both schemes.
        for (p in listOf(light.primary, dark.primary, light.primaryContainer)) assertTrue(p.red > p.green && p.green > p.blue)
        // The secondary stays sage: green is the largest channel.
        for (s in listOf(light.secondary, dark.secondary)) assertTrue(s.green >= s.red && s.green >= s.blue)
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
            // Sage is a mid-tone, so text on it meets AA (4.5) rather than AAA.
            assertTrue(contrast(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5)
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
        assertEquals("sk-ant-api03-••••••••••••••••••••", maskKey("sk-ant-api03-EXAMPLEEXAMPLEEXAMPL"))
        assertEquals("sk-ant", maskKey("sk-ant"))
        assertEquals("", maskKey(""))
    }
}
