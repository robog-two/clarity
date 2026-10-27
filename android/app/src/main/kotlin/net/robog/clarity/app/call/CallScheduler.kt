package net.robog.clarity.app.call

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.SettingsRepository
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.session.Settings

/** The alarms the app owns. */
enum class AlarmSlot(val requestCode: Int) {
    HeadsUp(7101),
    Call(7102),
    /** A declined call ringing again. */
    Snooze(7103),
}

/** The phone's alarm clock, behind an interface so scheduling is testable. */
interface CallAlarms {
    fun set(slot: AlarmSlot, at: Instant)
    fun cancel(slot: AlarmSlot)
    fun canScheduleExact(): Boolean
}

/** The last settings the server gave us, so alarms can be re-armed offline (boot, time zone change). */
interface CallSettingsCache {
    suspend fun load(): Settings?
    suspend fun save(settings: Settings)

    /** The server was forgotten: its schedule must not re-arm the call after a reboot. */
    suspend fun clear() {}
}

class DataStoreCallSettingsCache(private val store: DataStore<Preferences>) : CallSettingsCache {
    override suspend fun load(): Settings? =
        store.data.first()[KEY]?.let { runCatching { ClarityJson.decodeFromString<Settings>(it) }.getOrNull() }

    override suspend fun save(settings: Settings) {
        store.edit { it[KEY] = ClarityJson.encodeToString(settings) }
    }

    override suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private companion object { val KEY = stringPreferencesKey("cached_settings") }
}

/**
 * Keeps the heads-up and call alarms in step with `Settings.morningCall`. [refresh] is called on
 * app start, when settings are saved, after an alarm fires (to arm the next one), and after boot,
 * time-zone change and app update.
 */
class CallScheduler(
    private val settings: SettingsRepository,
    private val cache: CallSettingsCache,
    private val alarms: CallAlarms,
    private val clock: AppClock,
) {
    /** Fetches the settings (falling back to the cached copy) and re-arms the alarms. Returns the plan. */
    suspend fun refresh(): CallPlan? {
        val fetched = (settings.load() as? ApiResult.Success)?.value
        if (fetched != null) cache.save(fetched)
        val current = fetched ?: cache.load()
        return if (current == null) {
            alarms.cancel(AlarmSlot.HeadsUp); alarms.cancel(AlarmSlot.Call); null
        } else {
            apply(current)
        }
    }

    /** Re-arms from a settings value already in hand (e.g. just saved). */
    suspend fun apply(current: Settings): CallPlan? {
        cache.save(current)
        val plan = nextCallPlan(current, clock.now())
        alarms.cancel(AlarmSlot.HeadsUp)
        alarms.cancel(AlarmSlot.Call)
        if (plan != null) {
            plan.headsUpAt?.let { alarms.set(AlarmSlot.HeadsUp, it) }
            alarms.set(AlarmSlot.Call, plan.callAt)
        }
        return plan
    }

    /** "Not now": ring again in [minutes]. Returns when. */
    fun snooze(minutes: Int): Instant {
        val at = snoozedCallAt(clock.now(), minutes)
        alarms.set(AlarmSlot.Snooze, at)
        return at
    }

    fun cancelSnooze() = alarms.cancel(AlarmSlot.Snooze)

    /** The server was forgotten: no call, heads-up or snoozed call from its schedule, now or after a reboot. */
    suspend fun forgetServer() {
        AlarmSlot.entries.forEach(alarms::cancel)
        cache.clear()
    }

    fun needsExactAlarmPermission(current: Settings): Boolean = shouldAskForExactAlarms(current, alarms.canScheduleExact())
}
