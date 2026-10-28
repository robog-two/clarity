package net.robog.clarity.backend.reports

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.scheduler.FireContext
import net.robog.clarity.backend.scheduler.Schedule
import net.robog.clarity.backend.scheduler.ScheduledJob
import net.robog.clarity.shared.session.Settings

private val log = KotlinLogging.logger {}

/**
 * Keeps the rendition cache under `CLARITY_REPORTS_CACHE_MAX_MB` (500 MB): drops files of an old
 * renderer version, then the least recently downloaded. Only cache files and their rows go;
 * revisions (the reports themselves) are never touched.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class RenditionSweepJob(
    private val config: AppConfig,
    private val store: ReportStore,
    private val blobs: ReportBlobs,
    private val renderer: Renderer,
) : ScheduledJob {
    override val name = "report-rendition-sweep"

    override fun schedule(settings: Settings) = Schedule.Every(config.reports.sweepInterval)

    override suspend fun fire(context: FireContext) {
        val dropped = sweepRenditions(store, blobs, config.reports.cacheMaxBytes, renderer.version())
        if (dropped > 0) log.info { "report cache: dropped $dropped rendered files" }
    }
}
