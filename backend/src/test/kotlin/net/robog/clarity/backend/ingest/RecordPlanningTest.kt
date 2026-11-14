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

    test("a row only its source uses follows the source to a new identity; a shared row is left to the others") {
        val linked = kotlin.uuid.Uuid.random()
        val owner = kotlin.uuid.Uuid.random()
        identityFor(linked = null, linkedShared = false, keyOwner = null) shouldBe Identity.Insert
        identityFor(linked = null, linkedShared = false, keyOwner = owner) shouldBe Identity.Existing(owner, orphaned = null)
        // a moved event without a UID: same row, new key, id unchanged
        identityFor(linked = linked, linkedShared = false, keyOwner = null) shouldBe Identity.Rekey(linked)
        identityFor(linked = linked, linkedShared = false, keyOwner = linked) shouldBe Identity.Existing(linked, orphaned = null)
        // the source now matches another row: merge there, and its old row has no source left
        identityFor(linked = linked, linkedShared = false, keyOwner = owner) shouldBe Identity.Existing(owner, orphaned = linked)
        // another source still uses the old row: never move or delete it
        identityFor(linked = linked, linkedShared = true, keyOwner = null) shouldBe Identity.Insert
        identityFor(linked = linked, linkedShared = true, keyOwner = owner) shouldBe Identity.Existing(owner, orphaned = null)
    }

    test("a record differs from what is stored only when some field does") {
        val event = CalendarEventPayload(calendar = "Home", title = "Yoga", start = t0, end = t0)
        contentDiffers(event, event.copy()) shouldBe false
        contentDiffers(event, event.copy(title = "Hot yoga")) shouldBe true
        contentDiffers(event, event.copy(location = "Gym")) shouldBe true
        contentDiffers(event.copy(location = "Gym"), event) shouldBe true
    }
})
