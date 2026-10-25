package net.robog.clarity.backend.triggers

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.query.RecordQuery

private val message = MessagePayload(
    channel = MessagePayload.Channel.EMAIL,
    account = Handle.email("me@example.com"),
    sender = Handle.email("boss@example.com", "The Boss"),
    recipients = listOf(Handle.email("me@example.com"), Handle.email("team@example.com")),
    subject = "Quarterly Invoice",
    body = "Please pay by Friday",
    sentAt = Instant.parse("2026-03-01T10:00:00Z"),
)

private val event = CalendarEventPayload(
    icalUid = null, calendar = "work", title = "Standup",
    start = Instant.parse("2026-03-01T10:00:00Z"), end = Instant.parse("2026-03-01T10:15:00Z"),
    attendees = listOf(Handle.email("a@x.org"), Handle.email("b@x.org")),
)

class RecordQueryEvaluatorTest : FunSpec({
    test("equals on a nested path, case-insensitive by default") {
        RecordQuery.Equals("sender.value", "BOSS@example.com").matches(message) shouldBe true
        RecordQuery.Equals("sender.value", "BOSS@example.com", ignoreCase = false).matches(message) shouldBe false
    }

    test("contains") {
        RecordQuery.Contains("subject", "invoice").matches(message) shouldBe true
        RecordQuery.Contains("subject", "receipt").matches(message) shouldBe false
    }

    test("matches uses find semantics and tolerates invalid regexes") {
        RecordQuery.Matches("body", "pay by (Mon|Fri)day").matches(message) shouldBe true
        RecordQuery.Matches("body", "^pay").matches(message) shouldBe false
        RecordQuery.Matches("body", "(unclosed").matches(message) shouldBe false
    }

    test("a path through a list matches if any element matches") {
        RecordQuery.Equals("recipients.value", "team@example.com").matches(message) shouldBe true
        RecordQuery.Equals("attendees.value", "b@x.org").matches(event) shouldBe true
        RecordQuery.Equals("attendees.value", "c@x.org").matches(event) shouldBe false
    }

    test("missing and null fields never match; Not inverts") {
        RecordQuery.Equals("threadRef", "x").matches(message) shouldBe false
        RecordQuery.Contains("no.such.path", "x").matches(message) shouldBe false
        RecordQuery.Not(RecordQuery.Equals("no.such.path", "x")).matches(message) shouldBe true
    }

    test("object-valued paths do not match as scalars") {
        RecordQuery.Contains("sender", "boss").matches(message) shouldBe false
    }

    test("combinators") {
        val fromBoss = RecordQuery.Equals("sender.value", "boss@example.com")
        val mentionsInvoice = RecordQuery.Contains("subject", "invoice")
        val mentionsLunch = RecordQuery.Contains("body", "lunch")
        RecordQuery.All(listOf(fromBoss, mentionsInvoice)).matches(message) shouldBe true
        RecordQuery.All(listOf(fromBoss, mentionsLunch)).matches(message) shouldBe false
        RecordQuery.AnyOf(listOf(mentionsLunch, mentionsInvoice)).matches(message) shouldBe true
        RecordQuery.AnyOf(emptyList()).matches(message) shouldBe false
        RecordQuery.All(emptyList()).matches(message) shouldBe true
    }

    test("non-string scalars compare by their JSON text") {
        RecordQuery.Equals("allDay", "false").matches(event) shouldBe true
    }
})
