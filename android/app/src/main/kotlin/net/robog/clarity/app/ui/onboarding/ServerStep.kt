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
import net.robog.clarity.app.AppConfig
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.EnrollmentRepository
import net.robog.clarity.app.data.InstanceUrl
import net.robog.clarity.app.data.ServerProbe
import net.robog.clarity.app.data.ServerRepository
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.data.UrlError
import net.robog.clarity.app.data.UrlValidation

sealed interface ServerError {
    data class InvalidUrl(val reason: UrlError) : ServerError
    data object Unreachable : ServerError
    /** 401/403: the instance wants an (other) setup code. */
    data object CodeRequired : ServerError
    data class Server(val status: Int) : ServerError
    data object Unexpected : ServerError
}

data class ServerState(
    val url: String = "",
    /** The optional setup code (the enrollment token). */
    val code: String = "",
    val checking: Boolean = false,
    /** Set once the server answered and this phone is enrolled with it. */
    val probe: ServerProbe? = null,
    val enrolled: Boolean = false,
    val error: ServerError? = null,
) {
    val canCheck: Boolean get() = url.isNotBlank() && !checking && !enrolled
}

fun ApiError.toServerError(): ServerError = when (this) {
    is ApiError.Unreachable -> ServerError.Unreachable
    is ApiError.Unauthorized, is ApiError.Forbidden -> ServerError.CodeRequired
    is ApiError.Http -> ServerError.Server(status)
    is ApiError.NotFound -> ServerError.Unexpected
    is ApiError.Malformed, ApiError.NotConfigured -> ServerError.Unexpected
}

/** Step 1: the server address and optional setup code; "check server" probes it, then enrolls this phone. */
@Inject
@ViewModelKey(ServerStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ServerStepViewModel(
    private val servers: ServerRepository,
    private val enrollment: EnrollmentRepository,
    private val sessions: SessionRepository,
    private val config: AppConfig,
) : ViewModel() {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    init {
        // Coming back to this step after enrolling: show the connected server instead of the form.
        viewModelScope.launch {
            sessions.session.first()?.let { s -> _state.update { it.copy(url = s.instanceUrl, enrolled = true) } }
        }
    }

    fun onUrlChanged(value: String) = _state.update { it.copy(url = value, error = null) }
    fun onCodeChanged(value: String) = _state.update { it.copy(code = value, error = null) }

    fun check() {
        val current = _state.value
        if (!current.canCheck) return
        val url = when (val v = InstanceUrl.validate(current.url, config.allowCleartext)) {
            is UrlValidation.Invalid -> {
                _state.update { it.copy(error = ServerError.InvalidUrl(v.error)) }
                return
            }
            is UrlValidation.Valid -> v.url
        }
        _state.update { it.copy(checking = true, error = null, probe = null) }
        viewModelScope.launch {
            val probe = when (val p = servers.probe(url)) {
                is ApiResult.Success -> p.value
                is ApiResult.Failure -> {
                    _state.update { it.copy(checking = false, error = p.error.toServerError()) }
                    return@launch
                }
            }
            when (val e = enrollment.enroll(url, current.code)) {
                is ApiResult.Success -> _state.update { it.copy(checking = false, probe = probe, enrolled = true) }
                is ApiResult.Failure -> _state.update { it.copy(checking = false, error = e.error.toServerError()) }
            }
        }
    }
}
