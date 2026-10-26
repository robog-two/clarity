package net.robog.clarity.backend.evals

import arrow.core.Either
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.agent.InboxForwarder
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.SessionTools
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.harness.Block
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.StartSession

private const val NEW_LINE = "Replies to people I write to every week can go out after a short wait."

private fun <A> Either<*, A>.ok(): A = (this as? Either.Right)?.value ?: error("expected success but was $this")

/**
 * Eval: the guard holds a send, the person talks it through in an amend session, the amendment is
 * saved as constitution v2, and the lead learns about it: the decision and the new version arrive
 * as one inbox message and the next request carries the new line in its system prompt.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class HeldAmendEvalTest : FunSpec({
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    afterSpec { scope.cancel() }

    test("held -> amend session -> constitution v2 -> the lead's prompt changes") {
        val r = EvalDb.rig()
        r.constitutions.create(
            Constitution(
                1,
                listOf(Line(LineId("l-send"), Section.VALUES, "Ask before anything leaves the building.", "a sent mail cannot be unsent")),
                updatedAt = Clock.System.now(),
            ),
        ).ok()
        r.guardVerdict = GuardVerdict(Decision.ASK, listOf(LineId("l-send")), "It goes out as you.")
        scope.launch { InboxForwarder(r.events, r.results, r.inbox, net.robog.clarity.backend.governance.HeldWaiters(Clock.System)).run() }
        delay(300) // let the forwarder subscribe before anything is published

        // 1. the lead proposes a send; it is held
        r.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) end("held") else sendEmail("toolu_s", "dana@example.com")
        }
        r.inbox.enqueue(InboxItem.UserInput(net.robog.clarity.shared.feed.ClientInputId("u1"), "send Dana the contract"))
        r.lead.start()
        r.lead.step() shouldBe true
        val held = r.heldStore.list().single()
        held.status shouldBe HeldAction.Status.HELD
        val before = r.model.requests.last().prefix()
        before.system.joinToString { it.text } shouldNotContain NEW_LINE

        // 2. the person opens an amend session; the lead proposes the change
        val session = r.sessionService.start(StartSession(SessionKind.AMEND, held.id)).ok()
        val proposal = ClarityJson.encodeToJsonElement(
            SessionTools.ProposeAmendmentInput.serializer(),
            SessionTools.ProposeAmendmentInput("l-send", NEW_LINE, "I trust those people", "Dana is a weekly contact"),
        ) as JsonObject
        r.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) end("proposed") else callTool("toolu_a", "propose_amendment", proposal)
        }
        r.lead.step() shouldBe true // the amend playbook
        r.constitutions.amendments(Amendment.Status.PROPOSED) shouldHaveSize 1

        // 3. the person saves it: v2, the session ends, the held action is settled
        val amendment = r.constitutions.amendments(Amendment.Status.PROPOSED).single()
        r.constitutions.saveAmendment(amendment.id).ok().version shouldBe 2
        r.sessionService.get(session.id).ok().state shouldBe Session.State.ENDED
        r.heldStore.get(held.id)!!.status shouldBe HeldAction.Status.RULE_UPDATED

        // 4. the forwarder notes the decision for the lead (the new version is in its prompt, not its inbox)
        withTimeout(10.seconds) {
            while (r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.HeldDecided>().isEmpty()) delay(50)
        }
        r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.ConstitutionChanged>() shouldHaveSize 0
        r.inbox.pending().map { it.item }.filterIsInstance<InboxItem.HeldDecided>().single().priority shouldBe net.robog.clarity.backend.agent.Priority.LOW
        r.model.handler = { end("noted") }
        r.lead.step() shouldBe true
        val last = r.model.requests.last()
        // the system prompt now carries the new line; the tools did not move
        val after = last.prefix()
        after.system.joinToString { it.text } shouldContain NEW_LINE
        after.system shouldNotBe before.system
        after.tools shouldBe before.tools
        after.deferred shouldBe before.deferred
    }
})
