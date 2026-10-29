package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.reports.CITE_SOURCES
import net.robog.clarity.backend.reports.REPORT_TOOLS
import net.robog.clarity.backend.reports.ReportTools
import net.robog.clarity.backend.vault.VaultTools
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.backend.config.AgentRole
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.TaskId

/** The tools of a run, sorted by name; all of them are sent with every request. */
data class ToolSet(val tools: List<Tool>)

/** What a helper may use unless the lead narrows it: reads, the gated external tools and the report tools. */
val HELPER_BASE = setOf(
    "list_messages", "search_messages", "list_calendar_events", "search_contacts", "search_memory", "read_memory", "open_collection", "read_journal",
    "read_profile", "read_result", "list_triggers", "save_draft", "send_email", "create_event", "move_event", "respond_to_invite", "create_reminder", "set_do_not_disturb",
    "request_credential", "list_credentials", "ask_code",
) + REPORT_TOOLS

/**
 * The name the lead puts in `delegate`'s tools to give a helper OpenRouter's web search. It is not a
 * tool of ours (the search runs on OpenRouter's side), never a default, and never the lead's.
 */
const val WEB_SEARCH = "web_search"

/** MCP tools of the sandbox (`sandbox__shell`, ...). */
const val SANDBOX_PREFIX = "sandbox__"

/**
 * Pure: the tools a helper with web search keeps from [requested]: only the sandbox and the report
 * tools (always all of them, plus `cite_sources`). Everything that reads personal data or reaches
 * outside (mail, contacts, calendar, memory, journal, profile, drafts and other guarded actions, the
 * browser, the vault and credentials, saved results) is dropped, so a search query can only carry
 * what the task says. Returns (kept, dropped); `web_search` itself is neither.
 */
fun researchTools(requested: List<String>): Pair<List<String>, List<String>> {
    val asked = requested.filter { it != WEB_SEARCH }.distinct()
    val (kept, dropped) = asked.partition { it.startsWith(SANDBOX_PREFIX) || it in REPORT_TOOLS || it == CITE_SOURCES }
    return (kept + REPORT_TOOLS + CITE_SOURCES).distinct() to dropped
}

/** The memory clean-up helper's tools. */
val MEMORY_GC_TOOLS = listOf(
    "read_profile", "replace_profile", "read_journal", "search_memory", "read_memory", "open_collection", "save_note", "manage_collection",
)

/** The helper-control tools of the lead. */
@Inject
@SingleIn(AppScope::class)
class HelperTools(
    private val helpers: Lazy<HelperManager>,
    private val tasks: TaskStore,
    private val runs: RunStore,
    private val gate: FailureGate,
) {
    @Serializable
    class DelegateInput(
        @Description("What to do and what to report back. Self-contained: the helper has no other context. Say what a good result looks like and how long it should be.")
        val task: String,
        @Description("Omit this to give the helper the default tools: reads, the browser, the sandbox, the guarded actions, the vault tools (ask_code, list_credentials) and the report tools. A list REPLACES the defaults: only the tools named, so a browsing helper needs every browser__ tool it uses. Never includes send_to_user. Name web_search to let it search the web: such a helper gets only the sandbox and report tools (no mail, contacts, calendar, memory, browser or vault), so put everything it needs to know in the task.")
        val tools: List<String>? = null,
        @Description("helper (default) for real work, triage for a cheap quick classification.") val role: String? = null,
        @Description("The id of the task on the person's list (update_task) this helper works on. Pass it whenever there is one, so the person can follow the helper's steps from that task.")
        val taskId: String? = null,
        @Description("A short name for the person's task list, used only when no taskId is given.")
        val title: String? = null,
    )

    @Serializable
    class NoInput

    @Serializable
    class ListHelpersInput(@Description("Only helpers that are still running (default true).") val running: Boolean = true)

    @Serializable
    class MessageHelperInput(val id: String, @Description("What to tell the helper; it reads this before its next step.") val message: String)

    @Serializable
    class CancelHelperInput(val id: String)

    /**
     * Links a new helper to [taskId], or to the one task the lead's turn touched ([TurnTasks]). A
     * lead helper left unlinked (not triage) gets a task of its own when the turn ends ([endTurn]).
     */
    private suspend fun linkHelper(scope: RunScope, runId: String, input: DelegateInput, taskId: TaskId?) {
        if (taskId != null) {
            tasks.link(taskId, runId, TaskLink.EXPLICIT)
            scope.turn.touched(taskId.value).forEach { tasks.link(taskId, it, TaskLink.INFERRED) }
        } else {
            val autoTitle = if (scope.isLead && delegableRole(input.role) != AgentRole.TRIAGE) autoTaskTitle(input.title, input.task) else null
            scope.turn.started(runId, autoTitle)?.let { tasks.link(TaskId(it), runId, TaskLink.INFERRED) }
        }
    }

    /**
     * The lead's turn ended: every helper it left unlinked gets a task created here
     * ([TaskLink.AUTO]), WORKING, or already in the helper's final state if it finished meanwhile.
     */
    suspend fun endTurn(scope: RunScope) {
        for (helper in scope.turn.drain()) {
            val title = helper.autoTitle ?: continue
            val task = tasks.upsert(null, title, "", AgentTask.Status.WORKING) ?: continue
            tasks.link(task.id, helper.runId, TaskLink.AUTO)
            // A quick helper may have ended before its task existed.
            followRun(task.id, helper.runId)
        }
    }

    /** Helper [runId] ended: the task the server created for it, if any, takes its outcome. Tasks the lead owns are left alone. */
    suspend fun runEnded(runId: String) {
        tasks.autoTaskOf(runId)?.let { followRun(it, runId) }
    }

    /** The open tasks helper [runId] worked on that the lead owns (not the one made for it), as "id \"title\" (status)". */
    suspend fun openLeadTasksOf(runId: String): List<String> {
        val auto = tasks.autoTaskOf(runId)
        return tasks.list().filter { runId in it.runIds && it.id != auto }.map(::taskLine)
    }

    private suspend fun followRun(task: TaskId, runId: String) {
        val status = runs.get(runId)?.status?.let(::taskStatusOf) ?: return
        if (tasks.get(task)?.status != status) tasks.upsert(task, null, null, status)
    }

    fun all(scope: RunScope): List<Tool> = listOf(
        tool<DelegateInput>(
            name = "delegate",
            description = "Hand a token-heavy job (reading many messages, browsing, long documents, shell work) to a helper with fresh context. " +
                "Returns at once; the helper's single result arrives later in your inbox. Do not wait for it.",
            effect = ToolEffect.WRITE,
            json = ClarityJson,
        ) { input ->
            val named = input.taskId?.trim()?.ifEmpty { null }
            // Resolved to the task's full id, so a shortened id links the same task update_task touched.
            val taskId = named?.let { tasks.get(TaskId(it))?.id }
            when {
                input.task.isBlank() -> ToolResult.Error("`task` must not be empty")
                named != null && taskId == null -> ToolResult.Error("No task with id $named; create it with update_task first, or omit `taskId`.")
                gate.check(GateKind.BRIEF, "delegate", input.task) != null -> ToolResult.Error(FailureGate.BRIEF_REFUSAL)
                else -> helpers.value.start(input.task, input.tools, input.role, scope).fold({ ToolResult.Error(it) }) { started ->
                    linkHelper(scope, started.id, input, taskId)
                    ToolResult.Ok(started.text)
                }
            }
        },
        tool<ListHelpersInput>(
            name = "list_helpers",
            description = "List helpers (id, status, task).",
            json = ClarityJson,
        ) { input ->
            val runs = helpers.value.list(openOnly = input.running)
            ToolResult.Ok(
                if (runs.isEmpty()) "(none)"
                else runs.joinToString("\n") { "${it.id} [${it.status}] ${it.task.take(160).replace('\n', ' ')}" },
            )
        },
        tool<MessageHelperInput>(
            name = "message_helper",
            description = "Send a message to a running helper (new information, a changed priority).",
            effect = ToolEffect.WRITE,
            json = ClarityJson,
        ) { input ->
            // A message to a running helper is part of its brief: the same gate keeps failure stories out.
            gate.check(GateKind.BRIEF, "message_helper", input.message)?.let { return@tool ToolResult.Error(it) }
            helpers.value.message(input.id, input.message).fold({ ToolResult.Error(it) }, { ToolResult.Ok(it) })
        },
        tool<CancelHelperInput>(
            name = "cancel_helper",
            description = "Stop a helper. Its work so far is kept in its journal; it returns no result.",
            effect = ToolEffect.WRITE,
            json = ClarityJson,
        ) { input -> helpers.value.cancel(input.id).fold({ ToolResult.Error(it) }, { ToolResult.Ok(it) }) },
    )
}

/**
 * Assembles tool sets for the lead and for helpers. Tools that read content from outside are
 * wrapped so that using them marks the run as having seen untrusted data, which the guard then
 * holds against any external action the run proposes.
 */
@Inject
@SingleIn(AppScope::class)
class AgentToolbox(
    private val domain: DomainTools,
    private val own: OwnStateTools,
    private val memory: MemoryTools,
    private val external: ExternalTools,
    private val helperTools: HelperTools,
    private val results: LargeResults,
    private val mcp: McpToolSources,
    private val session: SessionTools,
    private val vault: VaultTools,
    private val browser: BrowserPilot,
    private val nudges: NudgeTools,
    private val reports: ReportTools,
) {
    private fun guarded(scope: RunScope, tools: List<Tool>): List<Tool> =
        tools.map { if (it.name in UNTRUSTED_SOURCE_TOOLS) it.taintingScope(scope) else it }

    /** Every MCP tool; the browser's are wrapped (placeholders, gate, scrubbing) and its raw-script tools hidden. */
    private suspend fun mcpTools(scope: RunScope): List<Tool> = browser.wrap(scope, mcp.tools()).map { it.taintingScope(scope) }

    private fun everything(scope: RunScope, forLead: Boolean): List<Tool> = buildList {
        addAll(domain.all)
        addAll(external.all(scope))
        add(results.readResultTool())
        // A code or a saved login is only ever typed into a page, and the lead never browses.
        addAll(vault.all(scope).filter { !forLead || it.name !in BROWSER_COMPANIONS })
        with(memory) {
            addAll(listOf(searchMemory(), readMemory(), openCollection()))
            if (forLead) addAll(listOf(saveNote(), manageCollection()))
        }
        with(own) {
            addAll(listOf(readJournal(), readProfile()))
            if (forLead) addAll(listOf(sendToUser(), react(), rememberFact(), writeJournal(), setScratch(), updateTask(scope), listTasks()))
        }
        addAll(if (forLead) reports.leadTools(scope) else reports.helperTools(scope))
        if (forLead) addAll(helperTools.all(scope))
        if (forLead) addAll(session.all())
        if (forLead) addAll(nudges.all())
    }.let { guarded(scope, it) }

    /**
     * The lead's tools, all sent with every request. The lead gets no MCP tools (the browser, the
     * sandbox): it hands that work to a helper on a cheaper model.
     */
    fun leadTools(scope: RunScope): ToolSet = ToolSet(everything(scope, forLead = true).sortedBy { it.name })

    /**
     * A helper's tools: [requested] (or [HELPER_BASE] plus every MCP tool) that exist, never
     * `send_to_user` and never helper controls. [unknown] lists requested names that matched nothing.
     */
    suspend fun helperTools(scope: RunScope, requested: List<String>?): HelperToolSet {
        val mcpAll = mcpTools(scope)
        val candidates = (everything(scope, forLead = false).filter { it.name in HELPER_BASE || it.name in MEMORY_GC_TOOLS || it.name == CITE_SOURCES } + gcOnly(scope) + mcpAll)
            .distinctBy { it.name }
        val chosen = if (requested == null) candidates.filter { it.name in HELPER_BASE || it in mcpAll }
        else candidates.filter { it.name in requested }
        val unknown = requested.orEmpty().filter { name -> candidates.none { it.name == name } }
        return HelperToolSet(ToolSet(chosen.sortedBy { it.name }), unknown)

    }

    /** A helper run finished, failed or was cancelled: it lets go of the browser, and a task made for it follows its outcome. */
    suspend fun runEnded(runId: String) {
        runCatching { browser.runEnded(runId) }
        helperTools.runEnded(runId)
    }

    /** The still-open tasks the lead owns that helper [runId] worked on (see [HelperTools.openLeadTasksOf]). */
    suspend fun openLeadTasksOf(runId: String): List<String> = helperTools.openLeadTasksOf(runId)

    /** The lead's turn ended (normally or not): helpers it left without a task get one. */
    suspend fun endTurn(scope: RunScope) = helperTools.endTurn(scope)

    private fun gcOnly(scope: RunScope): List<Tool> = guarded(scope, listOf(own.replaceProfile(), memory.saveNote(), memory.manageCollection()))
}

data class HelperToolSet(val tools: ToolSet, val unknown: List<String>)

private const val AUTO_TITLE_MAX = 60

/**
 * Pure: the title of the task the server creates for an unlinked helper: [title] when given, else
 * the first sentence or line of [brief]; longer than [AUTO_TITLE_MAX] characters is cut on a word
 * boundary with an ellipsis.
 */
fun autoTaskTitle(title: String?, brief: String): String {
    val given = title?.trim()?.replace(Regex("\\s+"), " ")?.ifEmpty { null }
    val raw = given ?: run {
        val line = brief.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val sentence = Regex("^.*?[.!?](?=\\s|$)").find(line)?.value ?: line
        sentence.trimEnd('.', ' ').ifEmpty { "Helper task" }
    }
    if (raw.length <= AUTO_TITLE_MAX) return raw
    val room = raw.take(AUTO_TITLE_MAX - 1)
    val cut = room.lastIndexOf(' ').takeIf { it > AUTO_TITLE_MAX / 2 } ?: room.length
    return room.take(cut).trimEnd(' ', ',', ';', ':', '.', '-') + "…"
}

/** Pure: a finished helper run's status as a task status; null while it is still open. */
fun taskStatusOf(runStatus: String): AgentTask.Status? = when (runStatus) {
    RunStatus.DONE -> AgentTask.Status.DONE
    RunStatus.FAILED -> AgentTask.Status.FAILED
    RunStatus.CANCELLED -> AgentTask.Status.CANCELLED
    else -> null
}
