package net.robog.clarity.backend.evals

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.governance.ActionOutboxTable
import net.robog.clarity.backend.governance.ActivityTable
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Eval: the process dies in the middle of a turn after the lead has done something on its own.
 * A new process on the same database resumes the thread and does not do it again: one outbox
 * row, one activity entry, and the person's message is answered once.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class RestartEvalTest : FunSpec({
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    afterSpec { scope.cancel() }

    suspend fun outboxRows() = EvalDb.database.tx { ActionOutboxTable.selectAll().count() }
    suspend fun activityRows() = EvalDb.database.tx { ActivityTable.selectAll().count() }

    suspend fun crashedAfterSend(dropResult: Boolean): LeadRig {
        val first = EvalDb.rig()
        first.constitutions.create(
            Constitution(1, emptyList(), autonomy = mapOf(ActionKind.SEND_EMAIL to AutonomyTier.DO_THEN_TELL), updatedAt = Clock.System.now()),
        )
        val sent = CompletableDeferred<Unit>()
        first.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result }) {
                sent.complete(Unit)
                awaitCancellation() // the process "dies" here
            } else sendEmail("toolu_once", "dana@example.com")
        }
        first.inbox.enqueue(InboxItem.UserInput(ClientInputId("u1"), "send Dana the contract"))
        first.lead.start()
        val turn = scope.launch { first.lead.step() }
        withTimeout(20.seconds) { sent.await() }
        turn.cancelAndJoin()
        outboxRows() shouldBe 1
        if (dropResult) {
            // worst case: it died before the tool result reached the journal
            EvalDb.database.tx { exec("DELETE FROM agent_journal WHERE seq = (SELECT max(seq) FROM agent_journal)") }
        }
        return first
    }

    test("restart after an allowed send: the resumed turn does not queue it again") {
        crashedAfterSend(dropResult = true)

        val second = LeadRig(EvalDb.database)
        second.model.handler = { end("sent; telling the person") }
        second.lead.start()

        outboxRows() shouldBe 1
        activityRows() shouldBe 1
        val resumed = second.model.requests.single().messages.last().content.filterIsInstance<Block.Result>().single()
        resumed.toolUseId shouldBe "toolu_once"
        resumed.content shouldContain "Already queued"
        second.lead.step() shouldBe false
        second.inbox.pending().shouldBeEmpty()
    }

    test("restart with the result already journaled: the thread continues, nothing is repeated, new input is answered once") {
        crashedAfterSend(dropResult = false)

        val second = LeadRig(EvalDb.database)
        second.model.handler = { end("fine") }
        second.lead.start() // resumes the open turn: the model is asked to finish it
        second.model.requests.size shouldBe 1
        outboxRows() shouldBe 1

        second.inbox.enqueue(InboxItem.UserInput(ClientInputId("u2"), "thanks"))
        second.lead.step() shouldBe true
        second.model.requests.size shouldBe 2
        second.model.requests.last().messages.count { m -> m.role == Role.USER && m.text().contains("thanks") } shouldBe 1
        second.lead.step() shouldBe false
        outboxRows() shouldBe 1
        activityRows() shouldBe 1
        // the thread is intact: the person's first words are still the first message
        second.journal.load(second.threads.ensure().id).first().shouldBeInstanceOf<net.robog.clarity.harness.Message>().text() shouldContain "send Dana the contract"
    }
})
