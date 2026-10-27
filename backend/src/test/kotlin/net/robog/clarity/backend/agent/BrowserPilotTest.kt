package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.BrowserPilotConfig

class BrowserPilotTest : FunSpec({

    test("the helper sees the pilot's tools, not the raw browser tools; other servers' tools are untouched") {
        val rig = PilotRig()
        rig.tools.keys shouldContain "browser__look"
        rig.tools.keys shouldContain "browser__act"
        rig.tools.keys shouldContain "browser__sign_in"
        rig.tools.keys shouldNotContain "browser__click_by_uid"
        rig.tools.keys shouldNotContain "browser__evaluate_script"
        rig.tools.keys shouldNotContain "browser__take_snapshot"
    }

    test("look on a deep login form: fields 19 levels down are listed with autocomplete and type, from the observation script, not the snapshot") {
        val rig = PilotRig()
        rig.site.open(rig.site.signin(false))
        val text = rig.call("look").text()
        text shouldContain "Tabs: [0]* Clarity ID – login.example.com/signin"
        text shouldContain "[c11] textbox \"Email address\" type=email autocomplete=username"
        text shouldContain "[c12] textbox \"Password\" type=password autocomplete=current-password"
        text shouldContain "[c13] button \"Continue\""
        text shouldContain "Sign-in on example.com: asking for the login"
        rig.site.calls.map { it.first } shouldNotContain "take_snapshot"
    }

    test("an action that opens a popup selects the new tab and says so; go in the opener is refused while it is open") {
        val rig = PilotRig()
        val report = rig.act("the Sign in with Clarity ID button", "a sign-in window")
        report shouldContain "Opened tab 1 (opener 0): Clarity ID — selected it."
        rig.site.selected shouldBe 1
        rig.call("look").text() shouldContain "[1]* Clarity ID – login.example.com/signin (opened by tab 0)"

        rig.call("tabs", buildJsonObject { put("select", 0) })
        val refused = rig.call("go", buildJsonObject { put("url", "https://shop.example.com/other") }).text()
        refused shouldContain "finish or close the sign-in tab first"
        rig.site.calls.map { it.first } shouldNotContain "navigate_page"
        rig.call("back").text() shouldContain "finish or close the sign-in tab first"

        // closing the popup lifts it
        rig.call("tabs", buildJsonObject { put("close", 1) })
        rig.call("go", buildJsonObject { put("url", "https://shop.example.com/other") }).text() shouldContain "Now on"
    }

    test("a popup sign-in that closes itself returns to its opener and says so") {
        val rig = PilotRig()
        rig.grantLogin()
        rig.grants.put("code", "example.com", mapOf("h-code" to FakeSite.CODE), 5.minutes)
        rig.act("the Sign in with Clarity ID button", "a sign-in window")
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "asks where to send a sign-in code"
        rig.act("the SMS option", "SMS is selected")
        rig.act("the Continue button", "a page asking for a 6-digit code") shouldContain "sent a sign-in code"
        val done = rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text()
        done shouldContain "back on tab 0"
        done shouldContain "Signed in to example.com"
        rig.site.selected shouldBe 0
        rig.site.tabs.size shouldBe 1
        rig.site.signedIn shouldBe true
    }

    test("after a code was sent, Resend and pressing Continue again are refused for 10 minutes; a third code is never requested") {
        val rig = PilotRig()
        rig.site.open(rig.site.method(false))
        rig.act("the SMS option", "SMS is selected")
        val sent = rig.act("the Continue button", "a page asking for a 6-digit code")
        sent shouldContain "sent a sign-in code"
        rig.site.codesSent shouldBe 1
        rig.call("look").text() shouldContain "1 code sent (last at 12:00)"

        rig.act("the Resend button", "a new code") shouldContain "a code was sent at 12:00 – use ask_code"
        rig.site.codesSent shouldBe 1

        // back on the method page, "Continue" is what sent the code: refused too
        rig.site.open(rig.site.method(false))
        rig.act("the Continue button", "a code page") shouldContain "a code was sent at 12:00"
        rig.site.codesSent shouldBe 1

        rig.site.open(rig.site.code(false))
        rig.clock.advance(11.minutes)
        rig.act("the Resend button", "a new code") shouldContain "sent a sign-in code"
        rig.site.codesSent shouldBe 2

        rig.clock.advance(30.minutes)
        val third = rig.act("the Resend button", "a new code")
        third shouldContain "2 codes were already sent"
        third shouldContain "report back"
        rig.site.codesSent shouldBe 2
    }

    test("Jev flagging a step as sending another code refuses it even when the label does not say so") {
        val rig = PilotRig()
        rig.site.open(rig.site.method(false))
        rig.act("the SMS option", "SMS is selected")
        rig.act("the Continue button", "a code page")
        rig.jev.resend = { 0.4 }
        rig.act("the Verify button", "signed in") shouldContain "use ask_code"
        rig.jev.resend = { 0.1 }
        rig.act("the Verify button", "signed in") shouldNotContain "Refused"
    }

    test("sign_in happy path: login, method chosen with act, the code through ask_code, verified signed in") {
        val rig = PilotRig()
        rig.site.open(rig.site.signin(false))
        rig.grantLogin()
        val first = rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text()
        first shouldContain "asks where to send a sign-in code"
        rig.site.values["c11"] shouldBe "dana.k@inbox.dev"
        rig.site.values["c12"] shouldBe "correct horse"
        rig.act("the SMS option", "SMS is selected")
        rig.act("the Continue button", "a page asking for a 6-digit code")
        rig.site.codesSent shouldBe 1

        rig.grants.put("code", "example.com", mapOf("h-code" to FakeSite.CODE), 5.minutes)
        val done = rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text()
        done shouldContain "Asked the person for the code"
        done shouldContain "Signed in to example.com"
        done shouldNotContain FakeSite.CODE
        rig.site.signedIn shouldBe true
        rig.site.codesSent shouldBe 1
        rig.call("look").text() shouldContain "Sign-in on example.com: signed in; 1 code sent"
    }

    test("sign_in waits for a code the person has not typed and reports back without resending") {
        val rig = PilotRig()
        rig.site.open(rig.site.code(false))
        val text = rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text()
        text shouldContain "has not typed that code yet"
        rig.site.codesSent shouldBe 0
        rig.site.clicked.shouldBeEmpty()
    }

    test("\"not found\" grounding never clicks and hands back the text view") {
        val rig = PilotRig()
        rig.jev.visible = { null }
        val text = rig.act("the Checkout button", "the cart")
        text shouldContain "Not found"
        text shouldContain "[c2] link \"Sign in\""
        rig.clicks().shouldBeEmpty()
        rig.site.clicked.shouldBeEmpty()
    }

    test("with jevMayApprove off, Jev's sure 'browse' cannot pass a step the triage would ask about") {
        val rig = PilotRig(lead = true)
        rig.jev.step = { "browse" to 0.99 }
        rig.triage = TriageVerdict(false, "it may submit something")
        rig.act("the Details button", "details") shouldContain "Waiting for the person"
        rig.triaged.size shouldBe 1
        rig.governance.steps.size shouldBe 1
        rig.site.clicked.shouldBeEmpty()
    }

    test("with jevMayApprove on, a sure 'browse' passes a step the rules leave open without the triage") {
        val rig = PilotRig(settings = BrowserPilotConfig(jevMayApprove = true), lead = true)
        rig.jev.step = { "browse" to 0.95 }
        rig.triage = TriageVerdict(false, "would ask")
        rig.act("the Details button", "details")
        rig.triaged.shouldBeEmpty()
        rig.site.clicked shouldContain "Details"
    }

    test("Jev can make a step the rules pass stricter: not sure it is browsing goes to the triage, a commit to the person") {
        val rig = PilotRig(lead = true)
        rig.site.open(rig.site.signin(false))
        rig.jev.step = { "unsure" to 0.6 }
        rig.act("the Continue button", "next page")
        rig.triaged.size shouldBe 1
        rig.jev.step = { "commit" to 0.8 }
        rig.site.open(rig.site.signin(false))
        rig.act("the Continue button", "next page") shouldContain "Waiting for the person"
        rig.governance.steps.single().summary shouldContain "Continue"
    }

    test("deterministic rules beat Jev: a risky button is held even when Jev is sure it is browsing and may approve") {
        val rig = PilotRig(settings = BrowserPilotConfig(jevMayApprove = true), lead = true)
        rig.jev.step = { "browse" to 0.99 }
        rig.act("the Send money button", "money sent") shouldContain "Waiting for the person"
        rig.governance.steps.single().summary shouldContain "Send money"
        rig.site.clicked.shouldBeEmpty()
        rig.triaged.shouldBeEmpty()
    }

    test("Jev errors fail closed: no grounding means no click; a failed step check asks the person") {
        val rig = PilotRig(lead = true)
        rig.jev.fail = DecisionError.Unreachable("timeout")
        rig.act("the Sign in link", "the sign-in page") shouldContain "I could not check the screen"
        rig.site.clicked.shouldBeEmpty()

        rig.jev.fail = null
        rig.jev.failApproval = DecisionError.Http(500, "boom")
        rig.act("the Sign in link", "the sign-in page") shouldContain "Waiting for the person"
        rig.governance.steps.single().summary shouldContain "Sign in"
        rig.site.clicked.shouldBeEmpty()
    }

    test("without any decision model the pilot never acts") {
        val rig = PilotRig()
        val pilot = BrowserPilot(rig.gate, FakeBrowserModels(null, null), rig.audit, rig.vault, rig.governance,
            net.robog.clarity.backend.config.AgentConfig(reviewInterval = kotlin.time.Duration.parse("2h")), rig.clock, { kotlinx.datetime.TimeZone.UTC }, settle = kotlin.time.Duration.parse("1ms"))
        val act = pilot.wrap(rig.scope, rig.site.tools).first { it.name == "browser__act" }
        act.invoke(buildJsonObject { put("intent", "the Sign in link"); put("expect", "x") }).text() shouldContain "I never click blind"
        rig.site.clicked.shouldBeEmpty()
    }

    test("two steps in a row without visible change: the report says so and act waits for a look") {
        val rig = PilotRig()
        rig.jev.happened = 0.1
        rig.act("the Details button", "details open") shouldContain "No visible change."
        val second = rig.act("the Details button", "details open")
        second shouldContain "two steps in a row with no visible change"
        rig.act("the Details button", "details open") shouldContain "Call look"
        rig.site.clicked.size shouldBe 2
        rig.call("look")
        rig.act("the Details button", "details open") shouldContain "No visible change."
    }

    test("the change report names the page change and Jev's verdict on expect") {
        val rig = PilotRig()
        val text = rig.act("the Sign in link", "a sign-in form")
        text shouldContain "Page changed: https://shop.example.com/ → https://login.example.com/signin"
        text shouldContain "looks like \"a sign-in form\" happened (Jev p=0.90)"
    }

    test("audit rows are written with Jev's answers and frames, and no frame is stored while a secret may be visible") {
        val rig = PilotRig()
        rig.act("the Sign in link", "a sign-in form")
        val first = rig.audit.rows.single()
        first.tool shouldBe "act"
        first.gate shouldContain "passed"
        first.hitName shouldBe "Sign in"
        first.beforePng.shouldNotBeNull()
        first.afterPng.shouldNotBeNull()
        first.box.shouldNotBeNull()
        first.jev.shouldNotBeNull().keys shouldBe setOf("ground", "approve", "verify")
        first.costMicros shouldBe 126

        rig.grantLogin()
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") })
        val signIn = rig.audit.rows.last()
        signIn.tool shouldBe "sign_in"
        signIn.afterWithheld shouldBe true
        signIn.afterPng.shouldBeNull()

        // the next step right after: its before frame is withheld too, and the row says so
        rig.act("the SMS option", "SMS is selected")
        val next = rig.audit.rows.last()
        next.beforeWithheld shouldBe true
        next.beforePng.shouldBeNull()
        next.changeReport.shouldNotBeNull() shouldNotContain "correct horse"
        rig.audit.rows.forEach { row -> (row.action.orEmpty() + row.changeReport.orEmpty()) shouldNotContain "correct horse" }
    }

    test("the action cap stops a long browser task") {
        val rig = PilotRig(settings = BrowserPilotConfig(maxActions = 2))
        rig.act("the Details button", "x")
        rig.call("look")
        rig.act("the Details button", "x")
        rig.act("the Details button", "x") shouldContain "used its 2 actions"
        rig.site.clicked.size shouldBe 2
    }

    test("a typed value goes only into a field; a placeholder is substituted by the gate and never shown") {
        val rig = PilotRig()
        rig.site.open(rig.site.signin(false))
        rig.act("the Continue button", "x", value = "hello") shouldContain "not a field"
        rig.grantLogin()
        val text = rig.act("the Password field", "the password is filled in", value = "[secret:clarity_login_password]")
        rig.site.values["c12"] shouldBe "correct horse"
        text shouldNotContain "correct horse"
        rig.jev.asked.size shouldBeGreaterThan 0
    }

    test("a low first p gets a second ask by the element's own name, and acts only when that box lands on the element") {
        val rig = PilotRig()
        // Jev does not see "the Sign in link" (it is drawn as a button), but sees the control by its name.
        rig.jev.visible = { intent -> if ("labelled" in intent) rig.site.find(intent) else null }
        rig.act("the Sign in link", "a sign-in form") shouldContain "Page changed"
        rig.audit.rows.last().jev!!.keys shouldContain "ground_named"
        rig.site.clicked shouldBe listOf("Sign in")

        // The second box lands on another element: not found, nothing clicked.
        val other = PilotRig()
        other.jev.visible = { intent -> if ("labelled" in intent) other.site.page.els.first { it.name == "Details" } else null }
        other.act("the Sign in link", "a sign-in form") shouldContain "Not found"
        other.site.clicked.shouldBeEmpty()

        // Nothing on the page shares a word with the intent: no second ask.
        val none = PilotRig()
        none.jev.visible = { null }
        none.act("the Checkout button", "the cart") shouldContain "Not found"
        none.audit.rows.last().jev!!.keys shouldBe setOf("ground")
    }

    test("a box that lands on the neighbouring control is not clicked: Jev is asked again by the right control's name") {
        val rig = PilotRig()
        // Live, Jev's box for "Reject all" straddled both buttons and its centre sat on "Accept all".
        rig.jev.visible = { intent ->
            if ("labelled" in intent) rig.site.find(intent) else rig.site.page.els.first { it.name == "Accept all" }
        }
        rig.act("the Reject all button", "the banner closes")
        rig.site.clicked shouldBe listOf("Reject all")
        rig.audit.rows.last().jev!!.keys shouldBe setOf("ground", "ground_named", "approve", "verify")

        // An intent that names both controls is a tie: no guess, whatever Jev's box says.
        val tie = PilotRig()
        tie.jev.visible = { tie.site.page.els.first { it.name == "Accept all" } }
        tie.act("the white Reject all button next to the blue Accept all button", "the banner closes") shouldContain "several controls fit"
        tie.site.clicked.shouldBeEmpty()

        // When the second box lands on the wrong control too, nothing is clicked.
        val stuck = PilotRig()
        stuck.jev.visible = { stuck.site.page.els.first { it.name == "Accept all" } }
        stuck.act("the Reject all button", "the banner closes") shouldContain "Not found"
        stuck.site.clicked.shouldBeEmpty()
    }

    test("clickjacking: an invisible layer, a transparent one, a page-wide catcher or a covered control is never clicked") {
        val layers = listOf(
            FakeSite.El("c29", "link", "Continue", x = 0.0, y = 0.0, w = 800.0, h = 600.0, paint = HitFacts(painted = false, under = 6400.0)) { hijacked = true } to "invisible layer",
            FakeSite.El("c29", "button", "Continue", y = 260.0, paint = HitFacts(opacity = 0.02)) { hijacked = true } to "transparent",
            FakeSite.El("c29", "link", "Continue", x = 0.0, y = 0.0, w = 800.0, h = 600.0, paint = HitFacts(painted = true)) { hijacked = true } to "far larger than what Jev pointed at",
            FakeSite.El("c29", "button", "Continue", y = 260.0, paint = HitFacts(onTop = false)) { hijacked = true } to "something else is on top",
        )
        for ((layer, why) in layers) {
            val rig = PilotRig()
            rig.site.overlay = layer
            rig.site.open(rig.site.method(false))
            // Jev points at a small box; the page answers with the layer.
            rig.jev.visible = { intent -> if ("Continue" in intent) rig.site.page.els.first { it.ref == "c23" } else rig.site.find(intent) }
            val text = rig.act("the Continue button", "a code page")
            text shouldContain "Not found"
            text shouldContain why
            rig.site.hijacked shouldBe false
            rig.site.clicked.shouldBeEmpty()
            rig.site.codesSent shouldBe 0
        }
    }

    test("the approval image outlines the element that will be clicked, and the stored frame also shows Jev's box") {
        val rig = PilotRig()
        rig.act("the Sign in link", "a sign-in form")
        val row = rig.audit.rows.single()
        val stored = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(row.beforePng!!))
        // "Sign in" is at x 40..200, y 100..140 on the fake page: red frame just outside it.
        (stored.getRGB(37, 120) and 0xff0000) shouldBe 0xe60000
        // Jev's box (the same element in the fake) is drawn dashed blue on top of the frame's inside edge.
        (stored.getRGB(45, 101) and 0x0000ff) shouldBe 0xff
    }

    test("a wrong code leads back to ask_code without a new code from the site; three rejections hand back") {
        val rig = PilotRig()
        rig.site.open(rig.site.method(false))
        rig.act("the SMS option", "SMS is selected")
        rig.act("the Continue button", "a code page")
        rig.site.codesSent shouldBe 1

        rig.grants.put("code", "example.com", mapOf("h-code" to "000000"), 5.minutes)
        val first = rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text()
        first shouldContain "did not accept the code"
        first shouldContain "attempt 1 of 3"
        first shouldContain "do not press Resend"
        rig.act("the Resend button", "a new code") shouldContain "use ask_code"
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "attempt 2 of 3"
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "rejected the code 3 times"
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "Stop and report back"
        rig.site.codesSent shouldBe 1
        rig.site.clicked.count { it == "Verify" } shouldBe 3
        rig.site.clicked shouldNotContain "Resend"
    }

    test("a wrong code then the right one signs in, still with one code sent") {
        val rig = PilotRig()
        rig.site.open(rig.site.code(false))
        rig.grants.put("code", "example.com", mapOf("h-code" to "000000"), 5.minutes)
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "did not accept the code"
        rig.grants.put("code", "example.com", mapOf("h-code" to FakeSite.CODE), 5.minutes)
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "Signed in to example.com"
        rig.site.codesSent shouldBe 0
    }

    test("frame retention drops old screenshots and keeps the rows") {
        val rig = PilotRig()
        rig.act("the Sign in link", "a sign-in form")
        rig.audit.dropFrames(rig.clock.now() - 1.minutes, rig.clock.now()) shouldBe 0
        rig.clock.advance(kotlin.time.Duration.parse("31d"))
        rig.audit.dropFrames(rig.clock.now() - kotlin.time.Duration.parse("30d"), rig.clock.now()) shouldBe 1
        val row = rig.audit.rows.single()
        row.beforePng.shouldBeNull()
        row.afterPng.shouldBeNull()
        row.changeReport.shouldNotBeNull() shouldContain "Page changed"
    }
})
