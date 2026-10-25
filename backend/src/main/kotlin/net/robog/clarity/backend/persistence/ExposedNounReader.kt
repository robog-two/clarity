package net.robog.clarity.backend.persistence

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Instant
import kotlin.uuid.toKotlinUuid
import net.robog.clarity.backend.dedup.normalizeLooseHandle
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.inList
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

    override suspend fun searchDocuments(query: String, limit: Int): List<Stored<DocumentPayload>> {
        val tsQuery = documentTsQuery(query) ?: return emptyList()
        return database.tx {
            // `search` is a generated tsvector column that Exposed does not model; match it with plain SQL.
            val ids = exec(
                "SELECT id FROM document WHERE deleted_at IS NULL AND search @@ to_tsquery('simple', ?) " +
                    "ORDER BY ts_rank(search, to_tsquery('simple', ?)) DESC, updated_at DESC LIMIT ?",
                listOf(
                    org.jetbrains.exposed.v1.core.TextColumnType() to tsQuery,
                    org.jetbrains.exposed.v1.core.TextColumnType() to tsQuery,
                    org.jetbrains.exposed.v1.core.IntegerColumnType() to limit.coerceIn(1, MAX_LIMIT),
                ),
            ) { rs ->
                buildList { while (rs.next()) add(java.util.UUID.fromString(rs.getString(1))) }
            }.orEmpty()
            val byId = Documents.selectAll().where { Documents.id inList ids.map { it.toKotlinUuid() } }.associate { it[Documents.id] to it.toDocument() }
            ids.mapNotNull { byId[it.toKotlinUuid()] }
        }
    }

    override suspend fun document(id: EntityId): Stored<DocumentPayload>? {
        val uuid = runCatching { kotlin.uuid.Uuid.parse(id.value) }.getOrNull() ?: return null
        return database.tx {
            Documents.selectAll().where { (Documents.id eq uuid) and Documents.deletedAt.isNull() }.singleOrNull()?.toDocument()
        }
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
            val documents = Documents.selectAll()
                .where { (Documents.updatedAt greater since) and Documents.deletedAt.isNull() }
                .orderBy(Documents.updatedAt, SortOrder.DESC).limit(cap).map { it.toDocument() }
            (messages + contacts + events + documents).sortedByDescending { it.updatedAt }.take(cap)
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

    private fun ResultRow.toDocument() = Stored(
        id = EntityId(this[Documents.id].toString()),
        payload = DocumentPayload(
            store = this[Documents.store],
            path = this[Documents.path],
            title = this[Documents.title],
            mimeType = this[Documents.mimeType],
            size = this[Documents.size],
            modifiedAt = this[Documents.modifiedAt],
            excerpt = this[Documents.excerpt],
        ),
        firstSeenAt = this[Documents.firstSeenAt],
        updatedAt = this[Documents.updatedAt],
    )

    private companion object { const val MAX_LIMIT = 500 }
}

private val TOKEN = Regex("[\\p{L}\\p{N}]+")

/**
 * A `to_tsquery` string from free text: every word must match, the last as a prefix
 * (`quarterly rep` -> `quarterly & rep:*`). Null when the text holds no searchable word.
 */
fun documentTsQuery(raw: String): String? {
    val words = TOKEN.findAll(raw.lowercase()).map { it.value }.take(12).toList()
    if (words.isEmpty()) return null
    return words.mapIndexed { i, w -> if (i == words.lastIndex) "$w:*" else w }.joinToString(" & ")
}
