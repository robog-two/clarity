package net.robog.clarity.backend.agent

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.BrowserPilotConfig
import net.robog.clarity.backend.governance.HeldWaiters
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolImage
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType

/**
 * A fake login site behind fake browser MCP tools, for the pilot: it answers the observation
 * script's ops as the real script would on these pages, keeps tabs (a popup sign-in), counts the
 * codes it sends, and serves PNG screenshots. Elements carry the DOM depth they sit at.
 */
class FakeSite(val popup: Boolean = false) {
    data class El(
        val ref: String, val role: String, val name: String, val depth: Int = 4,
        val type: String? = null, val ac: String? = null, val x: Double = 40.0, val y: Double = 100.0, val w: Double = 160.0, val h: Double = 40.0,
        val paint: HitFacts? = null,
        val action: (FakeSite.() -> Unit)? = null,
    )

    data class Page(val url: String, val title: String, val els: List<El>, val otp: Boolean = false, val password: Boolean = false, val signals: List<String> = emptyList())

    val tabs: MutableList<Page> = mutableListOf(shop())
    var selected = 0
    var codesSent = 0
    var method: String? = null
    var signedIn = false
    /** A layer over the method page that a click must never reach (its [El.paint] says what it is). */
    var overlay: El? = null
    var hijacked = false
    val values = LinkedHashMap<String, String>()
    val clicked = CopyOnWriteArrayList<String>()
    private val tokens = HashMap<String, String>()
    private var focused: String? = null
    private var armed: String? = null
    private var doc = 0
    private var armedDoc = -1
    private val added = mutableListOf<String>()
    val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    val page: Page get() = tabs[selected]

    fun shop(): Page = Page(
        "https://shop.example.com/", "Garden Shop",
        listOf(
            El("c1", "heading", "Garden Shop", y = 20.0),
            El("c2", "link", "Sign in", y = 100.0) { open(signin(false)) },
            El("c3", "button", "Sign in with Clarity ID", x = 240.0, y = 100.0, w = 220.0) { tabs += signin(true); /* window.open: a new tab, not selected */ },
            El("c4", "button", "Details", y = 200.0),
            El("c5", "button", "Send money", x = 240.0, y = 200.0),
            El("c6", "button", "Accept all", y = 300.0),
            El("c7", "button", "Reject all", x = 240.0, y = 300.0),
        ),
    )

    fun signin(inPopup: Boolean): Page = Page(
        "https://login.example.com/signin" + if (inPopup) "?popup=1" else "", "Clarity ID",
        listOf(
            El("c10", "heading", "Sign in", depth = 17, y = 40.0),
            El("c11", "textbox", "Email address", depth = 19, type = "email", ac = "username", y = 120.0, w = 400.0),
            El("c12", "textbox", "Password", depth = 19, type = "password", ac = "current-password", y = 200.0, w = 400.0),
            El("c13", "button", "Continue", depth = 17, y = 280.0) {
                if (values["c11"] == "dana.k@inbox.dev" && values["c12"] == "correct horse") open(method(inPopup))
            },
        ),
        password = true,
    )

    fun method(inPopup: Boolean): Page = Page(
        "https://login.example.com/method" + if (inPopup) "?popup=1" else "", "Clarity ID",
        listOfNotNull(overlay) + listOf(
            El("c20", "heading", "Select a method", depth = 13, y = 40.0),
            El("c21", "radio", "Email (d•••@example.com)", depth = 14, y = 120.0, w = 300.0) { method = "email" },
            El("c22", "radio", "SMS (••• •• 42)", depth = 14, y = 180.0, w = 300.0) { method = "sms" },
            El("c23", "button", "Continue", depth = 13, y = 260.0) { if (method != null) { codesSent++; open(code(inPopup)) } },
        ),
    )

    fun code(inPopup: Boolean): Page = Page(
        "https://login.example.com/code" + if (inPopup) "?popup=1" else "", "Clarity ID",
        listOf(
            El("c30", "heading", "Enter your code", depth = 13, y = 40.0),
            El("c31", "textbox", "6-digit code", depth = 15, ac = "one-time-code", y = 120.0, w = 200.0),
            El("c32", "button", "Verify", depth = 13, y = 200.0) {
                if (values["c31"] == CODE) {
                    signedIn = true
                    if (inPopup) { val opener = 0; tabs.removeAt(selected); selected = opener; open(account()) } else open(account())
                } else open(code(inPopup)) // wrong code: the code page again, nothing sent
            },
            El("c33", "button", "Resend", depth = 13, x = 240.0, y = 200.0) { codesSent++; open(code(inPopup)) },
        ),
        otp = true,
        signals = listOf("We sent a 6-digit code by SMS"),
    )

    fun account(): Page = Page("https://shop.example.com/account", "Garden Shop", listOf(El("c40", "heading", "Your account", y = 40.0), El("c41", "text", "Signed in as Dana", y = 100.0)))

    fun open(p: Page) {
        tabs[selected] = p
        doc++
        focused = null
    }

    fun find(intent: String): El? {
        val want = words(intent).toSet()
        return page.els.filter { it.role != "heading" && it.role != "text" }.maxByOrNull { e -> words(e.name).count { it in want } }?.takeIf { e -> words(e.name).any { it in want } }
    }

    fun signInKind(): String = when {
        signedIn && page.url.contains("account") -> "signed_in"
        page.otp -> "needs_code"
        page.url.contains("method") -> "choose_method"
        page.password -> "needs_login"
        else -> "other"
    }

    private fun item(e: El): JsonObject = buildJsonObject {
        put("ref", e.ref); put("role", e.role); put("name", e.name)
        put("states", buildJsonArray { if ((e.role == "radio") && method != null && e.name.lowercase().startsWith(method!!)) add(JsonPrimitive("checked")) })
        values[e.ref]?.let { put("value", if (e.type == "password") "•••" else it) }
        e.type?.let { put("type", it) }; e.ac?.let { put("ac", it) }
        put("x", e.x); put("y", e.y); put("w", e.w); put("h", e.h); put("inView", true)
        e.paint?.let { put("paint", observationJson.encodeToJsonElement(HitFacts.serializer(), it)) }
    }

    private fun pageJson(extra: JsonObject.() -> Map<String, JsonElement> = { emptyMap() }): Map<String, JsonElement> = mapOf(
        "url" to JsonPrimitive(page.url), "title" to JsonPrimitive(page.title), "vw" to JsonPrimitive(VW), "vh" to JsonPrimitive(VH),
        "otp" to JsonPrimitive(page.otp), "password" to JsonPrimitive(page.password),
        "signals" to JsonArray(page.signals.map(::JsonPrimitive)),
    )

    /** The observation script's reply for [op]. */
    fun observe(op: String, args: JsonObject): JsonElement {
        val base = pageJson()
        fun ref(key: String = "ref") = (args[key] as? JsonPrimitive)?.contentOrNull
        val out: Map<String, JsonElement> = when (op) {
            "look" -> base + mapOf("items" to JsonArray(page.els.map(::item)), "total" to JsonPrimitive(page.els.size))
            "point" -> {
                val x = (args["x"] as JsonPrimitive).doubleOrNull!!
                val y = (args["y"] as JsonPrimitive).doubleOrNull!!
                val hit = page.els.firstOrNull { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
                base + mapOf("hit" to (hit?.let(::item) ?: kotlinx.serialization.json.JsonNull))
            }
            "target" -> {
                val e = page.els.firstOrNull { it.ref == ref() }
                val token = "k" + tokens.size
                if (e != null) tokens[token] = e.ref
                base + mapOf("hit" to (e?.let(::item) ?: kotlinx.serialization.json.JsonNull), "token" to (if (e != null && e.depth < 99) JsonPrimitive(token) else kotlinx.serialization.json.JsonNull))
            }
            "focus" -> { focused = ref(); mapOf("ok" to JsonPrimitive(page.els.any { it.ref == focused })) }
            "arm" -> { armed = (args["token"] as JsonPrimitive).content; armedDoc = doc; added.clear(); mapOf("armed" to JsonPrimitive(true)) }
            "changes" -> {
                val same = armed == (args["token"] as? JsonPrimitive)?.content && armedDoc == doc
                base + mapOf("same" to JsonPrimitive(same), "added" to JsonArray(if (same) added.map(::JsonPrimitive) else emptyList()))
            }
            "signin" -> base.filterKeys { it != "otp" && it != "password" } + buildMap {
                page.els.firstOrNull { it.ac == "username" }?.let { put("user", item(it)) }
                page.els.firstOrNull { it.type == "password" }?.let { put("password", item(it)) }
                page.els.firstOrNull { it.ac == "one-time-code" }?.let { put("otp", item(it)) }
                page.els.firstOrNull { it.role == "button" && it.name in setOf("Continue", "Verify", "Sign in") }?.let { put("submit", item(it)) }
            }
            "rects" -> mapOf("rects" to JsonArray(((args["refs"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { r -> page.els.firstOrNull { it.ref == (r as JsonPrimitive).content } }.map { buildJsonObject { put("x", it.x); put("y", it.y); put("w", it.w); put("h", it.h) } }))
            "read" -> mapOf("text" to JsonPrimitive(page.els.joinToString(" ") { it.name }))
            "scroll" -> mapOf("scrollY" to JsonPrimitive(600))
            else -> mapOf("error" to JsonPrimitive("unknown op"))
        }
        return JsonObject(out)
    }

    private fun click(ref: String) {
        val e = page.els.first { it.ref == ref }
        clicked += e.name
        e.action?.invoke(this)
    }

    private fun tool(name: String, body: (JsonObject) -> ToolResult): Tool = object : Tool {
        override val name = "browser__$name"
        override val description = "fake $name"
        override val inputSchema = buildJsonObject { put("type", "object") }
        override val effect = ToolEffect.EXTERNAL
        override suspend fun invoke(input: JsonObject): ToolResult {
            calls += name to input
            return body(input)
        }
    }

    private val call = Regex("""\)\(("[a-z]+"), (\{.*\})\)$""", RegexOption.DOT_MATCHES_ALL)

    val tools: List<Tool> = listOf(
        tool("list_pages") { ToolResult.Ok("${tabs.size} pages (selected: $selected)\n" + tabs.withIndex().joinToString("\n") { (i, p) -> "${if (i == selected) ">" else " "}[$i] ${p.title} (${p.url})" }) },
        tool("select_page") { selected = (it["pageIdx"] as JsonPrimitive).content.toInt(); ToolResult.Ok("selected [$selected]") },
        tool("close_page") { val i = (it["pageIdx"] as JsonPrimitive).content.toInt(); tabs.removeAt(i); if (selected >= tabs.size) selected = tabs.size - 1; ToolResult.Ok("closed [$i]") },
        tool("navigate_page") { val u = (it["url"] as JsonPrimitive).content; open(if ("login" in u) signin(false) else shop().copy(url = u)); ToolResult.Ok("[$selected] → $u") },
        tool("navigate_history") { open(shop()); ToolResult.Ok("back") },
        tool("new_page") { tabs += Page((it["url"] as JsonPrimitive).content, "New Tab", emptyList()); selected = tabs.lastIndex; ToolResult.Ok("new page [${tabs.lastIndex}]") },
        tool("take_snapshot") { input ->
            val sel = (input["selector"] as? JsonPrimitive)?.content.orEmpty()
            val token = Regex("""data-clarity-ref="([^"]+)"""").find(sel)?.groupValues?.get(1)
            val ref = token?.let { tokens[it] }
            val e = page.els.firstOrNull { it.ref == ref }
            ToolResult.Ok(if (e != null) "Snapshot [selector: $sel]\n\nuid=u_${e.ref} ${e.role} \"${e.name}\"" else "uid=e0 body")
        },
        tool("click_by_uid") { click((it["uid"] as JsonPrimitive).content.removePrefix("u_")); ToolResult.Ok("click ${it["uid"]}") },
        tool("fill_by_uid") { values[(it["uid"] as JsonPrimitive).content.removePrefix("u_")] = (it["value"] as JsonPrimitive).content; ToolResult.Ok("Filled ${it["uid"]}") },
        tool("type_text") { focused?.let { f -> values[f] = (it["text"] as JsonPrimitive).content }; ToolResult.Ok("typed") },
        tool("press_key") { k -> if ((k["key"] as JsonPrimitive).content == "Enter") focused?.let { click(it) }; ToolResult.Ok("Pressed") },
        tool("evaluate_script") { input ->
            val fn = (input["function"] as JsonPrimitive).content
            val m = call.find(fn) ?: return@tool ToolResult.Error("not an observation call")
            val op = kotlinx.serialization.json.Json.parseToJsonElement(m.groupValues[1]).let { (it as JsonPrimitive).content }
            val args = kotlinx.serialization.json.Json.parseToJsonElement(m.groupValues[2]).jsonObject
            ToolResult.Ok("Script ran on page and returned:\n```json\n${observe(op, args)}\n```")
        },
        tool("screenshot_page") { ToolResult.Ok("[ImageContent omitted]", listOf(ToolImage("image/png", Base64.getEncoder().encodeToString(PNG)))) },
        tool("list_downloads") { ToolResult.Ok("no downloads") },
    )

    companion object {
        const val CODE = "246810"
        const val VW = 800.0
        const val VH = 600.0

        /** A plain 800x600 PNG. */
        val PNG: ByteArray by lazy {
            val img = BufferedImage(VW.toInt(), VH.toInt(), BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply { color = Color.WHITE; fillRect(0, 0, img.width, img.height); dispose() }
            ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
    }
}

/**
 * A fake Jev (and Decision-1) that answers from the fake site: what is visible and where, what a
 * step does ([step], by its description), whether it would resend a code ([resend]), and where the
 * sign-in stands. [fail] makes every call an error.
 */
class FakeJev(private val site: FakeSite) : DecisionClient {
    val asked = CopyOnWriteArrayList<Map<String, DecisionQuestion>>()
    var fail: DecisionError? = null
    /** Fails only the step check (grounding still answers). */
    var failApproval: DecisionError? = null
    var step: (String) -> Pair<String, Double> = { "browse" to 0.95 }
    var resend: (String) -> Double = { d -> if ("Resend" in d || "Continue" in d) 0.9 else 0.05 }
    var visible: (String) -> FakeSite.El? = { site.find(it) }
    var happened = 0.9

    override suspend fun decide(state: JsonElement, questions: Map<String, DecisionQuestion>): Either<DecisionError, DecisionResult> {
        asked += questions
        fail?.let { return it.left() }
        if (Q_STEP in questions) failApproval?.let { return it.left() }
        val text = state.toString()
        val description = Regex("The step: ([^\"]*)").find(text)?.groupValues?.get(1).orEmpty()
        val answers = questions.mapValues { (id, q) ->
            when (id) {
                Q_VISIBLE -> DecisionAnswer.Noul(if (visible(intentOf(q)) != null) 0.96 else 0.04)
                Q_TARGET -> {
                    val e = visible(intentOf(q)) ?: site.page.els.first()
                    DecisionAnswer.Box(e.y / FakeSite.VH, (e.y + e.h) / FakeSite.VH, e.x / FakeSite.VW, (e.x + e.w) / FakeSite.VW, 0.9)
                }
                Q_STEP -> step(description).let { (c, p) -> DecisionAnswer.Choice(c, mapOf(c to p), p) }
                Q_RESEND -> DecisionAnswer.Noul(resend(description))
                Q_HAPPENED -> DecisionAnswer.Noul(happened)
                Q_SIGN_IN -> site.signInKind().let { DecisionAnswer.Choice(it, mapOf(it to 0.93), 0.93) }
                Q_PICK -> (q as DecisionQuestion.Choice).criteria.keys.first().let { DecisionAnswer.Choice(it, mapOf(it to 0.9), 0.9) }
                else -> DecisionAnswer.Refusal
            }
        }
        return DecisionResult(answers, "fake", "fake", DecisionUsage(1000, 0, 42)).right()
    }

    private fun intentOf(q: DecisionQuestion): String = when (q) {
        is DecisionQuestion.Noul -> q.instructions.removePrefix("Is this visible on the page in the screenshot: ").removeSuffix("?")
        is DecisionQuestion.Box -> q.instructions.removeSuffix(", in the screenshot of the browser tab")
        else -> ""
    }
}

class FakeBrowserModels(override val jev: DecisionClient?, override val text: DecisionClient? = jev) : BrowserModels {
    override val jevModel = "typesafe/jev-1.14-20261014"
    override val textModel = "microsoft/microsoft-decision-1"
}

/** A gate and a pilot over a [FakeSite], with fake governance, triage and decision models. */
class PilotRig(
    popup: Boolean = false,
    settings: BrowserPilotConfig = BrowserPilotConfig(),
    lead: Boolean = false,
    codeWait: Duration = 300.milliseconds,
    /** Who holds the browser; share one between rigs to model two runs on one browser. */
    val leases: MemoryBrowserLeases = MemoryBrowserLeases(),
) {
    val clock = StepClock()
    val site = FakeSite(popup)
    val login = CredentialMeta(
        CredentialId(Uuid.random().toString()), "clarity_login", CredentialKind.LOGIN, "login.example.com", "sign in",
        listOf(FieldSpec("email", "Email", FieldType.EMAIL), FieldSpec("password", "Password", FieldType.PASSWORD)), emptyMap(), Instant.parse("2026-10-25T00:00:00Z"),
    )
    val vault = MemoryVault(listOf(login))
    val grants = GrantStore(clock)
    val governance = FakeGovernance()
    var triage = TriageVerdict(true, "ordinary")
    val triaged = CopyOnWriteArrayList<TriageStep>()
    val waiters = HeldWaiters(clock)
    val gate = BrowserGate(vault, grants, governance, { s -> triaged += s; triage }, clock, waiters, codeWait, personWait = Duration.ZERO)
    val jev = FakeJev(site)
    val audit = MemoryBrowserAudit()
    val pilot = BrowserPilot(
        gate, FakeBrowserModels(jev), audit, vault, governance, AgentConfig(reviewInterval = 2.hours, browser = settings), clock, { TimeZone.UTC }, leases, settle = 1.milliseconds, leasePoll = 5.milliseconds,
    )
    val scope = RunScope("run-p", isLead = lead, purpose = "Check my orders at the garden shop")
    val tools: Map<String, Tool> = pilot.wrap(scope, site.tools).associateBy { it.name }

    init {
        // The first browser call takes the lease and resets the browser (one empty tab); tests start from the shop.
        if (!lead) kotlinx.coroutines.runBlocking { tools.getValue("browser__tabs").invoke(JsonObject(emptyMap())) }
        site.tabs.clear()
        site.tabs += site.shop()
        site.selected = 0
        site.calls.clear()
    }

    /** Another run on the same pilot and browser (its own scope). */
    fun run(runId: String, purpose: String = "Check my orders at the garden shop", lead: Boolean = false): Map<String, Tool> =
        pilot.wrap(RunScope(runId, isLead = lead, purpose = purpose), site.tools).associateBy { it.name }

    suspend fun call(name: String, input: JsonObject = JsonObject(emptyMap())): ToolResult = tools.getValue("browser__$name").invoke(input)
    suspend fun act(intent: String, expect: String = "something happens", value: String? = null): String =
        call("act", buildJsonObject { put("intent", intent); put("expect", expect); value?.let { put("value", it) } }).text()

    fun grantLogin() = grants.put("clarity_login", "example.com", mapOf("email" to "dana.k@inbox.dev", "password" to "correct horse"), kotlin.time.Duration.parse("5m"))
    fun clicks(): List<String> = site.calls.filter { it.first == "click_by_uid" || it.first == "press_key" }.map { it.second.toString() }
}

fun ToolResult.text(): String = when (this) {
    is ToolResult.Ok -> content
    is ToolResult.Error -> "ERROR: $message"
}
