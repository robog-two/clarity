package net.robog.clarity.backend.agent

import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.SUMMARY_MARKER
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.browser.BrowserStepView
import net.robog.clarity.shared.path.Outcome
import net.robog.clarity.shared.path.PathItem

/*
 * The Agent's Path, built from the run journals: pure functions that turn journal rows and browser
 * steps into short, redacted timeline items. Nothing here passes a secret on: vault and code tools
 * become fixed lines, typed values and raw inputs are dropped, results are reduced to counts or a
 * short confirmation, and every text is scrubbed ([PathRedactor]) and clipped to [PATH_CLIP].
 */

/** The longest text of any one path item; the full text stays on the server. */
const val PATH_CLIP = 200

/** Makes a text safe and short for the app. [scrub] replaces values released from the vault in this process by their placeholders. */
class PathRedactor(private val scrub: (String) -> String = { it }) {
    fun text(raw: String, max: Int = PATH_CLIP): String {
        val safe = SecretDetector.redact(PLACEHOLDER.replace(UNTRUSTED.replace(scrub(raw), " "), "[secret]"))
        return clip(safe.replace(WHITESPACE, " ").trim(), max)
    }

    /**
     * A browser step as the app may see it: texts redacted and clipped, the address without its
     * query (it can carry tokens), the action reduced to its verb (its input may name a field's value).
     */
    fun step(step: BrowserStepView): BrowserStepView = step.copy(
        tabUrl = step.tabUrl?.let { text(it.substringBefore('?').substringBefore('#'), 120) },
        tabTitle = step.tabTitle?.let { text(it, 120) },
        intent = step.intent?.let { text(it) },
        expect = step.expect?.let { text(it) },
        hitName = step.hitName?.let { text(it, 120) },
        action = step.action?.let { text(it.substringBefore(' ').substringBefore('{').removeSuffix(":"), 40) },
        changeReport = step.changeReport?.let { text(it, 300) },
    )

    private companion object {
        val PLACEHOLDER = Regex("""\\?\[secret:[^\]]{0,200}]""")
        val UNTRUSTED = Regex("<untrusted[^>]*>[\\s\\S]*?(</untrusted>|$)", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
    }
}

/** Pure: [text] cut to [max] characters on a word boundary when one is near, with an ellipsis. */
fun clip(text: String, max: Int = PATH_CLIP): String {
    if (text.length <= max) return text
    val cut = text.take(max - 1)
    val space = cut.lastIndexOf(' ')
    return (if (space >= max * 2 / 3) cut.take(space) else cut).trimEnd() + "…"
}

/** A helper started from a path, as its parent's [PathItem.Helper] shows it. */
data class ChildRun(
    val id: String,
    val role: String,
    val task: String,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val steps: Int,
    val browserSteps: Int,
)

/**
 * Pure: a finished helper's [items] closed by one [PathItem.Finished] node. The helper's final turn
 * is its result to the lead, so a closing [PathItem.Said] folds into that node instead of showing
 * twice: the node says the run's [result] (redacted and clipped), else that final text. Unchanged
 * when there is neither.
 */
fun withFinish(items: List<PathItem>, runId: String, depth: Int, ok: Boolean, result: String?, finishedAt: Instant?, redact: PathRedactor): List<PathItem> {
    val last = items.lastOrNull() as? PathItem.Said
    val text = result?.takeIf { it.isNotBlank() }?.let { redact.text(it) } ?: last?.text ?: return items
    val at = finishedAt ?: last?.at ?: items.lastOrNull()?.at ?: return items
    return (if (last != null) items.dropLast(1) else items) + PathItem.Finished(at, runId, depth, ok, text)
}

/** Pure: the steps that count towards a path's total (not what woke the agent, not a helper's own result). */
fun countsAsStep(item: PathItem): Boolean = item is PathItem.Said || item is PathItem.Action || item is PathItem.Browser || item is PathItem.Helper

private val HELPER_STARTED = Regex("""Helper ([0-9a-fA-F-]{36}) started""")
private val INBOX_HELPER = Regex("""^Helper (\S+) (finished|failed) its task: (.*)$""")
private const val OPERATOR_PLACEHOLDER = "(operator update follows)"

/** Pure: whether [message] opens a lead turn: the person's words, or an inbox delivery. */
fun opensLeadTurn(message: Message): Boolean = when (message.role) {
    Role.USER -> message.content.none { it is Block.Result } && message.content.any { it is Block.Text && personText(it.text) }
    Role.SYSTEM -> message.content.any { it is Block.Text && it.text.startsWith("[inbox") }
    Role.ASSISTANT -> false
}

private fun personText(text: String): Boolean {
    val t = text.trim()
    return t.isNotEmpty() && !t.startsWith("(housekeeping") && t != OPERATOR_PLACEHOLDER && !t.startsWith(SUMMARY_MARKER) && t != HELPER_RESULT_NUDGE
}

/** Pure: the lead journal split into turns ([opensLeadTurn]); rows before the first opening form a turn of their own. */
fun leadTurns(rows: List<JournalRow>): List<List<JournalRow>> {
    val turns = mutableListOf<MutableList<JournalRow>>()
    for (row in rows) {
        if (turns.isEmpty() || opensLeadTurn(row.message)) turns += mutableListOf<JournalRow>()
        turns.last() += row
    }
    return turns
}

/** Tools through which the lead works on a task: a turn that names the task's id in one of them touched it. */
private val TASK_TOOLS = setOf("update_task", "delegate", "hand_off")

/** Pure: the lead turns that touched task [taskId]: created or updated it, or delegated a helper for it. */
fun turnsTouching(turns: List<List<JournalRow>>, taskId: String): List<List<JournalRow>> = turns.filter { turn ->
    val names = HashMap<String, String>()
    turn.any { row ->
        row.message.content.any { block ->
            when (block) {
                is Block.ToolUse -> {
                    names[block.id] = block.name
                    block.name in TASK_TOOLS && taskId in block.input.toString()
                }
                is Block.Result -> names[block.toolUseId] in TASK_TOOLS && taskId in block.content
                else -> false
            }
        }
    }
}

/**
 * Pure: the timeline items of one run's journal [rows]. [steps] are the run's browser steps; they
 * take the place of the browser tool call they belong to. [children] are the helpers it started,
 * by id. [lead]: the rows are lead turns (what woke each turn is shown); otherwise a helper's
 * journal, whose first message (the task) is left out.
 */
fun runItems(
    runId: String,
    rows: List<JournalRow>,
    steps: List<BrowserStepView>,
    children: Map<String, ChildRun>,
    redact: PathRedactor,
    lead: Boolean,
    depth: Int = 0,
): List<PathItem> {
    data class Use(val name: String, val input: JsonObject, val at: Instant, val order: Int)

    val steps = steps.map(redact::step)
    val out = mutableListOf<Pair<Int, PathItem>>()
    val uses = LinkedHashMap<String, Use>()
    val claimed = HashSet<Long>()
    var order = 0
    val firstUser = rows.indexOfFirst { it.message.role == Role.USER }

    fun emit(at: Int, item: PathItem) { out += at to item }

    rows.forEachIndexed { index, row ->
        val m = row.message
        if (m.isSummary || m.content.any { it is Block.Raw && it.isCompaction }) return@forEachIndexed
        for (block in m.content) {
            when (block) {
                is Block.Text -> when (m.role) {
                    Role.ASSISTANT -> block.text.takeIf { it.isNotBlank() }?.let { emit(order++, PathItem.Said(row.at, runId, depth, redact.text(it))) }
                    Role.USER -> if (personText(block.text) && !(index == firstUser && !lead)) {
                        val from = if (lead) PathItem.Heard.Source.PERSON else PathItem.Heard.Source.EVENT
                        val text = if (lead) redact.text(block.text) else "From the lead: " + redact.text(block.text)
                        emit(order++, PathItem.Heard(row.at, runId, depth, from, text))
                    }
                    Role.SYSTEM -> if (lead && block.text.startsWith("[inbox")) inboxLines(block.text).forEach { (source, line) ->
                        emit(order++, PathItem.Heard(row.at, runId, depth, source, redact.text(line)))
                    }
                }
                is Block.ToolUse -> uses[block.id] = Use(block.name, block.input, row.at, order++)
                is Block.Result -> {
                    val use = uses.remove(block.toolUseId) ?: continue
                    if (use.name.startsWith(BrowserGate.PREFIX)) {
                        val mine = steps.filter { it.id !in claimed && it.at >= use.at && it.at <= row.at }
                        if (mine.isNotEmpty()) {
                            mine.forEach { claimed += it.id; emit(use.order, PathItem.Browser(it.at, runId, depth, it)) }
                            continue
                        }
                    }
                    if (use.name == "delegate") {
                        val child = HELPER_STARTED.find(block.content)?.groupValues?.get(1)?.let { children[it] }
                        if (child != null) {
                            emit(use.order, child.toItem(use.at, runId, depth))
                            continue
                        }
                    }
                    emit(use.order, actionItem(runId, depth, use.name, use.input, use.at, block.content, block.isError, redact))
                }
                is Block.Raw -> Unit // reasoning and provider blocks are never shown
            }
        }
    }
    // Calls still running (or cut off by a restart): shown as running, browser steps already taken first.
    uses.values.forEach { use ->
        if (use.name.startsWith(BrowserGate.PREFIX)) {
            steps.filter { it.id !in claimed && it.at >= use.at }.forEach { claimed += it.id; emit(use.order, PathItem.Browser(it.at, runId, depth, it)) }
        }
        emit(use.order, actionItem(runId, depth, use.name, use.input, use.at, null, false, redact))
    }
    // Steps no call claimed (the journal and the audit trail disagree on time): by time, at the end of their run.
    steps.filter { it.id !in claimed }.forEach { emit(Int.MAX_VALUE, PathItem.Browser(it.at, runId, depth, it)) }
    return out.withIndex().sortedWith(compareBy({ it.value.first }, { it.index })).map { it.value.second }
}

private fun ChildRun.toItem(at: Instant, parent: String, depth: Int) =
    PathItem.Helper(at, parent, depth, id, task, status, role, steps, browserSteps, finishedAt)

/** Pure: what an inbox delivery says, line by line, untrusted quotes left out. */
private fun inboxLines(text: String): List<Pair<PathItem.Heard.Source, String>> =
    text.lineSequence().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.map { line ->
        val helper = INBOX_HELPER.find(line)
        when {
            helper != null -> PathItem.Heard.Source.HELPER to "A helper ${helper.groupValues[2]}: ${helper.groupValues[3]}"
            else -> PathItem.Heard.Source.EVENT to line
        }
    }.toList()

/** Pure: a tool call as a friendly line, with a short detail and its outcome. [result] null: still running. */
fun actionItem(
    runId: String,
    depth: Int,
    tool: String,
    input: JsonObject,
    at: Instant,
    result: String?,
    isError: Boolean,
    redact: PathRedactor,
): PathItem.Action {
    val outcome = when {
        result == null -> Outcome.RUNNING
        isError -> Outcome.ERROR
        result.trimStart().startsWith("Held for", ignoreCase = true) -> Outcome.HELD
        else -> Outcome.OK
    }
    val (line, detail) = describeCall(tool, input, if (outcome == Outcome.OK) result else null)
    // A vault call never shows an error text either: it could name what was asked for.
    val shown = when {
        archiveExcluded(tool) -> null
        outcome == Outcome.ERROR || outcome == Outcome.HELD -> result?.let { redact.text(it, 160) }
        else -> detail?.let { redact.text(it) }
    }
    return PathItem.Action(at, runId, depth, tool, line, shown?.ifBlank { null }, outcome)
}

/** Keys of tool inputs that may carry typed or written content: never shown. */
private val HIDDEN_KEYS = setOf("value", "text", "body", "content", "cards", "password", "code", "secret", "values", "fields", "html", "script", "function")

/** Pure: a short line for a call of [tool] and an optional detail, from its input and (when it worked) its result. */
fun describeCall(tool: String, input: JsonObject, result: String?): Pair<String, String?> {
    fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> (input[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
    fun count(noun: String, verb: String, fallback: String): String =
        jsonCount(result)?.let { n -> "$verb $n ${if (n == 1) noun else noun + "s"}" } ?: fallback
    val browser = tool.removePrefix(BrowserGate.PREFIX).takeIf { tool.startsWith(BrowserGate.PREFIX) }
    if (browser != null) return when (browser) {
        "look" -> "Looked at the page" to null
        "read" -> "Read the page" to null
        "tabs" -> "Checked the tabs" to null
        "go" -> "Opened a page" to arg("url")
        "back" -> "Went back a page" to null
        "wait" -> "Waited for the page" to arg("text")
        "sign_in" -> "Signed in" to null
        "act" -> "Did a step on the page" to arg("intent")
        "screenshot" -> "Took a screenshot" to null
        "downloads" -> "Checked downloads" to null
        else -> "Used the browser" to null
    }
    if (archiveExcluded(tool)) return when (tool) {
        "ask_code" -> "Asked you for a code"
        "request_credential" -> "Asked you for a login"
        "list_credentials" -> "Checked saved logins"
        else -> "Used the vault"
    } to null
    return when (tool) {
        "send_to_user" -> "Sent you a message" to (input["cards"]?.let { feedText(it.toString()) })
        "update_task" -> (if (input["id"] == null) "Added a task" else "Updated the task") to listOfNotNull(arg("title"), arg("detail")).joinToString(" · ").ifEmpty { null }
        "delegate" -> "Started a helper" to null
        "list_helpers" -> "Checked on helpers" to null
        "message_helper" -> "Messaged a helper" to null
        "cancel_helper" -> "Stopped a helper" to null
        "list_messages" -> count("message", "Read", "Checked mail") to arg("participant", "folder")
        "list_calendar_events" -> count("event", "Found", "Checked your calendar") to arg("date", "from")
        "search_contacts" -> count("contact", "Found", "Looked up contacts") to arg("query")
        "search_memory" -> "Searched memory" to arg("query")
        "read_memory" -> "Read from memory" to null
        "open_collection" -> "Opened notes" to arg("name", "collection")
        "read_journal" -> "Read the journal" to null
        "read_profile" -> "Read your profile" to null
        "read_result" -> "Read a saved result" to null
        "search_documents" -> count("document", "Found", "Searched documents") to arg("query")
        "read_document" -> "Read a document" to arg("path", "title")
        "save_draft" -> "Drafted an email" to arg("subject")
        "send_email" -> "Sent an email" to arg("subject")
        "create_event" -> "Added an event" to listOfNotNull(arg("title", "summary"), arg("start")).joinToString(" · ").ifEmpty { null }
        "move_event" -> "Moved an event" to arg("start")
        "respond_to_invite" -> "Answered an invite" to arg("response")
        "create_reminder" -> "Set a reminder" to listOfNotNull(arg("title"), arg("due", "at", "time")).joinToString(" · ").ifEmpty { null }
        "set_do_not_disturb" -> "Changed Do Not Disturb" to null
        "remember_fact" -> "Remembered something about you" to null
        "write_journal" -> "Wrote in the journal" to null
        "set_scratch" -> "Made a short note" to null
        "save_note" -> "Saved a note" to arg("collection")
        "manage_collection" -> "Organized notes" to null
        "replace_profile" -> "Updated your profile" to null
        "create_trigger" -> "Set up a watch" to arg("name")
        "delete_trigger" -> "Removed a watch" to null
        "list_triggers" -> "Checked watches" to null
        else -> "Used " + tool.replace("__", " ").replace('_', ' ') to safeArgs(input)
    }
}

/** Pure: the number of entries in a JSON array result; null when the result is not one. */
private fun jsonCount(result: String?): Int? {
    val text = result?.trim() ?: return null
    if (!text.startsWith("[")) return null
    return (runCatching { ClarityJson.parseToJsonElement(text) }.getOrNull() as? JsonArray)?.size
}

/** Pure: the short, plain arguments of a call (`key: value`), anything that may carry content left out. */
private fun safeArgs(input: JsonObject): String? = input.entries
    .filter { (k, v) -> k.lowercase() !in HIDDEN_KEYS && v is JsonPrimitive && v.isString }
    .take(3)
    .joinToString(", ") { (k, v) -> "$k: ${(v as JsonPrimitive).content.take(60)}" }
    .ifEmpty { null }
