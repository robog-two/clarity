package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.BrowserPilotConfig
import net.robog.clarity.harness.Tool
import net.robog.clarity.shared.governance.HeldAction

/** The browser lease: one run at a time, FIFO, fresh start per lease, released when idle, gone or done. */
class BrowserLeaseTest : FunSpec({
    suspend fun Map<String, Tool>.call(name: String, input: JsonObject = JsonObject(emptyMap())) = getValue("browser__$name").invoke(input).text()
    suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(5_000) { while (!check()) delay(5) }

    test("a second run waits for the browser (an audit step says so), then starts with one empty tab and the same cookies") {
        val rig = PilotRig()
        rig.site.open(rig.site.account())
        rig.site.signedIn = true // the site's cookie: kept across leases
        rig.call("look").text() shouldContain "Your account"
        rig.site.tabs += rig.site.shop()

        val b = rig.run("run-b")
        val waiting = async { b.call("look") }
        until("b queued") { "run-b" in rig.leases.queue }
        rig.audit.rows.single { it.runId == "run-b" }.let { it.tool shouldBe "lease_wait"; it.gate shouldBe "waiting"; it.changeReport!! shouldContain "another task is using it" }
        rig.call("look").text() shouldContain "Your account" // the holder carries on meanwhile

        rig.pilot.runEnded("run-p")
        val seen = waiting.await()
        seen shouldContain "Tabs: [0]* New Tab – about:blank"
        seen shouldNotContain "Your account"
        rig.site.tabs.size shouldBe 1
        rig.site.signedIn shouldBe true
        rig.leases.holder() shouldBe "run-b"
        rig.audit.rows.filter { it.runId == "run-b" }.map { it.gate } shouldBe listOf("waiting", "granted")
    }

    test("FIFO: runs get the browser in the order they asked") {
        val rig = PilotRig()
        val b = rig.run("run-b")
        val c = rig.run("run-c")
        val gotB = async { b.call("tabs") }
        until("b queued") { "run-b" in rig.leases.queue }
        rig.clock.advance(1.minutes.div(60))
        val gotC = async { c.call("tabs") }
        until("c queued") { "run-c" in rig.leases.queue }

        rig.pilot.runEnded("run-p")
        gotB.await()
        rig.leases.holder() shouldBe "run-b"
        delay(50)
        gotC.isCompleted shouldBe false
        rig.pilot.runEnded("run-b")
        gotC.await()
        rig.leases.holder() shouldBe "run-c"
    }

    test("a crashed holder (stale heartbeat) loses the browser to the next run; startup clears leases of runs not running") {
        val rig = PilotRig()
        rig.leases.age(rig.clock.now() - 3.minutes) // no heartbeat for 3 minutes: the process holding it died
        rig.run("run-b").call("tabs")
        rig.leases.holder() shouldBe "run-b"

        rig.leases.clearUnless { it != "run-b" } shouldBe 1
        rig.leases.holder().shouldBeNull()
    }

    test("10 minutes without browser activity release the lease; the run re-queues and is told it starts fresh") {
        val rig = PilotRig()
        rig.site.open(rig.site.code(false))
        rig.call("look")
        rig.clock.advance(11.minutes)
        rig.pilot.maintainLease()
        rig.leases.holder().shouldBeNull()
        rig.leases.resumes.single().url shouldBe "https://login.example.com/code"

        val again = rig.call("look").text()
        again shouldContain "The browser lease lapsed"
        again shouldContain "You start fresh"
        again shouldContain "about:blank"
        rig.leases.holder() shouldBe "run-p"
        rig.call("look").text() shouldNotContain "lapsed" // said once
    }

    test("waiting for the person past the idle time loses the lease: the approved step is not taken on a reset browser") {
        val rig = PilotRig()
        rig.triage = TriageVerdict(false, "may submit")
        rig.governance.decision = HeldAction.Status.APPROVED
        rig.governance.onDecision = {
            rig.clock.advance(11.minutes)
            kotlinx.coroutines.runBlocking { rig.pilot.maintainLease() }
        }
        val text = rig.act("the Details button", "details")
        text shouldContain "The browser lease lapsed"
        text shouldContain "This step was not taken"
        rig.site.clicked.shouldBeEmpty()
    }

    test("the next run of the same task sees where the last one was, once, in its first look; another task does not") {
        val rig = PilotRig()
        rig.site.open(rig.site.method(false))
        rig.act("the SMS option", "SMS is selected")
        rig.act("the Continue button", "a code page")
        rig.pilot.runEnded("run-p")
        rig.leases.resumes.single().where shouldBe "on the code page of example.com (a code had been sent)"

        val again = rig.run("run-b")
        val first = again.call("look")
        first shouldContain "Last time this task was on https://login.example.com/code (\"Clarity ID\"), on the code page of example.com (a code had been sent). It is not reopened"
        again.call("look") shouldNotContain "Last time"
        rig.site.page.url shouldBe "about:blank" // not reopened
        rig.pilot.runEnded("run-b")

        rig.run("run-c", purpose = "Something else entirely").call("look") shouldNotContain "Last time"
    }

    test("a run that waits too long for the browser is told to report back, and leaves the queue") {
        val rig = PilotRig(settings = BrowserPilotConfig(leaseWait = 100.milliseconds))
        val text = rig.run("run-b").call("look")
        text shouldContain "in use by another task"
        rig.leases.queue.keys shouldBe emptySet()
        rig.audit.rows.filter { it.runId == "run-b" }.map { it.gate } shouldBe listOf("waiting", "timed_out")
    }

    test("a lead-scoped call to the pilot is refused, and nothing reaches the browser or the lease") {
        val rig = PilotRig()
        val lead = rig.run("lead-run", lead = true)
        for (tool in listOf("look", "tabs", "read", "screenshot", "downloads")) lead.call(tool) shouldBe "ERROR: ${BrowserGate.LEAD_REFUSED}"
        lead.call("act", buildJsonObject { put("intent", "the Sign in link"); put("expect", "x") }) shouldBe "ERROR: ${BrowserGate.LEAD_REFUSED}"
        lead.call("go", buildJsonObject { put("url", "https://shop.example.com/") }) shouldBe "ERROR: ${BrowserGate.LEAD_REFUSED}"
        rig.site.calls.shouldBeEmpty()
        rig.leases.queue.keys shouldBe emptySet()
        rig.leases.holder() shouldBe "run-p"
    }

    test("whereLine names the sign-in step for the resume note") {
        BrowserPilot.whereLine(SignInPhase.CHOOSE_METHOD, "example.com") shouldBe "choosing where example.com sends its sign-in code"
        BrowserPilot.whereLine(SignInPhase.UNKNOWN, "example.com").shouldBeNull()
        taskKey("  Check my   orders ").shouldNotBeNull() shouldBe taskKey("check my orders")
        leaseExpired(rig0(), rig0() - 11.minutes, rig0(), 10.minutes, 2.minutes) shouldBe true
        leaseExpired(rig0(), rig0() - 1.minutes, rig0() - 3.minutes, 10.minutes, 2.minutes) shouldBe true
        leaseExpired(rig0(), rig0() - 1.minutes, rig0() - 1.minutes, 10.minutes, 2.minutes) shouldBe false
    }
})

private fun rig0() = kotlin.time.Instant.parse("2026-10-27T12:00:00Z")
