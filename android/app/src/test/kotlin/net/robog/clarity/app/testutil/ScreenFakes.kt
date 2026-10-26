package net.robog.clarity.app.testutil

import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.robog.clarity.app.call.CallAlarms
import net.robog.clarity.app.call.AlarmSlot
import net.robog.clarity.app.call.CallNotifier
import net.robog.clarity.app.call.CallSettingsCache
import net.robog.clarity.app.call.IncomingPresentation
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.FeedRepository
import net.robog.clarity.app.data.SessionsRepository
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.voice.MicInput
import net.robog.clarity.app.voice.MicState
import net.robog.clarity.app.voice.SpeechEvent
import net.robog.clarity.app.voice.SpeechInput
import net.robog.clarity.app.voice.micStep
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings

@OptIn(ExperimentalCoroutinesApi::class)
inline fun withMain(block: () -> Unit) {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    try { block() } finally { Dispatchers.resetMain() }
}

class FixedClock(var now: Instant = T0, var zone: kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.UTC) : AppClock {
    override fun now(): Instant = now
    override fun zone(): kotlinx.datetime.TimeZone = zone
}

/** A feed with mutable flows; records what the screens send and decide. */
class ScreenFeed : FeedRepository {
    val itemsFlow = MutableStateFlow<List<FeedItem>>(emptyList())
    val tasksFlow = MutableStateFlow<List<AgentTask>>(emptyList())
    val heldFlow = MutableStateFlow<List<HeldAction>>(emptyList())
    val busyFlow = MutableStateFlow(false)
    val signalFlow = MutableSharedFlow<SyncSignal>(extraBufferCapacity = 8)
    val sent = mutableListOf<Triple<String, InputSource, SessionId?>>()
    val decisions = mutableListOf<Pair<HeldActionId, HeldDecision>>()
    var sendResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var decideResult: ApiResult<Unit> = ApiResult.Success(Unit)

    override val items: Flow<List<FeedItem>> = itemsFlow
    override val tasks: Flow<List<AgentTask>> = tasksFlow
    override val held: Flow<List<HeldAction>> = heldFlow
    override val syncState: Flow<SyncState> = MutableStateFlow(SyncState.Live)
    override val agentBusy: Flow<Boolean> = busyFlow
    override val signals: Flow<SyncSignal> = signalFlow

    override suspend fun send(text: String, source: InputSource, sessionId: SessionId?, clientId: ClientInputId?): ApiResult<InputAccepted> {
        sent += Triple(text, source, sessionId)
        val failed = (sendResult as? ApiResult.Failure) ?: run {
            val item = userItem(1000L + sent.size, text, sessionId)
            itemsFlow.value = itemsFlow.value + item
            return ApiResult.Success(InputAccepted(item))
        }
        return failed
    }

    override suspend fun decideHeld(id: HeldActionId, decision: HeldDecision): ApiResult<Unit> {
        decisions += id to decision
        return decideResult
    }

    override suspend fun sync() = Unit
}

class ScreenSpeech : SpeechInput {
    val flow = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
    override val events: Flow<SpeechEvent> = flow
    val micState = MutableStateFlow(MicState())
    override val mic = micState
    val calls = mutableListOf<String>()
    override fun start(keepOn: Boolean) { calls += "start($keepOn)"; micState.value = micStep(micState.value, MicInput.Start(keepOn)).first }
    override fun stop() { calls += "stop"; micState.value = MicState() }
    override fun mute() { calls += "mute"; micState.value = micStep(micState.value, MicInput.Mute).first }
    override fun unmute() { calls += "unmute"; micState.value = micStep(micState.value, MicInput.Unmute).first }
    override fun release() = Unit
}

fun callSession(
    state: Session.State = Session.State.ACTIVE,
    stage: CallStage? = CallStage.CLEAR,
    id: String = "call1",
    kind: SessionKind = SessionKind.MORNING_CALL,
) = Session(SessionId(id), kind, state, T0, stage = stage)

/** Sessions with a small state machine like the server's: Next advances, End ends, Snooze snoozes. */
class ScriptedSessions(var active: Session? = null) : SessionsRepository {
    val started = mutableListOf<Pair<SessionKind, HeldActionId?>>()
    val controls = mutableListOf<SessionControl>()
    var startResult: ApiResult<Session>? = null
    var controlFailure: ApiError? = null
    /** After `Next` the session is BUSY until [finishStage] (the agent is producing the stage). */
    var busyAfterNext = false
    var current: Session? = active

    override suspend fun start(kind: SessionKind, heldActionId: HeldActionId?): ApiResult<Session> {
        started += kind to heldActionId
        startResult?.let { return it }
        val s = callSession(kind = kind).also { current = it }
        return ApiResult.Success(s)
    }

    override suspend fun get(id: SessionId): ApiResult<Session> =
        current?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.NotFound(null))

    override suspend fun control(id: SessionId, control: SessionControl): ApiResult<Session> {
        controls += control
        controlFailure?.let { return ApiResult.Failure(it) }
        val s = current ?: return ApiResult.Failure(ApiError.NotFound(null))
        val next = when (control) {
            SessionControl.Next -> s.copy(
                stage = CallStage.entries.getOrElse((s.stage?.ordinal ?: 0) + 1) { CallStage.HAND_OFF },
                state = if (busyAfterNext) Session.State.BUSY else s.state,
            )
            SessionControl.End -> s.copy(state = Session.State.ENDED, endedAt = T0)
            is SessionControl.Snooze -> s.copy(state = Session.State.SNOOZED)
            SessionControl.Reword -> s
        }
        current = next
        return ApiResult.Success(next)
    }

    override suspend fun active(kind: SessionKind): ApiResult<Session?> =
        ApiResult.Success(current?.takeIf { it.kind == kind && it.state != Session.State.ENDED })

    fun finishStage() { current = current?.copy(state = Session.State.ACTIVE) }
}

class ScreenSettings(var loaded: ApiResult<Settings> = ApiResult.Success(Settings()), var saveResult: ApiResult<Unit> = ApiResult.Success(Unit)) : SettingsRepository {
    val saved = mutableListOf<Settings>()
    override suspend fun load() = loaded
    override suspend fun save(settings: Settings): ApiResult<Unit> {
        saved += settings
        return saveResult
    }
}

class FakeAlarms(var exact: Boolean = true) : CallAlarms {
    val set = mutableMapOf<AlarmSlot, Instant>()
    override fun set(slot: AlarmSlot, at: Instant) { set[slot] = at }
    override fun cancel(slot: AlarmSlot) { set.remove(slot) }
    override fun canScheduleExact(): Boolean = exact
}

class MemorySettingsCache(var settings: Settings? = null) : CallSettingsCache {
    override suspend fun load(): Settings? = settings
    override suspend fun save(settings: Settings) { this.settings = settings }
}

class RecordingNotifier : CallNotifier {
    val log = mutableListOf<String>()
    override fun headsUp(title: String, body: String) { log += "headsUp:$title|$body" }
    override fun incoming(presentation: IncomingPresentation, callTime: String) { log += "incoming:$presentation@$callTime" }
    override fun ongoing(sessionId: String) { log += "ongoing:$sessionId" }
    override fun cancelHeadsUp() { log += "cancelHeadsUp" }
    override fun cancelIncoming() { log += "cancelIncoming" }
    override fun cancelOngoing() { log += "cancelOngoing" }
}

class FakeCapabilities(
    var notifications: Boolean = true,
    var fullScreen: Boolean = true,
    var exactAlarms: Boolean = true,
    var sms: Boolean = true,
    var dnd: Boolean = true,
    var battery: Boolean = true,
) : DeviceCapabilities {
    override fun notificationsEnabled() = notifications
    override fun canUseFullScreenIntent() = fullScreen
    override fun canScheduleExactAlarms() = exactAlarms
    override fun smsGranted() = sms
    override fun dndAccessGranted() = dnd
    override fun batteryUnrestricted() = battery
}
