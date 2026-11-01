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
import net.robog.clarity.backend.mcpservers.MCP_SWEEP_MAX_COST_USD
import net.robog.clarity.backend.mcpservers.MCP_SWEEP_MAX_TURNS
import net.robog.clarity.backend.mcpservers.MCP_SWEEP_PROFILE
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.OutOfSteps
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.harness.SpendCap
import net.robog.clarity.harness.ToolCallContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.harness.WebSearchTool
import net.robog.clarity.harness.searchFor
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

/** Appended once when a report helper wrote its document as its reply instead of saving it. */
const val HELPER_SAVE_NUDGE: String =
    "You wrote the document in your reply instead of saving it. Save it now with create_report (the text you wrote, as is, " +
        "with the style you were given), then reply with only the report id and a line about it."

/** How long a reply must be to count as a whole document ([looksLikeDocument]). */
const val DOCUMENT_MIN_CHARS: Int = 1500

private val MARKDOWN_HEADING = Regex("^#{1,6}\\s+\\S")

/** Pure: whether [text] reads like a whole document: long, with a Markdown heading line. */
fun looksLikeDocument(text: String): Boolean =
    text.length >= DOCUMENT_MIN_CHARS && text.lineSequence().any { MARKDOWN_HEADING.containsMatchIn(it.trimStart()) }

/** The tools whose success means a report was saved. */
private val SAVING_TOOLS = setOf("create_report", "edit_report")

/** Pure: whether [transcript] holds a create_report or edit_report call that succeeded. */
fun savedReportIn(transcript: List<Message>): Boolean {
    val blocks = transcript.flatMap(Message::content)
    val saving = blocks.filterIsInstance<Block.ToolUse>().filter { it.name in SAVING_TOOLS }.map { it.id }.toSet()
    return blocks.filterIsInstance<Block.Result>().any { it.toolUseId in saving && !it.isError }
}

/**
 * Pure: a helper that can create reports, finished on its own with a document as its reply and
 * never saved a report in the run gets one more turn to save it.
 */
fun helperOwesReport(event: HarnessEvent.Terminal?, tools: Collection<String>): Boolean =
    "create_report" in tools && event is HarnessEvent.Finished && event.reason == FinishReason.END_TURN &&
        looksLikeDocument(event.result.finalText) && !savedReportIn(event.transcript)

/** Pure: the one nudge a finished helper gets ([HELPER_RESULT_NUDGE] or [HELPER_SAVE_NUDGE]), or null. */
fun helperNudge(event: HarnessEvent.Terminal?, tools: Collection<String>): String? = when {
    helperOwesResult(event) -> HELPER_RESULT_NUDGE
    helperOwesReport(event, tools) -> HELPER_SAVE_NUDGE
    else -> null
}

/** Pure: [usd] in millionths of a dollar. */
fun dollarsToMicros(usd: Double): Long = Math.round(usd * 1_000_000)

/** Pure: what a finished helper reports back to the lead. */
fun helperOutcome(event: HarnessEvent.Terminal?): Pair<Boolean, String> = when (event) {
    is HarnessEvent.Finished -> when (event.reason) {
        FinishReason.END_TURN -> true to event.result.finalText.ifBlank { "(the helper finished without a result)" }
        FinishReason.MAX_TURNS -> false to "The helper ran out of steps. Its report: ${event.result.finalText.ifBlank { "(none)" }}"
        FinishReason.SPEND_LIMIT -> false to "The helper hit its spend limit for one task and was stopped. Its report: ${event.result.finalText.ifBlank { "(none)" }}"
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

    /**
     * Starts a helper for [task]; returns its id and what to tell the lead, or the reason it could not
     * start. At most [AgentConfig.helperMaxPerDay] in 24 hours, and not the same task again within
     * [REPEAT_WINDOW] of one that ran, unless the lead gives a [retryReason]. [servers] are labels of
     * the person's MCP servers the helper gets. A [profile] run ([MCP_SWEEP_PROFILE]) gets its own
     * tools from [serverIds] instead of [tools].
     */
    suspend fun start(
        task: String,
        tools: List<String>?,
        role: String?,
        parent: RunScope,
        retryReason: String? = null,
        servers: List<String> = emptyList(),
        profile: String? = null,
        serverIds: List<String> = emptyList(),
    ): Either<String, Delegated> {
        if (!runtime.enabled) return "Helpers are unavailable: no model key is configured.".left()
        val agentRole = delegableRole(role) ?: return "Unknown role \"$role\"; use helper or triage.".left()
        // The same delegate call made again (a resumed lead turn) gets the helper it already started.
        val id = coroutineContext[ToolCallContext]?.let { callRunId(it.runId, it.toolUseId) } ?: Uuid.random().toString()
        runs.get(id)?.let { return Delegated(id, "Helper $id was already started for this call (${it.status}).").right() }
        val now = clock.now()
        val recent = runs.startedSince(now - REPEAT_WINDOW.coerceAtLeast(1.days))
        delegationRefusal(task, recent, now, config.helperMaxPerDay, retryReason)?.let { return it.left() }
        if (profile != null) {
            runs.create(id, null, agentRole.name.lowercase(), task, null, clock.now(), servers = serverIds, profile = profile)
            taint[id] = parent.untrusted
            start(requireNotNull(runs.get(id)))
            return Delegated(id, "Helper $id started ($profile).").right()
        }
        val probe = RunScope("probe", isLead = false)
        // Web search only when the lead names it, and then without any personal-data or outbound tool.
        val search = tools != null && WEB_SEARCH in tools
        if (search && servers.isNotEmpty()) return "A helper with web_search gets no personal data: give it either web_search or servers, not both.".left()
        val ids = if (servers.isEmpty()) emptyList() else toolbox.value.resolveServers(servers).fold({ return it.left() }, { it })
        if (search && config.webSearch.tool() == null) return "Web search is turned off on this server (CLARITY_WEB_SEARCH=false).".left()
        val (requested, dropped) = when {
            tools == null -> null to emptyList()
            search -> researchTools(tools)
            else -> tools to emptyList()
        }
        val chosen = if (requested == null) null else {
            val defaults = toolbox.value.helperTools(probe, null).tools
            if (search) requested else completeHelperTools(requested, defaults.tools.map { it.name }.toSet()).fold({ return it.left() }, { it })
        }
        val granted = toolbox.value.helperTools(probe, chosen, ids)
        // Pages are read only from our side: research without the sandbox's fetch_page does not start.
        if (search && FETCH_PAGE in granted.unknown) return RESEARCH_NEEDS_SANDBOX.left()
        if (granted.unknown.isNotEmpty()) return "Unknown or not allowed for helpers: ${granted.unknown.joinToString()}.".left()
        val roleName = agentRole.name.lowercase()
        // The stored list keeps `web_search`, so a resumed run gets its search back.
        val stored = if (search) chosen.orEmpty() + WEB_SEARCH else chosen
        runs.create(id, parent.runId.takeIf { runCatching { Uuid.parse(it) }.isSuccess }, roleName, task, stored, clock.now(), servers = ids)
        // The helper inherits the taint of the context that wrote its task.
        taint[id] = parent.untrusted
        start(requireNotNull(runs.get(id)))
        val names = granted.tools.tools.map { it.name }
        val text = if (search) {
            delegatedText(id, roleName, null, names) + " It has only: ${names.sorted().joinToString()}." + searchGrantText(dropped)
        } else {
            delegatedText(id, roleName, chosen, names) +
                if (ids.isEmpty() || chosen != null) "" else " It has the tools of ${servers.joinToString()} that you allowed, and their notes."
        }
        return Delegated(id, text).right()
    }

    private val taint = ConcurrentHashMap<String, Boolean>()

    /** The run's spend cap, counting what it already spent (a resume, the nudge). */
    private suspend fun spendCap(id: String, maxUsd: Double = config.helperMaxCostUsd) = SpendCap(dollarsToMicros(maxUsd), runs.costUsed(id))

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
                // Finished, failed or cancelled: the browser goes to the next run.
                withContext(NonCancellable) { runCatching { toolbox.value.runEnded(run.id) } }
            }
        }
    }

    private suspend fun execute(run: HelperRun) {
        val agentRole = delegableRole(run.role) ?: net.robog.clarity.backend.config.AgentRole.HELPER
        val interrupts = Channel<List<Message>>(Channel.UNLIMITED)
        live[run.id] = interrupts
        var terminal: HarnessEvent.Terminal? = null
        var stopped: String? = null
        try {
            // A run that keeps being resumed (a crash loop at boot) is failed instead of resumed again.
            val starts = runs.recordStart(run.id)
            if (starts > 1 + config.helperMaxResumes) {
                stopped = "The helper was stopped: it was resumed ${starts - 1} times after restarts without finishing."
                log.warn { "helper ${run.id}: started $starts times, failing it" }
                throw TooManyResumes()
            }
            runs.markRunning(run.id)
            // Bound once: the run keeps its model to the end, whatever changes in settings meanwhile.
            val bound = runtime.bindOrFail(agentRole)
            val runScope = RunScope(run.id, isLead = false, untrusted = taint[run.id] ?: true, purpose = run.task)
            val sweep = run.profile == MCP_SWEEP_PROFILE
            val plan = if (sweep) HelperRunPlan(null, null) else helperRunPlan(run.tools, config.webSearch.tool())
            val set = if (sweep) {
                // The sweep's tool calls count across a resume too (its scratch note aside).
                val used = journal.load(run.id).flatMap { it.content }.count { it is Block.ToolUse && it.name != "set_scratch" }
                toolbox.value.sweepTools(runScope, run.servers, used)
            } else {
                toolbox.value.helperTools(runScope, plan.tools, run.servers).tools
            }
            val request = roleRequest(
                role = bound.config,
                runId = run.id,
                system = helperSystemBlocks(),
                messages = listOf(Message.user(helperPrompt(run.task, clock.now(), settings.homeZone()))),
                tools = set.tools,
                maxTurns = if (sweep) MCP_SWEEP_MAX_TURNS else config.helperMaxTurns,
                sink = large.sink,
                // The cap is per run, not per resume: searches already recorded for it count.
                webSearch = searchFor(plan.webSearch, runs.webSearchesUsed(run.id)),
            // A resume continues the run's step budget: every model call in its journal counts.
            ).copy(outOfSteps = OutOfSteps(), spendCap = spendCap(run.id, if (sweep) MCP_SWEEP_MAX_COST_USD else config.helperMaxCostUsd), countStepsSince = { false })
            val harness = runtime.harness(journal, bound)
            // Search results are outside content: a run that searched is tainted like one that read mail.
            val onEvent: (HarnessEvent) -> Unit = { event ->
                if (searchedIn(event)) {
                    runScope.taint()
                    log.info { "helper ${run.id}: model call ran ${(event as HarnessEvent.Called).usage.webSearchRequests} web searches" }
                }
                if (event is HarnessEvent.Terminal) terminal = event
            }
            harness.resume(run.id, request, interrupts.receiveAsFlow()).collect { onEvent(it) }
            // Open models sometimes end with no text, or with the document as text: one bounded nudge, never more.
            helperNudge(terminal, set.tools.map { it.name })?.let { nudge ->
                journal.append(run.id, Message.user(nudge))
                // The nudge has a budget of its own: one step to write the result, two to save the document and say so.
                val nudged = request.copy(
                    webSearch = searchFor(plan.webSearch, runs.webSearchesUsed(run.id)),
                    spendCap = spendCap(run.id, if (sweep) MCP_SWEEP_MAX_COST_USD else config.helperMaxCostUsd),
                    maxTurns = nudgeSteps(nudge),
                    countStepsSince = { it.role == net.robog.clarity.harness.Role.USER && it.content.any { b -> b is net.robog.clarity.harness.Block.Text && b.text == nudge } },
                )
                harness.resume(run.id, nudged, interrupts.receiveAsFlow()).collect { onEvent(it) }
            }
        } catch (e: CancellationException) {
            throw e // cancel_helper already recorded its status; a shutdown leaves the run open to resume at boot
        } catch (_: TooManyResumes) {
            // [stopped] says why
        } catch (e: Exception) {
            log.error(e) { "helper ${run.id} crashed" }
        }
        if ((terminal as? HarnessEvent.Finished)?.reason == FinishReason.SPEND_LIMIT) {
            log.warn { "helper ${run.id} hit its spend limit (${"$"}${config.helperMaxCostUsd}) and was stopped" }
        }
        val (ok, text) = stopped?.let { false to it } ?: helperOutcome(terminal)
        withContext(NonCancellable) {
            val now = clock.now()
            val current = runs.get(run.id)
            if (current != null && current.status in RunStatus.OPEN) {
                // A copy of the journal for diagnosis; only placeholders, never a released value.
                runCatching { runs.saveTranscript(run.id, helperTranscript(journal.load(run.id), grants::scrub)) }
                    .onFailure { log.warn { "helper ${run.id}: transcript not saved (${it::class.simpleName})" } }
                runs.finish(run.id, if (ok) RunStatus.DONE else RunStatus.FAILED, text, terminal?.usage, if (ok) null else text, now)
                // The lead's own tasks do not follow the helper; it is told which are still open so it closes them.
                if (run.profile == MCP_SWEEP_PROFILE) {
                    // The sweep's findings are its scratch note, read at the call prep; it wakes no one.
                    log.info { "MCP sweep ${run.id} ${if (ok) "finished" else "failed"}: ${text.take(200)}" }
                } else {
                    val openTasks = runCatching { toolbox.value.openLeadTasksOf(run.id) }.getOrDefault(emptyList())
                    inbox.enqueue(InboxItem.HelperResult(run.id, run.task, ok, text, openTasks), dedupKey = "helper:${run.id}")
                }
            }
        }
    }
}

private class TooManyResumes : RuntimeException()

/** How long a task that ran is not delegated again without a retry reason. */
val REPEAT_WINDOW: kotlin.time.Duration = 6.hours

/** Pure: [task] reduced to its words, so the same task in other spacing or case is the same. */
fun normalizedTask(task: String): String = task.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/**
 * Pure: why delegating [task] now is refused, given the helper runs started in the last day or more
 * ([recent]), or null: more than [maxPerDay] started in 24 hours; the same task ([normalizedTask])
 * still running; or the same task done or failed within [REPEAT_WINDOW] and no [retryReason].
 */
fun delegationRefusal(task: String, recent: List<HelperRun>, now: kotlin.time.Instant, maxPerDay: Int, retryReason: String?): String? {
    val today = recent.count { it.startedAt >= now - 24.hours }
    if (today >= maxPerDay) {
        return "Not started: $today helpers were started in the last 24 hours, the limit. Do the rest yourself, or tell the person."
    }
    val same = recent.filter { normalizedTask(it.task) == normalizedTask(task) }
    same.firstOrNull { it.status in RunStatus.OPEN }?.let {
        return "Not started: helper ${it.id} is already working on this task. Send it news with message_helper."
    }
    if (!retryReason.isNullOrBlank()) return null
    val ran = same.firstOrNull { it.status == RunStatus.DONE || it.status == RunStatus.FAILED }
        ?.takeIf { (it.finishedAt ?: it.startedAt) >= now - REPEAT_WINDOW }
        ?: return null
    return "Not started: helper ${ran.id} already ran this task (${ran.status}). Use its result; to run it again, say why in retryReason."
}

/** Pure: the run id of the helper that tool call [toolUseId] of run [runId] starts, the same every time. */
fun callRunId(runId: String, toolUseId: String): String =
    java.util.UUID.nameUUIDFromBytes("delegate:$runId:$toolUseId".toByteArray()).toString()

/** Pure: the steps a nudged helper gets: two to save the document and say so, one to write a result. */
fun nudgeSteps(nudge: String): Int = if (nudge == HELPER_SAVE_NUDGE) 2 else 1

/** What a helper run gets from its stored tool list: the tools to build, and web search when it was granted. */
data class HelperRunPlan(val tools: List<String>?, val webSearch: WebSearchTool?)

/**
 * Pure: the plan of a helper run whose stored tool list is [stored] ([WEB_SEARCH] in it means the lead
 * granted search). A search run gets [search] (null when search is off on the server) and only
 * [researchTools]; any other run gets its list as it is and never search.
 */
fun helperRunPlan(stored: List<String>?, search: WebSearchTool?): HelperRunPlan =
    if (stored != null && WEB_SEARCH in stored) HelperRunPlan(researchTools(stored).first, search)
    else HelperRunPlan(stored, null)

/** Pure: whether [event] is a model call that ran web searches (the run has then read outside content). */
fun searchedIn(event: HarnessEvent): Boolean = event is HarnessEvent.Called && event.usage.webSearchRequests > 0

/** Pure: what `delegate` adds for a helper granted web search, naming the tools it lost. */
fun searchGrantText(dropped: List<String>): String =
    " It can search the web (only it; you cannot), and has no personal data or outbound tools" +
        (if (dropped.isEmpty()) "" else " (left out: ${dropped.sorted().joinToString()})") +
        ", so its task must say everything it needs."

/** Why a research helper does not start when the sandbox (and its fetch_page) is not there. */
const val RESEARCH_NEEDS_SANDBOX: String = "Research needs the sandbox to open pages, and it is not available right now."

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
