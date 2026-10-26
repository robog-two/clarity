package net.robog.clarity.backend.scheduler

import net.robog.clarity.backend.agent.wire
import net.robog.clarity.backend.governance.NotifyDecision
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import net.robog.clarity.backend.agent.HelperManager
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.InboxStore
import net.robog.clarity.backend.agent.LeadAgent
import net.robog.clarity.backend.agent.MEMORY_GC_TOOLS
import net.robog.clarity.backend.agent.MemoryStore
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.feed.FeedStore
import net.robog.clarity.backend.feed.eveningSummaryCards
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.session.Settings

private val log = KotlinLogging.logger {}

/** Asks the lead to look over what changed since the last review and revisit anything waiting on information. */
@Inject
@ContributesIntoSet(AppScope::class)
class PeriodicReviewJob(private val config: AgentConfig, private val inbox: InboxStore) : ScheduledJob {
    override val name = "periodic-review"

    override fun schedule(settings: Settings) = Schedule.Every(config.reviewInterval)

    override suspend fun fire(context: FireContext) {
        val since = context.lastRunAt ?: (context.now - config.reviewInterval)
        inbox.enqueue(
            InboxItem.JobDue(
                job = name,
                instruction = "Review what changed since $since: look at new messages and calendar events in that window, " +
                    "tell the person only what matters, and revisit anything that was waiting on information. Nothing to do is a fine outcome.",
                priority = Priority.LOW,
            ),
            dedupKey = "job:$name:${context.scheduledFor}",
        )
    }
}

/** At the person's evening-summary time: everything held with urgency `batch` becomes one feed item. */
@Inject
@ContributesIntoSet(AppScope::class)
class EveningBatchJob(private val store: FeedStore, private val feed: FeedService) : ScheduledJob {
    override val name = "evening-batch"

    override fun schedule(settings: Settings) = Schedule.DailyAt(settings.eveningSummaryAt)

    override suspend fun fire(context: FireContext) {
        val pending = store.pendingBatch()
        val cards = eveningSummaryCards(pending)
        if (cards.isEmpty()) return
        // Keyed by the slot: a retry after a crash finds the summary already posted and only finishes marking.
        feed.append(
            FeedDraft(
                author = FeedItem.Author.AGENT,
                cards = cards,
                clientInputId = ClientInputId("evening:${context.scheduledFor}"),
                // the summary itself is the evening notification (phone: low-importance channel)
                notify = NotifyDecision.EveningBatch(context.scheduledFor).wire(),
            ),
        )
        store.markSummarized(pending.map { it.id })
    }
}

/** Daily: drop expired scratch notes and have a helper merge, de-duplicate and expire profile facts. */
@Inject
@ContributesIntoSet(AppScope::class)
class MemoryGcJob(
    private val memory: MemoryStore,
    private val constitutions: ConstitutionStore,
    private val helpers: HelperManager,
) : ScheduledJob {
    override val name = "memory-gc"

    override fun schedule(settings: Settings) = Schedule.DailyAt(LocalTime(3, 30))

    override suspend fun fire(context: FireContext) {
        memory.purgeScratch(context.now)
        val profile = constitutions.current()?.profile.orEmpty()
        if (profile.size < MIN_FACTS) return
        helpers.delegate(
            task = "Tidy the person's profile. Read it with read_profile. Merge facts that say the same thing, drop ones that are clearly stale " +
                "(check recent journal entries with read_journal when unsure), keep every fact that is still true, and keep each one short. " +
                "Then call replace_profile once with the complete cleaned list. Report in one line what you changed.",
            tools = MEMORY_GC_TOOLS,
            role = "helper",
            parent = RunScope("memory-gc", isLead = false),
        ).onLeft { log.warn { "memory clean-up helper did not start: $it" } }
    }

    private companion object { const val MIN_FACTS = 6 }
}

/** Every minute: compact the lead thread if the person is away and the prompt cache is still warm. */
@Inject
@ContributesIntoSet(AppScope::class)
class IdleCompactionJob(private val lead: LeadAgent, private val scope: CoroutineScope) : ScheduledJob {
    override val name = "idle-compaction"

    override fun schedule(settings: Settings) = Schedule.Every(1.minutes)

    override suspend fun fire(context: FireContext) {
        // Compaction is a model call: do not hold the job's row lock for it.
        scope.launch {
            try {
                lead.compactIfIdle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "idle compaction failed" }
            }
        }
    }
}
