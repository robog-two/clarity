package net.robog.clarity.harness

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
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
    // A stringified container passes here; the typed tool's decode unwraps it (see [unstringifyContainers]).
    "array" -> value is JsonArray || value.stringified<JsonArray>() != null
    "object" -> value is JsonObject || value.stringified<JsonObject>() != null
    "null" -> value is JsonNull
    else -> true
}

/**
 * Undoes a common model mistake: sending an object- or array-typed parameter as a JSON-encoded
 * *string* (`{"important": "{\"text\": \"...\"}"}`). Walks [element] against [descriptor] and,
 * wherever a class/object/map or a list is expected but a string holding JSON of that shape
 * arrives, substitutes the parsed value, then recurses into the (possibly substituted) structure.
 *
 * String-typed fields are never touched, even when their text looks like JSON, and a string that
 * does not parse to the expected shape is left as is so normal decoding reports the error.
 */
@OptIn(ExperimentalSerializationApi::class)
fun unstringifyContainers(descriptor: SerialDescriptor, element: JsonElement): JsonElement {
    val d = generateSequence(descriptor) { if (it.isInline) it.getElementDescriptor(0) else null }.last()
    return when (d.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> {
            val obj = element as? JsonObject ?: element.stringified<JsonObject>() ?: return element
            JsonObject(
                obj.mapValues { (key, value) ->
                    val index = d.getElementIndex(key)
                    if (index == CompositeDecoder.UNKNOWN_NAME) value else unstringifyContainers(d.getElementDescriptor(index), value)
                },
            )
        }
        StructureKind.MAP -> {
            val obj = element as? JsonObject ?: element.stringified<JsonObject>() ?: return element
            JsonObject(obj.mapValues { (_, value) -> unstringifyContainers(d.getElementDescriptor(1), value) })
        }
        StructureKind.LIST -> {
            val array = element as? JsonArray ?: element.stringified<JsonArray>() ?: return element
            JsonArray(array.map { unstringifyContainers(d.getElementDescriptor(0), it) })
        }
        // Which variant's shape applies is the decoder's call, so only the outer object is parsed.
        // `JsonElement` and friends are polymorphic too, and legitimately accept a plain string.
        is PolymorphicKind ->
            if (d.serialName.startsWith("kotlinx.serialization.json.")) element else element.stringified<JsonObject>() ?: element
        else -> element
    }
}

/** This element parsed as a [T] when it is a string primitive whose trimmed text is JSON of that shape. */
private inline fun <reified T : JsonElement> JsonElement.stringified(): T? {
    val text = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return null
    if (!text.startsWith(if (T::class == JsonArray::class) '[' else '{')) return null
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? T
}
