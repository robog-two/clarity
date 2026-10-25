package net.robog.clarity.harness

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Human description of a property or class, surfaced to the model in the tool's JSON schema. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Description(val text: String)

/**
 * Derives a JSON Schema (draft 2020-12 subset the Messages API accepts) from a serializer's
 * descriptor, so the schema the model sees can never drift from the class we decode into.
 *
 * Supported: primitives, enums, lists/sets, string-keyed maps, nested classes, nullable/optional
 * properties (omitted from `required`), and sealed hierarchies (as `oneOf` with the `type`
 * discriminator, via `$defs`/`$ref` so recursive types like `RecordQuery` terminate).
 */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
fun jsonSchemaOf(root: SerialDescriptor, discriminator: String = "type"): JsonObject {
    val defs = linkedMapOf<String, JsonElement>()
    val inProgress = mutableSetOf<String>()

    fun ref(name: String) = buildJsonObject { put("\$ref", "#/\$defs/$name") }

    fun defName(d: SerialDescriptor) = d.serialName.removeSuffix("?").substringAfterLast('.')

    fun description(annotations: List<Annotation>): String? =
        annotations.filterIsInstance<Description>().firstOrNull()?.text

    lateinit var schema: (SerialDescriptor) -> JsonObject

    fun objectSchema(d: SerialDescriptor, discriminatorValue: String?): JsonObject = buildJsonObject {
        put("type", "object")
        val props = buildJsonObject {
            if (discriminatorValue != null) {
                put(discriminator, buildJsonObject { put("const", discriminatorValue) })
            }
            for (i in 0 until d.elementsCount) {
                val child = schema(d.getElementDescriptor(i))
                val doc = description(d.getElementAnnotations(i))
                put(d.getElementName(i), if (doc == null) child else JsonObject(child + ("description" to JsonPrimitive(doc))))
            }
        }
        put("properties", props)
        putJsonArray("required") {
            if (discriminatorValue != null) add(JsonPrimitive(discriminator))
            for (i in 0 until d.elementsCount) {
                if (!d.isElementOptional(i) && !d.getElementDescriptor(i).isNullable) add(JsonPrimitive(d.getElementName(i)))
            }
        }
        description(d.annotations)?.let { put("description", it) }
    }

    fun named(d: SerialDescriptor, build: () -> JsonObject): JsonObject {
        val name = defName(d)
        if (name in defs || name in inProgress) return ref(name)
        inProgress += name
        defs[name] = build()
        inProgress -= name
        return ref(name)
    }

    schema = { d ->
        when (val kind: SerialKind = d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> buildJsonObject { put("type", "string") }
            PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                buildJsonObject { put("type", "integer") }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> buildJsonObject { put("type", "number") }
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { d.elementNames.forEach { add(JsonPrimitive(it)) } }
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schema(d.getElementDescriptor(0)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schema(d.getElementDescriptor(1)))
            }
            StructureKind.CLASS -> if (d === root) objectSchema(d, null) else named(d) { objectSchema(d, null) }
            StructureKind.OBJECT -> buildJsonObject { put("type", "object") }
            PolymorphicKind.SEALED -> named(d) {
                // Element 1 of a sealed descriptor holds one descriptor per subclass.
                val variants = d.getElementDescriptor(1).elementDescriptors
                buildJsonObject {
                    put("oneOf", JsonArray(variants.map { v -> objectSchema(v, v.serialName) }))
                    description(d.annotations)?.let { put("description", it) }
                }
            }
            else -> buildJsonObject { } // open polymorphism / contextual: accept anything
        }
    }

    val body = if (root.kind == StructureKind.CLASS) objectSchema(root, null) else schema(root)
    return JsonObject(buildMap {
        putAll(body)
        if (defs.isNotEmpty()) put("\$defs", JsonObject(defs))
    })
}
