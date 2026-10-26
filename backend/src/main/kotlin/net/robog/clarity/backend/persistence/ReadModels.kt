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
    /**
     * Messages in [range], newest first. [folder] narrows email to one folder or label (see
     * [emailFolderPattern]); null means every folder. SMS and chat have no folders and are always included.
     */
    suspend fun messages(range: TimeRange, participant: String? = null, limit: Int = 50, folder: String? = null): List<Stored<MessagePayload>>
    /** The email folders each message is in (from its live source links); messages without any are left out. */
    suspend fun messageFolders(ids: List<EntityId>): Map<EntityId, List<String>> = emptyMap()
    suspend fun calendarEvents(range: TimeRange, limit: Int = 100): List<Stored<CalendarEventPayload>>
    suspend fun searchContacts(query: String, limit: Int = 20): List<Stored<ContactPayload>>
    /** Full-text search over document titles, paths and extracted excerpts; best matches first. */
    suspend fun searchDocuments(query: String, limit: Int = 20): List<Stored<DocumentPayload>> = emptyList()
    suspend fun document(id: EntityId): Stored<DocumentPayload>? = null
    /** Entities created or updated after [since], across nouns (for periodic review). */
    suspend fun changedSince(since: Instant, limit: Int = 200): List<Stored<Payload>>
}

/**
 * Pure: the folder of an email source id (`<account>/<folder>/<uidValidity>/<uid>`, or the legacy
 * `<folder>/<uidValidity>/<uid>`); null for other ids. Folder names may contain slashes.
 */
fun emailFolder(sourceId: String): String? {
    val parts = sourceId.split('/')
    if (parts.size < 3 || parts.last().toLongOrNull() == null || parts[parts.size - 2].toLongOrNull() == null) return null
    return if (parts.size == 3) parts.first() else parts.subList(1, parts.size - 2).joinToString("/")
}

/**
 * Pure: a case-insensitive Postgres regex for source ids in [folder]: the whole folder name or its
 * last part ("Spam" finds `[Gmail]/Spam`, "Sent Mail" finds `[Gmail]/Sent Mail`).
 */
fun emailFolderPattern(folder: String): String {
    val escaped = folder.trim().trim('/').replace(Regex("""[\\^$.|?*+()\[\]{}]""")) { "\\" + it.value }
    return "^(.+/)?$escaped/[0-9]+/[0-9]+$"
}
