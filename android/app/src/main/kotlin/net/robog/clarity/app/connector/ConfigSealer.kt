package net.robog.clarity.app.connector

import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.internal.HpkeEncrypt
import com.google.crypto.tink.util.Bytes
import java.util.Base64
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing

/** Why a value could not be sealed. */
class SealingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Encrypts secret connector config values so only the connector can open them (see [Sealing]). */
interface ConfigSealer {
    /**
     * @param sealingKey the connector's `ConnectorManifest.sealingKey` (raw X25519 key, base64url).
     * @throws SealingException if the key is malformed.
     */
    fun seal(connectorId: ConnectorId, sealingKey: String, plaintext: String): SealedValue
}

/**
 * HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM through Google Tink.
 *
 * Tink's public `HybridEncrypt` API exposes the HPKE `info` but not the AEAD `aad`, so the field
 * name cannot be bound as `Sealing` describes; the value is bound to the connector through `info`.
 */
class TinkConfigSealer : ConfigSealer {
    override fun seal(connectorId: ConnectorId, sealingKey: String, plaintext: String): SealedValue {
        val raw = try {
            Base64.getUrlDecoder().decode(sealingKey)
        } catch (e: IllegalArgumentException) {
            throw SealingException("sealing key is not base64url", e)
        }
        if (raw.size != Sealing.ENC_LENGTH) throw SealingException("sealing key must be 32 bytes, got ${raw.size}")
        return try {
            val publicKey = HpkePublicKey.create(PARAMETERS, Bytes.copyFrom(raw), null)
            val out = HpkeEncrypt.create(publicKey).encrypt(plaintext.toByteArray(Charsets.UTF_8), info(connectorId))
            SealedValue(Sealing.ALGORITHM, Base64.getUrlEncoder().withoutPadding().encodeToString(out))
        } catch (e: java.security.GeneralSecurityException) {
            throw SealingException("could not seal value", e)
        }
    }

    companion object {
        /** HPKE `info`: `"clarity-connector-config-v1:<connectorId>"` (UTF-8). */
        fun info(connectorId: ConnectorId): ByteArray = "${Sealing.INFO_PREFIX}$connectorId".toByteArray(Charsets.UTF_8)

        val PARAMETERS: HpkeParameters = HpkeParameters.builder()
            .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
            .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
            .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
            .setVariant(HpkeParameters.Variant.NO_PREFIX)
            .build()
    }
}
