package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.harness.CachePolicy
import net.robog.clarity.harness.CompactionConfig
import net.robog.clarity.harness.Harness
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.harness.RunRequest
import net.robog.clarity.harness.SystemBlock
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolPolicy
import net.robog.clarity.harness.ToolResultSink
import net.robog.clarity.shared.governance.Constitution
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The model gateway shared by the lead and the helpers. Built from the configured API key (a Podman
 * secret or environment), or later from the key the person enters in setup ([installKey]), so that
 * agents start without a restart. Null (agents disabled) while there is no key; [awaitEnabled]
 * suspends until there is one. A configured key always wins over one entered at runtime.
 */
@Inject
@SingleIn(AppScope::class)
class AgentRuntime(val config: AgentConfig, private val factory: ModelGatewayFactory) {
    private val configured: Boolean = !config.anthropicApiKey.isNullOrBlank()
    private val current = MutableStateFlow<ModelGateway?>(config.anthropicApiKey?.takeIf { it.isNotBlank() }?.let(factory::create))

    val gateway: ModelGateway? get() = current.value

    val enabled: Boolean get() = gateway != null

    /** True when the key comes from configuration; a key entered in setup is then stored but not used. */
    val keyFromConfig: Boolean get() = configured

    /** Uses [apiKey] from now on, unless a configured key takes precedence. Returns whether it is in use. */
    fun installKey(apiKey: String): Boolean {
        if (configured) return false
        current.value = factory.create(apiKey)
        return true
    }

    /** Suspends until a model key is available. */
    suspend fun awaitEnabled(): ModelGateway = current.filterNotNull().first()

    fun harness(journal: RunJournal): Harness = Harness(requireNotNull(gateway) { "agents are disabled: no API key" }, journal)
}

private const val COMPACTION_INSTRUCTIONS =
    "Summarize the conversation so far for the same agent to continue it: the person's standing preferences and decisions, " +
        "open threads and what each waits on, held items, helpers running, and anything promised. Drop tool output and chatter."

/**
 * Pure: the system prompt blocks of the lead. The static persona block carries a cache breakpoint,
 * the constitution block (which changes only on amendment) another.
 */
fun leadSystemBlocks(constitution: Constitution?): List<SystemBlock> = listOf(
    SystemBlock(AgentPrompts.LEAD_SYSTEM, cacheBreakpoint = true),
    SystemBlock(AgentPrompts.constitutionBlock(constitution), cacheBreakpoint = true),
)

/** Pure: the system blocks of a helper. */
fun helperSystemBlocks(): List<SystemBlock> = listOf(SystemBlock(AgentPrompts.HELPER_SYSTEM, cacheBreakpoint = true))

/**
 * Pure: the harness request for a role. Roles never switch models inside a thread (the cache is
 * per model), so the request is a function of the role's configuration alone.
 */
fun roleRequest(
    role: RoleConfig,
    runId: String,
    system: List<SystemBlock>,
    messages: List<Message>,
    tools: List<Tool>,
    deferred: List<Tool>,
    maxTurns: Int,
    sink: ToolResultSink?,
    compactNow: Boolean = false,
): RunRequest = RunRequest(
    model = role.model,
    system = system,
    messages = messages,
    tools = tools,
    // Every tool is gated at its own door (the action gate, the scope's provenance), so the harness runs them all.
    policy = ToolPolicy.AllowAll,
    maxTurns = maxTurns,
    maxTokens = role.maxTokens,
    effort = role.effort,
    runId = runId,
    deferredTools = deferred,
    cache = CachePolicy(ttl = role.cacheTtl),
    compaction = if (role.compaction) CompactionConfig(instructions = COMPACTION_INSTRUCTIONS) else null,
    resultSink = sink,
    compactNow = compactNow,
)

/** How long after its last turn the lead's cache is still warm enough to compact cheaply. */
fun warmWindow(cacheTtl: String): Duration = if (cacheTtl == "1h") 55.minutes else 4.minutes

/** How long the person must have been quiet before idle compaction. */
val IDLE_BEFORE_COMPACTION: Duration = 3.minutes

/**
 * Pure: compact the lead thread now? When the person has been quiet for 3 minutes, the last
 * lead turn is still inside the warm-cache window (55 min for a 1 h cache, 4 min for 5 min), the
 * thread has not been compacted since that turn, and it is long enough to be worth compacting.
 */
fun shouldCompactNow(
    now: Instant,
    lastUserInput: Instant?,
    lastTurn: Instant?,
    compactedAt: Instant?,
    cacheTtl: String,
    estimatedTokens: Long,
): Boolean {
    if (lastTurn == null) return false
    if (compactedAt != null && compactedAt >= lastTurn) return false
    if (now - (lastUserInput ?: lastTurn) <= IDLE_BEFORE_COMPACTION) return false
    if (now - lastTurn >= warmWindow(cacheTtl)) return false
    return estimatedTokens >= net.robog.clarity.harness.COMPACTION_MIN_TRIGGER_TOKENS
}

/** Pure: a rough token count for a journal (4 characters per token). */
fun estimateTokens(messages: List<Message>): Long =
    messages.sumOf { net.robog.clarity.harness.HarnessJson.encodeToString(Message.serializer(), it).length.toLong() } / 4

/** Which role config a role name from `delegate` maps to; only the cheap roles may be delegated to. */
fun delegableRole(name: String?): AgentRole? = when (name?.lowercase()) {
    null, "", "helper" -> AgentRole.HELPER
    "triage" -> AgentRole.TRIAGE
    else -> null
}
