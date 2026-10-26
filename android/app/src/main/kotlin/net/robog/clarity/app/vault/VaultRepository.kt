package net.robog.clarity.app.vault

import kotlinx.coroutines.CancellationException
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.VaultApi
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.FieldType
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.Validators

/** Result of saving a credential from the form. [Failed.message] is for the person. */
sealed interface SaveOutcome {
    data class Saved(val meta: CredentialMeta) : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/** The vault from the phone's point of view: keys, the form, the list. */
interface VaultRepository {
    fun keyStatus(): VaultKeyStatus

    /** Makes the keys if needed and tells the server about them. Safe to repeat. */
    suspend fun register(): ApiResult<Unit>

    suspend fun credentials(): ApiResult<List<CredentialMeta>>
    suspend fun delete(id: CredentialId): ApiResult<Unit>
    suspend fun request(id: CredentialRequestId): ApiResult<CredentialRequest>

    /** Seals [values] to every enrolled device's vault key and uploads only the ciphertext and the allowed hints. */
    suspend fun save(request: CredentialRequest, values: Map<String, String>): SaveOutcome

    /** After a biometric change: new keys, registered. Credentials sealed to the old key must be entered again. */
    suspend fun rekey(): ApiResult<Unit>
}

/** Pure: the non-secret hints for a new credential (username, card brand, last four, expiry month). */
fun credentialHints(request: CredentialRequest, values: Map<String, String>): Map<String, String> {
    fun valueOf(type: FieldType) = request.fields.firstOrNull { it.type == type }?.let { values[it.name] }?.trim().orEmpty()
    val hints = LinkedHashMap<String, String>()
    when (request.kind) {
        CredentialKind.LOGIN -> {
            val user = valueOf(FieldType.USERNAME).ifEmpty { valueOf(FieldType.EMAIL) }
            if (user.isNotEmpty() && user.length <= 64) hints["username"] = user
        }
        CredentialKind.PAYMENT_CARD -> {
            val number = Validators.digitsOnly(valueOf(FieldType.CARD_NUMBER))
            if (number.length >= 4) {
                hints["brand"] = Validators.cardBrand(number)
                hints["last4"] = number.takeLast(4)
            }
            Regex("^\\s*(\\d{2})").find(valueOf(FieldType.CARD_EXPIRY))?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..12 }?.let { hints["expiry_month"] = it.toString() }
        }
        CredentialKind.IDENTITY, CredentialKind.NOTE -> Unit
    }
    return hints
}

class DefaultVaultRepository(
    private val api: VaultApi,
    private val keys: VaultKeys,
) : VaultRepository {
    override fun keyStatus(): VaultKeyStatus = keys.status()

    override suspend fun register(): ApiResult<Unit> {
        if (keys.status() == VaultKeyStatus.INVALIDATED) return ApiResult.Failure(ApiError.Forbidden(null))
        val request = RegisterVaultKey(publicKey = keys.vaultPublicKey(), approvalKey = keys.approvalPublicKey())
        return when (val r = api.registerVaultKeys(request)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> r
        }
    }

    override suspend fun credentials(): ApiResult<List<CredentialMeta>> = api.credentials()
    override suspend fun delete(id: CredentialId): ApiResult<Unit> = api.deleteCredential(id)
    override suspend fun request(id: CredentialRequestId): ApiResult<CredentialRequest> = api.credentialRequest(id)

    override suspend fun save(request: CredentialRequest, values: Map<String, String>): SaveOutcome {
        if (keys.status() == VaultKeyStatus.INVALIDATED) {
            return SaveOutcome.Failed("This phone’s vault keys were reset. Open Settings → Vault first.")
        }
        (register() as? ApiResult.Failure)?.let { return SaveOutcome.Failed(it.error.sentence()) }
        val devices = when (val r = api.vaultDevices()) {
            is ApiResult.Success -> r.value
            is ApiResult.Failure -> return SaveOutcome.Failed(r.error.sentence())
        }
        if (devices.isEmpty()) return SaveOutcome.Failed("No phone has a vault key yet.")
        val fields = request.fields.mapNotNull { f -> values[f.name]?.takeIf { it.isNotEmpty() }?.let { f.name to it } }.toMap()
        val envelopes = try {
            devices.map { VaultCrypto.seal(it.deviceId, it.publicKey, request.id.value, fields) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Never include the exception: it could echo input.
            return SaveOutcome.Failed("Couldn’t lock that up safely. Nothing was saved.")
        }
        return when (val r = api.saveCredential(NewCredential(request.id, credentialHints(request, values), envelopes))) {
            is ApiResult.Success -> SaveOutcome.Saved(r.value)
            is ApiResult.Failure -> SaveOutcome.Failed(r.error.sentence())
        }
    }

    override suspend fun rekey(): ApiResult<Unit> {
        keys.reset()
        return register()
    }
}
