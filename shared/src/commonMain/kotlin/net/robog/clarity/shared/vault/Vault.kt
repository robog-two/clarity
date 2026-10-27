package net.robog.clarity.shared.vault

import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.DeviceId

/**
 * The credential vault's contracts (see "Credential vault" in `ARCHITECTURE.md`).
 *
 * The backend stores only [CredentialMeta] and [VaultEnvelope]s (ciphertext it cannot open). The
 * secret values live behind the phone's Keystore key. The agent never sees a value: it writes
 * `[secret:<name>_<field>]` placeholders, which the backend's browser wrapper substitutes under a
 * live per-site grant the phone released after a biometric approval.
 */
@Serializable @JvmInline
value class CredentialId(val value: String) { override fun toString(): String = value }

@Serializable @JvmInline
value class CredentialRequestId(val value: String) { override fun toString(): String = value }

@Serializable
enum class CredentialKind {
    @SerialName("login") LOGIN,
    @SerialName("payment_card") PAYMENT_CARD,
    @SerialName("identity") IDENTITY,
    @SerialName("note") NOTE,
}

@Serializable
enum class FieldType {
    @SerialName("username") USERNAME,
    @SerialName("email") EMAIL,
    @SerialName("password") PASSWORD,
    @SerialName("card_number") CARD_NUMBER,
    @SerialName("card_expiry") CARD_EXPIRY,
    @SerialName("card_cvc") CARD_CVC,
    @SerialName("name") NAME,
    @SerialName("address") ADDRESS,
    @SerialName("phone") PHONE,
    @SerialName("otp") OTP,
    @SerialName("text") TEXT,
}

/**
 * One field of a credential. [name] is the lowercase snake_case identifier used in placeholders
 * (`[secret:bank_login_password]` is credential `bank_login`, field `password`); see [VaultNames].
 */
@Serializable
data class FieldSpec(
    val name: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = true,
)

/** Non-secret metadata of a stored credential: all the agent (and the app's list) ever sees. */
@Serializable
data class CredentialMeta(
    val id: CredentialId,
    /** Unique lowercase snake_case handle: the `<credential>` part of placeholders. */
    val name: String,
    val kind: CredentialKind,
    /** The site it is for: a host or URL, e.g. `bank.example.com`. Grants cover its registrable domain. */
    val site: String,
    val purpose: String,
    val fields: List<FieldSpec>,
    /** Non-secret hints for recognition: [VaultNames.HINT_KEYS] (username, brand, last4, expiry_month). */
    val hints: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val lastUsedAt: Instant? = null,
)

/**
 * A credential sealed to one device's vault key. One envelope per credential per device; its
 * plaintext is the JSON [VaultBody] with every field of the credential.
 *
 * Scheme ([VaultSealing.ALGORITHM]): ECDH-ES on P-256 with an ephemeral key, HKDF-SHA256, AES-256-GCM.
 * - [epk]: the sender's ephemeral public key, X9.62 uncompressed point (65 bytes), base64url, no padding.
 * - shared secret: ECDH(ephemeral private, device public key from [DeviceVaultKey.publicKey]).
 * - key: HKDF-SHA256(ikm = shared secret, salt = empty, info = [VaultSealing.info]`(context, deviceId)` UTF-8), 32 bytes.
 * - [iv]: 12 random bytes, base64url. [ciphertext]: AES-256-GCM output (ciphertext || 16-byte tag),
 *   base64url, with the same `info` bytes as AAD.
 * - [context]: the id of the [CredentialRequest] the credential was created from (binds the
 *   ciphertext to one request, so envelopes cannot be swapped between credentials).
 */
@Serializable
data class VaultEnvelope(
    val deviceId: DeviceId,
    val alg: String = VaultSealing.ALGORITHM,
    val epk: String,
    val iv: String,
    val ciphertext: String,
    val context: String,
)

object VaultSealing {
    const val ALGORITHM = "ECDH-ES-P256-HKDF-SHA256-A256GCM"
    const val INFO_PREFIX = "clarity-vault-v1|"
    fun info(context: String, deviceId: DeviceId): String = "$INFO_PREFIX$context|${deviceId.value}"
}

/** The plaintext inside an envelope. */
@Serializable
data class VaultBody(val fields: Map<String, String>)

/** A device's vault key (separate from its API signing key); other devices seal to it. */
@Serializable
data class DeviceVaultKey(
    val deviceId: DeviceId,
    /** SubjectPublicKeyInfo (DER) of the P-256 key-agreement key, base64url, no padding. */
    val publicKey: String,
    val alg: String = VaultSealing.ALGORITHM,
    val createdAt: Instant? = null,
    /** Whether this device has registered an approval signing key (the key itself is never listed). */
    val hasApprovalKey: Boolean = false,
)

/** `POST /api/v1/vault/devices`: the calling device registers (or replaces) its vault public key. */
@Serializable
data class RegisterVaultKey(
    val publicKey: String,
    val alg: String = VaultSealing.ALGORITHM,
    /**
     * The device's separate approval signing key (P-256, SubjectPublicKeyInfo DER, base64url, no
     * padding; ES256 / `SHA256withECDSA`). The backend verifies phone approvals against it
     * (see [net.robog.clarity.shared.governance.SignedDecision]).
     */
    val approvalKey: String? = null,
)

@Serializable
enum class CredentialRequestStatus {
    @SerialName("open") OPEN,
    @SerialName("fulfilled") FULFILLED,
    @SerialName("cancelled") CANCELLED,
}

/**
 * What the agent asked the person to enter (`request_credential`), shown as a form card
 * ([net.robog.clarity.shared.feed.Card.CredentialRequest]). [name] is the handle the resulting
 * credential will have.
 */
@Serializable
data class CredentialRequest(
    val id: CredentialRequestId,
    val name: String,
    val kind: CredentialKind,
    val fields: List<FieldSpec>,
    val site: String,
    val purpose: String,
    val status: CredentialRequestStatus,
    val credentialId: CredentialId? = null,
    val createdAt: Instant,
    /**
     * Why the request closed without the person cancelling it: the same login was saved from another
     * request ("superseded"), or nobody filled it in in time ("expired"). Those arrive as
     * [CredentialRequestStatus.CANCELLED], so apps that know only the three statuses keep working.
     */
    val closedReason: String? = null,
)

/** `POST /api/v1/vault/credentials`: completes a [CredentialRequest]; name, kind, site, purpose and fields come from it. */
@Serializable
data class NewCredential(
    val requestId: CredentialRequestId,
    val hints: Map<String, String> = emptyMap(),
    /** At least one; each [VaultEnvelope.deviceId] must have a registered vault key. */
    val envelopes: List<VaultEnvelope>,
)

/**
 * `POST /api/v1/vault/grants/{heldId}`: the phone's biometric-approved release of the secret
 * fields for one held [net.robog.clarity.shared.governance.ProposedAction.UseCredential].
 * Releasing is what approves the held action; the generic decision route cannot approve it.
 */
@Serializable
data class ReleaseGrant(
    /** Field name to opened value, for the fields the held action names. */
    val fields: Map<String, String>,
    /** Requested lifetime; the backend caps it at [VaultNames.MAX_GRANT_SECONDS]. */
    val ttlSeconds: Int = VaultNames.MAX_GRANT_SECONDS,
    /**
     * Base64url ES256 signature of the approval payload ([net.robog.clarity.shared.governance.ApprovalPayload])
     * for `approve`. Required when the device has registered an approval key.
     */
    val signature: String? = null,
)

/** `POST /api/v1/vault/codes/{heldId}`: the person typed the two-factor code the agent asked for. */
@Serializable
data class ReleaseCode(
    val code: String,
    val ttlSeconds: Int = VaultNames.MAX_GRANT_SECONDS,
    /** As [ReleaseGrant.signature]. */
    val signature: String? = null,
)

@Serializable
data class GrantReceipt(val expiresAt: Instant, val site: String)

/** Naming rules and limits shared by app and backend. */
object VaultNames {
    /** How long released login values stay usable: long enough for one task (sign in, a code, a few pages). */
    const val MAX_GRANT_SECONDS = 3600

    /** The cap before it was raised: phones built then always ask for exactly this, meaning "as long as allowed". */
    const val LEGACY_MAX_GRANT_SECONDS = 300
    val HINT_KEYS: Set<String> = setOf("username", "brand", "last4", "expiry_month")

    private val SLUG = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

    /** Credential handles and field names: lowercase snake_case, at most 48 characters. */
    fun isSlug(value: String): Boolean = value.length <= 48 && SLUG.matches(value)

    /** A best-effort handle from free text ("Bank of X login" -> `bank_of_x_login`). */
    fun slugOf(text: String): String {
        val slug = text.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
        val start = when {
            slug.isEmpty() -> "credential"
            slug[0].isDigit() -> "c_$slug"
            else -> slug
        }
        return start.take(48).trimEnd('_')
    }

    /** The placeholder text for a credential field. */
    fun placeholder(credentialName: String, field: String): String = "[secret:${credentialName}_$field]"
}
