package net.robog.clarity.backend.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

class ProposedActionsTest : FunSpec({
    val utc = TimeZone.UTC
    val newYork = TimeZone.of("America/New_York")

    fun email(to: List<String> = listOf("Dana <dana@x.com>")) =
        ExternalTools.EmailInput(to = to, subject = "Lunch", body = "Thursday?", reason = "she asked")

    test("a draft and a send differ only in kind; both keep the key") {
        val draft = ProposedActions.draft("k1", send = false, input = email())
        draft.shouldBeInstanceOf<ProposedAction.SaveDraft>()
        draft.idempotencyKey shouldBe "k1"
        draft.email.to shouldBe listOf(Handle.email("dana@x.com", "Dana"))
        draft.summary shouldContain "Dana"
        ProposedActions.draft("k2", send = true, input = email()).shouldBeInstanceOf<ProposedAction.SendEmail>()
    }

    test("an email needs a recipient") {
        shouldThrow<IllegalArgumentException> { ProposedActions.draft("k", false, email(to = emptyList())) }
    }

    test("events: times are parsed and ordered, attendees make it an invitation not a hold") {
        val hold = ProposedActions.createEvent(
            "k", ExternalTools.CreateEventInput("Focus", "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", reason = "protect the morning"), utc,
        ).shouldBeInstanceOf<ProposedAction.CreateEvent>()
        hold.start shouldBe Instant.parse("2026-10-26T09:00:00Z")
        hold.hold shouldBe true
        val invite = ProposedActions.createEvent(
            "k", ExternalTools.CreateEventInput("Sync", "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", attendees = listOf("a@b.co"), reason = "r"), utc,
        ).shouldBeInstanceOf<ProposedAction.CreateEvent>()
        invite.hold shouldBe false
        shouldThrow<IllegalArgumentException> {
            ProposedActions.createEvent("k", ExternalTools.CreateEventInput("x", "2026-10-26T10:00:00Z", "2026-10-26T09:00:00Z", reason = "r"), utc)
        }.message shouldContain "after"
        shouldThrow<IllegalArgumentException> {
            ProposedActions.createEvent("k", ExternalTools.CreateEventInput("x", "tomorrow", "2026-10-26T09:00:00Z", reason = "r"), utc)
        }.message shouldContain "not a time"
    }

    test("move, respond, reminder and do-not-disturb") {
        ProposedActions.moveEvent("k", ExternalTools.MoveEventInput("e1", "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", true, "r"), utc)
            .shouldBeInstanceOf<ProposedAction.MoveEvent>().notifyAttendees shouldBe true
        ProposedActions.respond("k", ExternalTools.RespondInput("e1", InviteResponse.DECLINE, "sorry", "r"))
            .shouldBeInstanceOf<ProposedAction.RespondToInvite>().response shouldBe InviteResponse.DECLINE
        ProposedActions.reminder("k", ExternalTools.ReminderInput("call mum", "2026-10-26T18:00:00Z", "r"), utc)
            .shouldBeInstanceOf<ProposedAction.CreateReminder>().text shouldBe "call mum"
        ProposedActions.doNotDisturb("k", ExternalTools.DndInput("2026-10-26T12:00:00Z", listOf("+4912345"), "r"), utc)
            .shouldBeInstanceOf<ProposedAction.SetDoNotDisturb>().allow shouldBe listOf(Handle.phone("+4912345"))
    }

    test("times without an offset are the person's local time, and the summary shows them there") {
        val hold = ProposedActions.createEvent(
            "k", ExternalTools.CreateEventInput("Focus", "2026-10-26T19:00", "2026-10-26T20:30", reason = "r"), newYork,
        ).shouldBeInstanceOf<ProposedAction.CreateEvent>()
        hold.start shouldBe Instant.parse("2026-10-26T23:00:00Z")
        hold.end shouldBe Instant.parse("2026-10-27T00:30:00Z")
        hold.summary shouldBe "Hold \"Focus\" 2026-10-26T19:00-04:00 (Mon) to 20:30"
        // An explicit offset or Z still wins; the summary is still local.
        val utcGiven = ProposedActions.reminder("k", ExternalTools.ReminderInput("call mum", "2026-10-26T23:00:00Z", "r"), newYork)
            .shouldBeInstanceOf<ProposedAction.CreateReminder>()
        utcGiven.at shouldBe Instant.parse("2026-10-26T23:00:00Z")
        utcGiven.summary shouldBe "Remind at 2026-10-26T19:00-04:00 (Mon): call mum"
        ProposedActions.doNotDisturb("k", ExternalTools.DndInput("2026-11-01T01:30", reason = "r"), newYork)
            .shouldBeInstanceOf<ProposedAction.SetDoNotDisturb>().until shouldBe Instant.parse("2026-11-01T05:30:00Z") // the first 01:30
        ProposedActions.moveEvent("k", ExternalTools.MoveEventInput("e1", "2026-10-26T23:00", "2026-10-27T01:00", false, "r"), newYork)
            .summary shouldBe "Move event e1 to 2026-10-26T23:00-04:00 (Mon) to 2026-10-27T01:00-04:00 (Tue)"
    }

    test("a plain date is not enough for an event, and says how to fix it") {
        shouldThrow<IllegalArgumentException> {
            ProposedActions.createEvent("k", ExternalTools.CreateEventInput("x", "2026-10-26", "2026-10-26T09:00", reason = "r"), newYork)
        }.message shouldContain "2026-10-26T09:00"
    }
})
