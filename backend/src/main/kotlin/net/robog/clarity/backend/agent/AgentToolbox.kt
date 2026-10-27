package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.vault.VaultTools
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson

/** The tools of a run, sorted by name; all of them are sent with every request. */
data class ToolSet(val tools: List<Tool>)

/** What a helper may use unless the lead narrows it: reads and the gated external tools. */
val HELPER_BASE = setOf(
    "list_messages", "list_calendar_events", "search_contacts", "search_memory", "read_memory", "open_collection", "read_journal",
    "read_profile", "read_result", "list_triggers", "save_draft", "send_email", "create_event", "move_event", "respond_to_invite", "create_reminder", "set_do_not_disturb",
    "request_credential", "list_credentials", "ask_code",
)

/** The memory clean-up helper's tools. */
val MEMORY_GC_TOOLS = listOf(
    "read_profile", "replace_profile", "read_journal", "search_memory", "read_memory", "open_collection", "save_note", "manage_collection",
)

/** The helper-control tools of the lead. */
@Inject
@SingleIn(AppScope::class)
class HelperTools(private val helpers: Lazy<HelperManager>) {
    @Serializable
    class DelegateInput(
        @Description("What to do and what to report back. Self-contained: the helper has no other context. Say what a good result looks like and how long it should be.")
        val task: String,
        @Description("Omit this to give the helper the default tools: reads, the browser, the sandbox, the guarded actions and the vault tools (ask_code, list_credentials). A list REPLACES the defaults: only the tools named, so a browsing helper needs every browser__ tool it uses. Never includes send_to_user.")
        val tools: List<String>? = null,
        @Description("helper (default) for real work, triage for a cheap quick classification.") val role: String? = null,
    )

    @Serializable
    class NoInput

    @Serializable
    class ListHelpersInput(@Description("Only helpers that are still running (default true).") val running: Boolean = true)

    @Serializable
    class MessageHelperInput(val id: String, @Description("What to tell the helper; it reads this before its next step.") val message: String)

    @Serializable
    class CancelHelperInput(val id: String)

    fun all(scope: RunScope): List<Tool> = listOf(
        tool<DelegateInput>(
            name = "delegate",
            description = "Hand a token-heavy job (reading many messages, browsing, long documents, shell work) to a helper with fresh context. " +
                "Returns at once; the helper's single result arrives later in your inbox. Do not wait for it.",
            effect = ToolEffect.WRITE,
            json = ClarityJson,
        ) { input ->
            if (input.task.isBlank()) ToolResult.Error("`task` must not be empty")
            else helpers.value.delegate(input.task, input.tools, input.role, scope).fold({ ToolResult.Error(it) }, { ToolResult.Ok(it) })
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
        ) { input -> helpers.value.message(input.id, input.message).fold({ ToolResult.Error(it) }, { ToolResult.Ok(it) }) },
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
    private val browser: BrowserGate,
    private val nudges: NudgeTools,
) {
    private fun guarded(scope: RunScope, tools: List<Tool>): List<Tool> =
        tools.map { if (it.name in UNTRUSTED_SOURCE_TOOLS) it.taintingScope(scope) else it }

    /** Every MCP tool; the browser's are wrapped (placeholders, gate, scrubbing) and its raw-script tools hidden. */
    private suspend fun mcpTools(scope: RunScope): List<Tool> = browser.wrap(scope, mcp.tools()).map { it.taintingScope(scope) }

    private fun everything(scope: RunScope, forLead: Boolean): List<Tool> = buildList {
        addAll(domain.all)
        addAll(external.all(scope))
        add(results.readResultTool())
        addAll(vault.all(scope))
        with(memory) {
            addAll(listOf(searchMemory(), readMemory(), openCollection()))
            if (forLead) addAll(listOf(saveNote(), manageCollection()))
        }
        with(own) {
            addAll(listOf(readJournal(), readProfile()))
            if (forLead) addAll(listOf(sendToUser(), rememberFact(), writeJournal(), setScratch(), updateTask()))
        }
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
        val candidates = (everything(scope, forLead = false).filter { it.name in HELPER_BASE || it.name in MEMORY_GC_TOOLS } + gcOnly(scope) + mcpAll)
            .distinctBy { it.name }
        val chosen = if (requested == null) candidates.filter { it.name in HELPER_BASE || it in mcpAll }
        else candidates.filter { it.name in requested }
        val unknown = requested.orEmpty().filter { name -> candidates.none { it.name == name } }
        return HelperToolSet(ToolSet(chosen.sortedBy { it.name }), unknown)

    }

    private fun gcOnly(scope: RunScope): List<Tool> = guarded(scope, listOf(own.replaceProfile(), memory.saveNote(), memory.manageCollection()))
}

data class HelperToolSet(val tools: ToolSet, val unknown: List<String>)
