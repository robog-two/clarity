package net.robog.clarity.app.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import net.robog.clarity.app.identity.base64UrlEncode

/** Where the two vault keys stand on this phone. */
enum class VaultKeyStatus {
    /** Not created yet (they are made the first time they are needed). */
    MISSING,
    READY,
    /** A new fingerprint or face was enrolled: the keys are unusable and stored credentials cannot be opened. */
    INVALIDATED,
}

/** Thrown when the Keystore refuses a use because the person has not just authenticated. */
class NotAuthenticatedException(cause: Throwable) : Exception("authentication is required", cause)

/** Thrown when the keys were invalidated by a biometric enrollment change. */
class KeysInvalidatedException(cause: Throwable) : Exception("the vault keys were invalidated", cause)

/**
 * The phone's two vault keys, both P-256 in the Android Keystore (StrongBox when available) and
 * both requiring biometric or device-credential authentication:
 *  - the **vault key** (key agreement) that opens sealed credentials;
 *  - the **approval key** (signing) that signs decisions the backend verifies. It requires
 *    authentication for every single signature (the [Signature] goes into a `BiometricPrompt.CryptoObject`).
 *
 * Private keys never leave the Keystore.
 */
interface VaultKeys {
    fun status(): VaultKeyStatus

    /** SPKI DER, base64url, no padding. Creates the key on first use. */
    fun vaultPublicKey(): String
    fun approvalPublicKey(): String

    /** A [Signature] ready to sign, to be authorized by a `BiometricPrompt`. Throws [KeysInvalidatedException]. */
    fun approvalSignature(): Signature

    /**
     * ECDH of the vault key with [ephemeral]. The person must have authenticated within the last few
     * seconds ([AndroidVaultKeys.AUTH_WINDOW_SECONDS]); otherwise [NotAuthenticatedException].
     */
    fun agree(ephemeral: PublicKey): ByteArray

    /** Deletes both keys and makes new ones (after a biometric change). Credentials sealed to the old key are lost. */
    fun reset()
}

class AndroidVaultKeys : VaultKeys {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun ensure(alias: String, purposes: Int, agreement: Boolean) {
        val ks = keyStore()
        if (ks.containsAlias(alias)) return
        try {
            generate(alias, purposes, agreement, strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, purposes, agreement, strongBox = false)
        }
    }

    private fun generate(alias: String, purposes: Int, agreement: Boolean, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, purposes)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .apply { if (!agreement) setDigests(KeyProperties.DIGEST_SHA256) }
            .setUserAuthenticationRequired(true)
            // The approval key needs a fresh authentication for every signature (timeout 0, tied to a CryptoObject).
            // Key agreement cannot be tied to a CryptoObject, so the vault key opens within a few seconds of an
            // authentication; every release is still signed with the per-use approval key.
            .setUserAuthenticationParameters(if (agreement) AUTH_WINDOW_SECONDS else 0, AUTH_TYPES)
            .setInvalidatedByBiometricEnrollment(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private fun ensureVault() = ensure(VAULT_ALIAS, KeyProperties.PURPOSE_AGREE_KEY, agreement = true)
    private fun ensureApproval() = ensure(APPROVAL_ALIAS, KeyProperties.PURPOSE_SIGN, agreement = false)

    private fun publicKey(alias: String): String = base64UrlEncode(keyStore().getCertificate(alias).publicKey.encoded)

    override fun vaultPublicKey(): String { ensureVault(); return publicKey(VAULT_ALIAS) }

    override fun approvalPublicKey(): String { ensureApproval(); return publicKey(APPROVAL_ALIAS) }

    override fun status(): VaultKeyStatus {
        val ks = keyStore()
        if (!ks.containsAlias(APPROVAL_ALIAS) || !ks.containsAlias(VAULT_ALIAS)) return VaultKeyStatus.MISSING
        return try {
            approvalSignature()
            VaultKeyStatus.READY
        } catch (_: KeysInvalidatedException) {
            VaultKeyStatus.INVALIDATED
        }
    }

    override fun approvalSignature(): Signature {
        ensureApproval()
        val key = keyStore().getKey(APPROVAL_ALIAS, null) as PrivateKey
        return try {
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeysInvalidatedException(e)
        }
    }

    override fun agree(ephemeral: PublicKey): ByteArray {
        ensureVault()
        val key = keyStore().getKey(VAULT_ALIAS, null) as PrivateKey
        return try {
            KeyAgreement.getInstance("ECDH", PROVIDER).apply { init(key); doPhase(ephemeral, true) }.generateSecret()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeysInvalidatedException(e)
        } catch (e: UserNotAuthenticatedException) {
            throw NotAuthenticatedException(e)
        }
    }

    @Synchronized
    override fun reset() {
        val ks = keyStore()
        ks.deleteEntry(VAULT_ALIAS)
        ks.deleteEntry(APPROVAL_ALIAS)
        ensureVault()
        ensureApproval()
    }

    companion object {
        const val AUTH_WINDOW_SECONDS = 5
        private const val AUTH_TYPES = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        private const val PROVIDER = "AndroidKeyStore"
        private const val VAULT_ALIAS = "clarity-vault"
        private const val APPROVAL_ALIAS = "clarity-approval"
    }
}
