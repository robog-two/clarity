package net.robog.clarity.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** kotlinx JSON to plain Java values (maps, lists, scalars), the form the MCP client takes for tool arguments. */
internal fun JsonElement.toJavaValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> mapValuesTo(LinkedHashMap()) { it.value.toJavaValue() }
    is JsonArray -> map { it.toJavaValue() }
    is JsonPrimitive -> when {
        isString -> content
        else -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    }
}
