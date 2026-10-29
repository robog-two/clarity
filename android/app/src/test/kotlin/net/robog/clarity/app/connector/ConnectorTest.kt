package net.robog.clarity.app.connector

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.internal.HpkeDecrypt
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing

private fun schema(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private val MAIL_SCHEMA = schema(
    """
    {
      "type": "object",
      "required": ["host", "username", "password"],
      "properties": {
        "host": {"type": "string", "title": "IMAP host"},
        "port": {"type": "integer", "minimum": 1, "maximum": 65535, "default": 993},
        "username": {"type": "string", "format": "email"},
        "password": {"type": "string", "format": "password"},
        "token": {"type": "string", "writeOnly": true},
        "calendar_url": {"type": "string", "format": "uri"},
        "use_tls": {"type": "boolean", "default": true},
        "timeout_s": {"type": "number", "minimum": 0.5},
        "nested": {"type": "object", "properties": {"a": {"type": "string"}}},
        "tags": {"type": "array"},
        "mode": {"type": "string", "enum": ["a", "b"], "default": "a"}
      }
    }
    """,
)

class ConfigFormTest {
    private val form = ConfigForms.parse(MAIL_SCHEMA)

    @Test fun mapsSchemaPropertiesToFields() {
        val byKey = form.fields.associateBy { it.key }
        assertEquals(listOf("host", "port", "username", "password", "token", "calendar_url", "use_tls", "timeout_s", "mode"), form.fields.map { it.key })
        assertEquals("IMAP host", byKey["host"]!!.label)
        assertEquals(FieldKind.TEXT, byKey["host"]!!.kind)
        assertEquals(FieldKind.NUMBER, byKey["port"]!!.kind)
        assertTrue(byKey["port"]!!.integer)
        assertEquals(FieldKind.EMAIL, byKey["username"]!!.kind)
        assertEquals(FieldKind.PASSWORD, byKey["password"]!!.kind)
        assertEquals(FieldKind.PASSWORD, byKey["token"]!!.kind, "writeOnly means secret")
        assertEquals(FieldKind.URL, byKey["calendar_url"]!!.kind)
        assertEquals(FieldKind.BOOLEAN, byKey["use_tls"]!!.kind)
        assertTrue(byKey["host"]!!.required && !byKey["port"]!!.required)
        assertTrue(byKey["password"]!!.secret && !byKey["host"]!!.secret)
    }

    @Test fun nestedObjectsAndUntypedArraysAreSkipped() {
        assertTrue(form.fields.none { it.key == "nested" || it.key == "tags" })
    }

    @Test fun emptySchemaIsAnEmptyForm() {
        assertEquals(emptyList(), ConfigForms.parse(JsonObject(emptyMap())).fields)
    }

    @Test fun defaultsPrefillTheForm() {
        assertEquals(mapOf("port" to "993", "use_tls" to "true", "mode" to "a"), form.initialValues().texts)
    }

    @Test fun validation() {
        val f = form.fields.associateBy { it.key }
        assertEquals(FieldError.Required, ConfigForms.validate(f["host"]!!, "  "))
        assertNull(ConfigForms.validate(f["token"]!!, ""))
        assertEquals(FieldError.InvalidEmail, ConfigForms.validate(f["username"]!!, "sam"))
        assertNull(ConfigForms.validate(f["username"]!!, "sam@home.local"))
        assertEquals(FieldError.InvalidUrl, ConfigForms.validate(f["calendar_url"]!!, "not a url"))
        assertEquals(FieldError.InvalidUrl, ConfigForms.validate(f["calendar_url"]!!, "home.local/dav"))
        assertNull(ConfigForms.validate(f["calendar_url"]!!, "https://home.local/dav/"))
        assertEquals(FieldError.NotANumber, ConfigForms.validate(f["port"]!!, "abc"))
        assertEquals(FieldError.NotAWholeNumber, ConfigForms.validate(f["port"]!!, "99.5"))
        assertEquals(FieldError.TooSmall(1.0), ConfigForms.validate(f["port"]!!, "0"))
        assertEquals(FieldError.TooLarge(65535.0), ConfigForms.validate(f["port"]!!, "70000"))
        assertNull(ConfigForms.validate(f["port"]!!, "993"))
        assertNull(ConfigForms.validate(f["timeout_s"]!!, "1.5"))
    }

    @Test fun errorsListsOnlyBadFields() {
        val errors = form.errors(FormValues(texts = mapOf("host" to "imap", "username" to "sam@x.y", "password" to "")))
        assertEquals(setOf("password"), errors.keys)
    }

    @Test fun buildsTypedValuesAndSealsSecrets() {
        val values = FormValues(
            texts = mapOf(
                "host" to " imap.home.local ", "port" to "993", "username" to "sam@home.local",
                "password" to "correct-horse", "use_tls" to "false", "timeout_s" to "2.5",
            ),
        )
        val sealed = mutableListOf<Pair<String, String>>()
        val out = form.toConfigValues(values) { field, plaintext ->
            sealed += field to plaintext
            SealedValue(Sealing.ALGORITHM, "CIPHERTEXT")
        }
        assertEquals("imap.home.local", out["host"]!!.jsonPrimitive.content)
        assertEquals(993L, out["port"]!!.jsonPrimitive.long)
        assertEquals(false, out["use_tls"]!!.jsonPrimitive.boolean)
        assertEquals(2.5, out["timeout_s"]!!.jsonPrimitive.content.toDouble())
        assertEquals(listOf("password" to "correct-horse"), sealed, "only filled secrets are sealed")
        val pw = out["password"]!!.jsonObject
        assertEquals("sealed", pw["type"]!!.jsonPrimitive.content)
        assertEquals(Sealing.ALGORITHM, pw["algorithm"]!!.jsonPrimitive.content)
        assertEquals("CIPHERTEXT", pw["ciphertext"]!!.jsonPrimitive.content)
        assertTrue("token" !in out && "calendar_url" !in out, "empty optional fields are omitted")
        assertTrue("correct-horse" !in out.toString(), "the plaintext never appears in the payload")
    }

    @Test fun storedValuesPrefillOnlyNonSecretFields() {
        val stored = JsonObject(
            mapOf(
                "host" to JsonPrimitive("imap.x"),
                "password" to JsonObject(mapOf("type" to JsonPrimitive("sealed"), "ciphertext" to JsonPrimitive("zzz"))),
                "port" to JsonPrimitive(143),
            ),
        )
        val v = form.valuesFromStored(stored)
        assertEquals(mapOf("host" to "imap.x", "port" to "143"), v.texts)
        assertEquals(setOf("password"), v.storedSecrets.keys)
    }
}

class SealingTest {
    private val connector = ConnectorId("imap-personal")
    private val privateKey = X25519.generatePrivateKey()
    private val publicKey = X25519.publicFromPrivate(privateKey)
    private val sealingKey = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)

    private fun open(sealed: SealedValue, info: ByteArray): String {
        val pub = HpkePublicKey.create(TinkConfigSealer.PARAMETERS, Bytes.copyFrom(publicKey), null)
        val priv = HpkePrivateKey.create(pub, SecretBytes.copyFrom(privateKey, InsecureSecretKeyAccess.get()))
        val raw = Base64.getUrlDecoder().decode(sealed.ciphertext)
        return String(HpkeDecrypt.create(priv).decrypt(raw, info), Charsets.UTF_8)
    }

    @Test fun sealedValueOpensWithTheConnectorsPrivateKey() {
        val sealed = TinkConfigSealer().seal(connector, "password", sealingKey, "correct-horse-staple")
        assertEquals(Sealing.ALGORITHM, sealed.algorithm)
        assertTrue(!sealed.ciphertext.contains('=') && !sealed.ciphertext.contains('+') && !sealed.ciphertext.contains('/'))
        assertEquals("correct-horse-staple", open(sealed, TinkConfigSealer.info(connector, "password")))
    }

    @Test fun layoutIsEncapsulatedKeyThenCiphertext() {
        val sealed = TinkConfigSealer().seal(connector, "password", sealingKey, "pw")
        val raw = Base64.getUrlDecoder().decode(sealed.ciphertext)
        // enc (32) + AES-GCM ciphertext (2 bytes) + tag (16)
        assertEquals(Sealing.ENC_LENGTH + 2 + 16, raw.size)
    }

    @Test fun sealingTwiceGivesDifferentCiphertexts() {
        val sealer = TinkConfigSealer()
        assertTrue(sealer.seal(connector, "password", sealingKey, "pw").ciphertext != sealer.seal(connector, "password", sealingKey, "pw").ciphertext)
    }

    @Test fun aValueSealedForOneConnectorDoesNotOpenForAnother() {
        val sealed = TinkConfigSealer().seal(connector, "password", sealingKey, "pw")
        assertFailsWith<java.security.GeneralSecurityException> { open(sealed, TinkConfigSealer.info(ConnectorId("dav-personal"), "password")) }
    }

    @Test fun infoFollowsTheSharedScheme() {
        assertEquals("clarity-connector-config-v1:imap-personal:password", String(TinkConfigSealer.info(connector, "password")))
    }

    @Test fun aValueSealedForOneFieldDoesNotOpenForAnother() {
        val sealed = TinkConfigSealer().seal(connector, "password", sealingKey, "pw")
        assertFailsWith<java.security.GeneralSecurityException> { open(sealed, TinkConfigSealer.info(connector, "username")) }
    }

    @Test fun malformedKeysAreRejected() {
        val sealer = TinkConfigSealer()
        assertFailsWith<SealingException> { sealer.seal(connector, "password", "not base64 !!", "pw") }
        assertFailsWith<SealingException> { sealer.seal(connector, "password", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16)), "pw") }
    }
}
