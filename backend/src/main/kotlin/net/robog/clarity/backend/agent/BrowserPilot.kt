package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import arrow.core.Either
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
    /** Who holds the shared browser: one run at a time. */
    private val leases: BrowserLeases = MemoryBrowserLeases(),
    /** How long a step is given to take effect before the change report is built. */
    private val settle: Duration = 700.milliseconds,
    /** How often a run waiting for the browser asks again. */
    private val leasePoll: Duration = 1.seconds,
    /** At most this long waiting for a page to settle before it is reported as still loading. */
    private val settleCap: Duration = 8.seconds,
    /** How often the page is asked whether it has settled. */
    private val settlePoll: Duration = 250.milliseconds,
    /** Each run's caps and sign-in state, kept across restarts. */
    private val runStates: BrowserRunStates = MemoryBrowserRunStates(),
) {
    private val settings: BrowserPilotConfig = config.browser

    /** Per run: actions taken, steps without visible change, sign-in state per site, audit sequence. */
    private class RunState {
        var actions = 0
        var noChange = 0
        var mustLook = false
        var seq = 0
        val signIn = ConcurrentHashMap<String, SignInState>()
        /** This run has held the lease before (losing it means a fresh start it is told about). */
        var hadLease = false
        var shownResume = false
        var lastUrl: String? = null
        var lastTitle: String? = null
        var taskKey: String? = null
        /** Controls that started this run's sign-in, by page ([flowKey]) and lower-case label: pressing one again restarts it. */
        val starters: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()
        /** The cookie names ([PageObservation.ckn]) of each site when first seen before signing in. */
        val cookies = ConcurrentHashMap<String, String>()
        /** Code placeholders already typed: a new code field needs a fresh code, never one of these. */
        val usedCodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun snapshot() = BrowserRunSnapshot(
            actions = actions, seq = seq, hadLease = hadLease, shownResume = shownResume, taskKey = taskKey,
            signIn = signIn.toMap(), starters = starters.map { SignInStarter(it.first, it.second) },
            cookies = cookies.toMap(), usedCodes = usedCodes.toList(),
        )

        companion object {
            fun from(saved: BrowserRunSnapshot) = RunState().apply {
                actions = saved.actions
                seq = saved.seq
                hadLease = saved.hadLease
                shownResume = saved.shownResume
                taskKey = saved.taskKey
                signIn.putAll(saved.signIn)
                saved.starters.forEach { starters += it.flow to it.label }
                cookies.putAll(saved.cookies)
                usedCodes += saved.usedCodes
            }
        }
    }

    private val runs = ConcurrentHashMap<String, RunState>()

    /** A run this process has not seen yet picks up its saved state (a restart, or a resumed run). */
    private suspend fun restore(runId: String) {
        if (runs.containsKey(runId)) return
        val saved = try {
            runStates.load(runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "could not load the browser state of run $runId; starting it fresh" }
            null
        }
        if (saved != null) runs.putIfAbsent(runId, RunState.from(saved))
    }

    private suspend fun persist(runId: String) {
        val state = runs[runId] ?: return
        try {
            runStates.save(runId, state.snapshot())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "could not save the browser state of run $runId" }
        }
    }

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
        @Description("The one element to act on, in words a person would use for what they see: \"the SMS option\", \"the Continue button\", \"the email field\". Or \"scroll down\" / \"scroll up\" (the area under the middle of the screen, or the page), \"scroll the available times down\" (a scroll area by its name in look), or \"press Escape\".")
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
                    "(codes sent; \"Signed in to …\" once done), how much of the page is below, its scroll areas (rows shown of all), then every " +
                    "visible control and heading with its ref, name, value and state, at any depth. It waits for the page to finish loading first. " +
                    "Call it first, after anything surprising, and whenever a step changed nothing. " + TABS_HINT,
                json = ClarityJson,
            ) { input -> guarded { look(input.scope) } },
            tool<ActInput>(
                name = "${BrowserGate.PREFIX}act",
                description = "Do one thing on the page, named in words (not a ref): click it, or type `value` into it. The element is found on a " +
                    "screenshot, the step is checked, then you get a report of what changed (address, tabs opened or closed, new text) and " +
                    "whether `expect` happened, once the page has finished loading (or a \"Still loading\" note). A new tab it opens is selected for you. " + BrowserGate.PLACEHOLDER_HINT + " " + BrowserGate.DOWNLOAD_HINT,
                effect = ToolEffect.EXTERNAL,
                json = ClarityJson,
            ) { input -> guarded { act(input.intent.trim(), input.expect.trim(), input.value) } },
            tool<ReadInput>(
                name = "${BrowserGate.PREFIX}read",
                description = "Read the visible text of the page (or of one part of it, by ref from look). It scrolls the page and every scroll area to " +
                    "the end, so rows that load on scroll are read too. Hidden text is left out.",
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
                    "the site for another code. It reports anything else (pick where a code goes with act, then call sign_in again). Once you " +
                    "are signed in it refuses to start over, unless the page shows a login or code field (a site asking to verify again gets a fresh code).",
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

        /** Every tool: never for the lead, only under the browser lease, never throwing. */
        private suspend fun guarded(block: suspend () -> ToolResult): ToolResult {
            // The lead never browses: fail closed even if it was handed these tools.
            if (scope.isLead) return ToolResult.Error(BrowserGate.LEAD_REFUSED)
            restore(scope.runId)
            if (state.taskKey == null) state.taskKey = taskKey(scope.purpose)
            val note = try {
                when (val lease = takeLease()) {
                    is Lease.Refused -> return lease.result
                    is Lease.Held -> lease.note
                }
            } finally {
                persist(scope.runId)
            }
            val result = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "browser pilot step failed" }
                ToolResult.Error("The browser step failed: ${gate.scrub(e.message ?: e::class.simpleName.orEmpty())}")
            } finally {
                persist(scope.runId)
            }
            leases.touch(scope.runId, clock.now())
            return if (note == null) result else when (result) {
                is ToolResult.Ok -> ToolResult.Ok(note + "\n\n" + result.content, result.images)
                is ToolResult.Error -> ToolResult.Error(note + "\n\n" + result.message)
            }
        }

        /**
         * The browser lease for this run: held already, or got now (the browser is reset for it), or
         * waited for up to `leaseWait` while another run uses the browser (that wait is an audit step).
         */
        private suspend fun takeLease(): Lease {
            val started = TimeSource.Monotonic.markNow()
            var waiting = false
            while (true) {
                when (val t = leases.tryAcquire(scope.runId, clock.now(), settings.leaseIdle, settings.leaseStale)) {
                    is LeaseTry.Granted -> {
                        if (!t.fresh) return Lease.Held(null)
                        val lapsed = state.hadLease
                        state.hadLease = true
                        freshStart()
                        if (waiting) record("lease_wait", null, null, null, null, null, null, "granted", null, null, "got the browser after ${started.elapsedNow().inWholeSeconds} s")
                        return Lease.Held(if (lapsed) LEASE_LAPSED else null)
                    }
                    is LeaseTry.Waiting -> {
                        if (!waiting) {
                            waiting = true
                            record("lease_wait", null, null, null, null, null, null, "waiting", null, null, LEASE_WAITING + if (t.ahead > 0) " (${t.ahead} ahead in the queue)" else "")
                        }
                        if (started.elapsedNow() >= settings.leaseWait) {
                            leases.leaveQueue(scope.runId)
                            record("lease_wait", null, null, null, null, null, null, "timed_out", null, null, "gave up waiting for the browser after ${settings.leaseWait}")
                            return Lease.Refused(
                                ToolResult.Ok(
                                    "The browser has been in use by another task for ${settings.leaseWait.inWholeMinutes} minutes. Stop and report back that " +
                                        "the browser was busy and what is left to do; do not try again now.",
                                ),
                            )
                        }
                        delay(leasePoll)
                    }
                }
            }
        }

        /** Before every try of a step (also after waiting for the person): the run must still hold the browser. */
        private val leaseGuard: suspend () -> ToolResult? = {
            if (leases.holder() == scope.runId) null
            else ToolResult.Ok("$LEASE_LAPSED This step was not taken. Call look (it gets the browser again) and start over from the page you need.")
        }

        /** A new lease: every tab closed, one empty tab, and this run's browser state reset. Cookies and logins stay. */
        private suspend fun freshStart() {
            openers = emptyMap()
            secretRefs = "" to emptySet()
            state.signIn.clear()
            state.starters.clear()
            state.cookies.clear()
            state.noChange = 0
            state.mustLook = false
            gate.withBrowser {
                if (raw["new_page"] != null) mcp("new_page", buildJsonObject { put("url", "about:blank") })
                else mcp("navigate_page", buildJsonObject { put("url", "about:blank") })
                val tabs = listTabs(lock = false)
                val keep = if (raw["new_page"] != null) tabs.lastOrNull()?.index else tabs.firstOrNull { it.selected }?.index
                tabs.sortedByDescending { it.index }.filter { it.index != keep }.forEach { mcp("close_page", buildJsonObject { put("pageIdx", it.index) }) }
                selectTab(0, lock = false)
            }
            gate.state.navigated("about:blank")
            lastTabs = listTabs()
        }

        /** Once per run, in the first look: where an earlier run of the same task left the browser. Never reopened. */
        private suspend fun resumeHint(): String? {
            if (state.shownResume) return null
            state.shownResume = true
            val key = state.taskKey ?: return null
            val last = leases.lastResume(key, scope.runId) ?: return null
            if (last.url == null || isBlankPage(last.url)) return null
            return "Last time this task was on ${last.url}" + (last.title?.let { " (\"${it.take(60)}\")" } ?: "") +
                (last.where?.let { ", $it" } ?: "") + ". It is not reopened: go there if it helps."
        }

        // ---- browser access -------------------------------------------------------------------

        private suspend fun mcp(name: String, input: JsonObject = JsonObject(emptyMap())): ToolResult =
            raw[name]?.invoke(input) ?: ToolResult.Error("the browser has no $name")

        private suspend fun locked(name: String, input: JsonObject = JsonObject(emptyMap())): ToolResult = gate.withBrowser { mcp(name, input) }

        private fun refsNow(url: String): Set<String> = secretRefs.takeIf { it.first == pageKey(url) }?.second ?: emptySet()

        /** Runs the observation script [op]; [lock] false when the caller already holds the browser. The reply is not scrubbed. */
        private suspend fun observe(op: String, extra: Map<String, Any?> = emptyMap(), lock: Boolean = true, timeoutMillis: Int = 8000): JsonElement? {
            val refs = secretRefs.second
            val input = buildJsonObject {
                put("function", ObserveScript.call(op, observeArgs(refs, extra)))
                put("sandbox", ObserveScript.SANDBOX)
                put("timeout", timeoutMillis)
            }
            val result = if (lock) locked("evaluate_script", input) else mcp("evaluate_script", input)
            // Parsed as the page gave it (scrubbing could cut a released value out of an address); whatever is shown or stored is scrubbed.
            return (result as? ToolResult.Ok)?.content?.let(::parseScriptResult)
        }

        private suspend fun page(op: String = "look", extra: Map<String, Any?> = emptyMap(), lock: Boolean = true): PageObservation? =
            decodeObservation<PageObservation>(observe(op, extra, lock))?.also {
                // Where the run is, for the resume note when it lets the browser go.
                if ((op == "look" || op == "changes") && it.url.isNotBlank()) { state.lastUrl = it.url; state.lastTitle = it.title }
                // The site's cookie names before signing in, to tell when a sign-in set new ones.
                val domain = Sites.registrableDomain(it.url)
                if (it.ckn != null && domain != null && state.signIn[domain]?.signedIn != true) state.cookies.putIfAbsent(domain, it.ckn)
            }

        /** How long the page took to settle, and why it had not when [settleCap] ran out. */
        private inner class Settled(val loading: String?, val waitedMillis: Long)

        /**
         * Waits until the page has settled ([settleWait]): ready, no request finishing and no DOM change
         * for 500 ms, no loading indicator on screen. Gives up after [cap] and says why.
         */
        private suspend fun settleDown(cap: Duration = settleCap): Settled {
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                val why = settleWait(decodeObservation<SettleProbe>(observe("settle", timeoutMillis = 2000)))
                val waited = started.elapsedNow()
                if (why == null) return Settled(null, waited.inWholeMilliseconds)
                if (waited >= minOf(cap, settleCap)) return Settled(why, waited.inWholeMilliseconds)
                delay(settlePoll)
            }
        }

        /** The sites whose sign-in this run is in the middle of. */
        private fun flowDomains(): Set<String> =
            state.signIn.filterValues { it.phase in setOf(SignInPhase.LOGIN, SignInPhase.CHOOSE_METHOD, SignInPhase.CODE_SENT) }.keys

        private fun signals(obs: PageObservation, jev: Pair<String, Double>?): SignInSignals {
            val domain = Sites.registrableDomain(obs.url)
            val seen = domain?.let { state.cookies[it] }
            return SignInSignals(
                loginForm = obs.password || obs.otp, authUrl = isAuthUrl(obs.url), account = obs.account != null,
                cookiesChanged = seen != null && obs.ckn != null && obs.ckn != seen, jev = jev, expired = obs.expired,
            )
        }

        /**
         * Updates the signed-in state from what [obs] shows (and Jev's [jev] answer, when asked). Marks
         * the page's site and every site whose sign-in was under way as signed in on firm signals only
         * ([signedInVerdict]); an expiry message signs it out. Returns a line for the helper when it changed.
         */
        private fun noteSignIn(obs: PageObservation, jev: Pair<String, Double>?, extra: Set<String> = emptySet()): String? {
            val domain = Sites.registrableDomain(obs.url) ?: return null
            val current = state.signIn[domain]
            val s = signals(obs, jev)
            when (signedInVerdict(s)) {
                true -> {
                    if (current?.signedIn == true) return null
                    val flow = flowDomains() + extra
                    if (flow.isEmpty() && jev == null) return null // signed in before this run: nothing to stop
                    val now = clock.now()
                    (flow + domain).forEach { d -> state.signIn[d] = (state.signIn[d] ?: SignInState()).signIn(now) }
                    val why = listOfNotNull(
                        obs.account?.let { "\"$it\" is on the page" }, "new session cookies".takeIf { s.cookiesChanged },
                        s.jev?.takeIf { it.first == "signed_in" }?.let { "Jev p=%.2f".format(it.second) },
                    )
                    return "Signed in to $domain (${why.joinToString(", ")}). Do not sign in again: go on with the task."
                }
                false -> if (current?.signedIn == true && obs.expired) {
                    state.signIn[domain] = current.copy(phase = SignInPhase.LOGIN, signedInAt = null)
                    return "The site says the session expired: you are no longer signed in to $domain."
                }
                null -> {}
            }
            return null
        }

        private suspend fun alreadySignedIn(domain: String, st: SignInState): String =
            "You are already signed in to $domain" + (st.signedInAt?.let { " (since ${clockTime(it, zone.zone())})" }.orEmpty())

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
            val notes = syncTabs().toMutableList()
            val settled = settleDown()
            val obs = page("look", mapOf("scope" to scopeRef)) ?: return ToolResult.Error("Could not read the page (it may still be loading, or a browser dialog is open). Try wait, then look.")
            state.noChange = 0
            state.mustLook = false
            updatePhase(obs)
            noteSignIn(obs, null)?.let { notes += it }
            val header = header(obs).copy(loading = settled.loading?.let { "after ${(settled.waitedMillis + 500) / 1000} s, $it" })
            val hint = resumeHint()?.let { "$it\n" }.orEmpty()
            return ToolResult.Ok(gate.scrub(hint + (notes.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" }) + formatLook(obs, header, scoped = scopeRef != null)))
        }

        /**
         * The visible text of the page (or of [scopeRef]), then of each scroll area in it read to its end:
         * the page and every area are scrolled step by step until nothing new loads (capped), the text
         * is collected at each step (a list that drops rows it scrolled past is still read whole) and
         * the overlap between steps is dropped ([mergeLines]). Scroll positions are put back afterwards.
         */
        suspend fun read(scopeRef: String?): ToolResult {
            syncTabs()
            val settled = settleDown()
            val obs = page("look", mapOf("scope" to scopeRef, "max" to 1)) ?: return ToolResult.Error("Could not read the page. Try wait, then look.")
            val areas = obs.areas.take(MAX_READ_AREAS)
            val skip = areas.map { it.ref }
            var pageLines = readLines(scopeRef, skip) ?: return ToolResult.Error("Could not read the page. Try wait, then look.")
            var pageSteps = 0
            if (scopeRef == null && (obs.below ?: 0.0) > 0) {
                val startY = obs.scrollY
                while (pageSteps < MAX_PAGE_STEPS) {
                    val moved = decodeObservation<ScrollArea>(observe("scroll", mapOf("dy" to 1, "page" to true)))?.moved ?: 0.0
                    pageSteps++
                    if (moved <= 0) break
                    settleDown(READ_SETTLE)
                    val merged = mergeLines(pageLines, readLines(null, skip).orEmpty())
                    if (merged.size == pageLines.size) break
                    pageLines = merged
                }
                observe("scroll", mapOf("page" to true, "top" to startY))
            }
            val sections = areas.map { area -> area to readArea(area) }
            val tab = lastTabs.firstOrNull { it.selected }
            val text = buildString {
                append("Page: ${tab?.title.orEmpty()} — ${tab?.url.orEmpty()}\n")
                settled.loading?.let { append("(still loading: $it – the text may be incomplete)\n") }
                append(pageLines.joinToString("\n").ifBlank { "(no visible text)" })
                if (pageSteps > 1) append("\n(scrolled the page $pageSteps times to load more)")
                sections.forEach { (area, lines) ->
                    append("\n\n--- scroll area \"${area.name}\": ${lines.size} rows, read to the end ---\n")
                    append(lines.joinToString("\n").ifBlank { "(no visible text)" })
                }
            }
            val cut = text.length > READ_BUDGET * 3
            return ToolResult.Ok(gate.scrub(text.take(READ_BUDGET * 3) + if (cut) "\n(cut off – read(scope=…) a part of the page)" else ""))
        }

        private suspend fun readLines(scopeRef: String?, skip: List<String> = emptyList()): List<String>? =
            decodeObservation<ReadReply>(observe("read", mapOf("scope" to scopeRef, "max" to READ_BUDGET * 3, "lines" to true, "skip" to skip)))?.lines

        /** One scroll area from its top to its end, step by step, collecting rows; its position is put back. */
        private suspend fun readArea(area: ScrollArea): List<String> {
            observe("scroll", mapOf("ref" to area.ref, "to" to "top"))
            settleDown(READ_SETTLE)
            var lines = readLines(area.ref).orEmpty()
            var still = 0
            for (step in 1..MAX_AREA_STEPS) {
                val moved = decodeObservation<ScrollArea>(observe("scroll", mapOf("ref" to area.ref, "dy" to 1)))?.moved ?: 0.0
                settleDown(READ_SETTLE)
                val merged = mergeLines(lines, readLines(area.ref).orEmpty())
                // At the end and nothing new loaded (a list that loads more grows once it is reached): done.
                still = if (merged.size == lines.size && moved <= 0) still + 1 else 0
                lines = merged
                if (still >= 1 || lines.sumOf { it.length } > READ_BUDGET * 2) break
            }
            observe("scroll", mapOf("ref" to area.ref, "top" to area.top))
            return lines
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

        /**
         * After going somewhere while signed in: a note when the page is one that starts the sign-in (a
         * sign-in address, or a page whose control started this run's sign-in), or when it shows a login form.
         */
        private suspend fun signedInNote(after: PageObservation): String? {
            val domain = Sites.registrableDomain(after.url) ?: return null
            val st = state.signIn[domain]?.takeIf { it.signedIn } ?: return null
            return when {
                after.password || after.otp ->
                    "${alreadySignedIn(domain, st)}, but this page shows a login or code field. Only if the site says the session expired, or asks to verify again, call sign_in; otherwise go to the page you need."
                isAuthUrl(after.url) || state.starters.any { it.first == flowKey(after.url) } ->
                    "${alreadySignedIn(domain, st)}; this page starts the sign-in. Do not sign in again: look for the page you need (the site's menu, a search, or its address)."
                else -> null
            }
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
            val result = gate.perform(scope, GateStep(remote, input, BrowserKind.NAVIGATE, pages, guard = leaseGuard) { mcp(remote, it) })
            if (result is ToolResult.Error) return result
            delay(settle)
            val settled = settleDown()
            val after = page("changes") ?: before
            lastTabs = listTabs()
            val signedIn = after?.let { noteSignIn(it, null) }
            val already = after?.let { signedInNote(it) }
            val report = buildString {
                settled.loading?.let { append(stillLoadingLine(it, settled.waitedMillis)).append('\n') }
                append(if (after?.url != before?.url) "Now on: ${after?.title.orEmpty()} — ${after?.url.orEmpty()}" else "Still on: ${after?.title.orEmpty()} — ${after?.url.orEmpty()}")
                if (after?.dialog != null) append("\nDialog open: \"${after.dialog}\"")
                signedIn?.let { append('\n').append(it) }
                already?.let { append('\n').append(it) }
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
            val notes = syncTabs().toMutableList()
            limit()?.let { return it }
            if (state.mustLook) return ToolResult.Ok("Two steps in a row changed nothing visible. Call look and rethink before acting again.")
            if (intent.isBlank()) return ToolResult.Error("`intent` must name what to act on.")
            scrollOf(intent)?.let { return scroll(it, intent) }
            var before = page("look") ?: return ToolResult.Error("Could not read the page (still loading, or a browser dialog is open). Try wait, then look.")
            val url = before.url
            val domain = Sites.registrableDomain(url)
            val trail = Trail()
            keyIntent(intent)?.let { key -> return pressKey(key, intent, expect, before, trail, notes) }
            val signedIn = domain?.let { state.signIn[it] }?.takeIf { it.signedIn }
            val loginForm = before.password || before.otp
            // Never restart a sign-in that is done: refused before any model call when the words name such a control.
            renameCandidate(before.items, intent)?.takeIf { restartsSignIn(signedIn != null, loginForm, url, it.name, state.starters) }?.let { c ->
                return refuseRestart(domain!!, signedIn!!, before, intent, expect, c.name)
            }
            value?.let { v ->
                Placeholders.tokens(v).mapNotNull(Placeholders::codeId).firstOrNull { it in state.usedCodes }?.let {
                    return ToolResult.Ok("Refused: that code was already typed for an earlier code field. A new code field needs a fresh code: call sign_in on this page (it asks the person for the new code with ask_code).")
                }
            }
            state.actions++

            // A target named in the text view but scrolled out of sight (in the page or in a scroll area) is brought into view first.
            renameCandidate(before.items, intent)?.takeIf { !it.inView }?.let { off ->
                if (decodeObservation<FocusReply>(observe("reveal", mapOf("ref" to off.ref)))?.ok == true) {
                    settleDown(READ_SETTLE)
                    page("look")?.let { before = it }
                    notes.add("Scrolled \"${off.name}\" into view.")
                }
            }

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
            if (value == null && restartsSignIn(signedIn != null, loginForm, url, element.name, state.starters)) {
                return refuseRestart(domain!!, signedIn!!, before, intent, expect, element.name, frame, hit, trail)
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
            value?.let { v -> state.usedCodes += Placeholders.tokens(v).mapNotNull(Placeholders::codeId) }
            return result
        }

        /** Refuses a step that would start a finished sign-in again, and says what to do instead. */
        private suspend fun refuseRestart(
            domain: String, st: SignInState, before: PageObservation, intent: String, expect: String, label: String,
            frame: Frame? = null, hit: Ground.Hit? = null, trail: Trail? = null,
        ): ToolResult {
            val message = "Refused: ${alreadySignedIn(domain, st)}, and this page shows no login form; \"$label\" would start the sign-in again. " +
                "Do not sign in again: look for the page you need (the site's menu, a search, or its address). Only if the site says the session expired, or shows a login or code field, sign in again."
            record("act", before, intent, expect, frame, hit, null, "signed_in_guard", null, trail, message)
            return ToolResult.Ok(gate.scrub(message))
        }

        /**
         * A scroll: of the area [req] names (by its name in the text view, else found on the screen by Jev),
         * or of the area under the viewport centre, or the page. Waits for rows that load on scroll and says
         * what is new.
         */
        private suspend fun scroll(req: ScrollRequest, intent: String): ToolResult {
            val token = newToken()
            val args = mutableMapOf<String, Any?>("dy" to req.dy)
            val notes = mutableListOf<String>()
            val before = page("look")
            var trail: Trail? = null
            if (req.target != null && before != null) {
                val picked = pickArea(before.areas, req.target)
                when {
                    picked != null -> args["ref"] = picked.ref
                    before.areas.isEmpty() -> notes += "There is no scroll area on this page, so I scrolled the page."
                    else -> {
                        // Not named in the text view: find it on the screen like any target, then scroll the area under that point.
                        val t = Trail().also { trail = it }
                        val frame = frame(before.url)
                        when (val g = ground(req.target, before, frame, t)) {
                            is Ground.Hit -> { args["x"] = g.element.centerX; args["y"] = g.element.centerY }
                            is Ground.NotFound, is Ground.Failed -> {
                                val why = (g as? Ground.NotFound)?.why ?: (g as Ground.Failed).why
                                record("scroll", before, intent, null, frame, null, null, "not_found", null, t, why)
                                return ToolResult.Ok(gate.scrub("Not found: $why. Scroll areas on this page: " + before.areas.joinToString("; ") { it.line() }))
                            }
                        }
                    }
                }
            }
            observe("arm", mapOf("token" to token))
            val r = decodeObservation<ScrollArea>(observe("scroll", args)) ?: return ToolResult.Error("Could not scroll.")
            r.error?.let { return ToolResult.Error("Could not scroll: $it") }
            settleDown(READ_SETTLE)
            val way = if (req.dy > 0) "down" else "up"
            val after = page("changes", mapOf("token" to token))
            val text = buildString {
                notes.forEach { appendLine(it) }
                val moved = r.moved ?: 0.0
                if (r.ref.isNotEmpty()) {
                    // The area again, now that more rows may have loaded.
                    val now = decodeObservation<ScrollArea>(observe("scroll", mapOf("ref" to r.ref, "dy" to 0)))?.takeIf { it.error == null } ?: r
                    if (moved == 0.0) appendLine("Scroll area \"${now.name}\" did not move: it is at the ${if (req.dy > 0) "end" else "top"} (${now.showing()}).")
                    else appendLine("Scrolled scroll area \"${now.name}\" [${now.ref}] $way: ${now.showing()}.")
                } else {
                    if (moved == 0.0) appendLine("The page did not move: it is at the ${if (req.dy > 0) "end" else "top"}.")
                    else appendLine("Scrolled the page $way (now %,d px from the top; %,d px below).".format((r.scrollY ?: 0.0).toInt(), (r.below).toInt()))
                }
                after?.added?.takeIf { it.isNotEmpty() }?.let { appendLine("New on the page: " + it.take(6).joinToString(" | ") { s -> "\"${s.take(120)}\"" }) }
                append("Call look to see what is there, or read for all of it.")
            }
            record("scroll", before, intent, null, null, null, null, "passed", "scroll $args", trail, text)
            return ToolResult.Ok(gate.scrub(text))
        }

        /** A key press ("press Escape"): judged by the rules as press_key, reported like any step. */
        private suspend fun pressKey(key: String, intent: String, expect: String, before: PageObservation, trail: Trail, notes: List<String>): ToolResult {
            state.actions++
            val token = newToken()
            observe("arm", mapOf("token" to token))
            val tabsBefore = listTabs()
            val input = buildJsonObject { put("key", key) }
            var ran = false
            val result = gate.perform(scope, GateStep("press_key", input, BrowserKind.ACT, pages, guard = leaseGuard) { ran = true; mcp("press_key", it) })
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
            if (settings.textGrounding) exactMatch(before.items, intent)?.let { exact -> textGround(intent, before, exact, frame, trail)?.let { return it } }
            val png = frame.png ?: return Ground.Failed(frame.why ?: "no screenshot")
            val jev = models.jev ?: return Ground.Failed("no screen model is configured")
            val g = when (val r = askGrounding("ground", intent, png, jev, trail)) {
                is Either.Left -> return r.value
                is Either.Right -> r.value
            }
            if (g.pVisible >= settings.visibleThreshold) {
                val found = locate(g, intent, before, frame, trail, expected = null)
                // A box between two controls ("Accept all" | "Reject all") can land on the wrong one: when the page has a
                // control that fits the words better than what was hit, ask again for that one by name.
                if (found !is Ground.Hit || groundingAgrees(found.element, before.items, intent)) return found
                trail.box = null
            }
            // Second step: Jev may not share the helper's words ("link" for a link styled as a button), or its box sat
            // between two controls. Ask again by the name the page gives the likeliest element, and act only if Jev sees
            // it and its box picks that element.
            val candidate = renameCandidate(before.items, intent)
                ?: return Ground.NotFound(
                    tiedCandidates(before.items, intent).takeIf { it.size > 1 }?.let { tie ->
                        "several controls fit \"$intent\" (${tie.joinToString { "\"" + it.name + "\"" }}): name only the one you mean, by its own words, where it is or its colour, without naming the others"
                    } ?: "\"$intent\" is not visible on the screen (Jev p=%.2f)".format(g.pVisible),
                )
            val named = namedIntent(candidate)
            val second = when (val r = askGrounding("ground_named", named, png, jev, trail)) {
                is Either.Left -> return r.value
                is Either.Right -> r.value
            }
            if (second.pVisible < settings.visibleThreshold) {
                return Ground.NotFound("\"$intent\" is not visible on the screen (Jev p=%.2f; as $named p=%.2f)".format(g.pVisible, second.pVisible))
            }
            return locate(second, intent, before, frame, trail, expected = candidate)
        }

        private suspend fun askGrounding(label: String, intent: String, png: ByteArray, jev: DecisionClient, trail: Trail): Either<Ground, Grounding> {
            val answer = trail.ask(label, models.jevModel, jev, groundingAsk(intent, png))
            val result = answer.getOrNull() ?: return Either.Left(Ground.Failed("Jev: ${answer.leftOrNull()?.message?.take(120)}"))
            return groundingOf(result)?.let { Either.Right(it) } ?: Either.Left(Ground.Failed("Jev did not answer"))
        }

        /**
         * The element under the box centre, unless a click there would land on something else (an invisible
         * layer, a page-wide click-catcher, a covered control). With [expected] (the second step) it must be that
         * element, or Jev must be sure of it by name and its box must cover more of it than of the control at the
         * box centre; the page's name alone never decides.
         */
        private suspend fun locate(g: Grounding, intent: String, before: PageObservation, frame: Frame, trail: Trail, expected: SeenElement?): Ground {
            val box = g.box ?: return Ground.Failed("Jev gave no box")
            trail.box = box // kept for the audit even when nothing is clicked
            val vw = before.vw.takeIf { it > 0 } ?: frame.width.toDouble()
            val vh = before.vh.takeIf { it > 0 } ?: frame.height.toDouble()
            val x = (box.left + box.right) / 2 * vw
            val y = (box.top + box.bottom) / 2 * vh
            val hit = page("point", mapOf("x" to x, "y" to y))?.hit ?: return Ground.NotFound("nothing to act on where \"$intent\" is on the screen")
            hitProblem(hit, box, vw, vh)?.let { why ->
                return Ground.NotFound("I did not click where Jev saw \"$intent\": $why")
            }
            val s = frame.width / vw
            val jevBox = boxPixels(box, frame.width, frame.height)
            if (expected != null && !sameElement(hit, expected)) {
                if (!namedWins(box, g.pVisible, vw, vh, expected, hit)) {
                    return Ground.NotFound(
                        "\"$intent\" is not clearly on the screen: Jev's box for \"${expected.name}\" sits over ${hit.role} \"${hit.name}\" too. " +
                            "Name it with more context: where it is or its colour, without naming the other control" +
                            (before.dialog?.let { "; a dialog is open: \"$it\", deal with it first" } ?: ""),
                    )
                }
                // Jev is sure and its box covers more of the named control: check what a click on that one would hit.
                val named = page("point", mapOf("x" to expected.centerX, "y" to expected.centerY))?.hit?.takeIf { sameElement(it, expected) }
                    ?: return Ground.NotFound("\"${expected.name}\" is covered by something else on the screen")
                hitProblem(named, box, vw, vh)?.let { why -> return Ground.NotFound("I did not click \"${expected.name}\": $why") }
                trail.box = box
                return Ground.Hit(named, pixelRect(named, s), "screen box + name", jevBox)
            }
            trail.box = box
            return Ground.Hit(hit, pixelRect(hit, s), if (expected == null) "screen" else "screen, by name", jevBox)
        }

        /** The cheaper text path: Decision-1 confirms the exact match among up to 20 candidates; null falls back to the screenshot. */
        private suspend fun textGround(intent: String, before: PageObservation, exact: SeenElement, frame: Frame, trail: Trail): Ground? {
            val text = models.text ?: return null
            val options = candidates(before.items, intent).let { if (exact in it) it else (listOf(exact) + it).take(20) }
            val answer = trail.ask("pick", models.textModel, text, pickAsk(intent, options)).getOrNull() ?: return null
            val choice = answer.answers[Q_PICK] as? DecisionAnswer.Choice ?: return null
            val p = choice.probabilities[choice.choice] ?: choice.confidence ?: 0.0
            if (choice.choice != exact.ref || p < settings.visibleThreshold) return null
            val vw = before.vw.takeIf { it > 0 } ?: frame.width.toDouble()
            return Ground.Hit(exact, if (frame.width > 0) pixelRect(exact, frame.width / vw) else null, "text")
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
                GateStep(remote, input, BrowserKind.ACT, pages, targets = listOf(id to element.element()), jev = jev, guard = leaseGuard) { sent ->
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
            // Judge the page only once it has settled (a single-page app renders after its data arrives).
            val settled = settleDown()
            val after = waitForPage(token) ?: before.copy(same = false, added = emptyList())
            val targetAfter = element?.takeIf { after.same }?.let { page("target", mapOf("ref" to it.ref))?.hit }
            val domain = Sites.registrableDomain(after.url)
            val signIn = domain?.let { state.signIn[it] } ?: SignInState()
            val codeSent = codeSentBy(before, after, element?.name, signIn.triggers)
            if (codeSent && domain != null) {
                state.signIn[domain] = signIn.copy(
                    codesSent = signIn.codesSent + 1, lastSentAt = clock.now(), phase = if (signIn.signedIn) SignInPhase.SIGNED_IN else SignInPhase.CODE_SENT,
                    triggers = signIn.triggers + listOfNotNull(element?.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }),
                )
            }
            noteStarter(before, after, element)
            updatePhase(after)
            val afterFrame = frame(after.url)
            val (verified, verifyNote) = verify(expect, action, afterFrame, trail)
            // Mid-sign-in and off the sign-in pages: is the person signed in now? Jev's sign_in choice joins the page's own signs.
            val jevSign = if (flowDomains().isNotEmpty() && !after.password && !after.otp && !isAuthUrl(after.url) && domain != null) signInState(domain, afterFrame, trail) else null
            val signInNote = noteSignIn(after, jevSign)
            val change = StepChange(
                before, after, diff, tabNotes, element, targetAfter, verified, verifyNote, expect, codeSent,
                loading = settled.loading, waitedMillis = settled.waitedMillis, signInNote = signInNote,
            )
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

        /** A step that led from an ordinary page into a sign-in started it: remember that control on that page. */
        private fun noteStarter(before: PageObservation, after: PageObservation, element: SeenElement?) {
            val label = element?.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
            if (Sites.registrableDomain(before.url)?.let { state.signIn[it]?.signedIn } == true) return
            val wasAuth = before.password || before.otp || isAuthUrl(before.url)
            val isAuth = after.password || after.otp || isAuthUrl(after.url)
            if (!wasAuth && isAuth) state.starters += flowKey(before.url) to label
        }

        private fun updatePhase(obs: PageObservation) {
            val domain = Sites.registrableDomain(obs.url) ?: return
            val current = state.signIn[domain] ?: if (obs.password || obs.otp) SignInState() else return
            // Signed in sticks: a code field now is a second verification (sign_in handles it), not a new sign-in.
            if (current.signedIn) return
            val phase = when {
                obs.otp -> if (current.codesSent > 0) SignInPhase.CODE_SENT else current.phase
                obs.password -> SignInPhase.LOGIN
                else -> current.phase
            }
            state.signIn[domain] = current.copy(phase = phase)
        }

        private suspend fun header(obs: PageObservation): LookHeader {
            val domain = Sites.registrableDomain(obs.url).orEmpty()
            return LookHeader(lastTabs, openers, state.signIn[domain], zone.zone(), state.signIn.filterKeys { it != domain }.toSortedMap())
        }

        private suspend fun lookText(obs: PageObservation): String =
            formatLook(obs, header(obs), budget = LOOK_BUDGET / 2)

        // ---- sign_in --------------------------------------------------------------------------

        suspend fun signIn(credentialName: String): ToolResult {
            syncTabs()
            limit()?.let { return it }
            val credential = vault.byName(credentialName)
                ?: return ToolResult.Error("No saved credential \"$credentialName\". Saved: ${vault.list().joinToString { it.name }.ifEmpty { "none" }} (see list_credentials).")
            val messages = mutableListOf<String>()
            settleDown()
            repeat(2) {
                val fields = decodeObservation<SignInFields>(observe("signin")) ?: return ToolResult.Error("Could not read the page. Try wait, then look.")
                val url = fields.url
                val domain = Sites.registrableDomain(url) ?: return ToolResult.Error("Cannot tell which site this page is.")
                // Signed in already and no login or code field: never start over (that costs the person new codes).
                state.signIn[domain]?.takeIf { it.signedIn && fields.password == null && fields.otp == null }?.let { st ->
                    val message = "${alreadySignedIn(domain, st)}, and this page shows no login or code field, so I did not sign in again. " +
                        "Look for the page you need (the site's menu, a search, or its address). Call sign_in again only if the site says the session expired or shows a login or code field."
                    record("sign_in", page("look"), "refused", null, null, null, null, "signed_in_guard", null, null, message)
                    return ToolResult.Ok(gate.scrub((messages + message).joinToString("\n")))
                }
                state.actions++
                val before = page("look") ?: PageObservation(url = url)
                val beforeFrame = frame(url)
                val trail = Trail()
                val tabsBefore = listTabs()
                val token = newToken()
                val step: String
                when {
                    fields.otp != null -> {
                        val current = state.signIn[domain] ?: SignInState()
                        // Signed in, and the site asks for a code again (a second verification): a new code, asked for afresh.
                        val stepUp = current.signedIn
                        if (current.codeTries >= MAX_CODE_TRIES) return ToolResult.Ok("The site rejected the code ${current.codeTries} times. Stop and report back so the person can finish signing in; do not ask the site for a new code.")
                        when {
                            stepUp && current.stepUpOpen() -> state.signIn[domain] = current.copy(codesSent = current.codesSent + 1, lastSentAt = clock.now(), codeTries = 0)
                            !stepUp && current.codesSent == 0 -> state.signIn[domain] = current.copy(codesSent = 1, lastSentAt = clock.now(), phase = SignInPhase.CODE_SENT)
                        }
                        val purpose = when {
                            stepUp && current.codeTries == 0 -> "the new code $domain just sent to verify again after signing in (not the earlier sign-in code)"
                            current.codeTries == 0 -> "the sign-in code $domain just sent"
                            else -> "the code $domain sent, again: the last one typed was not accepted"
                        }
                        val ask = governance.askCode(url, purpose, scope.provenance())
                        val heldId = ask.heldId ?: return ToolResult.Error(ask.text)
                        val placeholder = "[secret:code_${heldId.value}]"
                        messages += if (stepUp) "You are signed in, and $domain asks to verify again: asked the person for the new code (a fresh one; an earlier code is never reused)."
                        else "Asked the person for the code $domain sent."
                        fill(fields.otp, placeholder, url)?.let { return ToolResult.Ok(gate.scrub((messages + it).joinToString("\n"))) }
                        state.usedCodes += heldId.value
                        step = if (stepUp) "verify again" else "code"
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
                val outcome = afterSignInStep(domain, step, before, beforeFrame, tabsBefore, token, trail)
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
                GateStep("fill_by_uid", buildJsonObject { put("uid", id); put("value", value) }, BrowserKind.ACT, pages, targets = listOf(id to field.element()), guard = leaseGuard) { sent ->
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
                gate.perform(scope, GateStep("click_by_uid", buildJsonObject { put("uid", id) }, BrowserKind.ACT, pages, targets = listOf(id to button.element()), guard = leaseGuard) { sent ->
                    ran = true
                    send(button, uid, sent)
                })
            } else {
                gate.perform(scope, GateStep("press_key", buildJsonObject { put("key", "Enter") }, BrowserKind.ACT, pages, guard = leaseGuard) { ran = true; mcp("press_key", it) })
            }
            return when {
                !ran -> (result as? ToolResult.Ok)?.content ?: (result as ToolResult.Error).message
                result is ToolResult.Error && !looksCutOff(result.message) -> "Submitting failed: ${result.message}"
                else -> null
            }
        }

        /** Where a sign-in step left the site, by Jev on the screenshot: the message, and whether to go straight on to the code. */
        private suspend fun afterSignInStep(domain: String, step: String, before: PageObservation, beforeFrame: Frame, tabsBefore: List<Tab>, token: String, trail: Trail): Pair<String, Boolean> {
            delay(settle)
            val tabNotes = mutableListOf<String>()
            val tabsAfter = listTabs()
            val diff = diffTabs(tabsBefore, tabsAfter, openers.keys)
            diff.closed?.let { tabNotes += closedTab(it, it == selectedIndex(tabsBefore) || tabsAfter.none { t -> t.selected }, tabsAfter) }
            lastTabs = listTabs()
            // Judge where the sign-in stands only once the page has settled: the page a sign-in lands on often renders late.
            val settled = settleDown()
            val after = waitForPage(token) ?: before.copy(same = false)
            val frame = frame(after.url)
            val sign = signInState(domain, frame, trail)
            val current = state.signIn[domain] ?: SignInState()
            val code = step != "login"
            val codeArrived = after.otp && !before.otp
            // The code field is still there after typing the code: the site did not take it.
            val rejected = code && after.otp && sign?.first != "signed_in"
            // Firm signs on the page (no login form, off the sign-in pages, a greeting or sign-out, new cookies) count with Jev's answer.
            val firm = !rejected && signedInVerdict(signals(after, sign)) == true
            val signedIn = !rejected && (firm || (sign?.first == "signed_in" && !after.password && !after.otp))
            val next = when {
                rejected -> current.copy(phase = if (current.signedIn) SignInPhase.SIGNED_IN else SignInPhase.CODE_SENT, codeTries = current.codeTries + 1)
                signedIn || (step == "verify again" && !after.otp && !after.password) -> current.signIn(clock.now())
                else -> when (sign?.first) {
                    "needs_code" -> if (codeArrived) current.copy(codesSent = current.codesSent + 1, lastSentAt = clock.now(), phase = SignInPhase.CODE_SENT) else current.copy(phase = SignInPhase.CODE_SENT)
                    "choose_method" -> current.copy(phase = SignInPhase.CHOOSE_METHOD)
                    "needs_login" -> current.copy(phase = SignInPhase.LOGIN)
                    "error" -> current.copy(phase = SignInPhase.ERROR)
                    else -> current
                }.let { if (current.signedIn) it.copy(phase = SignInPhase.SIGNED_IN) else it }
            }
            state.signIn[domain] = next
            if (signedIn) {
                // The sign-in usually ends on the site it was for (an identity provider sends the person back): signed in there too.
                val now = clock.now()
                (flowDomains() + setOfNotNull(Sites.registrableDomain(after.url))).forEach { d -> state.signIn[d] = (state.signIn[d] ?: SignInState()).signIn(now) }
            }
            val p = sign?.second?.let { " (Jev p=%.2f)".format(it) }.orEmpty()
            val tries = current.codeTries + 1
            val landed = Sites.registrableDomain(after.url)?.takeIf { it != domain }?.let { " and $it" }.orEmpty()
            val message = (tabNotes + listOfNotNull(settled.loading?.let { stillLoadingLine(it, settled.waitedMillis) }) + if (rejected) {
                if (tries < MAX_CODE_TRIES) "The site did not accept the code$p (attempt $tries of $MAX_CODE_TRIES). Call sign_in again: it asks the person to type the code again. It does not ask the site for a new code; do not press Resend."
                else "The site rejected the code $tries times$p. Stop and report back so the person can finish signing in; do not ask the site for a new code."
            } else when {
                step == "verify again" && !after.otp && !after.password -> "The site took the new code: the verification is done and you are still signed in to $domain$p. Go on with the task."
                signedIn -> "Signed in to $domain$landed$p. Do not sign in again: go on with the task."
                settled.loading != null -> "Submitted the ${if (code) "code" else "login"}; the page is still loading, so where the sign-in stands is not known yet$p. Call wait, then look. Do not sign in again."
                else -> when (sign?.first) {
                    null -> "Submitted the ${if (code) "code" else "login"}. I could not check the screen (${frame.why ?: "Jev did not answer"}); call look to see where the sign-in stands."
                    "needs_code" -> if (codeArrived || after.otp) "The site sent a one-time code$p." else "The site asks for a one-time code$p, but I see no code field. Call look."
                    "choose_method" -> "The site asks where to send a sign-in code$p. Pick one with act, say act(intent=\"the SMS option\"…), continue with act, then call sign_in again for the code."
                    "needs_login" -> if (step == "login") "The site still asks for the login$p: the email or password may be wrong. Report back; do not retry." else "The site asks for the login again$p."
                    "error" -> "The site shows an error$p. Call read to see it and report back; do not retry."
                    else -> "Submitted the ${if (code) "code" else "login"}; the page shows something else$p. Call look."
                }
            }).joinToString("\n")
            val action = if (code) "sign_in: fill the code, submit" else "sign_in: fill the login, submit"
            record("sign_in", before, step, sign?.first, beforeFrame, null, frame, "passed", action, trail, message)
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

        /** The stored before frame: what is clicked outlined in red, what Jev pointed at dashed blue. */
        private fun annotate(png: ByteArray, frame: Frame, hit: Ground.Hit?, trail: Trail?): ByteArray {
            val jevBox = hit?.jevBox ?: trail?.box?.takeIf { frame.width > 0 }?.let { boxPixels(it, frame.width, frame.height) }
            if (hit?.box == null && jevBox == null) return png
            return editPng(png, outline = hit?.box, secondary = jevBox) ?: png
        }

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
                    beforePng = beforeFrame?.storable?.let { png -> annotate(png, beforeFrame, hit, trail) }, beforeWithheld = beforeFrame?.withheld == true,
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

    /** What a run got when it asked for the browser. */
    private sealed interface Lease {
        /** It holds the browser; [note] tells the helper something first (a lapsed lease). */
        data class Held(val note: String?) : Lease

        data class Refused(val result: ToolResult) : Lease
    }

    /**
     * Run [runId] finished, failed or was cancelled: it gives the browser back (keeping where it was)
     * and leaves the queue. Its saved state stays, so a resumed run keeps its caps.
     */
    suspend fun runEnded(runId: String) {
        val state = runs[runId]
        if (leases.holder() == runId) saveResume(runId, state)
        leases.release(runId)
        runs.remove(runId)
    }

    /**
     * The lease keeper's tick: heartbeats a holder whose run is alive here, and releases a holder
     * that has been idle (or gone) too long, keeping where it was. That run starts fresh next time.
     */
    suspend fun maintainLease() {
        val now = clock.now()
        val holder = leases.holder() ?: return
        if (runs[holder]?.hadLease == true) leases.heartbeat(holder, now)
        val released = leases.releaseExpired(now, settings.leaseIdle, settings.leaseStale) ?: return
        log.info { "browser lease of $released released after ${settings.leaseIdle} without browser activity" }
        saveResume(released, runs[released])
    }

    private suspend fun saveResume(runId: String, state: RunState?) {
        state ?: return
        val url = state.lastUrl ?: return
        val domain = Sites.registrableDomain(url)
        val where = domain?.let { state.signIn[it] }?.let { whereLine(it.phase, domain) }
        leases.saveResume(BrowserResume(runId, state.taskKey, url, state.lastTitle, where, clock.now()))
    }

    companion object {
        const val LEASE_WAITING = "Waiting for the browser – another task is using it."
        const val LEASE_LAPSED =
            "The browser lease lapsed (no browser activity for a while, or waiting for the person) and another task may have used the browser. " +
                "You start fresh: one empty tab, and this task's sign-in steps and code count are reset (cookies and logins stay)."

        /** Pure: one line on where a run was in a site's sign-in, for the resume note. */
        fun whereLine(phase: SignInPhase, domain: String): String? = when (phase) {
            SignInPhase.CODE_SENT -> "on the code page of $domain (a code had been sent)"
            SignInPhase.CHOOSE_METHOD -> "choosing where $domain sends its sign-in code"
            SignInPhase.LOGIN -> "on the $domain sign-in form"
            SignInPhase.SIGNED_IN -> "signed in to $domain"
            SignInPhase.ERROR -> "looking at an error on $domain"
            SignInPhase.UNKNOWN -> null
        }

        /** The raw tools the pilot works through; without them the helper gets the gated raw tools. */
        val REQUIRED = setOf("list_pages", "evaluate_script", "screenshot_page", "take_snapshot", "click_by_uid", "fill_by_uid", "type_text", "press_key", "navigate_page", "select_page")
        private val FILLABLE = setOf("textbox", "searchbox", "combobox", "spinbutton", "listbox", "slider")
        private const val READ_BUDGET = 6_000
        private const val MAX_RUNS = 50
        /** `read` scrolls at most this many areas, each at most this many steps, and the page this many. */
        private const val MAX_READ_AREAS = 4
        private const val MAX_AREA_STEPS = 25
        private const val MAX_PAGE_STEPS = 8
        /** How long `read` and a scroll wait for more rows to load after a step. */
        private val READ_SETTLE = 3.seconds

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
    /** [box] is the element's own rect on the frame (what will be clicked), [jevBox] what Jev pointed at. */
    data class Hit(val element: SeenElement, val box: PixelRect?, val how: String, val jevBox: PixelRect? = null) : Ground

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
private data class ReadReply(val text: String = "", val truncated: Boolean = false, val lines: List<String>? = null)

@Serializable
private data class FocusReply(val ok: Boolean = false)

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
