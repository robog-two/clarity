package net.robog.clarity.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.ui.components.ChatEntry
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.ui.held.AlwaysAllow
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

/**
 * Sessions whose items also show in Ask. Calls, interviews and amendments all run on the lead
 * agent's one thread, so they are part of the same conversation.
 */
val AskSessionKinds: Set<SessionKind> = setOf(SessionKind.MORNING_CALL, SessionKind.VALUES_INTERVIEW, SessionKind.AMEND)

/**
 * The main conversation: items outside any session, plus items of sessions in [AskSessionKinds].
 * An item of a session not in [sessions] yet stays hidden until its kind is known.
 */
fun askItems(items: List<FeedItem>, sessions: Map<SessionId, Session> = emptyMap()): List<FeedItem> =
    items.filter { item -> item.sessionId.let { it == null || sessions[it]?.kind in AskSessionKinds } }.sortedBy { it.seq }

/** The sessions [items] belong to. */
fun sessionIds(items: List<FeedItem>): Set<SessionId> = items.mapNotNullTo(mutableSetOf()) { it.sessionId }

/** "Morning meeting · 9:00 am", with the date when it was not [today]. */
fun sessionLabel(session: Session, zone: TimeZone, today: LocalDate): String {
    val name = when (session.kind) {
        SessionKind.MORNING_CALL -> "Morning meeting"
        SessionKind.VALUES_INTERVIEW -> "Values interview"
        SessionKind.AMEND -> "Talking it through"
    }
    val local = session.startedAt.toLocalDateTime(zone)
    val time = formatTime(local.time)
    val day = local.date
    val month = day.month.name.lowercase().replaceFirstChar { it.titlecase() }.take(3)
    return if (day == today) "$name · $time" else "$name · $month ${day.day}, $time"
}

/**
 * [items] (from [askItems], oldest first) with a labelled divider where a session starts and a
 * plain one where the main conversation picks up again.
 */
fun askEntries(items: List<FeedItem>, sessions: Map<SessionId, Session>, zone: TimeZone, today: LocalDate): List<ChatEntry> {
    val entries = mutableListOf<ChatEntry>()
    var current: SessionId? = null
    for (item in items) {
        val id = item.sessionId
        if (id != current) {
            val session = id?.let { sessions[it] }
            when {
                session != null -> entries += ChatEntry.Divider("start-$id-${item.seq}", sessionLabel(session, zone, today))
                current != null -> entries += ChatEntry.Divider("end-$current-${item.seq}", null)
            }
            current = id
        }
        entries += ChatEntry.Message(item)
    }
    return entries
}

/** Suggestions only while there is nothing else on screen and the agent is not working. */
fun showSuggestions(items: List<FeedItem>, busy: Boolean, live: String?): Boolean = items.isEmpty() && !busy && live == null

data class AskUiState(
    val items: List<FeedItem> = emptyList(),
    /** [items] with the session dividers, for the transcript. */
    val entries: List<ChatEntry> = emptyList(),
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
    private val alwaysAllow: AlwaysAllow,
    private val sessions: SessionsRepository,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    /** Sessions seen in the feed, looked up once each; a session's kind never changes. */
    private val known = MutableStateFlow<Map<SessionId, Session>>(emptyMap())

    private val voice = VoiceCapture(speech) { text -> deliver(text, InputSource.VOICE) }

    init {
        voice.attach(viewModelScope)
        viewModelScope.launch { voice.live.collect { l -> _state.update { it.copy(live = l) } } }
        viewModelScope.launch { voice.error.collect { e -> _state.update { it.copy(micProblem = e) } } }
        viewModelScope.launch { voice.mic.collect { m -> _state.update { it.copy(mic = micChipState(m)) } } }
        viewModelScope.launch {
            // Looks sessions up as their ids appear; one that failed is tried again when the set of ids changes.
            feed.items.map(::sessionIds).distinctUntilChanged().collect { ids ->
                for (id in ids - known.value.keys) {
                    (sessions.get(id) as? ApiResult.Success)?.value?.let { s -> known.update { it + (id to s) } }
                }
            }
        }
        viewModelScope.launch {
            val conversation = combine(feed.items, known) { items, sessions ->
                val zone = clock.zone()
                val shown = askItems(items, sessions)
                shown to askEntries(shown, sessions, zone, clock.now().toLocalDateTime(zone).date)
            }
            combine(conversation, feed.held, feed.tasks, feed.agentBusy, feed.syncState) { (items, entries), held, tasks, busy, sync ->
                AskUiState(
                    items = items,
                    entries = entries,
                    held = held.associateBy { it.id },
                    tasks = tasks.associateBy { it.id },
                    busy = busy,
                    sync = sync,
                )
            }.collect { fresh ->
                _state.update { it.copy(items = fresh.items, entries = fresh.entries, held = fresh.held, tasks = fresh.tasks, busy = fresh.busy, sync = fresh.sync) }
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

    /** "approve automatically from now on": asks the gate, saves the (edited) rule, then approves this one. */
    fun approveAlways(id: HeldActionId, text: String, because: String) {
        viewModelScope.launch {
            val held = _state.value.held[id] ?: return@launch
            val r = gate.authorize(held, ApprovalInput())
            if (r is ApprovalResult.Denied) { _state.update { it.copy(message = r.message) }; return@launch }
            (alwaysAllow.save(held, text, because) as? ApiResult.Failure)?.let { failed ->
                _state.update { it.copy(message = "The rule wasn’t saved: ${failed.error.sentence()}") }
                if (r == ApprovalResult.Proceed) return@launch
            }
            if (r == ApprovalResult.Proceed) {
                when (val d = feed.decideHeld(id, HeldDecision.Approve())) {
                    is ApiResult.Success -> _state.update { it.copy(message = null) }
                    is ApiResult.Failure -> _state.update { it.copy(message = d.error.sentence()) }
                }
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
