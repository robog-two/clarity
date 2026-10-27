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
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import net.robog.clarity.backend.agent.CollectionStore
import net.robog.clarity.backend.agent.ConversationArchive
import net.robog.clarity.backend.agent.EmbeddingIndexer
import net.robog.clarity.backend.agent.HelperManager
import net.robog.clarity.backend.agent.journalDate
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
import net.robog.clarity.shared.time.LocalTimes
import net.robog.clarity.shared.time.zoneOrUtc

private val log = KotlinLogging.logger {}

/** Asks the lead to look over what changed since the last review and revisit anything waiting on information. */
@Inject
@ContributesIntoSet(AppScope::class)
class PeriodicReviewJob(private val config: AgentConfig, private val inbox: InboxStore) : ScheduledJob {
    override val name = "periodic-review"

    override fun schedule(settings: Settings) = Schedule.Every(config.reviewInterval)

    override suspend fun fire(context: FireContext) {
        val since = context.lastRunAt ?: (context.now - config.reviewInterval)
        val sinceLocal = LocalTimes.format(since, zoneOrUtc(context.settings.homeZone))
        inbox.enqueue(
            InboxItem.JobDue(
                job = name,
                instruction = "Review what changed since $sinceLocal: look at new messages and calendar events in that window, " +
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

/**
 * Daily: drop expired scratch notes and have a helper tidy memory: merge and expire profile facts,
 * merge duplicate collections, keep their descriptions accurate, and file durable facts from recent
 * journal entries into collections. Skipped while there is little to tidy (it costs a helper run).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class MemoryGcJob(
    private val memory: MemoryStore,
    private val constitutions: ConstitutionStore,
    private val collections: CollectionStore,
    private val helpers: HelperManager,
) : ScheduledJob {
    override val name = "memory-gc"

    override fun schedule(settings: Settings) = Schedule.DailyAt(LocalTime(3, 30))

    override suspend fun fire(context: FireContext) {
        memory.purgeScratch(context.now)
        val today = journalDate(context.now, context.settings.homeZone)
        val weekAgo = today.minus(DatePeriod(days = 7))
        val profile = constitutions.current()?.profile.orEmpty()
        val worthIt = profile.size >= MIN_FACTS || collections.collections().size >= MIN_COLLECTIONS ||
            memory.journal(weekAgo, today).size >= MIN_JOURNAL
        if (!worthIt) return
        helpers.delegate(
            task = memoryGcTask(weekAgo, today),
            tools = MEMORY_GC_TOOLS,
            role = "helper",
            parent = RunScope("memory-gc", isLead = false),
        ).onLeft { log.warn { "memory clean-up helper did not start: $it" } }
    }

    private companion object {
        const val MIN_FACTS = 6
        const val MIN_COLLECTIONS = 2
        const val MIN_JOURNAL = 5
    }
}

/** Pure: the memory clean-up helper's task. */
fun memoryGcTask(from: LocalDate, until: LocalDate): String =
    "Tidy the agent's memory. Change only what is clearly better; when in doubt, leave it.\n" +
        "1. Profile: read it with read_profile. Merge facts that say the same thing, drop ones that are clearly stale " +
        "(check read_journal when unsure), keep every fact that is still true, and keep each one short. " +
        "If anything changed, call replace_profile once with the complete cleaned list.\n" +
        "2. Collections: list them with open_collection (no name) and open the ones you need. Merge collections about the same topic " +
        "(manage_collection with mergeInto), keep each description an accurate one-line summary of what is in it, " +
        "and remove notes that repeat another note or are clearly outdated (save_note with id and remove).\n" +
        "3. Journal: read entries from $from to $until with read_journal. File durable facts in them (people, places, projects, " +
        "preferences, how things are done) into the fitting collection with save_note, unless a note already says it; " +
        "create a collection only for a topic that clearly recurs. Leave passing events in the journal.\n" +
        "Never store passwords, codes, keys or tokens. Report in two or three lines what you changed."

/**
 * Every 10 minutes: archive the lead conversation that compaction took out of view (searchable
 * chunks) and embed what is new for semantic search. The first runs backfill; both steps are
 * idempotent and bounded, and run in the background so the job's row lock is not held.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class MemoryIndexJob(
    private val archive: ConversationArchive,
    private val indexer: EmbeddingIndexer,
    private val scope: CoroutineScope,
) : ScheduledJob {
    override val name = "memory-index"
    private val running = Mutex()

    override fun schedule(settings: Settings) = Schedule.Every(10.minutes)

    override suspend fun fire(context: FireContext) {
        scope.launch { runOnce() }
    }

    /** One pass; false when a pass was already running. */
    suspend fun runOnce(): Boolean {
        if (!running.tryLock()) return false
        try {
            val chunks = archive.indexCompacted()
            val embedded = indexer.run()
            if (chunks > 0 || embedded > 0) log.info { "memory index: $chunks conversation chunks archived, $embedded records embedded" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "memory indexing failed; the next run tries again" }
        } finally {
            running.unlock()
        }
        return true
    }
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
