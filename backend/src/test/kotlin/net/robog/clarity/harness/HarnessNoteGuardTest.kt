package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Anything the harness adds to the journal as text must go through `harnessNote`, which marks it, so
 * code reading the journal (the lead's reply detection) never mistakes it for the person's words. A new
 * note built with a bare `Block.Text(` in the loop would silently count as the person; this fails then.
 */
class HarnessNoteGuardTest : FunSpec({
    val dir = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "backend/src/main/kotlin/net/robog/clarity/harness"), File(it, "src/main/kotlin/net/robog/clarity/harness")) }
        .first { it.isDirectory }

    test("the loop builds no text block of its own") {
        for (name in listOf("Harness.kt", "Loop.kt")) {
            val bare = Regex("""Block\.Text\(""").findAll(File(dir, name).readText()).count()
            (name to bare) shouldBe (name to 0)
        }
    }

    test("every place that appends a note uses harnessNote") {
        Regex("""harnessNote\(""").findAll(File(dir, "Harness.kt").readText()).count() shouldBe 4
    }
})
