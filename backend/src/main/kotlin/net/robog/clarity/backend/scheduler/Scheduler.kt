package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import net.robog.clarity.backend.agent.ScheduledJobs
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.session.Settings
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

private val log = KotlinLogging.logger {}

/** What a job is told when it fires. */
data class FireContext(
    /** The time it was due (its row's `next_run_at`). Stable across retries: use it in idempotency keys. */
    val scheduledFor: Instant,
    val now: Instant,
    /** When it last completed, null on the first run. */
    val lastRunAt: Instant?,
    val settings: Settings,
)

/**
 * A recurring job. Contributed as a set via `@ContributesIntoSet(AppScope::class)`. [fire] runs
 * inside the transaction that claimed the job's row: if it throws, nothing is advanced and the job
 * is retried; if the process dies after it ran, it runs again, so [fire] must be idempotent
 * (inbox items carry dedup keys derived from [FireContext.scheduledFor]). Keep it short; start long
 * work in the background.
 */
interface ScheduledJob {
    val name: String

    /** Pure in [settings]: jobs follow the person's schedule (evening summary time, zone). */
    fun schedule(settings: Settings): Schedule

    suspend fun fire(context: FireContext)
}

/**
 * The durable scheduler. Jobs are rows in `scheduled_job`; a due row is claimed with
 * `FOR UPDATE SKIP LOCKED`, fired and advanced in one transaction, so concurrent instances and
 * restarts never double-fire a job. A job that was due several times (the backend was down) fires
 * once and moves to its next slot after now.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class Scheduler(
    private val database: Database,
    private val jobs: Set<ScheduledJob>,
    private val settings: SettingsProvider,
    private val clock: Clock,
) : BackgroundService {
    override val name = "scheduler"

    private val byName = jobs.associateBy { it.name }

    override suspend fun run() {
        ensureRows()
        while (true) {
            val wait = try {
                var fired = tickOnce()
                while (fired) fired = tickOnce()
                untilNextDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "scheduler tick failed; retrying shortly" }
                RETRY_AFTER_FAILURE
            }
            delay(wait.coerceIn(MIN_WAIT, MAX_WAIT))
        }
    }

    /** Creates rows for jobs that have none and drops rows of jobs that no longer exist. */
    suspend fun ensureRows() {
        val current = settings.current()
        val now = clock.now()
        val zone = zoneOrUtc(current.homeZone)
        database.tx {
            ScheduledJobs.deleteWhere { name notInList byName.keys.toList() }
            jobs.forEach { job ->
                ScheduledJobs.insertIgnore {
                    it[name] = job.name
                    it[nextRunAt] = job.schedule(current).nextAfter(now, zone)
                }
            }
        }
    }

    /**
     * Recomputes the next run of every job whose time follows the settings (not the fixed-interval
     * ones), after the person changed them: a new call time takes effect at once.
     */
    suspend fun resetSchedules() {
        val current = settings.current()
        val now = clock.now()
        val zone = zoneOrUtc(current.homeZone)
        database.tx {
            jobs.forEach { job ->
                val schedule = job.schedule(current)
                if (schedule !is Schedule.Every) {
                    ScheduledJobs.update({ ScheduledJobs.name eq job.name }) { it[nextRunAt] = schedule.nextAfter(now, zone) }
                }
            }
        }
    }

    /** Claims and fires one due job; false when none is due. */
    suspend fun tickOnce(): Boolean {
        val current = settings.current()
        val now = clock.now()
        val zone = zoneOrUtc(current.homeZone)
        return database.tx {
            val row = ScheduledJobs.selectAll().where { ScheduledJobs.nextRunAt lessEq now }
                .orderBy(ScheduledJobs.nextRunAt).limit(1)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .singleOrNull() ?: return@tx false
            val jobName = row[ScheduledJobs.name]
            val job = byName[jobName]
            if (job != null) {
                job.fire(FireContext(row[ScheduledJobs.nextRunAt], now, row[ScheduledJobs.lastRunAt], current))
                log.debug { "scheduled job $jobName fired" }
            }
            ScheduledJobs.update({ ScheduledJobs.name eq jobName }) {
                it[lastRunAt] = now
                it[nextRunAt] = job?.schedule(current)?.nextAfter(now, zone) ?: (now + UNKNOWN_JOB_BACKOFF)
            }
            true
        }
    }

    private suspend fun untilNextDue(): Duration {
        val next = database.tx { ScheduledJobs.selectAll().orderBy(ScheduledJobs.nextRunAt).limit(1).singleOrNull()?.get(ScheduledJobs.nextRunAt) }
        return next?.let { it - clock.now() } ?: MAX_WAIT
    }

    private companion object {
        val MIN_WAIT = 1.seconds
        val MAX_WAIT = 30.seconds
        val RETRY_AFTER_FAILURE = 1.minutes
        val UNKNOWN_JOB_BACKOFF = 365.days
    }
}
