package net.robog.clarity.app.ui.held

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.HeldRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.VoiceCapture
import net.robog.clarity.app.voice.micChipState
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind

data class DraftReviewState(
    val loading: Boolean = true,
    val held: HeldAction? = null,
    /** The text in the draft field (starts as the agent's draft). */
    val body: String = "",
    /** The conversation with the agent about this draft, oldest first. */
    val talk: List<FeedItem> = emptyList(),
    val live: String? = null,
    val mic: MicChipState = MicChipState.Off,
    /** A decision request is in flight. */
    val requesting: Boolean = false,
    /** The person spoke and the agent has not answered yet. */
    val waitingAgent: Boolean = false,
    /** Approved or declined: the screen can close. */
    val finished: Boolean = false,
    val message: String? = null,
) {
    val email get() = held?.action?.let(::emailOf)
    val decided: Boolean get() = held != null && !held.waiting
    /** The buttons wait while a request is in flight or the agent is revising. */
    val busy: Boolean get() = requesting || waitingAgent
}

/**
 * Review of one held action, most often an email draft: what was asked, what the agent thinks, why
 * it asks, the editable draft, "talk about it" (voice into an AMEND session on this action; the
 * agent revises and the held action updates) and the approve button. Approving asks the
 * [ApprovalGate] first, so the vault can require a biometric without changing this screen.
 */
@Inject
@ViewModelKey(DraftReviewViewModel::class)
@ContributesIntoMap(AppScope::class)
class DraftReviewViewModel(
    private val feed: FeedRepository,
    private val heldRepository: HeldRepository,
    private val sessions: SessionsRepository,
    private val gate: ApprovalGate,
    speech: SpeechInput,
) : ViewModel() {
    private val _state = MutableStateFlow(DraftReviewState())
    val state: StateFlow<DraftReviewState> = _state.asStateFlow()

    private var id: HeldActionId? = null
    private var session: Session? = null
    private var serverBody: String? = null

    private val voice = VoiceCapture(speech) { text ->
        val s = session ?: return@VoiceCapture
        _state.update { it.copy(waitingAgent = true) }
        if (feed.send(text, InputSource.VOICE, s.id) is ApiResult.Failure) {
            _state.update { it.copy(waitingAgent = false, message = "Couldn’t send that. Try again.") }
        }
    }

    init {
        voice.attach(viewModelScope)
        viewModelScope.launch { voice.live.collect { l -> _state.update { it.copy(live = l) } } }
        viewModelScope.launch { voice.mic.collect { m -> _state.update { it.copy(mic = micChipState(m)) } } }
        viewModelScope.launch {
            combine(feed.held, feed.items, feed.agentBusy) { held, items, busy -> Triple(held, items, busy) }
                .collect { (held, items, agentBusy) ->
                    held.firstOrNull { it.id == id }?.let(::adopt)
                    val talk = session?.let { s -> items.filter { it.sessionId == s.id }.sortedBy { it.seq } }.orEmpty()
                    val last = talk.lastOrNull()
                    // The agent has spoken last and is not working: it is our turn again.
                    val answered = last != null && last.author == FeedItem.Author.AGENT && !last.streaming && !agentBusy
                    _state.update { it.copy(talk = talk, waitingAgent = it.waitingAgent && !answered) }
                }
        }
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
                waitingAgent = if (revised) false else it.waitingAgent,
            )
        }
    }

    fun edit(text: String) = _state.update { it.copy(body = text) }

    /** "talk about it": opens (once) an AMEND session on this action and listens for one utterance. */
    fun talk() {
        val held = _state.value.held ?: return
        if (_state.value.busy) return
        viewModelScope.launch {
            if (session == null) {
                when (val r = sessions.start(SessionKind.AMEND, held.id)) {
                    is ApiResult.Success -> session = r.value
                    is ApiResult.Failure -> {
                        _state.update { it.copy(message = r.error.sentence()) }
                        return@launch
                    }
                }
            }
            voice.start(keepOn = false)
        }
    }

    /** Approve (the button reads "send" for mail), with the edited text if the person changed it. */
    fun approve() {
        val s = _state.value
        val held = s.held ?: return
        if (s.busy || s.decided) return
        _state.update { it.copy(requesting = true, message = null) }
        viewModelScope.launch {
            if (!gate.authorize(held)) {
                _state.update { it.copy(requesting = false, message = "Not approved.") }
                return@launch
            }
            finish(held.id, approvalFor(held, _state.value.body))
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
                endTalk()
                _state.update { it.copy(requesting = false, finished = true) }
            }
            is ApiResult.Failure -> _state.update { it.copy(requesting = false, message = r.error.sentence()) }
        }
    }

    /** Leaving the screen without deciding: stop listening and close the conversation. */
    fun close() {
        voice.stop()
        viewModelScope.launch(NonCancellable) { endTalk() }
    }

    private suspend fun endTalk() {
        voice.stop()
        val s = session ?: return
        session = null
        sessions.control(s.id, SessionControl.End)
    }

    fun onMicPermission(granted: Boolean) {
        if (granted) talk()
    }

    override fun onCleared() {
        voice.stop()
    }
}
