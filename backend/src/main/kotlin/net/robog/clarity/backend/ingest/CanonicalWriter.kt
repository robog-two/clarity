package net.robog.clarity.backend.ingest

import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.dedup.DedupKey
import net.robog.clarity.backend.persistence.CalendarEvents
import net.robog.clarity.backend.persistence.CanonicalTable
import net.robog.clarity.backend.persistence.Contacts
import net.robog.clarity.backend.persistence.Documents
import net.robog.clarity.backend.persistence.Messages
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun
import net.robog.clarity.shared.noun.Payload
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

/** Result of writing a canonical entity: its id and whether this call created it. */
data class Upserted(val id: Uuid, val created: Boolean)

val Noun.table: CanonicalTable
    get() = when (this) {
        Noun.MESSAGE -> Messages
        Noun.CONTACT -> Contacts
        Noun.CALENDAR_EVENT -> CalendarEvents
        Noun.DOCUMENT -> Documents
    }

/**
 * Inserts the canonical row for [payload] or merges into the one that already owns [key].
 *
 * `INSERT ... ON CONFLICT DO NOTHING` followed by a read of the winning id is race-free (a
 * concurrent insert of the same key either wins, and we merge, or loses to ours) and tells us
 * created-vs-merged by comparing the id we proposed with the id that is stored.
 */
fun JdbcTransaction.upsertCanonical(payload: Payload, key: DedupKey, now: Instant): Upserted = when (payload) {
    is MessagePayload -> upsert(Messages, key, now) {
        it[Messages.channel] = payload.channel.wire()
        it[Messages.account] = payload.account
        it[Messages.sender] = payload.sender
        it[Messages.recipients] = payload.recipients
        it[Messages.participants] = (listOf(payload.sender) + payload.recipients).map { h -> h.value }.distinct().sorted()
        it[Messages.subject] = payload.subject
        it[Messages.body] = payload.body
        it[Messages.sentAt] = payload.sentAt
        it[Messages.threadRef] = payload.threadRef
    }
    is ContactPayload -> upsert(Contacts, key, now) {
        it[Contacts.displayName] = payload.displayName
        it[Contacts.handles] = payload.handles
        it[Contacts.organization] = payload.organization
        it[Contacts.notes] = payload.notes
        it[Contacts.searchText] = (listOf(payload.displayName, payload.organization.orEmpty()) + payload.handles.map { h -> h.value })
            .joinToString(" ").lowercase()
    }
    is CalendarEventPayload -> upsert(CalendarEvents, key, now) {
        it[CalendarEvents.icalUid] = payload.icalUid
        it[CalendarEvents.calendar] = payload.calendar
        it[CalendarEvents.title] = payload.title
        it[CalendarEvents.startAt] = payload.start
        it[CalendarEvents.endAt] = payload.end
        it[CalendarEvents.allDay] = payload.allDay
        it[CalendarEvents.location] = payload.location
        it[CalendarEvents.description] = payload.description
        it[CalendarEvents.attendees] = payload.attendees
    }
    is DocumentPayload -> upsert(Documents, key, now) {
        it[Documents.store] = payload.store
        it[Documents.path] = payload.path
        it[Documents.title] = payload.title
        it[Documents.mimeType] = payload.mimeType
        it[Documents.size] = payload.size
        it[Documents.modifiedAt] = payload.modifiedAt
        it[Documents.excerpt] = payload.excerpt
    }
}

private fun <T : CanonicalTable> JdbcTransaction.upsert(
    table: T,
    key: DedupKey,
    now: Instant,
    columns: (UpdateBuilder<*>) -> Unit,
): Upserted {
    val proposed = Uuid.random()
    table.insertIgnore {
        it[table.id] = proposed
        it[table.dedupKey] = key.bytes()
        it[table.firstSeenAt] = now
        it[table.updatedAt] = now
        columns(it)
    }
    val stored = table.select(table.id).where { table.dedupKey eq key.bytes() }.single()[table.id]
    if (stored == proposed) return Upserted(stored, created = true)
    table.update({ table.id eq stored }) {
        columns(it)
        it[table.updatedAt] = now
        it[table.deletedAt] = null // a re-observed record is alive again
    }
    return Upserted(stored, created = false)
}

/** Soft-deletes entity [id] of [noun]. */
fun JdbcTransaction.softDelete(noun: Noun, id: Uuid, now: Instant) {
    val table = noun.table
    table.update({ table.id eq id }) { it[table.deletedAt] = now }
}
