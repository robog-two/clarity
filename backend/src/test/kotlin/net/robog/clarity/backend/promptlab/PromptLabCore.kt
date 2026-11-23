package net.robog.clarity.backend.promptlab

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.OPERATOR_UPDATE_PLACEHOLDER
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.SUMMARY_MARKER

/*
 * The prompt lab's pure core: cutting the lead's journal at a `send_to_user`, classifying what
 * triggered it, picking a spread of scenarios, rendering them as text and parsing the person's
 * answers. No I/O here; the psql and OpenRouter edges are in PromptLabIo.kt.
 */

const val SEND_TO_USER = "send_to_user"

enum class ScenarioType(val slug: String, val label: String) {
    REPLY("reply", "Reply to a request or question"),
    CHAT("chat", "Reply to light chat"),
    HELPER_RESULT("helper", "Report of a finished helper"),
    CHECK_IN("checkin", "Check-in from a nudge"),
    LOOK_FORWARD("lookfwd", "Look forward"),
    FINDING("finding", "Finding from an operator update"),
    HELD("held", "Held, blocked or declined"),
    UNFINISHED("unfinished", "A task did not finish"),
    SESSION("session", "Session message (values interview or morning call)"),
    OTHER("other", "Other"),
}

/** What the lead was last told by the person or the operator before it called `send_to_user`. */
data class Trigger(val index: Int, val role: Role, val text: String)

private val IGNORED_TRIGGER_PREFIXES = listOf("[memory", "[mcp servers]", "[clarity]")
private val CLOCK_ONLY = Regex("""^\[now [^\]]*]\s*$""")

private fun Message.text(): String = content.filterIsInstance<Block.Text>().joinToString("\n") { it.text }

/**
 * Pure: the latest message of [messages] that speaks to the lead: the person's words or an operator
 * update. Tool results, the lead's own turns, memory context, the reply nudge and bare clock lines
 * do not count.
 */
fun triggerOf(messages: List<Message>): Trigger? {
    for (i in messages.indices.reversed()) {
        val m = messages[i]
        if (m.role == Role.ASSISTANT) continue
        val text = m.text().trim()
        if (text.isEmpty()) continue
        if (m.role == Role.SYSTEM && (IGNORED_TRIGGER_PREFIXES.any(text::startsWith) || CLOCK_ONLY.matches(text))) continue
        if (m.isSummary || text == OPERATOR_UPDATE_PLACEHOLDER) continue
        return Trigger(i, m.role, text)
    }
    return null
}

/** Pure: true when the call was made right after the harness refused `send_to_user`'s input (a retry, not a fresh moment). */
fun isRetryOfInvalidInput(messages: List<Message>): Boolean =
    messages.lastOrNull()?.content?.filterIsInstance<Block.Result>()?.any { it.content.startsWith("Invalid input for $SEND_TO_USER") } == true

fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/** Pure: which kind of moment this `send_to_user` answered. */
fun classify(kind: String?, sessionId: String?, trigger: Trigger?): ScenarioType {
    if (sessionId != null) return ScenarioType.SESSION
    val text = trigger?.text.orEmpty()
    return when {
        trigger?.role == Role.SYSTEM && "Nudge you set at" in text -> ScenarioType.CHECK_IN
        kind == "check_in" -> ScenarioType.CHECK_IN
        kind == "look_forward" -> ScenarioType.LOOK_FORWARD
        kind == "finding" -> ScenarioType.FINDING
        trigger == null -> ScenarioType.OTHER
        trigger.role == Role.USER -> if (wordCount(text) <= 8 && '?' !in text) ScenarioType.CHAT else ScenarioType.REPLY
        " failed its task" in text -> ScenarioType.UNFINISHED
        " finished its task" in text -> ScenarioType.HELPER_RESULT
        "Could not do:" in text || "ruled on the held action" in text -> ScenarioType.HELD
        "Scheduled job" in text -> ScenarioType.FINDING
        else -> ScenarioType.OTHER
    }
}

/** A `send_to_user` in the journal that could become a scenario. */
data class Candidate(
    val seq: Int,
    val at: Instant,
    val type: ScenarioType,
    val sessionId: String?,
    val triggerKey: Int?,
    val snippet: String,
)

/**
 * Pure: one candidate per `send_to_user` in [journal] (seq to message, in order) at or after [from]. The context of each is
 * [contextOf] the journal before it. [times] gives each seq's instant. Retries of refused inputs are left out.
 */
fun candidatesIn(
    journal: List<Pair<Int, Message>>,
    times: Map<Int, Instant>,
    from: Instant,
    contextSince: (List<Message>) -> List<Message>,
): List<Candidate> {
    val out = ArrayList<Candidate>()
    journal.forEachIndexed { i, (seq, message) ->
        val at = times[seq] ?: return@forEachIndexed
        if (at < from || message.role != Role.ASSISTANT) return@forEachIndexed
        val send = message.content.filterIsInstance<Block.ToolUse>().firstOrNull { it.name == SEND_TO_USER } ?: return@forEachIndexed
        val context = contextSince(journal.subList(0, i).map { it.second })
        if (isRetryOfInvalidInput(context)) return@forEachIndexed
        val trigger = triggerOf(context)
        val kind = send.input["kind"]?.jsonPrimitive?.contentOrNull
        val sessionId = send.input["sessionId"]?.jsonPrimitive?.contentOrNull
        out += Candidate(
            seq, at, classify(kind, sessionId, trigger), sessionId,
            trigger?.let { journal.getOrNull(i - (context.size - it.index))?.first },
            (trigger?.text ?: "").lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("[inbox") }.orEmpty().take(110),
        )
    }
    return out
}

/** The default mix of the 20 scenarios. */
val DEFAULT_QUOTAS: Map<ScenarioType, Int> = linkedMapOf(
    ScenarioType.REPLY to 5,
    ScenarioType.CHAT to 2,
    ScenarioType.HELPER_RESULT to 3,
    ScenarioType.CHECK_IN to 2,
    ScenarioType.LOOK_FORWARD to 2,
    ScenarioType.FINDING to 1,
    ScenarioType.HELD to 1,
    ScenarioType.UNFINISHED to 1,
    ScenarioType.SESSION to 3,
)

data class Selection(val picked: List<Candidate>, val shortfalls: Map<ScenarioType, Int>)

/**
 * Pure: [quotas] worth of candidates, a spread of distinct moments: each type takes candidates spread evenly over its pool in
 * time (the pool is the ones since [recentSince] first, older ones only when that is not enough); no two picks share a trigger,
 * lie within [MIN_GAP] of each other, or (for sessions) belong to one session. Shortfalls say which types came up short.
 */
fun selectScenarios(candidates: List<Candidate>, quotas: Map<ScenarioType, Int> = DEFAULT_QUOTAS, recentSince: Instant): Selection {
    val picked = ArrayList<Candidate>()
    val shortfalls = LinkedHashMap<ScenarioType, Int>()
    fun fits(c: Candidate, type: ScenarioType) = picked.none { p ->
        p.seq == c.seq ||
            (c.triggerKey != null && p.triggerKey == c.triggerKey) ||
            (if (c.at > p.at) c.at - p.at else p.at - c.at) < MIN_GAP ||
            (type == ScenarioType.SESSION && p.type == type && p.sessionId == c.sessionId)
    }
    for ((type, quota) in quotas) {
        val mine = ArrayList<Candidate>()
        for (pool in listOf(candidates.filter { it.type == type && it.at >= recentSince }, candidates.filter { it.type == type && it.at < recentSince }.asReversed())) {
            val ordered = pool.sortedBy { it.at }.ifEmpty { continue }
            // Evenly spread: step through the pool at quota-sized strides, then take whatever else still fits.
            val stride = (ordered.size.toDouble() / quota).coerceAtLeast(1.0)
            val order = (0 until quota).map { (it * stride + stride / 2).toInt().coerceIn(0, ordered.size - 1) }.distinct().mapNotNull(ordered::getOrNull) +
                ordered.asReversed()
            for (c in order) {
                if (mine.size >= quota) break
                if (c in mine || !fits(c, type) || mine.any { m -> m.triggerKey != null && m.triggerKey == c.triggerKey }) continue
                if (mine.any { m -> (if (c.at > m.at) c.at - m.at else m.at - c.at) < MIN_GAP }) continue
                if (type == ScenarioType.SESSION && mine.any { it.sessionId == c.sessionId }) continue
                mine += c
            }
            if (mine.size >= quota) break
        }
        picked += mine
        if (mine.size < quota) shortfalls[type] = quota - mine.size
    }
    return Selection(picked.sortedBy { it.at }, shortfalls)
}

private val MIN_GAP = 30.minutes

// ---- rendering --------------------------------------------------------------------------------------------------------

/** Pure: the cards of a `send_to_user` input as readable text (text cards, the choices prompt and options, other cards by their text). */
fun renderCards(input: JsonObject): String {
    val parts = ArrayList<String>()
    val cards = (input["cards"] as? JsonArray).orEmpty()
    for (card in cards) {
        val c = card as? JsonObject ?: continue
        when (c["type"]?.jsonPrimitive?.contentOrNull) {
            "choices" -> {
                val prompt = c["prompt"]?.jsonPrimitive?.contentOrNull
                val options = (c["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull }
                parts += buildString {
                    append("[choices] ").append(prompt.orEmpty())
                    if (options.isNotEmpty()) append(if (prompt.isNullOrBlank()) "" else " ").append(options.joinToString(" | ") { "($it)" })
                }.trim()
            }
            "sources" -> parts += "[sources] " + (c["items"] as? JsonArray).orEmpty().size + " item(s)"
            "text", null -> c["text"]?.jsonPrimitive?.contentOrNull?.let(parts::add)
            else -> parts += "[${c["type"]!!.jsonPrimitive.content}] " + (c["text"] ?: c["title"] ?: c["label"] ?: JsonPrimitive("")).jsonPrimitive.contentOrNull.orEmpty()
        }
    }
    val kind = input["kind"]?.jsonPrimitive?.contentOrNull
    val urgency = input["urgency"]?.jsonPrimitive?.contentOrNull
    val tags = listOfNotNull(kind?.let { "kind: $it" }, urgency?.let { "urgency: $it" })
    return (parts + listOfNotNull(tags.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "($it)" })).joinToString("\n\n")
}

/** Pure: the words the person reads, without kind/urgency tags and the non-prose cards. */
fun proseWords(input: JsonObject): Int = wordCount(
    (input["cards"] as? JsonArray).orEmpty().joinToString(" ") { card ->
        val c = card as? JsonObject
        when (c?.get("type")?.jsonPrimitive?.contentOrNull) {
            "choices" -> listOfNotNull(
                c["prompt"]?.jsonPrimitive?.contentOrNull,
                *(c["options"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull }.toTypedArray(),
            ).joinToString(" ")
            "text", null -> c?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            else -> ""
        }
    },
)

/** System messages the harness delivers as background (memory, notes index, MCP list); shown as one-liners. */
private val BACKGROUND_PREFIXES = listOf("[memory", "[mcp servers]")

private const val SUMMARY_MAX = 1200

private fun String.cut(max: Int): String = if (length <= max) this else take(max).trimEnd() + "…"

private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

private fun argsLine(input: JsonObject): String = oneLine(input.entries.joinToString(", ") { (k, v) ->
    "$k=" + ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
})

/** Pure: one lead message as short lines (the person's and operator's words in full up to [textMax], tools as one-liners). */
fun condense(message: Message, resultNames: Map<String, String>, textMax: Int = 700, resultMax: Int = 300): List<String> {
    val lines = ArrayList<String>()
    when (message.role) {
        Role.USER, Role.SYSTEM -> {
            val who = if (message.role == Role.USER) "Person" else "Operator"
            val text = message.text().trim()
            if (message.isSummary) lines += "Summary of earlier conversation: ${oneLine(text.removePrefix(SUMMARY_MARKER)).cut(SUMMARY_MAX)}"
            else if (message.role == Role.SYSTEM && BACKGROUND_PREFIXES.any(text::startsWith)) lines += "Operator: (${text.substringBefore(']').removePrefix("[")} context loaded, ${text.length} chars)"
            else if (text.isNotEmpty()) lines += "$who: ${text.cut(textMax)}"
            for (b in message.content) if (b is Block.Result) {
                val name = resultNames[b.toolUseId] ?: "tool"
                lines += "   -> ${name} ${if (b.isError) "error: " else ""}${oneLine(b.content).cut(resultMax)}"
            }
        }
        Role.ASSISTANT -> for (b in message.content) if (b is Block.ToolUse) {
            lines += if (b.name == SEND_TO_USER) "Clarity sent: ${renderCards(b.input).replace("\n\n", " / ").cut(textMax)}"
            else "Clarity called ${b.name}(${argsLine(b.input).cut(160)})"
        }
        else -> Unit
    }
    return lines
}

/** Pure: tool use id to tool name over [messages], for labelling results. */
fun toolNames(messages: List<Message>): Map<String, String> =
    messages.flatMap { it.content }.filterIsInstance<Block.ToolUse>().associate { it.id to it.name }

/**
 * Pure: the readable "what it was given" of a scenario: the earlier exchanges condensed (about [history] spoken lines back),
 * the trigger in full, and what the lead did after it up to the cut.
 */
fun renderGiven(messages: List<Message>, history: Int = 10): String {
    val names = toolNames(messages)
    val trigger = triggerOf(messages) ?: return messages.flatMap { condense(it, names) }.joinToString("\n")
    var start = trigger.index
    var spoken = 0
    while (start > 0 && spoken < history) {
        start--
        val m = messages[start]
        if (m.role != Role.ASSISTANT && m.text().isNotBlank() && !m.isSummary && BACKGROUND_PREFIXES.none(m.text()::startsWith)) spoken++
        if (m.role == Role.ASSISTANT && m.content.any { it is Block.ToolUse && it.name == SEND_TO_USER }) spoken++
    }
    val earlier = (start until trigger.index).flatMap { condense(messages[it], names) }
    val after = (trigger.index + 1 until messages.size).flatMap { condense(messages[it], names) }
    return buildString {
        if (earlier.isNotEmpty()) {
            appendLine("EARLIER (condensed)").appendLine(earlier.joinToString("\n")).appendLine()
        }
        appendLine("WHAT CAME IN (${if (trigger.role == Role.USER) "the person" else "operator update"}, in full)")
        appendLine(trigger.text)
        if (after.isNotEmpty()) appendLine().appendLine("THEN, BEFORE SENDING").appendLine(after.joinToString("\n"))
    }.trimEnd()
}

// ---- the file the owner fills in -----------------------------------------------------------------------------------------

/** Pure: the numbered, zero-padded id of scenario [n] ("07"). */
fun id2(n: Int): String = n.toString().padStart(2, '0')

/** Text that would end the owner's block early or fake a fence is defused. */
fun defuse(text: String): String = text.replace("<!--", "<! --").replace("~~~", "~ ~ ~")

data class ScenarioView(
    val n: Int,
    val type: ScenarioType,
    val localTime: String,
    val constitutionVersion: Int?,
    val given: String,
    val original: String,
    val yours: String = "",
)

/** Pure: scenarios.md; [existing] answers are kept in their blocks so a re-extract does not lose what was written. */
fun scenariosMarkdown(views: List<ScenarioView>): String = buildString {
    appendLine("# Prompt lab scenarios")
    appendLine()
    appendLine("Write the message you wish Clarity had sent between the `yours` markers of each scenario. Leave a block empty to skip it.")
    appendLine("The original sits in a collapsed section under it so it does not bias you. Private: never commit this file.")
    for (v in views) {
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## ${id2(v.n)} · ${v.type.label} · ${v.localTime}")
        appendLine()
        appendLine("Constitution version: ${v.constitutionVersion ?: "none"}. The system prompt is left out.")
        appendLine()
        appendLine("**What it was given**")
        appendLine()
        appendLine("~~~text")
        appendLine(defuse(v.given))
        appendLine("~~~")
        appendLine()
        appendLine("**Your message**")
        appendLine()
        appendLine("<!-- yours:${id2(v.n)} -->")
        appendLine(defuse(v.yours))
        appendLine("<!-- /yours -->")
        appendLine()
        appendLine("<details><summary>What it sent</summary>")
        appendLine()
        appendLine("~~~text")
        appendLine(defuse(v.original))
        appendLine("~~~")
        appendLine()
        appendLine("</details>")
    }
}

private val YOURS = Regex("""<!--\s*yours:(\d+)\s*-->(.*?)<!--\s*/yours\s*-->""", RegexOption.DOT_MATCHES_ALL)

/** Pure: the filled-in blocks of scenarios.md by scenario number; empty blocks are left out. */
fun parseAnswers(markdown: String): Map<Int, String> =
    YOURS.findAll(markdown).mapNotNull { m ->
        val text = m.groupValues[2].trim()
        if (text.isEmpty()) null else m.groupValues[1].toInt() to text
    }.toMap()

// ---- the replay report ---------------------------------------------------------------------------------------------------

/** One model answer of a replay: readable text, or what went wrong, and which tools it called instead of `send_to_user`. */
@Serializable
data class ReplayOutput(val text: String, val words: Int, val otherTools: List<String> = emptyList(), val error: String? = null) {
    /** The model called `send_to_user` (an answer the person would see). */
    val sent: Boolean get() = error == null && text.isNotBlank()
}

/** Pure: how many more samples to take so [runs] of them sent, never past [cap] samples in all (default 3 x [runs]). */
fun moreSamplesNeeded(samples: List<ReplayOutput>, runs: Int, cap: Int = runs * 3): Int =
    minOf(runs - samples.count { it.sent }, cap - samples.size).coerceAtLeast(0)

/** Pure: "3 sent, 2 looked up first: list_calendar_events (5 samples)". */
fun samplingSummary(samples: List<ReplayOutput>): String {
    val tools = samples.filter { !it.sent && it.error == null }.flatMap { it.otherTools }.groupingBy { it }.eachCount()
    val unsent = samples.count { !it.sent && it.error == null }
    val failed = samples.count { it.error != null }
    return buildString {
        append(samples.count { it.sent }).append(" sent")
        if (unsent > 0) {
            append(", ").append(unsent).append(if (tools.isEmpty()) " sent nothing" else " looked up first")
            if (tools.isNotEmpty()) append(": ").append(tools.entries.joinToString { (t, n) -> if (n > 1) "$t x$n" else t })
        }
        if (failed > 0) append(", ").append(failed).append(" failed")
        append(" (").append(samples.size).append(" samples)")
    }
}

data class ReportEntry(
    val n: Int,
    val type: ScenarioType,
    val localTime: String,
    val yours: String?,
    val original: String,
    val originalWords: Int,
    val replays: List<ReplayOutput>,
)

private fun quote(text: String): String = text.lineSequence().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }

/** Pure: report.md, one section per scenario with yours, the original and each replay and their word counts. */
fun reportMarkdown(title: String, entries: List<ReportEntry>): String = buildString {
    appendLine("# $title")
    appendLine()
    appendLine("Private: built from the person's messages. Never commit.")
    val avg = entries.flatMap { it.replays }.filter { it.error == null }.map { it.words }.average().takeIf { !it.isNaN() }
    if (avg != null) appendLine("\nAverage words per replay: ${"%.0f".format(avg)}. Average original: ${"%.0f".format(entries.map { it.originalWords }.average())}.")
    for (e in entries) {
        appendLine()
        appendLine("---")
        appendLine()
        appendLine("## ${id2(e.n)} · ${e.type.label} · ${e.localTime}")
        appendLine()
        appendLine("**Yours**" + (e.yours?.let { " (${wordCount(it)} words)" } ?: ""))
        appendLine()
        appendLine(e.yours?.let(::quote) ?: "> (not filled in)")
        appendLine()
        appendLine("**Original** (${e.originalWords} words)")
        appendLine()
        appendLine(quote(e.original))
        appendLine()
        appendLine("**Sampling** ${samplingSummary(e.replays)}")
        e.replays.filter { it.sent }.forEachIndexed { i, r ->
            appendLine()
            appendLine("**Replay ${i + 1}** (${r.words} words)" + if (r.otherTools.isNotEmpty()) " also called: ${r.otherTools.joinToString()}" else "")
            appendLine()
            appendLine(quote(r.text))
        }
    }
}

/** Pure: what the person would see of a model answer ([content]: its `send_to_user` calls), and the other tools it called instead. */
fun interpret(content: List<Block>): ReplayOutput {
    val sends = content.filterIsInstance<Block.ToolUse>().filter { it.name == SEND_TO_USER }
    val others = content.filterIsInstance<Block.ToolUse>().filter { it.name != SEND_TO_USER }.map { it.name }
    return ReplayOutput(sends.joinToString("\n\n---\n\n") { renderCards(it.input) }, sends.sumOf { proseWords(it.input) }, others)
}
