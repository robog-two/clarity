package net.robog.clarity.backend.attachments

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.Schedule
import net.robog.clarity.backend.scheduler.ScheduledJob
import net.robog.clarity.shared.session.Settings

private val log = KotlinLogging.logger {}

/**
 * Keeps the attachment cache small: bytes older than `CLARITY_ATTACHMENT_CACHE_TTL` (24 h) go, then the oldest
 * until the total is under `CLARITY_ATTACHMENT_CACHE_MAX_MB` (500). Unanswered requests and failures go too.
 * Attachments are never kept for good: the mail server has them, and a fetch gets them again.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class AttachmentSweepJob(
    private val config: AppConfig,
    private val store: AttachmentFetchStore,
    private val clock: Clock,
) : ScheduledJob {
    override val name = "attachment-cache-sweep"

    override fun schedule(settings: Settings) = Schedule.Every(config.attachments.sweepInterval)

    override suspend fun fire(context: FireContext) {
        val dropped = store.sweep(clock.now(), config.attachments.ttl, config.attachments.cacheMaxBytes)
        if (dropped > 0) log.info { "attachment cache: dropped $dropped rows" }
    }
}
