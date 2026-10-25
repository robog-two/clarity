package com.google.crypto.tink.hybrid.internal

import com.google.crypto.tink.hybrid.HpkeParameters
import java.util.Base64
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing

/**
 * The app's side of config sealing, for tests. It lives in Tink's package because Tink's HPKE
 * sender context (the only public-API-free way to pass AAD) is package-private there.
 */
object TestSealer {
    fun seal(publicKeyBase64Url: String, connectorId: String, field: String, plaintext: String): SealedValue {
        val publicKey = Base64.getUrlDecoder().decode(publicKeyBase64Url)
        val context = HpkeContext.createSenderContext(
            publicKey,
            HpkePrimitiveFactory.createKem(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256),
            HpkePrimitiveFactory.createKdf(HpkeParameters.KdfId.HKDF_SHA256),
            HpkePrimitiveFactory.createAead(HpkeParameters.AeadId.AES_256_GCM),
            (Sealing.INFO_PREFIX + connectorId).toByteArray(Charsets.UTF_8),
        )
        val ciphertext = context.seal(plaintext.toByteArray(Charsets.UTF_8), field.toByteArray(Charsets.UTF_8))
        return SealedValue(Sealing.ALGORITHM, Base64.getUrlEncoder().withoutPadding().encodeToString(context.encapsulatedKey + ciphertext))
    }
}
