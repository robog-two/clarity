package net.robog.clarity.backend.agent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.feed.ExposedFeedStore
import net.robog.clarity.backend.feed.FeedHub
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.backend.governance.ActionOutbox
import net.robog.clarity.backend.governance.ActionResults
import net.robog.clarity.backend.governance.ConnectorRouter
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.Guard
import net.robog.clarity.backend.governance.GuardModel
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.NoConnectorPreferences
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.governance.UtcPersonZone
import net.robog.clarity.backend.persistence.ExposedNounReader
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.sessions.CallEffortOverride
import net.robog.clarity.backend.sessions.DayPlanStore
import net.robog.clarity.backend.sessions.SessionService
import net.robog.clarity.backend.sessions.SessionStore
import net.robog.clarity.backend.triggers.ExposedTriggerStore
import net.robog.clarity.backend.vault.ExposedVaultStore
import net.robog.clarity.backend.vault.GovernedVaultHolds
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.backend.vault.VaultService
import net.robog.clarity.backend.vault.VaultTools
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.jdbc.Database

/** A clock tests can move forward; starts at the real time so database defaults line up. */
class MovableClock : Clock {
    private val offsetMs = AtomicLong(0)
    fun advance(by: Duration) { offsetMs.addAndGet(by.inWholeMilliseconds) }
    override fun now(): Instant = Clock.System.now() + offsetMs.get().milliseconds
}

/** A scripted model: records every request and answers with whatever [handler] says. Never touches the network. */
class FakeModel : ModelGateway {
    val requests = CopyOnWriteArrayList<ModelRequest>()
    var handler: suspend (ModelRequest) -> ModelResponse = { end("nothing to do") }

    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        requests += request
        return handler(request)
    }

    fun requestsFor(model: String) = requests.filter { it.model == model }
}

fun end(text: String = "ok") = ModelResponse(listOf(Block.Text(text)), StopReason.END_TURN, Usage(100, 10))

fun callTool(id: String, name: String, input: JsonObject = buildJsonObject { }) =
    ModelResponse(listOf(Block.ToolUse(id, name, input)), StopReason.TOOL_USE, Usage(100, 10))

fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

const val LEAD_MODEL = "claude-opus-5-5"
const val HELPER_MODEL = "claude-sonnet-5-5"

/** Everything the lead needs, wired by hand against a real database (what Metro does in production). */
class LeadRig(val database: Database, val clock: Clock = Clock.System, apiKey: String? = "sk-test") {
    val model = FakeModel()
    val config = AgentConfig(anthropicApiKey = apiKey, reviewInterval = 2.hours, maxConcurrentRuns = 2)
    val runtime = AgentRuntime(config, ModelGatewayFactory { model })

    val signals = InboxSignals()
    val inbox = ExposedInboxStore(database, clock, signals)
    val journal = PgRunJournal(database, clock)
    val hub = FeedHub()
    val feedStore = ExposedFeedStore(database, clock)
    val feed = FeedService(feedStore, hub)
    val threads = ExposedThreadStore(database, clock)
    val memory = ExposedMemoryStore(database, clock)
    val tasks = ExposedTaskStore(database, clock, feed)
    val runs = ExposedRunStore(database, clock)
    var settings = Settings()
    val settingsProvider = SettingsProvider { settings }

    val events = GovernanceEvents()
    val results = ActionResults()
    val heldStore = HeldActionStore(database, clock)
    val constitutions = ConstitutionStore(database, clock, heldStore, events)
    val outbox = ActionOutbox(database, clock, heldStore, OutboxSignals(), events, results)
    var guardVerdict = GuardVerdict(Decision.ALLOW, emptyList(), "fine")
    val gate = ActionGate(
        Guard(GuardModel { guardVerdict }), constitutions, heldStore, outbox,
        ConnectorRouter(database, NoConnectorPreferences()), events, UtcPersonZone(), clock,
    )

    val mcp = McpToolSources(config)
    val large = LargeResults(database, mcp, clock)
    val own = OwnStateTools(feed, memory, constitutions, tasks, settingsProvider, clock)
    val external = ExternalTools(gate)
    private val reader = ExposedNounReader(database)
    val domain = DomainTools(reader, ExposedTriggerStore(database, clock), DocumentTools(reader) { DocumentFetch.Unavailable("none") })

    val sessionService = SessionService(SessionStore(database), feed, inbox, heldStore, constitutions, clock)
    val dayPlans = DayPlanStore(database, clock)
    val sessionTools = SessionTools(sessionService, feed, dayPlans, tasks, constitutions, settingsProvider, clock)
    val vaultStore = ExposedVaultStore(database, clock)
    val grants = GrantStore(clock)
    val vaultHolds = GovernedVaultHolds(gate, heldStore, clock)
    val vaultService = VaultService(vaultStore, grants, heldStore, events, inbox)
    var triageVerdict = TriageVerdict(true, "ordinary")
    val browserGate = BrowserGate(vaultStore, grants, vaultHolds, { triageVerdict }, clock)

    lateinit var helpers: HelperManager
    val toolbox: AgentToolbox
    val lead: LeadAgent

    init {
        val helperTools = HelperTools(lazy { helpers })
        toolbox = AgentToolbox(domain, own, external, helperTools, large, mcp, sessionTools, VaultTools(vaultService, vaultHolds), browserGate)
        helpers = HelperManager(config, runtime, runs, journal, inbox, lazy { toolbox }, large, clock)
        lead = LeadAgent(
            config, runtime, journal, inbox, signals, threads, constitutions, memory, toolbox, large, feed, settingsProvider, clock,
            CallEffortOverride(sessionService),
        )
    }

    /** Empties every table the lead touches so tests do not see each other. */
    suspend fun reset() {
        database.tx {
            exec(
                "TRUNCATE inbox_item, agent_journal, agent_thread, agent_run, feed_item, held_action, action_outbox, activity, " +
                    "credential_envelope, credential_request, credential, vault_device_key, constitution_version, amendment, memory_journal, memory_scratch, agent_task, scheduled_job, tool_result, message, " +
                    "agent_session, day_plan, settings " +
                    "RESTART IDENTITY CASCADE",
            )
        }
    }
}
