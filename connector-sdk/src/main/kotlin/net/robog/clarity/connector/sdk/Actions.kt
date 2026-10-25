package net.robog.clarity.connector.sdk

import arrow.core.Either
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.action.ConnectorAction
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ProposedAction

private val log = KotlinLogging.logger {}

/**
 * Handlers for the actions this connector executes, keyed by action type:
 *
 * ```kotlin
 * val handlers = actionHandlers {
 *     on<ProposedAction.SaveDraft> { action -> /* IMAP APPEND */ ActionOutcome.Done(SourceId("uid-1")) }
 * }
 * ```
 *
 * A handler must make execution idempotent in the upstream system where it can (use
 * `action.idempotencyKey`, e.g. as a Message-ID), because delivery is at-least-once. Return
 * [ActionOutcome.Failed] with `retryable = true` for transient problems; a thrown exception is
 * treated the same way.
 */
class ActionHandlers internal constructor(private val byType: Map<KClass<out ProposedAction>, suspend (ProposedAction) -> ActionOutcome>) {
    val isEmpty: Boolean get() = byType.isEmpty()

    /** The kinds handled, for comparing against the manifest's `handles`. */
    val kinds: Set<ActionKind> get() = byType.keys.mapNotNull { kindOf(it) }.toSet()

    internal suspend fun handle(action: ProposedAction): ActionOutcome? = byType[action::class]?.invoke(action)

    companion object {
        val None = ActionHandlers(emptyMap())

        private fun kindOf(type: KClass<out ProposedAction>): ActionKind? {
            val serialName = serializerFor(type)
            return ActionKind.entries.firstOrNull { kind -> kind.wireName() == serialName }
        }

        private fun serializerFor(type: KClass<out ProposedAction>): String? =
            runCatching { serializer(type.java).descriptor.serialName }.getOrNull()

        private fun ActionKind.wireName(): String = serializer<ActionKind>().descriptor.getElementName(ordinal)
    }
}

class ActionHandlersBuilder {
    private val handlers = LinkedHashMap<KClass<out ProposedAction>, suspend (ProposedAction) -> ActionOutcome>()

    fun <T : ProposedAction> on(type: KClass<T>, handler: suspend (T) -> ActionOutcome) {
        @Suppress("UNCHECKED_CAST")
        handlers[type] = { action -> handler(action as T) }
    }

    inline fun <reified T : ProposedAction> on(noinline handler: suspend (T) -> ActionOutcome) = on(T::class, handler)

    internal fun build() = ActionHandlers(handlers.toMap())
}

fun actionHandlers(block: ActionHandlersBuilder.() -> Unit): ActionHandlers = ActionHandlersBuilder().apply(block).build()

/**
 * Ledger of executed idempotency keys (in the connector's [StateStore]) so a re-delivered action
 * (lost result, expired lease) returns the stored outcome instead of executing again. Only final
 * outcomes are recorded: `Done` and non-retryable `Failed`. Bounded to the [capacity] most recent.
 */
class ActionLedger(private val state: StateStore, private val capacity: Int = 1000) {
    private val mutex = Mutex()

    suspend fun lookup(key: String): ActionOutcome? = mutex.withLock { read()[key] }

    suspend fun record(key: String, outcome: ActionOutcome) = mutex.withLock {
        val next = LinkedHashMap(read())
        next.remove(key) // re-insert at the end
        next[key] = outcome
        while (next.size > capacity) next.remove(next.keys.first())
        state.put(STATE_KEY, ClarityJson.encodeToJsonElement(serializer, next))
    }

    private suspend fun read(): Map<String, ActionOutcome> =
        state.get(STATE_KEY)?.let { runCatching { ClarityJson.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: emptyMap()

    private companion object {
        const val STATE_KEY = "action-ledger"
        val serializer = MapSerializer(String.serializer(), ActionOutcome.serializer())
    }
}

/** True for outcomes that will never change, so they may be replayed from the ledger. */
internal fun ActionOutcome.isFinal(): Boolean = this is ActionOutcome.Done || (this is ActionOutcome.Failed && !retryable)

/**
 * Executes one delivered [action]: replays a recorded outcome if its key was executed before,
 * otherwise runs the handler, records a final outcome and returns it. Never throws (except
 * cancellation): handler exceptions become retryable failures.
 */
internal suspend fun execute(action: ConnectorAction, handlers: ActionHandlers, ledger: ActionLedger): ActionOutcome {
    val key = action.action.idempotencyKey
    ledger.lookup(key)?.let {
        log.info { "action ${action.id} (key $key) already executed; replaying stored outcome" }
        return it
    }
    val outcome = try {
        handlers.handle(action.action)
            ?: ActionOutcome.Failed("this connector has no handler for ${action.action.kind}", retryable = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "handler for action ${action.id} failed" }
        ActionOutcome.Failed(e.message ?: e::class.simpleName ?: "handler failed", retryable = true)
    }
    if (outcome.isFinal()) ledger.record(key, outcome)
    return outcome
}

/**
 * One round of the action loop: long-poll for [wait], execute what arrives (sequentially, in the
 * order delivered) and report each result. Returns the poll error, if any, so the caller can back off.
 */
internal suspend fun pollAndExecute(
    client: ClarityClient,
    handlers: ActionHandlers,
    ledger: ActionLedger,
    wait: Duration,
): ClientError? {
    val poll = when (val r = client.pollActions(wait)) {
        is Either.Left -> return r.value
        is Either.Right -> r.value
    }
    for (action in poll.actions) {
        val outcome = execute(action, handlers, ledger)
        // A failed report is fine: the lease lapses, the action is re-delivered, and the ledger answers it.
        client.postResult(action.id, ActionResult(outcome)).onLeft { log.warn { "could not report ${action.id}: ${it.message}" } }
    }
    return null
}

/** Runs [pollAndExecute] forever, backing off (up to 30 s) while the backend is unreachable. */
internal suspend fun runActionLoop(
    client: ClarityClient,
    state: StateStore,
    handlers: ActionHandlers,
    wait: Duration = 25.seconds,
) {
    val ledger = ActionLedger(state)
    var backoff = 1.seconds
    while (true) {
        val error = pollAndExecute(client, handlers, ledger, wait)
        if (error == null) {
            backoff = 1.seconds
        } else {
            log.warn { "action poll failed: ${error.message}; retrying in $backoff" }
            delay(backoff)
            backoff = minOf(backoff * 2, 30.seconds)
        }
    }
}
