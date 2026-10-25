package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.ingest.Ingested
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.Stored
import net.robog.clarity.backend.persistence.TimeRange
import net.robog.clarity.backend.triggers.StoredTrigger
import net.robog.clarity.backend.triggers.TriggerStore
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.noun.Payload
import net.robog.clarity.shared.query.TriggerSpec

private class EmptyReader : NounReader {
    override suspend fun messages(range: TimeRange, participant: String?, limit: Int) = emptyList<Stored<MessagePayload>>()
    override suspend fun calendarEvents(range: TimeRange, limit: Int) = emptyList<Stored<CalendarEventPayload>>()
    override suspend fun searchContacts(query: String, limit: Int) = emptyList<Stored<ContactPayload>>()
    override suspend fun changedSince(since: Instant, limit: Int) = emptyList<Stored<Payload>>()
}

private class NoTriggers : TriggerStore {
    override suspend fun list() = emptyList<StoredTrigger>()
    override suspend fun enabledFor(noun: Noun) = emptyList<StoredTrigger>()
    override suspend fun create(spec: TriggerSpec) = StoredTrigger("1", spec)
    override suspend fun delete(id: String) = false
}

private class RecordingRunStore : AgentRunStore {
    val events = mutableListOf<String>()
    val done = CompletableDeferred<Unit>()
    var transcript: JsonElement? = null
    var error: String? = null
    var task: JsonElement? = null

    override suspend fun create(id: AgentRunId, reason: String, task: JsonElement, startedAt: Instant) {
        events += "create:$reason"; this.task = task
    }

    override suspend fun markRunning(id: AgentRunId) { events += "running" }

    override suspend fun finish(id: AgentRunId, status: String, transcript: JsonElement?, usage: JsonElement?, error: String?, finishedAt: Instant) {
        events += "finish:$status"; this.transcript = transcript; this.error = error; done.complete(Unit)
    }
}

private class CapturingGateway(private val reply: () -> ModelResponse) : ModelGateway {
    val requests = mutableListOf<ModelRequest>()
    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        requests += request
        return reply()
    }
}

private fun config(key: String?) = AgentConfig(anthropicApiKey = key, reviewInterval = 2.hours)

class DefaultAgentDispatcherTest : FunSpec({
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    afterSpec { scope.cancel() }

    fun dispatcher(key: String?, store: AgentRunStore, gateway: ModelGateway): DefaultAgentDispatcher {
        val cfg = config(key)
        return DefaultAgentDispatcher(
            cfg, scope, Clock.System, store, DomainTools(EmptyReader(), NoTriggers()), McpToolSources(cfg),
            ModelGatewayFactory { gateway },
        )
    }

    test("blank API key disables agents: returns null and persists nothing") {
        val store = RecordingRunStore()
        val d = dispatcher("  ", store, CapturingGateway { error("must not be called") })
        d.dispatch(AgentTask.PeriodicReview(Instant.parse("2026-10-24T00:00:00Z"))) shouldBe null
        d.dispatch(AgentTask.PeriodicReview(Instant.parse("2026-10-24T00:00:00Z"))) shouldBe null
        store.events shouldBe emptyList()
    }

    test("a periodic review runs end to end and is persisted queued -> running -> end_turn") {
        val store = RecordingRunStore()
        val gateway = CapturingGateway {
            ModelResponse(listOf(Block.Text("nothing new")), StopReason.END_TURN, Usage(100, 20))
        }
        val id = dispatcher("sk-test", store, gateway).dispatch(AgentTask.PeriodicReview(Instant.parse("2026-10-24T00:00:00Z")))

        id.shouldBeInstanceOf<AgentRunId>()
        withTimeout(10.seconds) { store.done.await() }
        store.events shouldBe listOf("create:periodic_review", "running", "finish:end_turn")
        store.transcript.toString() shouldContain "nothing new"

        val request = gateway.requests.single()
        request.model shouldBe "claude-opus-5-5"
        request.system shouldBe AgentPrompts.SYSTEM
        request.system shouldNotContain "Current time"
        request.tools.map { it.name } shouldBe
            listOf("create_trigger", "delete_trigger", "list_calendar_events", "list_messages", "list_triggers", "search_contacts")
        val prompt = (request.messages.single().content.single() as Block.Text).text
        prompt shouldContain "Current time:"
        prompt shouldContain "2026-10-24T00:00:00Z"
    }

    test("a model failure is recorded as failed with the error") {
        val store = RecordingRunStore()
        dispatcher("sk-test", store, CapturingGateway { error("overloaded") })
            .dispatch(AgentTask.PeriodicReview(Instant.parse("2026-10-24T00:00:00Z")))
        withTimeout(10.seconds) { store.done.await() }
        store.events.last() shouldBe "finish:failed"
        store.error shouldBe "overloaded"
    }

    test("trigger tasks render the instruction and record") {
        val record = Ingested(
            ConnectorId("imap-main"), EntityId("m1"),
            MessagePayload(
                MessagePayload.Channel.EMAIL, Handle.email("me@x.org"), Handle.email("boss@x.org"),
                listOf(Handle.email("me@x.org")), "Urgent", "call me", Instant.parse("2026-10-24T08:00:00Z"),
            ),
            created = true,
        )
        val text = AgentPrompts.render(AgentTask.TriggerFired("t1", "Alert me", record), Instant.parse("2026-10-24T09:00:00Z"))
        text shouldContain "Alert me"
        text shouldContain "boss@x.org"
        text shouldContain "call me"
        AgentPrompts.describe(AgentTask.TriggerFired("t1", "Alert me", record)).toString() shouldContain "imap-main"
    }
})
