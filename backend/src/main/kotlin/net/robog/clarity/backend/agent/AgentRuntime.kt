package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.awaitCancellation
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.CachePolicy
import net.robog.clarity.harness.Harness
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.harness.RunRequest
import net.robog.clarity.harness.SystemBlock
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolPolicy
import net.robog.clarity.harness.ToolResultSink
import net.robog.clarity.harness.contextSince
import net.robog.clarity.harness.definition
import net.robog.clarity.harness.planTools
import net.robog.clarity.harness.sinceSummary
import net.robog.clarity.harness.usesCacheBreakpoints
import net.robog.clarity.harness.wireOrder
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.session.ModelRoles

/**
 * The model gateway (OpenRouter, the only provider), built once from `OPENROUTER_API_KEY`, and the
 * roles that use it ([bind]). Configuration loading requires the key, so agents are enabled from
 * boot; without one (only when a test builds the config by hand) they stay disabled.
 *
 * Which model a role uses comes from [settings] and can change at any time, so every caller binds
 * the role when it calls (or when a run starts), never once at construction.
 */
@Inject
@SingleIn(AppScope::class)
class AgentRuntime(
    val config: AgentConfig,
    val settings: ModelSettings,
    factory: ModelGatewayFactory,
    private val usage: UsageRecorder,
) {
    private val gateway: ModelGateway? = config.openRouterApiKey?.takeIf(String::isNotBlank)?.let(factory::create)

    /**
     * [role]'s current settings and the gateway, which records every call as [role] (`model_call`);
     * null without a key.
     */
    fun bind(role: AgentRole): BoundRole? {
        val gateway = gateway ?: return null
        return BoundRole(role, settings[role], RecordingGateway(gateway, usage, role))
    }

    /** The gateway, recording calls as [role], or null without a key. */
    fun gatewayFor(role: AgentRole): ModelGateway? = bind(role)?.gateway

    /** The providers without a key: `openrouter` when none was configured, else none. */
    val missingKeys: Set<String> get() = if (gateway == null) setOf(ModelRoles.OPENROUTER) else emptySet()

    val enabled: Boolean get() = gateway != null

    /** Returns once agents can run; without a key that is never (a key only comes with a restart). */
    suspend fun awaitEnabled() {
        if (!enabled) awaitCancellation()
    }

    /** A harness on [role]'s gateway (the lead, or a helper running as `helper` or `triage`), bound now. */
    fun harness(journal: RunJournal, role: AgentRole = AgentRole.LEAD): Harness = harness(journal, bindOrFail(role))

    /** A harness on [bound]'s gateway; the run keeps it to the end. */
    fun harness(journal: RunJournal, bound: BoundRole): Harness = Harness(bound.gateway, journal)

    /** [bind], or fails without a key. */
    fun bindOrFail(role: AgentRole): BoundRole =
        requireNotNull(bind(role)) { "agents are disabled: no OpenRouter API key (OPENROUTER_API_KEY)" }
}

const val COMPACTION_INSTRUCTIONS =
    "Summarize the conversation so far for the same agent to continue it: the person's standing preferences and decisions, " +
        "open threads and what each waits on, held items, helpers running, and anything promised. Drop tool output and chatter."

/** The last user message of a client-side compaction call. The tools are still sent (the history calls them), so it says not to. */
const val CLIENT_COMPACTION_REQUEST =
    "(housekeeping) $COMPACTION_INSTRUCTIONS Answer with the summary only, as plain text. Do not call any tools."

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
 * Pure: the cache policy of [role]: breakpoints with the role's TTL for a model that caches only at
 * explicit breakpoints (Claude, [usesCacheBreakpoints]); none for the others, which cache on their
 * own (so there is no cache-miss check for them either).
 */
fun cachePolicy(role: RoleConfig): CachePolicy? = if (usesCacheBreakpoints(role.model)) CachePolicy(ttl = role.cacheTtl) else null

/**
 * Pure: the harness request for a role, a function of the role's configuration alone. The caller
 * passes the settings bound at the start of the run, so a run never switches models halfway. A long
 * thread compacts client-side ([shouldCompactClientSide]).
 */
fun roleRequest(
    role: RoleConfig,
    runId: String,
    system: List<SystemBlock>,
    messages: List<Message>,
    tools: List<Tool>,
    maxTurns: Int,
    sink: ToolResultSink?,
): RunRequest {
    val cache = cachePolicy(role)
    return RunRequest(
        model = role.model,
        system = if (cache == null) system.map { it.copy(cacheBreakpoint = false) } else system,
        messages = messages,
        tools = tools,
        // Every tool is gated at its own door (the action gate, the browser wrapper), so the harness runs what is visible.
        policy = ToolPolicy.Gated,
        maxTurns = maxTurns,
        maxTokens = role.maxTokens,
        effort = role.effort,
        runId = runId,
        cache = cache,
        resultSink = sink,
    )
}

/**
 * Pure: the part of [messages] the model still reads: from the latest compaction point on (a client
 * summary, or a stored Anthropic compaction block), or all of them. See [contextSince].
 */
fun sinceLastCompaction(messages: List<Message>): List<Message> = contextSince(messages)

/**
 * Pure: compact the thread client-side now? Only for a role with compaction on, only at a turn
 * boundary (the thread ends in an assistant turn without open tool calls), and once the prompt of
 * the last call ([lastPromptTokens], from usage; estimated from the journal when unknown) reached
 * [RoleConfig.compactAtTokens].
 */
fun shouldCompactClientSide(role: RoleConfig, lastPromptTokens: Long?, history: List<Message>): Boolean {
    if (!role.compaction) return false
    val last = history.lastOrNull() ?: return false
    if (last.role != Role.ASSISTANT || last.content.any { it is Block.ToolUse }) return false
    return (lastPromptTokens ?: estimateTokens(contextSince(history))) >= role.compactAtTokens
}

/**
 * Pure: the one call that writes a client-side summary, on the same model as the thread: the
 * context since the last compaction point, the same tools (the history calls them) and system
 * prompt, then [CLIENT_COMPACTION_REQUEST]. Its text goes into the journal as a [Message.summary].
 * On a model with breakpoints it reads the thread's cached prefix (same system blocks and tools).
 */
fun clientCompactionRequest(role: RoleConfig, system: List<SystemBlock>, history: List<Message>, tools: List<Tool>): ModelRequest {
    val plan = planTools(tools, ToolPolicy.Gated)
    val cache = cachePolicy(role)
    return ModelRequest(
        model = role.model,
        system = if (cache == null) system.map { it.copy(cacheBreakpoint = false) } else system,
        messages = wireOrder(sinceSummary(history)) + Message.user(CLIENT_COMPACTION_REQUEST),
        tools = plan.tools.map { it.definition },
        maxTokens = role.maxTokens,
        effort = role.effort,
        cache = cache,
    )
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
