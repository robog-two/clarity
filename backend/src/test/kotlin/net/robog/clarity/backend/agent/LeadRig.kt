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
import net.robog.clarity.backend.governance.MailAccounts
import net.robog.clarity.shared.connector.ConnectorAccount
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
import net.robog.clarity.backend.governance.HeldWaiters
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

/** Claude through OpenRouter, so the lead's requests carry cache breakpoints as in production with Claude. */
const val LEAD_MODEL = "anthropic/claude-opus-5.5"
const val HELPER_MODEL = "anthropic/claude-sonnet-5.5"

/**
 * The sandbox's `fetch_page` as a local tool (no network): each URL fetched gives a page in sandboxd's
 * format, its title and text from [pages] (else made up from the URL). [fetched] lists the URLs asked for.
 */
class FakeFetchPage(private val pages: Map<String, Pair<String, String>> = emptyMap()) : net.robog.clarity.harness.Tool {
    val fetched = CopyOnWriteArrayList<String>()
    override val name = FETCH_PAGE
    override val description = "Open a web page and read it as text."
    override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
    override val effect = net.robog.clarity.harness.ToolEffect.EXTERNAL

    override suspend fun invoke(input: JsonObject): net.robog.clarity.harness.ToolResult {
        val url = (input["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return net.robog.clarity.harness.ToolResult.Error("Missing required string argument \"url\".")
        fetched += url
        val (title, text) = pages[url] ?: ("Page at $url" to "Text of $url.")
        return net.robog.clarity.harness.ToolResult.Ok("Title: $title\nURL: $url\nFetched: 2026-10-29T15:00:00Z\nCharacters 0-${text.length} of ${text.length}\n\n$text")
    }
}

/** The default roles with the lead and helper on [LEAD_MODEL] and [HELPER_MODEL] (the scripted model tells them apart by name). */
val TEST_ROLES: net.robog.clarity.backend.config.AgentRoles = net.robog.clarity.backend.config.AgentRoles().let {
    it.copy(lead = it.lead.copy(model = LEAD_MODEL), helper = it.helper.copy(model = HELPER_MODEL))
}

/** The person's mail accounts in the rig: an email names one of these addresses as its `from`. */
val RIG_MAIL = listOf(ConnectorAccount("iCloud", "me@icloud.example"), ConnectorAccount("Gmail", "me@gmail.example"))

/** Everything the lead needs, wired by hand against a real database (what Metro does in production). */
class LeadRig(
    val database: Database,
    val clock: Clock = Clock.System,
    /** The OpenRouter key; null leaves the agents disabled. */
    apiKey: String? = "sk-or-test",
    roles: net.robog.clarity.backend.config.AgentRoles = TEST_ROLES,
    /** The lead's effort override; null for the real one (a live morning call). */
    effortOverride: EffortOverride? = null,
    /** The embeddings API (a fake); null runs memory search full-text only. */
    embeddingClient: EmbeddingClient? = null,
    /** The failure gate on memory writes and briefs; by default off (no decision model). */
    val failureGate: FailureGate = FailureGate(null),
    /** Where model usage goes; by default nowhere (pass a DbUsageRecorder to fill model_call). */
    usageRecorder: UsageRecorder = UsageRecorder { _, _, _ -> },
    /** The sandbox's tools, served in-process; by default only a fake fetch_page. */
    val sandboxTools: List<net.robog.clarity.harness.Tool> = listOf(FakeFetchPage()),
    /** What one helper run may spend, in dollars. */
    helperMaxCostUsd: Double = 0.15,
    /** The hard spend stops checked before each call; by default none. */
    spendGuard: SpendGuard = SpendGuard.OFF,
    /** Tool results kept for resumes; by default none (pass a PgToolLedger to keep them). */
    ledger: net.robog.clarity.harness.ToolLedger = net.robog.clarity.harness.ToolLedger.NONE,
    /** Further agent settings over the rig's. */
    configure: (AgentConfig) -> AgentConfig = { it },
    /** The person's third-party MCP servers (a fake host); by default none. */
    mcpHost: net.robog.clarity.backend.mcpservers.McpHost = net.robog.clarity.backend.mcpservers.McpHost.None,
    /** How long a helper waits for the person on a held MCP call. */
    mcpWait: Duration = Duration.ZERO,
    /** Tests list the host on every read; false is the production path (only a refresh lists it). */
    mcpRefreshOnRead: Boolean = true,
) {
    /** Answers for every role (tell them apart by the model name). */
    val model = FakeModel()
    val config = configure(AgentConfig(openRouterApiKey = apiKey, reviewInterval = 2.hours, maxConcurrentRuns = 2, roles = roles, helperMaxCostUsd = helperMaxCostUsd))
    val modelSettings = ModelSettings(config, InMemoryRoleOverrideStore(clock))
    val runtime = AgentRuntime(config, modelSettings, ModelGatewayFactory { model }, usageRecorder, spendGuard, ledger)

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

    val mcp = McpToolSources(config, null, sandboxTools)
    val large = LargeResults(database, mcp, clock)
    val own = OwnStateTools(feed, memory, constitutions, tasks, settingsProvider, clock, failureGate)
    val collections = ExposedCollectionStore(database, clock)
    val embeddings = Embeddings(config, { embeddingClient }, database, clock)
    val vectors = VectorIndex(database, embeddings)
    val memorySearch = MemorySearch(database, embeddings, vectors, settingsProvider)
    val embeddingIndexer = EmbeddingIndexer(database, embeddings, vectors)
    val archive = ConversationArchive(database)
    val memoryTools = MemoryTools(memorySearch, collections, archive, constitutions, settingsProvider, failureGate)
    private val reader = ExposedNounReader(database)
    /** The mail accounts an email can go from ([RIG_MAIL]); a test may change them. */
    var mailAccounts = RIG_MAIL
    private val mail = MailAccounts { mailAccounts }
    val external = ExternalTools(gate, mail, reader)
    val domain = DomainTools(reader, ExposedTriggerStore(database, clock), DocumentTools(reader) { DocumentFetch.Unavailable("none") })

    val sessionStore = SessionStore(database)
    val sessionService = SessionService(sessionStore, feed, inbox, heldStore, constitutions, clock)
    val dayPlans = DayPlanStore(database, clock)
    val sessionTools = SessionTools(sessionService, feed, dayPlans, tasks, constitutions, settingsProvider, clock)
    val vaultStore = ExposedVaultStore(database, clock)
    val grants = GrantStore(clock)
    val vaultHolds = GovernedVaultHolds(gate, heldStore, clock, events)
    val vaultService = VaultService(vaultStore, grants, heldStore, events, inbox)
    var triageVerdict = TriageVerdict(true, "ordinary")
    val heldWaiters = HeldWaiters(clock)
    // No wait for the person here: the tests answer held items themselves.
    val browserGate = BrowserGate(vaultStore, grants, vaultHolds, { triageVerdict }, clock, heldWaiters, codeWait = kotlin.time.Duration.parse("1s"), personWait = kotlin.time.Duration.ZERO)
    val browserPilot = BrowserPilot(browserGate, FakeBrowserModels(null, null), MemoryBrowserAudit(), vaultStore, vaultHolds, config, clock, UtcPersonZone())

    val nudges = ExposedNudgeStore(database, clock)
    val nudgeTools = NudgeTools(nudges, tasks, settingsProvider, clock)

    val reportStore = net.robog.clarity.backend.reports.ExposedReportStore(database, clock)
    val reportTools = net.robog.clarity.backend.reports.ReportTools(reportStore, own, journal, settingsProvider, clock)

    val mcpStore = net.robog.clarity.backend.mcpservers.ExposedMcpStore(database)
    val mcpCatalog = net.robog.clarity.backend.mcpservers.McpCatalog(mcpHost, mcpStore, collections, clock, refreshOnRead = mcpRefreshOnRead)
    val mcpCalls = net.robog.clarity.backend.mcpservers.McpCalls(mcpCatalog, gate, heldStore, events, heldWaiters, clock, mcpWait)
    val mcpKit = net.robog.clarity.backend.mcpservers.McpToolKit(mcpCatalog, mcpCalls, memoryTools, memory, mcpStore, failureGate, clock)

    lateinit var helpers: HelperManager
    val toolbox: AgentToolbox
    val lead: LeadAgent

    init {
        val helperTools = HelperTools(lazy { helpers }, tasks, runs, failureGate)
        toolbox = AgentToolbox(domain, own, memoryTools, external, helperTools, large, mcp, sessionTools, VaultTools(vaultService, vaultHolds), browserPilot, nudgeTools, reportTools, mcpKit)
        helpers = HelperManager(config, runtime, runs, journal, inbox, lazy { toolbox }, large, clock)
        lead = LeadAgent(
            config, runtime, journal, inbox, signals, threads, constitutions, memory, collections, toolbox, large, feed, settingsProvider, clock,
            effortOverride ?: CallEffortOverride(sessionService), mail, mcpCatalog,
        )
    }

    /** Empties every table the lead touches so tests do not see each other. */
    suspend fun reset() {
        database.tx {
            exec(
                "TRUNCATE inbox_item, agent_journal, agent_thread, agent_run, feed_item, held_action, action_outbox, activity, " +
                    "credential_envelope, credential_request, credential, vault_device_key, constitution_version, amendment, memory_journal, memory_scratch, memory_note, memory_collection, conversation_chunk, memory_embedding, agent_task, agent_task_run, model_call, scheduled_job, tool_result, message, " +
                    "agent_session, day_plan, settings, nudge, report, report_revision, report_rendition, report_source, report_link, tool_call_ledger, spend_notice, agent_trigger, job_slot_claim, browser_run_state, " +
                    "mcp_tool_policy, mcp_server_seen, mcp_suggestion, mcp_suggestion_made " +
                    "RESTART IDENTITY CASCADE",
            )
        }
    }
}
