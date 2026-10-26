package net.robog.clarity.backend.governance

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verification of decisions signed on the phone with the device's approval key (ES256: ECDSA over
 * P-256 with SHA-256, DER signature). Pure.
 */
object PhoneApprovals {
    private val unb64 = Base64.getUrlDecoder()

    /** True when [signature] (base64url DER) is a valid signature of [payload] by [publicKey] (base64url SPKI). Never throws. */
    fun verify(publicKey: String, payload: String, signature: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64.decode(publicKey)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload.toByteArray(Charsets.UTF_8))
            verify(unb64.decode(signature))
        }
    } catch (_: Exception) {
        false
    }

    /** What is stored to refuse a replay: a hash, never the signature itself. */
    fun signatureHash(signature: String): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
}
