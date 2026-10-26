package net.robog.clarity.backend.vault

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.VaultEnvelope

/**
 * The shared test vector (`vault-envelope-vector.json`, copied to the Android app's test
 * resources): an envelope sealed by [VaultCryptoReference] that the phone side must open to the same
 * fields. Run with the environment variable `CLARITY_WRITE_VECTOR=<path>` to regenerate it.
 */
class VaultEnvelopeVectorTest : FunSpec({
    test("the committed vector opens with the reference implementation") {
        System.getenv("CLARITY_WRITE_VECTOR")?.let { target ->
            val pair = VaultCryptoReference.newDeviceKey()
            val fields = mapOf("username" to "dana.vault@example.com", "password" to "hunter2!-Zebra")
            val device = DeviceId("11111111-1111-1111-1111-111111111111")
            val envelope = VaultCryptoReference.seal(device, VaultCryptoReference.publicKeyWire(pair), "req-vector-1", fields)
            val out = buildJsonObject {
                put("devicePrivateKeyPkcs8", JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(pair.private.encoded)))
                put("devicePublicKeySpki", JsonPrimitive(VaultCryptoReference.publicKeyWire(pair)))
                put("fields", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
                put("envelope", ClarityJson.encodeToJsonElement(VaultEnvelope.serializer(), envelope))
            }
            File(target).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out))
        }
        val text = File("src/test/resources/vault-envelope-vector.json").readText()
        val vector = ClarityJson.parseToJsonElement(text).jsonObject
        val key = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(vector.getValue("devicePrivateKeyPkcs8").jsonPrimitive.content)),
        )
        val envelope = ClarityJson.decodeFromJsonElement(VaultEnvelope.serializer(), vector.getValue("envelope"))
        val expected = vector.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content }
        VaultCryptoReference.open(envelope, key) shouldBe expected
    }
})
