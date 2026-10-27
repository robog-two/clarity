package net.robog.clarity.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import net.robog.clarity.app.call.CallScheduler
import net.robog.clarity.app.connector.PhoneConnector
import net.robog.clarity.app.connector.SmsSyncStatus
import net.robog.clarity.app.data.seenSettings
import net.robog.clarity.app.data.storedSettings
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.ConstitutionRepository
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.SessionRepository
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.push.PushController
import net.robog.clarity.app.ui.onboarding.toggleDay
import net.robog.clarity.app.ui.onboarding.toggledMode
import net.robog.clarity.app.ui.onboarding.withHeadsUp
import net.robog.clarity.app.ui.onboarding.NOT_RUNNING
import net.robog.clarity.app.ui.onboarding.isDeviceConnector
import net.robog.clarity.app.ui.sentence
import net.robog.clarity.shared.session.Settings

/** One line of "Sources": a name, where it comes from, and when it last synced. */
data class SourceRow(
    val title: String,
    val sub: String,
    val sync: String,
    /** The connector id; tapping the row opens its screen (accounts, passwords). */
    val id: String = "",
)

data class SettingsUiState(
    val instanceUrl: String = "",
    val deviceId: String = "",
    val loading: Boolean = true,
    val settings: Settings? = null,
    /** The server has no settings route yet: changes are kept on this phone only. */
    val localOnly: Boolean = false,
    val message: String? = null,
    val constitution: String? = null,
    val serverSources: List<SourceRow> = emptyList(),
    val smsGranted: Boolean = false,
    /** The messages row: last sync, how many, or what went wrong. */
    val sms: SmsRowText = SmsRowText("allow reading texts to sync them", "allow", needsAllow = true),
    val dndGranted: Boolean = false,
    val notificationsOn: Boolean = true,
    val fullScreenOk: Boolean = true,
    /** The call is on but exact alarms are not allowed: it may ring a few minutes late. */
    val needsExactAlarms: Boolean = false,
    /** Keep the connection to the server open so pushes arrive at once (the foreground service). */
    val pushOn: Boolean = true,
    /** The system does not restrict background work, so pushes are not delayed. */
    val batteryUnrestricted: Boolean = true,
)

/**
 * Settings: the morning call, voice, the constitution, held actions, sources on the server and on
 * this phone, forget this server, redo onboarding. Every change is saved at once and re-arms the
 * call alarms.
 */
@Inject
@ViewModelKey(SettingsViewModel::class)
@ContributesIntoMap(AppScope::class)
class SettingsViewModel(
    private val sessions: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val connectors: ConnectorsRepository,
    private val constitution: ConstitutionRepository,
    private val scheduler: CallScheduler,
    private val device: DeviceCapabilities,
    private val phone: PhoneConnector,
    private val flag: OnboardingFlagStore,
    private val clock: AppClock,
    private val push: PushController,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** The phone connector's last sync report (declared before `init`, which reads it). */
    private var smsStatus = SmsSyncStatus()

    init {
        viewModelScope.launch {
            sessions.session.collect { s ->
                _state.update { it.copy(instanceUrl = s?.instanceUrl.orEmpty(), deviceId = s?.deviceId?.value.orEmpty()) }
            }
        }
        viewModelScope.launch {
            phone.status.collect { status ->
                smsStatus = status
                _state.update { it.copy(sms = smsRowText(it.smsGranted, status, clock.now())) }
            }
        }
        viewModelScope.launch { push.enabled.collect { on -> _state.update { it.copy(pushOn = on) } } }
        viewModelScope.launch { load() }
        refreshDevice()
    }

    /** What the server last stored; the screen shows [seenSettings] of it. */
    private var stored: Settings? = null

    private suspend fun load() {
        when (val r = settingsRepository.load()) {
            is ApiResult.Success -> {
                stored = r.value
                _state.update { it.copy(loading = false, settings = seenSettings(r.value, clock.zone(), clock.now()), localOnly = false) }
            }
            is ApiResult.Failure -> _state.update {
                it.copy(
                    loading = false,
                    settings = it.settings ?: Settings(), // the defaults, read as times on this phone
                    localOnly = r.error is ApiError.NotFound,
                    message = r.error.takeUnless { e -> e is ApiError.NotFound }?.sentence(),
                )
            }
        }
        refreshDevice()
        (constitution.current() as? ApiResult.Success)?.value?.let { c ->
            _state.update { it.copy(constitution = "v${c.version} · ${c.lines.size} lines") }
        }
        refreshSources()
    }

    /** Reloads the sources on the server (also when coming back from a connector screen). */
    fun refreshSources() {
        viewModelScope.launch {
            (connectors.list() as? ApiResult.Success)?.value?.let { list ->
                val now = clock.now()
                _state.update {
                    it.copy(
                        serverSources = list
                            .filterNot { c -> isDeviceConnector(c.id) }
                            .map { c ->
                                val m = c.manifest
                                if (m == null) {
                                    SourceRow(c.id.value, NOT_RUNNING, "not running", c.id.value)
                                } else {
                                    SourceRow(m.displayName, m.kind, relativeTime(c.lastSeenAt, now), c.id.value)
                                }
                            },
                    )
                }
            }
        }
    }

    /** Re-reads what the phone allows; call it when the screen comes back from a system dialog. */
    fun refreshDevice() {
        val smsGranted = device.smsGranted()
        _state.update {
            it.copy(
                smsGranted = smsGranted,
                sms = smsRowText(smsGranted, smsStatus, clock.now()),
                dndGranted = device.dndAccessGranted(),
                notificationsOn = device.notificationsEnabled(),
                fullScreenOk = device.canUseFullScreenIntent(),
                batteryUnrestricted = device.batteryUnrestricted(),
                needsExactAlarms = it.settings?.let { s -> scheduler.needsExactAlarmPermission(s) } ?: false,
            )
        }
    }

    /** SMS permission was just granted (or the server is not taking texts yet): allow them there too and sync. */
    fun onSmsGranted() {
        refreshDevice()
        viewModelScope.launch { phone.enableMessages() }
    }

    // --- edits ---

    private fun edit(change: (Settings) -> Settings) {
        val current = _state.value.settings ?: return
        val updated = change(current)
        if (updated == current) return
        _state.update { it.copy(settings = updated, message = null) }
        viewModelScope.launch { persist(updated) }
    }

    private suspend fun persist(updated: Settings) {
        val toStore = storedSettings(updated, stored, clock.zone(), clock.now())
        stored = toStore
        when (val r = settingsRepository.save(toStore)) {
            is ApiResult.Success -> _state.update { it.copy(localOnly = false) }
            is ApiResult.Failure -> _state.update {
                if (r.error is ApiError.NotFound) it.copy(localOnly = true) else it.copy(message = r.error.sentence())
            }
        }
        // The alarms follow the settings even when the server could not store them.
        scheduler.apply(toStore)
        refreshDevice()
    }

    fun setCallTime(time: LocalTime) = edit { it.copy(morningCall = it.morningCall.copy(at = time)) }
    fun toggleDay(isoDay: Int) = edit { it.copy(morningCall = it.morningCall.toggleDay(isoDay)) }
    fun toggleHeadsUp() = edit { it.copy(morningCall = it.morningCall.withHeadsUp(it.morningCall.headsUpMinutes == null)) }
    fun toggleMode() = edit { it.copy(morningCall = it.morningCall.toggledMode()) }
    fun toggleMic() = edit { it.copy(micAlwaysOn = !it.micAlwaysOn) }
    fun toggleBatch() = edit { it.copy(batchLowUrgency = !it.batchLowUrgency) }
    fun togglePushUrgent() = edit { it.copy(pushUrgent = !it.pushUrgent) }

    /** "live connection": starts or stops the foreground service that keeps pushes instant. */
    fun togglePushService() {
        val next = !_state.value.pushOn
        _state.update { it.copy(pushOn = next) }
        viewModelScope.launch { push.setEnabled(next) }
    }

    /** The battery explanation is being shown to the person (they are about to see the system dialog). */
    fun batteryAsked() {
        viewModelScope.launch { push.markBatteryAsked() }
    }

    fun setEveningAt(time: LocalTime) = edit { it.copy(eveningSummaryAt = time) }

    /** Clears the session and device key; the root navigation returns to onboarding. */
    fun forgetInstance() {
        viewModelScope.launch {
            sessions.forget()
            push.reconcile() // nothing to keep open any more
        }
    }

    /** "redo onboarding": the root navigation shows the flow again, from the server step. */
    fun redoOnboarding() {
        viewModelScope.launch { flag.startRedo() }
    }
}
