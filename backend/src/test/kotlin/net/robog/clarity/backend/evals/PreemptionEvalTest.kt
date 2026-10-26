package net.robog.clarity.backend.evals

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.callTool
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem

/**
 * Eval: the person speaks while the lead is in the middle of a long background turn. The model
 * call in flight is cancelled and leaves nothing in the thread; the background note is not lost
 * (it rides along with the person's words) and the answer reaches the feed exactly once.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class PreemptionEvalTest : FunSpec({
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    afterSpec { scope.cancel() }

    test("user input pre-empts a long background turn; nothing is lost or answered twice") {
        val r = EvalDb.rig()
        val started = CompletableDeferred<Unit>()
        r.model.handler = { request ->
            when {
                r.model.requests.size == 1 -> { started.complete(Unit); awaitCancellation() }
                request.messages.last().content.any { it is Block.Result } -> end("answered")
                else -> callTool(
                    "toolu_reply", "send_to_user",
                    buildJsonObject { put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "Moved to 4pm.") }))) },
                )
            }
        }
        r.inbox.enqueue(InboxItem.System("Newsletter digest arrived"))
        r.lead.start()
        val turn = scope.async { r.lead.step() }
        withTimeout(10.seconds) { started.await() }
        r.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), "move my 3pm to 4pm"))
        withTimeout(30.seconds) { turn.await() } shouldBe true

        // cancelled call, then the reply, then the wrap-up
        r.model.requests shouldHaveSize 3
        val second = r.model.requests[1].transcript()
        second shouldContain "Newsletter digest arrived"
        // the person's words go in a user message ahead of the operator update, never after it (the API refuses that)
        second shouldContain "USER: (operator update follows)\nmove my 3pm to 4pm"
        net.robog.clarity.harness.systemPlacementViolations(r.model.requests[1].messages) shouldBe emptyList()
        r.model.requests[1].messages.count { it.role == Role.ASSISTANT } shouldBe 0

        // journal shape: the inputs first, then the assistant's work; no half-finished background answer
        val journal = r.journal.load(r.threads.ensure().id)
        val firstAssistant = journal.indexOfFirst { it.role == Role.ASSISTANT }
        journal.take(firstAssistant).all { it.role == Role.USER || it.role == Role.SYSTEM } shouldBe true
        journal.last().role shouldBe Role.ASSISTANT

        // delivered once
        val agentItems = r.feed.page(0, 50).items.filter { it.author == FeedItem.Author.AGENT }
        agentItems.single().cards shouldBe listOf(Card.Text("Moved to 4pm."))
        r.inbox.pending().shouldBeEmpty()
        r.lead.step() shouldBe false
    }
})
