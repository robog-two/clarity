package net.robog.clarity.connector.sdk

import com.google.crypto.tink.hybrid.internal.TestSealer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.SealedValue

private fun configOf(vararg fields: Pair<String, SealedValue>) = ConnectorConfig(
    1,
    JsonObject(fields.associate { (k, v) -> k to ClarityJson.encodeToJsonElement(SealedValue.serializer(), v) } + ("host" to JsonPrimitive("imap.example.com"))),
)

class SealingTest : FunSpec({
    val keys = SealingKeys.generate()

    test("a value sealed by the app opens in the connector") {
        val sealed = TestSealer.seal(keys.publicKey, "mail", "password", "hunter2 ✓")
        configOf("password" to sealed).secret("password", keys, "mail") shouldBe "hunter2 ✓"
        sealed.ciphertext shouldNotContain "hunter2"
    }

    test("the public key is 32 raw bytes, base64url without padding") {
        Base64.getUrlDecoder().decode(keys.publicKey).size shouldBe 32
        keys.publicKey shouldNotContain "="
    }

    test("a value sealed for another connector, field or key does not open") {
        val sealed = TestSealer.seal(keys.publicKey, "mail", "password", "pw")
        shouldThrow<SealingException> { keys.open(sealed, "dav", "password") }
        shouldThrow<SealingException> { keys.open(sealed, "mail", "smtpPassword") }
        shouldThrow<SealingException> { SealingKeys.generate().open(sealed, "mail", "password") }
    }

    test("tampered, truncated and foreign-algorithm values are refused") {
        val sealed = TestSealer.seal(keys.publicKey, "mail", "password", "pw")
        val bytes = Base64.getUrlDecoder().decode(sealed.ciphertext)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        shouldThrow<SealingException> { keys.open(sealed.copy(ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)), "mail", "password") }
        shouldThrow<SealingException> { keys.open(sealed.copy(ciphertext = "AAAA"), "mail", "password") }
        shouldThrow<SealingException> { keys.open(sealed.copy(algorithm = "rot13"), "mail", "password") }
    }

    test("unset secrets are null; plaintext in a secret field is refused") {
        configOf().secret("password", keys, "mail") shouldBe null
        shouldThrow<SealingException> { configOf().secret("host", keys, "mail") }
    }

    test("the key pair is created once and reloaded from the state dir") {
        val dir = Files.createTempDirectory("sealing")
        val first = SealingKeys.loadOrCreate(dir)
        val second = SealingKeys.loadOrCreate(dir)
        second.publicKey shouldBe first.publicKey
        val sealed = TestSealer.seal(first.publicKey, "mail", "password", "pw")
        second.open(sealed, "mail", "password") shouldBe "pw"
    }
})
