package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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
import net.robog.clarity.backend.config.AgentRole

import net.robog.clarity.backend.feed.FeedDraft
import net.robog.clarity.backend.feed.FeedService
import net.robog.clarity.backend.governance.ConstitutionStore
import net.robog.clarity.backend.governance.zoneOrUtc
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.FinishReason
import net.robog.clarity.harness.HarnessEvent
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.Role
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.contextSince
import net.robog.clarity.harness.foldInterrupts
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.time.ScheduleClock

private val log = KotlinLogging.logger {}

/** Pure: the text of the last system message in [messages] that starts with [prefix]. */
fun lastSystemText(messages: List<Message>, prefix: String): String? =
    messages.asReversed().firstNotNullOfOrNull { m ->
        if (m.role != Role.SYSTEM) null else (m.content.singleOrNull() as? Block.Text)?.text?.takeIf { it.startsWith(prefix) }
    }

/**
 * Pure: does a journal end mid-turn (an unanswered user message or tool results, or unanswered tool
 * calls)? A client-side summary at the end is not: it waits for the next turn.
 */
fun needsResume(history: List<Message>): Boolean {
    val last = history.lastOrNull() ?: return false
    return when (last.role) {
        Role.USER -> !last.isSummary
        Role.SYSTEM -> true
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

/**
 * The operator note appended when the person wrote and the lead ended its turn without answering
 * through `send_to_user` (open models sometimes do the work and stop). Appended at the end of the
 * thread, so the cached prefix is untouched.
 */
const val REPLY_NUDGE: String =
    "[clarity] The person wrote to you and has had no reply: your plain text is not shown to them. " +
        "Reply now with send_to_user (a short answer is fine)."

/**
 * Tools whose successful call shows the person something, so it counts as answering them:
 * `send_to_user`, and the session tools that post their own card (in a live call the person sees
 * "captured · N" or the plan appear; a nudge there would only add chatter).
 */
val ANSWERING_TOOLS: Set<String> = setOf("send_to_user", "present_report", "capture", "set_priorities", "set_plan", "hand_off", "propose_line")

/**
 * Pure: whether a run that just ended still owes the person a reply: they wrote during this turn
 * ([replyOwed]: no successful call of one of the [ANSWERING_TOOLS] since), and the model finished on its own (END_TURN).
 * A run that failed, hit its turn limit, refused or compacted gets no nudge.
 */
fun owesReply(replyOwed: Boolean, terminal: HarnessEvent.Terminal?): Boolean =
    replyOwed && terminal is HarnessEvent.Finished && terminal.reason == FinishReason.END_TURN

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
 * Plain assistant text is never shown to the person; `send_to_user` is the only way out. When the
 * person wrote during a turn and the model ends it without a successful `send_to_user`, the lead
 * appends [REPLY_NUDGE] and runs once more ([runTurn]); never more than once per turn. Turns made
 * only of events (jobs, triggers, helper results) are never nudged: silence is fine there.
 *
 * Compaction: the lead does it itself after a turn ([compactClientSide]): one summarizing call, whose
 * text is appended to the journal as a [Message.summary]; requests start there from then on. An
 * Anthropic `compaction` block in a journal from before OpenRouter was the only provider counts as
 * a compaction point too ([contextSince]); it is read as it is, never rewritten.
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
    private val collections: CollectionStore,
    private val toolbox: AgentToolbox,
    private val large: LargeResults,
    private val feed: FeedService,
    private val settings: SettingsProvider,
    private val clock: Clock,
    private val effortOverride: EffortOverride,
) : BackgroundService {
    override val name = "lead-agent"

    private lateinit var threadId: String
    private lateinit var scope: RunScope
    private val turnLock = Mutex()
    private val awaiting = ConcurrentLinkedQueue<Delivery>()
    private var lastUserActivity: Instant? = null

    /** When the thread was last told the current time (an inbox header or a `[now …]` line); null after a restart. */
    private var lastClockStated: Instant? = null
    private var failures = 0

    /** The person wrote during the current turn and nothing has answered them since ([ANSWERING_TOOLS]). */
    private val replyOwed = AtomicBoolean(false)

    /** The prompt size of the lead's last model call (from usage); null until one ran, and after a client-side compaction. */
    private var lastPromptTokens: Long? = null

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
            log.warn { "No OpenRouter API key configured (OPENROUTER_API_KEY or OPENROUTER_API_KEY_FILE): the lead agent cannot start" }
            noticeDisabled()
            runtime.awaitEnabled()
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
                cards = listOf(Card.Note("I can't work yet: the server has no OpenRouter API key. Once it is set there and the server restarts, I'll start.")),
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
                replyOwed.set(false)
                feed.publish(FeedEvent.Busy(true))
                try {
                    val bound = runtime.bindOrFail(AgentRole.LEAD)
                    runTurn(bound)
                    threads.touchTurn(clock.now())
                    compactClientSide(bound)
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
        replyOwed.set(userTurn)
        val history = journal.load(threadId)
        val visible = contextSince(history)
        val zone = settings.homeZone()
        // Like the memory context: state the time again if the model can no longer see one (compacted away).
        val statesClock = clockDue(lastClockStated, now, zone) || !statesTime(visible)
        val rendered = renderDeliveries(items, now, zone, statesClock = statesClock)
        if (rendered.any { it.message.role == Role.SYSTEM }) lastClockStated = now
        val deliveries = memoryDelivery(now, visible) + rendered
        if (userTurn) {
            lastUserActivity = now
            threads.touchUserInput(now)
        }
        feed.publish(FeedEvent.Busy(true))
        try {
            // The turn boundary: the lead's settings are read here and kept for the whole turn.
            val bound = runtime.bindOrFail(AgentRole.LEAD)
            val closing = closeOpenToolCalls(history)
            closing?.let { trackedJournal.append(threadId, it) }
            // A live call runs at low effort and the thread goes back afterwards, by a mid-conversation message (the cache prefix stays intact).
            val roleEffort = bound.config.effort
            // Only what the model still reads counts: an effort set before the last compaction point is gone.
            val effortMessage = effortChange(lastEffort(contextSince(history)) ?: roleEffort, effortOverride.current() ?: roleEffort)
            val messages = foldInterrupts(
                if (closing != null) Role.USER else history.lastOrNull()?.role,
                deliveries.map { it.message } + listOfNotNull(effortMessage),
            )
            awaiting.addAll(deliveries.filter { it.seqs.isNotEmpty() })
            messages.forEach { trackedJournal.append(threadId, it) }
            runTurn(bound)
            threads.touchTurn(clock.now())
            compactClientSide(bound)
        } finally {
            awaiting.clear()
            withContext(NonCancellable) { feed.publish(FeedEvent.Busy(false)) }
        }
        if (failures > 0) delay(minOf(60.seconds, 5.seconds * (1 shl (failures - 1).coerceAtMost(4))))
    }

    /**
     * The memory context and the collection index, each only when it differs from the last one the
     * model can still see ([visible]: the thread since its latest compaction point). So nothing is
     * repeated while it is in view, after a restart included, and both come back after a compaction.
     */
    private suspend fun memoryDelivery(now: Instant, visible: List<Message>): List<Delivery> {
        val zone = settings.homeZone()
        val today = ScheduleClock.homeDate(now, zone)
        val yesterday = today.minus(DatePeriod(days = 1))
        val lastMemory = lastSystemText(visible, MEMORY_CONTEXT_PREFIX)
        val memoryText = renderMemoryContext(today, memory.journal(yesterday, today), memory.liveScratch(now), zone)
            ?: if (lastMemory != null && !lastMemory.endsWith(EMPTY_MEMORY)) "[memory, $today] $EMPTY_MEMORY" else null
        val lastIndex = lastSystemText(visible, COLLECTION_INDEX_PREFIX)
        val indexText = renderCollectionIndex(collections.collections())
            ?: if (lastIndex != null && lastIndex != NO_COLLECTIONS) NO_COLLECTIONS else null
        return listOfNotNull(memoryText.takeIf { it != lastMemory }, indexText.takeIf { it != lastIndex })
            .map { Delivery(emptyList(), Message(Role.SYSTEM, listOf(Block.Text(it)))) }
    }

    /**
     * One lead turn: the harness run, and, when the person wrote and got no reply, ONE more run after
     * [REPLY_NUDGE]. The nudge goes through the operator channel (a system message, after a
     * placeholder user message since the thread ends with the assistant), appended at the end.
     */
    private suspend fun runTurn(bound: BoundRole) {
        val first = runHarness(bound)
        if (!owesReply(replyOwed.get(), first)) return
        log.info { "the person wrote and the lead ended without send_to_user: nudging once" }
        foldInterrupts(Role.ASSISTANT, listOf(Message.system(REPLY_NUDGE))).forEach { trackedJournal.append(threadId, it) }
        val second = runHarness(bound)
        if (owesReply(replyOwed.get(), second)) {
            log.warn { "the lead still did not answer the person after the nudge; leaving it" }
        }
    }

    /**
     * One lead harness run on [bound]'s model. Reasoning blocks from an earlier model (and old
     * Anthropic blocks) stay in the journal as they are; the gateway replays reasoning only to the
     * model that wrote it, so nothing in the history is ever edited.
     */
    private suspend fun runHarness(bound: BoundRole): HarnessEvent.Terminal? = coroutineScope {
        val tools = toolbox.leadTools(scope)
        val request = roleRequest(
            role = bound.config,
            runId = threadId,
            system = systemBlocks(),
            messages = emptyList(),
            tools = tools.tools,
            maxTurns = config.maxTurns,
            sink = large.sink,
        )
        val interrupts = Channel<List<Message>>(Channel.UNLIMITED)
        val watcher = launch { watchUserInput(interrupts) }
        var terminal: HarnessEvent.Terminal? = null
        try {
            runtime.harness(trackedJournal, bound).resume(threadId, request, interrupts.receiveAsFlow()).collect { event ->
                when (event) {
                    is HarnessEvent.AssistantText -> log.debug { "lead (not shown): ${event.text.take(200)}" }
                    is HarnessEvent.Called -> lastPromptTokens = event.usage.promptTokens
                    is HarnessEvent.CacheMiss -> log.warn { "prompt cache miss on lead turn ${event.turn}: ${event.usage}" }
                    is HarnessEvent.ToolCompleted -> if (event.name in ANSWERING_TOOLS && !event.isError) replyOwed.set(false)
                    is HarnessEvent.Terminal -> terminal = event
                    else -> Unit
                }
            }
        } finally {
            watcher.cancel()
            interrupts.close()
        }
        afterTurn(terminal)
        terminal
    }

    private suspend fun systemBlocks() =
        leadSystemBlocks(constitutions.current(), HomeClock(zoneOrUtc(settings.current().homeZone), clock.now()))

    /**
     * After a turn: once the last call's prompt reached
     * the role's `compactAtTokens`, one call on the same model summarizes the thread, and the summary
     * is appended to the journal ([Message.summary]). Nothing earlier is rewritten. A failed or empty
     * summary is skipped; the next turn tries again.
     */
    private suspend fun compactClientSide(bound: BoundRole) {
        val history = journal.load(threadId)
        if (!shouldCompactClientSide(bound.config, lastPromptTokens, history)) return
        log.info { "compacting the lead thread client-side (last prompt ${lastPromptTokens ?: "unknown"} tokens, threshold ${bound.config.compactAtTokens})" }
        val tools = toolbox.leadTools(scope)
        val request = clientCompactionRequest(bound.config, systemBlocks(), history, tools.tools)

        val response = try {
            bound.gateway.complete(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "client-side compaction failed; the next turn tries again" }
            return
        }
        val summary = response.text.trim()
        if (response.stopReason != StopReason.END_TURN || summary.isEmpty()) {
            log.warn { "client-side compaction gave no summary (${response.stopReason}); the next turn tries again" }
            return
        }
        trackedJournal.append(threadId, Message.summary(summary))
        lastPromptTokens = null
        threads.markCompacted(clock.now())
        // A pill in the app's chat names what the compacted stretch was about.
        postCompactionTopic(runtime.bind(AgentRole.TRIAGE), feed, summary)
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
                val deliveries = renderDeliveries(users, clock.now(), settings.homeZone())
                scope.userAsked()
                replyOwed.set(true)
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

    private companion object {
        const val MEMORY_CONTEXT_PREFIX = "[memory, "
        const val COLLECTION_INDEX_PREFIX = "[memory collections]"
        const val EMPTY_MEMORY = "The journal and scratch notes are empty."
        const val NO_COLLECTIONS = "[memory collections] none left."
        val IDLE_POLL = 30.seconds
        val INTERRUPT_POLL = 5.seconds
    }
}
