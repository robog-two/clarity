package net.robog.clarity.app.push

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import net.robog.clarity.app.call.graph
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.FeedSync

// The background fallback: when the push service is not running (killed by the system, a battery
// manager or a crash), a periodic job catches up on the feed, posts what the service would have
// posted, and tries to bring the service back. The decisions are pure; the Android glue is at the end.

// --- what has already been notified ---

/**
 * The arrivals both paths have already handled, for one server and device ([scope]). Items are
 * remembered by seq: everything at or below [floor] is done, plus the recent [seqs] above it (kept
 * as a set so that two paths finishing out of order never skip one). Held actions have no seq, so
 * the most recent [held] ids are kept instead.
 */
data class Notified(
    val scope: String = "",
    val floor: Long = 0,
    val seqs: List<Long> = emptyList(),
    val held: List<String> = emptyList(),
) {
    /** The highest seq either path has notified (or decided not to). */
    val lastSeq: Long get() = seqs.maxOrNull() ?: floor

    /** The state after claiming [arrival] for [scope], or null when it was already claimed. */
    fun claim(arrival: Arrival, scope: String, keep: Int = KEEP): Notified? {
        // A different server or device starts over: its seqs mean something else.
        val base = if (scope == this.scope) this else Notified(scope)
        return when (arrival) {
            is Arrival.Item -> {
                val seq = arrival.item.seq
                if (seq <= base.floor || seq in base.seqs) return null
                val all = (base.seqs + seq).sorted()
                val dropped = all.dropLast(keep)
                base.copy(floor = maxOf(base.floor, dropped.maxOrNull() ?: 0), seqs = all.takeLast(keep))
            }
            is Arrival.Held -> {
                val id = arrival.action.id.value
                if (id in base.held) return null
                base.copy(held = (base.held + id).takeLast(keep))
            }
        }
    }

    companion object {
        const val KEEP = 128
    }
}

/** Shared by the live listener and the fallback, so the same arrival never notifies twice. */
interface NotifiedLedger {
    /** True the first time [arrival] is claimed (and records it); false every time after. */
    suspend fun claim(arrival: Arrival): Boolean

    /** Remembers nothing: every arrival is new. For tests and previews. */
    object None : NotifiedLedger {
        override suspend fun claim(arrival: Arrival): Boolean = true
    }
}

/** [Notified] kept in DataStore. Each claim is one atomic edit, so the service and the job cannot both win. */
class DataStoreNotifiedLedger(
    private val store: DataStore<Preferences>,
    private val sessions: SessionStore,
) : NotifiedLedger {
    override suspend fun claim(arrival: Arrival): Boolean {
        val scope = sessions.session.first()?.let { "${it.instanceUrl}|${it.deviceId.value}" } ?: ""
        var claimed = false
        store.edit { prefs ->
            val next = read(prefs).claim(arrival, scope) ?: return@edit
            write(prefs, next)
            claimed = true
        }
        return claimed
    }

    private fun read(prefs: Preferences) = Notified(
        scope = prefs[SCOPE] ?: "",
        floor = prefs[FLOOR] ?: 0,
        seqs = prefs[SEQS].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        held = prefs[HELD].orEmpty().split('\n').filter { it.isNotEmpty() },
    )

    private fun write(prefs: MutablePreferences, n: Notified) {
        prefs[SCOPE] = n.scope
        prefs[FLOOR] = n.floor
        prefs[SEQS] = n.seqs.joinToString(",")
        prefs[HELD] = n.held.joinToString("\n")
    }

    private companion object {
        val SCOPE = stringPreferencesKey("push_notified_scope")
        val FLOOR = longPreferencesKey("push_notified_floor")
        val SEQS = stringPreferencesKey("push_notified_seqs")
        val HELD = stringPreferencesKey("push_notified_held")
    }
}

// --- what one run does ---

enum class FallbackStep {
    /** Push is off or there is no server: stop the job. */
    Cancel,

    /** The service is up and its stream is live: it already notifies. */
    Skip,

    /** Fetch what was missed and notify it. */
    CatchUp,
}

/** What a run of the fallback should do. */
fun fallbackStep(shouldRun: Boolean, serviceRunning: Boolean, feedLive: Boolean): FallbackStep = when {
    !shouldRun -> FallbackStep.Cancel
    serviceRunning && feedLive -> FallbackStep.Skip
    else -> FallbackStep.CatchUp
}

/**
 * Whether a background job may start the foreground service. From Android 12 that is refused
 * unless the app is exempt from battery optimization (one of the few exemptions a job can have).
 */
fun canStartInBackground(sdk: Int, batteryUnrestricted: Boolean): Boolean = sdk < 31 || batteryUnrestricted

/** One run: catches up when the service is not keeping up, and restarts it when it may. */
class PushFallback(
    private val conditions: suspend () -> PushConditions,
    private val serviceRunning: () -> Boolean,
    private val feedLive: () -> Boolean,
    private val catchUp: suspend () -> FeedSync.CatchUp,
    private val deliver: suspend (Arrival) -> Boolean,
    private val reconnect: () -> Unit,
    private val canStartService: () -> Boolean,
    private val startService: suspend () -> Unit,
    private val work: PushFallbackWork,
) {
    data class Outcome(val step: FallbackStep, val posted: Int = 0, val restartTried: Boolean = false)

    suspend fun run(): Outcome {
        val running = serviceRunning()
        when (fallbackStep(conditions().shouldRun, running, feedLive())) {
            FallbackStep.Cancel -> {
                work.cancel()
                return Outcome(FallbackStep.Cancel)
            }
            FallbackStep.Skip -> return Outcome(FallbackStep.Skip)
            FallbackStep.CatchUp -> Unit
        }
        // The ledger makes this safe even if the service is posting the same arrivals right now.
        val posted = catchUp().arrived.count { deliver(it) }
        if (running) {
            // Up but not live (a stalled socket or a long backoff): have it reconnect now.
            reconnect()
            return Outcome(FallbackStep.CatchUp, posted)
        }
        val tried = canStartService()
        if (tried) startService()
        return Outcome(FallbackStep.CatchUp, posted, restartTried = tried)
    }
}

// --- scheduling ---

/** The periodic job, behind an interface so that scheduling is testable. */
interface PushFallbackWork {
    fun schedule()
    fun cancel()
}

/** Keeps the job scheduled exactly while the service should run. */
fun PushFallbackWork.follow(conditions: PushConditions) = if (conditions.shouldRun) schedule() else cancel()

class WorkManagerPushFallbackWork(private val context: Context) : PushFallbackWork {
    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<PushFallbackWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Keep: reconciling on every app open must not push the next run back.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object { const val UNIQUE_NAME = "push-fallback" }
}

class PushFallbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A failed catch-up is not retried: the next run is at most 15 minutes away.
        applicationContext.graph().pushFallback.run()
        return Result.success()
    }
}

/** The Android side of [canStartInBackground]. */
fun canStartServiceNow(batteryUnrestricted: Boolean): Boolean = canStartInBackground(Build.VERSION.SDK_INT, batteryUnrestricted)
