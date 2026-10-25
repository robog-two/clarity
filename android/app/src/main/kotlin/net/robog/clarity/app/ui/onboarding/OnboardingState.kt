package net.robog.clarity.app.ui.onboarding

import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.UrlError
import net.robog.clarity.shared.api.InstanceInfo

sealed interface OnboardingError {
    data class InvalidUrl(val reason: UrlError) : OnboardingError
    data object Unreachable : OnboardingError
    /** 401/403: the instance wants an (other) enrollment token. */
    data object TokenRequired : OnboardingError
    data class Server(val status: Int) : OnboardingError
    data object Unexpected : OnboardingError
}

data class OnboardingState(
    val url: String = "",
    val token: String = "",
    val connecting: Boolean = false,
    val error: OnboardingError? = null,
) {
    val canSubmit: Boolean get() = url.isNotBlank() && !connecting
}

/** Everything that can happen on the onboarding screen, as data so the reducer stays pure. */
sealed interface OnboardingAction {
    data class UrlChanged(val value: String) : OnboardingAction
    data class TokenChanged(val value: String) : OnboardingAction
    data class UrlRejected(val reason: UrlError) : OnboardingAction
    data object ConnectStarted : OnboardingAction
    data class ConnectFinished(val result: ApiResult<InstanceInfo>) : OnboardingAction
}

fun reduce(state: OnboardingState, action: OnboardingAction): OnboardingState = when (action) {
    is OnboardingAction.UrlChanged -> state.copy(url = action.value, error = null)
    is OnboardingAction.TokenChanged -> state.copy(token = action.value, error = null)
    is OnboardingAction.UrlRejected -> state.copy(error = OnboardingError.InvalidUrl(action.reason))
    OnboardingAction.ConnectStarted -> state.copy(connecting = true, error = null)
    is OnboardingAction.ConnectFinished -> when (val r = action.result) {
        // Success is observed through the persisted session (navigation), nothing else to show.
        is ApiResult.Success -> state.copy(connecting = false, error = null)
        is ApiResult.Failure -> state.copy(connecting = false, error = r.error.toOnboardingError())
    }
}

private fun ApiError.toOnboardingError(): OnboardingError = when (this) {
    is ApiError.Unreachable -> OnboardingError.Unreachable
    is ApiError.Unauthorized, is ApiError.Forbidden -> OnboardingError.TokenRequired
    is ApiError.Http -> OnboardingError.Server(status)
    is ApiError.Malformed, ApiError.NotConfigured -> OnboardingError.Unexpected
}
