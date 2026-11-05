package net.robog.clarity.backend.persistence

import net.robog.clarity.shared.noun.Handle
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

/**
 * How handles are compared when matching messages to saved contacts. A phone is its last 10 digits
 * when it has at least 10 ("+1 (555) 555-0123" is "+15555550123"); anything else matches exactly
 * (an email ignoring case). Soft-deleted contacts never match; of two contacts sharing a handle,
 * the most recently updated wins.
 */
private val PHONE_SHAPE = Regex("^[+0-9 ().\\-]+$")

/** Pure: the comparison key of a handle value. */
fun handleKey(value: String): String {
    val v = value.trim()
    if ('@' !in v && PHONE_SHAPE.matches(v)) {
        val digits = v.filter(Char::isDigit)
        if (digits.length >= 10) return "p:" + digits.takeLast(10)
    }
    return "x:" + v.lowercase()
}

/** Handle key to saved contact name. */
class ContactNameIndex(private val names: Map<String, String> = emptyMap()) {
    fun nameFor(handle: Handle): String? = names[handleKey(handle.value)]

    /** [handle] with the saved name filled in when it has none. A name already on it stays. */
    fun named(handle: Handle): Handle =
        if (handle.displayName.isNullOrBlank()) nameFor(handle)?.let { handle.copy(displayName = it) } ?: handle else handle
}

/** Handle keys split by how the database compares them. */
internal class HandleKeys(keys: Collection<String>) {
    val exact = keys.filter { it.startsWith("x:") }.map { it.drop(2) }.distinct()
    val last10 = keys.filter { it.startsWith("p:") }.map { it.drop(2) }.distinct()
    val isEmpty get() = exact.isEmpty() && last10.isEmpty()

    /** SQL true when the text [expr] matches one of the keys; [arg] registers a bound text parameter in SQL order. */
    fun matching(expr: String, arg: (String) -> String): String {
        val parts = buildList {
            if (exact.isNotEmpty()) add("lower(btrim($expr)) IN (${exact.joinToString(",") { arg(it) }})")
            if (last10.isNotEmpty()) {
                add("($expr ~ '^[+0-9 ().-]+\$' AND right(regexp_replace($expr, '\\D', '', 'g'), 10) IN (${last10.joinToString(",") { arg(it) }}))")
            }
        }
        return parts.joinToString(" OR ", "(", ")")
    }
}

/** The saved names of [handles] that have none, one query for the whole batch. */
suspend fun JdbcTransaction.contactNames(handles: Collection<Handle>): ContactNameIndex {
    val keys = HandleKeys(handles.filter { it.displayName.isNullOrBlank() }.map { handleKey(it.value) })
    if (keys.isEmpty) return ContactNameIndex()
    val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
    val match = keys.matching("h.v ->> 'value'") { v -> args += TextColumnType() to v; "?" }
    val names = LinkedHashMap<String, String>()
    exec(
        "SELECT h.v ->> 'value', c.display_name FROM contact c CROSS JOIN LATERAL jsonb_array_elements(c.handles) h(v) " +
            "WHERE c.deleted_at IS NULL AND $match ORDER BY c.updated_at DESC, c.id",
        args,
    ) { rs ->
        while (rs.next()) {
            val name = rs.getString(2).trim()
            if (name.isNotEmpty()) names.putIfAbsent(handleKey(rs.getString(1)), name)
        }
    }
    return ContactNameIndex(names)
}

private const val MAX_NAME_CONTACTS = 50

/** The handles (as keys) of live contacts whose name or search text matches the LIKE [pattern] (over lowercase). */
internal suspend fun JdbcTransaction.contactHandleKeys(pattern: String): HandleKeys {
    val keys = mutableListOf<String>()
    exec(
        "SELECT h.v ->> 'value' FROM (SELECT c.handles FROM contact c WHERE c.deleted_at IS NULL " +
            "AND (lower(c.display_name) LIKE ? OR c.search_text LIKE ?) ORDER BY c.updated_at DESC LIMIT $MAX_NAME_CONTACTS) c " +
            "CROSS JOIN LATERAL jsonb_array_elements(c.handles) h(v)",
        listOf(TextColumnType() to pattern, TextColumnType() to pattern),
    ) { rs -> while (rs.next()) rs.getString(1)?.let { keys += handleKey(it) } }
    return HandleKeys(keys)
}
