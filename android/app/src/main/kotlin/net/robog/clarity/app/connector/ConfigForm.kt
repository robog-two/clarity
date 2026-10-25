package net.robog.clarity.app.connector

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.SealedValue

enum class FieldKind { TEXT, PASSWORD, URL, EMAIL, NUMBER, BOOLEAN }

/** One input of a connector's settings form, derived from a property of its `configSchema`. */
data class FormField(
    val key: String,
    /** Lowercase label shown above the input. */
    val label: String,
    val description: String? = null,
    val kind: FieldKind = FieldKind.TEXT,
    val required: Boolean = false,
    /** Only for [FieldKind.NUMBER]: whole numbers only. */
    val integer: Boolean = false,
    val minimum: Double? = null,
    val maximum: Double? = null,
    /** The schema default as input text ("true"/"false" for booleans). */
    val default: String? = null,
) {
    val secret: Boolean get() = kind == FieldKind.PASSWORD
}

sealed interface FieldError {
    data object Required : FieldError
    data object InvalidUrl : FieldError
    data object InvalidEmail : FieldError
    data object NotANumber : FieldError
    data object NotAWholeNumber : FieldError
    data class TooSmall(val minimum: Double) : FieldError
    data class TooLarge(val maximum: Double) : FieldError
}

data class ConfigForm(val fields: List<FormField>) {
    /** Initial input texts: schema defaults. Booleans always have a value. */
    fun initialValues(): Map<String, String> =
        fields.mapNotNull { f ->
            val v = f.default ?: if (f.kind == FieldKind.BOOLEAN) "false" else null
            v?.let { f.key to it }
        }.toMap()

    /** Errors for every field that is not acceptable yet (secrets left empty count as missing). */
    fun errors(values: Map<String, String>): Map<String, FieldError> =
        fields.mapNotNull { f -> ConfigForms.validate(f, values[f.key].orEmpty())?.let { f.key to it } }.toMap()

    /**
     * The `values` object to PUT: typed per field, optional empties omitted, secrets sealed with [seal]
     * (the result is the JSON form of a [SealedValue] with a `type: "sealed"` marker).
     */
    fun toConfigValues(values: Map<String, String>, seal: (FormField, String) -> SealedValue): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        for (f in fields) {
            val raw = values[f.key].orEmpty().trim()
            if (raw.isEmpty() && f.kind != FieldKind.BOOLEAN) continue
            out[f.key] = when (f.kind) {
                FieldKind.BOOLEAN -> JsonPrimitive(raw == "true")
                FieldKind.NUMBER -> if (f.integer) JsonPrimitive(raw.toLong()) else JsonPrimitive(raw.toDouble())
                FieldKind.PASSWORD -> seal(f, values[f.key].orEmpty()).let {
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("sealed"),
                            "algorithm" to JsonPrimitive(it.algorithm),
                            "ciphertext" to JsonPrimitive(it.ciphertext),
                        ),
                    )
                }
                else -> JsonPrimitive(raw)
            }
        }
        return JsonObject(out)
    }
}

object ConfigForms {
    /** Builds the form from a JSON Schema object; unsupported (nested/array) properties are skipped. */
    fun parse(schema: JsonObject): ConfigForm {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val required = (schema["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        val fields = properties.mapNotNull { (key, node) ->
            val p = node as? JsonObject ?: return@mapNotNull null
            val type = typeOf(p) ?: return@mapNotNull null
            val format = p["format"].str()
            val writeOnly = (p["writeOnly"] as? JsonPrimitive)?.booleanOrNull == true
            val kind = when {
                type == "boolean" -> FieldKind.BOOLEAN
                type == "integer" || type == "number" -> FieldKind.NUMBER
                type == "string" -> when {
                    format == "password" || writeOnly -> FieldKind.PASSWORD
                    format == "uri" || format == "url" -> FieldKind.URL
                    format == "email" -> FieldKind.EMAIL
                    else -> FieldKind.TEXT
                }
                else -> return@mapNotNull null
            }
            FormField(
                key = key,
                label = (p["title"].str() ?: humanize(key)).lowercase(),
                description = p["description"].str(),
                kind = kind,
                required = key in required,
                integer = type == "integer",
                minimum = (p["minimum"] as? JsonPrimitive)?.doubleOrNull,
                maximum = (p["maximum"] as? JsonPrimitive)?.doubleOrNull,
                default = p["default"].let { d -> if (d is JsonPrimitive && d !is JsonNull) d.content else null },
            )
        }
        return ConfigForm(fields)
    }

    fun validate(field: FormField, raw: String): FieldError? {
        val text = raw.trim()
        if (field.kind == FieldKind.BOOLEAN) return null
        if (text.isEmpty()) return if (field.required) FieldError.Required else null
        return when (field.kind) {
            FieldKind.URL -> if (isUrl(text)) null else FieldError.InvalidUrl
            FieldKind.EMAIL -> if (EMAIL.matches(text)) null else FieldError.InvalidEmail
            FieldKind.NUMBER -> numberError(field, text)
            else -> null
        }
    }

    private fun numberError(field: FormField, text: String): FieldError? {
        val n = if (field.integer) {
            text.toLongOrNull()?.toDouble() ?: return if (text.toDoubleOrNull() != null) FieldError.NotAWholeNumber else FieldError.NotANumber
        } else {
            text.toDoubleOrNull() ?: return FieldError.NotANumber
        }
        field.minimum?.let { if (n < it) return FieldError.TooSmall(it) }
        field.maximum?.let { if (n > it) return FieldError.TooLarge(it) }
        return null
    }

    private fun isUrl(text: String): Boolean = try {
        val uri = URI(text)
        !uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()
    } catch (_: URISyntaxException) {
        false
    }

    private fun typeOf(p: JsonObject): String? = when (val t = p["type"]) {
        is JsonPrimitive -> t.contentOrNull
        is JsonArray -> t.map { it.jsonPrimitive.content }.firstOrNull { it != "null" }
        else -> if (p["enum"] != null) "string" else null
    }

    private fun humanize(key: String): String =
        key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ')

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")
}

/** Non-secret values already stored on the server, to prefill the form (sealed ones are skipped). */
fun storedFormValues(form: ConfigForm, stored: JsonObject): Map<String, String> =
    form.fields.mapNotNull { f ->
        if (f.secret) return@mapNotNull null
        val e = stored[f.key] as? JsonPrimitive ?: return@mapNotNull null
        f.key to e.content
    }.toMap()

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

/** Builds the secret-sealing callback for [ConfigForm.toConfigValues]. */
fun sealerFor(sealer: ConfigSealer, connectorId: ConnectorId, sealingKey: String): (FormField, String) -> SealedValue =
    { _, plaintext -> sealer.seal(connectorId, sealingKey, plaintext) }
