package net.robog.clarity.app.ui.path

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.browser.BrowserStepView
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.path.PathItem
import net.robog.clarity.shared.path.PathSubject

/*
 * Pure presentation of the Agent's Path: what each step's node says and looks like, in words a
 * person reads at a glance. The server already summarized and redacted everything.
 */

/** How a browser step ended, as its node shows it. */
enum class StepOutcome { PASSED, HELD, REFUSED, NOT_FOUND, FAILED }

/** Pure: the outcome of a step from the gate's word (`passed (jev sign_in p=0.99)`, `code_guard`, `held`, …). */
fun stepOutcome(gate: String): StepOutcome = when (gate.substringBefore(' ').trim()) {
    "passed" -> StepOutcome.PASSED
    "held" -> StepOutcome.HELD
    "refused", "code_guard" -> StepOutcome.REFUSED
    "not_found" -> StepOutcome.NOT_FOUND
    else -> StepOutcome.FAILED
}

/** Pure: the outcome pill's words. */
fun outcomeLabel(step: BrowserStepView): String = when (stepOutcome(step.gate)) {
    StepOutcome.PASSED -> "Passed"
    StepOutcome.HELD -> "Held for you"
    StepOutcome.REFUSED -> if (step.gate.startsWith("code_guard")) "Refused · code guard" else "Refused"
    StepOutcome.NOT_FOUND -> "Not found"
    StepOutcome.FAILED -> "Failed"
}

/** Pure: the step in plain words: "Tapped the Sign in link", "Filled in your saved login", "Opened shop.clarity.test". */
fun stepTitle(step: BrowserStepView): String {
    val intent = step.intent?.trim().orEmpty()
    val outcome = stepOutcome(step.gate)
    return when (step.tool) {
        "go" -> "Opened " + (hostOf(intent) ?: intent.ifEmpty { "a page" })
        "back" -> "Went back a page"
        "sign_in" -> when (intent) {
            "login" -> "Filled in your saved login"
            "code" -> "Typed the sign-in code"
            else -> "Signed in"
        }
        else -> {
            val target = intent.ifEmpty { step.hitName?.let { "“$it”" } ?: "something on the page" }
            when (outcome) {
                StepOutcome.PASSED, StepOutcome.HELD -> "Tapped $target"
                StepOutcome.REFUSED -> "Wanted to tap $target"
                StepOutcome.NOT_FOUND -> "Looked for $target"
                StepOutcome.FAILED -> "Tried to tap $target"
            }
        }
    }.replaceFirstChar { it.uppercase() }
}

/** Pure: the element the step hit, "button · Sign in with Clarity ID"; null when none. */
fun elementLine(step: BrowserStepView): String? {
    val name = step.hitName?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    val role = step.hitRole?.takeIf { it.isNotBlank() && it != "null" }
    return if (role == null) name else "$role · $name"
}

private val SCHEME = Regex("https?://")

private val JEV_P = Regex("""\(Jev p=([0-9.]+)\)""")

/** Hints the change report gives the agent, not the person. */
private val AGENT_HINTS = listOf("Call look", "Use ask_code", "Pick one with act", "do not press", "continue with act", "then call sign_in")

/**
 * Pure: the change report, condensed for a person: the agent's instructions dropped, at most
 * [parts] parts, and "(Jev p=0.99)" said as words.
 */
fun condenseReport(report: String?, parts: Int = 2): String? {
    if (report.isNullOrBlank()) return null
    val kept = report.split(" / ", ". ")
        .map { it.trim().trimEnd('.').replace(SCHEME, "").replace("On the screen: looks like", "Looks like") }
        .filterNot { it.startsWith("Title:") || it.startsWith("Now on:") }
        .filter { part -> part.isNotEmpty() && AGENT_HINTS.none { part.contains(it, ignoreCase = true) } }
        .take(parts)
    // All of it was meant for the agent ("Refused: a code was sent at 15:29 – use ask_code …"): keep what happened.
    if (kept.isEmpty()) return report.substringBefore(" – ").substringBefore(" — ").trim().trimEnd('.').ifEmpty { null }
    return kept.joinToString(" · ") { part -> JEV_P.replace(part) { m -> m.groupValues[1].toDoubleOrNull()?.let { "(${confidenceText(it)})" } ?: "" } }
}

/** Pure: a probability in words: "Jev is very sure, 99%". */
fun confidenceText(p: Double): String {
    val pct = (p * 100).roundToInt().coerceIn(0, 100)
    val words = when {
        p >= 0.95 -> "very sure"
        p >= 0.8 -> "fairly sure"
        p >= 0.6 -> "leaning yes"
        else -> "unsure"
    }
    return "Jev is $words, $pct%"
}

/** Pure: how sure Jev was that the step did what it should (its check, else its approval), if it answered. */
fun stepConfidence(step: BrowserStepView): Double? {
    val jev = step.jev ?: return null
    fun answers(call: String): JsonObject? = (jev[call] as? JsonObject)?.get("answers") as? JsonObject
    (answers("verify")?.get("happened") as? JsonPrimitive)?.doubleOrNull?.let { return it }
    answers("sign_in")?.let { a -> (a["sign_in"] as? JsonObject)?.let { choice -> choiceProbability(choice) }?.let { return it } }
    answers("approve")?.let { a -> (a["step"] as? JsonObject)?.let { choice -> choiceProbability(choice) }?.let { return it } }
    return null
}

private fun choiceProbability(choice: JsonObject): Double? {
    val picked = (choice["choice"] as? JsonPrimitive)?.content ?: return null
    return (choice[picked] as? JsonPrimitive)?.doubleOrNull
}

/** Jev's box on a screenshot, as fractions of the image. */
data class JevBox(val top: Float, val bottom: Float, val left: Float, val right: Float)

/** Pure: Jev's box of [step], when it drew one that makes sense. */
fun jevBox(step: BrowserStepView): JevBox? {
    val box = step.box ?: return null
    fun f(key: String) = (box[key] as? JsonPrimitive)?.doubleOrNull?.toFloat()
    val b = JevBox(f("top") ?: return null, f("bottom") ?: return null, f("left") ?: return null, f("right") ?: return null)
    return b.takeIf { it.top in 0f..1f && it.bottom in 0f..1f && it.left in 0f..1f && it.right in 0f..1f && it.bottom > it.top && it.right > it.left }
}

/** Pure: the host of an address, or null when it is not one. */
fun hostOf(url: String?): String? {
    val rest = url?.substringAfter("://", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() } ?: return null
    return rest.substringBefore('/').substringBefore('?').ifEmpty { null }
}

private val SIGN_IN_WORDS = listOf("login", "signin", "sign-in", "sign_in", "auth", "sso")

/**
 * Pure: the line that groups browser steps by tab: "On Garden Shop" for a run's first step,
 * "Opened the sign-in tab · Clarity ID" when the step works in a new place, "Back on Garden Shop"
 * when it returns; null while the step stays where the last one was. [seen] are the hosts the run
 * has been on, in order.
 */
fun tabChange(previous: BrowserStepView?, step: BrowserStepView, seen: List<String>): String? {
    val host = hostOf(step.tabUrl) ?: return null
    val title = step.tabTitle?.takeIf { it.isNotBlank() } ?: host
    if (previous == null) return "On $title"
    if (hostOf(previous.tabUrl) == host) return null
    if (host in seen) return "Back on $title"
    val signIn = SIGN_IN_WORDS.any { host.contains(it, ignoreCase = true) || step.tabUrl.orEmpty().contains(it, ignoreCase = true) }
    return if (signIn) "Opened the sign-in tab · $title" else "Opened $title"
}

/** One row of the timeline: a node, or the line that says the agent moved to another tab. */
sealed interface PathRow {
    val depth: Int
    val key: String

    data class Node(val item: PathItem, override val key: String) : PathRow {
        override val depth: Int get() = item.depth
    }

    data class Tab(val label: String, override val depth: Int, override val key: String) : PathRow
}

/** Pure: the rows of the timeline, with a tab line wherever a run's browser steps change tab. */
fun pathRows(items: List<PathItem>): List<PathRow> {
    val last = HashMap<String, BrowserStepView>()
    val seen = HashMap<String, MutableList<String>>()
    return buildList {
        items.forEachIndexed { i, item ->
            val key = rowKey(item, i)
            if (item is PathItem.Browser) {
                val hosts = seen.getOrPut(item.runId) { mutableListOf() }
                tabChange(last[item.runId], item.step, hosts)?.let { add(PathRow.Tab(it, item.depth, "tab-$key")) }
                hostOf(item.step.tabUrl)?.let { if (it !in hosts) hosts += it }
                last[item.runId] = item.step
            }
            add(PathRow.Node(item, key))
        }
    }
}

private fun rowKey(item: PathItem, index: Int): String = when (item) {
    is PathItem.Browser -> "step-${item.step.id}"
    is PathItem.Helper -> "helper-${item.childRunId}"
    else -> "${item.runId}-$index"
}

/** Pure: a cost in dollars: "$0.0023", "$0.41"; null when unknown. */
fun costText(micros: Long?): String? {
    micros ?: return null
    val dollars = micros / 1_000_000.0
    return if (micros >= 10_000) "$" + "%.2f".format(java.util.Locale.US, dollars) else "$" + "%.4f".format(java.util.Locale.US, dollars)
}

/** Pure: a duration in words: "38 s", "4 min", "1 h 5 min". */
fun durationText(millis: Long): String {
    val s = millis / 1000
    return when {
        s < 60 -> "${s.coerceAtLeast(0)} s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h" + (s % 3600 / 60).let { if (it > 0) " $it min" else "" }
    }
}

/** Pure: a task's status (its wire name) as an [AgentTask.Status]; null for a run's status. */
fun taskStatusOf(wire: String): AgentTask.Status? =
    runCatching { ClarityJson.decodeFromString(AgentTask.Status.serializer(), "\"$wire\"") }.getOrNull()

/** Pure: the subject's status in words: a task's as on Today, a run's as it is. */
fun statusLabel(subject: PathSubject): String =
    taskStatusOf(subject.status)?.takeIf { subject.kind == PathSubject.Kind.TASK }?.let(::taskStatusLabel) ?: when (subject.status) {
        "queued" -> "waiting to start"
        "running" -> "working"
        else -> subject.status
    }

/** Pure: "7 steps", "1 step". */
fun stepsText(n: Int): String = if (n == 1) "1 step" else "$n steps"

/** Pure: a helper's one-line summary: "7 steps · 3 in the browser". */
fun helperLine(item: PathItem.Helper): String =
    stepsText(item.steps) + if (item.browserSteps > 0) " · ${item.browserSteps} in the browser" else ""

/** Pure: the frame's accessible description: "Before: tapped the Sign in link, on link · Sign in". */
fun frameDescription(step: BrowserStepView, after: Boolean): String =
    (if (after) "After: " else "Before: ") + stepTitle(step).replaceFirstChar { it.lowercase() } + (elementLine(step)?.let { ", on $it" } ?: "")
