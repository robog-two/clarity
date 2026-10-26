package net.robog.clarity.app.ui.amend

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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.AmendmentsRepository
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.ui.Toasts
import net.robog.clarity.app.ui.held.waiting
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.VoiceCapture
import net.robog.clarity.app.voice.micChipState
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionKind

/** The agent's opening question when it has not said anything yet. */
fun fallbackQuestion(heldPresent: Boolean): String =
    if (heldPresent) "This hit one of your lines. Should it have?" else "What would you like to change?"

/** True once the agent has said something in the conversation. */
fun agentHasSpoken(talk: List<FeedItem>): Boolean =
    talk.any { it.author == FeedItem.Author.AGENT && it.cards.any { c -> c is Card.Text } }

/** "change to a line · writing" / "new line · people": the caption of the proposed change box. */
fun proposalLabel(amendment: Amendment): String =
    (if (amendment.lineId == null) "new line" else "change to a line") + " · " + amendment.proposed.section.name.lowercase().replace('_', ' ')

/** "Rule saved. Constitution v3." */
fun ruleSavedToast(version: Int): String = "Rule saved. Constitution v$version."

data class AmendUiState(
    val loading: Boolean = true,
    /** Set when the amendment started from a held action; null when amending the constitution by talking. */
    val held: HeldAction? = null,
    val session: Session? = null,
    val talk: List<FeedItem> = emptyList(),
    val live: String? = null,
    val mic: MicChipState = MicChipState.Off,
    val proposal: Amendment? = null,
    val waitingAgent: Boolean = false,
    val working: Boolean = false,
    val finished: Boolean = false,
    /** The held action was already decided: nothing left to talk about. */
    val settled: Boolean = false,
    val message: String? = null,
) {
    val busy: Boolean get() = working || waitingAgent || session?.state == Session.State.BUSY
    val canKeep: Boolean get() = !busy && session != null
    val canSave: Boolean get() = !busy && proposal?.status == Amendment.Status.PROPOSED
}

/**
 * Talks a held action through (or amends the constitution by talking): an AMEND session, the
 * person's voice answers, the agent's proposed change, and the two ways out: keep as is, or save
 * the rule (the amendment save route).
 */
@Inject
@ViewModelKey(AmendViewModel::class)
@ContributesIntoMap(AppScope::class)
class AmendViewModel(
    private val sessions: SessionsRepository,
    private val feed: FeedRepository,
    private val amendments: AmendmentsRepository,
    private val toasts: Toasts,
    speech: SpeechInput,
) : ViewModel() {
    private val _state = MutableStateFlow(AmendUiState())
    val state: StateFlow<AmendUiState> = _state.asStateFlow()

    private var started = false
    private var heldId: HeldActionId? = null
    private var lastCheckedSeq = 0L

    private val voice = VoiceCapture(speech) { text ->
        val s = _state.value.session ?: return@VoiceCapture
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
            combine(feed.items, feed.held, feed.agentBusy) { items, held, busy -> Triple(items, held, busy) }
                .collect { (items, held, agentBusy) ->
                    val session = _state.value.session
                    val talk = session?.let { s -> items.filter { it.sessionId == s.id }.sortedBy { it.seq } }.orEmpty()
                    val last = talk.lastOrNull()
                    val answered = last != null && last.author == FeedItem.Author.AGENT && !last.streaming && !agentBusy
                    _state.update {
                        it.copy(
                            talk = talk,
                            held = held.firstOrNull { h -> h.id == heldId } ?: it.held,
                            waitingAgent = it.waitingAgent && !answered,
                        )
                    }
                    // The agent may have proposed a change while answering.
                    if (answered && session != null && last!!.seq != lastCheckedSeq) {
                        lastCheckedSeq = last.seq
                        refreshSession(session)
                    }
                }
        }
        viewModelScope.launch {
            feed.signals.filterIsInstance<SyncSignal.SessionChanged>().collect { s ->
                val current = _state.value.session ?: return@collect
                if (s.sessionId == current.id) refreshSession(current)
            }
        }
    }

    /** Starts the amend session; [id] is the held action, or null to amend the constitution by talking. */
    fun load(id: HeldActionId?) {
        if (started) return
        started = true
        heldId = id
        viewModelScope.launch {
            val held = id?.let { hid -> feed.held.first().firstOrNull { it.id == hid } }
            _state.update { it.copy(held = held) }
            if (held != null && !held.waiting) {
                _state.update { it.copy(loading = false, settled = true) }
                return@launch
            }
            when (val r = sessions.start(SessionKind.AMEND, id)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loading = false, session = r.value) }
                    refreshSession(r.value)
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, message = r.error.sentence()) }
            }
        }
    }

    /** The mic stays on while this screen is up, so the answer can simply be spoken. */
    fun listen() {
        if (_state.value.session != null && _state.value.mic == MicChipState.Off) voice.start(keepOn = true)
    }

    private suspend fun refreshSession(current: Session) {
        val fresh = (sessions.get(current.id) as? ApiResult.Success)?.value ?: return
        val amendmentId = fresh.amendmentId
        val proposal = amendmentId?.let { (amendments.proposed(it) as? ApiResult.Success)?.value }
        _state.update { it.copy(session = fresh, proposal = proposal ?: it.proposal.takeIf { p -> p?.id == amendmentId }) }
    }

    /** "keep as is": the line was right. Declines the proposal and closes the conversation. */
    fun keep() {
        val s = _state.value
        if (!s.canKeep) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            heldId?.let { id ->
                val held = s.held
                if (held == null || held.waiting) {
                    val r = feed.decideHeld(id, HeldDecision.Keep)
                    if (r is ApiResult.Failure) {
                        _state.update { it.copy(working = false, message = r.error.sentence()) }
                        return@launch
                    }
                }
            }
            s.proposal?.let { amendments.discard(it.id) }
            endSession()
            _state.update { it.copy(working = false, finished = true) }
        }
    }

    /** "save rule": applies the proposed change; the reply carries the new constitution version. */
    fun save() {
        val s = _state.value
        val proposal = s.proposal ?: return
        if (!s.canSave) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            when (val r = amendments.save(proposal.id)) {
                is ApiResult.Success -> {
                    toasts.show(ruleSavedToast(r.value.version))
                    voice.stop()
                    _state.update { it.copy(working = false, finished = true) }
                }
                is ApiResult.Failure -> _state.update { it.copy(working = false, message = r.error.sentence()) }
            }
        }
    }

    /** Leaving without a decision: stop listening and end the conversation. */
    fun close() {
        voice.stop()
        if (!_state.value.finished) viewModelScope.launch(NonCancellable) { endSession() }
    }

    private suspend fun endSession() {
        voice.stop()
        val s = _state.value.session ?: return
        sessions.control(s.id, SessionControl.End)
    }

    override fun onCleared() {
        voice.stop()
    }
}
