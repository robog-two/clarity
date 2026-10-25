package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.robog.clarity.backend.lifecycle.BackgroundService

private val log = KotlinLogging.logger {}

/** How long until a job is due: zero or negative means "run now". Pure. */
fun timeUntilDue(lastRun: Instant?, now: Instant, interval: Duration): Duration =
    if (lastRun == null) Duration.ZERO else lastRun + interval - now

/**
 * Runs every contributed [ScheduledJob] on its own interval. Last-run times are persisted, so a
 * restart waits out the remainder of the interval instead of firing again.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class Scheduler(
    private val jobs: Set<ScheduledJob>,
    private val store: JobRunStore,
    private val clock: Clock,
) : BackgroundService {
    override val name = "scheduler"

    override suspend fun run() = coroutineScope {
        jobs.forEach { job ->
            launch {
                log.info { "scheduling ${job.name} every ${job.interval}" }
                while (true) delay(tick(job))
            }
        }
    }

    /** Runs [job] if it is due and returns how long to sleep before checking it again. */
    suspend fun tick(job: ScheduledJob): Duration {
        val lastRun = store.lastRun(job.name)
        val now = clock.now()
        val wait = timeUntilDue(lastRun, now, job.interval)
        if (wait.isPositive()) return wait
        return try {
            job.run(lastRun, now)
            store.markRun(job.name, now)
            job.interval
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "scheduled job ${job.name} failed; retrying shortly" }
            minOf(job.interval, RETRY_AFTER_FAILURE)
        }
    }

    private companion object { val RETRY_AFTER_FAILURE = 1.minutes }
}
