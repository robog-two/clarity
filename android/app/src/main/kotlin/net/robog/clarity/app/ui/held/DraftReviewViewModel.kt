package net.robog.clarity.app.ui.held

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
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.HeldRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision

data class DraftReviewState(
    val loading: Boolean = true,
    val held: HeldAction? = null,
    /** The text in the draft field (starts as the agent's draft). */
    val body: String = "",
    /** A decision request is in flight. */
    val requesting: Boolean = false,
    /** Approved or declined: the screen can close. */
    val finished: Boolean = false,
    val message: String? = null,
    /** The two-factor code the person typed (only for a code request); never persisted. */
    val code: String = "",
) {
    val email get() = held?.action?.let(::emailOf)
    val decided: Boolean get() = held != null && !held.waiting
    /** The buttons wait while a request is in flight. */
    val busy: Boolean get() = requesting
}

/**
 * Review of one held action, most often an email draft: what was asked, what the agent thinks, why
 * it asks, the editable draft, the approve slider and "not now" (deny, or approve automatically
 * from now on with an edited rule). Nothing here goes to the agent. Approving asks the
 * [ApprovalGate] first, so the vault can require a biometric without changing this screen.
 */
@Inject
@ViewModelKey(DraftReviewViewModel::class)
@ContributesIntoMap(AppScope::class)
class DraftReviewViewModel(
    private val feed: FeedRepository,
    private val heldRepository: HeldRepository,
    private val gate: ApprovalGate,
    private val alwaysAllow: AlwaysAllow,
) : ViewModel() {
    private val _state = MutableStateFlow(DraftReviewState())
    val state: StateFlow<DraftReviewState> = _state.asStateFlow()

    private var id: HeldActionId? = null
    private var serverBody: String? = null

    init {
        viewModelScope.launch { feed.held.collect { held -> held.firstOrNull { it.id == id }?.let(::adopt) } }
    }

    /** Opens the held action [heldId] (from the cache, else from the server). */
    fun load(heldId: HeldActionId) {
        if (id == heldId) return
        id = heldId
        viewModelScope.launch {
            val cached = feed.held.first().firstOrNull { it.id == heldId }
            val held = cached ?: when (val r = heldRepository.get(heldId)) {
                is ApiResult.Success -> r.value
                is ApiResult.Failure -> {
                    _state.update { it.copy(loading = false, message = r.error.sentence()) }
                    return@launch
                }
            }
            adopt(held)
        }
    }

    /** Takes a (possibly revised) held action: a changed server text replaces the field. */
    private fun adopt(held: HeldAction) {
        val body = emailOf(held.action)?.body
        val revised = body != null && body != serverBody
        serverBody = body
        _state.update {
            it.copy(
                loading = false,
                held = held,
                body = if (revised || it.held == null) body ?: it.body else it.body,
            )
        }
    }

    fun edit(text: String) = _state.update { it.copy(body = text) }

    fun editCode(text: String) = _state.update { it.copy(code = text.take(32)) }

    /** "approve automatically from now on" with the (edited) rule: saves the rule, then approves this one. */
    fun approveAlways(text: String, because: String) = approve(rule = text to because)

    /** Approve (the slider reads "send" for mail), with the edited text if the person changed it. */
    fun approve(rule: Pair<String, String>? = null) {
        val s = _state.value
        val held = s.held ?: return
        if (s.busy || s.decided) return
        _state.update { it.copy(requesting = true, message = null) }
        viewModelScope.launch {
            val r = gate.authorize(held, ApprovalInput(code = _state.value.code))
            if (r is ApprovalResult.Denied) {
                _state.update { it.copy(requesting = false, message = r.message) }
                return@launch
            }
            if (rule != null) {
                (alwaysAllow.save(held, rule.first, rule.second) as? ApiResult.Failure)?.let { failed ->
                    _state.update { it.copy(requesting = false, message = "The rule wasn’t saved: ${failed.error.sentence()}") }
                    if (r == ApprovalResult.Proceed) return@launch
                }
            }
            when (r) {
                ApprovalResult.Proceed -> finish(held.id, approvalFor(held, _state.value.body))
                else -> {
                    _state.update { it.copy(requesting = false, finished = it.message == null, code = "") }
                }
            }
        }
    }

    /** "not now" */
    fun decline() {
        val s = _state.value
        val held = s.held ?: return
        if (s.busy || s.decided) return
        _state.update { it.copy(requesting = true, message = null) }
        viewModelScope.launch { finish(held.id, HeldDecision.Decline) }
    }

    private suspend fun finish(heldId: HeldActionId, decision: HeldDecision) {
        when (val r = feed.decideHeld(heldId, decision)) {
            is ApiResult.Success -> {
                _state.update { it.copy(requesting = false, finished = true) }
            }
            is ApiResult.Failure -> _state.update { it.copy(requesting = false, message = r.error.sentence()) }
        }
    }

}
