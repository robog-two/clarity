package net.robog.clarity.app.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialKind
import net.robog.clarity.shared.vault.CredentialMeta

/** One row of the vault list: metadata only. */
data class CredentialRow(val id: CredentialId, val name: String, val kind: String, val site: String, val detail: String)

data class VaultUiState(
    val keys: VaultKeyStatus = VaultKeyStatus.MISSING,
    val rows: List<CredentialRow> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    /** Shown after a re-key: the old entries can no longer be opened. */
    val notice: String? = null,
)

/** "Visa ·· 4242 · expires 03" / "dana@example.com" from the non-secret hints. */
fun credentialRow(meta: CredentialMeta): CredentialRow {
    val detail = when (meta.kind) {
        CredentialKind.PAYMENT_CARD -> listOfNotNull(
            meta.hints["brand"]?.replaceFirstChar { it.uppercase() },
            meta.hints["last4"]?.let { "·· $it" },
            meta.hints["expiry_month"]?.let { "expires $it" },
        ).joinToString(" ")
        CredentialKind.LOGIN -> meta.hints["username"].orEmpty()
        CredentialKind.IDENTITY, CredentialKind.NOTE -> ""
    }
    val kind = when (meta.kind) {
        CredentialKind.LOGIN -> "login"
        CredentialKind.PAYMENT_CARD -> "card"
        CredentialKind.IDENTITY -> "identity"
        CredentialKind.NOTE -> "note"
    }
    return CredentialRow(meta.id, meta.name, kind, meta.site, detail)
}

/** The vault screen in Settings: what is stored (never the values), delete, and re-keying after a biometric change. */
@Inject
@ViewModelKey(VaultViewModel::class)
@ContributesIntoMap(AppScope::class)
class VaultViewModel(private val vault: VaultRepository) : ViewModel() {
    private val _state = MutableStateFlow(VaultUiState())
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val status = vault.keyStatus()
            _state.update { it.copy(keys = status, loading = true) }
            if (status != VaultKeyStatus.INVALIDATED) vault.register()
            when (val r = vault.credentials()) {
                is ApiResult.Success -> _state.update { it.copy(rows = r.value.map(::credentialRow), loading = false, message = null) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, message = r.error.sentence()) }
            }
            _state.update { it.copy(keys = vault.keyStatus()) }
        }
    }

    fun delete(id: CredentialId) {
        viewModelScope.launch {
            when (val r = vault.delete(id)) {
                is ApiResult.Success -> _state.update { s -> s.copy(rows = s.rows.filterNot { it.id == id }, message = null) }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error.sentence()) }
            }
        }
    }

    /** After a fingerprint or face was added: new keys. The stored credentials cannot be opened any more and must be entered again. */
    fun rekey() {
        viewModelScope.launch {
            when (val r = vault.rekey()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(notice = "The vault has new keys. Delete the old entries below and ask me to collect them again.") }
                    refresh()
                }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error.sentence()) }
            }
        }
    }
}
