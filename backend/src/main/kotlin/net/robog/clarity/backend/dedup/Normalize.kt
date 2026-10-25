package net.robog.clarity.backend.dedup

import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.ContactPayload
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Payload

private val WHITESPACE = Regex("\\s+")

/** Collapses every whitespace run (incl. newlines) to one space and trims. */
fun String.collapseWhitespace(): String = trim().replace(WHITESPACE, " ")

private fun String?.collapsedOrNull(): String? = this?.collapseWhitespace()?.takeIf(String::isNotEmpty)

/** Digits only with a single leading `+` (a `00` international prefix counts as `+`). */
fun normalizePhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    val national = if (raw.trim().startsWith("00")) digits.removePrefix("00") else digits
    return "+$national"
}

fun normalizeEmail(raw: String): String = raw.trim().lowercase()

/**
 * Normalizes a handle value by [kind]. Display names are presentation only and kept
 * (collapsed) on the stored copy, but never take part in dedup keys.
 */
fun Handle.normalized(): Handle {
    val value = when (kind) {
        Handle.Kind.EMAIL -> normalizeEmail(value)
        Handle.Kind.PHONE -> normalizePhone(value)
        Handle.Kind.OTHER -> value.trim().lowercase()
    }
    return copy(value = value, displayName = displayName.collapsedOrNull())
}

/** Best-effort normalization of a bare string (e.g. an agent's "participant" argument). */
fun normalizeLooseHandle(raw: String): String = when {
    '@' in raw -> normalizeEmail(raw)
    raw.any(Char::isLetter) -> raw.trim().lowercase()
    raw.count(Char::isDigit) >= 5 -> normalizePhone(raw)
    else -> raw.trim().lowercase()
}

// display name last (nulls first) so which duplicate survives never depends on input order
private val handleOrder = compareBy<Handle>({ it.kind }, { it.value }).thenBy(nullsFirst()) { it.displayName }

/** Normalizes, de-duplicates and orders handles so list order and spelling never matter. */
fun List<Handle>.normalizedSet(): List<Handle> =
    map(Handle::normalized).sortedWith(handleOrder).distinctBy { it.kind to it.value }

fun MessagePayload.normalized(): MessagePayload = copy(
    account = account.normalized(),
    sender = sender.normalized(),
    recipients = recipients.normalizedSet(),
    subject = subject.collapsedOrNull(),
    body = body.collapseWhitespace(),
    threadRef = threadRef?.trim()?.takeIf(String::isNotEmpty),
)

fun ContactPayload.normalized(): ContactPayload = copy(
    displayName = displayName.collapseWhitespace(),
    handles = handles.normalizedSet(),
    organization = organization.collapsedOrNull(),
    notes = notes?.trim()?.takeIf(String::isNotEmpty),
)

fun CalendarEventPayload.normalized(): CalendarEventPayload = copy(
    icalUid = icalUid?.trim()?.takeIf(String::isNotEmpty),
    calendar = calendar.collapseWhitespace(),
    title = title.collapseWhitespace(),
    location = location.collapsedOrNull(),
    description = description?.trim()?.takeIf(String::isNotEmpty),
    attendees = attendees.normalizedSet(),
)

/** Exhaustive per-noun normalization. Idempotent: `p.normalized().normalized() == p.normalized()`. */
fun Payload.normalized(): Payload = when (this) {
    is MessagePayload -> normalized()
    is ContactPayload -> normalized()
    is CalendarEventPayload -> normalized()
}
