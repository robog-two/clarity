package net.robog.clarity.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.push.ConnectionHolder
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechEvent
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.micChipState
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind

/** What the interview screen reads out of the session's feed items. */
data class InterviewView(val question: String?, val answer: String?)

/**
 * The current question is the agent's latest text in the session; the answer is what the person
 * said after it (nothing until they answer the new question).
 */
fun deriveInterview(items: List<FeedItem>, sessionId: SessionId): InterviewView {
    val mine = items.filter { it.sessionId == sessionId }.sortedBy { it.seq }
    val lastAgent = mine.lastOrNull { it.author == FeedItem.Author.AGENT && it.cards.any { c -> c is Card.Text } }
    val question = lastAgent?.cards?.filterIsInstance<Card.Text>()?.joinToString("\n\n") { it.text }
    val answer = mine
        .lastOrNull { it.author == FeedItem.Author.USER && (lastAgent == null || it.seq > lastAgent.seq) && it.cards.any { c -> c is Card.Said } }
        ?.cards?.filterIsInstance<Card.Said>()?.joinToString(" ") { it.text }
    return InterviewView(question, answer)
}

/** The label above a captured line ("value", "hard line", ...). */
fun sectionLabel(section: Section): String = when (section) {
    Section.VALUES -> "value"
    Section.HARD_LINES -> "hard line"
    Section.AUTONOMY -> "how much I do alone"
    Section.COLLISIONS -> "priority"
    Section.PEOPLE -> "people"
    Section.REPAIR -> "repair + memory"
}

sealed interface InterviewPhase {
    data object Idle : InterviewPhase
    data object Starting : InterviewPhase
    data object Active : InterviewPhase
    /** The server cannot run the interview yet (route missing). */
    data object Unavailable : InterviewPhase
    data class Failed(val error: ApiError) : InterviewPhase
    /** The last question was kept; the constitution is ready to review. */
    data object Finished : InterviewPhase
}

data class ValuesState(
    val phase: InterviewPhase = InterviewPhase.Idle,
    val sessionId: SessionId? = null,
    /** 0-based. */
    val index: Int = 0,
    val count: Int = 0,
    val question: String? = null,
    /** Words heard so far, not yet final (the dashed bubble). */
    val live: String? = null,
    /** The finished answer to the current question (the solid bubble). */
    val said: String? = null,
    val captured: Line? = null,
    val busy: Boolean = false,
    val mic: MicChipState = MicChipState.Off,
) {
    val position: String get() = if (count > 0) "${index + 1} of $count" else ""
    /** "reword" and "keep" need a captured line and an idle agent. */
    val canDecide: Boolean get() = phase == InterviewPhase.Active && captured != null && !busy
}

/** Step 2: the spoken values interview, driven by a `VALUES_INTERVIEW` session. */
@Inject
@ViewModelKey(ValuesStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ValuesStepViewModel(
    private val sessions: SessionsRepository,
    private val feed: FeedRepository,
    private val speech: SpeechInput,
    private val connection: ConnectionHolder,
) : ViewModel() {
    private val _state = MutableStateFlow(ValuesState())
    val state: StateFlow<ValuesState> = _state.asStateFlow()

    private var observers: Job? = null

    /** Starts a new interview unless one is already running. */
    fun begin() {
        val phase = _state.value.phase
        if (phase == InterviewPhase.Starting || phase == InterviewPhase.Active) return
        restart()
    }

    /** "redo": throw the old interview away and start again. */
    fun restart() {
        _state.value = ValuesState(phase = InterviewPhase.Starting)
        viewModelScope.launch {
            when (val r = sessions.start(SessionKind.VALUES_INTERVIEW)) {
                is ApiResult.Success -> {
                    applySession(r.value)
                    observe(r.value.id)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(
                        phase = if (r.error is ApiError.NotFound) InterviewPhase.Unavailable else InterviewPhase.Failed(r.error),
                    )
                }
            }
        }
    }

    private fun applySession(s: Session) = _state.update {
        it.copy(
            phase = if (s.state == Session.State.ENDED) InterviewPhase.Finished else InterviewPhase.Active,
            sessionId = s.id,
            index = s.question ?: it.index,
            count = s.questionCount ?: it.count,
            captured = s.capturedLine,
            busy = s.state == Session.State.BUSY,
        )
    }

    private fun observe(id: SessionId) {
        observers?.cancel()
        observers = viewModelScope.launch {
            // Onboarding comes before the main screen and the push service, so nothing else has the
            // feed open yet: without this the agent's questions and replies never reach the phone.
            launch { connection.holdFeed() }
            launch {
                feed.items.collect { items ->
                    val view = deriveInterview(items, id)
                    _state.update { it.copy(question = view.question, said = view.answer) }
                }
            }
            launch {
                // The change signal only travels on the live stream and is lost while it reconnects,
                // so a new item in this session (also filled in after a gap) reloads the session too.
                val newItem = feed.items
                    .map { items -> items.filter { it.sessionId == id }.maxOfOrNull { it.seq } }
                    .distinctUntilChanged()
                    .drop(1)
                val changed = feed.signals.filterIsInstance<SyncSignal.SessionChanged>().filter { it.sessionId == id }
                merge(newItem, changed).collect {
                    (sessions.get(id) as? ApiResult.Success)?.let { applySession(it.value) }
                }
            }
            launch {
                speech.events.collect { event ->
                    when (event) {
                        is SpeechEvent.Partial -> _state.update { it.copy(live = event.text) }
                        is SpeechEvent.Final -> {
                            _state.update { it.copy(live = null) }
                            if (event.text.isNotBlank()) feed.send(event.text.trim(), InputSource.VOICE, id)
                        }
                        else -> Unit
                    }
                }
            }
            launch {
                speech.mic.collect { mic -> _state.update { it.copy(mic = micChipState(mic)) } }
            }
        }
    }

    /** The microphone permission is granted: the mic stays on until the person moves on. */
    fun startListening() = speech.start(keepOn = true)

    fun stopListening() = speech.stop()

    fun mute() = speech.mute()
    fun unmute() = speech.unmute()

    /** Typing instead of talking. */
    fun sendTyped(text: String) {
        val id = _state.value.sessionId ?: return
        if (text.isBlank()) return
        viewModelScope.launch { feed.send(text.trim(), InputSource.TYPED, id) }
    }

    fun reword() = control(SessionControl.Reword)

    /** "keep →": the line is right, on to the next question (or to the review after the last). */
    fun keep() = control(SessionControl.Next)

    private fun control(c: SessionControl) {
        val id = _state.value.sessionId ?: return
        if (!_state.value.canDecide) return
        _state.update { it.copy(busy = true, live = null) }
        viewModelScope.launch {
            when (val r = sessions.control(id, c)) {
                is ApiResult.Success -> applySession(r.value)
                is ApiResult.Failure -> _state.update { it.copy(busy = false, phase = InterviewPhase.Failed(r.error)) }
            }
        }
    }

    override fun onCleared() {
        speech.stop()
    }
}
