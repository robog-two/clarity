package net.robog.clarity.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.InstanceRepository
import net.robog.clarity.shared.api.InstanceInfo

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val instance: InstanceInfo) : HomeUiState
    data class Error(val kind: Kind) : HomeUiState
    enum class Kind { Unreachable, Unauthorized, Other }
}

fun ApiResult<InstanceInfo>.toHomeState(): HomeUiState = when (this) {
    is ApiResult.Success -> HomeUiState.Content(value)
    is ApiResult.Failure -> HomeUiState.Error(
        when (error) {
            is ApiError.Unreachable -> HomeUiState.Kind.Unreachable
            is ApiError.Unauthorized, is ApiError.Forbidden -> HomeUiState.Kind.Unauthorized
            else -> HomeUiState.Kind.Other
        },
    )
}

@Inject
@ViewModelKey(HomeViewModel::class)
@ContributesIntoMap(AppScope::class)
class HomeViewModel(private val instances: InstanceRepository) : ViewModel() {
    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        _state.value = HomeUiState.Loading
        viewModelScope.launch { _state.value = instances.info().toHomeState() }
    }
}
