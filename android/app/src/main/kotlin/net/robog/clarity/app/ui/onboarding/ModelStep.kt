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
import net.robog.clarity.app.data.ModelKeyOutcome
import net.robog.clarity.app.data.SetupRepository

sealed interface ModelResult {
    /** "● key works". */
    data object Works : ModelResult
    data class Rejected(val detail: String?) : ModelResult
    /** The server cannot take a key yet (route missing); setup can go on, the key must be set later. */
    data object ServerNotReady : ModelResult
    data object Failed : ModelResult
}

data class ModelState(
    val key: String = "",
    val checking: Boolean = false,
    val result: ModelResult? = null,
) {
    val canCheck: Boolean get() = key.isNotBlank() && !checking && result != ModelResult.Works
    val canContinue: Boolean get() = result == ModelResult.Works || result == ModelResult.ServerNotReady
}

fun ModelKeyOutcome.toResult(): ModelResult = when (this) {
    ModelKeyOutcome.Works -> ModelResult.Works
    is ModelKeyOutcome.Rejected -> ModelResult.Rejected(detail)
    ModelKeyOutcome.Unavailable -> ModelResult.ServerNotReady
    is ModelKeyOutcome.Failed -> ModelResult.Failed
}

/** Step 1, model: the Anthropic API key, stored and validated by the server. */
@Inject
@ViewModelKey(ModelStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ModelStepViewModel(private val setup: SetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(ModelState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun onKeyChanged(value: String) = _state.update { ModelState(key = value.trim()) }

    fun check() {
        val key = _state.value.takeIf { it.canCheck }?.key ?: return
        _state.update { it.copy(checking = true, result = null) }
        viewModelScope.launch {
            val result = setup.submitModelKey(key).toResult()
            // The key stays in memory only until this screen goes away; it is never persisted on the phone.
            _state.update { it.copy(checking = false, result = result) }
        }
    }
}
