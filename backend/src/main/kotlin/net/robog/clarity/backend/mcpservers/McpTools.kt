package net.robog.clarity.backend.mcpservers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.backend.agent.FailureGate
import net.robog.clarity.backend.agent.GateKind
import net.robog.clarity.backend.agent.HelperManager
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.MemoryStore
import net.robog.clarity.backend.agent.MemoryTools
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.SecretDetector
import net.robog.clarity.backend.agent.UntrustedPart
import net.robog.clarity.backend.agent.quoteUntrusted
import net.robog.clarity.backend.agent.taintingScope
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.HeldWaiters
import net.robog.clarity.backend.governance.LocalOutcome
import net.robog.clarity.backend.governance.MCP_SERVER_PREFIX
import net.robog.clarity.backend.governance.isMcpCall
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.mcp.McpToolMode

private val log = KotlinLogging.logger {}

/** The profile of the morning sweep's helper run ([net.robog.clarity.backend.agent.HelperRun.profile]). */
const val MCP_SWEEP_PROFILE = "mcp-sweep"

/** What one sweep may spend, in dollars, and how many tool calls it may make (its scratch note aside). */
const val MCP_SWEEP_MAX_COST_USD = 0.03
const val MCP_SWEEP_MAX_TOOL_CALLS = 12

/** Model calls the sweep gets: its tool calls, a few at a time, and the last word. */
const val MCP_SWEEP_MAX_TURNS = 16

/** The scratch key the sweep writes; the call prep reads it. */
const val COMING_UP_KEY = "coming-up"
val COMING_UP_TTL: Duration = 20.hours
const val COMING_UP_MAX_CHARS = 1200

/** How the idempotency key of an MCP call separates the run that asked from the rest. */
private const val KEY_INFIX = ":mcp:"

/** Pure: the run that proposed an MCP call, from its idempotency key. */
fun runOfMcpCall(idempotencyKey: String): String? = idempotencyKey.substringBefore(KEY_INFIX, "").ifEmpty { null }

/** How long a helper waits for the person to answer a held call before it carries on without it. */
val MCP_WAIT_FOR_PERSON: Duration = 15.minutes

/** How long the person's yes to a call waits for the run that asked to take it. */
private val APPROVAL_LASTS = 30.minutes

private val RECENT_DECLINE = 10.minutes

private val DECISION_POLL = 3.seconds

private const val MAX_DESCRIPTION = 1000

/** Pure: one input value as the held card shows it. */
private fun shown(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.content.replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
    is JsonArray -> "${value.size} item${if (value.size == 1) "" else "s"}"
    is JsonObject -> "{…}"
    JsonNull -> "null"
}

/**
 * Pure: the held card's summary of a call: the server, the tool and its key inputs, e.g.
 * `GitHub: create_issue (owner: robog, repo: clarity, title: Fix the login)`. Plain values come
 * first; at most four are named.
 */
fun mcpCallSummary(label: String, tool: String, input: JsonObject): String {
    val keys = input.entries.sortedBy { if (it.value is JsonPrimitive) 0 else 1 }.take(4)
    val args = keys.joinToString(", ") { (k, v) -> "$k: ${shown(v)}" }
    val more = if (input.size > keys.size) ", …" else ""
    return "$label: $tool" + if (args.isEmpty()) "" else " ($args$more)"
}

/** Pure: a server's answer quoted as untrusted, so nothing in it can pass for an instruction. */
fun quotedResult(label: String, result: ToolResult): ToolResult = when (result) {
    is ToolResult.Ok -> ToolResult.Ok(quoteUntrusted(UntrustedPart("mcp:$label", result.content)))
    is ToolResult.Error -> ToolResult.Error(quoteUntrusted(UntrustedPart("mcp:$label", result.message)))
}

/** What came of proposing a held call. */
private sealed interface Proposal {
    /** An earlier yes to exactly this call, now used up: run it. */
    data object Run : Proposal

    /** Waiting for the person on [heldId]. */
    data class Wait(val heldId: HeldActionId, val text: String) : Proposal

    /** Blocked or declined: tell the agent [text]. */
    data class Stop(val text: String) : Proposal
}

/**
 * Calls to the person's servers. An `ask` tool's call becomes a held [ProposedAction.ExternalTool]
 * (`server = "mcp:<id>"`), always held whatever the guard would allow alone; the helper waits for
 * the decision (up to [personWait]) and runs the call itself on a yes. A yes that comes later is run
 * by [McpApprovals]. Each yes is one call: it is used up when the call runs ([HeldAction.Status.EXECUTED]).
 */
@Inject
@SingleIn(AppScope::class)
class McpCalls(
    private val catalog: McpCatalog,
    private val gate: ActionGate,
    private val held: HeldActionStore,
    private val events: GovernanceEvents,
    private val waiters: HeldWaiters,
    private val clock: Clock,
    private val personWait: Duration = MCP_WAIT_FOR_PERSON,
) {
    /** Held calls a helper is waiting on right now; [McpApprovals] leaves those to it. */
    private val awaited = ConcurrentHashMap.newKeySet<String>()

    /** One proposal at a time: parallel identical calls must not ask twice. */
    private val proposing = Mutex()

    fun isAwaited(id: HeldActionId): Boolean = id.value in awaited

    /** Runs an `auto` tool. */
    suspend fun run(server: McpServer, tool: String, input: JsonObject): ToolResult =
        quotedResult(server.label, catalog.call(server.id, tool, input))

    /** Runs an `ask` tool: held for the person, then run once on their yes. */
    suspend fun ask(scope: RunScope, server: McpServer, tool: HostTool, input: JsonObject): ToolResult {
        if (scope.isLead) return ToolResult.Error("The lead never calls MCP tools: delegate to a helper with servers=[\"${server.label}\"].")
        val action = ProposedAction.ExternalTool(
            idempotencyKey = "${scope.runId}$KEY_INFIX${Uuid.random()}",
            summary = mcpCallSummary(server.label, tool.name, input),
            server = MCP_SERVER_PREFIX + server.id,
            tool = tool.name,
            input = input,
        )
        return when (val proposal = proposing.withLock { propose(scope, server, tool, action) }) {
            Proposal.Run -> run(server, tool.name, input)
            is Proposal.Stop -> ToolResult.Ok(proposal.text)
            is Proposal.Wait -> await(scope, server, proposal)
        }
    }

    private suspend fun propose(scope: RunScope, server: McpServer, tool: HostTool, action: ProposedAction.ExternalTool): Proposal {
        val same = { a: ProposedAction ->
            a is ProposedAction.ExternalTool && a.server == action.server && a.tool == action.tool && a.input == action.input &&
                runOfMcpCall(a.idempotencyKey) == scope.runId
        }
        val now = clock.now()
        held.list(setOf(HeldAction.Status.APPROVED), limit = 200)
            .firstOrNull { same(it.action) && (it.decidedAt ?: now) > now - APPROVAL_LASTS && it.id.value !in awaited }
            ?.let { if (held.transition(it.id, setOf(HeldAction.Status.APPROVED), HeldAction.Status.EXECUTED)) return Proposal.Run }
        held.list(setOf(HeldAction.Status.HELD, HeldAction.Status.BLOCKED), limit = 200).firstOrNull { same(it.action) }?.let {
            return if (it.status == HeldAction.Status.BLOCKED) Proposal.Stop("Blocked earlier: ${it.verdict.rationale} Do not retry; report what you could not do.")
            else Proposal.Wait(it.id, "Already waiting for the person to approve this call.")
        }
        held.list(setOf(HeldAction.Status.DECLINED), limit = 200).firstOrNull { same(it.action) && (it.decidedAt ?: now) > now - RECENT_DECLINE }?.let {
            return Proposal.Stop("The person declined this call. Do not try it again; report what you could not do.")
        }
        val context = listOf(
            ContextItem("server", server.label),
            ContextItem("tool", listOfNotNull(tool.title ?: tool.name, firstSentence(tool.description).ifEmpty { null }).joinToString(" · ")),
            ContextItem("input", ClarityJson.encodeToString(JsonObject.serializer(), action.input).let { if (it.length > 600) it.take(599) + "…" else it }),
        )
        return when (
            val outcome = gate.gateLocal(
                action,
                agentView = "I want to call ${tool.name} on ${server.label}.",
                context = context,
                provenance = scope.provenance(),
                urgency = Urgency.URGENT,
                alwaysAsk = "I'm asking because you approve every call of ${tool.name} on ${server.label} (Settings → MCP servers can set it to Auto).",
            )
        ) {
            // Never alone for an ask tool; kept for completeness.
            is LocalOutcome.Cleared -> Proposal.Run
            is LocalOutcome.Held -> Proposal.Wait(outcome.heldId, outcome.text)
            is LocalOutcome.Blocked -> Proposal.Stop("${outcome.text} Do not retry; report what you could not do.")
        }
    }

    private suspend fun await(scope: RunScope, server: McpServer, wait: Proposal.Wait): ToolResult {
        val id = wait.heldId
        awaited += id.value
        waiters.waiting(id, scope.runId)
        val status = try {
            awaitDecision(id, personWait)
        } finally {
            waiters.resumed(id)
            awaited -= id.value
        }
        // A yes that landed as the wait ended is still taken here.
        return when (status ?: held.get(id)?.status) {
            HeldAction.Status.APPROVED ->
                if (held.transition(id, setOf(HeldAction.Status.APPROVED), HeldAction.Status.EXECUTED)) {
                    val action = held.get(id)?.action as? ProposedAction.ExternalTool
                    if (action == null) ToolResult.Error("The approved call ${id.value} is gone.")
                    else when (val result = run(server, action.tool, action.input)) {
                        is ToolResult.Ok -> ToolResult.Ok("The person approved it. Result:\n${result.content}")
                        is ToolResult.Error -> ToolResult.Error("The person approved it, but the call failed: ${result.message}")
                    }
                } else ranElsewhere(id)
            HeldAction.Status.EXECUTED -> ranElsewhere(id)
            HeldAction.Status.HELD, null -> ToolResult.Ok(
                "${wait.text}\nI waited ${personWait.inWholeMinutes} minutes and the person has not answered \"${id.value}\" yet. " +
                    "If they approve it later, Clarity runs the call and the result reaches you or the lead. Carry on without it; do not ask again.",
            )
            else -> ToolResult.Ok("The person did not approve this call. Do not try it again; report what you could not do.")
        }
    }

    private fun ranElsewhere(id: HeldActionId) =
        ToolResult.Ok("The person approved it and Clarity already ran the call (${id.value}); its result reaches you or the lead separately.")

    private suspend fun awaitDecision(id: HeldActionId, timeout: Duration): HeldAction.Status? = withTimeoutOrNull(timeout) {
        suspend fun now() = held.get(id)?.status ?: HeldAction.Status.EXPIRED
        var status = now()
        while (status == HeldAction.Status.HELD) {
            // Woken by the decision event; the poll is the fallback if an event was dropped.
            withTimeoutOrNull(DECISION_POLL) { events.events.first { it is GovernanceEvent.HeldChanged && it.held.id == id } }
            status = now()
        }
        status
    }

    /**
     * Runs an approved call nobody is waiting for, once: null when it was already taken (or is not an
     * MCP call). The label names the server in the quote.
     */
    suspend fun runApproved(h: HeldAction): Pair<String, ToolResult>? {
        val action = h.action as? ProposedAction.ExternalTool ?: return null
        if (!isMcpCall(action)) return null
        if (!held.transition(h.id, setOf(HeldAction.Status.APPROVED), HeldAction.Status.EXECUTED)) return null
        val serverId = action.server.removePrefix(MCP_SERVER_PREFIX)
        val label = catalog.servers().firstOrNull { it.id == serverId }?.label ?: serverId
        return label to quotedResult(label, catalog.call(serverId, action.tool, action.input))
    }
}

/**
 * Approved MCP calls whose helper stopped waiting: the backend runs each once and sends the result to
 * that helper if it is still running, else to the lead's inbox.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class McpApprovals(
    private val events: GovernanceEvents,
    private val calls: McpCalls,
    private val helpers: Lazy<HelperManager>,
    private val inbox: InboxStore,
) : BackgroundService {
    override val name = "mcp-approvals"

    override suspend fun run() = coroutineScope {
        events.events.collect { e ->
            val h = (e as? GovernanceEvent.HeldChanged)?.held ?: return@collect
            if (h.status != HeldAction.Status.APPROVED || !isMcpCall(h.action) || calls.isAwaited(h.id)) return@collect
            launch { deliver(h) }
        }
    }

    /** Runs [h] and reports it; one approval at most once. */
    suspend fun deliver(h: HeldAction) {
        try {
            val (label, result) = calls.runApproved(h) ?: return
            val ok = result is ToolResult.Ok
            val text = when (result) {
                is ToolResult.Ok -> result.content
                is ToolResult.Error -> result.message
            }
            val runId = runOfMcpCall(h.action.idempotencyKey)
            val toHelper = runId?.let {
                helpers.value.message(it, "The person approved \"${h.action.summary}\" (${h.id.value}) and Clarity ran it. " +
                    (if (ok) "Result:" else "It failed:") + "\n$text").isRight()
            } ?: false
            if (!toHelper) inbox.enqueue(InboxItem.McpCallResult(h.id.value, label, h.action.summary, ok, text), dedupKey = "mcp:${h.id.value}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "approved MCP call ${h.id.value} could not be run or reported" }
        }
    }
}

/** A tool-call allowance shared by a run's tools. */
class CallBudget(private val max: Int, used: Int = 0) {
    private val count = AtomicInteger(used)

    fun take(): Boolean = count.incrementAndGet() <= max
}

/** [this] counting against [budget]; once it is spent the call does not run and the agent is told [spent]. */
fun Tool.budgeted(budget: CallBudget, spent: String): Tool {
    val inner = this
    return object : Tool by inner {
        override suspend fun invoke(input: JsonObject): ToolResult = if (budget.take()) inner.invoke(input) else ToolResult.Error(spent)
    }
}

/** Pure: whether every line of [text] starts with one of [labels] (a leading "- " allowed); the first that does not, else null. */
fun lineWithoutServer(text: String, labels: List<String>): String? =
    text.lineSequence().map { it.trim().removePrefix("- ").removePrefix("• ").trim() }.filter { it.isNotEmpty() }
        .firstOrNull { line -> labels.none { line.startsWith(it, ignoreCase = true) } }

/** Pure: why a suggestion is refused, or null: it is set up already, one is open, or three were made this week. */
fun suggestionRefusal(label: String, configured: List<String>, open: List<String>, madeThisWeek: Int): String? = when {
    configured.any { labelKey(it) == labelKey(label) } -> "${label.trim()} is set up already; delegate to a helper with servers=[\"${label.trim()}\"]."
    open.any { labelKey(it) == labelKey(label) } -> "${label.trim()} is already suggested; the person sees it in Settings."
    madeThisWeek >= MAX_SUGGESTIONS_PER_WEEK -> "Not saved: $madeThisWeek servers were suggested in the last 7 days, the limit."
    else -> null
}

const val MAX_SUGGESTIONS_PER_WEEK = 3

/**
 * The tools that reach the person's servers: per server its usable tools (`auto` ones run, `ask`
 * ones are held; `off` ones are never built), the server's notes, the sweep's scratch note and the
 * lead's `suggest_mcp_server`. Every server result taints the run.
 */
@Inject
@SingleIn(AppScope::class)
class McpToolKit(
    private val catalog: McpCatalog,
    private val calls: McpCalls,
    private val memoryTools: MemoryTools,
    private val memory: MemoryStore,
    private val store: McpStore,
    private val gate: FailureGate,
    private val clock: Clock,
) {
    /** The servers with these ids, as listed now (a server gone since is left out). */
    suspend fun byIds(ids: List<String>): List<McpServer> = catalog.servers().filter { it.id in ids && it.enabled }

    /** Servers named by the lead, or why one cannot be used. */
    suspend fun resolve(labels: List<String>): Pair<List<McpServer>, String?> = catalog.resolve(labels)

    /** Enabled servers that have a tool which runs without asking (what the sweep can use). */
    suspend fun sweepable(): List<McpServer> = catalog.servers().filter { s -> s.enabled && s.tools.any { it.mode == McpToolMode.AUTO } }

    /** The tools of [servers] for [scope]: `off` never, `ask` only when not [autoOnly]. */
    fun serverTools(scope: RunScope, servers: List<McpServer>, autoOnly: Boolean = false): List<Tool> =
        servers.flatMap { server ->
            server.usable.filter { !autoOnly || it.mode == McpToolMode.AUTO }.map { ServerTool(scope, server, it) }
        }.distinctBy { it.name }

    /** What a helper given [servers] keeps notes with: reading, search, and `save_note` limited to the servers' collections. */
    fun noteTools(scope: RunScope, servers: List<McpServer>): List<Tool> = listOf(
        memoryTools.openCollection(),
        memoryTools.searchMemory().taintingScope(scope),
        memoryTools.saveNoteIn(servers.associate { collectionOf(it) to serverCollectionDescription(it.label) }),
    )

    /** The morning sweep's tools: `auto` tools only, the servers' notes, all within [MCP_SWEEP_MAX_TOOL_CALLS], and its scratch note. */
    fun sweepTools(scope: RunScope, servers: List<McpServer>, used: Int): List<Tool> {
        val budget = CallBudget(MCP_SWEEP_MAX_TOOL_CALLS, used)
        val spent = "You have used your $MCP_SWEEP_MAX_TOOL_CALLS tool calls. Write what you found now with set_scratch, then finish."
        val reads = serverTools(scope, servers, autoOnly = true) + listOf(
            memoryTools.openCollection(),
            memoryTools.saveNoteIn(servers.associate { collectionOf(it) to serverCollectionDescription(it.label) }),
        )
        return reads.map { it.budgeted(budget, spent) } + comingUp(servers)
    }

    @Serializable
    class ComingUpInput(
        @Description("One line per item, each starting with its server's name, e.g. \"Canvas: Essay 2 due Thu 23:59 (ENGL 201)\". At most 1,200 characters.")
        val text: String,
    )

    /** The sweep's `set_scratch`: the `coming-up` note, replacing the last one, for [COMING_UP_TTL]. */
    fun comingUp(servers: List<McpServer>): Tool = tool<ComingUpInput>(
        name = "set_scratch",
        description = "Save what is coming up as the note the lead reads before the morning call (it replaces the previous one and lasts 20 hours). Call it once, at the end.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val text = input.text.trim()
        val labels = servers.map { it.label }
        when {
            text.isEmpty() -> ToolResult.Error("`text` must not be empty.")
            text.length > COMING_UP_MAX_CHARS -> ToolResult.Error("At most $COMING_UP_MAX_CHARS characters (this is ${text.length}): keep the most pressing items.")
            else -> {
                lineWithoutServer(text, labels)?.let {
                    return@tool ToolResult.Error("Start every line with its server's name (${labels.joinToString()}): \"${it.take(80)}\".")
                }
                SecretDetector.reason(text)?.let { return@tool ToolResult.Error("Not saved: it looks like $it. Never write passwords, codes, keys or tokens.") }
                gate.check(GateKind.MEMORY, "set_scratch", text)?.let { return@tool ToolResult.Error(it) }
                memory.setScratch(text, COMING_UP_TTL, COMING_UP_KEY)
                ToolResult.Ok("Saved as the coming-up note for the next ${COMING_UP_TTL.inWholeHours} hours.")
            }
        }
    }

    @Serializable
    class SuggestInput(
        @Description("The service, e.g. \"Canvas\".") val label: String,
        @Description("One or two sentences for the person: what it would let you do for them.") val why: String,
        @Description("The title of a preset in the MCP servers form, when one fits; else omit.") val preset: String? = null,
    )

    /** The lead's `suggest_mcp_server`: stored for Settings, never posted to the chat. */
    fun suggestTool(): Tool = tool<SuggestInput>(
        name = "suggest_mcp_server",
        description = "Suggest an MCP server the person could add, when one would clearly help with something they keep needing (their courses, issues, tasks). " +
            "It appears only in their Settings under MCP servers, never in the chat. At most one per service and three a week.",
        effect = ToolEffect.WRITE,
        json = ClarityJson,
    ) { input ->
        val label = input.label.trim().replace(Regex("\\s+"), " ")
        val why = input.why.trim()
        when {
            label.isEmpty() || label.length > 60 -> ToolResult.Error("`label` must be 1-60 characters.")
            why.isEmpty() || why.length > 400 -> ToolResult.Error("`why` must be 1-400 characters.")
            else -> {
                val now = clock.now()
                val refusal = suggestionRefusal(
                    label,
                    configured = catalog.servers().map { it.label },
                    open = store.suggestions().map { it.label },
                    madeThisWeek = store.suggestionsMadeSince(now - 7.days),
                )
                if (refusal != null) return@tool ToolResult.Error(refusal)
                val saved = store.addSuggestion(label, why, input.preset?.trim()?.ifEmpty { null }, now)
                    ?: return@tool ToolResult.Error("$label is already suggested; the person sees it in Settings.")
                ToolResult.Ok("Suggested $label; it waits in the person's Settings (${saved.id}). Do not mention it unless they ask.")
            }
        }
    }

    private fun collectionOf(server: McpServer): String = server.collection ?: serverCollectionName(server.host)

    private inner class ServerTool(private val scope: RunScope, private val server: McpServer, private val view: McpToolView) : Tool {
        private val ask = view.mode == McpToolMode.ASK
        override val name = mcpToolName(server.label, view.tool.name)
        override val description: String = buildString {
            append('[').append(server.label).append("] ")
            val own = view.tool.description.ifBlank { view.tool.title ?: view.tool.name }
            append(if (own.length > MAX_DESCRIPTION) own.take(MAX_DESCRIPTION - 1).trimEnd() + "…" else own)
            if (ask) append(" Each call waits for the person's approval; once they approve, it runs and you get its result.")
        }
        override val inputSchema: JsonObject = view.tool.inputSchema
        override val effect = if (view.tool.readOnly) ToolEffect.READ else ToolEffect.EXTERNAL

        override suspend fun invoke(input: JsonObject): ToolResult {
            val result = if (ask) calls.ask(scope, server, view.tool, input) else calls.run(server, view.tool.name, input)
            // Third-party output, like a web page.
            scope.taint()
            return result
        }
    }
}
