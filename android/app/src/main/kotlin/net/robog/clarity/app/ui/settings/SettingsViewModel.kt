package net.robog.clarity.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.SessionRepository

data class SettingsUiState(val instanceUrl: String = "", val deviceId: String = "")

@Inject
@ViewModelKey(SettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
class SettingsViewModel(private val sessions: SessionRepository) : ViewModel() {
    val state: StateFlow<SettingsUiState> = sessions.session
        .map { s -> s?.let { SettingsUiState(it.instanceUrl, it.deviceId.value) } ?: SettingsUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Clears the session and device key; the root navigation returns to onboarding. */
    fun forgetInstance() {
        viewModelScope.launch { sessions.forget() }
    }
}
