package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.BrowserPilotConfig
import net.robog.clarity.backend.governance.PersonZone
import net.robog.clarity.backend.vault.Placeholders
import net.robog.clarity.backend.vault.Sites
import net.robog.clarity.backend.vault.VaultGovernance
import net.robog.clarity.backend.vault.VaultStore
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.vault.FieldType

private val log = KotlinLogging.logger {}

/**
 * The helper's browser. The helper (a text model) plans in words; Jev grounds, double-checks and
 * verifies each step on a screenshot, so what it acts on is what a person would see (hidden text,
 * aria labels and off-screen print never reach it), and every step leaves an audit row.
 *
 * Tools: `look` (a compact text view with no depth limit), `act(intent, expect, value?)` (one action
 * in words, followed by a change report), `read`, `tabs`, `go`, `back`, `wait`, `sign_in(credential)`
 * (a fixed routine through `ask_code`), `screenshot` and `downloads`. Every step reaches the browser
 * through [BrowserGate.perform]: placeholders, rules, held approvals and scrubbing are the gate's.
 *
 * Fails closed: without Jev, or when it errors, a step is not taken (or goes to the person); it
 * never clicks blind.
 */
@Inject
@SingleIn(AppScope::class)
class BrowserPilot(
    private val gate: BrowserGate,
    private val models: BrowserModels,
    private val audit: BrowserAudit,
    private val vault: VaultStore,
    private val governance: VaultGovernance,
    config: AgentConfig,
    private val clock: Clock,
    private val zone: PersonZone,
    /** How long a step is given to take effect before the change report is built. */
    private val settle: Duration = 700.milliseconds,
) {
    private val settings: BrowserPilotConfig = config.browser

    /** Per run: actions taken, steps without visible change, sign-in state per site, audit sequence. */
    private class RunState {
        var actions = 0
        var noChange = 0
        var mustLook = false
        var seq = 0
        val signIn = ConcurrentHashMap<String, SignInState>()
    }

    private val runs = ConcurrentHashMap<String, RunState>()

    /** Shared by every run (there is one browser): popups and their openers, secret-filled elements, the last tab list. */
    @Volatile private var openers: Map<Int, Int> = emptyMap()
    @Volatile private var secretRefs: Pair<String, Set<String>> = "" to emptySet()
    @Volatile private var lastTabs: List<Tab> = emptyList()

    private fun run(scope: RunScope): RunState {
        if (runs.size > MAX_RUNS) runs.keys.take(runs.size - MAX_RUNS).forEach { runs.remove(it) }
        return runs.getOrPut(scope.runId) { RunState() }
    }

    /**
     * [tools] with the raw `browser__*` tools replaced by the pilot's; other tools untouched. Without
     * the browser's script tool the pilot cannot see, and the helper gets the gated raw tools instead.
     */
    fun wrap(scope: RunScope, tools: List<Tool>): List<Tool> {
        val raw = tools.filter { it.name.startsWith(BrowserGate.PREFIX) }.associateBy { it.name.removePrefix(BrowserGate.PREFIX) }
        val others = tools.filterNot { it.name.startsWith(BrowserGate.PREFIX) }
        if (raw.isEmpty()) return others
        if (REQUIRED.any { it !in raw }) {
            log.warn { "browser lacks ${REQUIRED.filter { it !in raw }}; the helper gets the raw tools" }
            return gate.gatedTools(scope, tools)
        }
        return others + Session(scope, raw).tools()
    }

    @Serializable
    class LookInput(@Description("A ref from an earlier look (\"c12\") to list only what is inside that element.") val scope: String? = null)

    @Serializable
    class ActInput(
        @Description("The one element to act on, in words a person would use for what they see: \"the SMS option\", \"the Continue button\", \"the email field\". Or \"scroll down\" / \"scroll up\", or \"press Escape\".")
        val intent: String,
        @Description("What should be on the screen afterwards, in words: \"a page asking for a 6-digit code\". The step is checked against it.")
        val expect: String,
        @Description("Text to type into the element (a field). May be a placeholder such as [secret:bank_login_password] or a code placeholder from ask_code. Omit to click.")
        val value: String? = null,
    )

    @Serializable
    class ReadInput(@Description("A ref from look to read only that part of the page.") val scope: String? = null)

    @Serializable
    class TabsInput(
        @Description("Tab number to switch to.") val select: Int? = null,
        @Description("Tab number to close.") val close: Int? = null,
    )

    @Serializable
    class GoInput(@Description("The address to open in the selected tab.") val url: String)

    @Serializable
    class WaitInput(
        @Description("Wait until this text is on the page (up to 20 seconds).") val text: String? = null,
        @Description("Or wait this many seconds (1-30).") val seconds: Int? = null,
    )

    @Serializable
    class SignInInput(@Description("The saved credential's name (see list_credentials).") val credential: String)

    @Serializable
    class NoInput

    /** One helper run's view of the browser. */
    private inner class Session(private val scope: RunScope, private val raw: Map<String, Tool>) {
        private val pages = raw["list_pages"]
        private val state get() = run(scope)

        fun tools(): List<Tool> = listOf(
            tool<LookInput>(
                name = "${BrowserGate.PREFIX}look",
                description = "See the selected tab as text: tabs (with which tab opened which), the page, any dialog, the sign-in state for the site " +
                    "(codes sent), then every visible control and heading with its ref, name, value and state, at any depth. Call it first, " +
                    "after anything surprising, and whenever a step changed nothing. " + TABS_HINT,
                json = ClarityJson,
            ) { input -> guarded { look(input.scope) } },
            tool<ActInput>(
                name = "${BrowserGate.PREFIX}act",
                description = "Do one thing on the page, named in words (not a ref): click it, or type `value` into it. The element is found on a " +
                    "screenshot, the step is checked, then you get a report of what changed (address, tabs opened or closed, new text) and " +
                    "whether `expect` happened. A new tab it opens is selected for you. " + BrowserGate.PLACEHOLDER_HINT + " " + BrowserGate.DOWNLOAD_HINT,
                effect = ToolEffect.EXTERNAL,
                json = ClarityJson,
            ) { input -> guarded { act(input.intent.trim(), input.expect.trim(), input.value) } },
            tool<ReadInput>(
                name = "${BrowserGate.PREFIX}read",
                description = "Read the visible text of the page (or of one part of it, by ref from look). Hidden and off-screen text is left out.",
                json = ClarityJson,
            ) { input -> guarded { read(input.scope) } },
            tool<TabsInput>(
                name = "${BrowserGate.PREFIX}tabs",
                description = "List the open tabs (which tab opened which), or switch to one (`select`) or close one (`close`).",
                effect = ToolEffect.EXTERNAL,
                json = ClarityJson,
            ) { input -> guarded { tabs(input.select, input.close) } },
            tool<GoInput>(
                name = "${BrowserGate.PREFIX}go",
                description = "Open an address in the selected tab. Refused while a sign-in tab this tab opened is still open (finish or close it first).",
                effect = ToolEffect.EXTERNAL,
                json = ClarityJson,
            ) { input -> guarded { go(input.url.trim()) } },
            tool<NoInput>(
                name = "${BrowserGate.PREFIX}back",
                description = "Go back one page in the selected tab.",
                effect = ToolEffect.EXTERNAL,
                json = ClarityJson,
            ) { guarded { back() } },
            tool<WaitInput>(
                name = "${BrowserGate.PREFIX}wait",
                description = "Wait for text to appear on the page, or for a few seconds (a page still loading).",
                json = ClarityJson,
            ) { input -> guarded { wait(input.text, input.seconds) } },
            tool<SignInInput>(
                name = "${BrowserGate.PREFIX}sign_in",
                description = "Sign in on the page that is open, with a saved login: fills the login fields, submits, and checks whether you are " +
                    "signed in. On a page asking for a one-time code it asks the person for it (ask_code), types it and verifies. It never asks " +
                    "the site for another code. It reports anything else (pick where a code goes with act, then call sign_in again).",
                effect = ToolEffect.EXTERNAL,
                json = ClarityJson,
            ) { input -> guarded { signIn(input.credential.trim()) } },
            tool<NoInput>(
                name = "${BrowserGate.PREFIX}screenshot",
                description = "Take a screenshot of the selected tab (refused right after a secret was typed). Prefer look.",
                json = ClarityJson,
            ) { guarded { screenshot() } },
            tool<NoInput>(
                name = "${BrowserGate.PREFIX}downloads",
                description = "List the files the browser downloaded (they are in the sandbox at /workspace/downloads).",
                json = ClarityJson,
            ) { guarded { gate.scrubbed(gate.withBrowser { mcp("list_downloads") }) } },
        )

        private suspend fun guarded(block: suspend () -> ToolResult): ToolResult = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "browser pilot step failed" }
            ToolResult.Error("The browser step failed: ${gate.scrub(e.message ?: e::class.simpleName.orEmpty())}")
        }

        // ---- browser access -------------------------------------------------------------------

        private suspend fun mcp(name: String, input: JsonObject = JsonObject(emptyMap())): ToolResult =
            raw[name]?.invoke(input) ?: ToolResult.Error("the browser has no $name")

        private suspend fun locked(name: String, input: JsonObject = JsonObject(emptyMap())): ToolResult = gate.withBrowser { mcp(name, input) }

        private fun refsNow(url: String): Set<String> = secretRefs.takeIf { it.first == pageKey(url) }?.second ?: emptySet()

        /** Runs the observation script [op]; [lock] false when the caller already holds the browser. The reply is not scrubbed. */
        private suspend fun observe(op: String, extra: Map<String, Any?> = emptyMap(), lock: Boolean = true): JsonElement? {
            val refs = secretRefs.second
            val input = buildJsonObject {
                put("function", ObserveScript.call(op, observeArgs(refs, extra)))
                put("sandbox", ObserveScript.SANDBOX)
                put("timeout", 8000)
            }
            val result = if (lock) locked("evaluate_script", input) else mcp("evaluate_script", input)
            // Parsed as the page gave it (scrubbing could cut a released value out of an address); whatever is shown or stored is scrubbed.
            return (result as? ToolResult.Ok)?.content?.let(::parseScriptResult)
        }

        private suspend fun page(op: String = "look", extra: Map<String, Any?> = emptyMap(), lock: Boolean = true): PageObservation? =
            decodeObservation<PageObservation>(observe(op, extra, lock))

        private suspend fun listTabs(lock: Boolean = true): List<Tab> =
            ((if (lock) locked("list_pages") else mcp("list_pages")) as? ToolResult.Ok)?.content?.let(::parseTabs).orEmpty()

        private suspend fun selectTab(index: Int, lock: Boolean = true): Boolean {
            val input = buildJsonObject { put("pageIdx", index) }
            return (if (lock) locked("select_page", input) else mcp("select_page", input)) is ToolResult.Ok
        }

        /** A screenshot of the selected tab for Jev, masked where a secret was typed; see [Frame]. */
        private suspend fun frame(url: String?): Frame {
            val shot = locked("screenshot_page")
            val image = (shot as? ToolResult.Ok)?.images?.firstOrNull() ?: return Frame(null, "no screenshot (${(shot as? ToolResult.Error)?.message?.take(80) ?: "no image"})", false)
            val png = runCatching { Base64.getDecoder().decode(image.base64) }.getOrNull() ?: return Frame(null, "unreadable screenshot", false)
            val (w, h) = pngSize(png) ?: return Frame(null, "the screenshot is not a PNG", false)
            if (!jevAcceptsSize(w, h)) return Frame(null, "the screenshot is ${w}x$h, a size Jev does not take", false)
            if (!gate.state.secretMayBeVisible(Sites.host(url.orEmpty()))) return Frame(png, null, false, w, h)
            // A secret was typed a moment ago: Jev only gets the frame with those fields blacked out, and it is never stored.
            val refs = refsNow(url.orEmpty())
            if (refs.isEmpty()) return Frame(png, null, true, w, h)
            val rects = decodeObservation<RectsReply>(observe("rects", mapOf("refs" to refs)))?.rects ?: return Frame(null, "could not find the secret fields to hide", true)
            val masked = editPng(png, rects.map { it.scaled(w, h, null) }) ?: return Frame(null, "could not hide the secret fields", true)
            return Frame(masked, null, true, w, h)
        }

        // ---- tabs and popups ------------------------------------------------------------------

        /** Notices tabs that closed on their own since the last step (a sign-in popup finishing) and goes back to the opener. */
        private suspend fun syncTabs(): List<String> {
            val now = listTabs()
            val notes = mutableListOf<String>()
            if (lastTabs.isNotEmpty() && now.size < lastTabs.size) {
                val closed = diffTabs(lastTabs, now, openers.keys).closed
                if (closed != null) notes += closedTab(closed, wasSelected = lastTabs.getOrNull(closed)?.selected == true || now.none { it.selected }, now)
            }
            lastTabs = listTabs().ifEmpty { now }
            return notes
        }

        /** Tab [closed] went away: forget it, and when it was the selected one, go back to its opener. */
        private suspend fun closedTab(closed: Int, wasSelected: Boolean, now: List<Tab>, lock: Boolean = true): List<String> {
            val opener = openers[closed]
            openers = shiftAfterClose(openers, closed)
            if (!wasSelected) return listOf("Tab $closed closed.")
            val back = opener?.let { if (it > closed) it - 1 else it } ?: now.lastIndex.takeIf { it >= 0 } ?: return listOf("Tab $closed closed; no tab is open.")
            selectTab(back, lock)
            val title = now.getOrNull(back)?.title.orEmpty()
            return listOf("Tab $closed closed" + (if (opener != null) " (it was a popup of tab $back)" else "") + "; back on tab $back: $title")
        }

        private fun selectedIndex(tabs: List<Tab>): Int? = tabs.firstOrNull { it.selected }?.index

        /** A popup the selected tab opened that is still open. */
        private fun openPopupOf(tabs: List<Tab>): Int? {
            val selected = selectedIndex(tabs) ?: return null
            return openers.entries.firstOrNull { (popup, opener) -> opener == selected && tabs.any { it.index == popup } }?.key
        }

        // ---- tools ----------------------------------------------------------------------------

        suspend fun look(scopeRef: String?): ToolResult {
            val notes = syncTabs()
            val obs = page("look", mapOf("scope" to scopeRef)) ?: return ToolResult.Error("Could not read the page (it may still be loading, or a browser dialog is open). Try wait, then look.")
            state.noChange = 0
            state.mustLook = false
            updatePhase(obs)
            val header = LookHeader(lastTabs, openers, state.signIn[Sites.registrableDomain(obs.url).orEmpty()], zone.zone())
            return ToolResult.Ok(gate.scrub((notes.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" }) + formatLook(obs, header, scoped = scopeRef != null)))
        }

        suspend fun read(scopeRef: String?): ToolResult {
            syncTabs()
            val reply = decodeObservation<ReadReply>(observe("read", mapOf("scope" to scopeRef, "max" to READ_BUDGET)))
                ?: return ToolResult.Error("Could not read the page. Try wait, then look.")
            val tab = lastTabs.firstOrNull { it.selected }
            return ToolResult.Ok(
                gate.scrub(
                    "Page: ${tab?.title.orEmpty()} — ${tab?.url.orEmpty()}\n" + reply.text.ifBlank { "(no visible text)" } +
                        (if (reply.truncated) "\n(cut off – read(scope=…) a part of the page)" else ""),
                ),
            )
        }

        suspend fun tabs(select: Int?, close: Int?): ToolResult {
            val notes = syncTabs().toMutableList()
            if (close != null) {
                val before = listTabs()
                if (before.none { it.index == close }) return ToolResult.Error("There is no tab $close.")
                val wasSelected = before.first { it.index == close }.selected
                locked("close_page", buildJsonObject { put("pageIdx", close) })
                notes += closedTab(close, wasSelected, listTabs())
            }
            if (select != null) {
                if (!selectTab(select)) return ToolResult.Error("There is no tab $select.")
                notes += "Selected tab $select."
            }
            lastTabs = listTabs()
            return ToolResult.Ok(gate.scrub((notes + tabsLine(lastTabs, openers)).joinToString("\n")))
        }

        suspend fun go(url: String): ToolResult {
            syncTabs()
            limit()?.let { return it }
            openPopupOf(lastTabs)?.let { return ToolResult.Error("Refused: tab $it, a sign-in tab this tab opened, is still open: finish or close the sign-in tab first (tabs).") }
            state.actions++
            return navigate("go", "navigate_page", buildJsonObject { put("url", url) }, url)
        }

        suspend fun back(): ToolResult {
            syncTabs()
            limit()?.let { return it }
            openPopupOf(lastTabs)?.let { return ToolResult.Error("Refused: tab $it, a sign-in tab this tab opened, is still open: finish or close the sign-in tab first (tabs).") }
            state.actions++
            return navigate("back", "navigate_history", buildJsonObject { put("direction", "back") }, null)
        }

        private suspend fun navigate(tool: String, remote: String, input: JsonObject, url: String?): ToolResult {
            val before = page("look")
            val result = gate.perform(scope, GateStep(remote, input, BrowserKind.NAVIGATE, pages) { mcp(remote, it) })
            if (result is ToolResult.Error) return result
            delay(settle)
            val after = page("changes") ?: before
            lastTabs = listTabs()
            val report = buildString {
                append(if (after?.url != before?.url) "Now on: ${after?.title.orEmpty()} — ${after?.url.orEmpty()}" else "Still on: ${after?.title.orEmpty()} — ${after?.url.orEmpty()}")
                if (after?.dialog != null) append("\nDialog open: \"${after.dialog}\"")
                append("\nCall look to see the page.")
            }
            record(tool, before, url ?: "back", null, null, null, null, "passed", "$remote $input", null, report)
            return ToolResult.Ok(gate.scrub(report))
        }

        suspend fun wait(text: String?, seconds: Int?): ToolResult {
            if (text.isNullOrBlank()) {
                delay((seconds ?: 2).coerceIn(1, 30).seconds)
                return ToolResult.Ok("Waited ${(seconds ?: 2).coerceIn(1, 30)} seconds.")
            }
            val started = TimeSource.Monotonic.markNow()
            while (started.elapsedNow() < 20.seconds) {
                val reply = decodeObservation<ReadReply>(observe("read", mapOf("max" to 20_000)))
                if (reply != null && reply.text.contains(text, ignoreCase = true)) return ToolResult.Ok("\"$text\" is on the page.")
                delay(1.seconds)
            }
            return ToolResult.Ok("\"$text\" did not appear within 20 seconds. Call look.")
        }

        suspend fun screenshot(): ToolResult {
            val shot = raw["screenshot_page"] ?: return ToolResult.Error("the browser has no screenshot tool")
            return gate.scrubbed(gate.withBrowser { gate.capture(shot, JsonObject(emptyMap()), pages) })
        }

        private fun limit(): ToolResult? =
            if (state.actions >= settings.maxActions) ToolResult.Ok("This browser task has used its ${settings.maxActions} actions. Stop and report what you found and what is left.")
            else null

        // ---- act ------------------------------------------------------------------------------

        suspend fun act(intent: String, expect: String, value: String?): ToolResult {
            val notes = syncTabs()
            limit()?.let { return it }
            if (state.mustLook) return ToolResult.Ok("Two steps in a row changed nothing visible. Call look and rethink before acting again.")
            if (intent.isBlank()) return ToolResult.Error("`intent` must name what to act on.")
            scrollIntent(intent)?.let { dy ->
                val r = decodeObservation<ScrollReply>(observe("scroll", mapOf("dy" to dy)))
                return ToolResult.Ok(if (r == null) "Could not scroll." else "Scrolled ${if (dy > 0) "down" else "up"} (now ${r.scrollY.toInt()} px from the top). Call look to see what is there.")
            }
            val before = page("look") ?: return ToolResult.Error("Could not read the page (still loading, or a browser dialog is open). Try wait, then look.")
            val url = before.url
            val domain = Sites.registrableDomain(url)
            val trail = Trail()
            keyIntent(intent)?.let { key -> return pressKey(key, intent, expect, before, trail, notes) }
            state.actions++

            val frame = frame(url)
            val grounded = ground(intent, before, frame, trail)
            val hit = when (grounded) {
                is Ground.Hit -> grounded
                is Ground.NotFound -> {
                    record("act", before, intent, expect, frame, null, null, "not_found", null, trail, grounded.why)
                    return ToolResult.Ok(gate.scrub("Not found: ${grounded.why}. I did not act. Rephrase what you see, scroll, or narrow with look.\n\n" + lookText(before)))
                }
                is Ground.Failed -> {
                    record("act", before, intent, expect, frame, null, null, "failed", null, trail, grounded.why)
                    return ToolResult.Ok(gate.scrub("I could not check the screen (${grounded.why}), so I did not act; I never click blind. Report back if this keeps happening.\n\n" + lookText(before)))
                }
            }
            val element = hit.element
            if (value != null && element.role !in FILLABLE) {
                record("act", before, intent, expect, frame, hit, null, "refused", null, trail, "not a field")
                return ToolResult.Error(gate.scrub("\"${element.name}\" is a ${element.role}, not a field: act on it without `value`, or name the field to type into."))
            }

            val description = gate.scrub(describeAct(element, value, url))
            val signIn = domain?.let { state.signIn[it] }
            val askResend = (signIn?.codesSent ?: 0) > 0 && value == null
            val (view, resend) = approve(description, frame, hit.box, askResend, trail)
            if (value == null) {
                val guard = codeGuard(signIn, element.name, if (askResend) resend ?: 1.0 else null, settings.resendThreshold, clock.now(), zone.zone())
                if (guard is CodeGuard.Refuse) {
                    record("act", before, intent, expect, frame, hit, null, "code_guard", null, trail, guard.message)
                    return ToolResult.Ok(guard.message)
                }
            }

            val result = execute(element, value, before, frame, intent, expect, hit, trail, notes) { JevCheck(view, settings.jevMayApprove, settings.approveThreshold) }
            return result
        }

        /** A key press ("press Escape"): judged by the rules as press_key, reported like any step. */
        private suspend fun pressKey(key: String, intent: String, expect: String, before: PageObservation, trail: Trail, notes: List<String>): ToolResult {
            state.actions++
            val token = newToken()
            observe("arm", mapOf("token" to token))
            val tabsBefore = listTabs()
            val input = buildJsonObject { put("key", key) }
            var ran = false
            val result = gate.perform(scope, GateStep("press_key", input, BrowserKind.ACT, pages) { ran = true; mcp("press_key", it) })
            if (!ran) return result
            return report("act", intent, expect, before, null, null, tabsBefore, token, trail, notes, "press_key $key", result)
        }

        private fun describeAct(element: SeenElement, value: String?, url: String): String {
            val remote = if (value == null) "click_by_uid" else "fill_by_uid"
            val input = buildJsonObject { put("uid", element.ref); value?.let { put("value", it) } }
            return describeStep(remote, input, listOf(element.ref to element.element()), url)
        }

        /** Finds [intent] on the page: on the screenshot (Jev), or for an exact name match by the text decision model when allowed. */
        private suspend fun ground(intent: String, before: PageObservation, frame: Frame, trail: Trail): Ground {
            if (settings.textGrounding) exactMatch(before.items, intent)?.let { exact -> textGround(intent, before, exact, trail)?.let { return it } }
            val png = frame.png ?: return Ground.Failed(frame.why ?: "no screenshot")
            val jev = models.jev ?: return Ground.Failed("no screen model is configured")
            val answer = trail.ask("ground", models.jevModel, jev, groundingAsk(intent, png))
            val result = answer.getOrNull() ?: return Ground.Failed("Jev: ${answer.leftOrNull()?.message?.take(120)}")
            val g = groundingOf(result) ?: return Ground.Failed("Jev did not answer")
            if (g.pVisible < settings.visibleThreshold) return Ground.NotFound("\"$intent\" is not visible on the screen (Jev p=%.2f)".format(g.pVisible))
            val box = g.box ?: return Ground.Failed("Jev gave no box")
            val vw = before.vw.takeIf { it > 0 } ?: frame.width.toDouble()
            val vh = before.vh.takeIf { it > 0 } ?: frame.height.toDouble()
            val x = (box.left + box.right) / 2 * vw
            val y = (box.top + box.bottom) / 2 * vh
            val hit = page("point", mapOf("x" to x, "y" to y))?.hit ?: return Ground.NotFound("nothing to act on where \"$intent\" is on the screen")
            trail.box = box
            return Ground.Hit(hit, boxPixels(box, frame.width, frame.height), "screen")
        }

        /** The cheaper text path: Decision-1 confirms the exact match among up to 20 candidates; null falls back to the screenshot. */
        private suspend fun textGround(intent: String, before: PageObservation, exact: SeenElement, trail: Trail): Ground? {
            val text = models.text ?: return null
            val options = candidates(before.items, intent).let { if (exact in it) it else (listOf(exact) + it).take(20) }
            val answer = trail.ask("pick", models.textModel, text, pickAsk(intent, options)).getOrNull() ?: return null
            val choice = answer.answers[Q_PICK] as? DecisionAnswer.Choice ?: return null
            val p = choice.probabilities[choice.choice] ?: choice.confidence ?: 0.0
            if (choice.choice != exact.ref || p < settings.visibleThreshold) return null
            return Ground.Hit(exact, null, "text")
        }

        /** Jev's check of the step on the screenshot with the target outlined; a failure is a [JevView] that asks the person. */
        private suspend fun approve(description: String, frame: Frame, box: PixelRect?, askResend: Boolean, trail: Trail): Pair<JevView, Double?> {
            val jev = models.jev ?: return JevView(null, 0.0, "no screen model is configured") to null
            val outlined = frame.png?.let { png -> box?.let { editPng(png, outline = it) } ?: png }
            val where = if (frame.png != null && box != null && outlined === frame.png) " (at x=${box.x}..${box.x + box.w}, y=${box.y}..${box.y + box.h} px)" else ""
            val answer = trail.ask("approve", models.jevModel, jev, approvalAsk(description + where, scope.purpose, outlined, askResend))
            val result = answer.getOrNull() ?: return JevView(null, 0.0, "Jev: ${answer.leftOrNull()?.message?.take(100)}") to null
            return approvalOf(result).also { (v, _) -> trail.gateNote = "jev ${v.step?.wire ?: "none"} p=%.2f".format(v.p) }
        }

        /** Clicks or fills [element] through the gate, then builds the change report. */
        private suspend fun execute(
            element: SeenElement,
            value: String?,
            before: PageObservation,
            frame: Frame,
            intent: String,
            expect: String,
            hit: Ground.Hit,
            trail: Trail,
            notes: List<String>,
            jev: (suspend (String) -> JevCheck)?,
        ): ToolResult {
            val target = page("target", mapOf("ref" to element.ref, "scroll" to true))
            val uid = target?.token?.let { token -> snapshotUid(token) }
            val id = uid ?: element.ref
            val remote = if (value == null) "click_by_uid" else "fill_by_uid"
            val input = buildJsonObject { put("uid", id); value?.let { put("value", it) } }
            if (value != null && Placeholders.tokens(value).isNotEmpty()) noteSecretRef(before.url, element.ref)
            val token = newToken()
            observe("arm", mapOf("token" to token))
            val tabsBefore = listTabs()
            var ran = false
            val result = gate.perform(
                scope,
                GateStep(remote, input, BrowserKind.ACT, pages, targets = listOf(id to element.element()), jev = jev) { sent ->
                    ran = true
                    send(element, uid, sent)
                },
            )
            if (!ran) {
                record("act", before, intent, expect, frame, hit, null, if (result is ToolResult.Error) "error" else "held", "$remote ${input}", trail, (result as? ToolResult.Ok)?.content)
                return result
            }
            return report("act", intent, expect, before, element, frame, tabsBefore, token, trail, notes, "$remote $input", result, hit)
        }

        /** Sends one click or fill to the browser (holding it): by snapshot uid when there is one, else by focusing the element and using the keyboard. */
        private suspend fun send(element: SeenElement, uid: String?, sent: JsonObject): ToolResult {
            val value = (sent["value"] as? JsonPrimitive)?.contentOrNull
            if (uid != null) {
                return if (value == null) mcp("click_by_uid", buildJsonObject { put("uid", uid) })
                else mcp("fill_by_uid", buildJsonObject { put("uid", uid); put("value", value) })
            }
            // Inside a shadow root or a frame the snapshot cannot reach it: trusted key events to the focused element instead.
            val focused = decodeObservation<FocusReply>(observe("focus", mapOf("ref" to element.ref, "clear" to (value != null)), lock = false))?.ok == true
            if (!focused) return ToolResult.Error("Could not reach \"${element.name}\" on the page.")
            return if (value != null) mcp("type_text", buildJsonObject { put("text", value) })
            else mcp("press_key", buildJsonObject { put("key", if (element.role == "checkbox" || element.role == "radio" || element.role == "switch") "Space" else "Enter") })
        }

        /** The snapshot uid of the element marked [token], or null (not reachable by a selector). */
        private suspend fun snapshotUid(token: String): String? {
            val snap = locked("take_snapshot", buildJsonObject { put("selector", "[data-clarity-ref=\"$token\"]"); put("maxLines", 3) })
            return (snap as? ToolResult.Ok)?.content?.let(::parseSnapshot)?.keys?.firstOrNull()
        }

        private fun noteSecretRef(url: String, ref: String) {
            val key = pageKey(url)
            secretRefs = key to ((secretRefs.takeIf { it.first == key }?.second ?: emptySet()) + ref)
        }

        /** After a step ran: tabs, page changes, Jev's verification, the code count, the audit row. */
        private suspend fun report(
            tool: String,
            intent: String,
            expect: String,
            before: PageObservation,
            element: SeenElement?,
            frame: Frame?,
            tabsBefore: List<Tab>,
            token: String,
            trail: Trail,
            notes: List<String>,
            action: String,
            result: ToolResult,
            hit: Ground.Hit? = null,
        ): ToolResult {
            delay(settle)
            var tabsAfter = listTabs()
            val diff = diffTabs(tabsBefore, tabsAfter, openers.keys)
            val tabNotes = notes.toMutableList()
            val selectedBefore = selectedIndex(tabsBefore)
            if (diff.opened.isNotEmpty()) {
                val newest = diff.opened.last()
                selectTab(newest.index)
                if (selectedBefore != null) openers = openers + (newest.index to selectedBefore)
                delay(settle)
                tabsAfter = listTabs()
                val title = tabsAfter.getOrNull(newest.index)?.title ?: newest.title
                tabNotes += "Opened tab ${newest.index} (opener ${selectedBefore ?: "?"}): $title — selected it."
            }
            diff.closed?.let { closed -> tabNotes += closedTab(closed, closed == selectedBefore || tabsAfter.none { it.selected }, tabsAfter); tabsAfter = listTabs() }
            lastTabs = tabsAfter
            val after = waitForPage(token) ?: before.copy(same = false, added = emptyList())
            val targetAfter = element?.takeIf { after.same }?.let { page("target", mapOf("ref" to it.ref))?.hit }
            val domain = Sites.registrableDomain(after.url)
            val signIn = domain?.let { state.signIn[it] } ?: SignInState()
            val codeSent = codeSentBy(before, after, element?.name, signIn.triggers)
            if (codeSent && domain != null) {
                state.signIn[domain] = signIn.copy(
                    codesSent = signIn.codesSent + 1, lastSentAt = clock.now(), phase = SignInPhase.CODE_SENT,
                    triggers = signIn.triggers + listOfNotNull(element?.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }),
                )
            }
            updatePhase(after)
            val afterFrame = frame(after.url)
            val (verified, verifyNote) = verify(expect, action, afterFrame, trail)
            val change = StepChange(before, after, diff, tabNotes, element, targetAfter, verified, verifyNote, expect, codeSent)
            val changed = visiblyChanged(change)
            state.noChange = if (changed) 0 else state.noChange + 1
            if (state.noChange >= 2) state.mustLook = true
            val text = (result as? ToolResult.Ok)?.content?.takeIf { it.isNotBlank() && !it.startsWith("click ") && !it.startsWith("Filled") && !it.startsWith("Pressed") }
            val body = changeReport(change, state.noChange) + (text?.let { "\n(browser: ${it.take(300)})" } ?: "")
            record(tool, before, intent, expect, frame, hit, afterFrame, trail.gateNote?.let { "passed ($it)" } ?: "passed", action, trail, body, element)
            return ToolResult.Ok(gate.scrub(body))
        }

        /** The page after a step, once it answers (a navigation may still be loading). */
        private suspend fun waitForPage(token: String): PageObservation? {
            repeat(6) { attempt ->
                page("changes", mapOf("token" to token))?.let { if (it.url.isNotBlank()) return it }
                delay((300 * (attempt + 1)).milliseconds)
            }
            return null
        }

        private suspend fun verify(expect: String, description: String, frame: Frame, trail: Trail): Pair<Double?, String?> {
            if (expect.isBlank()) return null to null
            val png = frame.png ?: return null to "Not checked on the screen (${frame.why ?: "no screenshot"})."
            val jev = models.jev ?: return null to "Not checked on the screen (no screen model)."
            val answer = trail.ask("verify", models.jevModel, jev, verifyAsk(expect, description, png))
            val p = (answer.getOrNull()?.answers?.get(Q_HAPPENED) as? DecisionAnswer.Noul)?.probability
            return p to (if (p == null) "Not checked on the screen (Jev: ${answer.leftOrNull()?.message?.take(80) ?: "no answer"})." else null)
        }

        private fun updatePhase(obs: PageObservation) {
            val domain = Sites.registrableDomain(obs.url) ?: return
            val current = state.signIn[domain] ?: if (obs.password || obs.otp) SignInState() else return
            val phase = when {
                obs.otp -> if (current.codesSent > 0) SignInPhase.CODE_SENT else current.phase
                obs.password -> SignInPhase.LOGIN
                else -> current.phase
            }
            state.signIn[domain] = current.copy(phase = phase)
        }

        private suspend fun lookText(obs: PageObservation): String =
            formatLook(obs, LookHeader(lastTabs, openers, state.signIn[Sites.registrableDomain(obs.url).orEmpty()], zone.zone()), budget = LOOK_BUDGET / 2)

        // ---- sign_in --------------------------------------------------------------------------

        suspend fun signIn(credentialName: String): ToolResult {
            syncTabs()
            limit()?.let { return it }
            val credential = vault.byName(credentialName)
                ?: return ToolResult.Error("No saved credential \"$credentialName\". Saved: ${vault.list().joinToString { it.name }.ifEmpty { "none" }} (see list_credentials).")
            val messages = mutableListOf<String>()
            repeat(2) {
                val fields = decodeObservation<SignInFields>(observe("signin")) ?: return ToolResult.Error("Could not read the page. Try wait, then look.")
                val url = fields.url
                val domain = Sites.registrableDomain(url) ?: return ToolResult.Error("Cannot tell which site this page is.")
                state.actions++
                val before = page("look") ?: PageObservation(url = url)
                val trail = Trail()
                val tabsBefore = listTabs()
                val token = newToken()
                val step: String
                when {
                    fields.otp != null -> {
                        val current = state.signIn[domain] ?: SignInState()
                        if (current.codesSent == 0) state.signIn[domain] = current.copy(codesSent = 1, lastSentAt = clock.now(), phase = SignInPhase.CODE_SENT)
                        val ask = governance.askCode(url, "the sign-in code $domain just sent", scope.provenance())
                        val heldId = ask.heldId ?: return ToolResult.Error(ask.text)
                        val placeholder = "[secret:code_${heldId.value}]"
                        messages += "Asked the person for the code $domain sent."
                        fill(fields.otp, placeholder, url)?.let { return ToolResult.Ok(gate.scrub((messages + it).joinToString("\n"))) }
                        step = "code"
                    }
                    fields.password != null -> {
                        val user = credential.fields.firstOrNull { it.type == FieldType.USERNAME } ?: credential.fields.firstOrNull { it.type == FieldType.EMAIL }
                        val password = credential.fields.firstOrNull { it.type == FieldType.PASSWORD }
                            ?: return ToolResult.Error("\"${credential.name}\" has no password field.")
                        if (fields.user != null && user != null) fill(fields.user, Placeholders.placeholder(credential.name, user.name), url)?.let { return ToolResult.Ok(gate.scrub((messages + it).joinToString("\n"))) }
                        fill(fields.password, Placeholders.placeholder(credential.name, password.name), url)?.let { return ToolResult.Ok(gate.scrub((messages + it).joinToString("\n"))) }
                        step = "login"
                    }
                    else -> return ToolResult.Ok(
                        gate.scrub((messages + "There is no sign-in form on this page (no password or code field). Use look, then act to open the sign-in page or pick where a code goes, and call sign_in again.").joinToString("\n")),
                    )
                }
                observe("arm", mapOf("token" to token))
                val submitted = submit(fields, url)
                if (submitted != null) return ToolResult.Ok(gate.scrub((messages + submitted).joinToString("\n")))
                val outcome = afterSignInStep(domain, step, before, tabsBefore, token, trail)
                messages += outcome.first
                if (!outcome.second) return ToolResult.Ok(gate.scrub(messages.joinToString("\n")))
            }
            return ToolResult.Ok(gate.scrub(messages.joinToString("\n")))
        }

        /** Types [value] into [field] through the gate (a sign-in field: the rules let ordinary typing pass). Null when typed, else what happened. */
        private suspend fun fill(field: SeenElement, value: String, url: String): String? {
            val target = page("target", mapOf("ref" to field.ref, "scroll" to true))
            val uid = target?.token?.let { snapshotUid(it) }
            val id = uid ?: field.ref
            noteSecretRef(url, field.ref)
            var ran = false
            val result = gate.perform(
                scope,
                GateStep("fill_by_uid", buildJsonObject { put("uid", id); put("value", value) }, BrowserKind.ACT, pages, targets = listOf(id to field.element())) { sent ->
                    ran = true
                    send(field, uid, sent)
                },
            )
            return when {
                !ran -> (result as? ToolResult.Ok)?.content ?: (result as ToolResult.Error).message
                result is ToolResult.Error -> "Typing into \"${field.name}\" failed: ${result.message}"
                else -> null
            }
        }

        /** Submits the sign-in form: its submit button through the gate, or Enter. Null when sent. */
        private suspend fun submit(fields: SignInFields, url: String): String? {
            val button = fields.submit
            var ran = false
            val result = if (button != null) {
                val target = page("target", mapOf("ref" to button.ref, "scroll" to true))
                val uid = target?.token?.let { snapshotUid(it) }
                val id = uid ?: button.ref
                gate.perform(scope, GateStep("click_by_uid", buildJsonObject { put("uid", id) }, BrowserKind.ACT, pages, targets = listOf(id to button.element())) { sent ->
                    ran = true
                    send(button, uid, sent)
                })
            } else {
                gate.perform(scope, GateStep("press_key", buildJsonObject { put("key", "Enter") }, BrowserKind.ACT, pages) { ran = true; mcp("press_key", it) })
            }
            return when {
                !ran -> (result as? ToolResult.Ok)?.content ?: (result as ToolResult.Error).message
                result is ToolResult.Error && !looksCutOff(result.message) -> "Submitting failed: ${result.message}"
                else -> null
            }
        }

        /** Where a sign-in step left the site, by Jev on the screenshot: the message, and whether to go straight on to the code. */
        private suspend fun afterSignInStep(domain: String, step: String, before: PageObservation, tabsBefore: List<Tab>, token: String, trail: Trail): Pair<String, Boolean> {
            delay(settle)
            val tabNotes = mutableListOf<String>()
            val tabsAfter = listTabs()
            val diff = diffTabs(tabsBefore, tabsAfter, openers.keys)
            diff.closed?.let { tabNotes += closedTab(it, it == selectedIndex(tabsBefore) || tabsAfter.none { t -> t.selected }, tabsAfter) }
            lastTabs = listTabs()
            val after = waitForPage(token) ?: before.copy(same = false)
            val frame = frame(after.url)
            val sign = signInState(domain, frame, trail)
            val current = state.signIn[domain] ?: SignInState()
            val codeArrived = after.otp && !before.otp
            val next = when (sign?.first) {
                "signed_in" -> current.copy(phase = SignInPhase.SIGNED_IN)
                "needs_code" -> if (codeArrived) current.copy(codesSent = current.codesSent + 1, lastSentAt = clock.now(), phase = SignInPhase.CODE_SENT) else current.copy(phase = SignInPhase.CODE_SENT)
                "choose_method" -> current.copy(phase = SignInPhase.CHOOSE_METHOD)
                "needs_login" -> current.copy(phase = SignInPhase.LOGIN)
                "error" -> current.copy(phase = SignInPhase.ERROR)
                else -> current
            }
            state.signIn[domain] = next
            val p = sign?.second?.let { " (Jev p=%.2f)".format(it) }.orEmpty()
            val message = (tabNotes + when (sign?.first) {
                null -> "Submitted the ${if (step == "code") "code" else "login"}. I could not check the screen (${frame.why ?: "Jev did not answer"}); call look to see where the sign-in stands."
                "signed_in" -> "Signed in to $domain$p."
                "needs_code" -> if (codeArrived || after.otp) "The site sent a one-time code$p." else "The site asks for a one-time code$p, but I see no code field. Call look."
                "choose_method" -> "The site asks where to send a sign-in code$p. Pick one with act (for example act(intent=\"the SMS option\"…)), continue with act, then call sign_in again for the code."
                "needs_login" -> if (step == "login") "The site still asks for the login$p: the email or password may be wrong. Report back; do not retry." else "The site asks for the login again$p."
                "error" -> "The site shows an error$p. Call read to see it and report back; do not retry."
                else -> "Submitted the ${if (step == "code") "code" else "login"}; the page shows something else$p. Call look."
            }).joinToString("\n")
            val action = if (step == "code") "sign_in: fill the code, submit" else "sign_in: fill the login, submit"
            record("sign_in", before, step, sign?.first, null, null, frame, "passed", action, trail, message)
            // Straight on to the code only when it is the login that led to a code page, and only once.
            return message to (step == "login" && (sign?.first == "needs_code") && after.otp)
        }

        private suspend fun signInState(domain: String, frame: Frame, trail: Trail): Pair<String, Double>? {
            val png = frame.png ?: return null
            val jev = models.jev ?: return null
            val result = trail.ask("sign_in", models.jevModel, jev, signInAsk(domain, png)).getOrNull() ?: return null
            val choice = result.answers[Q_SIGN_IN] as? DecisionAnswer.Choice ?: return null
            return choice.choice to (choice.probabilities[choice.choice] ?: choice.confidence ?: 0.0)
        }

        // ---- audit ----------------------------------------------------------------------------

        private suspend fun record(
            tool: String,
            before: PageObservation?,
            intent: String?,
            expect: String?,
            beforeFrame: Frame?,
            hit: Ground.Hit?,
            afterFrame: Frame?,
            gateOutcome: String,
            action: String?,
            trail: Trail?,
            report: String?,
            element: SeenElement? = hit?.element,
        ) {
            val seq = ++state.seq
            audit.record(
                BrowserStepRecord(
                    runId = scope.runId, seq = seq, at = clock.now(), tool = tool,
                    tabUrl = before?.url?.let(gate::scrub), tabTitle = before?.title?.let(gate::scrub),
                    intent = intent?.let(gate::scrub), expect = expect?.let(gate::scrub),
                    beforePng = beforeFrame?.storable, beforeWithheld = beforeFrame?.withheld == true,
                    box = trail?.box?.let { b -> buildJsonObject { put("top", b.top); put("bottom", b.bottom); put("left", b.left); put("right", b.right); b.confidence?.let { put("confidence", it) } } },
                    hitRole = element?.role, hitName = element?.name?.let(gate::scrub),
                    jev = trail?.json(), gate = gateOutcome, action = action?.let(gate::scrub),
                    afterPng = afterFrame?.storable, afterWithheld = afterFrame?.withheld == true,
                    changeReport = report?.let(gate::scrub), costMicros = trail?.cost(),
                ),
            )
        }
    }

    private fun newToken(): String = "s" + kotlin.random.Random.nextLong().toULong().toString(36)

    companion object {
        /** The raw tools the pilot works through; without them the helper gets the gated raw tools. */
        val REQUIRED = setOf("list_pages", "evaluate_script", "screenshot_page", "take_snapshot", "click_by_uid", "fill_by_uid", "type_text", "press_key", "navigate_page", "select_page")
        private val FILLABLE = setOf("textbox", "searchbox", "combobox", "spinbutton", "listbox", "slider")
        private const val READ_BUDGET = 6_000
        private const val MAX_RUNS = 50

        const val TABS_HINT = "Tabs survive a browser restart, but then an empty tab is selected: if the page is not the one you expect, switch with tabs or go to it again. Never assume you are still where you were."

        /** Secret-filled fields belong to one page: its address without the fragment. */
        fun pageKey(url: String): String = url.substringBefore('#')
    }
}

/** A screenshot for Jev. [withheld]: a secret may be on it, so it is never stored (Jev gets it masked). */
class Frame(val png: ByteArray?, val why: String?, val withheld: Boolean, val width: Int = 0, val height: Int = 0) {
    /** What the audit trail may keep: nothing while a secret may be visible. */
    val storable: ByteArray? get() = if (withheld) null else png
}

/** Where grounding found the element an intent names, or why not. */
sealed interface Ground {
    data class Hit(val element: SeenElement, val box: PixelRect?, val how: String) : Ground

    data class NotFound(val why: String) : Ground

    data class Failed(val why: String) : Ground
}

/** The decision calls of one step, for the audit trail: answers, latency, cost. */
class Trail {
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var costMicros = 0L
    var box: DecisionAnswer.Box? = null
    var gateNote: String? = null

    suspend fun ask(label: String, model: String, client: DecisionClient, ask: DecisionAsk): arrow.core.Either<DecisionError, DecisionResult> {
        val started = TimeSource.Monotonic.markNow()
        val result = client.decide(ask.state, ask.questions)
        val ms = started.elapsedNow().inWholeMilliseconds
        result.fold(
            { e -> calls += label to buildJsonObject { put("model", model); put("ms", ms); put("error", e.message.take(200)) } },
            { r -> calls += label to answersJson(r, ms); r.usage.costMicros?.let { costMicros += it } },
        )
        return result
    }

    fun json(): JsonObject? = if (calls.isEmpty()) null else buildJsonObject { calls.forEach { (k, v) -> put(k, v) } }

    fun cost(): Long? = if (calls.isEmpty()) null else costMicros
}

@Serializable
private data class ReadReply(val text: String = "", val truncated: Boolean = false)

@Serializable
private data class FocusReply(val ok: Boolean = false)

@Serializable
private data class ScrollReply(val scrollY: Double = 0.0)

@Serializable
data class CssRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    /** In the pixels of a [width] x [height] screenshot of a viewport of [vw] CSS pixels (same scale both ways). */
    fun scaled(width: Int, height: Int, vw: Double?): PixelRect {
        val s = if (vw != null && vw > 0) width / vw else 1.0
        return PixelRect((x * s).toInt(), (y * s).toInt(), (w * s).toInt(), (h * s).toInt())
    }
}

@Serializable
private data class RectsReply(val rects: List<CssRect> = emptyList())
