package net.robog.clarity.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Screenshot sizes and density are defined once, in PreviewSizes.kt. A raw @Preview anywhere else in the
 * screenshot source set would bring back its own size or the phone's full density (7 times the pixels).
 */
class PreviewSizesTest {
    private val root = listOf("src/screenshotTest/kotlin", "app/src/screenshotTest/kotlin").map(::File).first { it.isDirectory }

    @Test fun onlyPreviewSizesDeclaresPreviews() {
        val raw = root.walkTopDown().filter { it.extension == "kt" && it.name != "PreviewSizes.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//")
                if (Regex("""@Preview\b(?!Test|Parameter)""").containsMatchIn(code)) "${f.name}:${i + 1}" else null
            }
        }.toList()
        assertTrue(raw.isEmpty(), "Use a size from PreviewSizes.kt (PhonePreview, PartPreview, Screen, ...) instead of @Preview at: $raw")
    }

    @Test fun everySizeRendersAtOneTimesDensity() {
        val sizes = root.walkTopDown().first { it.name == "PreviewSizes.kt" }.readText()
        val previews = Regex("""@Preview\(([^\n]*)\)""").findAll(sizes).map { it.groupValues[1] }.toList()
        assertTrue(previews.isNotEmpty())
        assertTrue(previews.all { "device = PREVIEW_DEVICE" in it }, "every size passes device = PREVIEW_DEVICE")
        assertTrue("dpi=160" in sizes, "PREVIEW_DEVICE stays at 160 dpi")
    }
}
