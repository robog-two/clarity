package net.robog.clarity.backend.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

class ProposedActionsTest : FunSpec({
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
            "k", ExternalTools.CreateEventInput("Focus", "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", reason = "protect the morning"),
        ).shouldBeInstanceOf<ProposedAction.CreateEvent>()
        hold.start shouldBe Instant.parse("2026-10-26T09:00:00Z")
        hold.hold shouldBe true
        val invite = ProposedActions.createEvent(
            "k", ExternalTools.CreateEventInput("Sync", "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", attendees = listOf("a@b.co"), reason = "r"),
        ).shouldBeInstanceOf<ProposedAction.CreateEvent>()
        invite.hold shouldBe false
        shouldThrow<IllegalArgumentException> {
            ProposedActions.createEvent("k", ExternalTools.CreateEventInput("x", "2026-10-26T10:00:00Z", "2026-10-26T09:00:00Z", reason = "r"))
        }.message shouldContain "after"
        shouldThrow<IllegalArgumentException> {
            ProposedActions.createEvent("k", ExternalTools.CreateEventInput("x", "tomorrow", "2026-10-26T09:00:00Z", reason = "r"))
        }.message shouldContain "ISO-8601"
    }

    test("move, respond, reminder and do-not-disturb") {
        ProposedActions.moveEvent("k", ExternalTools.MoveEventInput("e1", "2026-10-26T09:00:00Z", "2026-10-26T10:00:00Z", true, "r"))
            .shouldBeInstanceOf<ProposedAction.MoveEvent>().notifyAttendees shouldBe true
        ProposedActions.respond("k", ExternalTools.RespondInput("e1", InviteResponse.DECLINE, "sorry", "r"))
            .shouldBeInstanceOf<ProposedAction.RespondToInvite>().response shouldBe InviteResponse.DECLINE
        ProposedActions.reminder("k", ExternalTools.ReminderInput("call mum", "2026-10-26T18:00:00Z", "r"))
            .shouldBeInstanceOf<ProposedAction.CreateReminder>().text shouldBe "call mum"
        ProposedActions.doNotDisturb("k", ExternalTools.DndInput("2026-10-26T12:00:00Z", listOf("+4912345"), "r"))
            .shouldBeInstanceOf<ProposedAction.SetDoNotDisturb>().allow shouldBe listOf(Handle.phone("+4912345"))
    }
})
