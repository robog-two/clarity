package net.robog.clarity.harness

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

@Serializable
private enum class Mode { FAST, SLOW }

@Serializable
private class Sample(
    @Description("the name") val name: String,
    val count: Int,
    val mode: Mode = Mode.FAST,
    val tags: List<String> = emptyList(),
    val note: String? = null,
)

@Serializable
private sealed interface Node {
    @Serializable @kotlinx.serialization.SerialName("leaf")
    data class Leaf(val value: String) : Node

    @Serializable @kotlinx.serialization.SerialName("branch")
    data class Branch(val children: List<Node>) : Node
}

@Serializable
private class Tree(val root: Node)

class JsonSchemaTest : FunSpec({
    test("derives properties, required, enums, arrays and descriptions") {
        val schema = jsonSchemaOf(serializer<Sample>().descriptor)
        schema["type"]?.jsonPrimitive?.content shouldBe "object"
        val props = schema["properties"]!!.jsonObject
        props["name"]!!.jsonObject["description"]?.jsonPrimitive?.content shouldBe "the name"
        props["count"]!!.jsonObject["type"]?.jsonPrimitive?.content shouldBe "integer"
        props["tags"]!!.jsonObject["items"]!!.jsonObject["type"]?.jsonPrimitive?.content shouldBe "string"
        props["mode"]!!.jsonObject["enum"] shouldBe JsonArray(listOf(JsonPrimitive("FAST"), JsonPrimitive("SLOW")))
        // Only properties without defaults and not nullable are required.
        schema["required"] shouldBe JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("count")))
    }

    test("recursive sealed hierarchies terminate via \$defs and carry the discriminator") {
        val schema = jsonSchemaOf(serializer<Tree>().descriptor)
        val defs = schema["\$defs"]!!.jsonObject
        val node = defs["Node"]!!.jsonObject
        val variants = node["oneOf"] as JsonArray
        variants.size shouldBe 2
        variants.toString() shouldContain """"const":"leaf""""
        // The recursive reference points back at the definition instead of expanding forever.
        defs.toString() shouldContain """"${'$'}ref":"#/${'$'}defs/Node""""
        schema["properties"]!!.jsonObject["root"] shouldBe JsonObject(mapOf("\$ref" to JsonPrimitive("#/\$defs/Node")))
    }
})
