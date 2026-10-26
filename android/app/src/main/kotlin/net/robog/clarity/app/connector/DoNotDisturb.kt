package net.robog.clarity.app.connector

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import net.robog.clarity.app.call.async
import net.robog.clarity.app.call.graph
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.governance.ProposedAction

/** What turning do-not-disturb on means on this phone. */
sealed interface DndPlan {
    /** The window is already over: nothing to do. */
    data object AlreadyOver : DndPlan

    /**
     * Android cannot allow-list individual numbers through do not disturb, only starred contacts.
     * So any non-empty allow list becomes "starred contacts only"; an empty list silences everything but alarms.
     */
    data class Enable(val until: Instant, val allowStarred: Boolean) : DndPlan
}

fun dndPlanFor(action: ProposedAction.SetDoNotDisturb, now: Instant): DndPlan =
    if (action.until <= now) DndPlan.AlreadyOver else DndPlan.Enable(action.until, allowStarred = action.allow.isNotEmpty())

/** The phone's do-not-disturb switch. */
interface DndController {
    /** "Do not disturb access" was granted to the app. */
    fun hasAccess(): Boolean
    fun currentFilter(): Int
    fun enable(allowStarred: Boolean)
    fun restore(filter: Int)
}

class AndroidDndController(context: Context) : DndController {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun hasAccess(): Boolean = manager.isNotificationPolicyAccessGranted
    override fun currentFilter(): Int = manager.currentInterruptionFilter

    override fun enable(allowStarred: Boolean) {
        if (allowStarred) {
            manager.notificationPolicy = NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
                    NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
                    NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
                NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
            )
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        } else {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        }
    }

    override fun restore(filter: Int) {
        manager.setInterruptionFilter(if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else filter)
    }
}

/** What to undo, and when. */
data class DndState(val until: Instant, val previousFilter: Int)

interface DndStateStore {
    suspend fun load(): DndState?
    suspend fun save(state: DndState)
    suspend fun clear()
}

class DataStoreDndStateStore(private val store: DataStore<Preferences>) : DndStateStore {
    override suspend fun load(): DndState? {
        val p = store.data.first()
        val until = p[UNTIL] ?: return null
        return DndState(Instant.fromEpochMilliseconds(until), p[PREVIOUS] ?: NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    override suspend fun save(state: DndState) {
        store.edit {
            it[UNTIL] = state.until.toEpochMilliseconds()
            it[PREVIOUS] = state.previousFilter
        }
    }

    override suspend fun clear() {
        store.edit { it.remove(UNTIL); it.remove(PREVIOUS) }
    }

    private companion object {
        val UNTIL = longPreferencesKey("dnd_until")
        val PREVIOUS = intPreferencesKey("dnd_previous_filter")
    }
}

/** Fires [DndEndReceiver] when the window closes. */
interface DndAlarms {
    fun scheduleEnd(at: Instant)
    fun cancelEnd()
}

class AndroidDndAlarms(private val context: Context) : DndAlarms {
    private val manager = context.getSystemService(AlarmManager::class.java)

    private fun pending() = PendingIntent.getBroadcast(
        context, 7201, Intent(context, DndEndReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun scheduleEnd(at: Instant) {
        if (manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilliseconds(), pending())
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilliseconds(), pending())
        }
    }

    override fun cancelEnd() = manager.cancel(pending())
}

class DndEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph()
        async { graph.dndActions.endIfDue() }
    }
}

/** Executes `SET_DO_NOT_DISTURB` from the action channel and turns it off again at `until`. */
class DndActionHandler(
    private val controller: DndController,
    private val state: DndStateStore,
    private val alarms: DndAlarms,
    private val clock: AppClock,
) {
    suspend fun handle(action: ProposedAction): ActionOutcome {
        if (action !is ProposedAction.SetDoNotDisturb) {
            return ActionOutcome.Failed("This phone only handles do not disturb.", retryable = false)
        }
        if (!controller.hasAccess()) {
            return ActionOutcome.Failed(
                "Do not disturb access is off for the app. The person can allow it in settings, under sources on this phone.",
                retryable = false,
            )
        }
        return when (val plan = dndPlanFor(action, clock.now())) {
            DndPlan.AlreadyOver -> ActionOutcome.Done(content = "The window was already over; nothing changed.")
            is DndPlan.Enable -> {
                // A second window while one is running keeps the original setting to go back to.
                val previous = state.load()?.previousFilter ?: controller.currentFilter()
                controller.enable(plan.allowStarred)
                state.save(DndState(plan.until, previous))
                alarms.scheduleEnd(plan.until)
                ActionOutcome.Done(
                    content = if (plan.allowStarred) {
                        "Do not disturb until ${plan.until}. Only starred contacts get through; Android can't allow individual numbers."
                    } else {
                        "Do not disturb until ${plan.until}. Only alarms get through."
                    },
                )
            }
        }
    }

    /** Called by the end alarm, after boot, and on every connector pass. */
    suspend fun endIfDue() {
        val current = state.load() ?: return
        if (clock.now() >= current.until) {
            if (controller.hasAccess()) controller.restore(current.previousFilter)
            state.clear()
            alarms.cancelEnd()
        } else {
            alarms.scheduleEnd(current.until)
        }
    }
}
