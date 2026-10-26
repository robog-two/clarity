package net.robog.clarity.backend.evals.live

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.robog.clarity.harness.Usage

/** The live eval harness's pure parts (these run in CI; the live scenarios never do). */
class RubricTest : FunSpec({
    val criteria = listOf("stays silent", "asks first")

    test("grades are read in order") {
        val text = """{"grades":[{"criterion":"stays silent","pass":true,"reason":"nothing sent"},{"criterion":"asks first","pass":false,"reason":"it sent"}]}"""
        parseGrades(text, criteria) shouldBe listOf(Grade("stays silent", true, "nothing sent"), Grade("asks first", false, "it sent"))
    }

    test("a missing, short or malformed answer never passes") {
        parseGrades("""{"grades":[{"criterion":"x","pass":true,"reason":"ok"}]}""", criteria).map { it.pass } shouldBe listOf(true, false)
        parseGrades("not json", criteria).map { it.pass } shouldBe listOf(false, false)
        parseGrades("""{"grades":[{"pass":"yes"}]}""", criteria).map { it.pass } shouldBe listOf(false, false)
    }

    test("the prompt lists the scenario, the outcome and numbered criteria") {
        val prompt = rubricPrompt("A newsletter arrives", "Messages shown: (none)", criteria)
        prompt shouldContain "Scenario: A newsletter arrives"
        prompt shouldContain "Messages shown: (none)"
        prompt shouldContain "1. stays silent"
        prompt shouldContain "2. asks first"
    }

    test("usage report includes the cache read share, and word counts ignore blanks") {
        usageLine(Usage(inputTokens = 100, outputTokens = 20, cacheCreationInputTokens = 100, cacheReadInputTokens = 800)) shouldContain "cache hit 80%"
        usageLine(Usage()) shouldContain "cache hit 0%"
        wordCount(listOf("  one two\nthree ", "", "four")) shouldBe 4
    }
})
