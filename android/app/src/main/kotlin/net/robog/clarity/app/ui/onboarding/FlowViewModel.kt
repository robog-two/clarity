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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.data.SetupRepository

data class FlowState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    /** True once the starting step has been decided from the enrollment/setup status. */
    val ready: Boolean = false,
)

/** Which screen of the onboarding flow is showing. */
@Inject
@ViewModelKey(OnboardingFlowViewModel::class)
@ContributesIntoMap(AppScope::class)
class OnboardingFlowViewModel(
    private val sessions: SessionRepository,
    private val setup: SetupRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(FlowState())
    val state: StateFlow<FlowState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val enrolled = sessions.session.first() != null
            val status = if (enrolled) setup.status() else null
            _state.value = FlowState(initialStep(enrolled, status), ready = true)
        }
    }

    fun go(step: OnboardingStep) = _state.update { it.copy(step = step) }

    fun next() = _state.update { it.copy(step = it.step.next()) }

    fun back() = _state.update { s -> s.step.previous()?.let { s.copy(step = it) } ?: s }
}
