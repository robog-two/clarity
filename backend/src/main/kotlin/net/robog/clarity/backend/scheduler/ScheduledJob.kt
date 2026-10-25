package net.robog.clarity.backend.scheduler

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A job the in-process [Scheduler] runs every [interval]. Contributed as a set via
 * `@ContributesIntoSet(AppScope::class)`. [run] should enqueue work and return promptly.
 */
interface ScheduledJob {
    val name: String
    val interval: Duration

    /** [lastRun] is the persisted start time of the previous run (null on first ever run). */
    suspend fun run(lastRun: Instant?, now: Instant)
}

/** Persisted last-run times, so a restart does not re-fire a job that just ran. */
interface JobRunStore {
    suspend fun lastRun(name: String): Instant?
    suspend fun markRun(name: String, at: Instant)
}
