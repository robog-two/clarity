package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The browser pilot waiting for pages to settle, keeping a sign-in that is done, and scrolling: the
 * pure rules first, then the pilot over the fake site.
 */
class BrowserSettleTest : FunSpec({

    // ---- pure -----------------------------------------------------------------------------------

    test("settle: ready, 500 ms without a finished request or a DOM change, and no loading indicator") {
        settleWait(SettleProbe(ready = "complete", mutAgo = 800, netAgo = 900)).shouldBeNull()
        settleWait(SettleProbe(ready = "interactive", mutAgo = 800, netAgo = 900)).shouldBeNull()
        settleWait(null)!! shouldContain "does not answer"
        settleWait(SettleProbe(ready = "loading", mutAgo = 800, netAgo = 900))!! shouldContain "document is still loading"
        settleWait(SettleProbe(ready = "complete", mutAgo = 800, netAgo = 120))!! shouldContain "requests"
        settleWait(SettleProbe(ready = "complete", mutAgo = 40, netAgo = 900))!! shouldContain "still changing"
        // Right after a scroll, before the list's fetch shows anywhere: not settled yet.
        settleWait(SettleProbe(ready = "complete", mutAgo = 5000, netAgo = 5000, actAgo = 30))!! shouldContain "still answering the step"
        settleWait(SettleProbe(ready = "complete", mutAgo = 5000, netAgo = 5000, busy = "Loading appointments"))!! shouldContain "\"Loading appointments\""
    }

    test("a change report on an unsettled page says so and never says 'did not happen'") {
        val page = PageObservation(url = "https://www.ulta.com/beautyservices/appointment", title = "Ulta")
        val change = StepChange(
            page, page.copy(added = listOf("Book an appointment")), TabDiff(emptyList(), null), emptyList(), null, null,
            verified = 0.14, verifyNote = null, expect = "signed in and back on the booking page", codeSent = false,
            loading = "a loading indicator is showing: \"spinner\"", waitedMillis = 8_100,
        )
        val text = changeReport(change, 0)
        text shouldContain "Still loading after 8 s (a loading indicator is showing: \"spinner\")"
        text shouldContain "is not there yet, but the page is still loading (Jev p=0.14)"
        text shouldNotContain "did not happen"
        changeReport(change.copy(loading = null), 0) shouldContain "did not happen (Jev p=0.14)"
    }

    test("signed in only on firm signals: the half-loaded Ulta page is not judged, the settled one is") {
        // Run 4c935101: right after VERIFY, the SPA had not rendered; Jev saw no sign of being signed in.
        signedInVerdict(SignInSignals(loginForm = false, authUrl = false, account = false, cookiesChanged = false, jev = "other" to 0.14)).shouldBeNull()
        // Settled: a greeting and new session cookies.
        signedInVerdict(SignInSignals(loginForm = false, authUrl = false, account = true, cookiesChanged = true, jev = "other" to 0.4)) shouldBe true
        signedInVerdict(SignInSignals(loginForm = false, authUrl = false, account = true, cookiesChanged = false, jev = "signed_in" to 0.8)) shouldBe true
        signedInVerdict(SignInSignals(loginForm = false, authUrl = false, account = true, cookiesChanged = false)).shouldBeNull()
        signedInVerdict(SignInSignals(loginForm = false, authUrl = true, account = true, cookiesChanged = true, jev = "signed_in" to 0.9)).shouldBeNull()
        signedInVerdict(SignInSignals(loginForm = true, authUrl = false, account = true, cookiesChanged = true)) shouldBe false
        signedInVerdict(SignInSignals(loginForm = false, authUrl = false, account = true, cookiesChanged = true, expired = true)) shouldBe false
        signedInVerdict(SignInSignals(loginForm = false, authUrl = false, account = true, cookiesChanged = true, jev = "needs_login" to 0.9)).shouldBeNull()
    }

    test("sign-in addresses, and which controls would restart a finished sign-in") {
        isAuthUrl("https://login.clarity.test/signin?return=x") shouldBe true
        isAuthUrl("https://www.ulta.com/ulta/myaccount/login.jsp") shouldBe true
        isAuthUrl("https://accounts.google.com/o/oauth2/auth") shouldBe true
        isAuthUrl("https://www.ulta.com/beautyservices/appointment") shouldBe false
        isAuthUrl("https://www.ulta.com/welcome") shouldBe false
        isAuthUrl("https://shop.example.com/account") shouldBe false
        flowKey("https://www.ulta.com/welcome/?a=1#x") shouldBe "https://www.ulta.com/welcome"

        val starters = setOf("https://www.ulta.com/welcome" to "continue")
        restartsSignIn(true, false, "https://www.ulta.com/welcome?x=1", "Continue", starters) shouldBe true
        restartsSignIn(true, false, "https://www.ulta.com/beautyservices", "Continue", starters) shouldBe false
        restartsSignIn(true, false, "https://www.ulta.com/", "Sign In", emptySet()) shouldBe true
        restartsSignIn(true, false, "https://www.ulta.com/", "Book now", emptySet()) shouldBe false
        restartsSignIn(true, true, "https://www.ulta.com/welcome", "Continue", starters) shouldBe false // a login form: the session may have ended
        restartsSignIn(false, false, "https://www.ulta.com/welcome", "Continue", starters) shouldBe false
    }

    test("the code guard lets a site that asks to verify again after sign-in send one new code, then guards it") {
        val t = Instant.parse("2026-10-25T14:00:00Z")
        val signedIn = SignInState(codesSent = 1, lastSentAt = t, phase = SignInPhase.CODE_SENT, triggers = setOf("continue")).signIn(t.plus(2.minutes))
        signedIn.signedInAt shouldBe t.plus(2.minutes)
        signedIn.stepUpOpen() shouldBe true
        codeGuard(signedIn, "Send code", 0.9, 0.3, t.plus(3.minutes), TimeZone.UTC) shouldBe CodeGuard.Allow
        val stepUp = signedIn.copy(codesSent = 2, lastSentAt = t.plus(3.minutes))
        stepUp.stepUpOpen() shouldBe false
        codeGuard(stepUp, "Resend", 0.9, 0.3, t.plus(4.minutes), TimeZone.UTC).shouldBeInstanceOf<CodeGuard.Refuse>()
        signInLine("ulta.com", stepUp, TimeZone.UTC)!! shouldContain "Signed in to ulta.com (since 14:02); 2 codes sent"
    }

    test("scroll intents: bare, named, up; pickArea by the area's name") {
        scrollOf("scroll down") shouldBe ScrollRequest(1, null)
        scrollOf("Scroll up.") shouldBe ScrollRequest(-1, null)
        scrollOf("page down") shouldBe ScrollRequest(1, null)
        scrollOf("scroll down the page") shouldBe ScrollRequest(1, null)
        scrollOf("scroll the available times down") shouldBe ScrollRequest(1, "the available times")
        scrollOf("scroll down in the list of times") shouldBe ScrollRequest(1, "the list of times")
        scrollOf("scroll up inside the stylists list") shouldBe ScrollRequest(-1, "the stylists list")
        scrollOf("the scroll bar").shouldBeNull()
        scrollIntent("scroll down") shouldBe 1
        scrollIntent("scroll the times down").shouldBeNull()

        val times = ScrollArea("c40", "Available times", rows = 8, total = 30, below = 880.0)
        val dates = ScrollArea("c41", "More dates", rows = 6, total = 60, below = 2160.0)
        pickArea(listOf(times, dates), "the available times") shouldBe times
        pickArea(listOf(times, dates), "the dates list") shouldBe dates
        pickArea(listOf(times, dates), "the stylists").shouldBeNull()
        pickArea(listOf(times), "the list") shouldBe times
        times.line() shouldBe "[c40] scroll area \"Available times\": showing ~8 of ~30 rows, more below (880 px)"
    }

    test("read accumulation: rows a list loads on scroll are added, rows a virtualized list drops are kept, overlap is dropped") {
        val first = listOf("9:00", "9:15", "9:30", "9:45")
        // A list that loads more keeps the old rows: only the new ones are added.
        mergeLines(first, first + listOf("10:00", "10:15")) shouldBe first + listOf("10:00", "10:15")
        // A virtualized list shows a moving window: the overlap is dropped, the dropped rows stay.
        mergeLines(first, listOf("9:30", "9:45", "10:00", "10:15")) shouldBe first + listOf("10:00", "10:15")
        // The same window again (nothing moved): nothing added.
        mergeLines(first, listOf("9:15", "9:30")) shouldBe first
        // Rows that repeat inside one reading stay ("Available" on every row).
        mergeLines(emptyList(), listOf("Available", "Available")) shouldBe listOf("Available", "Available")
        mergeLines(listOf("a", "Available"), listOf("Available", "Available", "b")) shouldBe listOf("a", "Available", "Available", "b")
        // A row cut at the window's top edge (live: a lone "Select") does not break the overlap.
        mergeLines(first, listOf("Select", "9:30", "9:45", "10:00")) shouldBe first + "10:00"
        mergeLines(first, listOf("Select", "9:30", "9:45")) shouldBe first
        // New rows at the end of a reading are never dropped.
        mergeLines(first, listOf("9:45", "10:00", "10:15")) shouldBe first + listOf("10:00", "10:15")
        // No overlap at all (a jump): everything is added.
        mergeLines(first, listOf("11:00")) shouldBe first + "11:00"
    }

    test("look shows how much of the page is below and lists scroll areas as their own entries") {
        val page = PageObservation(
            url = "https://salon.example.com/booking", title = "Book", vw = 1280.0, vh = 714.0, below = 3200.0,
            items = listOf(SeenElement("c3", "button", "Times after 6:30 PM", inView = false, area = "c40")), total = 1,
            areas = listOf(ScrollArea("c40", "Available times", rows = 8, total = 30, below = 880.0)),
        )
        val text = formatLook(page, LookHeader(emptyList(), emptyMap(), null, TimeZone.UTC, loading = "after 8 s, requests are still finishing"))
        text shouldContain "Page continues: 3,200 px below"
        text shouldContain "[c40] scroll area \"Available times\": showing ~8 of ~30 rows, more below (880 px)"
        text shouldContain "[c3] button \"Times after 6:30 PM\" (scrolled out of view in c40)"
        text shouldContain "Still loading: after 8 s, requests are still finishing"
    }

    test("the observation script: each op is sent with only its sections, within evaluate_script's 16 KiB") {
        val ops = listOf("look", "point", "target", "focus", "arm", "changes", "signin", "rects", "read", "scroll", "reveal", "settle")
        ops.forEach { op ->
            val call = ObserveScript.call(op, observeArgs(listOf("c1", "c2"), mapOf("scope" to "c9", "skip" to listOf("c40", "c41"))))
            (call.length < 16 * 1024) shouldBe true
            call shouldContain "op === \"$op\""
        }
        ObserveScript.sourceFor("settle") shouldNotContain "op === \"look\""
        ObserveScript.sourceFor("look") shouldNotContain "op === \"settle\""
        ObserveScript.sourceFor("look") shouldNotContain "//#"
        scriptSections(listOf("a", "//#if x y", "b", "//#end", "c", "//#if z", "d", "//#end"), "y") shouldBe listOf("a", "b", "c")
    }

    // ---- the pilot over the fake site -------------------------------------------------------------

    test("a step waits for the page to settle before the change report and Jev's check") {
        val rig = PilotRig()
        rig.site.loadingProbes = 3
        val text = rig.act("the Sign in link", "a sign-in form")
        text shouldContain "looks like \"a sign-in form\" happened"
        text shouldNotContain "Still loading"
        rig.site.settleProbes shouldBeGreaterThan 3
    }

    test("a page that never settles is reported as still loading, never as 'did not happen'; look says so too") {
        val rig = PilotRig()
        rig.site.loadingForever = true
        rig.jev.happened = 0.1
        val text = rig.act("the Sign in link", "a sign-in form")
        text shouldContain "Still loading after"
        text shouldContain "not there yet, but the page is still loading"
        text shouldNotContain "did not happen"
        rig.call("look").text() shouldContain "Still loading: after"
    }

    test("signed in sticks: no second sign_in, the control that started it is refused, going back to it gets a note") {
        val rig = PilotRig()
        rig.signInFully()
        rig.call("look").text() shouldContain "Signed in to example.com (since 12:01)"
        val asked = rig.jev.asked.size

        val back = rig.call("go", buildJsonObject { put("url", "https://shop.example.com/") }).text()
        back shouldContain "You are already signed in to example.com (since 12:01); this page starts the sign-in"
        val again = rig.act("the Sign in link", "a sign-in form")
        again shouldContain "Refused: You are already signed in to example.com"
        again shouldContain "would start the sign-in again"
        rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "so I did not sign in again"
        rig.jev.asked.size shouldBe asked // refused before any model call
        rig.site.clicked.count { it == "Sign in" } shouldBe 1
        rig.site.codesSent shouldBe 1
        rig.audit.rows.filter { it.gate == "signed_in_guard" }.size shouldBe 2
    }

    // Guards against: a run resumed after a restart forgetting it is signed in and which codes it already typed.
    test("a pilot rebuilt for the same run keeps its sign-in, its code count and the codes already typed") {
        val leases = MemoryBrowserLeases()
        val states = MemoryBrowserRunStates()
        val before = PilotRig(leases = leases, runStates = states)
        before.signInFully()
        before.site.codesSent shouldBe 1

        val after = PilotRig(leases = leases, runStates = states)
        after.call("look").text() shouldContain "Signed in to example.com"
        // the sign-in control is refused, as it was before the restart, before any model call
        after.act("the Sign in link", "a sign-in form") shouldContain "Refused: You are already signed in to example.com"
        after.site.open(after.site.verifyAgain())
        after.act("the Verification code field", "the code is typed", value = "[secret:code_h-code]") shouldContain "already typed for an earlier code field"
        after.site.codesSent shouldBe 0
        after.site.clicked.shouldBeEmpty()
    }

    test("a second verification after sign-in gets a fresh code through ask_code; an earlier code is never reused") {
        val rig = PilotRig()
        rig.signInFully()
        rig.governance.freshIds = true
        rig.site.open(rig.site.verifyAgain())
        rig.act("the Verification code field", "the code is typed", value = "[secret:code_h-code]") shouldContain "already typed for an earlier code field"
        rig.site.values["c61"].shouldBeNull()

        rig.grants.put("code", "example.com", mapOf("h-code2" to FakeSite.STEP_UP_CODE), 5.minutes)
        val text = rig.call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text()
        text shouldContain "asks to verify again: asked the person for the new code"
        text shouldContain "the verification is done and you are still signed in"
        text shouldNotContain FakeSite.STEP_UP_CODE
        rig.governance.codeAsks.size shouldBe 2
        rig.governance.codeAsks.last() shouldContain "not the earlier sign-in code"
        rig.site.values["c61"] shouldBe FakeSite.STEP_UP_CODE
        rig.call("look").text() shouldContain "Signed in to example.com (since 12:01); 2 codes sent"
    }

    test("scroll areas: look lists them, a named scroll moves that area, read gets every row once, an off-screen target is scrolled into view") {
        val rig = PilotRig()
        rig.site.open(rig.site.booking())
        val look = rig.call("look").text()
        look shouldContain "[c50] scroll area \"Available times\": showing ~8 of ~10 rows, more below (80 px)"
        look shouldContain "[c52] button \"Times after 6:30 PM\" (off screen)"

        val scrolled = rig.act("scroll the available times down", "more times")
        scrolled shouldContain "Scrolled scroll area \"Available times\" [c50] down"
        scrolled shouldContain "of ~20 rows" // reaching the end loaded more
        rig.act("scroll down", "more") shouldContain "scroll area \"Available times\""

        val read = rig.call("read").text()
        read shouldContain "--- scroll area \"Available times\": 30 rows, read to the end ---"
        val lines = read.lines()
        (0 until 30).map(rig.site::timeRow).forEach { row -> lines.count { it == row } shouldBe 1 }

        val off = rig.act("the Times after 6:30 PM button", "later times")
        off shouldContain "Scrolled \"Times after 6:30 PM\" into view."
        rig.site.calls.filter { it.first == "evaluate_script" }.any { it.second.str("function").contains(")(\"reveal\", ") } shouldBe true
    }

    test("scrolling a page with no scroll area at its end says it did not move") {
        val rig = PilotRig()
        rig.act("scroll down", "more") shouldContain "The page did not move"
        rig.act("scroll the times down", "more") shouldContain "There is no scroll area on this page"
        rig.site.clicked.shouldBeEmpty()
    }
})

/** Signs in through the fake site: the Sign in link, sign_in, SMS, Continue (one code), sign_in with the code. */
suspend fun PilotRig.signInFully() {
    act("the Sign in link", "a sign-in form")
    grantLogin()
    call("sign_in", buildJsonObject { put("credential", "clarity_login") })
    act("the SMS option", "SMS is selected")
    act("the Continue button", "a page asking for a 6-digit code")
    grants.put("code", "example.com", mapOf("h-code" to FakeSite.CODE), 5.minutes)
    clock.advance(1.minutes) // the person reads the code
    call("sign_in", buildJsonObject { put("credential", "clarity_login") }).text() shouldContain "Signed in to example.com"
}
