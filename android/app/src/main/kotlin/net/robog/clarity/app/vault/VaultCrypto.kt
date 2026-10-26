package net.robog.clarity.app.vault

import com.google.crypto.tink.subtle.Hkdf
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
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
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.vault.VaultBody
import net.robog.clarity.shared.vault.VaultEnvelope
import net.robog.clarity.shared.vault.VaultSealing

/**
 * The phone's side of the vault envelope scheme documented on [VaultEnvelope]: ECDH-ES on P-256
 * with an ephemeral key, HKDF-SHA256 (empty salt, `info` = [VaultSealing.info]), AES-256-GCM with
 * `info` as AAD. [seal] needs only the recipient's public key; [open] takes the ECDH step as a
 * function so the private key can stay in the Android Keystore.
 *
 * Plaintext secrets are only ever handled here and in the form state; nothing is logged.
 */
object VaultCrypto {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()
    private val random = SecureRandom()

    /** Seals [fields] to the device whose vault key is [devicePublicKeySpki] (base64url SPKI), bound to [context]. */
    fun seal(device: DeviceId, devicePublicKeySpki: String, context: String, fields: Map<String, String>): VaultEnvelope {
        val recipient = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64.decode(devicePublicKeySpki)))
        val ephemeral = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val secret = KeyAgreement.getInstance("ECDH").apply { init(ephemeral.private); doPhase(recipient, true) }.generateSecret()
        val info = VaultSealing.info(context, device).toByteArray()
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, aesKey(secret, info), GCMParameterSpec(128, iv))
            updateAAD(info)
        }
        val plain = ClarityJson.encodeToString(VaultBody.serializer(), VaultBody(fields)).toByteArray()
        return VaultEnvelope(
            deviceId = device,
            epk = b64.encodeToString(uncompressed(ephemeral.public as ECPublicKey)),
            iv = b64.encodeToString(iv),
            ciphertext = b64.encodeToString(cipher.doFinal(plain)),
            context = context,
        )
    }

    /** The sender's ephemeral key of [envelope], for the key-agreement step. */
    fun ephemeralKey(envelope: VaultEnvelope): PublicKey = fromUncompressed(unb64.decode(envelope.epk))

    /** Opens [envelope]; [agree] performs ECDH of the device's private key with the ephemeral key. */
    fun open(envelope: VaultEnvelope, agree: (PublicKey) -> ByteArray): Map<String, String> {
        require(envelope.alg == VaultSealing.ALGORITHM) { "unsupported envelope algorithm" }
        val secret = agree(ephemeralKey(envelope))
        val info = VaultSealing.info(envelope.context, envelope.deviceId).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, aesKey(secret, info), GCMParameterSpec(128, unb64.decode(envelope.iv)))
            updateAAD(info)
        }
        val plain = cipher.doFinal(unb64.decode(envelope.ciphertext))
        return ClarityJson.decodeFromString(VaultBody.serializer(), String(plain)).fields
    }

    private fun aesKey(secret: ByteArray, info: ByteArray) =
        SecretKeySpec(Hkdf.computeHkdf("HmacSha256", secret, ByteArray(0), info, 32), "AES")

    private fun uncompressed(key: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray = v.toByteArray().let { b ->
            val trimmed = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            ByteArray(32 - trimmed.size) + trimmed
        }
        return byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun fromUncompressed(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0].toInt() == 4) { "not an uncompressed P-256 point" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }
}
