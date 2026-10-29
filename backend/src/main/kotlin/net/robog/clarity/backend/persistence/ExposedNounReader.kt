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
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
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

    override suspend fun messages(range: TimeRange, participant: String?, limit: Int, folder: String?): List<Stored<MessagePayload>> =
        database.tx {
            Messages.selectAll()
                .where {
                    var op = (Messages.sentAt greaterEq range.from) and (Messages.sentAt less range.until) and
                        Messages.deletedAt.isNull()
                    if (participant != null) op = op and Messages.participants.contains(listOf(normalizeLooseHandle(participant)))
                    if (folder != null) op = op and ((Messages.channel neq EMAIL) or InEmailFolder(emailFolderPattern(folder)))
                    op
                }
                .orderBy(Messages.sentAt, SortOrder.DESC)
                .limit(limit.coerceIn(1, MAX_LIMIT))
                .map { it.toMessage() }
        }

    override suspend fun messageFolders(ids: List<EntityId>): Map<EntityId, List<String>> =
        ids.mapNotNull { kotlin.uuid.Uuid.parseOrNull(it.value) }.let { uuids ->
            if (uuids.isEmpty()) return emptyMap()
            database.tx {
            SourceLinks.selectAll()
                .where {
                    (SourceLinks.entityId inList uuids) and
                        (SourceLinks.noun eq "message") and SourceLinks.deletedAt.isNull()
                }
                .mapNotNull { row -> emailFolder(row[SourceLinks.sourceId])?.let { EntityId(row[SourceLinks.entityId].toString()) to it } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, folders) -> folders.distinct().sorted() }
            }
        }

    override suspend fun emailFolders(): List<String> = database.tx {
        // One sample source id per folder prefix; emailFolder() turns it into the folder name.
        val samples = mutableListOf<String>()
        exec(
            "SELECT DISTINCT ON (regexp_replace(source_id, '/[0-9]+/[0-9]+$', '')) source_id FROM source_link " +
                "WHERE noun = 'message' AND deleted_at IS NULL AND source_id ~ '/[0-9]+/[0-9]+$'",
        ) { rs -> while (rs.next()) samples += rs.getString(1) }
        samples.mapNotNull(::emailFolder).distinct().sorted()
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

    override suspend fun searchMessages(search: MessageSearch): MessageHits {
        val terms = search.terms.ifEmpty { return MessageHits(emptyList()) }
        val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
        fun text(value: String) = "?".also { args += TextColumnType() to value }
        // Per term: 3 for a name or address, 2 for the subject, 1 for the body; a stopword-only term matches everything.
        val scores = terms.map { t ->
            "CASE WHEN m.people LIKE ${text(t.peoplePattern())} THEN 3 " +
                "WHEN m.search @@ to_tsquery('english', ${text(t.tsQuery('A'))}) THEN 2 " +
                "WHEN m.search @@ to_tsquery('english', ${text(t.tsQuery())}) THEN 1 " +
                "WHEN numnode(to_tsquery('english', ${text(t.tsQuery())})) = 0 THEN 0 ELSE -1 END"
        }
        val anyTerm = terms.joinToString(" OR ") { t ->
            "m.people LIKE ${text(t.peoplePattern())} OR m.search @@ to_tsquery('english', ${text(t.tsQuery())})"
        }
        val filters = buildList {
            add("m.deleted_at IS NULL")
            add("($anyTerm)")
            search.range.from?.let { add("m.sent_at >= ?"); args += Messages.sentAt.columnType to it }
            search.range.until?.let { add("m.sent_at < ?"); args += Messages.sentAt.columnType to it }
            search.channel?.let { add("m.channel = ${text(it.wire())}") }
            search.folder?.let {
                add(
                    "(m.channel <> '$EMAIL' OR EXISTS (SELECT 1 FROM source_link sl WHERE sl.entity_id = m.id " +
                        "AND sl.noun = 'message' AND sl.deleted_at IS NULL AND sl.source_id ~* ${text(emailFolderPattern(it))}))",
                )
            }
        }
        args += IntegerColumnType() to search.limit.coerceIn(1, MAX_LIMIT)
        // Placeholders appear in the order of [args]: the term scores, then the filters, then the limit.
        val perTerm = scores.withIndex().joinToString(", ") { (i, score) -> "$score AS s$i" }
        val matched = scores.indices.joinToString(" + ") { "(CASE WHEN s$it >= 0 THEN 1 ELSE 0 END)" }
        val score = scores.indices.joinToString(" + ") { "GREATEST(s$it, 0)" }
        val sql = "SELECT id, $matched AS matched FROM (" +
            "SELECT m.id, m.sent_at, $perTerm FROM message m WHERE ${filters.joinToString(" AND ")}) per_term " +
            "ORDER BY matched DESC, $score DESC, sent_at DESC LIMIT ?"
        return database.tx {
            val ranked = exec(sql, args) { rs ->
                buildList { while (rs.next()) add(rs.getString(1).let(Uuid::parse) to rs.getInt(2)) }
            }.orEmpty()
            // Ordered by terms matched first: when the best hit has them all, keep only those that do.
            val partial = ranked.isNotEmpty() && ranked.first().second < terms.size
            val ids = if (partial) ranked.map { it.first } else ranked.filter { it.second == terms.size }.map { it.first }
            val byId = Messages.selectAll().where { Messages.id inList ids }.associate { it[Messages.id] to it.toMessage() }
            MessageHits(ids.mapNotNull { byId[it] }, partial)
        }
    }

    override suspend fun message(id: EntityId): Stored<MessagePayload>? {
        val uuid = kotlin.uuid.Uuid.parseOrNull(id.value) ?: return null
        return database.tx {
            Messages.selectAll().where { (Messages.id eq uuid) and Messages.deletedAt.isNull() }.singleOrNull()?.toMessage()
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

private const val EMAIL = "email"

/** `EXISTS` a live source link of this message whose id is in the folder matched by [pattern]. */
private class InEmailFolder(private val pattern: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append("EXISTS (SELECT 1 FROM source_link sl WHERE sl.entity_id = ")
        append(Messages.id)
        append(" AND sl.noun = 'message' AND sl.deleted_at IS NULL AND sl.source_id ~* ")
        registerArgument(TextColumnType(), pattern)
        append(")")
    }
}
