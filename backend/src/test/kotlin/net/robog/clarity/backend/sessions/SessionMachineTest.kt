package net.robog.clarity.backend.sessions

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

private val t0 = Instant.parse("2026-10-26T08:00:00Z")
private val id = SessionId("s1")

private fun call() = startRecord(id, SessionKind.MORNING_CALL, t0)
private fun interview() = startRecord(id, SessionKind.VALUES_INTERVIEW, t0)
private fun amend() = startRecord(id, SessionKind.AMEND, t0, HeldActionId("h1"))

private fun line(n: Int) = Line(LineId("q$n"), Section.VALUES, "line $n", "because $n")

private fun Either<SessionError, SessionRecord>.ok(): SessionRecord = (this as Either.Right).value
private fun Either<SessionError, SessionRecord>.err(): SessionError = (this as Either.Left).value

private fun SessionRecord.next(now: Instant = t0) = control(SessionControl.Next, now)

/** A call opened (stage_done) and ready for the person to move on. */
private fun SessionRecord.ready() = stageDone().ok()

class SessionMachineTest : FunSpec({

    test("a new call starts busy at CLEAR, an interview at question 0, an amend session active") {
        call().session.let { it.state shouldBe Session.State.BUSY; it.stage shouldBe CallStage.CLEAR }
        interview().session.let { it.state shouldBe Session.State.BUSY; it.question shouldBe 0; it.questionCount shouldBe 7 }
        amend().session.let { it.state shouldBe Session.State.ACTIVE; it.heldActionId shouldBe HeldActionId("h1") }
    }

    test("morning call: CLEAR, CHOOSE, COMMIT, HAND_OFF, ENDED, each step busy until the agent is done") {
        var r = call()
        val stages = listOf(CallStage.CHOOSE, CallStage.COMMIT, CallStage.HAND_OFF)
        stages.forEach { expected ->
            r.next().err() shouldBe SessionError.Busy // still producing the step
            r = r.ready().next().ok()
            r.session.stage shouldBe expected
            r.state shouldBe Session.State.BUSY
        }
        val ended = r.ready().next(t0 + 20.minutes).ok()
        ended.state shouldBe Session.State.ENDED
        ended.session.endedAt shouldBe t0 + 20.minutes
        ended.next().err().shouldBeInstanceOf<SessionError.NotActive>()
    }

    test("next while busy is rejected with 409, nothing changes") {
        val busy = call()
        val error = busy.next().err()
        error shouldBe SessionError.Busy
        error.status.value shouldBe 409
    }

    test("stage_done only releases a busy step") {
        val busy = call()
        busy.stageDone().ok().state shouldBe Session.State.ACTIVE
        busy.ready().stageDone().err().shouldBeInstanceOf<SessionError.Illegal>()
        busy.closed(t0).stageDone().err().shouldBeInstanceOf<SessionError.NotActive>()
    }

    test("snooze records the next call time and rings again later; only at the start of a call") {
        val snoozed = call().control(SessionControl.Snooze(15), t0).ok()
        snoozed.state shouldBe Session.State.SNOOZED
        snoozed.snoozedUntil shouldBe t0 + 15.minutes
        snoozed.recallDue(t0 + 14.minutes) shouldBe false
        snoozed.recallDue(t0 + 15.minutes) shouldBe true
        snoozed.next().err().shouldBeInstanceOf<SessionError.NotActive>()

        val back = snoozed.recalled()
        back.state shouldBe Session.State.BUSY
        back.session.stage shouldBe CallStage.CLEAR
        back.snoozedUntil shouldBe null

        call().ready().next().ok().control(SessionControl.Snooze(5), t0).err().shouldBeInstanceOf<SessionError.Illegal>()
        call().control(SessionControl.Snooze(0), t0).err().shouldBeInstanceOf<SessionError.Invalid>()
        call().control(SessionControl.Snooze(241), t0).err().shouldBeInstanceOf<SessionError.Invalid>()
        interview().control(SessionControl.Snooze(5), t0).err().shouldBeInstanceOf<SessionError.Illegal>()
    }

    test("end works from any live state, once") {
        listOf(call(), call().ready(), call().control(SessionControl.Snooze(5), t0).ok(), interview(), amend()).forEach { r ->
            val ended = r.control(SessionControl.End, t0 + 1.minutes).ok()
            ended.state shouldBe Session.State.ENDED
            ended.session.endedAt shouldBe t0 + 1.minutes
            ended.control(SessionControl.End, t0).err().shouldBeInstanceOf<SessionError.NotActive>()
        }
    }

    test("values interview: each question needs a captured line before next; the line is saved and the next question opens busy") {
        var r = interview()
        r.next().err() shouldBe SessionError.Busy
        r = r.lineCaptured(line(1), emptyList()).ok()
        r.state shouldBe Session.State.ACTIVE
        r.session.capturedLine shouldBe line(1)

        r = r.next().ok()
        r.session.question shouldBe 1
        r.state shouldBe Session.State.BUSY
        r.session.capturedLine shouldBe null
        r.data.lines shouldBe listOf(line(1))

        // a question that was released without a line cannot be kept
        r.stageDone().ok().next().err().shouldBeInstanceOf<SessionError.Illegal>()
    }

    test("values interview: reword goes busy until a new line arrives, and replaces the line") {
        var r = interview().lineCaptured(line(1), emptyList()).ok()
        r = r.control(SessionControl.Reword, t0).ok()
        r.state shouldBe Session.State.BUSY
        r.control(SessionControl.Reword, t0).err() shouldBe SessionError.Busy
        r.next().err() shouldBe SessionError.Busy

        r = r.lineCaptured(line(1).copy(text = "other phrasing"), emptyList()).ok()
        r.state shouldBe Session.State.ACTIVE
        r.session.capturedLine?.text shouldBe "other phrasing"
        r.next().ok().data.lines.single().text shouldBe "other phrasing"

        call().ready().control(SessionControl.Reword, t0).err().shouldBeInstanceOf<SessionError.Illegal>()
        interview().stageDone().ok().control(SessionControl.Reword, t0).err().shouldBeInstanceOf<SessionError.Illegal>()
    }

    test("values interview: seven kept lines lead to finalizing, then ended once the constitution is written") {
        var r = interview()
        repeat(7) { i ->
            r.session.question shouldBe i
            r = r.lineCaptured(line(i + 1), emptyList()).ok().next().ok()
        }
        r.finalizing shouldBe true
        r.state shouldBe Session.State.BUSY
        r.data.lines.size shouldBe 7
        r.lineCaptured(line(8), emptyList()).err().shouldBeInstanceOf<SessionError.Illegal>()

        interview().interviewFinished(t0).err().shouldBeInstanceOf<SessionError.Illegal>() // not all answered
        val done = r.interviewFinished(t0 + 9.minutes).ok()
        done.state shouldBe Session.State.ENDED
        done.session.endedAt shouldBe t0 + 9.minutes
    }

    test("the autonomy tiers belong to the third question and are kept with it") {
        var r = interview()
        repeat(2) { i -> r = r.lineCaptured(line(i + 1), emptyList()).ok().next().ok() }
        r.session.question shouldBe 2
        val tiers = listOf(TierNote(net.robog.clarity.shared.governance.AutonomyTier.NEVER_ALONE, "payments"))
        r = r.lineCaptured(line(3), tiers).ok()
        r.data.tiers shouldBe tiers
        r.next().ok().data.tiers shouldBe tiers
    }

    test("amend: no steps, ends on end or when the amendment is decided") {
        val a = amend()
        a.next().err().shouldBeInstanceOf<SessionError.Illegal>()
        a.control(SessionControl.Snooze(5), t0).err().shouldBeInstanceOf<SessionError.Illegal>()
        val proposed = a.amendmentProposed(AmendmentId("am1")).ok()
        proposed.session.amendmentId shouldBe AmendmentId("am1")
        proposed.state shouldBe Session.State.ACTIVE
        proposed.closed(t0 + 1.minutes).state shouldBe Session.State.ENDED
        call().amendmentProposed(AmendmentId("x")).err().shouldBeInstanceOf<SessionError.Illegal>()
    }

    test("every transition bumps the version, so playbook inbox keys never repeat") {
        val r0 = call()
        val r1 = r0.stageDone().ok()
        val r2 = r1.next().ok()
        listOf(r0.version, r1.version, r2.version) shouldBe listOf(0, 1, 2)
    }
})
