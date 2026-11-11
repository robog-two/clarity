package net.robog.clarity.app.vault

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.RollbackPayload
import net.robog.clarity.shared.backup.RollbackRequest

/** The outcome of asking the person to sign a rollback. [No.message] is for them. */
sealed interface RollbackSigned {
    data class Ok(val request: RollbackRequest) : RollbackSigned
    data class No(val message: String) : RollbackSigned
}

/** Signs a rollback for [backup]. Faked in tests of the screen. */
fun interface RollbackSigning {
    suspend fun sign(backup: BackupInfo, subtitle: String): RollbackSigned
}

/**
 * Signs a rollback the way held actions are approved: the fingerprint or face prompt is bound to the
 * approval key, which signs [RollbackPayload.payload] (the file's name and SHA-256) once. The server
 * checks it against the key it has for this phone.
 */
class RollbackSigner(
    private val keys: VaultKeys,
    private val auth: PhoneAuth,
    private val vault: VaultRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RollbackSigning {
    override suspend fun sign(backup: BackupInfo, subtitle: String): RollbackSigned = try {
        if (vault.keyStatus() == VaultKeyStatus.INVALIDATED) throw KeysInvalidatedException(IllegalStateException("invalidated"))
        // The server must know this phone's approval key before it can check the signature.
        (vault.register() as? ApiResult.Failure)?.let { return RollbackSigned.No(it.error.approvalSentence()) }
        val unsigned = withContext(io) { keys.approvalSignature() }
        when (val r = auth.authenticate(AuthPrompt("Approve with your fingerprint or face", subtitle), unsigned)) {
            is AuthResult.Authenticated -> {
                val authorized = r.signature ?: return RollbackSigned.No("Couldn’t approve that on this phone.")
                val signature = withContext(io) { signPayload(authorized, RollbackPayload.payload(backup.name, backup.sha256)) }
                RollbackSigned.Ok(RollbackRequest(backup.name, backup.sha256, signature))
            }
            AuthResult.Cancelled -> RollbackSigned.No("Not approved.")
            is AuthResult.Unavailable -> RollbackSigned.No(r.message)
            is AuthResult.Failed -> RollbackSigned.No(r.message)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: KeysInvalidatedException) {
        RollbackSigned.No("Vault keys were reset. Go to Settings, then Vault.")
    } catch (_: NotAuthenticatedException) {
        RollbackSigned.No("That took too long. Try again.")
    } catch (e: Exception) {
        // Only the type is logged: nothing from a failed signature belongs in a message.
        runCatching { Log.w("ClarityBackups", "signing failed: ${e::class.java.name}") }
        RollbackSigned.No("Couldn’t approve that on this phone.")
    }
}
