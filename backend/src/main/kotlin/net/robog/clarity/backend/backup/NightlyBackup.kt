package net.robog.clarity.backend.backup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.scheduler.Schedule
import net.robog.clarity.backend.scheduler.nextAfter
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.time.zoneOrUtc

private val log = KotlinLogging.logger {}

/** When the nightly backup is due. Pure. Times are on the person's home clock, daylight saving included. */
object NightlySchedule {
    val AT = LocalTime(2, 0)

    /** The next 02:00 strictly after [now]. */
    fun nextRun(now: Instant, zone: TimeZone): Instant = Schedule.DailyAt(AT).nextAfter(now, zone)

    /**
     * The day whose backup is missing, or null when every night so far has its file. The latest 02:00
     * that has passed names the day: today after 02:00, yesterday before it (so a backend that was down
     * at 02:00 makes the missed one when it starts, once per day, and never a pile of old ones).
     */
    fun missing(now: Instant, zone: TimeZone, have: Set<LocalDate>): LocalDate? {
        val local = now.toLocalDateTime(zone)
        val slot = if (local.time >= AT) local.date else local.date.minus(DatePeriod(days = 1))
        return slot.takeIf { it !in have }
    }
}

/**
 * Makes the nightly backup at 02:00 on the home clock. It keeps no state in the database (a rollback
 * replaces that): the files in the backup folder say which nights are done. A dump that fails is
 * tried again an hour later, and the person gets one quiet note in the feed per night.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class NightlyBackup(
    private val service: BackupService,
    private val settings: SettingsProvider,
    private val clock: Clock,
    private val feed: FeedService,
) : BackgroundService {
    override val name = "nightly-backup"

    override suspend fun run() {
        if (!service.enabled) {
            log.info { "backups are turned off" }
            return
        }
        service.folder.ensure()
        var retryAt: Instant? = null
        var told: LocalDate? = null
        while (true) {
            val now = clock.now()
            val zone = zoneOrUtc(settings.current().homeZone)
            val due = NightlySchedule.missing(now, zone, service.nightlyDates())
            if (due != null && (retryAt == null || now >= retryAt)) {
                val outcome = try {
                    service.backupNightly(due, wait = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "the nightly backup failed" }
                    null
                }
                if (outcome?.isRight() == true) {
                    retryAt = null
                    log.info { "nightly backup for $due written" }
                } else {
                    retryAt = clock.now() + RETRY
                    log.warn { "nightly backup for $due failed; trying again at $retryAt" }
                    if (told != due) {
                        told = due
                        tell()
                    }
                }
            }
            delay(untilNextCheck(clock.now(), zone, retryAt))
        }
    }

    private suspend fun tell() {
        runCatching {
            feed.append(
                FeedDraft(
                    author = FeedItem.Author.AGENT,
                    cards = listOf(Card.Note("Last night's backup did not work. It will try again. Check Settings, then Backups.")),
                ),
            )
        }.onFailure { log.warn(it) { "could not post the backup notice" } }
    }

    companion object {
        val RETRY: Duration = 1.hours
        private val CHECK_EVERY = 5.minutes

        /** Sleep until the next 02:00 or the next look, whichever is sooner. */
        fun untilNextCheck(now: Instant, zone: TimeZone, retryAt: Instant?): Duration {
            val candidates = listOfNotNull(NightlySchedule.nextRun(now, zone) - now, retryAt?.let { it - now }, CHECK_EVERY)
            return candidates.min().coerceAtLeast(Duration.parse("1s"))
        }
    }
}
