package net.robog.clarity.connector.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.robog.clarity.shared.connector.ConnectorConfig

/** Id of the single item a legacy flat config (no collection) is read as. */
const val LEGACY_ITEM_ID = "default"

/**
 * One item of a config collection (for example one mail account). [legacy] items come from an old
 * flat config, whose secrets are sealed under the plain property name.
 */
data class ConfigItem(val collection: String, val id: String, val values: JsonObject, val legacy: Boolean = false) {
    /** Trimmed non-empty string property, or null. */
    fun text(name: String): String? =
        (values[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)

    /** Integer property (a number or a numeric string), or null. */
    fun int(name: String): Int? = (values[name] as? JsonPrimitive)?.intOrNull ?: text(name)?.toIntOrNull()

    /** String-array property; a comma or newline separated string is accepted too. Empty when unset. */
    fun list(name: String): List<String> =
        (values[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
            ?: text(name)?.split(',', '\n')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    /** What the person sees for this item: its `label`, else the id. */
    val label: String get() = text("label") ?: id
}

/**
 * The items of collection [collection]. A config without that collection but with other values is
 * the legacy flat form and reads as one item with id [LEGACY_ITEM_ID]. Items without an id are skipped.
 */
fun ConnectorConfig.items(collection: String): List<ConfigItem> {
    val array = values[collection] as? JsonArray
        ?: return if (values.isEmpty()) emptyList() else listOf(ConfigItem(collection, LEGACY_ITEM_ID, values, legacy = true))
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        ConfigItem(collection, id, obj)
    }
}

/** A [StateStore] whose keys all live under `<prefix>/`, so one connector can keep a state per account. */
fun StateStore.scoped(prefix: String): StateStore = object : StateStore {
    private val parent = this@scoped
    private fun k(key: String) = "$prefix/$key"
    override suspend fun get(key: String) = parent.get(k(key))
    override suspend fun put(key: String, value: JsonElement) = parent.put(k(key), value)
    override suspend fun remove(key: String) = parent.remove(k(key))
    override suspend fun keys() = parent.keys().filter { it.startsWith("$prefix/") }.map { it.removePrefix("$prefix/") }.toSet()
}

/** The outcome of testing one account. */
data class AccountTest(val ok: Boolean, val message: String, val counts: Map<String, Int> = emptyMap())

/**
 * Combines per-account tests (label to outcome) into one result: `ok` only if all are, failing
 * accounts first (`"Personal: password rejected · Work: 4,218 messages"`), counts summed.
 */
fun combineAccountTests(results: List<Pair<String, AccountTest>>): ConnectorTestResult {
    if (results.isEmpty()) return ConnectorTestResult(false, "No accounts have been added yet.")
    val counts = results.flatMap { it.second.counts.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
    return ConnectorTestResult(
        ok = results.all { it.second.ok },
        message = results.sortedBy { it.second.ok }.joinToString(" · ") { (label, test) -> "$label: ${test.message}" },
        counts = counts,
    )
}

/** Runs [block] and returns its outcome as a [Result]; any exception except cancellation becomes a failure, so one account cannot stop another. */
suspend fun <T> runIsolated(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
