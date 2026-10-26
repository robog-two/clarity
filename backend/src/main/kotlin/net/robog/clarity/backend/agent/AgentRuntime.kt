package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.ModelProvider
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The model gateways, one per provider, and which role uses which ([bind]). The Anthropic one
 * is built from the configured API key (a Podman secret or environment), or later from the key the
 * person enters in setup ([installKey]), so that agents start without a restart; the OpenRouter one
 * from `OPENROUTER_API_KEY`. Agents are disabled until every provider a role runs on has a key;
 * [awaitEnabled] suspends until then. A configured Anthropic key always wins over one entered at runtime.
 *
 * Which provider and model a role uses comes from [settings] and can change at any time, so every
 * caller binds the role when it calls (or when a run starts), never once at construction.
 */
@Inject
@SingleIn(AppScope::class)
class AgentRuntime(
    val config: AgentConfig,
    val settings: ModelSettings,
    private val factory: ModelGatewayFactory,
    private val usage: UsageRecorder,
) {
    private val configured: Boolean = !config.anthropicApiKey.isNullOrBlank()

    private val gateways = MutableStateFlow(
        buildMap {
            config.anthropicApiKey?.takeIf { it.isNotBlank() }?.let { put(ModelProvider.ANTHROPIC, factory.create(ModelProvider.ANTHROPIC, it)) }
            config.openRouterApiKey?.takeIf { it.isNotBlank() }?.let { put(ModelProvider.OPENROUTER, factory.create(ModelProvider.OPENROUTER, it)) }
        },
    )

    /** The providers that have a key now. */
    val providersWithKeys: Set<ModelProvider> get() = gateways.value.keys

    /**
     * [role]'s current settings and its provider's gateway, which records every call as [role]
     * (`model_call`); null while that provider has no key.
     */
    fun bind(role: AgentRole): BoundRole? {
        val roleConfig = settings[role]
        val gateway = gateways.value[roleConfig.provider] ?: return null
        return BoundRole(role, roleConfig, RecordingGateway(gateway, usage, role))
    }

    /** The gateway of [role]'s current provider, or null while that provider has no key. */
    fun gatewayFor(role: AgentRole): ModelGateway? = bind(role)?.gateway

    /** The providers some role uses now that have no key (empty when agents can run). */
    fun missingKeys(roles: AgentRoles = settings.roles.value): Set<ModelProvider> = roles.providersInUse - gateways.value.keys

    val enabled: Boolean get() = missingKeys().isEmpty()

    /** True when the Anthropic key comes from configuration; a key entered in setup is then stored but not used. */
    val keyFromConfig: Boolean get() = configured

    /** Uses [apiKey] for Anthropic from now on, unless a configured key takes precedence. Returns whether it is in use. */
    fun installKey(apiKey: String): Boolean {
        if (configured) return false
        gateways.update { it + (ModelProvider.ANTHROPIC to factory.create(ModelProvider.ANTHROPIC, apiKey)) }
        return true
    }

    /** Suspends until every provider in use has a key (a key arriving, or a role moving to a provider that has one). */
    suspend fun awaitEnabled() {
        combine(gateways, settings.roles) { available, roles -> roles.providersInUse.all { it in available } }.first { it }
    }

    /** A harness on [role]'s gateway (the lead, or a helper running as `helper` or `triage`), bound now. */
    fun harness(journal: RunJournal, role: AgentRole = AgentRole.LEAD): Harness = harness(journal, bindOrFail(role))

    /** A harness on [bound]'s gateway; the run keeps it to the end. */
    fun harness(journal: RunJournal, bound: BoundRole): Harness = Harness(bound.gateway, journal)

    /** [bind], or fails when [role]'s provider has no key. */
    fun bindOrFail(role: AgentRole): BoundRole =
        requireNotNull(bind(role)) { "agents are disabled: no API key for ${settings[role].provider.wireName}" }
}

private const val COMPACTION_INSTRUCTIONS =
    "Summarize the conversation so far for the same agent to continue it: the person's standing preferences and decisions, " +
        "open threads and what each waits on, held items, helpers running, and anything promised. Drop tool output and chatter."

/**
 * Pure: the system prompt blocks of the lead. The static persona block carries a cache breakpoint,
 * the constitution block (which changes only on amendment) another.
 */
fun leadSystemBlocks(constitution: Constitution?, home: HomeClock? = null): List<SystemBlock> = listOf(
    SystemBlock(AgentPrompts.LEAD_SYSTEM, cacheBreakpoint = true),
    SystemBlock(AgentPrompts.constitutionBlock(constitution, home), cacheBreakpoint = true),
)

/** Pure: the system blocks of a helper. */
fun helperSystemBlocks(): List<SystemBlock> = listOf(SystemBlock(AgentPrompts.HELPER_SYSTEM, cacheBreakpoint = true))

/**
 * Pure: the harness request for a role, a function of the role's configuration alone. The caller
 * passes the settings bound at the start of the run, so a run never switches models halfway.
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
    // Every tool is gated at its own door (the action gate, the browser wrapper), so the harness runs what is visible.
    policy = ToolPolicy.Gated,
    maxTurns = maxTurns,
    maxTokens = role.maxTokens,
    effort = role.effort,
    runId = runId,
    deferredTools = deferred,
    cache = CachePolicy(ttl = role.cacheTtl),
    compaction = if (role.compaction) CompactionConfig(triggerInputTokens = role.compactAtTokens, instructions = COMPACTION_INSTRUCTIONS) else null,
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

/**
 * Pure: the part of [messages] the provider still reads: from the message holding the latest
 * compaction block on (everything before it is dropped server-side), or all of them.
 */
fun sinceLastCompaction(messages: List<Message>): List<Message> {
    val last = messages.indexOfLast { m -> m.content.any { it is net.robog.clarity.harness.Block.Raw && it.isCompaction } }
    return if (last < 0) messages else messages.subList(last, messages.size)
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
