package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.vault.CreateOutcome
import net.robog.clarity.backend.vault.GrantStore
import net.robog.clarity.backend.vault.Hold
import net.robog.clarity.backend.vault.VaultGovernance
import net.robog.clarity.backend.vault.VaultStore
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.backend.governance.HeldWaiters
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.VaultEnvelope

private fun fields(vararg names: Pair<String, FieldType>) = names.map { (n, t) -> FieldSpec(n, n.replaceFirstChar { it.uppercase() }, t) }

private fun cred(name: String, site: String, kind: CredentialKind, fields: List<FieldSpec>) =
    CredentialMeta(CredentialId(Uuid.random().toString()), name, kind, site, "purpose", fields, emptyMap(), Instant.parse("2026-10-25T00:00:00Z"))

class BrowserGateTest : FunSpec({
    val login = cred("bank_login", "bank.example.com", CredentialKind.LOGIN, fields("username" to FieldType.USERNAME, "password" to FieldType.PASSWORD))
    val card = cred("visa", "shop.example.com", CredentialKind.PAYMENT_CARD, fields("card_number" to FieldType.CARD_NUMBER, "card_cvc" to FieldType.CARD_CVC))

    class Rig(purpose: String? = null, lead: Boolean = true) {
        val clock = StepClock()
        val browser = FakeBrowser()
        val vault = MemoryVault(listOf(login, card))
        val grants = GrantStore(clock)
        val governance = FakeGovernance()
        var triage = TriageVerdict(true, "ordinary")
        val triaged = CopyOnWriteArrayList<TriageStep>()
        val waiters = HeldWaiters(clock)
        val gate = BrowserGate(vault, grants, governance, { step -> triaged += step; triage }, clock, waiters)
        val scope = RunScope("run-1", isLead = lead, purpose = purpose)
        val tools: Map<String, Tool> = gate.wrap(scope, browser.tools).associateBy { it.name }

        suspend fun call(name: String, input: JsonObject): ToolResult = tools.getValue("browser__$name").invoke(input)
        suspend fun snapshot() = call("take_snapshot", JsonObject(emptyMap()))
        fun grantLogin(domain: String = "example.com", ttl: Duration = 5.minutes) =
            grants.put("bank_login", domain, mapOf("username" to "dana@example.com", "password" to "hunter2!"), ttl)
    }

    test("raw-script tools are hidden; other servers' tools and read tools are untouched") {
        val rig = Rig()
        rig.tools.keys shouldNotContain "browser__evaluate_script"
        rig.tools.keys shouldNotContain "browser__send_bidi_command"
        rig.tools.keys shouldContain "browser__fill_by_uid"
        rig.tools.keys shouldContain "sandbox__run"
        rig.tools.getValue("sandbox__run").description shouldBe "not the browser"
        rig.tools.getValue("browser__fill_by_uid").description shouldContain "[secret:"
        rig.tools.getValue("browser__click_by_uid").description shouldContain "/workspace/downloads"
    }

    test("an empty tab where a page was (browser restarted): list_pages and an empty snapshot say so and name the page; back on it, no note") {
        val rig = Rig(lead = false)
        rig.tools.getValue("browser__list_pages").description shouldContain "select_page"
        rig.call("navigate_page", args("url" to "https://shop.example.com/booking/step-2"))
        (rig.call("list_pages", JsonObject(emptyMap())) as ToolResult.Ok).content shouldNotContain "Note:"

        // the browser container was recreated: an empty tab is selected
        rig.browser.url = "about:blank"
        rig.browser.snapshot = "uid=e0 body"
        val pages = (rig.call("list_pages", JsonObject(emptyMap())) as ToolResult.Ok).content
        pages shouldContain "the selected tab is empty, but the browser was last on https://shop.example.com/booking/step-2"
        (rig.snapshot() as ToolResult.Ok).content shouldContain "last on https://shop.example.com/booking/step-2"

        rig.call("navigate_page", args("url" to "https://shop.example.com/booking/step-2"))
        (rig.call("list_pages", JsonObject(emptyMap())) as ToolResult.Ok).content shouldNotContain "Note:"
        rig.browser.snapshot = "uid=e0 body"
        (rig.snapshot() as ToolResult.Ok).content shouldNotContain "Note:" // an empty page that is the page we are on
    }

    test("no grant: the placeholder is not substituted, the person is asked once per credential, and the browser is not called") {
        val rig = Rig()
        rig.snapshot()
        val result = rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        result.shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "Asked the person"
        rig.governance.credentialAsks shouldBe listOf("bank_login" to "example.com")
        rig.browser.calls("fill_by_uid").shouldBeEmpty()
    }

    test("approved release: the value reaches the browser, the agent-visible result holds only the placeholder") {
        val rig = Rig()
        rig.snapshot()
        rig.grantLogin()
        val result = rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        rig.browser.calls("fill_by_uid").single()["value"] shouldBe kotlinx.serialization.json.JsonPrimitive("hunter2!")
        val text = result.shouldBeInstanceOf<ToolResult.Ok>().content
        text shouldBe "filled 1_2 with [secret:bank_login_password]"
        rig.vault.touched.size shouldBe 1
    }

    test("every result is scrubbed, including page text, other spellings and errors") {
        val rig = Rig()
        rig.grantLogin()
        rig.grants.put("visa", "example.com", mapOf("card_number" to "4111111111111111"), 5.minutes)
        val text = (rig.call("get_page_text", JsonObject(emptyMap())) as ToolResult.Ok).content
        text shouldNotContain "hunter2"
        text shouldNotContain "4111"
        text shouldContain "[secret:bank_login_password]"
        text shouldContain "[secret:visa_card_number]"
        val error = rig.call("fill_by_uid", args("uid" to "boom", "value" to "x")).shouldBeInstanceOf<ToolResult.Error>().message
        error shouldBe "could not fill [secret:bank_login_password]"
    }

    test("values keep being scrubbed after the grant expires, but the next placeholder asks again") {
        val rig = Rig()
        rig.grantLogin()
        rig.clock.advance(6.minutes)
        (rig.call("get_page_text", JsonObject(emptyMap())) as ToolResult.Ok).content shouldNotContain "hunter2"
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]")).shouldBeInstanceOf<ToolResult.Ok>()
        rig.governance.credentialAsks.size shouldBe 1
        rig.browser.calls("fill_by_uid").shouldBeEmpty()
    }

    test("a grant covers its credential on its site only: another site asks again") {
        val rig = Rig()
        rig.grantLogin(domain = "example.com")
        rig.browser.url = "https://bank.evil.io/login"
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        rig.governance.credentialAsks shouldBe listOf("bank_login" to "evil.io")
        rig.browser.calls("fill_by_uid").shouldBeEmpty()
    }

    test("a subdomain of the granted registrable domain is the same site") {
        val rig = Rig()
        rig.grantLogin(domain = "example.com")
        rig.browser.url = "https://accounts.example.com/signin"
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        rig.browser.calls("fill_by_uid").size shouldBe 1
    }

    test("a grant for one credential does not cover another") {
        val rig = Rig()
        rig.grantLogin()
        rig.call("fill_by_uid", args("uid" to "1_5", "value" to "[secret:visa_card_number]"))
        rig.governance.credentialAsks shouldBe listOf("visa" to "example.com")
    }

    test("secrets are never typed into plain http pages, unknown placeholders are explained, and other tools refuse placeholders") {
        val rig = Rig()
        rig.grantLogin()
        rig.browser.url = "http://bank.example.com/login"
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "https"
        rig.browser.url = "https://bank.example.com/login"
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:nope_password]")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "bank_login"
        rig.call("click_by_uid", args("uid" to "[secret:bank_login_password]")).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "only work in"
        rig.call("navigate_page", args("url" to "https://x.com/?p=[secret:bank_login_password]")).shouldBeInstanceOf<ToolResult.Error>()
        rig.browser.calls("navigate_page").shouldBeEmpty()
        rig.browser.calls("fill_by_uid").shouldBeEmpty()
    }

    test("a form fill substitutes inside nested values") {
        val rig = Rig()
        rig.grantLogin()
        val input = buildJsonObject {
            put("elements", buildJsonArray {
                add(args("uid" to "1_1", "value" to "[secret:bank_login_username]"))
                add(args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
            })
        }
        val result = rig.call("fill_form_by_uid", input) as ToolResult.Ok
        result.content shouldNotContain "hunter2"
        result.content shouldNotContain "dana@example.com"
        rig.browser.calls("fill_form_by_uid").single().toString() shouldContain "hunter2!"
    }

    test("a code placeholder waits until the person has typed it, then works only on its site") {
        val rig = Rig()
        val waiting = rig.call("type_text", args("text" to "[secret:code_h-1]")) as ToolResult.Ok
        waiting.content shouldContain "not entered"
        rig.grants.put("code", "example.com", mapOf("h-1" to "424242"), 5.minutes)
        val ok = rig.call("type_text", args("text" to "[secret:code_h-1]")) as ToolResult.Ok
        ok.content shouldBe "typed [secret:code_h-1]"
        rig.browser.calls("type_text").single()["text"] shouldBe kotlinx.serialization.json.JsonPrimitive("424242")
        rig.browser.url = "https://other.org/"
        rig.call("type_text", args("text" to "[secret:code_h-1]")).shouldBeInstanceOf<ToolResult.Error>()
    }

    test("screenshots are refused right after a secret was typed, and allowed again after other actions or leaving the site") {
        val rig = Rig()
        rig.snapshot()
        (rig.call("screenshot_page", JsonObject(emptyMap()))).shouldBeInstanceOf<ToolResult.Ok>()
        rig.grantLogin()
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        rig.call("screenshot_page", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Error>().message shouldContain "Refused"
        repeat(8) { rig.call("fill_by_uid", args("uid" to "1_1", "value" to "hello")) }
        rig.call("screenshot_page", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Ok>()
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        rig.call("screenshot_page", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Error>()
        rig.call("navigate_page", args("url" to "https://elsewhere.org/"))
        rig.call("screenshot_page", JsonObject(emptyMap())).shouldBeInstanceOf<ToolResult.Ok>()
    }

    test("a risky click is held, not run; nothing reaches the browser; one approval lets the same call through") {
        val rig = Rig()
        rig.snapshot()
        val held = rig.call("click_by_uid", args("uid" to "1_4")) as ToolResult.Ok
        held.content shouldContain "Waiting"
        rig.browser.calls("click_by_uid").shouldBeEmpty()
        val proposed = rig.governance.steps.single()
        proposed.server shouldBe "browser"
        proposed.tool shouldBe "click_by_uid"
        proposed.summary shouldBe "Click button \"Send money\" on bank.example.com/login"
        rig.governance.stepAnswer = Hold.Proceed
        rig.call("click_by_uid", args("uid" to "1_4")) shouldBe ToolResult.Ok("clicked 1_4")
        rig.browser.calls("click_by_uid").size shouldBe 1
    }

    test("an ordinary click goes to the triage, whose verdict is cached per page and element") {
        val rig = Rig()
        rig.snapshot()
        rig.call("click_by_uid", args("uid" to "1_3")) shouldBe ToolResult.Ok("clicked 1_3")
        rig.call("click_by_uid", args("uid" to "1_3")) shouldBe ToolResult.Ok("clicked 1_3")
        rig.triaged.size shouldBe 1
        rig.triaged.single().description shouldBe "Click button \"Review details\" on bank.example.com/login"
        rig.clock.advance(6.minutes)
        rig.call("click_by_uid", args("uid" to "1_3"))
        rig.triaged.size shouldBe 2
    }

    test("the triage is told the run's task and what the element is, including where a link goes") {
        val rig = Rig(purpose = "Find open haircut slots at the nearest salon")
        rig.browser.snapshot = """
            uid=e1 RootWebArea "Hair salon"
              uid=e260 a "Book an appointment" href="https://beautyservices.ulta..." text="Book an appointment"
              uid=e336 button "More options" text="More options"
        """.trimIndent()
        rig.snapshot()
        rig.call("click_by_uid", args("uid" to "e336"))
        val step = rig.triaged.single()
        step.task shouldBe "Find open haircut slots at the nearest salon"
        step.target shouldBe "button \"More options\""
        // A link to another page is browsing: it passes the rules without a triage.
        rig.call("click_by_uid", args("uid" to "e260")) shouldBe ToolResult.Ok("clicked e260")
        rig.triaged.size shouldBe 1
    }

    test("plain buttons that only move along or acknowledge pass without asking anyone") {
        val rig = Rig()
        rig.browser.snapshot = """
            uid=e1 RootWebArea "Hair salon"
              uid=e2 button "I Understand"
              uid=e3 button "Continue"
              uid=e4 button "Sign in"
        """.trimIndent()
        rig.snapshot()
        listOf("e2", "e3", "e4").forEach { rig.call("click_by_uid", args("uid" to it)) shouldBe ToolResult.Ok("clicked $it") }
        rig.triaged.shouldBeEmpty()
        rig.governance.steps.shouldBeEmpty()
    }

    test("with a live login for the site, the sign-in page's own steps pass; without one they are checked") {
        val rig = Rig()
        rig.browser.url = "https://www.ulta.com/u/mfa-sms-challenge"
        rig.browser.snapshot = """
            uid=e1 RootWebArea "Select a method to verify"
              uid=e2 button "SMS"
        """.trimIndent()
        rig.snapshot()
        rig.call("click_by_uid", args("uid" to "e2"))
        rig.triaged.size shouldBe 1
        rig.grants.put("ulta_login", "ulta.com", mapOf("email" to "sam@example.com", "password" to "pw-123456"), 60.minutes)
        rig.clock.advance(6.minutes) // past the triage cache
        rig.call("click_by_uid", args("uid" to "e2")) shouldBe ToolResult.Ok("clicked e2")
        rig.triaged.size shouldBe 1
    }

    test("a helper's held step waits for the person, then runs by itself on the same page") {
        val rig = Rig(lead = false)
        rig.snapshot()
        rig.governance.decision = HeldAction.Status.APPROVED
        rig.governance.onDecision = { rig.governance.stepAnswer = Hold.Proceed }
        rig.call("click_by_uid", args("uid" to "1_4")) shouldBe ToolResult.Ok("clicked 1_4")
        rig.governance.awaited shouldBe listOf(HeldActionId("h1"))
        rig.browser.calls("click_by_uid").size shouldBe 1
        rig.waiters.helperFor(HeldActionId("h1")) shouldBe "run-1"
    }

    test("a helper's held login waits, then the same fill goes through with the released values") {
        val rig = Rig(lead = false)
        rig.snapshot()
        rig.governance.decision = HeldAction.Status.APPROVED
        rig.governance.onDecision = { rig.grantLogin() }
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]")) shouldBe
            ToolResult.Ok("filled 1_2 with [secret:bank_login_password]")
        rig.governance.awaited shouldBe listOf(HeldActionId("h-bank_login"))
    }

    test("a helper is told when the person said no, or did not answer in time; nothing runs") {
        val declined = Rig(lead = false)
        declined.snapshot()
        declined.governance.decision = HeldAction.Status.DECLINED
        (declined.call("click_by_uid", args("uid" to "1_4")) as ToolResult.Ok).content shouldContain "did not approve"
        declined.browser.calls("click_by_uid").shouldBeEmpty()

        val silent = Rig(lead = false)
        silent.snapshot()
        (silent.call("click_by_uid", args("uid" to "1_4")) as ToolResult.Ok).content shouldContain "has not answered"
        silent.browser.calls("click_by_uid").shouldBeEmpty()
    }

    test("the lead never waits on a held step") {
        val rig = Rig(lead = true)
        rig.snapshot()
        rig.governance.decision = HeldAction.Status.APPROVED
        (rig.call("click_by_uid", args("uid" to "1_4")) as ToolResult.Ok).content shouldContain "Waiting"
        rig.governance.awaited.shouldBeEmpty()
    }

    test("a snapshot of part of the page adds to the known elements instead of replacing them") {
        val rig = Rig()
        rig.snapshot()
        rig.browser.snapshot = "uid=9_1 button \"Close\""
        rig.call("take_snapshot", args("selector" to "dialog"))
        rig.call("click_by_uid", args("uid" to "1_3"))
        rig.triaged.single().target shouldBe "button \"Review details\""
        rig.call("take_snapshot", JsonObject(emptyMap()))
        rig.call("click_by_uid", args("uid" to "1_3"))
        rig.triaged.size shouldBe 1 // cached
    }

    test("when the triage says ask, the step is held with its reason") {
        val rig = Rig()
        rig.snapshot()
        rig.triage = TriageVerdict(false, "it looks like it submits a form")
        val result = rig.call("click_by_uid", args("uid" to "1_3")) as ToolResult.Ok
        result.content shouldContain "Waiting"
        rig.browser.calls("click_by_uid").shouldBeEmpty()
    }

    test("typing into an ordinary field passes without any model call, and typing a card number asks") {
        val rig = Rig()
        rig.snapshot()
        rig.call("fill_by_uid", args("uid" to "1_1", "value" to "dana")) shouldBe ToolResult.Ok("filled 1_1 with dana")
        rig.triaged.shouldBeEmpty()
        rig.call("fill_by_uid", args("uid" to "1_5", "value" to "4111111111111111")).shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "Waiting"
        rig.governance.steps.single().input["value"] shouldBe kotlinx.serialization.json.JsonPrimitive("4111111111111111")
    }

    test("what governance is asked to hold carries placeholders, never values") {
        val rig = Rig()
        rig.snapshot()
        rig.grantLogin()
        rig.governance.stepAnswer = Hold.Wait("held", HeldActionId("h9"))
        rig.triage = TriageVerdict(false, "types a password")
        rig.call("fill_by_uid", args("uid" to "1_3", "value" to "[secret:bank_login_password]"))
        val step = rig.governance.steps.single()
        step.input.toString() shouldContain "[secret:bank_login_password]"
        step.toString() shouldNotContain "hunter2"
        rig.triaged.single().description shouldNotContain "hunter2"
    }

    test("a browser tool that throws becomes a scrubbed error, not a crash") {
        val rig = Rig()
        rig.grantLogin()
        val boom = object : Tool {
            override val name = "browser__type_text"
            override val description = "boom"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override val effect = ToolEffect.EXTERNAL
            override suspend fun invoke(input: JsonObject): ToolResult = error("driver said hunter2!")
        }
        val tools = rig.gate.wrap(rig.scope, rig.browser.tools.filter { it.name != "browser__type_text" } + boom).associateBy { it.name }
        val result = tools.getValue("browser__type_text").invoke(args("text" to "hello"))
        result.shouldBeInstanceOf<ToolResult.Error>().message shouldBe "Browser tool type_text failed: driver said [secret:bank_login_password]"
    }

    test("calls sent in parallel reach the browser one at a time, so two fills never type over each other") {
        val rig = Rig()
        rig.grantLogin()
        var active = 0
        var most = 0
        val slow = object : Tool {
            override val name = "browser__fill_by_uid"
            override val description = "slow fill"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override val effect = ToolEffect.EXTERNAL
            override suspend fun invoke(input: JsonObject): ToolResult {
                active++
                most = maxOf(most, active)
                kotlinx.coroutines.delay(50)
                active--
                return ToolResult.Ok("filled")
            }
        }
        val tools = rig.gate.wrap(rig.scope, rig.browser.tools.filter { it.name != "browser__fill_by_uid" } + slow).associateBy { it.name }
        tools.getValue("browser__take_snapshot").invoke(JsonObject(emptyMap()))
        kotlinx.coroutines.coroutineScope {
            listOf("1_1" to "[secret:bank_login_username]", "1_2" to "[secret:bank_login_password]").map { (uid, value) ->
                async { tools.getValue("browser__fill_by_uid").invoke(args("uid" to uid, "value" to value)) }
            }.awaitAll()
        }
        most shouldBe 1
    }

    test("after a secret is typed, that field's value is hidden in snapshots even when it does not match the secret") {
        val rig = Rig()
        rig.snapshot()
        rig.grantLogin()
        rig.call("fill_by_uid", args("uid" to "1_2", "value" to "[secret:bank_login_password]"))
        rig.browser.snapshot = """
            uid=1_1 textbox "Username" value="typed by hand"
            uid=1_2 textbox "Password" value="hunXter2"
        """.trimIndent()
        val text = rig.snapshot().shouldBeInstanceOf<ToolResult.Ok>().content
        text shouldContain "value=\"typed by hand\""
        text shouldContain "uid=1_2 textbox \"Password\" value=\"[secret, hidden]\""
        text shouldNotContain "hunX"
        // a new page has new elements
        rig.call("navigate_page", args("url" to "https://bank.example.com/home"))
        rig.snapshot().shouldBeInstanceOf<ToolResult.Ok>().content shouldContain "hunXter2"
    }
})
