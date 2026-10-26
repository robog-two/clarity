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
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.RunJournal

private val log = KotlinLogging.logger {}

/** The task text a helper starts from. Pure. */
fun helperPrompt(task: String, now: kotlin.time.Instant): String = "Current time: $now\n\nTask from the lead agent:\n$task"

/** Pure: what a finished helper reports back to the lead. */
fun helperOutcome(event: HarnessEvent.Terminal?): Pair<Boolean, String> = when (event) {
    is HarnessEvent.Finished -> when (event.reason) {
        FinishReason.END_TURN, FinishReason.COMPACTED -> true to event.result.finalText.ifBlank { "(the helper finished without a result)" }
        FinishReason.MAX_TURNS -> false to "The helper ran out of steps. Last words: ${event.result.finalText.ifBlank { "(none)" }}"
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

    /** Starts a helper for [task]; returns its id, or the reason it could not start. */
    suspend fun delegate(task: String, tools: List<String>?, role: String?, parent: RunScope): Either<String, String> {
        if (!runtime.enabled) return "Helpers are unavailable: no model key is configured.".left()
        val agentRole = delegableRole(role) ?: return "Unknown role \"$role\"; use helper or triage.".left()
        val granted = toolbox.value.helperTools(RunScope("probe", isLead = false), tools)
        if (granted.unknown.isNotEmpty()) return "Unknown or not allowed for helpers: ${granted.unknown.joinToString()}.".left()
        val id = Uuid.random().toString()
        val roleName = agentRole.name.lowercase()
        runs.create(id, parent.runId.takeIf { runCatching { Uuid.parse(it) }.isSuccess }, roleName, task, tools, clock.now())
        // The helper inherits the taint of the context that wrote its task.
        taint[id] = parent.untrusted
        start(requireNotNull(runs.get(id)))
        return "Helper $id started ($roleName). Its result will arrive in your inbox.".right()
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
        val roleConfig = config.roles[delegableRole(run.role) ?: net.robog.clarity.backend.config.AgentRole.HELPER]
        val interrupts = Channel<List<Message>>(Channel.UNLIMITED)
        live[run.id] = interrupts
        var terminal: HarnessEvent.Terminal? = null
        try {
            runs.markRunning(run.id)
            val runScope = RunScope(run.id, isLead = false, untrusted = taint[run.id] ?: true)
            val set = toolbox.value.helperTools(runScope, run.tools).tools
            val request = roleRequest(
                role = roleConfig,
                runId = run.id,
                system = helperSystemBlocks(),
                messages = listOf(Message.user(helperPrompt(run.task, clock.now()))),
                tools = set.loaded,
                deferred = set.deferred,
                maxTurns = config.maxTurns,
                sink = large.sink,
            )
            runtime.harness(journal).resume(run.id, request, interrupts.receiveAsFlow()).collect { event ->
                if (event is HarnessEvent.Terminal) terminal = event
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
                runs.finish(run.id, if (ok) RunStatus.DONE else RunStatus.FAILED, text, terminal?.usage, if (ok) null else text, now)
                inbox.enqueue(InboxItem.HelperResult(run.id, run.task, ok, text), dedupKey = "helper:${run.id}")
            }
        }
    }
}
