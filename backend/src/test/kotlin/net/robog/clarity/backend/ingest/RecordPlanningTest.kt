package net.robog.clarity.backend.ingest

import arrow.core.raise.context.either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun

private val t0 = Instant.parse("2026-03-01T10:00:00Z")

private fun upsert(payload: net.robog.clarity.shared.noun.Payload, version: String = "1") =
    IngestRecord.Upsert(SourceId("s1"), version, t0, payload)

private val message = MessagePayload(
    MessagePayload.Channel.EMAIL, Handle.email("Me@x.org"), Handle.email("A@x.org"), listOf(Handle.email("b@x.org")),
    "  Hi  there ", "Hello   world", t0,
)

private fun plan(record: IngestRecord, previous: PriorSighting? = null, granted: Set<Noun> = Noun.entries.toSet()) =
    either<RecordRejection, RecordPlan> { planRecord(record, previous, granted) }

class RecordPlanningTest : FunSpec({
    test("a granted upsert is normalized and keyed") {
        val applied = plan(upsert(message)).getOrNull().shouldBeInstanceOf<RecordPlan.Apply>()
        applied.payload.shouldBeInstanceOf<MessagePayload>().body shouldBe "Hello world"
        applied.payload.shouldBeInstanceOf<MessagePayload>().sender.value shouldBe "a@x.org"
    }

    test("a replay short-circuits before the grant check") {
        val replay = PriorSighting(EntityId("e1"))
        plan(upsert(message), replay, granted = emptySet()).getOrNull() shouldBe RecordPlan.Replay(EntityId("e1"))
    }

    test("an ungranted noun is rejected") {
        plan(upsert(message), granted = setOf(Noun.CONTACT)).leftOrNull() shouldBe RecordRejection.NotGranted(Noun.MESSAGE)
    }

    test("tombstones need the grant too and become removals") {
        val tombstone = IngestRecord.Tombstone(SourceId("s1"), "2", t0, Noun.MESSAGE)
        plan(tombstone).getOrNull() shouldBe RecordPlan.Remove(Noun.MESSAGE)
        plan(tombstone, granted = emptySet()).leftOrNull() shouldBe RecordRejection.NotGranted(Noun.MESSAGE)
    }

    test("structurally invalid payloads are rejected") {
        plan(upsert(message.copy(sender = Handle.email("   ")))).leftOrNull().shouldBeInstanceOf<RecordRejection.InvalidPayload>()
        val badEvent = CalendarEventPayload(null, "c", "t", t0, Instant.parse("2026-03-01T09:00:00Z"))
        plan(upsert(badEvent)).leftOrNull().shouldBeInstanceOf<RecordRejection.InvalidPayload>()
    }

    test("etag matching") {
        etagMatches("\"3\"", "\"3\"") shouldBe true
        etagMatches("W/\"3\", \"4\"", "\"4\"") shouldBe true
        etagMatches("*", "\"3\"") shouldBe true
        etagMatches("\"2\"", "\"3\"") shouldBe false
        etagMatches(null, "\"3\"") shouldBe false
    }
})
