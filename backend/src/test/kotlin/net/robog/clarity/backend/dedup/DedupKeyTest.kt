package net.robog.clarity.backend.dedup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlin.time.Instant
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

private val t0 = Instant.parse("2026-03-01T10:00:00Z")
private val t1 = Instant.parse("2026-03-01T11:00:00Z")

private fun message(
    body: String = "Lunch tomorrow?",
    recipients: List<Handle> = listOf(Handle.email("bob@example.com"), Handle.email("carol@example.com")),
    sender: Handle = Handle.email("alice@example.com"),
    channel: MessagePayload.Channel = MessagePayload.Channel.EMAIL,
    sentAt: Instant = t0,
) = MessagePayload(channel, Handle.email("me@example.com"), sender, recipients, "subject", body, sentAt)

private val arbMessage: Arb<MessagePayload> = arbitrary {
    MessagePayload(
        channel = Arb.enum<MessagePayload.Channel>().bind(),
        account = arbHandle.bind(),
        sender = arbHandle.bind(),
        recipients = Arb.list(arbHandle, 0..5).bind(),
        subject = Arb.string(0..10).bind(),
        body = Arb.string(0..40).bind(),
        sentAt = t0,
    )
}

class DedupKeyTest : FunSpec({
    test("the same message reported with different spelling gets the same key") {
        val a = message(body = "Lunch tomorrow?", sender = Handle.email("alice@example.com"))
        val b = message(body = "  Lunch\n tomorrow?  ", sender = Handle.email(" ALICE@Example.com ", "Alice"))
        a.dedupKey() shouldBe b.dedupKey()
    }

    test("recipient order and duplicates do not matter") {
        val a = message(recipients = listOf(Handle.email("bob@example.com"), Handle.email("carol@example.com")))
        val b = message(recipients = listOf(Handle.email("CAROL@example.com"), Handle.email("bob@example.com"), Handle.email("bob@example.com")))
        a.dedupKey() shouldBe b.dedupKey()
    }

    test("different body, sender, channel or recipients change the key") {
        val base = message().dedupKey()
        message(body = "Dinner tomorrow?").dedupKey() shouldNotBe base
        message(sender = Handle.email("dave@example.com")).dedupKey() shouldNotBe base
        message(channel = MessagePayload.Channel.CHAT).dedupKey() shouldNotBe base
        message(recipients = listOf(Handle.email("bob@example.com"))).dedupKey() shouldNotBe base
    }

    test("field boundaries cannot be forged by shifting characters") {
        message(sender = Handle.email("ab@x.org"), recipients = listOf(Handle.email("c@x.org"))).dedupKey() shouldNotBe
            message(sender = Handle.email("a@x.org"), recipients = listOf(Handle.email("bc@x.org"))).dedupKey()
    }

    test("message key is order independent and idempotent under normalization (property)") {
        checkAll(arbMessage) { m ->
            m.dedupKey() shouldBe m.normalized().dedupKey()
            m.copy(recipients = m.recipients.reversed()).dedupKey() shouldBe m.dedupKey()
        }
    }

    test("contacts are identified by their sorted normalized handles") {
        val a = ContactPayload("Alice", listOf(Handle.email("A@x.org"), Handle.phone("+1 555 0100")))
        val b = ContactPayload("Alice Smith", listOf(Handle.phone("+15550100"), Handle.email("a@x.org")))
        a.dedupKey() shouldBe b.dedupKey()
        ContactPayload("Alice", listOf(Handle.email("other@x.org"))).dedupKey() shouldNotBe a.dedupKey()
    }

    test("contacts without handles fall back to the name") {
        ContactPayload("Alice", emptyList()).dedupKey() shouldBe ContactPayload(" alice ", emptyList()).dedupKey()
        ContactPayload("Alice", emptyList()).dedupKey() shouldNotBe ContactPayload("Bob", emptyList()).dedupKey()
    }

    test("events with an iCal uid ignore every other field") {
        val a = CalendarEventPayload("uid-1", "work", "Standup", t0, t1)
        val b = CalendarEventPayload("uid-1", "personal", "Stand-up (moved)", t0, t1.plus(kotlin.time.Duration.parse("1h")))
        a.dedupKey() shouldBe b.dedupKey()
        a.dedupKey() shouldNotBe CalendarEventPayload("uid-2", "work", "Standup", t0, t1).dedupKey()
    }

    test("events without a uid use calendar, title and times") {
        val a = CalendarEventPayload(null, "work", "Standup", t0, t1)
        a.dedupKey() shouldBe CalendarEventPayload(null, "work", "  Standup ", t0, t1).dedupKey()
        a.dedupKey() shouldNotBe CalendarEventPayload(null, "work", "Standup", t0, t0).dedupKey()
        a.dedupKey() shouldNotBe CalendarEventPayload("uid", "work", "Standup", t0, t1).dedupKey()
    }

    test("keys of different nouns never collide") {
        val keys = listOf(message().dedupKey(), ContactPayload("x", emptyList()).dedupKey(), CalendarEventPayload(null, "x", "x", t0, t1).dedupKey())
        keys.toSet().size shouldBe 3
    }
})
