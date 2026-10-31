package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.backend.agent.BrowserAudit
import net.robog.clarity.backend.agent.BrowserRunStates
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.shared.session.Settings

private val log = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Drops browser-step screenshots older than `CLARITY_BROWSER_FRAME_RETENTION` (30 days by default).
 * The audit rows stay (what was done, Jev's answers, the change report); only their two PNG
 * columns are cleared. Saved browser run states ([BrowserRunStates]) that old are dropped too.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class BrowserFrameRetentionJob(
    private val audit: BrowserAudit,
    private val config: AgentConfig,
    private val clock: Clock,
    private val runStates: BrowserRunStates,
) : ScheduledJob {
    override val name = "browser-frame-retention"

    override fun schedule(settings: Settings) = Schedule.Every(6.hours)

    override suspend fun fire(context: FireContext) {
        val now = clock.now()
        val dropped = audit.dropFrames(now - config.browser.frameRetention, now)
        if (dropped > 0) log.info { "dropped the screenshots of $dropped browser steps older than ${config.browser.frameRetention}" }
        runStates.dropOlderThan(now - config.browser.frameRetention)
    }
}
