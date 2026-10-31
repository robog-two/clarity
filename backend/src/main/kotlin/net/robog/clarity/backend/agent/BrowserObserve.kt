package net.robog.clarity.backend.agent

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import net.robog.clarity.backend.vault.Sites

/**
 * Pure parts of the browser pilot: Clarity's observation script, what it returns, the text views
 * the helper reads (`look`, the change report) and the sign-in code rules.
 */

/** Clarity's observation script (resources/browser/observe.js), run with `evaluate_script` in the "clarity" sandbox. */
object ObserveScript {
    const val SANDBOX = "clarity"

    private val raw: List<String> by lazy {
        (ObserveScript::class.java.getResource("/browser/observe.js")?.readText() ?: error("observe.js missing from resources")).lines()
    }

    /** The whole script (every op's sections) without its comment lines and indentation. */
    val source: String by lazy { strip(raw) }

    private val byOp = ConcurrentHashMap<String, String>()

    /** The script as sent for [op]: the shared part plus [op]'s sections (evaluate_script takes at most 16 KiB). */
    fun sourceFor(op: String): String = byOp.getOrPut(op) { strip(scriptSections(raw, op)) }

    /** The `function` argument of evaluate_script for [op] with [args]. */
    fun call(op: String, args: JsonObject = JsonObject(emptyMap())): String = "() => (${sourceFor(op)})(${JsonPrimitive(op)}, $args)"

    private fun strip(lines: List<String>): String =
        lines.asSequence().map { it.trim().replace(Regex(";\\s+// .*$"), ";") }.filter { it.isNotEmpty() && !it.startsWith("//") }.joinToString("\n")
}

/**
 * Pure: the lines of a script for [op]. Lines outside `//#if a b` … `//#end` sections always go;
 * lines inside one only when [op] is among its ops.
 */
fun scriptSections(lines: List<String>, op: String): List<String> {
    var keep = true
    return lines.filter { line ->
        val t = line.trim()
        when {
            t.startsWith("//#if ") -> { keep = op in t.removePrefix("//#if ").split(' ').filter(String::isNotBlank); false }
            t == "//#end" -> { keep = true; false }
            else -> keep
        }
    }
}

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** Pure: the JSON an evaluate_script reply carries ("Script ran on page and returned:\n```json\n…\n```"), or null. */
fun parseScriptResult(text: String): JsonElement? {
    val start = text.indexOf("```json")
    val body = if (start >= 0) text.substring(start + 7).substringBeforeLast("```") else text
    return runCatching { lenient.parseToJsonElement(body.trim()) }.getOrNull()
}

/** One element the observation script saw: [ref] is Clarity's stable id for it on this page. */
@Serializable
data class SeenElement(
    val ref: String,
    val role: String,
    val name: String = "",
    val states: List<String> = emptyList(),
    val value: String? = null,
    val ac: String? = null,
    val type: String? = null,
    val href: String? = null,
    val level: Int? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = 0.0,
    val h: Double = 0.0,
    val inView: Boolean = true,
    /** For a hit under Jev's box only: what a click there would really land on ([hitProblem]). */
    val paint: HitFacts? = null,
    /** The ref of the scroll area it sits in, if any (it may be scrolled out of that area's view). */
    val area: String? = null,
) {
    val centerX: Double get() = x + w / 2
    val centerY: Double get() = y + h / 2

    /** For the gate's rules: the role and name, and where a link goes. */
    fun element(): Element = Element(role, name.ifBlank { null }, href)

    /** One line of `look`. */
    fun line(): String = buildString {
        append('[').append(ref).append("] ").append(role)
        if (name.isNotBlank()) append(" \"").append(name).append('"')
        level?.let { append(" h").append(it) }
        type?.takeIf { it !in setOf("text", "submit", "button") }?.let { append(" type=").append(it) }
        ac?.let { append(" autocomplete=").append(it) }
        value?.let { append(" value=\"").append(it).append('"') }
        states.filter { it != "shadow" && it != "frame" }.forEach { append(' ').append(it) }
        href?.let { append(" → ").append(it) }
        if (!inView) append(if (area != null) " (scrolled out of view in $area)" else " (off screen)")
    }
}

/** A part of the page that scrolls on its own (an inner list in a fixed layout), as `look` lists it. */
@Serializable
data class ScrollArea(
    /** Empty in a scroll reply for the page itself. */
    val ref: String = "",
    val name: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val w: Double = 0.0,
    val h: Double = 0.0,
    /** Rows in view, and in all (estimated from its height for a list that renders only what shows). */
    val rows: Int = 0,
    val total: Int = 0,
    /** Pixels scrolled from its top, and still below. */
    val top: Double = 0.0,
    val below: Double = 0.0,
    // scroll replies
    val moved: Double? = null,
    val scrollY: Double? = null,
    val error: String? = null,
) {
    /** One line of `look`: "[c40] scroll area "Available times": showing ~8 of ~30 rows, more below (700 px)". */
    fun line(): String = buildString {
        append('[').append(ref).append("] scroll area")
        if (name.isNotBlank()) append(" \"").append(name).append('"')
        append(": ").append(showing())
    }

    fun showing(): String = buildString {
        if (total > 0) append("showing ~$rows of ~$total rows") else append("scrollable")
        append(
            when {
                below > 1 && top > 1 -> ", more above and below (%,d px below)".format(below.toInt())
                below > 1 -> ", more below (%,d px)".format(below.toInt())
                top > 1 -> ", at the end (more above)"
                else -> ""
            },
        )
    }
}

/**
 * What the observation script found about the element a click would land on: its [opacity] with its
 * ancestors', whether it is visibility-[hidden], whether it [painted] anything itself (background,
 * border, shadow, text or media), the area of the control [under] it at the point, and whether it is
 * [onTop] at its own centre (where the click goes).
 */
@Serializable
data class HitFacts(
    val opacity: Double = 1.0,
    val hidden: Boolean = false,
    val painted: Boolean = true,
    val under: Double? = null,
    val onTop: Boolean = true,
)

/**
 * Pure: why a hit must not be clicked (a clickjacking layer, or something covering it), or null.
 * [box] is Jev's box in fractions of the [vw] x [vh] viewport.
 */
fun hitProblem(hit: SeenElement, box: DecisionAnswer.Box?, vw: Double, vh: Double): String? {
    val f = hit.paint ?: return null
    val area = hit.w * hit.h
    return when {
        f.opacity < 0.1 -> "it is transparent (opacity %.2f)".format(f.opacity)
        f.hidden -> "it is hidden (visibility: hidden)"
        !f.painted && f.under != null && area > f.under -> "it is an invisible layer over the control underneath"
        box != null && area > 6 * (box.right - box.left) * vw * (box.bottom - box.top) * vh && area > 0.25 * vw * vh ->
            "it is far larger than what Jev pointed at (a page-wide click-catcher)"
        !f.onTop -> "something else is on top of it"
        else -> null
    }
}

/** Pure: the share of [element]'s area that [box] (fractions of a [vw] x [vh] viewport) covers. */
fun coveredShare(box: DecisionAnswer.Box, vw: Double, vh: Double, element: SeenElement): Double {
    if (element.w <= 0 || element.h <= 0) return 0.0
    val w = minOf(box.right * vw, element.x + element.w) - maxOf(box.left * vw, element.x)
    val h = minOf(box.bottom * vh, element.y + element.h) - maxOf(box.top * vh, element.y)
    return if (w <= 0 || h <= 0) 0.0 else (w * h) / (element.w * element.h)
}

/** A sure by-name answer, for taking the named control when the box centre is on its neighbour. */
const val NAMED_SURE = 0.9

/**
 * Pure: Jev's by-name box ([box], with [p] visible) picks [named] over [atCentre], the control under
 * the box centre: Jev is sure (p ≥ [NAMED_SURE]) and the box covers more of the named control.
 */
fun namedWins(box: DecisionAnswer.Box, p: Double, vw: Double, vh: Double, named: SeenElement, atCentre: SeenElement): Boolean =
    p >= NAMED_SURE && coveredShare(box, vw, vh, named) > coveredShare(box, vw, vh, atCentre)

/** What `look` and `changes` return about the page. */
@Serializable
data class PageObservation(
    val url: String = "",
    val title: String = "",
    val vw: Double = 0.0,
    val vh: Double = 0.0,
    val scrollY: Double = 0.0,
    val docH: Double = 0.0,
    val opener: Boolean = false,
    val dialog: String? = null,
    val items: List<SeenElement> = emptyList(),
    val total: Int = 0,
    val otp: Boolean = false,
    val password: Boolean = false,
    val signals: List<String> = emptyList(),
    val error: String? = null,
    /** The page says the session expired or the person was signed out. */
    val expired: Boolean = false,
    /** A sign-out control or a greeting on screen ("Hi, Dana"): a sign of being signed in. */
    val account: String? = null,
    /** The count and a hash of the names (never the values) of the page's readable cookies. */
    val ckn: String? = null,
    /** Pixels of the page below the viewport. */
    val below: Double? = null,
    val areas: List<ScrollArea> = emptyList(),
    // changes
    val same: Boolean = true,
    val added: List<String> = emptyList(),
    // point / target
    val hit: SeenElement? = null,
    val token: String? = null,
)

/** The sign-in fields the observation script found. */
@Serializable
data class SignInFields(
    val url: String = "",
    val user: SeenElement? = null,
    val password: SeenElement? = null,
    val otp: SeenElement? = null,
    val submit: SeenElement? = null,
    val passwords: Int = 0,
)

/** Pure: decode an observation reply, or null when it is not one. */
inline fun <reified T> decodeObservation(json: JsonElement?): T? =
    json?.let { runCatching { observationJson.decodeFromJsonElement<T>(it) }.getOrNull() }

val observationJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** One open tab as `list_pages` lists it. */
data class Tab(val index: Int, val title: String, val url: String, val selected: Boolean)

private val TAB_LINE = Regex("""^(>?)\s*\[(\d+)]\s+(.*)$""")

/** Pure: the tabs in `list_pages` output (`>[1] Title (https://...)`, the selected one marked `>`). */
fun parseTabs(text: String): List<Tab> = text.lineSequence().mapNotNull { raw ->
    val m = TAB_LINE.find(raw.trim()) ?: return@mapNotNull null
    val rest = m.groupValues[3]
    val open = rest.lastIndexOf(" (")
    val (title, url) = if (open >= 0 && rest.endsWith(")")) rest.substring(0, open) to rest.substring(open + 2, rest.length - 1) else rest to ""
    Tab(m.groupValues[2].toInt(), title, url, m.groupValues[1] == ">")
}.toList()

/** How the tabs changed across one step: tabs that appeared (by index after) and the index (before) that went away. */
data class TabDiff(val opened: List<Tab>, val closed: Int?)

/**
 * Pure: what changed between two `list_pages` readings. New tabs are appended at the end by
 * Firefox. A closed tab is the one whose removal leaves exactly the addresses after; when that is
 * not clear (another tab navigated meanwhile, as an opener does when its sign-in popup finishes),
 * a tab in [popups] is taken first, the selected one before others, then the first that differs.
 */
fun diffTabs(before: List<Tab>, after: List<Tab>, popups: Set<Int> = emptySet()): TabDiff = when {
    after.size > before.size -> TabDiff(after.drop(before.size), null)
    after.size < before.size -> {
        val urls = after.map { it.url }
        val exact = before.indices.filter { i -> before.filterIndexed { j, _ -> j != i }.map { it.url } == urls }
        val popup = before.filter { it.index in popups }.sortedByDescending { it.selected }.firstOrNull()?.index
        TabDiff(emptyList(), exact.singleOrNull() ?: popup ?: before.indices.firstOrNull { i -> after.getOrNull(i)?.url != before[i].url } ?: before.lastIndex)
    }
    else -> TabDiff(emptyList(), null)
}

/** Pure: [popups] (tab index to its opener's) after tab [closed] went away: later tabs move down one. */
fun shiftAfterClose(popups: Map<Int, Int>, closed: Int): Map<Int, Int> =
    popups.filterKeys { it != closed }.mapNotNull { (tab, opener) ->
        if (opener == closed) null else (if (tab > closed) tab - 1 else tab) to (if (opener > closed) opener - 1 else opener)
    }.toMap()

/** Where a site's sign-in stands in this run. */
enum class SignInPhase(val label: String) {
    UNKNOWN("not started"), LOGIN("asking for the login"), CHOOSE_METHOD("asking where to send a code"),
    CODE_SENT("code sent, waiting for it"), SIGNED_IN("signed in"), ERROR("showing an error"),
}

/** The sign-in state of one site in one run: how many codes were sent, when, and what sent them. */
@Serializable
data class SignInState(
    val codesSent: Int = 0,
    val lastSentAt: Instant? = null,
    val phase: SignInPhase = SignInPhase.UNKNOWN,
    /** The labels of the steps that sent a code ("Continue", "Resend"): pressing one again sends another. */
    val triggers: Set<String> = emptySet(),
    /** Codes typed and not accepted (wrong or mistyped): the person is asked again, the site never for a new one. */
    val codeTries: Int = 0,
    /** When this run became sure it is signed in to the site ([signedInVerdict]); it sticks until the site says the session expired. */
    val signedInAt: Instant? = null,
) {
    val signedIn: Boolean get() = phase == SignInPhase.SIGNED_IN

    /** Signed in, now (kept from the first time). */
    fun signIn(now: Instant): SignInState = copy(phase = SignInPhase.SIGNED_IN, signedInAt = signedInAt ?: now, codeTries = 0)

    /**
     * A site that asks for a code again after sign-in (a second verification, such as a "verify your
     * account" dialog): a new code request, not a resend. True while no code was sent since signing in.
     */
    fun stepUpOpen(): Boolean = signedInAt != null && (lastSentAt == null || lastSentAt < signedInAt)
}

/** How long after a code was sent a step that would send another is refused. */
val CODE_GUARD_WINDOW: kotlin.time.Duration = kotlin.time.Duration.parse("10m")

/** At most this many codes per site per run; then the person takes over. */
const val MAX_CODES_PER_SITE = 2

/** At most this many typed codes per site per run that the site rejects; then the person takes over. */
const val MAX_CODE_TRIES = 3

private val RESEND = Regex(
    "\\b(resend|re-send|send (it )?again|send (me )?(a |another |the )?(new )?code|get (a )?new code|new code|request (a )?(new )?code|didn'?t (get|receive)|" +
        "text me|call me|email me|try again|start over|restart)\\b",
    RegexOption.IGNORE_CASE,
)

/** Pure: [label] reads like a step that sends another one-time code or restarts sign-in. */
fun isResendStep(label: String?): Boolean = label != null && RESEND.containsMatchIn(label)

/** What the code guard says about one step. */
sealed interface CodeGuard {
    data object Allow : CodeGuard

    data class Refuse(val message: String) : CodeGuard
}

/**
 * Pure: the code guard. Once a code was sent for the site, a step the rules ([isResendStep], or a
 * label that sent a code before) or Jev ([jevResend] at [threshold] or more) flag as sending
 * another is refused for [CODE_GUARD_WINDOW]; after [MAX_CODES_PER_SITE] codes it is refused for
 * good and the person takes over. Times are shown in [zone].
 */
fun codeGuard(state: SignInState?, label: String?, jevResend: Double?, threshold: Double, now: Instant, zone: TimeZone): CodeGuard {
    if (state == null || state.codesSent == 0) return CodeGuard.Allow
    // Signed in, and the site asks to verify again: its first code request is a new one (only once).
    if (state.stepUpOpen()) return CodeGuard.Allow
    val flagged = isResendStep(label) || (label != null && label.trim().lowercase() in state.triggers) || (jevResend != null && jevResend >= threshold)
    if (!flagged) return CodeGuard.Allow
    val at = state.lastSentAt?.let { clockTime(it, zone) } ?: "a moment ago"
    if (state.codesSent >= MAX_CODES_PER_SITE) {
        return CodeGuard.Refuse(
            "Refused: ${state.codesSent} codes were already sent for this sign-in (the last at $at). I won't ask the site for another: " +
                "stop here and report back so the person can finish signing in themselves.",
        )
    }
    val last = state.lastSentAt ?: return CodeGuard.Refuse("Refused: a code was sent at $at – use ask_code")
    return if (now - last < CODE_GUARD_WINDOW) CodeGuard.Refuse("Refused: a code was sent at $at – use ask_code (then type it with act or sign_in).")
    else CodeGuard.Allow
}

/** Pure: HH:MM of [moment] in [zone]. */
fun clockTime(moment: Instant, zone: TimeZone): String =
    moment.toLocalDateTime(zone).let { "%02d:%02d".format(it.hour, it.minute) }

/**
 * Pure: did the step just taken (on an element labelled [label]) make the site send a code? A code
 * field appeared that was not there; or the code page came back (reloaded, or saying a new code is
 * on its way) after a step that sends codes ([isResendStep], or one of [triggers]).
 */
fun codeSentBy(before: PageObservation, after: PageObservation, label: String?, triggers: Set<String> = emptySet()): Boolean {
    if (after.otp && !before.otp) return true
    if (!(after.otp && before.otp)) return false
    val sends = isResendStep(label) || (label != null && label.trim().lowercase() in triggers)
    val again = !after.same || after.url != before.url || after.added.any { RESENT.containsMatchIn(it) || SENT.containsMatchIn(it) }
    return sends && again
}

private val RESENT = Regex("\\b(resent|new code|sent (you )?(a |another )?new)\\b", RegexOption.IGNORE_CASE)
private val SENT = Regex("\\b(we('ve| have)? (sent|texted|emailed)|code (was |has been )?sent)\\b", RegexOption.IGNORE_CASE)

/** The header of `look`: tabs with their openers, the sign-in state of the page's site. */
data class LookHeader(
    val tabs: List<Tab>,
    val openers: Map<Int, Int>,
    val signIn: SignInState?,
    val zone: TimeZone,
    /** Sign-ins on other sites in this run (an identity provider's, once back on the shop). */
    val others: Map<String, SignInState> = emptyMap(),
    /** Why the page had not settled when it was read ([settleWait]), or null. */
    val loading: String? = null,
)

/** About 1.5k tokens of text. */
const val LOOK_BUDGET = 6_000

/** Pure: one line per tab, the selected one starred, popups with their opener. */
fun tabsLine(tabs: List<Tab>, openers: Map<Int, Int>): String =
    if (tabs.isEmpty()) "Tabs: (none)" else "Tabs: " + tabs.joinToString(" · ") { t ->
        "[${t.index}]${if (t.selected) "*" else ""} ${t.title.take(40)} – ${shortUrl(t.url)}" + (openers[t.index]?.let { " (opened by tab $it)" } ?: "")
    }

/** Pure: a URL without its scheme and query, for headers. */
fun shortUrl(url: String): String = url.removePrefix("https://").removePrefix("http://").substringBefore('?').take(70)

/** Pure: the sign-in line of `look` for [domain]. */
fun signInLine(domain: String?, state: SignInState?, zone: TimeZone): String? {
    if (domain == null) return null
    if (state == null) return "Sign-in on $domain: nothing done yet in this task."
    val codes = when (state.codesSent) {
        0 -> "no code sent"
        else -> "${state.codesSent} code${if (state.codesSent > 1) "s" else ""} sent (last at ${state.lastSentAt?.let { clockTime(it, zone) } ?: "?"})"
    }
    if (state.signedIn) {
        val since = state.signedInAt?.let { " (since ${clockTime(it, zone)})" }.orEmpty()
        return "Signed in to $domain$since; $codes. Do not sign in again unless a login form is on the page or the site says the session expired."
    }
    return "Sign-in on $domain: ${state.phase.label}; $codes."
}

/**
 * Pure: the `look` text. Header (tabs, page, dialog, sign-in), then one line per element in page
 * order until [budget] characters; plain text lines and off-screen elements go first when it does
 * not fit, and the rest is counted with a note to narrow the view.
 */
fun formatLook(page: PageObservation, header: LookHeader, budget: Int = LOOK_BUDGET, scoped: Boolean = false): String = buildString {
    appendLine(tabsLine(header.tabs, header.openers))
    appendLine("Page: ${page.title.take(80)} — ${page.url.take(200)}")
    page.dialog?.let { appendLine("Dialog open: \"$it\"") }
    header.loading?.let { appendLine("Still loading: $it. What follows may be incomplete; call wait, then look again.") }
    signInLine(Sites.registrableDomain(page.url), header.signIn, header.zone)?.let { appendLine(it) }
    header.others.forEach { (domain, state) -> signInLine(domain, state, header.zone)?.let { appendLine(it) } }
    if (page.error != null) { appendLine(page.error); return@buildString }
    val below = page.below ?: (page.docH - page.scrollY - page.vh).coerceAtLeast(0.0)
    if (below > page.vh * 0.2) appendLine("Page continues: %,d px below (act(intent=\"scroll down\")).".format(below.toInt()))
    appendLine("--")
    page.areas.forEach { appendLine(it.line()) }
    val lines = page.items.map { it to it.line() }
    val keep = fitLines(lines.map { (e, l) -> Triple(e, l, priority(e)) }, budget - length)
    lines.filter { it.first.ref in keep }.forEach { appendLine(it.second) }
    val more = page.total - keep.size
    if (more > 0) appendLine("($more more – narrow with look(scope=\"<ref>\") or scroll)")
    if (page.items.isEmpty()) appendLine(if (scoped) "(nothing to act on in this part of the page)" else "(nothing to act on is visible)")
    if (page.areas.any { it.below > 1 }) appendLine("(scroll an area with act(intent=\"scroll the <area name> down\"); read reads every area to its end)")
}.trimEnd()

private fun priority(e: SeenElement): Int = when {
    e.role == "text" -> 3
    !e.inView -> 2
    e.role == "heading" -> 1
    else -> 0
}

/** Pure: which refs fit in [budget] characters, dropping the highest priority numbers first, keeping page order. */
fun fitLines(lines: List<Triple<SeenElement, String, Int>>, budget: Int): Set<String> {
    var size = lines.sumOf { it.second.length + 1 }
    val keep = lines.map { it.first.ref }.toMutableSet()
    for (p in 3 downTo 0) {
        if (size <= budget) break
        for ((e, l, prio) in lines.asReversed()) {
            if (size <= budget) break
            if (prio == p && e.ref in keep) { keep -= e.ref; size -= l.length + 1 }
        }
    }
    return keep
}

/** What one step did, for the change report. */
data class StepChange(
    val before: PageObservation,
    val after: PageObservation,
    val tabs: TabDiff,
    val tabNotes: List<String>,
    val targetBefore: SeenElement?,
    val targetAfter: SeenElement?,
    /** Jev's "did this happen" probability, or why it was not asked. */
    val verified: Double?,
    val verifyNote: String?,
    val expect: String?,
    val codeSent: Boolean,
    /** Why the page had not settled when the report was built ([settleWait]), or null when it had. */
    val loading: String? = null,
    /** How long the step waited for the page to settle. */
    val waitedMillis: Long = 0,
    /** A line about the sign-in this step changed ("Signed in to ulta.com"), if any. */
    val signInNote: String? = null,
)

/** Pure: did anything a person would notice change? */
fun visiblyChanged(c: StepChange): Boolean =
    c.after.url != c.before.url || c.after.title != c.before.title || c.tabs.opened.isNotEmpty() || c.tabs.closed != null ||
        c.after.dialog != c.before.dialog || c.after.added.isNotEmpty() || !c.after.same || c.after.otp != c.before.otp ||
        c.after.password != c.before.password || c.codeSent ||
        (c.targetBefore != null && c.targetAfter != null && (c.targetBefore.states != c.targetAfter.states || c.targetBefore.value != c.targetAfter.value))

/** Pure: the change report after one step, built in code; "no visible change" is said outright. */
fun changeReport(c: StepChange, noChangeInARow: Int): String = buildString {
    c.loading?.let { appendLine(stillLoadingLine(it, c.waitedMillis)) }
    c.tabNotes.forEach { appendLine(it) }
    if (c.after.url != c.before.url) appendLine("Page changed: ${c.before.url.take(120)} → ${c.after.url.take(160)}")
    if (c.after.title != c.before.title) appendLine("Title: \"${c.before.title.take(60)}\" → \"${c.after.title.take(80)}\"")
    if (c.after.dialog != c.before.dialog) appendLine(c.after.dialog?.let { "Dialog open: \"$it\"" } ?: "The dialog closed.")
    if (c.targetBefore != null && c.targetAfter != null) {
        if (c.targetBefore.states != c.targetAfter.states) appendLine("The element is now: ${c.targetAfter.states.filter { it != "shadow" && it != "frame" }.ifEmpty { listOf("unchecked/cleared") }.joinToString()}")
        if (c.targetBefore.value != c.targetAfter.value) appendLine("Its value is now \"${c.targetAfter.value.orEmpty()}\"")
    }
    if (c.after.url == c.before.url && c.after.added.isNotEmpty()) appendLine("New on the page: " + c.after.added.take(6).joinToString(" | ") { "\"${it.take(120)}\"" })
    if (c.after.otp && !c.before.otp) appendLine("A one-time-code field is now on the page.")
    if (c.codeSent) appendLine("The site says it sent a sign-in code. Use ask_code now; do not press anything that sends another.")
    val changed = visiblyChanged(c)
    if (!changed) appendLine(if (c.loading != null) "No visible change yet." else "No visible change.")
    c.signInNote?.let { appendLine(it) }
    when {
        c.verified != null && c.expect != null -> appendLine(
            when {
                c.verified >= 0.5 -> "On the screen: looks like \"${c.expect}\" happened (Jev p=%.2f).".format(c.verified)
                // Judged on a half-loaded page: not a verdict.
                c.loading != null -> "On the screen: \"${c.expect}\" is not there yet, but the page is still loading (Jev p=%.2f). Do not redo the step: call wait, then look.".format(c.verified)
                else -> "On the screen: \"${c.expect}\" did not happen (Jev p=%.2f).".format(c.verified)
            },
        )
        c.verifyNote != null -> appendLine(c.verifyNote)
    }
    if (!changed && noChangeInARow >= 2) appendLine("That is two steps in a row with no visible change: stop and call look before anything else.")
}.trimEnd()

/** Pure: the first line of a change report on a page that had not settled. */
fun stillLoadingLine(loading: String, waitedMillis: Long): String =
    "Still loading after ${(waitedMillis + 500) / 1000} s ($loading): this report may be incomplete."

/** Pure: an intent that is not an element but a scroll ("scroll down"), as +1 / -1 screens. */
fun scrollIntent(intent: String): Int? = scrollOf(intent)?.takeIf { it.target == null }?.dy

/** A scroll the helper asked for: [dy] screens (+1 down, -1 up) of [target] (an area named in words), or of what is under the viewport centre. */
data class ScrollRequest(val dy: Int, val target: String?)

private val SCROLL_WORDS = Regex(
    """^(?:please\s+)?scroll\s+(?:(down|up)\s+(?:in\s+|inside\s+|within\s+)?(.*?)|(.*?)\s+(down|up)|(down|up)|)$""",
    RegexOption.IGNORE_CASE,
)
private val PAGE_ONLY = setOf("", "the page", "page", "the whole page", "the window")

/**
 * Pure: a scroll intent. "scroll down", "page down" → the area under the viewport centre or the
 * page; "scroll the available times down", "scroll down in the times list" → that area; null when
 * the intent is not a scroll ("the scroll bar").
 */
fun scrollOf(intent: String): ScrollRequest? {
    val t = intent.trim().removeSuffix(".").replace(Regex("\\s+"), " ")
    when (t.lowercase()) {
        "page down" -> return ScrollRequest(1, null)
        "page up" -> return ScrollRequest(-1, null)
    }
    val m = SCROLL_WORDS.matchEntire(t) ?: return null
    val (d1, area1, area2, d2, d3) = m.destructured
    val dir = (d1.ifEmpty { d2 }.ifEmpty { d3 }).lowercase()
    val dy = if (dir == "up") -1 else 1
    val area = area1.ifEmpty { area2 }.trim().lowercase().removeSuffix(" area").let { if (it in PAGE_ONLY || it.startsWith("the page")) null else it }
    return ScrollRequest(dy, area?.takeIf { it.isNotBlank() })
}

/** Pure: the one scroll area whose name shares the most words with [target] (null when none or a tie). */
fun pickArea(areas: List<ScrollArea>, target: String): ScrollArea? {
    val want = words(target).filter { it !in setOf("list", "area", "scroll", "section", "panel") }.toSet()
    if (want.isEmpty()) return areas.singleOrNull()
    val scored = areas.map { it to words(it.name).count { w -> w in want } }.filter { it.second > 0 }
    val best = scored.maxOfOrNull { it.second } ?: return null
    return scored.filter { it.second == best }.singleOrNull()?.first
}

/**
 * Pure: [acc] with the lines of [next] appended, without the overlap: a list read again after a
 * scroll repeats rows it showed before (all of them on a page that loads more, some on a list that
 * renders only what shows). Whole repeats of [next] add nothing; repeated rows inside one reading stay.
 * Up to [EDGE_ROWS] lines at the top of a reading may be a row cut at the window's edge (seen live: a
 * lone "Select" from a half-rendered row), so the overlap may start just after them.
 */
fun mergeLines(acc: List<String>, next: List<String>): List<String> {
    if (acc.isEmpty()) return next
    if (next.isEmpty()) return acc
    val skips = 0..minOf(EDGE_ROWS, next.size - 1)
    // Skipping edge lines needs at least two lines to match after them, so a common row ("Sold out") cannot swallow new ones.
    if (skips.any { j -> (j == 0 || next.size - j >= 2) && containsRun(acc, next.drop(j)) }) return acc
    var best = 0 to 0 // (skipped, overlap)
    for (j in skips) {
        val rest = next.drop(j)
        val k = (minOf(acc.size, rest.size) downTo 1).firstOrNull { k -> acc.subList(acc.size - k, acc.size) == rest.subList(0, k) } ?: 0
        if (k > best.second && (j == 0 || k >= 2)) best = j to k
    }
    val (skipped, overlap) = best
    return if (overlap == 0) acc + next else acc + next.drop(skipped + overlap)
}

private const val EDGE_ROWS = 2

private fun containsRun(acc: List<String>, run: List<String>): Boolean =
    run.size <= acc.size && (0..acc.size - run.size).any { i -> acc.subList(i, i + run.size) == run }

/** What the observation script says about whether the page has settled. */
@Serializable
data class SettleProbe(
    val url: String = "",
    val ready: String = "",
    val mutAgo: Long = 0,
    val netAgo: Long = 0,
    /** Since Clarity's own last step on this page (a click armed, a scroll); large when none. */
    val actAgo: Long = 1_000_000,
    val busy: String? = null,
)

/**
 * Pure: why the page has not settled yet, or null when it has: the document is ready, no request
 * finished and the DOM did not change for [quietMillis], also counted from Clarity's own last step
 * (a request still in flight is invisible to resource timing, so a step gets that long to start
 * one), and no loading indicator is on screen. Playwright's `networkidle` and browser-use's wait
 * both use a 500 ms quiet window.
 */
fun settleWait(p: SettleProbe?, quietMillis: Long = 500): String? = when {
    p == null -> "the page does not answer yet"
    p.ready == "loading" -> "the document is still loading"
    p.busy != null -> "a loading indicator is showing: \"${p.busy}\""
    p.actAgo < quietMillis -> "the page is still answering the step"
    p.netAgo < quietMillis -> "requests are still finishing"
    p.mutAgo < quietMillis -> "the page is still changing"
    else -> null
}

private val AUTH_HOST = Regex("^(login|logon|auth|signin|sso|id|identity|account|accounts|secure)\\.", RegexOption.IGNORE_CASE)
private val AUTH_PATH = Regex(
    "(^|[/._-])(log-?in|log-?on|sign-?in|sign_in|signon|auth|oauth2?|authorize|sso|saml|mfa|2fa|otp|verify|verification|challenge|identity)([/._-]|$)",
    RegexOption.IGNORE_CASE,
)

/** Pure: [url] looks like a sign-in page (an auth host such as login.…, or a sign-in path). */
fun isAuthUrl(url: String): Boolean {
    val host = Sites.host(url) ?: return false
    val path = url.substringAfter(host, "").substringBefore('?').substringBefore('#')
    return AUTH_HOST.containsMatchIn(host) || AUTH_PATH.containsMatchIn(path)
}

/** Pure: an address without its query and fragment: what identifies a page of a sign-in flow. */
fun flowKey(url: String): String = url.substringBefore('#').substringBefore('?').removeSuffix("/")

/** What a page shows about being signed in. */
data class SignInSignals(
    /** A password or code field is on the page. */
    val loginForm: Boolean,
    val authUrl: Boolean,
    /** A sign-out control or a greeting is visible. */
    val account: Boolean,
    /** The names of the site's readable cookies changed since it was first seen in this run. */
    val cookiesChanged: Boolean,
    /** Jev's `sign_in` choice and its probability, when asked. */
    val jev: Pair<String, Double>? = null,
    val expired: Boolean = false,
)

/**
 * Pure: is the person signed in? True only on firm signals: no login form, not a sign-in address,
 * and two of a sign-out control or greeting, changed session cookies, and Jev seeing a signed-in
 * page; or Jev sure (p ≥ 0.8) with one of the others. False when a login form or an expiry message
 * shows; null when it cannot be told.
 */
fun signedInVerdict(s: SignInSignals): Boolean? {
    if (s.expired) return false
    if (s.loginForm) return false
    if (s.authUrl) return null
    val jevSays = s.jev?.takeIf { it.first == "signed_in" }?.second ?: 0.0
    val jevAgainst = s.jev?.let { it.first == "needs_login" && it.second >= 0.7 } == true
    if (jevAgainst) return null
    val firm = listOf(s.account, s.cookiesChanged, jevSays >= 0.5).count { it }
    return if (firm >= 2) true else null
}

private val SIGN_IN_STARTER = Regex("^(sign ?in|log ?in|log ?on|sign in or (create|register).*|(sign|log) ?in with .*|(sign|log) ?in to .*|continue with (email|phone|google|apple|facebook).*)$", RegexOption.IGNORE_CASE)

/**
 * Pure: would acting on [label] on [pageUrl] start the sign-in again? When [signedIn] and no login
 * form is on the page: a control named like "Sign in", or one that started this run's sign-in on
 * that page ([starters], page key to label).
 */
fun restartsSignIn(signedIn: Boolean, loginForm: Boolean, pageUrl: String, label: String?, starters: Set<Pair<String, String>>): Boolean {
    if (!signedIn || loginForm || label.isNullOrBlank()) return false
    val name = label.trim()
    return SIGN_IN_STARTER.matches(name) || (flowKey(pageUrl) to name.lowercase()) in starters
}

private val PRESS = Regex("""^press (the )?(enter|return|escape|esc|tab|space|arrowdown|arrowup|arrow down|arrow up)( key)?\.?$""", RegexOption.IGNORE_CASE)

/** Pure: an intent that is a key press ("press Escape"), as the key name press_key takes. */
fun keyIntent(intent: String): String? = PRESS.find(intent.trim())?.groupValues?.get(2)?.lowercase()?.let {
    when (it) {
        "enter", "return" -> "Enter"
        "escape", "esc" -> "Escape"
        "tab" -> "Tab"
        "space" -> "Space"
        "arrowdown", "arrow down" -> "ArrowDown"
        else -> "ArrowUp"
    }
}

/** Pure: words of a label or intent, lower-case, without punctuation or filler. */
fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in FILLER }

private val FILLER = setOf("the", "a", "an", "button", "link", "field", "option", "box", "on", "to", "of", "for", "page", "tab")

/** Pure: the one element whose name is exactly [intent] (words compared), or null when none or several. */
fun exactMatch(items: List<SeenElement>, intent: String): SeenElement? {
    val want = words(intent)
    if (want.isEmpty()) return null
    val hits = items.filter { it.role != "text" && it.role != "heading" && words(it.name) == want }
    return hits.singleOrNull()
}

/** Pure: up to [max] candidates for [intent] (those sharing a word first, then the rest in page order). */
fun candidates(items: List<SeenElement>, intent: String, max: Int = 20): List<SeenElement> {
    val want = words(intent).toSet()
    val acting = items.filter { it.role != "text" && it.role != "heading" }
    val (near, far) = acting.partition { e -> words(e.name).any { it in want } }
    return (near + far).take(max)
}

/**
 * Pure: the element to ask Jev about again by its own name when it did not see [intent]: the exact
 * match, else the one control sharing the most words with the intent (null when none, or a tie).
 */
fun renameCandidate(items: List<SeenElement>, intent: String): SeenElement? {
    exactMatch(items, intent)?.let { return it }
    val want = words(intent).toSet()
    val scored = items.filter { it.role != "text" && it.role != "heading" && it.name.isNotBlank() }
        .map { it to words(it.name).count { w -> w in want } }
        .filter { it.second > 0 }
    val best = scored.maxOfOrNull { it.second } ?: return null
    return scored.filter { it.second == best }.singleOrNull()?.first
}

/**
 * Pure: the element a box hit fits [intent] at least as well as the page's best candidate for it
 * ([renameCandidate]); true when there is no candidate to compare with.
 */
fun groundingAgrees(hit: SeenElement, items: List<SeenElement>, intent: String): Boolean {
    // "the Reject all button next to Accept all" fits both: when the hit is one of several equal fits, it is a guess.
    if (tiedCandidates(items, intent).let { tie -> tie.size > 1 && tie.any { sameElement(it, hit) } }) return false
    val candidate = renameCandidate(items, intent) ?: return true
    if (sameElement(hit, candidate)) return true
    val want = words(intent).toSet()
    return words(hit.name).count { it in want } >= words(candidate.name).count { it in want }
}

/** Pure: [element]'s rect on a screenshot [scale] times its CSS pixels. */
fun pixelRect(element: SeenElement, scale: Double): PixelRect =
    PixelRect((element.x * scale).toInt(), (element.y * scale).toInt(), (element.w * scale).toInt().coerceAtLeast(1), (element.h * scale).toInt().coerceAtLeast(1))

/** Pure: the controls that share the most words with [intent], when there is no exact match (more than one is a tie). */
fun tiedCandidates(items: List<SeenElement>, intent: String): List<SeenElement> {
    if (exactMatch(items, intent) != null) return emptyList()
    val want = words(intent).toSet()
    val scored = items.filter { it.role != "text" && it.role != "heading" && it.name.isNotBlank() }
        .map { it to words(it.name).count { w -> w in want } }
        .filter { it.second > 0 }
    val best = scored.maxOfOrNull { it.second } ?: return emptyList()
    return scored.filter { it.second == best }.map { it.first }
}

/** Pure: the second grounding question's target, by the element's own visible name. */
fun namedIntent(element: SeenElement): String = "the control labelled \"${element.name}\""

/** Pure: [a] is [b] (same ref, or same role and name on a re-rendered page). */
fun sameElement(a: SeenElement, b: SeenElement): Boolean = a.ref == b.ref || (a.role == b.role && words(a.name) == words(b.name))

/** Pure: the observation script's arguments: [extra] plus the refs whose values are secrets. */
fun observeArgs(secretRefs: Collection<String>, extra: Map<String, Any?> = emptyMap()): JsonObject = buildJsonObject {
    put("secret", JsonArray(secretRefs.map(::JsonPrimitive)))
    extra.forEach { (k, v) ->
        when (v) {
            null -> {}
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is Collection<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
            else -> put(k, v.toString())
        }
    }
}
