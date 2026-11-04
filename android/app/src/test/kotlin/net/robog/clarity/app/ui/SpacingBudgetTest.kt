package net.robog.clarity.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app has three spacing values (Space.Small, Space.Medium, Space.Large). This reads the source
 * tree and fails when a padding, a gap or a spacer is written with a raw dp number instead.
 * Sizes, touch targets, corners and strokes are not spacing and are not looked at. 0.dp is allowed.
 */
class SpacingBudgetTest {
    private val spacingCalls = Regex("""(padding|spacedBy|Spacer|PaddingValues|contentPadding)\s*[(=]""")
    private val rawDp = Regex("""(?<![\w.])([1-9]\d*(\.\d+)?|0\.\d*[1-9]\d*)\.dp\b""")

    @Test
    fun `spacing in app code uses only the Space tokens`() {
        val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name != "Space.kt" }.flatMap { file ->
            val text = file.readText()
            spacingCalls.findAll(text).mapNotNull { call ->
                val args = argumentsAfter(text, call.range.last)
                rawDp.find(args)?.let { "${file.name}:${text.take(call.range.first).count { it == '\n' } + 1} uses ${it.value}" }
            }
        }.toList()
        assertTrue(offenders.isEmpty(), "Use Space.Small, Space.Medium or Space.Large instead of raw dp:\n" + offenders.joinToString("\n"))
    }

    /** The text inside the bracket that [open] points at (or, after `=`, up to the end of the line). */
    private fun argumentsAfter(text: String, open: Int): String {
        if (text[open] != '(') return text.substring(open, text.indexOf('\n', open).let { if (it < 0) text.length else it })
        var depth = 1
        var i = open + 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(open + 1, i)
    }
}
