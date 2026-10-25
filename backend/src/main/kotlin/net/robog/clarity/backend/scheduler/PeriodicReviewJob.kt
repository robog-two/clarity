package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.time.Duration
import kotlin.time.Instant
import net.robog.clarity.backend.agent.AgentDispatcher
import net.robog.clarity.backend.agent.AgentTask
import net.robog.clarity.backend.config.AgentConfig

/** Asks the agent to summarize what changed and revisit projects that are waiting on information. */
@Inject
@ContributesIntoSet(AppScope::class)
class PeriodicReviewJob(
    config: AgentConfig,
    private val dispatcher: AgentDispatcher,
) : ScheduledJob {
    override val name = "periodic-review"
    override val interval: Duration = config.reviewInterval

    override suspend fun run(lastRun: Instant?, now: Instant) {
        dispatcher.dispatch(AgentTask.PeriodicReview(since = lastRun ?: (now - interval)))
    }
}
