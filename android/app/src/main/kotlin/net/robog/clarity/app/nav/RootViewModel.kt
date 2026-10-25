package net.robog.clarity.app.nav

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
import net.robog.clarity.app.data.SessionRepository

enum class RootState { Loading, SignedOut, SignedIn }

/** Single source of truth for "is this device enrolled", derived from the persisted session. */
@Inject
@ViewModelKey(RootViewModel::class)
@ContributesIntoMap(AppScope::class)
class RootViewModel(sessions: SessionRepository) : ViewModel() {
    val state: StateFlow<RootState> = sessions.session
        .map { if (it == null) RootState.SignedOut else RootState.SignedIn }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootState.Loading)
}
