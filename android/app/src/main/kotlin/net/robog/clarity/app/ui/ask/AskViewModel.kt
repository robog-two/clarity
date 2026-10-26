package net.robog.clarity.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.ui.held.ApprovalGate
import net.robog.clarity.app.ui.held.ApprovalInput
import net.robog.clarity.app.ui.held.ApprovalResult
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechError
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.VoiceCapture
import net.robog.clarity.app.voice.micChipState
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision

/** "try saying" while the conversation is empty. */
val AskSuggestions: List<String> = listOf(
    "Move my call with Dana to Thursday",
    "What’s left for me today?",
    "Did Alex send the deck yet?",
)

/** The main feed: everything that is not part of a call, interview or amendment. */
fun askItems(items: List<FeedItem>): List<FeedItem> = items.filter { it.sessionId == null }.sortedBy { it.seq }

/** Suggestions only while there is nothing else on screen and the agent is not working. */
fun showSuggestions(items: List<FeedItem>, busy: Boolean, live: String?): Boolean = items.isEmpty() && !busy && live == null

data class AskUiState(
    val items: List<FeedItem> = emptyList(),
    val held: Map<HeldActionId, HeldAction> = emptyMap(),
    val tasks: Map<TaskId, AgentTask> = emptyMap(),
    val draft: String = "",
    val live: String? = null,
    val mic: MicChipState = MicChipState.Off,
    val micProblem: SpeechError? = null,
    /** The agent is thinking or working. */
    val busy: Boolean = false,
    val sync: SyncState = SyncState.Connecting,
    val message: String? = null,
) {
    val canSend: Boolean get() = draft.isNotBlank()
    val suggestions: List<String> get() = if (showSuggestions(items, busy, live)) AskSuggestions else emptyList()
}

/** Ask: type or talk to the agent; its answers and proposals appear inline. */
@Inject
@ViewModelKey(AskViewModel::class)
@ContributesIntoMap(AppScope::class)
class AskViewModel(
    private val feed: FeedRepository,
    private val gate: ApprovalGate,
    speech: SpeechInput,
) : ViewModel() {
    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private val voice = VoiceCapture(speech) { text -> deliver(text, InputSource.VOICE) }

    init {
        voice.attach(viewModelScope)
        viewModelScope.launch { voice.live.collect { l -> _state.update { it.copy(live = l) } } }
        viewModelScope.launch { voice.error.collect { e -> _state.update { it.copy(micProblem = e) } } }
        viewModelScope.launch { voice.mic.collect { m -> _state.update { it.copy(mic = micChipState(m)) } } }
        viewModelScope.launch {
            combine(feed.items, feed.held, feed.tasks, feed.agentBusy, feed.syncState) { items, held, tasks, busy, sync ->
                AskUiState(
                    items = askItems(items),
                    held = held.associateBy { it.id },
                    tasks = tasks.associateBy { it.id },
                    busy = busy,
                    sync = sync,
                )
            }.collect { fresh ->
                _state.update { it.copy(items = fresh.items, held = fresh.held, tasks = fresh.tasks, busy = fresh.busy, sync = fresh.sync) }
            }
        }
    }

    fun onDraft(text: String) = _state.update { it.copy(draft = text) }

    /** The send button (or the keyboard's send action). */
    fun submit() {
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(draft = "") }
        viewModelScope.launch { deliver(text, InputSource.TYPED) }
    }

    /** A "try saying" suggestion. */
    fun suggest(text: String) {
        viewModelScope.launch { deliver(text, InputSource.TYPED) }
    }

    /** The talk button: listen for one utterance and send it. */
    fun talk() {
        if (_state.value.mic == MicChipState.Off) voice.start(keepOn = false) else voice.stop()
    }

    /** Buttons on a proposal; approving first asks the [ApprovalGate]. */
    fun decide(id: HeldActionId, decision: HeldDecision) {
        viewModelScope.launch {
            val held = _state.value.held[id]
            if (decision is HeldDecision.Approve && held != null) {
                when (val r = gate.authorize(held, ApprovalInput())) {
                    ApprovalResult.Proceed -> Unit
                    ApprovalResult.Done -> { _state.update { it.copy(message = null) }; return@launch }
                    is ApprovalResult.Denied -> { _state.update { it.copy(message = r.message) }; return@launch }
                }
            }
            when (val r = feed.decideHeld(id, decision)) {
                is ApiResult.Success -> _state.update { it.copy(message = null) }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error.sentence()) }
            }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private suspend fun deliver(text: String, source: InputSource) {
        when (val r = feed.send(text, source, null)) {
            is ApiResult.Success -> _state.update { it.copy(message = null) }
            is ApiResult.Failure -> _state.update { it.copy(message = r.error.sentence(), draft = if (source == InputSource.TYPED && it.draft.isEmpty()) text else it.draft) }
        }
    }

    fun onMicPermission(granted: Boolean) {
        if (granted) voice.start(keepOn = false)
    }

    override fun onCleared() {
        voice.stop()
    }
}
