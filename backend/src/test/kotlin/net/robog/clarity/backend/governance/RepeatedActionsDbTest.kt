package net.robog.clarity.backend.governance

import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.end
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.Connectors
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.ToolCallContext
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Outbound actions proposed again with the same content by the same run or thread, now that every
 * tool call has its own id: one action, not one per call. Against real Postgres; the model is
 * scripted and never reached over the network.
 */
@EnabledIf(ContainerRuntimeAvailable::class)
class RepeatedActionsDbTest : FunSpec({
    val postgres = PostgreSQLContainer("postgres:18-alpine")
    lateinit var database: Database
    lateinit var rig: LeadRig
    lateinit var decisions: HeldActionService

    beforeSpec {
        postgres.start()
        val dataSource = DatabaseFactory.dataSource(DatabaseConfig(postgres.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 8))
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        database.tx {
            Connectors.insertIgnore {
                it[id] = "mail"
                it[provisioned] = true
                it[createdAt] = Clock.System.now()
                it[manifest] = ConnectorManifest(
                    "mail", "Mail", "1", setOf(Noun.MESSAGE),
                    handles = setOf(ActionKind.SAVE_DRAFT, ActionKind.SEND_EMAIL, ActionKind.CREATE_EVENT, ActionKind.CREATE_REMINDER),
                )
            }
        }
        rig = LeadRig(database)
        decisions = HeldActionService(database, Clock.System, rig.heldStore, rig.constitutions, rig.outbox, ConnectorRouter(database, NoConnectorPreferences()), rig.events, rig.vaultStore)
    }
    afterSpec { postgres.stop() }
    beforeTest { rig.reset() }

    /** The person lets these go out on their own (no approval). */
    suspend fun autonomous(vararg kinds: ActionKind) {
        rig.constitutions.create(Constitution(version = 1, lines = emptyList(), autonomy = kinds.associateWith { AutonomyTier.DO_THEN_TELL }, updatedAt = Clock.System.now()))
    }

    suspend fun outboxRows(): List<ProposedAction> = database.tx { ActionOutboxTable.selectAll().map { it[ActionOutboxTable.action] } }

    fun email(body: String = "Here is the update.", to: List<String> = listOf("dana@example.com"), subject: String = "Update") = buildJsonObject {
        put("from", "me@gmail.example")
        put("newThread", true)
        put("to", JsonArray(to.map(::JsonPrimitive)))
        put("subject", subject)
        put("body", body)
        put("reason", "the person asked")
    }

    /** One tool call of run [runId] with its own call id, as the harness makes it. */
    suspend fun call(runId: String, callId: String, tool: String, input: JsonObject): String {
        val t = rig.external.all(RunScope(runId, isLead = true)).first { it.name == tool }
        val result = withContext(ToolCallContext(runId, callId)) { t.invoke(input) }
        return result.shouldBeInstanceOf<ToolResult.Ok>().content
    }

    // ---- through the lead ------------------------------------------------------------------------

    /** The send and a short reply to the person, in one step. */
    fun sendCall(n: Int, body: String) = ModelResponse(
        listOf(
            Block.ToolUse("send_$n", "send_email", email(body)),
            Block.ToolUse(
                "reply_$n", "send_to_user",
                buildJsonObject { put("cards", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "Sent.") }))) },
            ),
        ),
        StopReason.TOOL_USE, Usage(100, 10),
    )

    suspend fun twoTurns(firstBody: String, secondBody: String): List<String> {
        var calls = 0
        val bodies = listOf(firstBody, secondBody)
        rig.model.handler = { request ->
            if (request.messages.last().content.any { it is Block.Result } || calls >= bodies.size) end("done")
            else sendCall(calls, bodies[calls]).also { calls++ }
        }
        rig.lead.start()
        rig.inbox.enqueue(net.robog.clarity.backend.agent.InboxItem.UserInput(ClientInputId("u1"), "send Dana the update"))
        rig.lead.step() shouldBe true
        rig.inbox.enqueue(net.robog.clarity.backend.agent.InboxItem.UserInput(ClientInputId("u2"), "did you send it?"))
        rig.lead.step() shouldBe true
        return rig.journal.load(rig.threads.ensure().id).flatMap { it.content }.filterIsInstance<Block.Result>().map { it.content }
    }

    // Guards against: a later lead turn sending the very same email again as a second real action.
    test("two lead turns send the same email: one outbox row") {
        autonomous(ActionKind.SEND_EMAIL)
        val results = twoTurns("Here is the update.", "Here is   the update.\n")
        withClue(results.joinToString("\n")) {
            outboxRows() shouldHaveSize 1
            results.count { it.contains("Already queued as") } shouldBe 1
        }
    }

    // Guards against: the dedupe swallowing a corrected email.
    test("two lead turns send the same email with a changed body: two outbox rows") {
        autonomous(ActionKind.SEND_EMAIL)
        val results = twoTurns("Here is the update.", "Here is the corrected update.")
        withClue(results.joinToString("\n")) {
            outboxRows() shouldHaveSize 2
            results.none { it.contains("Already") } shouldBe true
        }
    }

    // ---- tool calls ------------------------------------------------------------------------------

    // Guards against: recipients in another order or case, or reflowed whitespace, counting as a new email.
    test("recipient order and case and whitespace do not make a repeat new; a held one is not asked twice") {
        val first = call("thread-1", "c1", "send_email", email(to = listOf("dana@example.com", "Bo <bo@example.com>")))
        val again = call("thread-1", "c2", "send_email", email(body = " Here is the update. ", to = listOf("BO@example.com", "Dana@Example.com")))
        rig.heldStore.list() shouldHaveSize 1
        first shouldContain "Held for review"
        again shouldContain "Already waiting for the person as ${rig.heldStore.list().single().id}"
    }

    // Guards against: one run's email blocking the same email from another run or thread.
    test("the same email from another run is its own action") {
        autonomous(ActionKind.SEND_EMAIL)
        call("thread-1", "c1", "send_email", email())
        call("helper-9", "c1", "send_email", email()) shouldNotContain "Already"
        outboxRows() shouldHaveSize 2
    }

    // Guards against: a changed subject or recipient counting as the same email.
    test("a different subject or recipient is a new email") {
        autonomous(ActionKind.SEND_EMAIL)
        call("thread-1", "c1", "send_email", email())
        call("thread-1", "c2", "send_email", email(subject = "Update, part two"))
        call("thread-1", "c3", "send_email", email(to = listOf("eve@example.com")))
        outboxRows() shouldHaveSize 3
    }

    // Guards against: a repeat after the first one went out being sent again.
    test("a repeat of an email already sent says it was sent, and sends nothing") {
        autonomous(ActionKind.SEND_EMAIL)
        call("thread-1", "c1", "send_email", email())
        database.tx { ActionOutboxTable.update { it[status] = OutboxStatus.DONE } }
        call("thread-1", "c2", "send_email", email()) shouldContain "Already sent as"
        outboxRows() shouldHaveSize 1
    }

    // Guards against: a failed send blocking the retry, or an old email blocking a new day's.
    test("after a failed send, or a day later, the same email goes out again") {
        autonomous(ActionKind.SEND_EMAIL)
        call("thread-1", "c1", "send_email", email())
        database.tx { ActionOutboxTable.update { it[status] = OutboxStatus.FAILED } }
        call("thread-1", "c2", "send_email", email()) shouldNotContain "Already"
        database.tx { ActionOutboxTable.update { it[createdAt] = Clock.System.now() - 25.hours } }
        call("thread-1", "c3", "send_email", email()) shouldNotContain "Already"
        outboxRows() shouldHaveSize 3
    }

    // Guards against: the dedupe getting in the way of the person's decision.
    test("after the person declines, the same email is asked again; after they approve an edit, the original is not sent twice") {
        call("thread-1", "c1", "send_email", email())
        val first = rig.heldStore.list().single()
        decisions.decide(first.id, HeldDecision.Decline).isRight() shouldBe true
        call("thread-1", "c2", "send_email", email()) shouldContain "Held for review"
        val second = rig.heldStore.list(setOf(HeldAction.Status.HELD)).single()
        second.id shouldNotBe first.id

        val original = second.action as ProposedAction.SendEmail
        val edited = original.copy(email = original.email.copy(body = "Here is the update, edited by me."))
        decisions.decide(second.id, HeldDecision.Approve(edited)).isRight() shouldBe true
        outboxRows() shouldHaveSize 1
        call("thread-1", "c3", "send_email", email()) shouldContain "Already"
        outboxRows() shouldHaveSize 1
        // the person's edited text, proposed again, is a repeat too
        call("thread-1", "c4", "send_email", email(body = "Here is the update, edited by me.")) shouldContain "Already"
        outboxRows() shouldHaveSize 1
    }

    // Guards against: a draft, reminder or event saved once per repeated call.
    test("save_draft, create_reminder and create_event repeated make one each; a changed one is new") {
        autonomous(ActionKind.SAVE_DRAFT, ActionKind.CREATE_REMINDER, ActionKind.CREATE_EVENT)
        call("thread-1", "c1", "save_draft", email())
        call("thread-1", "c2", "save_draft", email()) shouldContain "Already"
        call("thread-1", "c3", "save_draft", email(body = "A better draft.")) shouldNotContain "Already"
        val reminder = buildJsonObject { put("text", "Call the dentist"); put("at", "2026-11-02T09:00"); put("reason", "asked") }
        call("thread-1", "c4", "create_reminder", reminder)
        call("thread-1", "c5", "create_reminder", reminder) shouldContain "Already"
        val event = buildJsonObject { put("title", "Focus"); put("start", "2026-11-02T09:00"); put("end", "2026-11-02T11:00"); put("reason", "asked") }
        call("thread-1", "c6", "create_event", event)
        call("thread-1", "c7", "create_event", event) shouldContain "Already"
        call("thread-1", "c8", "create_event", buildJsonObject { put("title", "Focus"); put("start", "2026-11-02T13:00"); put("end", "2026-11-02T15:00"); put("reason", "asked") }) shouldNotContain "Already"
        outboxRows().groupingBy { it.kind }.eachCount() shouldBe mapOf(ActionKind.SAVE_DRAFT to 2, ActionKind.CREATE_REMINDER to 1, ActionKind.CREATE_EVENT to 2)
    }

    // Guards against: the LIKE prefix matching other runs whose id shares characters with wildcards.
    test("a run id with LIKE wildcards matches only its own actions") {
        autonomous(ActionKind.SEND_EMAIL)
        call("run_1", "c1", "send_email", email())
        call("runx1", "c1", "send_email", email()) shouldNotContain "Already"
        outboxRows() shouldHaveSize 2
        database.tx { ActionOutboxTable.selectAll().where { ActionOutboxTable.idempotencyKey eq "run_1:c1" }.count() } shouldBe 1
    }
})
