package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Anything the harness adds to the lead's journal as text must go through `harnessNote`, which marks it,
 * so code reading the journal (the lead's reply detection, the archive) never mistakes it for the
 * person's words. A new note built with a bare `Block.Text(` in a journal writer would silently count
 * as the person; this fails then. Known-safe constructions are listed with the reason.
 */
class HarnessNoteGuardTest : FunSpec({
    fun source(path: String): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .flatMap { sequenceOf(File(it, "backend/src/main/kotlin/net/robog/clarity/$path"), File(it, "src/main/kotlin/net/robog/clarity/$path")) }
        .first { it.isFile }

    // file -> a fragment of each line that may build a bare text block, and why
    val allowed: Map<String, List<String>> = mapOf(
        "harness/Interrupts.kt" to listOf("Block.Text(HARNESS_NOTE_MARK + text)"), // the marker itself
        "harness/Harness.kt" to listOf("request.tailNote?.let { Message(Role.SYSTEM, listOf(Block.Text(it))) }"), // the tail note: a system message on the wire, never journaled
        "backend/agent/LeadAgent.kt" to listOf("Message(Role.SYSTEM, listOf(Block.Text(it)))"), // memory context: a system message, never a person's
    )

    test("no journal writer builds a bare text block outside the allowlist") {
        for (path in listOf("harness/Harness.kt", "harness/Loop.kt", "harness/Interrupts.kt", "backend/agent/LeadAgent.kt")) {
            val bare = source(path).readLines().filter { "Block.Text(" in it }
                .filter { line -> allowed[path].orEmpty().none { it in line } }
            (path to bare) shouldBe (path to emptyList<String>())
        }
    }
})
