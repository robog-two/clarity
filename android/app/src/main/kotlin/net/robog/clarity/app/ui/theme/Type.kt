package net.robog.clarity.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.robog.clarity.R

/**
 * Bundled variable fonts (SIL OFL, licenses in `assets/licenses`): Roboto Flex for UI and
 * Libre Caslon Text for reading text. Nothing is downloaded, so the app works on phones without Google Play.
 */
@OptIn(ExperimentalTextApi::class)
object ClarityFonts {
    private val weights = listOf(400, 500, 600, 700)

    val ui: FontFamily = FontFamily(
        weights.map { w ->
            Font(
                R.font.roboto_flex,
                weight = FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        },
    )

    val reading: FontFamily = FontFamily(
        weights.flatMap { w ->
            listOf(
                Font(
                    R.font.libre_caslon_text,
                    weight = FontWeight(w),
                    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                ),
                Font(
                    R.font.libre_caslon_text_italic,
                    weight = FontWeight(w),
                    style = FontStyle.Italic,
                    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
                ),
            )
        },
    )
}

private fun ui(weight: Int, size: Int, line: Int, spacing: Float = 0f, family: FontFamily): TextStyle =
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(weight),
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = spacing.sp,
    )

/** Type scale from the prototype: UI roles in Roboto Flex, `body*` (the `.rd` / `.sm` classes) in Libre Caslon. */
fun clarityTypography(uiFamily: FontFamily, readingFamily: FontFamily): Typography {
    fun u(w: Int, s: Int, l: Int, sp: Float = 0f) = ui(w, s, l, sp, uiFamily)
    fun r(w: Int, s: Int, l: Int) = ui(w, s, l, 0f, readingFamily)
    return Typography(
        displayLarge = u(600, 40, 44),
        displayMedium = u(600, 36, 41),
        displaySmall = u(600, 32, 38),
        headlineLarge = u(400, 32, 40),
        headlineMedium = u(400, 28, 36),
        headlineSmall = u(400, 22, 28),
        titleLarge = u(400, 22, 28),
        titleMedium = u(500, 16, 24, 0.15f),
        titleSmall = u(500, 14, 20, 0.1f),
        bodyLarge = r(400, 16, 25),   // .rd
        bodyMedium = r(400, 14, 21),  // .sm
        bodySmall = r(400, 12, 18),
        labelLarge = u(500, 14, 20, 0.1f),   // buttons, .rs
        labelMedium = u(500, 12, 16, 0.4f),  // .k
        labelSmall = u(500, 11, 16, 0.5f),   // .ct
    )
}

/** Prototype text roles that have no Material slot. */
@Immutable
data class ClarityTextStyles(
    /** `.ag`: the agent's prose. */
    val agent: TextStyle,
    /** `.rd`: reading text. */
    val reading: TextStyle,
    /** `.sm`: small reading text. */
    val readingSmall: TextStyle,
    /** `.k`: kicker / caption line. */
    val kicker: TextStyle,
    /** `.cb`: body line inside a context box. */
    val contextBody: TextStyle,
    /** Big onboarding headings (`h1` 40 / 36). */
    val hero: TextStyle,
    /** Top-level titles (the tabs, the call screens): Libre Caslon italic with its decorative ligatures. */
    val heroTitle: TextStyle,
    /** Chat bubbles and the rows under them (sans, like the rest of Material). */
    val chat: TextStyle,
    /** Secondary lines in chat rows. */
    val chatSmall: TextStyle,
)

fun clarityTextStyles(uiFamily: FontFamily, readingFamily: FontFamily): ClarityTextStyles = ClarityTextStyles(
    agent = ui(500, 17, 24, 0f, uiFamily),
    reading = ui(400, 16, 25, 0f, readingFamily),
    readingSmall = ui(400, 14, 21, 0f, readingFamily),
    kicker = ui(500, 12, 16, 0.4f, uiFamily),
    contextBody = ui(500, 14, 20, 0f, uiFamily),
    hero = ui(600, 40, 44, 0f, uiFamily),
    // Always the serif, even though the rest of the theme is sans.
    heroTitle = ui(600, 32, 38, 0f, ClarityFonts.reading).copy(fontStyle = FontStyle.Italic, fontFeatureSettings = "liga, dlig"),
    chat = ui(400, 16, 24, 0f, uiFamily),
    chatSmall = ui(400, 14, 20, 0.1f, uiFamily),
)

val LocalClarityText = staticCompositionLocalOf<ClarityTextStyles> {
    error("ClarityTheme not provided")
}

