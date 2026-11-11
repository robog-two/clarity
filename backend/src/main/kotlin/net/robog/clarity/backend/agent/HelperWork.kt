package net.robog.clarity.backend.agent

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role

/*
 * How the lead steers a helper after it started, as pure functions: task changes and revisions
 * (delivery exactly once, judged from the journal), done criteria and their check, questions the
 * helper asks the lead, long results, and the compact view the lead inspects. HelperManager only
 * wires them to the run store, the journal and the model.
 */

/** What the lead sent a helper after it started. */
enum class ChangeKind(val wire: String, val label: String) {
    /** The task changed while the helper works. */
    CHANGE("change", "task change"),

    /** Feedback on a finished result; the helper works again. */
    REVISION("revision", "revision");

    companion object {
        fun of(wire: String): ChangeKind = entries.firstOrNull { it.wire == wire } ?: CHANGE
    }
}

/** One thing the lead sent a helper: [seq] counts within the run. */
data class TaskChange(val seq: Int, val kind: ChangeKind, val text: String, val at: Instant)

/** The tag that makes a delivered change findable in the journal. */
fun changeMarker(change: TaskChange): String = "[${change.kind.label} #${change.seq}]"

/** The user message a helper reads for [change]. */
fun changeMessage(change: TaskChange): String = when (change.kind) {
    ChangeKind.CHANGE -> "${changeMarker(change)} Change to your task from the lead: ${change.text}\n" +
        "Keep what you already did unless this says otherwise."
    ChangeKind.REVISION -> "${changeMarker(change)} Feedback from the lead on your result: ${change.text}\n" +
        "Fix what it names, then write your result again in full."
}

private fun userTexts(journal: List<Message>): List<String> =
    journal.filter { it.role == Role.USER }.flatMap { it.content }.filterIsInstance<Block.Text>().map { it.text }

/**
 * Pure: the [changes] the helper has not read yet. A change counts as read once its marker is in a
 * user message of the [journal], so a resume (a restart, a queued start) never sends one twice.
 */
fun undeliveredChanges(changes: List<TaskChange>, journal: List<Message>): List<TaskChange> {
    val texts = userTexts(journal)
    return changes.filter { change -> texts.none { changeMarker(change) in it } }
}

/** Pure: whether the journal ends on a finished assistant turn (no open tool calls), where a user message can be appended. */
fun endsOnAssistantTurn(journal: List<Message>): Boolean =
    journal.lastOrNull()?.let { it.role == Role.ASSISTANT && it.content.none { b -> b is Block.ToolUse } } == true

/** Pure: whether [message] carries a revision's feedback; the step budget of a revision counts from the latest. */
fun isRevisionFeedback(message: Message): Boolean =
    message.role == Role.USER && message.content.any { it is Block.Text && "[revision #" in it.text }

/** Done criteria as stored: trimmed, no blanks or repeats, each cut to [DONE_WHEN_CLIP] characters, at most [DONE_WHEN_MAX]. */
fun normalizeDoneWhen(items: List<String>?): List<String> =
    items.orEmpty().map { clip(it.replace(Regex("\\s+"), " ").trim(), DONE_WHEN_CLIP) }.filter { it.isNotEmpty() }.distinct().take(DONE_WHEN_MAX)

const val DONE_WHEN_CLIP = 200
const val DONE_WHEN_MAX = 8

/** Pure: the lines of the task prompt that list the done criteria; empty without any. */
fun doneWhenBlock(doneWhen: List<String>): String =
    if (doneWhen.isEmpty()) "" else "\n\nDone when:\n" + doneWhen.mapIndexed { i, item -> "${i + 1}. $item" }.joinToString("\n") +
        "\nYour result answers each item by number: met or not met, and why."

/**
 * Pure: why run [run] cannot be revised now, or null. Only a finished (done) helper can: a running
 * one takes `message_helper`, a failed or cancelled one starts over with `delegate`. A revision
 * works within the run's spend cap, so one that spent nearly all of it is refused too.
 */
fun revisionRefusal(run: HelperRun?, id: String, usedMicros: Long, capMicros: Long, maxRevisions: Int): String? = when {
    run == null -> "No helper with id $id."
    run.status in RunStatus.OPEN -> "Helper $id is still working. Change its task with message_helper."
    run.status != RunStatus.DONE ->
        "Helper $id ${run.status}, so there is no finished work to revise. Start a new one with delegate and say why in retryReason."
    run.profile != null -> "Helper $id cannot be revised."
    run.revisions >= maxRevisions ->
        "Helper $id was already revised ${run.revisions} times, the limit. Use its result as it is, or delegate again and say why in retryReason."
    capMicros - usedMicros < capMicros / 10 ->
        "Helper $id used nearly all of its spend limit, so it cannot work more. Use its result, or delegate again and say why in retryReason."
    else -> null
}

/** One question a helper asked the lead. */
data class HelperQuestion(
    val seq: Int,
    val toolUseId: String,
    val text: String,
    val askedAt: Instant,
    val answer: String? = null,
    val answeredAt: Instant? = null,
    val timedOut: Boolean = false,
) {
    /** Still waiting for the lead. */
    val open: Boolean get() = answer == null && !timedOut
}

/** What the helper reads when the wait ends without an answer. */
const val NO_ANSWER: String = "No answer came. Use your best judgment, and say in your result what you assumed."

/** Pure: what the helper reads for the lead's [answer]. */
fun answerText(answer: String): String = "Answer from the lead: $answer"

/** Pure: why a helper that asked [asked] questions already may not ask another, or null. */
fun askRefusal(asked: Int, max: Int): String? =
    if (asked >= max) "You already asked $max questions, the limit. Use your best judgment and say in your result what you assumed." else null

/** Pure: how long a question asked at [askedAt] still waits, given the [timeout]; never negative. */
fun askWaitLeft(askedAt: Instant, now: Instant, timeout: Duration): Duration = (timeout - (now - askedAt)).coerceAtLeast(Duration.ZERO)

/** The longest helper result the lead's inbox shows in full. */
const val RESULT_EXCERPT_CHARS = 3000

/**
 * Pure: [text] as the lead's inbox shows it. A result over [max] keeps its beginning and its end
 * (a conclusion usually closes it) with the gap marked; the full text stays in the run.
 */
fun excerptResult(text: String, max: Int = RESULT_EXCERPT_CHARS): String {
    if (text.length <= max) return text
    val head = max * 2 / 3
    val tail = max - head
    val left = text.length - head - tail
    return text.take(head).trimEnd() + "\n[… $left characters left out …]\n" + text.takeLast(tail).trimStart()
}

/** Pure: what the lead is told about a result [text] it sees cut, or null when it sees all of it. */
fun fullResultHint(runId: String, text: String, max: Int = RESULT_EXCERPT_CHARS): String? =
    if (text.length <= max) null
    else "The result is ${text.length} characters and shown cut. Read all of it with inspect_helper (id $runId, full true)."

/** A done criterion's verdict from the automatic check. */
@Serializable
enum class Verdict {
    @SerialName("met") MET,
    @SerialName("unmet") UNMET,
    @SerialName("unclear") UNCLEAR,
}

/** The automatic check of one done criterion against the result. */
@Serializable
data class CriterionCheck(val criterion: String, val verdict: Verdict, val reason: String)

/** The longest reason a check keeps per criterion. */
const val CHECK_REASON_CLIP = 160

/**
 * Pure: the check's verdicts from the model's [json], strictly: an object whose `criteria` array
 * gives every criterion number (1 to [criteria].size) exactly once with a known verdict and a
 * reason. Anything else is null (check unavailable).
 */
fun parseCriteriaCheck(json: JsonElement?, criteria: List<String>): List<CriterionCheck>? {
    val items = ((json as? JsonObject)?.get("criteria") as? JsonArray) ?: return null
    if (items.size != criteria.size) return null
    val parsed = items.map { item ->
        val o = item as? JsonObject ?: return null
        val number = (o["number"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull() ?: return null
        val verdict = when ((o["verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull) {
            "met" -> Verdict.MET
            "unmet" -> Verdict.UNMET
            "unclear" -> Verdict.UNCLEAR
            else -> return null
        }
        val reason = (o["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        number to (verdict to reason)
    }
    if (parsed.map { it.first }.sorted() != criteria.indices.map { it + 1 }) return null
    return parsed.sortedBy { it.first }.map { (number, v) ->
        CriterionCheck(criteria[number - 1], v.first, clip(v.second.replace(Regex("\\s+"), " ").trim(), CHECK_REASON_CLIP))
    }
}

/** Pure: the check as lines for the lead. */
fun formatCriteriaCheck(checks: List<CriterionCheck>): String = checks.mapIndexed { i, c ->
    "${i + 1}. ${when (c.verdict) { Verdict.MET -> "met"; Verdict.UNMET -> "not met"; Verdict.UNCLEAR -> "unclear" }}: ${c.criterion}" +
        if (c.reason.isBlank()) "" else " (${c.reason})"
}.joinToString("\n")

/** Pure: the check in a few words, for the inbox summary. */
fun checkSummary(checks: List<CriterionCheck>): String {
    val met = checks.count { it.verdict == Verdict.MET }
    val unmet = checks.count { it.verdict == Verdict.UNMET }
    val unclear = checks.count { it.verdict == Verdict.UNCLEAR }
    return "$met of ${checks.size} met" + (if (unmet > 0) ", $unmet not met" else "") + (if (unclear > 0) ", $unclear unclear" else "")
}

// -- The compact view of a helper's work ------------------------------------------------------------

private val REPORT_CREATED = Regex("Report ([0-9a-f]{8}-[0-9a-f-]{27}) created")

/** The tools whose calls reach the outside world through the guard. */
private val GUARDED_TOOLS = setOf(
    "save_draft", "send_email", "create_event", "move_event", "respond_to_invite", "create_reminder", "set_do_not_disturb",
)

/** One tool as the helper used it: how often, how many failed, and the arguments of its first and last call. */
data class ToolTally(val name: String, val count: Int, val errors: Int, val first: String?, val last: String?)

private val redactor = PathRedactor()

/** Pure: the first plain argument of a call, short, as a one-line hint of what it did; null when it has none. */
fun argHint(input: JsonObject, max: Int = 50): String? {
    val value = input.entries.firstNotNullOfOrNull { (_, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } } ?: return null
    return redactor.text(value, max)
}

/** Pure: the tools called in [journal], in order of first use, with calls of one tool collapsed. */
fun toolTally(journal: List<Message>): List<ToolTally> {
    val blocks = journal.flatMap { it.content }
    val results = blocks.filterIsInstance<Block.Result>().associateBy { it.toolUseId }
    return blocks.filterIsInstance<Block.ToolUse>().filter { !archiveExcluded(it.name) }.groupBy { it.name }.map { (name, calls) ->
        ToolTally(name, calls.size, calls.count { results[it.id]?.isError == true }, argHint(calls.first().input), argHint(calls.last().input).takeIf { calls.size > 1 })
    }
}

/** Pure: the guarded actions the helper proposed, as "tool: first line of what came back" (the guard's answer says done, queued or held). */
fun guardedActions(journal: List<Message>): List<String> {
    val blocks = journal.flatMap { it.content }
    val results = blocks.filterIsInstance<Block.Result>().associateBy { it.toolUseId }
    return blocks.filterIsInstance<Block.ToolUse>().filter { it.name in GUARDED_TOOLS }.map { call ->
        val outcome = results[call.id]?.content?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "no answer yet" }
        "${call.name}${argHint(call.input, 40)?.let { " ($it)" }.orEmpty()}: ${redactor.text(outcome, 90)}"
    }
}

/** Pure: ids of the reports the helper created, in order. */
fun reportsCreated(journal: List<Message>): List<String> {
    val blocks = journal.flatMap { it.content }
    val creating = blocks.filterIsInstance<Block.ToolUse>().filter { it.name == "create_report" }.map { it.id }.toSet()
    return blocks.filterIsInstance<Block.Result>().filter { it.toolUseId in creating && !it.isError }
        .mapNotNull { REPORT_CREATED.find(it.content)?.groupValues?.get(1) }
}

/** Pure: the helper's latest scratch note, if it wrote one. */
fun scratchNote(journal: List<Message>): String? =
    journal.flatMap { it.content }.filterIsInstance<Block.ToolUse>().lastOrNull { it.name == "set_scratch" }
        ?.let { (it.input["text"] as? JsonPrimitive)?.contentOrNull }

/** Pure: the helper's last words (the final assistant text), or null. */
fun lastText(journal: List<Message>): String? =
    journal.lastOrNull { m -> m.role == Role.ASSISTANT && m.content.any { it is Block.Text && it.text.isNotBlank() } }
        ?.content?.filterIsInstance<Block.Text>()?.joinToString("") { it.text }?.trim()

/** Pure: model calls the run made so far (one assistant message each). */
fun stepsTaken(journal: List<Message>): Int = journal.count { it.role == Role.ASSISTANT }

/** Everything the inspect view is built from. */
data class InspectView(
    val run: HelperRun,
    val journal: List<Message>,
    val costMicros: Long,
    val capMicros: Long,
    val maxSteps: Int,
    val maxRevisions: Int,
    val changes: List<TaskChange> = emptyList(),
    val questions: List<HelperQuestion> = emptyList(),
)

/** The most characters [inspectHelper] gives the lead. */
const val INSPECT_MAX_CHARS = 3000

private fun dollars(micros: Long): String = "$" + "%.2f".format(java.util.Locale.ROOT, micros / 1_000_000.0)

/**
 * Pure: a compact view of a helper's work for the lead, running or finished, at most [limit]
 * characters. What the lead wrote itself (task, criteria, changes) comes first; everything the
 * helper produced (its words, the arguments of its calls, its note, the check's reasons) sits in
 * one `<untrusted>` quote at the end, which shrinks to fit.
 */
fun inspectHelper(view: InspectView, limit: Int = INSPECT_MAX_CHARS): String {
    val run = view.run
    val unread = undeliveredChanges(view.changes, view.journal).map { it.seq }.toSet()
    val own = buildString {
        append("Helper ").append(run.id).append(" [").append(run.status).append("] ").append(run.role).append('\n')
        append("Task: ").append(clip(run.task.replace(Regex("\\s+"), " "), 160)).append('\n')
        append("Steps ").append(stepsTaken(view.journal)).append(" of ").append(view.maxSteps)
            .append(", cost ").append(dollars(view.costMicros)).append(" of ").append(dollars(view.capMicros))
            .append(", revisions ").append(run.revisions).append(" of ").append(view.maxRevisions).append('\n')
        view.questions.firstOrNull { it.open }?.let { append("Waiting for your answer to its question (answer with message_helper).\n") }
        if (run.doneWhen.isNotEmpty()) {
            append("Done when:\n")
            run.doneWhen.take(6).forEachIndexed { i, item -> append(i + 1).append(". ").append(clip(item, 80)).append('\n') }
        }
        if (view.changes.isNotEmpty()) {
            append("Changes you sent:\n")
            view.changes.takeLast(4).forEach { c ->
                append("- ").append(c.kind.label).append(" #").append(c.seq).append(if (c.seq in unread) " not read yet: " else " read: ")
                    .append(clip(c.text.replace(Regex("\\s+"), " "), 100)).append('\n')
            }
        }
        if (run.status == RunStatus.DONE) {
            val length = run.result?.length ?: 0
            if (length > 0) append("Result: ").append(length).append(" characters (inspect_helper with full true reads it).\n")
        }
    }
    val tools = toolTally(view.journal)
    val guarded = guardedActions(view.journal)
    val reports = reportsCreated(view.journal)
    val theirs = buildList {
        lastText(view.journal)?.let { add("Last text: " + clip(it.replace(Regex("\\s+"), " "), 400)) }
        if (tools.isNotEmpty()) {
            add(
                "Tools called: " + tools.take(12).joinToString("; ") { t ->
                    t.name + (if (t.count > 1) " x${t.count}" else "") + (if (t.errors > 0) " (${t.errors} failed)" else "") +
                        (t.first?.let { " [$it${t.last?.let { l -> " … $l" }.orEmpty()}]" }.orEmpty())
                } + (if (tools.size > 12) "; and ${tools.size - 12} more" else ""),
            )
        }
        if (guarded.isNotEmpty()) add("Guarded actions: " + guarded.take(5).joinToString("; "))
        if (reports.isNotEmpty()) add("Reports created: " + reports.take(5).joinToString(", "))
        scratchNote(view.journal)?.let { add("Its note: " + clip(it.replace(Regex("\\s+"), " "), 200)) }
        view.questions.lastOrNull()?.let { q ->
            add(
                "Question ${q.seq}: " + clip(q.text.replace(Regex("\\s+"), " "), 200) +
                    when {
                        q.answer != null -> " (you answered)"
                        q.timedOut -> " (no answer came)"
                        else -> " (open)"
                    },
            )
        }
        if (run.check.isNotEmpty()) add("Check of the done criteria: " + formatCriteriaCheck(run.check).replace('\n', ' ').let { clip(it, 400) })
    }
    val room = (limit - own.length - UNTRUSTED_WRAP).coerceAtLeast(MIN_UNTRUSTED)
    val quoted = if (theirs.isEmpty()) "" else quoteUntrusted(UntrustedPart("helper:${run.id}", clip(theirs.joinToString("\n"), room)))
    return (own + quoted).trimEnd()
}

/** The room kept for the quote's tags around the helper's text in [inspectHelper]. */
private const val UNTRUSTED_WRAP = 60
private const val MIN_UNTRUSTED = 300

/** Pure: the work log the automatic check reads: the same facts as [inspectHelper], without quoting. */
fun workLog(journal: List<Message>, max: Int = 1500): String {
    val tools = toolTally(journal)
    val guarded = guardedActions(journal)
    val reports = reportsCreated(journal)
    val text = buildList {
        add("Steps taken: ${stepsTaken(journal)}")
        if (tools.isNotEmpty()) add("Tools called: " + tools.joinToString("; ") { it.name + (if (it.count > 1) " x${it.count}" else "") + (if (it.errors > 0) " (${it.errors} failed)" else "") })
        if (guarded.isNotEmpty()) add("Guarded actions: " + guarded.joinToString("; "))
        if (reports.isNotEmpty()) add("Reports created: " + reports.joinToString(", "))
    }.joinToString("\n")
    return clip(text, max)
}

/** Pure: one line per helper for `list_helpers`, with its changes and whether the helper read them. */
fun helperListing(run: HelperRun, changes: List<TaskChange>, journal: List<Message>): String = buildString {
    append(run.id).append(" [").append(run.status).append(']')
    if (run.revisions > 0) append(" (revised ").append(run.revisions).append("x)")
    append(' ').append(run.task.take(160).replace('\n', ' '))
    if (run.doneWhen.isNotEmpty()) append(" | done when: ").append(run.doneWhen.joinToString("; ") { clip(it, 60) })
    val pending = undeliveredChanges(changes, journal).map { it.seq }.toSet()
    changes.takeLast(5).forEach { c ->
        append("\n  ").append(c.kind.label).append(" #").append(c.seq).append(if (c.seq in pending) " (not read yet): " else " (read): ")
            .append(clip(c.text.replace(Regex("\\s+"), " "), 100))
    }
}
