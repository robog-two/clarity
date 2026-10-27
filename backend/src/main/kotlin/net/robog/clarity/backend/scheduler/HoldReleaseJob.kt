package net.robog.clarity.backend.scheduler

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.Clock
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.datetime.TimeZone
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.feed.FeedStore
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.getsThrough
import net.robog.clarity.backend.governance.protectedUntil
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.NOTIFY_IN_APP
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.session.Settings

private val log = KotlinLogging.logger {}

/** The `notify` value of a released hold: the phone notifies once when an item turns into this. */
fun releasedNotify(at: Instant): String = "released:$at"

/** A hold older than this when protection ends is not worth a buzz any more; it stays in the app only. */
val HOLD_MAX_AGE: Duration = 24.hours

/** How long a release is resent to a phone that reconnects (the phone ignores anything older anyway). */
val RELEASE_RESEND: Duration = 2.hours

/** What to do with one held notification. */
enum class HoldOutcome {
    /** Still in protected time. */
    Wait,

    /** Protection is over: notify now. */
    Release,

    /** Protection is over but the item is too old, or urgent push was turned off: in the app only. */
    Drop,
}

/**
 * Pure: whether a held item is due. It looks at the protected times as they are now, not at the
 * recorded `hold_until` time, so a block that was shortened or removed releases early and one
 * that was extended keeps holding. A hold whose time passed while the backend was down is released
 * on the first check outside protected time. [getsThrough] is true when someone the item is about
 * is now on "who gets through": the protection no longer applies to it.
 */
fun holdOutcome(
    itemAt: Instant,
    blocks: List<TimeBlock>,
    zone: TimeZone,
    settings: Settings,
    now: Instant,
    getsThrough: Boolean = false,
): HoldOutcome = when {
    !getsThrough && protectedUntil(blocks, now, zone) != null -> HoldOutcome.Wait
    !settings.pushUrgent || now - itemAt > HOLD_MAX_AGE -> HoldOutcome.Drop
    else -> HoldOutcome.Release
}

/**
 * Every minute: releases notifications that `send_to_user` held for protected time. A released
 * item gets `notify = released:<now>` (persisted, compare-and-set on the old value, so a retry or a
 * second instance never releases twice) and is published as [FeedEvent.ItemFinal]; a phone that
 * was offline gets it again when it reconnects (see `FeedRoutes`).
 *
 * An item about someone who now gets through is released too, even inside protected time: right
 * away when the constitution changes ([GetsThroughRelease]), and on the next tick otherwise.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class HoldReleaseJob(
    private val store: FeedStore,
    private val feed: FeedService,
    private val constitutions: ConstitutionStore,
) : ScheduledJob {
    override val name = "hold-release"

    override fun schedule(settings: Settings) = Schedule.Every(1.minutes)

    override suspend fun fire(context: FireContext) {
        releaseDue(context.settings, context.now)
    }

    /** Releases every held item that is due under the current constitution; returns those that now notify. */
    suspend fun releaseDue(settings: Settings, now: Instant): List<FeedItem> {
        val held = store.heldNotifications()
        if (held.isEmpty()) return emptyList()
        val constitution = constitutions.current()
        val about = if (constitution?.getsThrough.isNullOrEmpty()) emptyMap() else store.aboutOf(held.map { it.id })
        return release(held, constitution?.protectedTimes.orEmpty(), settings, now) { item ->
            constitution != null && getsThrough(about[item.id].orEmpty(), constitution, now)
        }
    }

    /**
     * Releases the due items of [held]; returns those that now notify. [getsThroughNow] says whether
     * an item is about someone who gets through at [now].
     */
    suspend fun release(
        held: List<FeedItem>,
        blocks: List<TimeBlock>,
        settings: Settings,
        now: Instant,
        getsThroughNow: (FeedItem) -> Boolean = { false },
    ): List<FeedItem> {
        val zone = zoneOrUtc(settings.homeZone)
        return held.mapNotNull { item ->
            when (holdOutcome(item.at, blocks, zone, settings, now, getsThroughNow(item))) {
                HoldOutcome.Wait -> null
                HoldOutcome.Drop -> {
                    store.releaseHold(item.id, item.notify, NOTIFY_IN_APP, releasedAt = null)
                    null
                }
                HoldOutcome.Release -> store.releaseHold(item.id, item.notify, releasedNotify(now), releasedAt = now)?.also {
                    feed.publish(FeedEvent.ItemFinal(it))
                    log.debug { "released held notification ${it.id}" }
                }
            }
        }
    }
}

/**
 * When the constitution changes (someone added to "who gets through", or a rule changed so they
 * now get through), releases what is already held from them at once instead of at the next tick
 * or at the end of protected time. Same path and compare-and-set as [HoldReleaseJob], so it never
 * notifies twice; a missed event is caught by the job's next tick.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class GetsThroughRelease(
    private val governance: GovernanceEvents,
    private val job: HoldReleaseJob,
    private val settings: SettingsProvider,
    private val clock: Clock,
) : BackgroundService {
    override val name = "gets-through-release"

    override suspend fun run() {
        governance.events.filterIsInstance<GovernanceEvent.ConstitutionChanged>().collect {
            val released = job.releaseDue(settings.current(), clock.now())
            if (released.isNotEmpty()) log.info { "released ${released.size} held notification(s) after constitution v${it.version}" }
        }
    }
}
