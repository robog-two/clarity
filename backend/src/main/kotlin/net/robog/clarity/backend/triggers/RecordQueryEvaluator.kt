package net.robog.clarity.backend.triggers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.noun.Payload
import net.robog.clarity.shared.query.RecordQuery

/** Serializes a payload exactly as it is on the wire, so query field paths match the documented JSON. */
fun Payload.toJsonElement(): JsonElement = ClarityJson.encodeToJsonElement(Payload.serializer(), this)

/**
 * Pure evaluation of [query] against a payload's JSON. Field paths are dot-separated
 * (`sender.value`); a path through a list visits every element, and a leaf matches when *any*
 * visited value matches. Missing fields never match (so `Not(Equals(missing, x))` is true).
 * `Matches` uses find-semantics (`containsMatchIn`); anchor with `^...$` for a full match, and an
 * invalid regex simply does not match rather than throwing.
 */
fun RecordQuery.matches(payload: JsonElement): Boolean = when (this) {
    is RecordQuery.All -> of.all { it.matches(payload) }
    is RecordQuery.AnyOf -> of.any { it.matches(payload) }
    is RecordQuery.Not -> !query.matches(payload)
    is RecordQuery.Equals -> payload.valuesAt(field).any { it.equals(value, ignoreCase) }
    is RecordQuery.Contains -> payload.valuesAt(field).any { it.contains(value, ignoreCase) }
    is RecordQuery.Matches -> {
        val compiled = runCatching { Regex(regex) }.getOrNull()
        compiled != null && payload.valuesAt(field).any { compiled.containsMatchIn(it) }
    }
}

fun RecordQuery.matches(payload: Payload): Boolean = matches(payload.toJsonElement())

/** All scalar values reachable via [path], rendered as strings. */
internal fun JsonElement.valuesAt(path: String): List<String> =
    path.split('.').filter(String::isNotEmpty).fold(listOf(this)) { nodes, segment ->
        nodes.flatMap { node ->
            when (node) {
                is JsonObject -> listOfNotNull(node[segment])
                is JsonArray -> node.mapNotNull { (it as? JsonObject)?.get(segment) }
                else -> emptyList()
            }
        }
    }.flatMap { it.scalars() }

private fun JsonElement.scalars(): List<String> = when (this) {
    JsonNull -> emptyList()
    is JsonPrimitive -> listOf(content)
    is JsonArray -> flatMap { it.scalars() }
    is JsonObject -> emptyList()
}
