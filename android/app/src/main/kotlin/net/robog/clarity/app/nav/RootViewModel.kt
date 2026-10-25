package net.robog.clarity.app.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.data.SetupRepository
import net.robog.clarity.app.ui.onboarding.isServerSetupComplete

enum class RootState {
    Loading,
    /** Not enrolled, or enrolled but setup is not finished: show onboarding. */
    Onboarding,
    /** Enrolled and set up: show the app. */
    Ready,
}

/**
 * Single source of truth for which half of the app shows. Onboarding continues until the person
 * finishes it on this device, or until the server already reports a complete setup (a reinstall).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ViewModelKey(RootViewModel::class)
@ContributesIntoMap(AppScope::class)
class RootViewModel(
    sessions: SessionRepository,
    flag: OnboardingFlagStore,
    setup: SetupRepository,
) : ViewModel() {
    val state: StateFlow<RootState> = combine(sessions.session, flag.done) { session, done -> (session != null) to done }
        .transformLatest { (enrolled, done) ->
            when {
                !enrolled -> emit(RootState.Onboarding)
                done -> emit(RootState.Ready)
                // Enrolled but not marked done: ask the server once (the previous value stays until it answers).
                else -> emit(if (isServerSetupComplete(setup.status())) RootState.Ready else RootState.Onboarding)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootState.Loading)
}
