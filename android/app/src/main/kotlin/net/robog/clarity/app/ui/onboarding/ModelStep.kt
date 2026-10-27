package net.robog.clarity.app.ui.onboarding

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
import net.robog.clarity.app.data.SetupRepository
import net.robog.clarity.shared.session.SetupStatus

sealed interface ModelResult {
    /** "● models ready": the server has its OpenRouter key. */
    data object Works : ModelResult
    /** The server has no OpenRouter key: it is set in the server's configuration, not here. */
    data object Missing : ModelResult
    /** The server does not report setup status (yet); setup can go on. */
    data object ServerNotReady : ModelResult
}

data class ModelState(
    val checking: Boolean = false,
    val result: ModelResult? = null,
) {
    val canCheck: Boolean get() = !checking && result != ModelResult.Works
    val canContinue: Boolean get() = result == ModelResult.Works || result == ModelResult.ServerNotReady
}

/** Pure: what the setup status says about the model key. */
fun modelResult(status: SetupStatus?): ModelResult = when (status?.modelKey) {
    null -> ModelResult.ServerNotReady
    SetupStatus.KeyState.VALID -> ModelResult.Works
    else -> ModelResult.Missing
}

/**
 * Step 1, model: every model runs on OpenRouter with the server's own key (`OPENROUTER_API_KEY_FILE`),
 * so this step only checks that the server has it; the key is never entered on the phone.
 */
@Inject
@ViewModelKey(ModelStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ModelStepViewModel(private val setup: SetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(ModelState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val result = modelResult(setup.status())
            _state.update { it.copy(checking = false, result = result) }
        }
    }
}
