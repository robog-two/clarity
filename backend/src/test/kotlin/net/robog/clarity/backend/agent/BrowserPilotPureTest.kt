package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BrowserPilotPureTest : FunSpec({
    val now = Instant.parse("2026-10-27T09:30:00Z")
    fun check(step: JevStep?, p: Double, mayApprove: Boolean = false, failed: String? = null) = JevCheck(JevView(step, p, failed), mayApprove, 0.9)

    test("combineVerdict: rules that ask always win; Jev can only tighten unless it may approve") {
        val ask = StepVerdict.Ask("clicking \"Pay\"", neverAlone = true)
        combineVerdict(ask, check(JevStep.BROWSE, 0.99, mayApprove = true), signingIn = false) shouldBe ask
        combineVerdict(StepVerdict.Pass, check(JevStep.BROWSE, 0.95), false) shouldBe StepVerdict.Pass
        combineVerdict(StepVerdict.Pass, check(JevStep.BROWSE, 0.8), false) shouldBe StepVerdict.Triage
        combineVerdict(StepVerdict.Pass, check(JevStep.SIGN_IN, 0.95), signingIn = false) shouldBe StepVerdict.Triage
        combineVerdict(StepVerdict.Pass, check(JevStep.SIGN_IN, 0.95), signingIn = true) shouldBe StepVerdict.Pass
        combineVerdict(StepVerdict.Pass, check(JevStep.COMMIT, 0.6), false).shouldBeInstanceOf<StepVerdict.Ask>()
        combineVerdict(StepVerdict.Triage, check(JevStep.BROWSE, 0.99), false) shouldBe StepVerdict.Triage
        combineVerdict(StepVerdict.Triage, check(JevStep.BROWSE, 0.99, mayApprove = true), false) shouldBe StepVerdict.Pass
        combineVerdict(StepVerdict.Triage, check(JevStep.BROWSE, 0.85, mayApprove = true), false) shouldBe StepVerdict.Triage
        combineVerdict(StepVerdict.Pass, check(null, 0.0, failed = "timeout"), false).shouldBeInstanceOf<StepVerdict.Ask>().reason shouldContain "couldn't double-check"
    }

    test("codeGuard: nothing before a code; resends refused for 10 minutes, then allowed, and never past two codes") {
        val zone = TimeZone.UTC
        codeGuard(null, "Resend", null, 0.3, now, zone) shouldBe CodeGuard.Allow
        val one = SignInState(codesSent = 1, lastSentAt = now - 2.minutes, phase = SignInPhase.CODE_SENT, triggers = setOf("continue"))
        codeGuard(one, "Resend code", null, 0.3, now, zone).shouldBeInstanceOf<CodeGuard.Refuse>().message shouldBe "Refused: a code was sent at 09:28 – use ask_code (then type it with act or sign_in)."
        codeGuard(one, "Continue", null, 0.3, now, zone).shouldBeInstanceOf<CodeGuard.Refuse>()
        codeGuard(one, "Verify", 0.1, 0.3, now, zone) shouldBe CodeGuard.Allow
        codeGuard(one, "Verify", 0.35, 0.3, now, zone).shouldBeInstanceOf<CodeGuard.Refuse>()
        codeGuard(one, "Resend", null, 0.3, now + 7.minutes, zone).shouldBeInstanceOf<CodeGuard.Refuse>()
        codeGuard(one, "Resend", null, 0.3, now + 9.minutes, zone) shouldBe CodeGuard.Allow
        val two = one.copy(codesSent = 2)
        codeGuard(two, "Resend", null, 0.3, now + 3.hours(), zone).shouldBeInstanceOf<CodeGuard.Refuse>().message shouldContain "2 codes were already sent"
        codeGuard(SignInState(1, now, SignInPhase.CODE_SENT), "Send", null, 0.3, now, TimeZone.of("America/New_York"))
            .shouldBeInstanceOf<CodeGuard.Allow>() // "Send" alone is not a resend label and not a trigger
    }

    test("isResendStep reads resend labels, not verify ones") {
        listOf("Resend", "Resend code", "Send me a new code", "Didn't get a code?", "Text me", "Try again").forEach { isResendStep(it) shouldBe true }
        listOf("Verify", "Continue", "Submit code", "Sign in").forEach { isResendStep(it) shouldBe false }
    }

    test("parseTabs and diffTabs: a tab opened at the end, a tab closed in the middle; openers shift") {
        val before = parseTabs("2 pages (selected: 0)\n>[0] Shop (https://shop.example.com/)\n [1] Mail (https://mail.example.com/)")
        before shouldBe listOf(Tab(0, "Shop", "https://shop.example.com/", true), Tab(1, "Mail", "https://mail.example.com/", false))
        val opened = before + Tab(2, "Clarity ID", "https://login.example.com/", false)
        diffTabs(before, opened) shouldBe TabDiff(listOf(opened[2]), null)
        diffTabs(opened, listOf(opened[0], opened[2].copy(index = 1))).closed shouldBe 1
        shiftAfterClose(mapOf(2 to 0, 3 to 1), closed = 1) shouldBe mapOf(1 to 0)
        shiftAfterClose(mapOf(2 to 0), closed = 2) shouldBe emptyMap()
    }

    test("formatLook: header with tabs, dialog and sign-in; drops text lines first and counts the rest") {
        val items = (1..200).map { i -> SeenElement("c$i", if (i % 2 == 0) "text" else "button", "Item number $i with a longish label", y = i * 10.0) }
        val page = PageObservation(url = "https://shop.example.com/a", title = "Shop", vh = 700.0, docH = 700.0, dialog = "Cookies", items = items, total = 200)
        val header = LookHeader(listOf(Tab(0, "Shop", "https://shop.example.com/a", true), Tab(1, "ID", "https://id.example.com/", false)), mapOf(1 to 0), SignInState(1, now, SignInPhase.CODE_SENT), TimeZone.UTC)
        val text = formatLook(page, header, budget = 2000)
        text shouldContain "Tabs: [0]* Shop – shop.example.com/a · [1] ID – id.example.com/ (opened by tab 0)"
        text shouldContain "Dialog open: \"Cookies\""
        text shouldContain "Sign-in on example.com: code sent, waiting for it; 1 code sent (last at 09:30)."
        text shouldContain "[c1] button"
        text shouldNotContain "[c2] text"
        text shouldContain "more – narrow with look"
        (text.length <= 2100) shouldBe true
    }

    test("change report: no visible change is said outright; a new code field and Jev's verdict are reported") {
        val before = PageObservation(url = "https://a.example.com/x", title = "A")
        val none = StepChange(before, before.copy(same = true), TabDiff(emptyList(), null), emptyList(), null, null, 0.2, null, "a new page", false)
        visiblyChanged(none) shouldBe false
        changeReport(none, 1) shouldContain "No visible change."
        changeReport(none, 2) shouldContain "stop and call look"
        val code = none.copy(after = before.copy(otp = true, added = listOf("We sent a code")), codeSent = true, verified = 0.91, expect = "a code page")
        val text = changeReport(code, 0)
        text shouldContain "A one-time-code field is now on the page."
        text shouldContain "sent a sign-in code"
        text shouldContain "looks like \"a code page\" happened (Jev p=0.91)"
        text shouldNotContain "No visible change"
    }

    test("codeSentBy: a code field appearing counts; a resend that reloads the code page counts; a wrong code does not") {
        val form = PageObservation(url = "https://id.example.com/method")
        val code = PageObservation(url = "https://id.example.com/code", otp = true, signals = listOf("We sent a code"))
        codeSentBy(form, code.copy(same = false), "Continue") shouldBe true
        codeSentBy(code, code.copy(same = false), "Resend") shouldBe true
        codeSentBy(code, code.copy(same = false), "Verify") shouldBe false
        codeSentBy(code, code.copy(same = true), "Resend") shouldBe false
        codeSentBy(code, code.copy(same = false), "Continue", triggers = setOf("continue")) shouldBe true
    }

    test("Jev request shapes: image parts as data URLs, a box question without criteria, a noul with criteria") {
        val ask = groundingAsk("the SMS option", FakeSite.PNG)
        val body = decisionRequestBody("typesafe/jev-1.14-20261014", ask.state, ask.questions, zdr = true)
        val state = body["state"] as JsonArray
        state[0] shouldBe JsonPrimitive("Screenshot of a browser tab, as the person sees it:")
        val part = state[1].jsonObject
        part["type"]!!.jsonPrimitive.content shouldBe "image_url"
        part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,iVBOR") shouldBe true
        val questions = body["questions"]!!.jsonObject
        questions[Q_TARGET] shouldBe JsonObject(mapOf("type" to JsonPrimitive("box"), "instructions" to JsonPrimitive("the SMS option, in the screenshot of the browser tab")))
        questions[Q_VISIBLE]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "noul"
        body["provider"]!!.jsonObject["zdr"] shouldBe JsonPrimitive(true)
    }

    test("a box answer is parsed as fractions; a bad box is malformed, never a guess") {
        val questions = mapOf(Q_TARGET to DecisionQuestion.Box("x"), Q_VISIBLE to DecisionQuestion.Noul("v", "t", "f"))
        val ok = parseDecisionResponse(
            """{"answers":{"target":{"type":"box","box":{"top":0.49,"bottom":0.89,"left":0.58,"right":0.96},"confidence":0.93},"visible":{"type":"noul","noul":0.8}},"usage":{"input_tokens":1200,"output_tokens":0,"cost":0.0000504}}""",
            questions,
        ).getOrNull().shouldNotBeNull()
        ok.answers[Q_TARGET] shouldBe DecisionAnswer.Box(0.49, 0.89, 0.58, 0.96, 0.93)
        ok.usage.costMicros shouldBe 50
        parseDecisionResponse("""{"answers":{"target":{"type":"box","box":{"top":0.9,"bottom":0.1,"left":0.5,"right":0.6}},"visible":{"type":"noul","noul":0.8}}}""", questions).isLeft() shouldBe true
    }

    test("images: PNG size, Jev's size limits, a red outline and black masks") {
        pngSize(FakeSite.PNG) shouldBe (800 to 600)
        jevAcceptsSize(1280, 714) shouldBe true
        jevAcceptsSize(200, 714) shouldBe false
        jevAcceptsSize(1600, 900) shouldBe false
        val edited = editPng(FakeSite.PNG, rects = listOf(PixelRect(10, 10, 50, 20)), outline = PixelRect(200, 200, 100, 40)).shouldNotBeNull()
        val img = ImageIO.read(ByteArrayInputStream(edited))
        (img.getRGB(30, 20) and 0xffffff) shouldBe 0
        (img.getRGB(198, 220) and 0xff0000) shouldBe 0xe60000
        (img.getRGB(400, 400) and 0xffffff) shouldBe 0xffffff
        editPng(byteArrayOf(1, 2, 3)).shouldBeNull()
        boxPixels(DecisionAnswer.Box(0.5, 0.6, 0.25, 0.5, null), 800, 600) shouldBe PixelRect(200, 300, 200, 60)
    }

    test("intents: scrolls and key presses are not elements; exact matches need one element with exactly those words") {
        scrollIntent("Scroll down") shouldBe 1
        scrollIntent("the scroll bar").shouldBeNull()
        keyIntent("press Escape") shouldBe "Escape"
        keyIntent("press the Enter key") shouldBe "Enter"
        val items = listOf(SeenElement("c1", "button", "Continue"), SeenElement("c2", "link", "Continue shopping"), SeenElement("c3", "heading", "Continue"))
        exactMatch(items, "the Continue button")?.ref shouldBe "c1"
        exactMatch(items + SeenElement("c4", "button", "Continue"), "Continue").shouldBeNull()
        candidates(items, "continue shopping").first().ref shouldBe "c1"
    }

    test("the observation script fits evaluate_script's 16 KiB with its arguments") {
        (ObserveScript.call("look", observeArgs(listOf("c1", "c2"), mapOf("scope" to "c9"))).length < 16 * 1024) shouldBe true
        ObserveScript.source shouldNotContain "\n//"
        parseScriptResult("Script ran on page and returned:\n```json\n{\"a\": 1}\n```") shouldBe JsonObject(mapOf("a" to JsonPrimitive(1)))
    }
})

private fun Int.hours() = kotlin.time.Duration.parse("${this}h")
