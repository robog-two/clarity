package net.robog.clarity.backend.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

private fun emailOf(action: ProposedAction): EmailDraft = when (action) {
    is ProposedAction.SendEmail -> action.email
    is ProposedAction.SaveDraft -> action.email
    else -> error("not an email: $action")
}

class ProposedActionsTest : FunSpec({
    val utc = TimeZone.UTC
    val newYork = TimeZone.of("America/New_York")

    val icloud = ConnectorAccount("iCloud", "robogamingbysam@icloud.com")
    val gmail = ConnectorAccount("Gmail", "samuel.elliot.knight@gmail.com")
    val accounts = listOf(icloud, gmail)

    fun email(to: List<String> = listOf("Dana <dana@x.com>"), from: String = gmail.address, inReplyTo: String? = null, newThread: Boolean = inReplyTo == null) =
        ExternalTools.EmailInput(from = from, to = to, subject = "Lunch", body = "Thursday?", inReplyTo = inReplyTo, newThread = newThread, reason = "she asked")

    fun arrivedAt(address: String) = MessagePayload(
        channel = MessagePayload.Channel.EMAIL, account = Handle.email(address), sender = Handle.email("dana@x.com"),
        recipients = listOf(Handle.email(address)), subject = "Lunch?", body = "Free Thursday?", sentAt = Instant.parse("2026-10-26T09:00:00Z"),
    )

    fun draft(input: ExternalTools.EmailInput, replied: MessagePayload? = null, send: Boolean = true) =
        ProposedActions.draft("k", send, input, accounts, replied)

    test("a draft and a send differ only in kind; both keep the key and name the sender") {
        val draft = ProposedActions.draft("k1", send = false, input = email(), accounts = accounts, replied = null)
        draft.shouldBeInstanceOf<ProposedAction.SaveDraft>()
        draft.idempotencyKey shouldBe "k1"
        draft.email.to shouldBe listOf(Handle.email("dana@x.com", "Dana"))
        draft.email.from shouldBe Handle.email(gmail.address)
        draft.email.fromLabel shouldBe "Gmail"
        draft.summary shouldBe "Draft \"Lunch\" to Dana from Gmail"
        draft(email()).shouldBeInstanceOf<ProposedAction.SendEmail>().summary shouldBe "Send \"Lunch\" to Dana from Gmail"
    }

    test("an email needs a recipient") {
        shouldThrow<IllegalArgumentException> { draft(email(to = emptyList())) }
    }

    test("the sender is one of the accounts, by address, \"Name <address>\" or label") {
        draft(email(from = "ROBOGAMINGBYSAM@icloud.com")).let { emailOf(it).from shouldBe Handle.email(icloud.address) }
        draft(email(from = "Sam <samuel.elliot.knight@gmail.com>")).let { emailOf(it).fromLabel shouldBe "Gmail" }
        draft(email(from = "gmail")).let { emailOf(it).from shouldBe Handle.email(gmail.address) }
    }

    test("an unknown sender is an error that lists the accounts") {
        val e = shouldThrow<IllegalArgumentException> { draft(email(from = "sam@work.example")) }
        e.message shouldContain "iCloud <robogamingbysam@icloud.com>"
        e.message shouldContain "Gmail <samuel.elliot.knight@gmail.com>"
        shouldThrow<IllegalArgumentException> { ProposedActions.draft("k", true, email(), emptyList(), null) }.message shouldContain "No mail account"
    }

    test("the thread is explicit: a reply names its message, a new email says so, never both or neither") {
        val fresh = emailOf(draft(email(newThread = true)))
        fresh.inReplyTo shouldBe null
        fresh.replySubject shouldBe null
        shouldThrow<IllegalArgumentException> { draft(email(newThread = false)) }.message shouldContain "inReplyTo"
        shouldThrow<IllegalArgumentException> { draft(email(inReplyTo = "m1", newThread = true), arrivedAt(gmail.address)) }.message shouldContain "not both"
        shouldThrow<IllegalArgumentException> { draft(email(inReplyTo = "m1"), replied = null) }.message shouldContain "No message has the id m1"
    }

    test("a reply goes from the account its message arrived at and carries the thread's subject") {
        val reply = emailOf(draft(email(inReplyTo = "m1"), arrivedAt(gmail.address)))
        reply.inReplyTo shouldBe EntityId("m1")
        reply.replySubject shouldBe "Lunch?"
        val wrong = shouldThrow<IllegalArgumentException> { draft(email(from = "iCloud", inReplyTo = "m1"), arrivedAt(gmail.address)) }
        wrong.message shouldContain "arrived at Gmail <samuel.elliot.knight@gmail.com>"
        wrong.message shouldContain "set `from` to \"samuel.elliot.knight@gmail.com\""
        shouldThrow<IllegalArgumentException> { draft(email(inReplyTo = "m1"), arrivedAt("old@elsewhere.example")) }.message shouldContain "newThread"
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
