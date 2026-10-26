package net.robog.clarity.backend.vault

import arrow.core.Either
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.context.raise
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import arrow.core.raise.Raise
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.governance.PhoneApprovals
import net.robog.clarity.shared.governance.ApprovalPayload
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.governance.payloadFor
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.PENDING_STATUSES
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.GrantReceipt
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant
import net.robog.clarity.shared.vault.VaultEnvelope
import net.robog.clarity.shared.vault.VaultNames
import net.robog.clarity.shared.vault.VaultSealing

private val B64 = Base64.getUrlDecoder()

private fun decodeB64(value: String): ByteArray? = try {
    B64.decode(value)
} catch (_: IllegalArgumentException) {
    null
}

/** Pure: why [key] is not a usable vault key (a base64url SPKI P-256 public key), else null. */
fun vaultKeyProblem(key: RegisterVaultKey): String? {
    if (key.alg != VaultSealing.ALGORITHM) return "unsupported algorithm '${key.alg}'"
    val der = decodeB64(key.publicKey) ?: return "publicKey must be base64url"
    return try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as? ECPublicKey ?: return "publicKey must be an EC key"
        if (pub.params.curve.field.fieldSize != 256) "publicKey must be on P-256" else null
    } catch (_: Exception) {
        "publicKey is not a valid SubjectPublicKeyInfo"
    }
}

/** Pure: why [envelope] is malformed for credential request [context], else null. The content is opaque to the backend. */
fun envelopeProblem(envelope: VaultEnvelope, context: String): String? {
    if (envelope.alg != VaultSealing.ALGORITHM) return "unsupported envelope algorithm '${envelope.alg}'"
    if (envelope.context != context) return "envelope context must be the request id"
    val epk = decodeB64(envelope.epk)
    if (epk == null || epk.size != 65 || epk[0].toInt() != 4) return "epk must be an uncompressed P-256 point"
    val iv = decodeB64(envelope.iv)
    if (iv == null || iv.size != 12) return "iv must be 12 bytes"
    val ct = decodeB64(envelope.ciphertext)
    if (ct == null || ct.size < 17 || ct.size > MAX_CIPHERTEXT) return "ciphertext is missing or too large"
    return null
}

private const val MAX_CIPHERTEXT = 64 * 1024

/** Pure: why the agent's request is unacceptable, else null. */
fun requestProblem(name: String, fields: List<FieldSpec>, site: String, purpose: String): String? = when {
    name == "code" || name.startsWith("code_") -> "`name` cannot start with code_ (that is for two-factor codes)"
    !VaultNames.isSlug(name) -> "`name` must be lowercase letters, digits and underscores (like bank_login)"
    fields.isEmpty() || fields.size > 12 -> "a credential has between 1 and 12 fields"
    fields.any { !VaultNames.isSlug(it.name) } -> "field names must be lowercase letters, digits and underscores (like password)"
    fields.map { it.name }.distinct().size != fields.size -> "field names must be unique"
    fields.any { it.label.isBlank() || it.label.length > 80 } -> "every field needs a short label"
    Sites.host(site) == null -> "`site` must be a website address or host"
    purpose.isBlank() || purpose.length > 300 -> "`purpose` is one short sentence for the person"
    else -> null
}

/** Pure: why the hints are unacceptable (only [VaultNames.HINT_KEYS], short, no value that looks like a secret field), else null. */
fun hintsProblem(hints: Map<String, String>, fields: List<FieldSpec>): String? {
    hints.forEach { (key, value) ->
        if (key !in VaultNames.HINT_KEYS) return "hint '$key' is not allowed (allowed: ${VaultNames.HINT_KEYS.sorted().joinToString()})"
        if (value.length > 64) return "hint '$key' is too long"
    }
    hints["last4"]?.let { if (it.length != 4 || !it.all(Char::isDigit)) return "last4 must be four digits" }
    hints["expiry_month"]?.let { if (it.toIntOrNull() !in 1..12) return "expiry_month must be 1-12" }
    if (fields.any { it.type == FieldType.PASSWORD || it.type == FieldType.CARD_CVC }) {
        val names = fields.filter { it.type == FieldType.PASSWORD || it.type == FieldType.CARD_CVC }.map { it.name }
        if (hints.keys.any { it in names }) return "hints are for non-secret details only"
    }
    return null
}

/**
 * The vault's API logic. It stores ciphertext and metadata, creates grants from values the phone
 * released, and never returns, logs or persists a secret value. Approval of a vault action is only
 * possible here, through the phone's release (the generic held-decision route refuses it).
 */
@Inject
@SingleIn(AppScope::class)
class VaultService(
    private val store: VaultStore,
    private val grants: GrantStore,
    private val held: HeldActionStore,
    private val events: GovernanceEvents,
    private val inbox: InboxStore,
) {
    suspend fun registerKey(device: DeviceId, request: RegisterVaultKey): Either<VaultError, DeviceVaultKey> = either {
        vaultKeyProblem(request)?.let { raise(VaultError.Invalid(it)) }
        request.approvalKey?.let { approval ->
            vaultKeyProblem(RegisterVaultKey(approval, VaultSealing.ALGORITHM))?.let { raise(VaultError.Invalid("approvalKey: $it")) }
        }
        store.upsertDeviceKey(DeviceVaultKey(device, request.publicKey, request.alg), request.approvalKey)
    }

    suspend fun deviceKeys(): List<DeviceVaultKey> = store.deviceKeys()

    /**
     * The agent's `request_credential`: stores the request. The form reaches the chat only when the
     * agent posts its `credential_request` card with `send_to_user`.
     *
     * With [replace], [name] may already be saved: the new entry takes its place once the person
     * saves it (until then the old one stays usable). Without it a taken name is refused.
     */
    suspend fun requestCredential(
        name: String?,
        kind: CredentialKind,
        fields: List<FieldSpec>,
        site: String,
        purpose: String,
        replace: Boolean = false,
    ): Either<VaultError, CredentialRequest> = either {
        val handle = name?.takeIf { it.isNotBlank() } ?: VaultNames.slugOf("${Sites.registrableDomain(site) ?: site}_${kind.label()}")
        requestProblem(handle, fields, site, purpose)?.let { raise(VaultError.Invalid(it)) }
        if (!replace) ensure(store.byName(handle) == null) { VaultError.NameTaken(handle) }
        store.createRequest(handle, kind, fields, site.trim(), purpose.trim())
    }

    suspend fun request(id: CredentialRequestId): Either<VaultError, CredentialRequest> = either {
        ensureNotNull(store.request(id)) { VaultError.RequestNotFound(id.value) }
    }

    /** `POST /vault/credentials`: the phone completes a request with sealed copies. */
    suspend fun complete(new: NewCredential): Either<VaultError, CredentialMeta> = either {
        val request = ensureNotNull(store.request(new.requestId)) { VaultError.RequestNotFound(new.requestId.value) }
        ensure(request.status == net.robog.clarity.shared.vault.CredentialRequestStatus.OPEN) { VaultError.RequestNotOpen(request.id.value) }
        ensure(new.envelopes.isNotEmpty()) { VaultError.Invalid("at least one envelope is required") }
        ensure(new.envelopes.map { it.deviceId }.distinct().size == new.envelopes.size) { VaultError.Invalid("one envelope per device") }
        hintsProblem(new.hints, request.fields)?.let { raise(VaultError.Invalid(it)) }
        new.envelopes.forEach { env ->
            envelopeProblem(env, request.id.value)?.let { raise(VaultError.Invalid(it)) }
            ensureNotNull(store.deviceKey(env.deviceId)) { VaultError.NoDeviceKey(env.deviceId.value) }
        }
        when (val outcome = store.createCredential(new.requestId, new.hints, new.envelopes)) {
            is CreateOutcome.Created -> outcome.meta.also {
                // Values released for the entry it replaced must not be typed any more.
                if (outcome.replaced) grants.revoke(it.name)
                // Metadata only: the lead learns it can continue, never a value.
                inbox.enqueue(
                    InboxItem.System("Credential \"${it.name}\" for ${it.site} is ready (use it with [secret:${it.name}_<field>] placeholders; fields: ${it.fields.joinToString { f -> f.name }})."),
                    dedupKey = "credential-saved:${it.id.value}",
                )
            }
            CreateOutcome.NameTaken -> raise(VaultError.NameTaken(request.name))
            CreateOutcome.RequestNotOpen -> raise(VaultError.RequestNotOpen(request.id.value))
            CreateOutcome.UnknownRequest -> raise(VaultError.RequestNotFound(request.id.value))
        }
    }

    suspend fun list(): List<CredentialMeta> = store.list()

    suspend fun delete(id: CredentialId): Either<VaultError, Unit> = either {
        val meta = ensureNotNull(store.get(id)) { VaultError.CredentialNotFound(id.value) }
        store.delete(id)
        grants.revoke(meta.name)
    }

    /** The caller's own sealed copy, for the phone to open. */
    suspend fun envelope(id: CredentialId, device: DeviceId): Either<VaultError, VaultEnvelope> = either {
        ensureNotNull(store.envelopeFor(id, device)) { VaultError.EnvelopeNotFound(id.value) }
    }

    /**
     * `POST /vault/grants/{heldId}`: the phone, after biometric approval, releases the secret fields
     * of the credential a held [ProposedAction.UseCredential] names. This is the only way such an
     * action becomes approved. Creates the grant for that credential on the action's site.
     */
    suspend fun release(heldId: HeldActionId, device: DeviceId, release: ReleaseGrant): Either<VaultError, GrantReceipt> = either {
        val current = ensureNotNull(held.get(heldId)) { VaultError.HeldNotFound(heldId.value) }
        val action = current.action as? ProposedAction.UseCredential ?: raise(VaultError.WrongKind(heldId.value, "credential use"))
        ensure(current.status in PENDING_STATUSES) { VaultError.NotPending(heldId.value, current.status.name.lowercase()) }
        val meta = ensureNotNull(store.get(action.credentialId)) { VaultError.CredentialNotFound(action.credentialId.value) }
        ensure(store.deviceKey(device) != null) { VaultError.NoDeviceKey(device.value) }
        val allowed = action.fields.toSet()
        release.fields.keys.firstOrNull { it !in allowed }?.let { raise(VaultError.Invalid("field '$it' was not part of this request")) }
        meta.fields.firstOrNull { it.required && it.name in allowed && release.fields[it.name].isNullOrEmpty() }
            ?.let { raise(VaultError.Invalid("field '${it.name}' is required")) }
        release.fields.values.firstOrNull { it.length > MAX_VALUE }?.let { raise(VaultError.Invalid("a value is too long")) }
        ensure(release.ttlSeconds > 0) { VaultError.Invalid("ttlSeconds must be positive") }
        requireSignature(current, device, release.signature)
        ensure(held.transition(heldId, PENDING_STATUSES, HeldAction.Status.APPROVED)) { VaultError.NotPending(heldId.value, "decided") }
        publish(heldId)
        val grant = grants.put(meta.name, action.site, release.fields, release.ttlSeconds.coerceAtMost(VaultNames.MAX_GRANT_SECONDS).seconds)
        GrantReceipt(grant.expiresAt, action.site)
    }

    /** `POST /vault/codes/{heldId}`: the person typed the two-factor code a held [ProposedAction.AskCode] asked for. */
    suspend fun releaseCode(heldId: HeldActionId, device: DeviceId, release: ReleaseCode): Either<VaultError, GrantReceipt> = either {
        val current = ensureNotNull(held.get(heldId)) { VaultError.HeldNotFound(heldId.value) }
        val action = current.action as? ProposedAction.AskCode ?: raise(VaultError.WrongKind(heldId.value, "two-factor code"))
        ensure(current.status in PENDING_STATUSES) { VaultError.NotPending(heldId.value, current.status.name.lowercase()) }
        val code = release.code.trim()
        ensure(code.length in 3..32) { VaultError.Invalid("that code does not look right") }
        ensure(release.ttlSeconds > 0) { VaultError.Invalid("ttlSeconds must be positive") }
        requireSignature(current, device, release.signature)
        ensure(held.transition(heldId, PENDING_STATUSES, HeldAction.Status.APPROVED)) { VaultError.NotPending(heldId.value, "decided") }
        publish(heldId)
        val domain = Sites.registrableDomain(action.site) ?: action.site
        val grant = grants.put("code", domain, mapOf(heldId.value to code), release.ttlSeconds.coerceAtMost(VaultNames.MAX_GRANT_SECONDS).seconds)
        GrantReceipt(grant.expiresAt, domain)
    }

    /**
     * Every release must be signed by the device's approval key (its biometric yes): the signature
     * covers this exact held action and works once. A device without an approval key cannot release.
     */
    context(_: Raise<VaultError>)
    private suspend fun requireSignature(current: HeldAction, device: DeviceId, signature: String?) {
        val key = ensureNotNull(store.approvalKey(device)) { VaultError.ApprovalRequired("this phone has no approval key; set up the vault on it first") }
        ensureNotNull(signature) { VaultError.ApprovalRequired("this release must be signed with your approval key") }
        ensure(PhoneApprovals.verify(key, ApprovalPayload.payloadFor(current, SignedDecision.APPROVE), signature)) {
            VaultError.ApprovalRequired("the signature does not match this action")
        }
        ensure(store.markSignatureUsed(PhoneApprovals.signatureHash(signature), current.id.value, device)) {
            VaultError.ApprovalRequired("that signature was already used")
        }
    }

    private suspend fun publish(id: HeldActionId) {
        held.get(id)?.let { events.publish(GovernanceEvent.HeldChanged(it)) }
    }

    private companion object {
        const val MAX_VALUE = 4096
    }
}

private fun CredentialKind.label(): String = when (this) {
    CredentialKind.LOGIN -> "login"
    CredentialKind.PAYMENT_CARD -> "card"
    CredentialKind.IDENTITY -> "identity"
    CredentialKind.NOTE -> "note"
}
