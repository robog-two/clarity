package net.robog.clarity.backend.dedup

import java.nio.ByteBuffer
import net.robog.clarity.backend.crypto.sha256
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload

/**
 * Identity of a canonical entity: SHA-256 over a canonical tuple of normalized fields. Two
 * connectors reporting the same real-world thing produce the same key and merge into one row.
 */
class DedupKey(bytes: ByteArray) {
    private val value = bytes.copyOf()

    init { require(bytes.size == 32) { "dedup key must be a SHA-256 digest" } }

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is DedupKey && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = value.joinToString("") { "%02x".format(it) }
}

/**
 * Length-prefixed concatenation so `["ab","c"]` and `["a","bc"]` can never collide, then hashed.
 * The first part is always the noun tag, so keys of different nouns are disjoint.
 */
private fun digestOf(vararg parts: String): DedupKey {
    val encoded = parts.map { it.toByteArray(Charsets.UTF_8) }
    val buffer = ByteBuffer.allocate(encoded.sumOf { Int.SIZE_BYTES + it.size })
    encoded.forEach { buffer.putInt(it.size).put(it) }
    return DedupKey(sha256(buffer.array()))
}

private fun Handle.canonical(): String = "${kind.name.lowercase()}:$value"

/**
 * Computes the dedup key of [this] payload. The payload is normalized first, so callers may pass
 * raw or already-normalized data and get the same answer.
 *
 * - message: `channel | account | sender | sorted(recipients) | sha256(normalized body)`
 * - contact: sorted normalized handles (display name only when there are none)
 * - calendar event: `icalUid` if present, else `calendar | title | start | end`
 */
fun Payload.dedupKey(): DedupKey = when (val p = normalized()) {
    is MessagePayload -> digestOf(
        "message",
        p.channel.name.lowercase(),
        p.account.canonical(),
        p.sender.canonical(),
        p.recipients.joinToString(",") { it.canonical() },
        sha256(p.body).joinToString("") { "%02x".format(it) },
    )
    // A contact with no handles has nothing else to be identified by but its name.
    is ContactPayload ->
        if (p.handles.isEmpty()) digestOf("contact", "name", p.displayName.lowercase())
        else digestOf("contact", "handles", *p.handles.map { it.canonical() }.toTypedArray())
    is CalendarEventPayload -> when (val uid = p.icalUid) {
        null -> digestOf(
            "calendar_event", "fallback", p.calendar, p.title,
            p.start.toEpochMilliseconds().toString(), p.end.toEpochMilliseconds().toString(),
        )
        else -> digestOf("calendar_event", "uid", uid)
    }
}
