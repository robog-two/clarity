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
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import net.robog.clarity.app.call.graph
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.SystemAppClock
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
 * the most recent [held] ids are kept instead. A released hold reuses the seq its item was first
 * claimed under, so the most recent [released] seqs are kept apart. [since] is when this ledger
 * began for [scope] (epoch millis; 0 when unknown, a ledger from before it was kept): what happened
 * before then was never this phone's to notify about as missed.
 */
data class Notified(
    val scope: String = "",
    val floor: Long = 0,
    val seqs: List<Long> = emptyList(),
    val held: List<String> = emptyList(),
    val released: List<Long> = emptyList(),
    val since: Long = 0,
) {
    /** The highest seq either path has notified (or decided not to). */
    val lastSeq: Long get() = seqs.maxOrNull() ?: floor

    /**
     * The state after claiming [arrival] for [scope], or null when it was already claimed. [now]
     * (epoch millis) starts [since] when the scope is new.
     */
    fun claim(arrival: Arrival, scope: String, keep: Int = KEEP, now: Long = 0): Notified? {
        // A different server or device starts over: its seqs mean something else.
        val base = if (scope == this.scope) this else Notified(scope, since = now)
        return when (arrival) {
            is Arrival.Item if arrival.item.notify.startsWith("released:") -> {
                val seq = arrival.item.seq
                if (seq in base.released) return null
                base.copy(released = (base.released + seq).takeLast(keep))
            }
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

    /**
     * Claims each of [arrivals] in turn: the state after, and the ones that were new (in order).
     * One write for a whole catch-up or a screenful of seen items.
     */
    fun claimAll(arrivals: List<Arrival>, scope: String, keep: Int = KEEP, now: Long = 0): Pair<Notified, List<Arrival>> {
        var state = this
        val fresh = mutableListOf<Arrival>()
        for (arrival in arrivals) {
            state = state.claim(arrival, scope, keep, now)?.also { fresh += arrival } ?: continue
        }
        return state to fresh
    }

    companion object {
        const val KEEP = 128
    }
}

/** Shared by the live listener, the fallback and the screens, so the same arrival never notifies twice. */
interface NotifiedLedger {
    /** True the first time [arrival] is claimed (and records it); false every time after. */
    suspend fun claim(arrival: Arrival): Boolean

    /** Claims all of [arrivals] at once; returns the ones nobody had claimed yet, in order. */
    suspend fun claimAll(arrivals: List<Arrival>): List<Arrival> = arrivals.filter { claim(it) }

    /** When this ledger began for the current server; null when unknown. */
    suspend fun since(): Instant? = null

    /** Forgets every claim (the server was forgotten). */
    suspend fun reset() {}

    /** Remembers nothing: every arrival is new. For tests and previews. */
    object None : NotifiedLedger {
        override suspend fun claim(arrival: Arrival): Boolean = true
    }
}

/** [Notified] kept in DataStore. Each claim is one atomic edit, so the service and the job cannot both win. */
class DataStoreNotifiedLedger(
    private val store: DataStore<Preferences>,
    private val sessions: SessionStore,
    private val clock: AppClock = SystemAppClock(),
) : NotifiedLedger {
    private suspend fun scope(): String = sessions.session.first()?.let { "${it.instanceUrl}|${it.deviceId.value}" } ?: ""

    override suspend fun claim(arrival: Arrival): Boolean = claimAll(listOf(arrival)).isNotEmpty()

    override suspend fun claimAll(arrivals: List<Arrival>): List<Arrival> {
        if (arrivals.isEmpty()) return emptyList()
        val scope = scope()
        val now = clock.now().toEpochMilliseconds()
        var fresh = emptyList<Arrival>()
        store.edit { prefs ->
            val (next, claimed) = read(prefs).claimAll(arrivals, scope, now = now)
            if (claimed.isEmpty()) return@edit
            write(prefs, next)
            fresh = claimed
        }
        return fresh
    }

    override suspend fun since(): Instant? {
        val n = read(store.data.first())
        return if (n.scope == scope() && n.since > 0) Instant.fromEpochMilliseconds(n.since) else null
    }

    override suspend fun reset() {
        store.edit { prefs -> listOf(SCOPE, FLOOR, SEQS, HELD, RELEASED, SINCE).forEach { prefs.remove(it) } }
    }

    private fun read(prefs: Preferences) = Notified(
        scope = prefs[SCOPE] ?: "",
        floor = prefs[FLOOR] ?: 0,
        seqs = prefs[SEQS].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        held = prefs[HELD].orEmpty().split('\n').filter { it.isNotEmpty() },
        released = prefs[RELEASED].orEmpty().split(',').mapNotNull { it.toLongOrNull() },
        since = prefs[SINCE] ?: 0,
    )

    private fun write(prefs: MutablePreferences, n: Notified) {
        prefs[SCOPE] = n.scope
        prefs[FLOOR] = n.floor
        prefs[SEQS] = n.seqs.joinToString(",")
        prefs[HELD] = n.held.joinToString("\n")
        prefs[RELEASED] = n.released.joinToString(",")
        prefs[SINCE] = n.since
    }

    private companion object {
        val SCOPE = stringPreferencesKey("push_notified_scope")
        val FLOOR = longPreferencesKey("push_notified_floor")
        val SEQS = stringPreferencesKey("push_notified_seqs")
        val HELD = stringPreferencesKey("push_notified_held")
        val RELEASED = stringPreferencesKey("push_notified_released")
        val SINCE = longPreferencesKey("push_notified_since")
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
    /** Notifies what was missed (what the catch-up brought, and what the cache holds unnotified); returns how many posted. */
    private val deliver: suspend (List<Arrival>) -> Int,
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
        val posted = deliver(catchUp().arrived)
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
