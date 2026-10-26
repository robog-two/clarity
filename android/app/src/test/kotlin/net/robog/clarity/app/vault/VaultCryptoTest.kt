package net.robog.clarity.app.vault

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import net.robog.clarity.app.testutil.newKeyPair
import net.robog.clarity.app.testutil.spki
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.VaultEnvelope
import net.robog.clarity.shared.vault.VaultSealing
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VaultCryptoTest {
    private val device = DeviceId("11111111-1111-1111-1111-111111111111")
    private val fields = mapOf("username" to "dana@example.com", "password" to "hunter2!-Zebra")

    @Test fun sealedEnvelopeOpensWithTheDeviceKey() {
        val pair = newKeyPair()
        val envelope = VaultCrypto.seal(device, spki(pair), "req-1", fields)
        assertEquals(VaultSealing.ALGORITHM, envelope.alg)
        assertEquals("req-1", envelope.context)
        assertEquals(fields, openWith(pair.private, envelope))
    }

    @Test fun envelopeCarriesNoPlaintext() {
        val envelope = VaultCrypto.seal(device, spki(newKeyPair()), "req-1", fields)
        val wire = ClarityJson.encodeToString(VaultEnvelope.serializer(), envelope)
        assertFalse("hunter2" in wire)
        assertFalse("dana@example.com" in wire)
    }

    @Test fun eachSealUsesFreshKeysAndNonce() {
        val pair = newKeyPair()
        val a = VaultCrypto.seal(device, spki(pair), "req-1", fields)
        val b = VaultCrypto.seal(device, spki(pair), "req-1", fields)
        assertFalse(a.epk == b.epk)
        assertFalse(a.iv == b.iv)
    }

    @Test fun envelopeIsBoundToItsRequestAndDevice() {
        val pair = newKeyPair()
        val envelope = VaultCrypto.seal(device, spki(pair), "req-1", fields)
        assertFailsWith<Exception> { openWith(pair.private, envelope.copy(context = "req-2")) }
        assertFailsWith<Exception> { openWith(pair.private, envelope.copy(deviceId = DeviceId("other"))) }
    }

    @Test fun anotherKeyCannotOpenIt() {
        val envelope = VaultCrypto.seal(device, spki(newKeyPair()), "req-1", fields)
        assertFailsWith<Exception> { openWith(newKeyPair().private, envelope) }
    }

    @Test fun tamperedCiphertextIsRefused() {
        val pair = newKeyPair()
        val envelope = VaultCrypto.seal(device, spki(pair), "req-1", fields)
        val bytes = Base64.getUrlDecoder().decode(envelope.ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad = envelope.copy(ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        assertFailsWith<Exception> { openWith(pair.private, bad) }
    }

    /** The backend's reference implementation sealed this (shared vector): the phone must open it byte for byte. */
    @Test fun opensTheBackendReferenceVector() {
        val vector = ClarityJson.parseToJsonElement(
            javaClass.getResource("/vault-envelope-vector.json")!!.readText(),
        ).jsonObject
        val key = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(vector.getValue("devicePrivateKeyPkcs8").jsonPrimitive.content)),
        )
        val envelope = ClarityJson.decodeFromJsonElement(VaultEnvelope.serializer(), vector.getValue("envelope"))
        val expected = vector.getValue("fields").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(expected, openWith(key, envelope))
    }

    /** An independent HKDF (RFC 5869) and AES-GCM opener written straight from the documented scheme. */
    @Test fun matchesTheDocumentedSchemeWithAnIndependentOpener() {
        val pair = newKeyPair()
        val envelope = VaultCrypto.seal(device, spki(pair), "req-9", fields)
        val shared = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(VaultCrypto.ephemeralKey(envelope), true) }.generateSecret()
        val info = VaultSealing.info("req-9", device).toByteArray()
        val prk = hmac(ByteArray(32), shared)
        val key = hmac(prk, info + byteArrayOf(1))
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, Base64.getUrlDecoder().decode(envelope.iv)))
            updateAAD(info)
        }
        val plain = String(cipher.doFinal(Base64.getUrlDecoder().decode(envelope.ciphertext)))
        assertEquals(fields, ClarityJson.decodeFromString(net.robog.clarity.shared.vault.VaultBody.serializer(), plain).fields)
    }

    private fun hmac(key: ByteArray, data: ByteArray) =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun openWith(private: java.security.PrivateKey, envelope: VaultEnvelope) = VaultCrypto.open(envelope) { ephemeral ->
        KeyAgreement.getInstance("ECDH").apply { init(private); doPhase(ephemeral, true) }.generateSecret()
    }
}
