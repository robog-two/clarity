package net.robog.clarity.backend.persistence

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Instant
import net.robog.clarity.backend.dedup.normalizeLooseHandle
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.json.contains

/** [NounReader] over the canonical Postgres tables. Soft-deleted rows are never returned. */
@Inject
@ContributesBinding(AppScope::class)
class ExposedNounReader(private val database: Database) : NounReader {

    override suspend fun messages(range: TimeRange, participant: String?, limit: Int): List<Stored<MessagePayload>> =
        database.tx {
            Messages.selectAll()
                .where {
                    val inRange = (Messages.sentAt greaterEq range.from) and (Messages.sentAt less range.until) and
                        Messages.deletedAt.isNull()
                    if (participant == null) inRange
                    else inRange and Messages.participants.contains(listOf(normalizeLooseHandle(participant)))
                }
                .orderBy(Messages.sentAt, SortOrder.DESC)
                .limit(limit.coerceIn(1, MAX_LIMIT))
                .map { it.toMessage() }
        }

    override suspend fun calendarEvents(range: TimeRange, limit: Int): List<Stored<CalendarEventPayload>> =
        database.tx {
            // overlap test: starts before the range ends and ends after it begins
            CalendarEvents.selectAll()
                .where {
                    (CalendarEvents.startAt less range.until) and (CalendarEvents.endAt greaterEq range.from) and
                        CalendarEvents.deletedAt.isNull()
                }
                .orderBy(CalendarEvents.startAt, SortOrder.ASC)
                .limit(limit.coerceIn(1, MAX_LIMIT))
                .map { it.toEvent() }
        }

    override suspend fun searchContacts(query: String, limit: Int): List<Stored<ContactPayload>> =
        database.tx {
            val pattern = "%" + query.trim().lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            Contacts.selectAll()
                .where { Contacts.searchText.like(pattern) and Contacts.deletedAt.isNull() }
                .orderBy(Contacts.displayName, SortOrder.ASC)
                .limit(limit.coerceIn(1, MAX_LIMIT))
                .map { it.toContact() }
        }

    override suspend fun changedSince(since: Instant, limit: Int): List<Stored<Payload>> =
        database.tx {
            val cap = limit.coerceIn(1, MAX_LIMIT)
            val messages = Messages.selectAll()
                .where { (Messages.updatedAt greater since) and Messages.deletedAt.isNull() }
                .orderBy(Messages.updatedAt, SortOrder.DESC).limit(cap).map { it.toMessage() }
            val contacts = Contacts.selectAll()
                .where { (Contacts.updatedAt greater since) and Contacts.deletedAt.isNull() }
                .orderBy(Contacts.updatedAt, SortOrder.DESC).limit(cap).map { it.toContact() }
            val events = CalendarEvents.selectAll()
                .where { (CalendarEvents.updatedAt greater since) and CalendarEvents.deletedAt.isNull() }
                .orderBy(CalendarEvents.updatedAt, SortOrder.DESC).limit(cap).map { it.toEvent() }
            (messages + contacts + events).sortedByDescending { it.updatedAt }.take(cap)
        }

    private fun ResultRow.toMessage() = Stored(
        id = EntityId(this[Messages.id].toString()),
        payload = MessagePayload(
            channel = parseWire<MessagePayload.Channel>(this[Messages.channel]) ?: MessagePayload.Channel.CHAT,
            account = this[Messages.account],
            sender = this[Messages.sender],
            recipients = this[Messages.recipients],
            subject = this[Messages.subject],
            body = this[Messages.body],
            sentAt = this[Messages.sentAt],
            threadRef = this[Messages.threadRef],
        ),
        firstSeenAt = this[Messages.firstSeenAt],
        updatedAt = this[Messages.updatedAt],
    )

    private fun ResultRow.toContact() = Stored(
        id = EntityId(this[Contacts.id].toString()),
        payload = ContactPayload(
            displayName = this[Contacts.displayName],
            handles = this[Contacts.handles],
            organization = this[Contacts.organization],
            notes = this[Contacts.notes],
        ),
        firstSeenAt = this[Contacts.firstSeenAt],
        updatedAt = this[Contacts.updatedAt],
    )

    private fun ResultRow.toEvent() = Stored(
        id = EntityId(this[CalendarEvents.id].toString()),
        payload = CalendarEventPayload(
            icalUid = this[CalendarEvents.icalUid],
            calendar = this[CalendarEvents.calendar],
            title = this[CalendarEvents.title],
            start = this[CalendarEvents.startAt],
            end = this[CalendarEvents.endAt],
            allDay = this[CalendarEvents.allDay],
            location = this[CalendarEvents.location],
            description = this[CalendarEvents.description],
            attendees = this[CalendarEvents.attendees],
        ),
        firstSeenAt = this[CalendarEvents.firstSeenAt],
        updatedAt = this[CalendarEvents.updatedAt],
    )

    private companion object { const val MAX_LIMIT = 500 }
}
