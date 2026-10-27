package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.OutOfSteps
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.shared.time.LocalTimes
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val log = KotlinLogging.logger {}

/** The task text a helper starts from: the current time on the person's clock ([zone]), then the task. Pure. */
fun helperPrompt(task: String, now: kotlin.time.Instant, zone: kotlinx.datetime.TimeZone): String =
    "Current time: ${LocalTimes.now(now, zone)} (the person's local time; every time you see and give is on this clock)" +
        "\n\nTask from the lead agent:\n$task"

/** Appended once when a helper finished with no result text (its plain final text IS its result). */
const val HELPER_RESULT_NUDGE: String =
    "You finished without a result. Write your result now as plain text: what you found or did, short and specific."

/** Pure: a helper that finished on its own but left no final text gets one more turn to write it. */
fun helperOwesResult(event: HarnessEvent.Terminal?): Boolean =
    event is HarnessEvent.Finished && event.reason == FinishReason.END_TURN && event.result.finalText.isBlank()

/** Pure: what a finished helper reports back to the lead. */
fun helperOutcome(event: HarnessEvent.Terminal?): Pair<Boolean, String> = when (event) {
    is HarnessEvent.Finished -> when (event.reason) {
        FinishReason.END_TURN -> true to event.result.finalText.ifBlank { "(the helper finished without a result)" }
        FinishReason.MAX_TURNS -> false to "The helper ran out of steps. Its report: ${event.result.finalText.ifBlank { "(none)" }}"
        FinishReason.MAX_TOKENS -> false to "The helper's answer was cut off: ${event.result.finalText.ifBlank { "(none)" }}"
        FinishReason.REFUSAL -> false to "The model declined to do this task."
        FinishReason.OTHER -> false to "The helper stopped unexpectedly. Last words: ${event.result.finalText.ifBlank { "(none)" }}"
    }
    is HarnessEvent.Failed -> false to "The helper failed: ${event.message}"
    null -> false to "The helper ended without a result."
}

/**
 * Helpers (subagents): separate harness runs with the `helper` role, their own journal and a
 * restricted tool set. A run is a row in `agent_run`; its result returns to the lead as an inbox
 * item. Durable: runs that were open at shutdown resume from their journal at boot. At most
 * `maxConcurrentRuns` execute at once; the rest wait queued.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class HelperManager(
    private val config: AgentConfig,
    private val runtime: AgentRuntime,
    private val runs: RunStore,
    private val journal: RunJournal,
    private val inbox: InboxStore,
    private val toolbox: Lazy<AgentToolbox>,
    private val large: LargeResults,
    private val clock: Clock,
    private val settings: SettingsProvider = SettingsProvider { net.robog.clarity.shared.session.Settings() },
    /** Scrubs released secret values out of the saved transcript. */
    private val grants: GrantStore = GrantStore(clock),
) : BackgroundService {
    override val name = "helpers"

    private val scope = CompletableDeferred<CoroutineScope>()
    private val permits = Semaphore(config.maxConcurrentRuns.coerceAtLeast(1))
    private val jobs = ConcurrentHashMap<String, Job>()
    private val live = ConcurrentHashMap<String, Channel<List<Message>>>()

    override suspend fun run() {
        runtime.awaitEnabled()
        coroutineScope {
            scope.complete(this)
            runs.open().forEach { start(it) }
            awaitCancellation()
        }
    }

    /** Starts a helper for [task]; returns what to tell the lead, or the reason it could not start. */
    suspend fun delegate(task: String, tools: List<String>?, role: String?, parent: RunScope): Either<String, String> =
        start(task, tools, role, parent).map { it.text }

    /** Starts a helper for [task]; returns its id and what to tell the lead, or the reason it could not start. */
    suspend fun start(task: String, tools: List<String>?, role: String?, parent: RunScope): Either<String, Delegated> {
        if (!runtime.enabled) return "Helpers are unavailable: no model key is configured.".left()
        val agentRole = delegableRole(role) ?: return "Unknown role \"$role\"; use helper or triage.".left()
        val probe = RunScope("probe", isLead = false)
        val chosen = if (tools == null) null else {
            val defaults = toolbox.value.helperTools(probe, null).tools
            completeHelperTools(tools, defaults.tools.map { it.name }.toSet()).fold({ return it.left() }, { it })
        }
        val granted = toolbox.value.helperTools(probe, chosen)
        if (granted.unknown.isNotEmpty()) return "Unknown or not allowed for helpers: ${granted.unknown.joinToString()}.".left()
        val id = Uuid.random().toString()
        val roleName = agentRole.name.lowercase()
        runs.create(id, parent.runId.takeIf { runCatching { Uuid.parse(it) }.isSuccess }, roleName, task, chosen, clock.now())
        // The helper inherits the taint of the context that wrote its task.
        taint[id] = parent.untrusted
        start(requireNotNull(runs.get(id)))
        return Delegated(id, delegatedText(id, roleName, chosen, granted.tools.tools.map { it.name })).right()
    }

    private val taint = ConcurrentHashMap<String, Boolean>()

    suspend fun list(openOnly: Boolean): List<HelperRun> = runs.list(openOnly)

    suspend fun message(id: String, text: String): Either<String, String> {
        val channel = live[id] ?: return "Helper $id is not running.".left()
        channel.send(listOf(Message.user(text)))
        return "Sent. The helper will read it before its next step.".right()
    }

    suspend fun cancel(id: String): Either<String, String> {
        val run = runs.get(id) ?: return "No helper with id $id.".left()
        if (run.status !in RunStatus.OPEN) return "Helper $id already ${run.status}.".left()
        runs.finish(id, RunStatus.CANCELLED, null, null, "cancelled by the lead", clock.now())
        jobs.remove(id)?.cancel()
        return "Helper $id cancelled.".right()
    }

    /** Waits for every running helper to finish (tests). */
    internal suspend fun joinAll() = jobs.values.toList().forEach { it.join() }

    private suspend fun start(run: HelperRun) {
        val parent = scope.await()
        if (jobs.containsKey(run.id)) return
        jobs[run.id] = parent.launch {
            try {
                permits.withPermit { execute(run) }
            } finally {
                jobs.remove(run.id)
                live.remove(run.id)
            }
        }
    }

    private suspend fun execute(run: HelperRun) {
        val agentRole = delegableRole(run.role) ?: net.robog.clarity.backend.config.AgentRole.HELPER
        val interrupts = Channel<List<Message>>(Channel.UNLIMITED)
        live[run.id] = interrupts
        var terminal: HarnessEvent.Terminal? = null
        try {
            runs.markRunning(run.id)
            // Bound once: the run keeps its model to the end, whatever changes in settings meanwhile.
            val bound = runtime.bindOrFail(agentRole)
            val runScope = RunScope(run.id, isLead = false, untrusted = taint[run.id] ?: true, purpose = run.task)
            val set = toolbox.value.helperTools(runScope, run.tools).tools
            val request = roleRequest(
                role = bound.config,
                runId = run.id,
                system = helperSystemBlocks(),
                messages = listOf(Message.user(helperPrompt(run.task, clock.now(), settings.homeZone()))),
                tools = set.tools,
                maxTurns = config.helperMaxTurns,
                sink = large.sink,
            ).copy(outOfSteps = OutOfSteps())
            val harness = runtime.harness(journal, bound)
            harness.resume(run.id, request, interrupts.receiveAsFlow()).collect { event ->
                if (event is HarnessEvent.Terminal) terminal = event
            }
            // Open models sometimes end on a tool round with no text: one bounded nudge, never more.
            if (helperOwesResult(terminal)) {
                journal.append(run.id, Message.user(HELPER_RESULT_NUDGE))
                harness.resume(run.id, request, interrupts.receiveAsFlow()).collect { event ->
                    if (event is HarnessEvent.Terminal) terminal = event
                }
            }
        } catch (e: CancellationException) {
            throw e // cancel_helper already recorded its status; a shutdown leaves the run open to resume at boot
        } catch (e: Exception) {
            log.error(e) { "helper ${run.id} crashed" }
        }
        val (ok, text) = helperOutcome(terminal)
        withContext(NonCancellable) {
            val now = clock.now()
            val current = runs.get(run.id)
            if (current != null && current.status in RunStatus.OPEN) {
                // A copy of the journal for diagnosis; only placeholders, never a released value.
                runCatching { runs.saveTranscript(run.id, helperTranscript(journal.load(run.id), grants::scrub)) }
                    .onFailure { log.warn { "helper ${run.id}: transcript not saved (${it::class.simpleName})" } }
                runs.finish(run.id, if (ok) RunStatus.DONE else RunStatus.FAILED, text, terminal?.usage, if (ok) null else text, now)
                inbox.enqueue(InboxItem.HelperResult(run.id, run.task, ok, text), dedupKey = "helper:${run.id}")
            }
        }
    }
}

/** A helper that started: its run id and what `delegate` tells the lead. */
data class Delegated(val id: String, val text: String)

/** Pure: what `delegate` tells the lead, naming the tools when it picked them, so a missing browser is plain. */
fun delegatedText(id: String, role: String, requested: List<String>?, granted: List<String>): String = buildString {
    append("Helper ").append(id).append(" started (").append(role).append("). Its result will arrive in your inbox.")
    if (requested != null) {
        append(" It has only: ").append(granted.sorted().joinToString().ifEmpty { "no tools" }).append('.')
        if (granted.none { it.startsWith(BrowserGate.PREFIX) }) append(" It has no browser; omit `tools` to give it the defaults.")
    }
}

/**
 * Pure: a run's [messages] as the JSON saved in `agent_run.transcript`, every string in it passed
 * through [scrub] (which replaces released secret values by their placeholders).
 */
fun helperTranscript(messages: List<Message>, scrub: (String) -> String): JsonElement =
    scrubStrings(HarnessJson.encodeToJsonElement(ListSerializer(Message.serializer()), messages), scrub)

private fun scrubStrings(element: JsonElement, scrub: (String) -> String): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { scrubStrings(it.value, scrub) })
    is JsonArray -> JsonArray(element.map { scrubStrings(it, scrub) })
    is JsonPrimitive -> if (element.isString) JsonPrimitive(scrub(element.content)) else element
    else -> element
}

/** The tools that only make sense next to the browser: a code or a saved login is typed into a page. */
val BROWSER_COMPANIONS: List<String> = listOf("ask_code", "list_credentials")

/**
 * Pure: the tool list a helper gets when the lead names [requested], given the tool names that
 * exist now ([available]). The browser and [BROWSER_COMPANIONS] come together: any browser tool
 * brings them, and a companion asked for without any browser tool brings every browser tool. Left
 * (the delegation fails) when a companion is asked for and there is no browser to use it in.
 */
fun completeHelperTools(requested: List<String>, available: Set<String>): Either<String, List<String>> {
    val browser = available.filter { it.startsWith(BrowserGate.PREFIX) }.sorted()
    val companions = BROWSER_COMPANIONS.filter { it in available }
    return when {
        requested.any { it.startsWith(BrowserGate.PREFIX) } -> (requested + companions).distinct().right()
        requested.none { it in BROWSER_COMPANIONS } -> requested.right()
        browser.isEmpty() -> (
            "Not started: ${requested.filter { it in BROWSER_COMPANIONS }.joinToString()} only works together with the browser, " +
                "and no browser tools are available right now (the browser may be down). Tell the person, or try again later."
            ).left()
        else -> (requested + browser + companions).distinct().right()
    }
}
