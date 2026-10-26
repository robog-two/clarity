package net.robog.clarity.backend.persistence

import kotlin.time.Instant
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.DocumentPayload
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload

/** A canonical entity as stored, with bookkeeping timestamps. */
data class Stored<out P : Payload>(
    val id: EntityId,
    val payload: P,
    val firstSeenAt: Instant,
    val updatedAt: Instant,
)

data class TimeRange(val from: Instant, val until: Instant)

/**
 * Read-only queries over canonical nouns. This is the *only* way agent tools and MCP read user
 * data, so it doubles as the access-control boundary for what an agent can see.
 */
interface NounReader {
    suspend fun messages(range: TimeRange, participant: String? = null, limit: Int = 50): List<Stored<MessagePayload>>
    suspend fun calendarEvents(range: TimeRange, limit: Int = 100): List<Stored<CalendarEventPayload>>
    suspend fun searchContacts(query: String, limit: Int = 20): List<Stored<ContactPayload>>
    /** Full-text search over document titles, paths and extracted excerpts; best matches first. */
    suspend fun searchDocuments(query: String, limit: Int = 20): List<Stored<DocumentPayload>> = emptyList()
    suspend fun document(id: EntityId): Stored<DocumentPayload>? = null
    /** Entities created or updated after [since], across nouns (for periodic review). */
    suspend fun changedSince(since: Instant, limit: Int = 200): List<Stored<Payload>>
}
