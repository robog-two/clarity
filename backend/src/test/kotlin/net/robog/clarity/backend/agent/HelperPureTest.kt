package net.robog.clarity.backend.agent

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.RunResult
import net.robog.clarity.harness.Usage

class HelperPureTest : FunSpec({
    val available = setOf("ask_code", "list_credentials", "search_memory", "browser__navigate_page", "browser__take_snapshot", "browser__fill_by_uid")

    context("which tools a helper gets") {
        test("a list without the browser or its companions stays as it is") {
            completeHelperTools(listOf("search_memory"), available) shouldBe Either.Right(listOf("search_memory"))
        }
        test("ask_code alone brings the whole browser (run e15b3bca got only ask_code)") {
            val tools = completeHelperTools(listOf("ask_code"), available).shouldBeInstanceOf<Either.Right<List<String>>>().value
            tools shouldContainAll listOf("ask_code", "list_credentials", "browser__navigate_page", "browser__take_snapshot", "browser__fill_by_uid")
        }
        test("browser tools bring ask_code and list_credentials") {
            completeHelperTools(listOf("browser__navigate_page"), available) shouldBe
                Either.Right(listOf("browser__navigate_page", "ask_code", "list_credentials"))
        }
        test("ask_code with no browser to use it in fails the delegation") {
            val left = completeHelperTools(listOf("ask_code"), setOf("ask_code", "list_credentials")).shouldBeInstanceOf<Either.Left<String>>()
            left.value shouldContain "Not started"
            left.value shouldContain "browser"
        }
    }

    context("out of steps") {
        test("the lead gets the helper's report") {
            val finished = HarnessEvent.Finished(RunResult(FinishReason.MAX_TURNS, Usage.ZERO, emptyList(), "Passed the code step; on the store search."))
            helperOutcome(finished) shouldBe (false to "The helper ran out of steps. Its report: Passed the code step; on the store search.")
        }
    }

    context("saved transcripts") {
        test("hold placeholders, never a released value or part of one") {
            val grants = GrantStore(StepClock())
            grants.put("ulta_login", "ulta.com", mapOf("email" to "dana@example.com", "password" to "B86yxc4qM7KXmG##"), 5.minutes)
            val messages = listOf(
                Message(Role.ASSISTANT, listOf(Block.ToolUse("t1", "browser__fill_by_uid", buildJsonObject { put("value", "[secret:ulta_login_password]") }))),
                Message(Role.USER, listOf(Block.Result("t1", "uid=e1 textbox \"Email\" value=\"B86yxc4qdana@example.com\" and dana@example.com"))),
            )
            val text = helperTranscript(messages, grants::scrub).toString()
            text shouldContain "[secret:ulta_login_password]"
            text shouldContain "[secret:ulta_login_email]"
            text shouldNotContain "B86yxc"
            text shouldNotContain "dana@example"
        }
    }
})
