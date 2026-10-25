package net.robog.clarity.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Cheap structural check of a model-produced tool input against the tool's JSON schema, run before
 * any tool code: required properties present and top-level property types right. This is what turns
 * a truncated or malformed eagerly-streamed input into an error result the model can correct.
 * Returns a message for the model, or null when the input looks valid. Schemas without
 * `required` / `properties` (hand-written tools) always pass.
 */
fun validateToolInput(schema: JsonObject, input: JsonObject): String? {
    val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    val missing = required.filter { it !in input }
    if (missing.isNotEmpty()) return "missing required parameter(s): ${missing.joinToString()}"
    val properties = schema["properties"] as? JsonObject ?: return null
    for ((name, value) in input) {
        if (value is JsonNull) continue // nullable properties are not marked in the derived schema
        val declared = (properties[name] as? JsonObject)?.get("type") as? JsonPrimitive ?: continue
        if (!matches(declared.content, value)) return "parameter '$name' must be of type ${declared.content}"
    }
    return null
}

private fun matches(type: String, value: JsonElement): Boolean = when (type) {
    "string" -> value is JsonPrimitive && value.isString
    "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
    "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
    "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
    "array" -> value is JsonArray
    "object" -> value is JsonObject
    "null" -> value is JsonNull
    else -> true
}
