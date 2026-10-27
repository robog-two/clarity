package net.robog.clarity.app.ui.call

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.call.CallNotifier
import net.robog.clarity.app.call.CallService
import net.robog.clarity.app.call.SNOOZE_MINUTES
import net.robog.clarity.app.data.seenSettings
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.push.ConnectionHolder
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.app.voice.MicChipState
import net.robog.clarity.app.voice.SpeechError
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.VoiceCapture
import net.robog.clarity.app.voice.micChipState
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionKind

/** The screens of `Call.dc.html`. */
enum class CallScreen { Loading, Incoming, Snoozed, InCall, Ended, Replay }

data class CallUiState(
    val screen: CallScreen = CallScreen.Loading,
    /** The scheduled time, "9:00". */
    val callTime: String = "",
    /** When a declined call rings again, "9:15"; null if unknown (arrived while already snoozed). */
    val snoozedUntil: String? = null,
    val session: Session? = null,
    /** Items of this call, oldest first. */
    val items: List<FeedItem> = emptyList(),
    val tasks: Map<TaskId, AgentTask> = emptyMap(),
    val held: Map<HeldActionId, HeldAction> = emptyMap(),
    /** The dashed bubble with the words heard so far. */
    val live: String? = null,
    val mic: MicChipState = MicChipState.Off,
    val pushToTalk: Boolean = false,
    val micProblem: SpeechError? = null,
    /** What the person is typing in the call. */
    val draft: String = "",
    /** True while a control request is in flight. */
    val working: Boolean = false,
    val message: String? = null,
) {
    val stage: CallStage? get() = session?.stage
    val muted: Boolean get() = mic == MicChipState.Muted
    val canAdvance: Boolean get() = primaryEnabled(session) && !working
    val plan: List<IfThen> get() = planOf(items)
    val setup: List<AgentTask> get() = setupTasks(items, tasks)
    val startedAt: Instant? get() = session?.startedAt
}

/**
 * The morning call. It starts from the incoming screen (or straight into the call when a session is
 * already running), drives the session with `Next`/`End`/`Snooze`, and shows the session's feed items
 * live. Speech goes out as voice input on the session; the agent answers in text.
 */
@Inject
@ViewModelKey(CallViewModel::class)
@ContributesIntoMap(AppScope::class)
class CallViewModel(
    private val sessions: SessionsRepository,
    private val feed: FeedRepository,
    private val settings: SettingsRepository,
    private val calls: CallService,
    private val notifier: CallNotifier,
    speech: SpeechInput,
    private val clock: AppClock,
    private val connection: ConnectionHolder,
) : ViewModel() {
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val zone: TimeZone get() = clock.zone()
    private var micAlwaysOn = true
    private var foreground = true

    private val voice = VoiceCapture(speech) { text ->
        val id = _state.value.session?.id ?: return@VoiceCapture
        feed.send(text, InputSource.VOICE, id)
    }

    init {
        voice.attach(viewModelScope)
        viewModelScope.launch { voice.live.collect { live -> _state.update { it.copy(live = live) } } }
        viewModelScope.launch { voice.error.collect { e -> _state.update { it.copy(micProblem = e) } } }
        viewModelScope.launch {
            voice.mic.collect { mic -> _state.update { it.copy(mic = micChipState(mic)) } }
        }
        viewModelScope.launch {
            combine(feed.items, feed.tasks, feed.held, _state) { items, tasks, held, s ->
                val id = s.session?.id
                Triple(
                    if (id == null) emptyList() else items.filter { it.sessionId == id }.sortedBy { it.seq },
                    tasks.associateBy { it.id },
                    held.associateBy { it.id },
                )
            }.collect { (items, tasks, held) ->
                _state.update { if (it.items == items && it.tasks == tasks && it.held == held) it else it.copy(items = items, tasks = tasks, held = held) }
            }
        }
        viewModelScope.launch {
            feed.signals.filterIsInstance<SyncSignal.SessionChanged>().collect { signal ->
                val current = _state.value.session ?: return@collect
                if (signal.sessionId == current.id) refreshSession(current)
            }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        (settings.load() as? ApiResult.Success)?.value?.let { s ->
            micAlwaysOn = s.micAlwaysOn
            val seen = seenSettings(s, zone, clock.now())
            _state.update { it.copy(callTime = formatTime(seen.morningCall.at), pushToTalk = !s.micAlwaysOn) }
        }
        val active = (sessions.active(SessionKind.MORNING_CALL) as? ApiResult.Success)?.value
        when (active?.state) {
            null, Session.State.ENDED -> _state.update { it.copy(screen = CallScreen.Incoming) }
            Session.State.SNOOZED -> _state.update { it.copy(screen = CallScreen.Snoozed, session = active) }
            Session.State.ACTIVE, Session.State.BUSY -> enterCall(active)
        }
    }

    /** "answer" / "call me now". Starts the session unless one is already running. */
    fun answer() {
        val s = _state.value
        if (s.working || s.screen == CallScreen.InCall) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            val running = s.session?.takeIf { it.state == Session.State.ACTIVE || it.state == Session.State.BUSY }
            when (val r = if (running != null) ApiResult.Success(running) else sessions.start(SessionKind.MORNING_CALL)) {
                is ApiResult.Success -> enterCall(r.value)
                is ApiResult.Failure -> _state.update { it.copy(working = false, message = r.error.sentence()) }
            }
        }
    }

    private fun enterCall(session: Session) {
        notifier.cancelIncoming()
        _state.update { it.copy(screen = CallScreen.InCall, session = session, working = false, message = null, snoozedUntil = null) }
        if (foreground) voice.start(keepOn = micAlwaysOn)
    }

    /** "not now": snooze for 15 minutes. */
    fun decline() {
        if (_state.value.working) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            val result = calls.snooze(SNOOZE_MINUTES)
            val until = formatClock(clock.now() + SNOOZE_MINUTES.minutes, zone)
            _state.update {
                it.copy(
                    screen = CallScreen.Snoozed,
                    working = false,
                    snoozedUntil = until,
                    session = (result as? ApiResult.Success)?.value ?: it.session,
                    // The local alarm is set either way; only mention a server problem.
                    message = (result as? ApiResult.Failure)?.error?.takeUnless { e -> e is net.robog.clarity.app.data.ApiError.NotFound }?.sentence(),
                )
            }
        }
    }

    /** The primary button: next stage, or end the call at the last one. */
    fun advance() {
        val s = _state.value
        val session = s.session ?: return
        if (!s.canAdvance) return
        _state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            when (val r = sessions.control(session.id, primaryControl(session.stage))) {
                is ApiResult.Success -> applySession(r.value)
                is ApiResult.Failure -> _state.update { it.copy(working = false, message = r.error.sentence()) }
            }
        }
    }

    /** mute / unmute, or "talk" when the mic is push to talk. */
    fun toggleMute() {
        val s = _state.value
        when {
            s.pushToTalk -> voice.start(keepOn = false)
            s.muted -> voice.unmute()
            else -> voice.mute()
        }
    }

    fun onDraft(text: String) = _state.update { it.copy(draft = text) }

    /** The composer's send: typed text goes to the call like speech does. A failed send gives the text back. */
    fun sendTyped() {
        val s = _state.value
        val id = s.session?.id ?: return
        val text = s.draft.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(draft = "") }
        viewModelScope.launch {
            when (val r = feed.send(text, InputSource.TYPED, id)) {
                is ApiResult.Success -> _state.update { it.copy(message = null) }
                is ApiResult.Failure -> _state.update { it.copy(message = r.error.sentence(), draft = it.draft.ifEmpty { text }) }
            }
        }
    }

    fun replay() = _state.update { it.copy(screen = CallScreen.Replay) }
    fun closeReplay() = _state.update { it.copy(screen = CallScreen.Ended) }

    /** The call screen left or came back to the foreground; the mic only listens in front. */
    fun setForeground(inFront: Boolean) {
        if (inFront == foreground) return
        foreground = inFront
        val s = _state.value
        if (s.screen != CallScreen.InCall) return
        if (inFront) {
            notifier.cancelOngoing()
            voice.start(keepOn = micAlwaysOn)
        } else {
            voice.stop()
            s.session?.let { notifier.ongoing(it.id.value) }
        }
    }

    /** Called when the system microphone dialog closes. */
    fun onMicPermission(granted: Boolean) {
        if (granted && _state.value.screen == CallScreen.InCall && foreground) voice.start(keepOn = micAlwaysOn)
    }

    private suspend fun refreshSession(current: Session) {
        (sessions.get(current.id) as? ApiResult.Success)?.value?.let { applySession(it) }
    }

    private fun applySession(session: Session) {
        val ended = callEnded(session)
        if (ended) {
            voice.stop()
            notifier.cancelOngoing()
        }
        _state.update {
            it.copy(
                session = session,
                working = false,
                screen = when {
                    ended && it.screen != CallScreen.Replay -> CallScreen.Ended
                    session.state == Session.State.SNOOZED -> CallScreen.Snoozed
                    else -> it.screen
                },
            )
        }
    }

    /**
     * Keeps the cache current while the call screen is in front. Holds the feed connection that the
     * main app and the push service share, so the phone never has two streams open at once.
     */
    suspend fun keepInSync() = connection.holdFeed()

    override fun onCleared() {
        voice.stop()
        notifier.cancelOngoing()
    }
}
