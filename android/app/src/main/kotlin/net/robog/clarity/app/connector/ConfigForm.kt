package net.robog.clarity.app.connector

import java.net.URI
import java.net.URISyntaxException
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing

/**
 * The subset of JSON Schema the app renders (see docs/workstreams/connector-accounts.md):
 * strings (text, url, email, secret, dropdown), numbers, booleans, lists of strings and
 * collections of objects (one card per item, for example one mail account each).
 */
enum class FieldKind { TEXT, PASSWORD, URL, EMAIL, NUMBER, BOOLEAN, ENUM, STRING_LIST, COLLECTION }

data class EnumOption(val value: String, val title: String)

/** A choice offered when adding a collection item ("iCloud", "Fastmail", ...). */
data class ItemPreset(val title: String, val description: String?, val values: FormValues)

/** How a collection field renders and validates its items. */
data class CollectionSpec(
    val itemForm: ConfigForm,
    /** Item property shown as the card title. */
    val titleKey: String? = null,
    /** Item property shown under the title. */
    val subtitleKey: String? = null,
    val minItems: Int = 0,
    val presets: List<ItemPreset> = emptyList(),
)

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
    /** Only for [FieldKind.STRING_LIST]: the schema default entries. */
    val defaultList: List<String> = emptyList(),
    /** `examples[0]`. */
    val placeholder: String? = null,
    /** Only for [FieldKind.ENUM]. */
    val options: List<EnumOption> = emptyList(),
    /** Only for [FieldKind.COLLECTION]. */
    val collection: CollectionSpec? = null,
) {
    val secret: Boolean get() = kind == FieldKind.PASSWORD
}

sealed interface FieldError {
    data object Required : FieldError
    data object InvalidUrl : FieldError
    data object InvalidEmail : FieldError
    data object InvalidChoice : FieldError
    data object NotANumber : FieldError
    data object NotAWholeNumber : FieldError
    data class TooSmall(val minimum: Double) : FieldError
    data class TooLarge(val maximum: Double) : FieldError
    data class TooFewItems(val minimum: Int) : FieldError
    /** A stored item is missing something it needs; open it to see what. */
    data object ItemInvalid : FieldError
}

/** One item of a collection. [id] never changes (it is part of the sealing `info`). */
data class ItemValues(val id: String, val values: FormValues)

/**
 * What the person has entered, in the shape of the form. Scalars (including enums, numbers,
 * booleans and secrets typed this session) are text; lists and collections have their own maps.
 */
data class FormValues(
    val texts: Map<String, String> = emptyMap(),
    val lists: Map<String, List<String>> = emptyMap(),
    val collections: Map<String, List<ItemValues>> = emptyMap(),
    /** Sealed secrets already stored on the server, kept when the person does not retype them. */
    val storedSecrets: Map<String, JsonObject> = emptyMap(),
) {
    fun text(key: String): String = texts[key].orEmpty()
    fun list(key: String): List<String> = lists[key].orEmpty()
    fun items(key: String): List<ItemValues> = collections[key].orEmpty()

    fun withText(key: String, value: String) = copy(texts = texts + (key to value))
    fun withList(key: String, value: List<String>) = copy(lists = lists + (key to value))
    fun withItems(key: String, value: List<ItemValues>) = copy(collections = collections + (key to value))

    /** True if there is something for the secret [key]: typed now or stored. */
    fun hasSecret(key: String): Boolean = text(key).isNotBlank() || key in storedSecrets

    /** [other] wins where it has a value. */
    fun overlay(other: FormValues) = FormValues(
        texts + other.texts,
        lists + other.lists,
        collections + other.collections,
        storedSecrets + other.storedSecrets,
    )

    // --- collection state ---

    /** Adds the item, or replaces the one with the same id. */
    fun withItem(key: String, item: ItemValues): FormValues {
        val current = items(key)
        val next = if (current.any { it.id == item.id }) current.map { if (it.id == item.id) item else it } else current + item
        return withItems(key, next)
    }

    fun withoutItem(key: String, id: String): FormValues = withItems(key, items(key).filterNot { it.id == id })
}

/** Ids for collection items: 12 characters of `[a-z0-9]`, never reused within a collection. */
object ItemIds {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val VALID = Regex("[a-z0-9]{8,16}")
    const val LENGTH = 12

    fun isValid(id: String): Boolean = VALID.matches(id)

    fun generate(taken: Set<String> = emptySet(), random: Random = Random.Default): String {
        while (true) {
            val id = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
            if (id !in taken) return id
        }
    }
}

data class ConfigForm(val fields: List<FormField>) {
    /** Initial values: schema defaults. Booleans always have a value. */
    fun initialValues(): FormValues {
        val texts = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, List<String>>()
        for (f in fields) {
            when (f.kind) {
                FieldKind.STRING_LIST -> if (f.defaultList.isNotEmpty()) lists[f.key] = f.defaultList
                FieldKind.COLLECTION -> Unit
                FieldKind.BOOLEAN -> texts[f.key] = f.default ?: "false"
                else -> f.default?.let { texts[f.key] = it }
            }
        }
        return FormValues(texts = texts, lists = lists)
    }

    /** Errors for every field that is not acceptable yet. A stored secret counts as filled in. */
    fun errors(values: FormValues): Map<String, FieldError> =
        fields.mapNotNull { f -> fieldError(f, values)?.let { f.key to it } }.toMap()

    private fun fieldError(f: FormField, values: FormValues): FieldError? = when (f.kind) {
        FieldKind.BOOLEAN -> null
        FieldKind.STRING_LIST ->
            if (f.required && values.list(f.key).none { it.isNotBlank() }) FieldError.Required else null
        FieldKind.COLLECTION -> {
            val spec = f.collection!!
            val items = values.items(f.key)
            val minimum = maxOf(spec.minItems, if (f.required) 1 else 0)
            when {
                items.size < minimum -> FieldError.TooFewItems(minimum)
                items.any { spec.itemForm.errors(it.values).isNotEmpty() } -> FieldError.ItemInvalid
                else -> null
            }
        }
        FieldKind.PASSWORD ->
            if (f.required && !values.hasSecret(f.key)) FieldError.Required else null
        else -> ConfigForms.validate(f, values.text(f.key))
    }

    /** True if the person typed a new secret anywhere (so a sealing key is needed). */
    fun hasTypedSecrets(values: FormValues): Boolean = fields.any { f ->
        when (f.kind) {
            FieldKind.PASSWORD -> values.text(f.key).isNotBlank()
            FieldKind.COLLECTION -> values.items(f.key).any { f.collection!!.itemForm.hasTypedSecrets(it.values) }
            else -> false
        }
    }

    /**
     * The `values` object to PUT: typed per field, optional empties omitted.
     *
     * A secret typed now is sealed with [seal], given the HPKE `info` field name (the property name
     * at the top level, [Sealing.itemField] inside a collection item). A secret left alone is sent as
     * the stored sealed object, unchanged.
     */
    fun toConfigValues(values: FormValues, seal: (field: String, plaintext: String) -> SealedValue): JsonObject =
        build(values, { it }, seal)

    private fun build(values: FormValues, infoField: (String) -> String, seal: (String, String) -> SealedValue): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        for (f in fields) {
            when (f.kind) {
                FieldKind.BOOLEAN -> out[f.key] = JsonPrimitive(values.text(f.key) == "true")
                FieldKind.STRING_LIST -> {
                    val list = values.list(f.key).map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isNotEmpty()) out[f.key] = JsonArray(list.map(::JsonPrimitive))
                }
                FieldKind.COLLECTION -> {
                    val spec = f.collection!!
                    val items = values.items(f.key)
                    if (items.isNotEmpty()) {
                        out[f.key] = JsonArray(
                            items.map { item ->
                                val body = spec.itemForm.build(item.values, { p -> Sealing.itemField(f.key, item.id, p) }, seal)
                                JsonObject(linkedMapOf<String, JsonElement>("id" to JsonPrimitive(item.id)) + body)
                            },
                        )
                    }
                }
                FieldKind.PASSWORD -> {
                    val typed = values.text(f.key)
                    if (typed.isNotBlank()) {
                        val s = seal(infoField(f.key), typed)
                        out[f.key] = JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("sealed"),
                                "algorithm" to JsonPrimitive(s.algorithm),
                                "ciphertext" to JsonPrimitive(s.ciphertext),
                            ),
                        )
                    } else {
                        values.storedSecrets[f.key]?.let { out[f.key] = it }
                    }
                }
                else -> {
                    val raw = values.text(f.key).trim()
                    if (raw.isEmpty()) continue
                    out[f.key] = when (f.kind) {
                        FieldKind.NUMBER -> if (f.integer) JsonPrimitive(raw.toLong()) else JsonPrimitive(raw.toDouble())
                        else -> JsonPrimitive(raw)
                    }
                }
            }
        }
        return JsonObject(out)
    }

    /**
     * The values already stored on the server (`GET .../config`). Secrets come back sealed and are
     * kept as they are; they are never shown.
     */
    fun valuesFromStored(stored: JsonObject): FormValues {
        val texts = linkedMapOf<String, String>()
        val lists = linkedMapOf<String, List<String>>()
        val collections = linkedMapOf<String, List<ItemValues>>()
        val secrets = linkedMapOf<String, JsonObject>()
        for (f in fields) {
            val e = stored[f.key] ?: continue
            when (f.kind) {
                FieldKind.PASSWORD -> if (e.isSealed()) secrets[f.key] = e as JsonObject
                FieldKind.STRING_LIST -> (e as? JsonArray)?.let { a ->
                    lists[f.key] = a.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                }
                FieldKind.COLLECTION -> (e as? JsonArray)?.let { a ->
                    val spec = f.collection!!
                    val taken = mutableSetOf<String>()
                    collections[f.key] = a.mapNotNull { node ->
                        val obj = node as? JsonObject ?: return@mapNotNull null
                        val given = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        val id = given?.takeIf { it.isNotEmpty() && it !in taken } ?: ItemIds.generate(taken)
                        taken += id
                        var v = spec.itemForm.valuesFromStored(obj)
                        // A secret sealed for another id could not be opened any more: drop it.
                        if (id != given) v = v.copy(storedSecrets = emptyMap())
                        ItemValues(id, v)
                    }
                }
                else -> (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { texts[f.key] = it.content }
            }
        }
        return FormValues(texts, lists, collections, secrets)
    }

    private fun JsonElement.isSealed(): Boolean =
        this is JsonObject && (this["type"] as? JsonPrimitive)?.contentOrNull == "sealed"

    /** The card title of [item] of collection [field]. */
    fun itemTitle(field: FormField, item: ItemValues): String =
        field.collection?.titleKey?.let { item.values.text(it).takeIf(String::isNotBlank) }
            ?: field.collection?.subtitleKey?.let { item.values.text(it).takeIf(String::isNotBlank) }
            ?: "account"

    fun itemSubtitle(field: FormField, item: ItemValues): String? {
        val spec = field.collection ?: return null
        val sub = spec.subtitleKey?.let { item.values.text(it).takeIf(String::isNotBlank) } ?: return null
        // Never repeat the title.
        return sub.takeIf { it != itemTitle(field, item) }
    }

    /** True if some field of this form (or of a collection item) is a secret. */
    val hasSecrets: Boolean
        get() = fields.any { it.secret || it.collection?.itemForm?.hasSecrets == true }
}

object ConfigForms {
    /** Builds the form from a JSON Schema object; properties outside the supported subset are skipped. */
    fun parse(schema: JsonObject): ConfigForm = parse(schema, allowCollections = true)

    private fun parse(schema: JsonObject, allowCollections: Boolean): ConfigForm {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val required = (schema["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
        val fields = properties.mapNotNull { (key, node) ->
            val p = node as? JsonObject ?: return@mapNotNull null
            // `id` of an item and other generated values are never shown.
            if ((p["readOnly"] as? JsonPrimitive)?.booleanOrNull == true) return@mapNotNull null
            val type = typeOf(p) ?: return@mapNotNull null
            val format = p["format"].str()
            val flagSecret = listOf("writeOnly", "x-secret").any { (p[it] as? JsonPrimitive)?.booleanOrNull == true }
            val options = enumOptions(p)
            var collection: CollectionSpec? = null
            val kind = when {
                type == "boolean" -> FieldKind.BOOLEAN
                type == "integer" || type == "number" -> FieldKind.NUMBER
                type == "string" -> when {
                    format == "password" || flagSecret -> FieldKind.PASSWORD
                    options.isNotEmpty() -> FieldKind.ENUM
                    format == "uri" || format == "url" -> FieldKind.URL
                    format == "email" -> FieldKind.EMAIL
                    else -> FieldKind.TEXT
                }
                type == "array" -> {
                    val items = p["items"] as? JsonObject ?: return@mapNotNull null
                    when (typeOf(items)) {
                        "string" -> FieldKind.STRING_LIST
                        "object" -> {
                            if (!allowCollections) return@mapNotNull null
                            collection = collectionSpec(p, items)
                            FieldKind.COLLECTION
                        }
                        else -> return@mapNotNull null
                    }
                }
                else -> return@mapNotNull null
            }
            val default = p["default"]
            FormField(
                key = key,
                label = (p["title"].str() ?: humanize(key)).lowercase(),
                description = p["description"].str(),
                kind = kind,
                required = key in required,
                integer = type == "integer",
                minimum = (p["minimum"] as? JsonPrimitive)?.doubleOrNull,
                maximum = (p["maximum"] as? JsonPrimitive)?.doubleOrNull,
                default = if (default is JsonPrimitive && default !is JsonNull) default.content else null,
                defaultList = (default as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                placeholder = ((p["examples"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                options = options,
                collection = collection,
            )
        }
        return ConfigForm(fields)
    }

    private fun collectionSpec(p: JsonObject, items: JsonObject): CollectionSpec {
        val itemForm = parse(items, allowCollections = false)
        val presets = (p["x-presets"] as? JsonArray).orEmpty().mapNotNull { node ->
            val o = node as? JsonObject ?: return@mapNotNull null
            val title = o["title"].str() ?: return@mapNotNull null
            ItemPreset(title, o["description"].str(), presetValues(itemForm, o["values"] as? JsonObject))
        }
        return CollectionSpec(
            itemForm = itemForm,
            titleKey = p["x-item-title"].str(),
            subtitleKey = p["x-item-subtitle"].str(),
            minItems = ((p["minItems"] as? JsonPrimitive)?.doubleOrNull)?.toInt() ?: 0,
            presets = presets,
        )
    }

    private fun presetValues(itemForm: ConfigForm, values: JsonObject?): FormValues {
        var out = FormValues()
        for (f in itemForm.fields) {
            val e = values?.get(f.key) ?: continue
            out = when {
                f.kind == FieldKind.STRING_LIST && e is JsonArray ->
                    out.withList(f.key, e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
                f.kind == FieldKind.COLLECTION || f.kind == FieldKind.PASSWORD -> out
                e is JsonPrimitive && e !is JsonNull -> out.withText(f.key, e.content)
                else -> out
            }
        }
        return out
    }

    private fun enumOptions(p: JsonObject): List<EnumOption> {
        val values = (p["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x !is JsonNull }?.content }.orEmpty()
        val titles = (p["x-enum-titles"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull }
        return values.mapIndexed { i, v ->
            EnumOption(v, titles?.takeIf { it.size == values.size }?.get(i) ?: v)
        }
    }

    /** Validates one scalar input. Secrets, lists and collections are checked by [ConfigForm.errors]. */
    fun validate(field: FormField, raw: String): FieldError? {
        val text = raw.trim()
        if (field.kind == FieldKind.BOOLEAN) return null
        if (text.isEmpty()) return if (field.required) FieldError.Required else null
        return when (field.kind) {
            FieldKind.URL -> if (isUrl(text)) null else FieldError.InvalidUrl
            FieldKind.EMAIL -> if (EMAIL.matches(text)) null else FieldError.InvalidEmail
            FieldKind.NUMBER -> numberError(field, text)
            FieldKind.ENUM -> if (field.options.any { it.value == text }) null else FieldError.InvalidChoice
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

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

/** Builds the secret-sealing callback for [ConfigForm.toConfigValues]. */
fun sealerFor(sealer: ConfigSealer, connectorId: ConnectorId, sealingKey: String): (String, String) -> SealedValue =
    { field, plaintext -> sealer.seal(connectorId, field, sealingKey, plaintext) }

/**
 * The part of a connector's test text that names [label]: from "Work: 4,218 messages · Personal: 312
 * messages" and "Work" this is "4,218 messages". Null if the text does not name that account.
 */
fun accountTestLine(text: String?, label: String): String? {
    if (text.isNullOrBlank() || label.isBlank()) return null
    val prefix = "$label: "
    return text.split(" · ", "; ", "\n").map { it.trim() }
        .firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }
}
