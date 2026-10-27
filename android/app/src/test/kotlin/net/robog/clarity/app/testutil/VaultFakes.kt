package net.robog.clarity.app.testutil

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.VaultApi
import net.robog.clarity.app.vault.AuthPrompt
import net.robog.clarity.app.vault.AuthResult
import net.robog.clarity.app.vault.KeysInvalidatedException
import net.robog.clarity.app.vault.NotAuthenticatedException
import net.robog.clarity.app.vault.PhoneAuth
import net.robog.clarity.app.vault.SaveOutcome
import net.robog.clarity.app.vault.VaultKeyStatus
import net.robog.clarity.app.vault.VaultKeys
import net.robog.clarity.app.vault.VaultRepository
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.CredentialRequestStatus
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.FieldSpec
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.GrantReceipt
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant
import net.robog.clarity.shared.vault.VaultEnvelope

private val b64 = Base64.getUrlEncoder().withoutPadding()

fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

fun spki(pair: KeyPair): String = b64.encodeToString(pair.public.encoded)

/** Software stand-ins for the two Keystore keys. */
class FakeVaultKeys(
    val vault: KeyPair = newKeyPair(),
    val approval: KeyPair = newKeyPair(),
    var status: VaultKeyStatus = VaultKeyStatus.READY,
) : VaultKeys {
    var resets = 0
    /** How many [agree] calls still fail as "authenticate first" (the unlock window has passed). */
    var staleAgreements = 0
    override fun status() = status
    override fun vaultPublicKey() = spki(vault)
    override fun approvalPublicKey() = spki(approval)
    override fun approvalSignature(): Signature {
        if (status == VaultKeyStatus.INVALIDATED) throw KeysInvalidatedException(IllegalStateException("x"))
        return Signature.getInstance("SHA256withECDSA").apply { initSign(approval.private) }
    }
    override fun agree(ephemeral: PublicKey): ByteArray =
        if (staleAgreements > 0) { staleAgreements--; throw NotAuthenticatedException(IllegalStateException("window")) } else KeyAgreement.getInstance("ECDH").apply { init(vault.private); doPhase(ephemeral, true) }.generateSecret()
    override fun reset() { resets++; status = VaultKeyStatus.READY }
    var deletes = 0
    override fun delete() { deletes++; status = VaultKeyStatus.MISSING }
}

/** Answers the prompts from a script and records what was asked. */
class ScriptedAuth(vararg results: AuthResult?) : PhoneAuth {
    private val script = results.toMutableList()
    val prompts = mutableListOf<AuthPrompt>()
    val boundToSignature = mutableListOf<Boolean>()
    override suspend fun authenticate(prompt: AuthPrompt, signature: Signature?): AuthResult {
        prompts += prompt
        boundToSignature += (signature != null)
        // null in the script means "authenticated"
        val next = if (script.isEmpty()) null else script.removeAt(0)
        return next ?: AuthResult.Authenticated(signature)
    }
}

/** A server that records what the phone sent. */
class FakeVaultApi : VaultApi {
    var held: MutableMap<String, HeldAction> = mutableMapOf()
    var devices: List<DeviceVaultKey> = emptyList()
    var envelopes: MutableMap<String, VaultEnvelope> = mutableMapOf()
    var requests: MutableMap<String, CredentialRequest> = mutableMapOf()
    var stored: MutableList<CredentialMeta> = mutableListOf()
    var failWith: ApiError? = null

    val registered = mutableListOf<RegisterVaultKey>()
    val saved = mutableListOf<NewCredential>()
    val grants = mutableListOf<Pair<HeldActionId, ReleaseGrant>>()
    val codes = mutableListOf<Pair<HeldActionId, ReleaseCode>>()
    val signed = mutableListOf<Pair<HeldActionId, SignedDecision>>()
    val deleted = mutableListOf<CredentialId>()

    private fun <T> ok(value: T): ApiResult<T> = failWith?.let { ApiResult.Failure(it) } ?: ApiResult.Success(value)

    override suspend fun registerVaultKeys(request: RegisterVaultKey): ApiResult<DeviceVaultKey> {
        registered += request
        return ok(DeviceVaultKey(DeviceId("me"), request.publicKey))
    }
    override suspend fun vaultDevices() = ok(devices)
    override suspend fun credentials() = ok(stored.toList())
    override suspend fun deleteCredential(id: CredentialId): ApiResult<Unit> { deleted += id; stored.removeAll { it.id == id }; return ok(Unit) }
    override suspend fun credentialRequest(id: CredentialRequestId): ApiResult<CredentialRequest> =
        requests[id.value]?.let { ok(it) } ?: ApiResult.Failure(ApiError.NotFound(null))
    override suspend fun saveCredential(new: NewCredential): ApiResult<CredentialMeta> {
        saved += new
        val req = requests.getValue(new.requestId.value)
        return ok(CredentialMeta(CredentialId("c1"), req.name, req.kind, req.site, req.purpose, req.fields, new.hints, T0))
    }
    override suspend fun envelope(id: CredentialId): ApiResult<VaultEnvelope> =
        envelopes[id.value]?.let { ok(it) } ?: ApiResult.Failure(ApiError.NotFound(null))
    override suspend fun releaseGrant(heldId: HeldActionId, release: ReleaseGrant): ApiResult<GrantReceipt> {
        grants += heldId to release
        return ok(GrantReceipt(T0, "example.com"))
    }
    override suspend fun releaseCode(heldId: HeldActionId, release: ReleaseCode): ApiResult<GrantReceipt> {
        codes += heldId to release
        return ok(GrantReceipt(T0, "example.com"))
    }
    override suspend fun signedDecision(heldId: HeldActionId, decision: SignedDecision): ApiResult<Unit> {
        signed += heldId to decision
        return ok(Unit)
    }
    override suspend fun heldAction(id: HeldActionId): ApiResult<HeldAction> =
        held[id.value]?.let { ok(it) } ?: ApiResult.Failure(ApiError.NotFound(null))
}

val loginFields = listOf(
    FieldSpec("username", "Username", FieldType.USERNAME),
    FieldSpec("password", "Password", FieldType.PASSWORD),
)

fun credentialRequest(
    id: String = "req1",
    kind: CredentialKind = CredentialKind.LOGIN,
    fields: List<FieldSpec> = loginFields,
    status: CredentialRequestStatus = CredentialRequestStatus.OPEN,
) = CredentialRequest(
    id = CredentialRequestId(id), name = "bank_login", kind = kind, fields = fields,
    site = "bank.example.com", purpose = "Pay the water bill", status = status, createdAt = T0,
)

/** A repository fake for UI state tests. */
class FakeVault(
    var request: ApiResult<CredentialRequest> = ApiResult.Success(credentialRequest()),
    var outcome: SaveOutcome = SaveOutcome.Failed("no"),
    var status: VaultKeyStatus = VaultKeyStatus.READY,
    var list: ApiResult<List<CredentialMeta>> = ApiResult.Success(emptyList()),
) : VaultRepository {
    val savedValues = mutableListOf<Map<String, String>>()
    val deleted = mutableListOf<CredentialId>()
    var registers = 0
    var rekeys = 0
    override suspend fun keyStatus() = status
    override suspend fun register(): ApiResult<Unit> { registers++; return ApiResult.Success(Unit) }
    override suspend fun credentials() = list
    override suspend fun delete(id: CredentialId): ApiResult<Unit> { deleted += id; return ApiResult.Success(Unit) }
    override suspend fun request(id: CredentialRequestId) = request
    override suspend fun save(request: CredentialRequest, values: Map<String, String>): SaveOutcome { savedValues += values; return outcome }
    override suspend fun rekey(): ApiResult<Unit> { rekeys++; status = VaultKeyStatus.READY; return ApiResult.Success(Unit) }
}
