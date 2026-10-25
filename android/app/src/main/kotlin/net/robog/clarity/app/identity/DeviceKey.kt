package net.robog.clarity.app.identity

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The device's signing identity. Private key material never leaves the implementation. */
interface DeviceKey {
    /** SPKI DER public key, base64url without padding. Creates the key on first use. */
    fun publicKeySpki(): String

    /** Signs [data] with ECDSA/SHA-256 and returns the JOSE (R||S, 64 byte) signature. */
    fun signJose(data: ByteArray): ByteArray

    /** Deletes the key (used when forgetting the instance). */
    fun delete()
}

/** EC P-256 key in the AndroidKeyStore: non-exportable, SIGN only, StrongBox when available. */
class AndroidKeystoreDeviceKey : DeviceKey {

    @Synchronized
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun ensureKey(): KeyStore {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) generate(strongBox = hasStrongBox())
        return ks
    }

    private fun hasStrongBox(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
                .apply { initialize(spec) }
                .generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) generate(strongBox = false) else throw e
        }
    }

    override fun publicKeySpki(): String =
        base64UrlEncode(ensureKey().getCertificate(ALIAS).publicKey.encoded)

    override fun signJose(data: ByteArray): ByteArray {
        val key = ensureKey().getKey(ALIAS, null) as PrivateKey
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
        return EcdsaSignature.derToJose(der)
    }

    @Synchronized
    override fun delete() {
        keyStore().deleteEntry(ALIAS)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "clarity-device"
    }
}
