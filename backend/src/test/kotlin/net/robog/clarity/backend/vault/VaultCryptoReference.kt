package net.robog.clarity.backend.vault

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec
import kotlinx.serialization.json.Json
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.VaultBody
import net.robog.clarity.shared.vault.VaultEnvelope
import net.robog.clarity.shared.vault.VaultSealing

/**
 * A reference implementation of the envelope scheme documented on [VaultEnvelope], on the JDK alone
 * (ECDH P-256, HKDF-SHA256, AES-256-GCM). The phone does the same with the Android Keystore for the
 * device key; this is what tests use to play the phone, and what proves the documented scheme is complete.
 */
object VaultCryptoReference {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val random = SecureRandom()

    fun newDeviceKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun publicKeyWire(pair: KeyPair): String = b64.encodeToString(pair.public.encoded)

    private fun uncompressed(key: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray = v.toByteArray().let { b ->
            val trimmed = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            ByteArray(32 - trimmed.size) + trimmed
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun fromUncompressed(bytes: ByteArray, params: ECParameterSpec): PublicKey {
        require(bytes.size == 65 && bytes[0].toInt() == 4) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    private fun key(secret: ByteArray, info: ByteArray) =
        KDF.getInstance("HKDF-SHA256").deriveKey("AES", HKDFParameterSpec.ofExtract().addIKM(secret).thenExpand(info, 32))

    fun seal(device: DeviceId, devicePublicKey: String, context: String, fields: Map<String, String>, json: Json = ClarityJson): VaultEnvelope {
        val recipient = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64.decode(devicePublicKey)))
        val ephemeral = newDeviceKey()
        val secret = KeyAgreement.getInstance("ECDH").apply { init(ephemeral.private); doPhase(recipient, true) }.generateSecret()
        val info = VaultSealing.info(context, device).toByteArray()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(secret, info), GCMParameterSpec(128, iv))
            updateAAD(info)
        }
        val ciphertext = cipher.doFinal(json.encodeToString(VaultBody.serializer(), VaultBody(fields)).toByteArray())
        return VaultEnvelope(
            deviceId = device,
            epk = b64.encodeToString(uncompressed(ephemeral.public as ECPublicKey)),
            iv = b64.encodeToString(iv),
            ciphertext = b64.encodeToString(ciphertext),
            context = context,
        )
    }

    fun open(envelope: VaultEnvelope, devicePrivateKey: PrivateKey, json: Json = ClarityJson): Map<String, String> {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val ephemeral = fromUncompressed(unb64.decode(envelope.epk), params)
        val secret = KeyAgreement.getInstance("ECDH").apply { init(devicePrivateKey); doPhase(ephemeral, true) }.generateSecret()
        val info = VaultSealing.info(envelope.context, envelope.deviceId).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(secret, info), GCMParameterSpec(128, unb64.decode(envelope.iv)))
            updateAAD(info)
        }
        val plain = cipher.doFinal(unb64.decode(envelope.ciphertext))
        return json.decodeFromString(VaultBody.serializer(), String(plain)).fields
    }
}
