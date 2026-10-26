package net.robog.clarity.connector.sdk

import com.google.crypto.tink.hybrid.internal.TestSealer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.Sealing

private fun sealed(keys: SealingKeys, field: String, plain: String): JsonElement {
    val s = TestSealer.seal(keys.publicKey, "mail", field, plain)
    return buildJsonObject { put("type", "sealed"); put("algorithm", s.algorithm); put("ciphertext", s.ciphertext) }
}

private fun item(id: String, vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf("id" to JsonPrimitive(id)) + fields)

private fun collection(vararg items: JsonObject) = ConnectorConfig(1, JsonObject(mapOf("accounts" to JsonArray(items.toList()))))

class ConfigItemsTest : FunSpec({
    val keys = SealingKeys.generate()
    val runtime = ConnectorRuntime("mail", keys)

    test("items of a collection keep their order and skip entries without an id") {
        val config = collection(item("a1", "label" to JsonPrimitive("Work")), JsonObject(mapOf("label" to JsonPrimitive("no id"))), item("b2"))
        config.items("accounts").map { it.id } shouldBe listOf("a1", "b2")
        config.items("accounts")[0].label shouldBe "Work"
        config.items("accounts")[1].label shouldBe "b2"
    }

    test("a flat config reads as one legacy item, an empty config as none") {
        val flat = ConnectorConfig(1, JsonObject(mapOf("username" to JsonPrimitive("me"))))
        flat.items("accounts").single().let { it.id to it.legacy } shouldBe ("default" to true)
        ConnectorConfig(1, JsonObject(emptyMap())).items("accounts") shouldBe emptyList()
        collection().items("accounts") shouldBe emptyList()
    }

    test("item properties read as text, number and list") {
        val it = item(
            "a1", "host" to JsonPrimitive(" h "), "port" to JsonPrimitive("993"), "n" to JsonPrimitive(7),
            "folders" to JsonArray(listOf(JsonPrimitive("INBOX"), JsonPrimitive(" "))), "csv" to JsonPrimitive("a, b"),
        ).let { ConfigItem("accounts", "a1", it) }
        it.text("host") shouldBe "h"
        it.int("port") shouldBe 993
        it.int("n") shouldBe 7
        it.list("folders") shouldBe listOf("INBOX")
        it.list("csv") shouldBe listOf("a", "b")
        it.list("missing") shouldBe emptyList()
    }

    test("an item secret opens only under its own collection, item id and property") {
        val config = collection(
            item("a1", "password" to sealed(keys, Sealing.itemField("accounts", "a1", "password"), "pw1")),
            item("a2", "password" to sealed(keys, Sealing.itemField("accounts", "a1", "password"), "pw1")),
        )
        config.itemSecret("accounts", "a1", "password", keys, "mail") shouldBe "pw1"
        shouldThrow<SealingException> { config.itemSecret("accounts", "a2", "password", keys, "mail") }
        runtime.secret(config, config.items("accounts")[0], "password") shouldBe "pw1"
        config.itemSecret("accounts", "gone", "password", keys, "mail") shouldBe null
    }

    test("a legacy item's secret is sealed under the plain property name") {
        val config = ConnectorConfig(1, JsonObject(mapOf("password" to sealed(keys, "password", "pw"))))
        runtime.secret(config, config.items("accounts").single(), "password") shouldBe "pw"
    }

    test("a scoped store keeps its keys apart") {
        val base = InMemoryStateStore()
        val a = base.scoped("a1")
        val b = base.scoped("a2")
        a.put("k", JsonPrimitive(1)); b.put("k", JsonPrimitive(2))
        a.get("k") shouldBe JsonPrimitive(1)
        b.get("k") shouldBe JsonPrimitive(2)
        a.keys() shouldBe setOf("k")
        base.keys() shouldBe setOf("a1/k", "a2/k")
        a.remove("k")
        a.get("k") shouldBe null
        b.get("k") shouldBe JsonPrimitive(2)
    }

    test("account tests combine: failing accounts first, ok only if all are, counts summed") {
        val result = combineAccountTests(listOf(
            "Work" to AccountTest(true, "4,218 messages", mapOf("messages" to 4218)),
            "Personal" to AccountTest(false, "password rejected"),
            "Home" to AccountTest(true, "312 messages", mapOf("messages" to 312)),
        ))
        result.ok shouldBe false
        result.message shouldBe "Personal: password rejected · Work: 4,218 messages · Home: 312 messages"
        result.counts shouldBe mapOf("messages" to 4530)
        combineAccountTests(emptyList()).ok shouldBe false
    }

    test("runIsolated turns an exception into a failure") {
        runIsolated { error("boom") }.exceptionOrNull()!!.message shouldBe "boom"
        runIsolated { 3 }.getOrNull() shouldBe 3
    }
})
