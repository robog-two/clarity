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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.OnboardingFlagStore
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.app.data.SetupRepository
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.Settings.MorningCall

/** The time choices offered; a stored time that is not among them is added. */
val DefaultCallTimes: List<LocalTime> = listOf(LocalTime(8, 30), LocalTime(9, 0), LocalTime(9, 30))

/** Weekdays in the prototype's order, Sunday first (ISO numbers, Monday = 1). */
val DayOrder: List<Int> = listOf(7, 1, 2, 3, 4, 5, 6)

fun dayInitial(isoDay: Int): String = "SMTWTFS"[DayOrder.indexOf(isoDay)].toString()

fun dayName(isoDay: Int): String = DayOfWeek(isoDay).name.lowercase().replaceFirstChar { it.uppercase() }

/** "9:00" */
fun formatTime(t: LocalTime): String = "${t.hour}:${t.minute.toString().padStart(2, '0')}"

fun callTimeChoices(current: LocalTime): List<LocalTime> =
    if (current in DefaultCallTimes) DefaultCallTimes else (DefaultCallTimes + current).sorted()

fun MorningCall.toggleDay(isoDay: Int): MorningCall =
    copy(days = if (isoDay in days) days - isoDay else days + isoDay)

fun MorningCall.withHeadsUp(on: Boolean): MorningCall = copy(headsUpMinutes = if (on) 10 else null)

fun MorningCall.toggledMode(): MorningCall =
    copy(mode = if (mode == MorningCall.Mode.CALL) MorningCall.Mode.NOTIFY else MorningCall.Mode.CALL)

/**
 * "tomorrow" if the call runs tomorrow, else the next weekday it runs ("on Monday"); null if no day is selected.
 */
fun nextCallDay(call: MorningCall, today: LocalDate): String? {
    if (call.days.isEmpty()) return null
    for (offset in 1..7) {
        val date = today.plus(DatePeriod(days = offset))
        if (date.dayOfWeek.isoDayNumber in call.days) {
            return if (offset == 1) "tomorrow" else "on ${dayName(date.dayOfWeek.isoDayNumber)}"
        }
    }
    return null
}

/** The sentence under the done heading, as in the prototype. */
fun doneLine(call: MorningCall): String {
    val time = formatTime(call.at)
    val ringing = call.mode == MorningCall.Mode.CALL
    val headsUp = call.headsUpMinutes
    return when {
        headsUp != null -> "I’ll send a heads-up $headsUp minutes before, then " +
            if (ringing) "call. Pick up in the app." else "a notification to start."
        ringing -> "I’ll call at $time. Pick up in the app."
        else -> "You’ll get a notification at $time."
    }
}

data class ScheduleState(
    val loading: Boolean = true,
    val settings: Settings = Settings(),
    val saving: Boolean = false,
    /** Saved (or the server cannot store settings yet, in which case [notStored] is set too). */
    val saved: Boolean = false,
    val notStored: Boolean = false,
    val error: ApiError? = null,
) {
    val call: MorningCall get() = settings.morningCall
    val times: List<LocalTime> get() = callTimeChoices(call.at)
}

/** Step 4: the morning call schedule. */
@Inject
@ViewModelKey(ScheduleStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class ScheduleStepViewModel(private val settings: SettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = (settings.load() as? ApiResult.Success)?.value
            _state.update { s ->
                s.copy(
                    loading = false,
                    settings = (loaded ?: s.settings).let { it.copy(timeZone = if (loaded == null) java.util.TimeZone.getDefault().id else it.timeZone) },
                )
            }
        }
    }

    private fun edit(f: (MorningCall) -> MorningCall) =
        _state.update { it.copy(settings = it.settings.copy(morningCall = f(it.settings.morningCall)), error = null) }

    fun pickTime(t: LocalTime) = edit { it.copy(at = t) }
    fun toggleDay(isoDay: Int) = edit { it.toggleDay(isoDay) }
    fun toggleHeadsUp() = edit { it.withHeadsUp(it.headsUpMinutes == null) }
    fun toggleMode() = edit { it.toggledMode() }

    /** "finish →" */
    fun finish() {
        val s = _state.value
        if (s.saving || s.loading) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = settings.save(s.settings)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is ApiResult.Failure ->
                    if (r.error is ApiError.NotFound) {
                        // Not a crash: this server has no settings route yet; the schedule is not stored.
                        _state.update { it.copy(saving = false, saved = true, notStored = true) }
                    } else {
                        _state.update { it.copy(saving = false, error = r.error) }
                    }
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }
}

data class DoneState(
    val call: MorningCall = MorningCall(),
    val connectors: Int = 0,
    val constitutionVersion: Int? = null,
    val nextDay: String? = null,
)

/** The last screen; "open the app" marks onboarding as done on this device. */
@Inject
@ViewModelKey(DoneStepViewModel::class)
@ContributesIntoMap(AppScope::class)
class DoneStepViewModel(
    private val settings: SettingsRepository,
    private val setup: SetupRepository,
    private val flag: OnboardingFlagStore,
    private val clock: AppClock,
) : ViewModel() {
    private val _state = MutableStateFlow(DoneState())
    val state: StateFlow<DoneState> = _state.asStateFlow()

    fun load(zone: TimeZone = TimeZone.currentSystemDefault()) {
        viewModelScope.launch {
            val call = (settings.load() as? ApiResult.Success)?.value?.morningCall ?: MorningCall()
            val status = setup.status()
            val today = clock.now().toLocalDateTime(zone).date
            _state.value = DoneState(call, status?.connectorsReady ?: 0, status?.constitutionVersion, nextCallDay(call, today))
        }
    }

    fun openApp() {
        viewModelScope.launch { flag.markDone() }
    }
}
