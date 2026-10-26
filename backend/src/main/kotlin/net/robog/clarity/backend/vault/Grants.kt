package net.robog.clarity.backend.vault

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One released credential on one site: the secret values, in memory only, until [expiresAt]. */
class Grant(
    val credential: String,
    val domain: String,
    values: Map<String, String>,
    val expiresAt: Instant,
) {
    private val fieldValues: Map<String, String> = values.toMap()

    fun value(field: String): String? = fieldValues[field]
    val fields: Set<String> get() = fieldValues.keys
    internal fun entries(): Map<String, String> = fieldValues

    /** Never prints a value. */
    override fun toString(): String = "Grant($credential@$domain, fields=${fieldValues.keys}, expires=$expiresAt)"
}

/**
 * Pure: every spelling of [value] that a page or an error message might echo back (as-is, URL
 * encoded, HTML-entity escaped, JSON-string escaped, and for digit strings the usual groupings
 * with spaces or dashes), as regexes. Digit-only values need non-digit neighbours so that short
 * codes do not match inside longer numbers.
 */
fun scrubPatterns(value: String): List<Regex> {
    if (value.length < MIN_SCRUB_LENGTH) return emptyList()
    val literals = linkedSetOf(value)
    literals += URLEncoder.encode(value, Charsets.UTF_8)
    literals += URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    literals += htmlEscape(value)
    literals += jsonEscape(value)
    literals += htmlEscape(value, numeric = true)
    val digitsOnly = value.all { it.isDigit() }
    val patterns = literals.filter { it.isNotEmpty() }.map { lit ->
        val body = Regex.escape(lit)
        if (digitsOnly && lit == value) Regex("(?<![0-9])$body(?![0-9])") else Regex(body, RegexOption.IGNORE_CASE)
    }.toMutableList()
    val digits = value.filter { it.isDigit() }
    if (digits.length in 12..19 && digits.length >= value.length - 6) {
        // card numbers with spaces or dashes between any digits ("4111 1111 1111 1111", "4111-1111-1111-1111")
        patterns += Regex("(?<![0-9])" + digits.map { Regex.escape(it.toString()) }.joinToString("[ \\-]?") + "(?![0-9])")
    }
    return patterns
}

private const val MIN_SCRUB_LENGTH = 3

private fun htmlEscape(value: String, numeric: Boolean = false): String = buildString {
    for (c in value) {
        when (c) {
            '&' -> append(if (numeric) "&#38;" else "&amp;")
            '<' -> append(if (numeric) "&#60;" else "&lt;")
            '>' -> append(if (numeric) "&#62;" else "&gt;")
            '"' -> append(if (numeric) "&#34;" else "&quot;")
            '\'' -> append(if (numeric) "&#39;" else "&#x27;")
            else -> append(c)
        }
    }
}

private fun jsonEscape(value: String): String = buildString {
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

/** Pure: replaces every pattern of every `(value to placeholder)` in [text], longest values first. */
fun scrubText(text: String, replacements: List<Pair<String, String>>): String {
    var out = text
    for ((value, placeholder) in replacements.sortedByDescending { it.first.length }) {
        for (pattern in scrubPatterns(value)) out = pattern.replace(out, java.util.regex.Matcher.quoteReplacement(placeholder))
    }
    return out
}

/**
 * The live grants, in memory only. A grant covers one credential on one registrable domain until it
 * expires (at most [MAX_TTL]); its values are dropped then. Released values are remembered for
 * scrubbing a little longer ([SCRUB_RETENTION]) because a filled page keeps showing them after the
 * grant is gone. Nothing here is ever persisted or logged.
 */
@Inject
@SingleIn(AppScope::class)
class GrantStore(private val clock: Clock) {
    private val grants = CopyOnWriteArrayList<Grant>()
    private val remembered = CopyOnWriteArrayList<Remembered>()

    private class Remembered(val value: String, val placeholder: String, val until: Instant)

    /** Registers a grant, replacing any earlier one for the same credential and domain. [ttl] is capped at [MAX_TTL]. */
    fun put(credential: String, domain: String, values: Map<String, String>, ttl: Duration): Grant {
        val now = clock.now()
        val grant = Grant(credential, domain, values.filterValues { it.isNotEmpty() }, now + ttl.coerceIn(Duration.ZERO, MAX_TTL))
        sweep(now)
        grants.removeAll { it.credential == credential && it.domain == domain }
        grants += grant
        grant.entries().forEach { (field, value) ->
            remembered += Remembered(value, Placeholders.placeholder(credential, field), grant.expiresAt + SCRUB_RETENTION)
        }
        return grant
    }

    /** The live grant for [credential] on [domain], or null (expired grants are dropped). */
    fun find(credential: String, domain: String): Grant? {
        sweep(clock.now())
        return grants.firstOrNull { it.credential == credential && it.domain == domain }
    }

    /** Drops every live grant of [credential] (it was deleted). Remembered values stay for scrubbing. */
    fun revoke(credential: String) {
        grants.removeAll { it.credential == credential }
    }

    /** The live code grant for a held id, regardless of page (the caller checks the domain). */
    fun findCode(heldId: String): Grant? {
        sweep(clock.now())
        return grants.firstOrNull { it.credential == "code" && heldId in it.fields }
    }

    /** Replaces every value released in this process recently, in any spelling, by its placeholder. */
    fun scrub(text: String): String {
        if (text.isEmpty()) return text
        sweep(clock.now())
        return scrubText(text, remembered.map { it.value to it.placeholder })
    }

    fun liveCount(): Int {
        sweep(clock.now())
        return grants.size
    }

    private fun sweep(now: Instant) {
        grants.removeAll { it.expiresAt <= now }
        remembered.removeAll { it.until <= now }
    }

    companion object {
        val MAX_TTL: Duration = 5.minutes
        val SCRUB_RETENTION: Duration = 15.minutes
    }
}
