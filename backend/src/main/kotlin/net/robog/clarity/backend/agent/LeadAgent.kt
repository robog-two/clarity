package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.coroutines.withContext
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.harness.foldInterrupts
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem

private val log = KotlinLogging.logger {}

/** Pure: does a journal end mid-turn (an unanswered user message or tool results, or unanswered tool calls)? */
fun needsResume(history: List<Message>): Boolean {
    val last = history.lastOrNull() ?: return false
    return when (last.role) {
        Role.USER, Role.SYSTEM -> true
        Role.ASSISTANT -> last.content.any { it is Block.ToolUse }
    }
}

/**
 * Pure: when the journal ends in an assistant turn whose tool calls never got results (the run
 * stopped at its turn limit), a user message answering them, so the next turn is valid.
 */
fun closeOpenToolCalls(history: List<Message>): Message? {
    val last = history.lastOrNull()?.takeIf { it.role == Role.ASSISTANT } ?: return null
    val open = last.content.filterIsInstance<Block.ToolUse>()
    if (open.isEmpty()) return null
    return Message(Role.USER, open.map { Block.Result(it.id, "Not run: the turn ended before this call could run.", isError = true) })
}

/** How long after the person's last word the lead counts as "not idle" for low priority work. */
val USER_QUIET_FOR_LOW_PRIORITY: Duration = 60.seconds

/**
 * The always-on lead agent: owns the one thread. Loop: wait for the inbox, plan the next turn
 * ([planNextTurn]), journal the rendered messages, run the harness over the thread. The person's
 * input arriving mid-turn pre-empts the model call (the harness folds it in); everything else
 * waits for the next turn and is batched.
 *
 * Durable: the journal is the thread; at boot a turn the crash interrupted is resumed
 * (unanswered tool calls run again; external ones are idempotent through the action gate).
 * Inbox rows are marked consumed only when the message carrying them has been journaled.
 *
 * Plain assistant text is never shown to the person; `send_to_user` is the only way out.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class LeadAgent(
    private val config: AgentConfig,
    private val runtime: AgentRuntime,
    private val journal: RunJournal,
    private val inbox: InboxStore,
    private val signals: InboxSignals,
    private val threads: ThreadStore,
    private val constitutions: ConstitutionStore,
    private val memory: MemoryStore,
    private val toolbox: AgentToolbox,
    private val large: LargeResults,
    private val feed: FeedService,
    private val settings: SettingsProvider,
    private val clock: Clock,
) : BackgroundService {
    override val name = "lead-agent"

    private lateinit var threadId: String
    private lateinit var scope: RunScope
    private val turnLock = Mutex()
    private val awaiting = ConcurrentLinkedQueue<Delivery>()
    private var lastMemoryContext: String? = null
    private var lastUserActivity: Instant? = null
    private var failures = 0

    /** Journal decorator: marks inbox rows consumed once the message carrying them is durable. */
    private val trackedJournal = object : RunJournal {
        override suspend fun append(runId: String, message: Message) {
            journal.append(runId, message)
            if (runId == threadId) consumeMatching(message)
        }

        override suspend fun load(runId: String): List<Message> = journal.load(runId)
    }

    override suspend fun run() {
        if (!runtime.enabled) {
            log.warn { "No Anthropic API key configured (clarity.agent.anthropicApiKey): the lead agent is disabled" }
            noticeDisabled()
            return
        }
        start()
        while (true) {
            val ran = try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "lead turn crashed" }
                false
            }
            if (!ran) signals.await(IDLE_POLL)
        }
    }

    private suspend fun noticeDisabled() {
        feed.append(
            FeedDraft(
                author = FeedItem.Author.AGENT,
                cards = listOf(Card.Note("I can't work yet: there is no Anthropic API key. Add one in setup and I'll start.")),
                clientInputId = ClientInputId("system:no-api-key"),
            ),
        )
    }

    /** Finds or creates the thread and resumes a turn the last run left open. */
    suspend fun start() {
        val thread = threads.ensure()
        threadId = thread.id
        scope = RunScope(threadId, isLead = true)
        if (needsResume(journal.load(threadId))) {
            log.info { "resuming the lead thread from its journal" }
            turnLock.withLock {
                scope.startTurn(untrusted = true, requestedByUser = false)
                feed.publish(FeedEvent.Busy(true))
                try {
                    runHarness(compactNow = false)
                    threads.touchTurn(clock.now())
                } finally {
                    withContext(NonCancellable) { feed.publish(FeedEvent.Busy(false)) }
                }
            }
        }
    }

    /** Plans and runs one turn if there is work; false when there was nothing to do. */
    suspend fun step(): Boolean {
        val now = clock.now()
        val quiet = lastUserActivity?.let { now - it >= USER_QUIET_FOR_LOW_PRIORITY } ?: true
        return when (val plan = planNextTurn(inbox.pending(), now, idle = quiet)) {
            TurnPlan.Wait -> false
            is TurnPlan.Turn -> {
                turn(plan.items, plan.userTurn, untrusted = hasUntrusted(plan.items))
                true
            }
        }
    }

    private suspend fun turn(items: List<PendingItem>, userTurn: Boolean, untrusted: Boolean) = turnLock.withLock {
        val now = clock.now()
        scope.startTurn(untrusted = untrusted, requestedByUser = userTurn)
        val deliveries = memoryDelivery(now) + renderDeliveries(items, now)
        if (userTurn) {
            lastUserActivity = now
            threads.touchUserInput(now)
        }
        feed.publish(FeedEvent.Busy(true))
        try {
            val history = journal.load(threadId)
            val closing = closeOpenToolCalls(history)
            closing?.let { trackedJournal.append(threadId, it) }
            val messages = foldInterrupts(if (closing != null) Role.USER else history.lastOrNull()?.role, deliveries.map { it.message })
            awaiting.addAll(deliveries.filter { it.seqs.isNotEmpty() })
            messages.forEach { trackedJournal.append(threadId, it) }
            runHarness(compactNow = false)
            threads.touchTurn(clock.now())
        } finally {
            awaiting.clear()
            withContext(NonCancellable) { feed.publish(FeedEvent.Busy(false)) }
        }
        if (failures > 0) delay(minOf(60.seconds, 5.seconds * (1 shl (failures - 1).coerceAtMost(4))))
    }

    /** The memory context message, only when it differs from what the thread last saw. */
    private suspend fun memoryDelivery(now: Instant): List<Delivery> {
        val zone = settings.current().timeZone
        val today = journalDate(now, zone)
        val yesterday = today.minus(DatePeriod(days = 1))
        val text = renderMemoryContext(today, memory.journal(yesterday, today), memory.liveScratch(now))
            ?: if (lastMemoryContext != null) "[memory, $today] The journal and scratch notes are empty." else null
        if (text == null || text == lastMemoryContext) return emptyList()
        lastMemoryContext = text
        return listOf(Delivery(emptyList(), Message(Role.SYSTEM, listOf(Block.Text(text)))))
    }

    private suspend fun runHarness(compactNow: Boolean) = coroutineScope {
        val constitution = constitutions.current()
        val tools = toolbox.leadTools(scope)
        val request = roleRequest(
            role = config.roles.lead,
            runId = threadId,
            system = leadSystemBlocks(constitution),
            messages = emptyList(),
            tools = tools.loaded,
            deferred = tools.deferred,
            maxTurns = config.maxTurns,
            sink = large.sink,
            compactNow = compactNow,
        )
        val interrupts = Channel<List<Message>>(Channel.UNLIMITED)
        val watcher = if (compactNow) null else launch { watchUserInput(interrupts) }
        var terminal: HarnessEvent.Terminal? = null
        try {
            runtime.harness(trackedJournal).resume(threadId, request, interrupts.receiveAsFlow()).collect { event ->
                when (event) {
                    is HarnessEvent.AssistantText -> log.debug { "lead (not shown): ${event.text.take(200)}" }
                    is HarnessEvent.CacheMiss -> log.warn { "prompt cache miss on lead turn ${event.turn}: ${event.usage}" }
                    is HarnessEvent.Terminal -> terminal = event
                    else -> Unit
                }
            }
        } finally {
            watcher?.cancel()
            interrupts.close()
        }
        afterTurn(terminal)
    }

    private suspend fun afterTurn(terminal: HarnessEvent.Terminal?) {
        when (terminal) {
            is HarnessEvent.Finished -> {
                failures = 0
                val u = terminal.usage
                log.info { "lead turn ${terminal.reason} (in=${u.inputTokens} out=${u.outputTokens} cacheRead=${u.cacheReadInputTokens})" }
                if (terminal.reason == FinishReason.REFUSAL) {
                    note("The model declined to continue with that. If you meant it, say it another way.")
                }
            }
            is HarnessEvent.Failed -> {
                failures++
                log.error { "lead turn failed: ${terminal.message}" }
                if (failures == 1) note("I couldn't reach the model just now (${terminal.message.take(120)}). I'll try again shortly.")
            }
            null -> Unit
        }
    }

    private suspend fun note(text: String) {
        feed.append(FeedDraft(author = FeedItem.Author.AGENT, cards = listOf(Card.Note(text))))
    }

    /** While a turn runs: hand the person's new input to the harness, which pre-empts the model call. */
    private suspend fun watchUserInput(interrupts: Channel<List<Message>>) {
        val delivered = mutableSetOf<Long>()
        while (true) {
            val users = inbox.pending().filter { it.item.priority == Priority.USER && it.seq !in delivered }
            if (users.isNotEmpty()) {
                val deliveries = renderDeliveries(users, clock.now())
                scope.userAsked()
                lastUserActivity = clock.now()
                awaiting.addAll(deliveries)
                delivered += users.map { it.seq }
                interrupts.send(deliveries.map { it.message })
            }
            signals.await(INTERRUPT_POLL)
        }
    }

    private suspend fun consumeMatching(message: Message) {
        val remaining = message.content.toMutableList()
        val consumed = mutableListOf<Long>()
        val iterator = awaiting.iterator()
        while (iterator.hasNext()) {
            val delivery = iterator.next()
            if (delivery.message.role != message.role) continue
            val block = delivery.message.content.singleOrNull() ?: continue
            val index = remaining.indexOf(block)
            if (index >= 0) {
                remaining.removeAt(index)
                consumed += delivery.seqs
                iterator.remove()
            }
        }
        inbox.markConsumed(consumed)
    }

    /**
     * Idle housekeeping: when the person has been quiet and the cache is still warm, compact the
     * thread now (cheap while warm) so the next conversation starts from the summary. Returns
     * whether a compaction ran. Never blocks a turn: it gives up if the lead is busy.
     */
    suspend fun compactIfIdle(): Boolean {
        if (!runtime.enabled || !::threadId.isInitialized || !turnLock.tryLock()) return false
        try {
            val now = clock.now()
            val thread = threads.ensure()
            val history = journal.load(threadId)
            if (history.lastOrNull()?.role != Role.ASSISTANT || history.last().content.any { it is Block.ToolUse }) return false
            if (inbox.pending().any { it.item.priority == Priority.USER }) return false
            val due = shouldCompactNow(
                now, lastUserActivity ?: thread.lastUserInputAt, thread.lastTurnAt, thread.compactedAt,
                config.roles.lead.cacheTtl, estimateTokens(history),
            )
            if (!due) return false
            log.info { "compacting the lead thread while the cache is warm" }
            trackedJournal.append(threadId, Message.user("(housekeeping: the conversation is being compacted; nothing to answer)"))
            runHarness(compactNow = true)
            threads.markCompacted(clock.now())
            return true
        } finally {
            turnLock.unlock()
        }
    }

    private companion object {
        val IDLE_POLL = 30.seconds
        val INTERRUPT_POLL = 5.seconds
    }
}
