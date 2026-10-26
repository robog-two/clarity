package net.robog.clarity.backend.evals

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.args
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.harness.Block
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.StartSession

/**
 * Eval: the bytes a prompt cache keys on (system blocks, loaded tools, deferred tools) are the
 * same on every request of a 20-turn day, whatever the turns do: talk, tool calls, memory writes,
 * triggers, a morning call starting and ending. They change exactly once, when the constitution
 * is amended, and then only the constitution's own block moves.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class PrefixStabilityEvalTest : FunSpec({
    test("tools and system never change across 20 turns except at a constitution amendment") {
        val r = EvalDb.rig()
        r.constitutions.create(Constitution(1, emptyList(), updatedAt = Clock.System.now()))
        var wrote = false
        r.model.handler = { request ->
            when {
                request.messages.last().content.any { it is Block.Result } -> end("ok")
                !wrote && request.transcript().contains("remember the dentist") -> { wrote = true; callTool("w", "write_journal", args("text" to "Dentist on Friday.")) }
                else -> end("noted")
            }
        }
        r.lead.start()

        var amendedAt = -1
        for (turn in 1..20) {
            when (turn) {
                3 -> r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u$turn"), "remember the dentist"))
                6 -> r.sessionService.start(StartSession(SessionKind.MORNING_CALL)) // a call starts mid-day
                12 -> {
                    amendedAt = r.model.requests.size
                    r.constitutions.saveVersion { it.copy(profile = it.profile + "Lives in Berlin") }
                    r.inbox.enqueue(InboxItem.ConstitutionChanged(2))
                }
                15 -> r.inbox.enqueue(InboxItem.TriggerFired("t", "Dana", "tell me", """{"subject":"x"}""", "message from connector mail"))
                17 -> r.inbox.enqueue(InboxItem.ActionResult("a1", "Send reply", done = true))
                else -> r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u$turn"), "message $turn"))
            }
            r.lead.step() shouldBe true
        }
        while (r.lead.step()) Unit // whatever the call and the triggers left behind

        val prefixes = r.model.requests.map { it.prefix() }
        (prefixes.size >= 18) shouldBe true
        // before the amendment: one prefix; after: one other prefix; nothing in between
        prefixes.take(amendedAt).toSet() shouldHaveSize 1
        prefixes.drop(amendedAt).toSet() shouldHaveSize 1
        val (before, after) = prefixes.first() to prefixes.last()
        (before == after) shouldBe false
        after.tools shouldBe before.tools
        after.deferred shouldBe before.deferred
        after.system.first() shouldBe before.system.first() // the instructions block is untouched
        after.system.last().text.contains("Lives in Berlin") shouldBe true
        // both system blocks keep their cache breakpoints
        prefixes.all { p -> p.system.map { it.cacheBreakpoint } == listOf(true, true) } shouldBe true
        // no request carries both: sorted tool order is byte-stable
        prefixes.all { p -> p.tools.map { it.name } == p.tools.map { it.name }.sorted() } shouldBe true
    }
})
