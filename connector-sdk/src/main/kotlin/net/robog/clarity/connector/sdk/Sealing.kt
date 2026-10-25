package net.robog.clarity.connector.sdk

import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.internal.HpkeContext
import com.google.crypto.tink.hybrid.internal.HpkeKemPrivateKey
import com.google.crypto.tink.hybrid.internal.HpkePrimitiveFactory
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.util.Bytes
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.GeneralSecurityException
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing

/** A [SealedValue] could not be opened: wrong key, wrong connector or field, or damaged ciphertext. */
class SealingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private val b64 = Base64.getUrlEncoder().withoutPadding()
private val b64d = Base64.getUrlDecoder()

/**
 * The connector's X25519 key pair for opening secret config values (see [Sealing]). The private
 * key never leaves the connector's state directory; only [publicKey] is published, in the
 * manifest's `sealingKey`.
 */
class SealingKeys private constructor(private val privateKey: ByteArray) {
    private val publicKeyBytes: ByteArray = X25519.publicFromPrivate(privateKey)

    /** Raw 32-byte public key, base64url without padding, as `ConnectorManifest.sealingKey` expects. */
    val publicKey: String get() = b64.encodeToString(publicKeyBytes)

    /**
     * Opens [sealed] for the config field [field] of connector [connectorId].
     *
     * @throws SealingException if the value was sealed for another key, connector or field, or was altered.
     */
    fun open(sealed: SealedValue, connectorId: String, field: String): String {
        if (sealed.algorithm != Sealing.ALGORITHM) throw SealingException("unsupported sealing algorithm '${sealed.algorithm}'")
        val bytes = try { b64d.decode(sealed.ciphertext) } catch (e: IllegalArgumentException) { throw SealingException("ciphertext is not base64url", e) }
        if (bytes.size <= Sealing.ENC_LENGTH) throw SealingException("ciphertext is too short")
        val enc = bytes.copyOfRange(0, Sealing.ENC_LENGTH)
        val ciphertext = bytes.copyOfRange(Sealing.ENC_LENGTH, bytes.size)
        return try {
            val context = HpkeContext.createRecipientContext(
                enc,
                HpkeKemPrivateKey(Bytes.copyFrom(privateKey), Bytes.copyFrom(publicKeyBytes)),
                HpkePrimitiveFactory.createKem(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256),
                HpkePrimitiveFactory.createKdf(HpkeParameters.KdfId.HKDF_SHA256),
                HpkePrimitiveFactory.createAead(HpkeParameters.AeadId.AES_256_GCM),
                (Sealing.INFO_PREFIX + connectorId).toByteArray(Charsets.UTF_8),
            )
            String(context.open(ciphertext, field.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw SealingException("could not open the sealed value for '$field' (sealed for another key, connector or field?)", e)
        }
    }

    companion object {
        const val FILE_NAME = "sealing-key"

        fun generate(): SealingKeys = SealingKeys(X25519.generatePrivateKey())

        /** Reads the key pair from `<dir>/sealing-key`, creating it (owner-only permissions) on first start. */
        fun loadOrCreate(dir: Path): SealingKeys {
            val file = dir.resolve(FILE_NAME)
            if (file.exists()) {
                val raw = b64d.decode(file.readText().trim())
                require(raw.size == 32) { "$file does not hold a 32-byte X25519 key" }
                return SealingKeys(raw)
            }
            Files.createDirectories(dir)
            val keys = generate()
            val tmp = Files.createTempFile(dir, "sealing-", ".tmp")
            try {
                try { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) } catch (_: UnsupportedOperationException) { }
                Files.writeString(tmp, b64.encodeToString(keys.privateKey) + "\n")
                Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(tmp)
            }
            return keys
        }
    }
}

/**
 * The plaintext of secret config field [name], or null when it is not set.
 *
 * Secrets must arrive sealed (a `{"algorithm", "ciphertext"}` object); a plain string is refused so
 * a password can never sit in the backend's database in the clear by accident.
 *
 * @throws SealingException if the value is not sealed or cannot be opened.
 */
fun ConnectorConfig.secret(name: String, keys: SealingKeys, connectorId: String): String? {
    val raw = values[name] ?: return null
    if (raw is JsonPrimitive && raw.isString && raw.content.isEmpty()) return null
    val sealed = try {
        ClarityJson.decodeFromJsonElement(SealedValue.serializer(), raw as? JsonObject ?: throw SealingException("'$name' is not sealed"))
    } catch (e: kotlinx.serialization.SerializationException) {
        throw SealingException("'$name' is not a sealed value", e)
    }
    return keys.open(sealed, connectorId, name)
}
