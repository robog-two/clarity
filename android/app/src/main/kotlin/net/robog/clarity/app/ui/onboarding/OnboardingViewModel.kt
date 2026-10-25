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
import net.robog.clarity.app.AppConfig
import net.robog.clarity.app.data.EnrollmentRepository
import net.robog.clarity.app.data.InstanceUrl
import net.robog.clarity.app.data.UrlValidation

@Inject
@ViewModelKey(OnboardingViewModel::class)
@ContributesIntoMap(AppScope::class)
class OnboardingViewModel(
    private val enrollment: EnrollmentRepository,
    private val config: AppConfig,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private fun dispatch(action: OnboardingAction) = _state.update { reduce(it, action) }

    fun onUrlChanged(value: String) = dispatch(OnboardingAction.UrlChanged(value))
    fun onTokenChanged(value: String) = dispatch(OnboardingAction.TokenChanged(value))

    fun connect() {
        val current = _state.value
        if (!current.canSubmit) return
        when (val v = InstanceUrl.validate(current.url, config.allowCleartext)) {
            is UrlValidation.Invalid -> dispatch(OnboardingAction.UrlRejected(v.error))
            is UrlValidation.Valid -> {
                dispatch(OnboardingAction.ConnectStarted)
                viewModelScope.launch {
                    val result = enrollment.enroll(v.url, current.token)
                    dispatch(OnboardingAction.ConnectFinished(result))
                }
            }
        }
    }
}
