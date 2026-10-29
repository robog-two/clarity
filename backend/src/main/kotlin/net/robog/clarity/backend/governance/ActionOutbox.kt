package net.robog.clarity.backend.governance

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.robog.clarity.backend.persistence.SourceLinks
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ConnectorAction
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** How long a connector holds an action before it is offered again. */
val LEASE_DURATION: Duration = 2.minutes

/** Total deliveries (first try included) before an action fails for good. */
const val MAX_ATTEMPTS = 5

/** Longest a long-poll may wait. */
val MAX_WAIT: Duration = 30.seconds

/** Backoff after the [attempt]th failed delivery: 15 s, 30 s, 60 s, ... capped at 30 min. */
fun retryDelay(attempt: Int): Duration =
    minOf(15.seconds * (1L shl (attempt - 1).coerceIn(0, 20)).toDouble(), 30.minutes)

/** What a reported outcome does to an action that has been delivered [attempts] times. */
sealed interface Transition {
    data object Finish : Transition
    data class Retry(val at: Instant) : Transition
    data object GiveUp : Transition
}

/** Pure: done finishes; a retryable failure retries with backoff until [MAX_ATTEMPTS]; anything else gives up. */
fun transitionFor(attempts: Int, outcome: ActionOutcome, now: Instant): Transition = when (outcome) {
    is ActionOutcome.Done -> Transition.Finish
    is ActionOutcome.Failed ->
        if (outcome.retryable && attempts < MAX_ATTEMPTS) Transition.Retry(now + retryDelay(attempts)) else Transition.GiveUp
}

/** The canonical entity an action refers to, if it refers to one. */
fun referencedEntity(action: ProposedAction): EntityId? = when (action) {
    is ProposedAction.SaveDraft -> action.email.inReplyTo
    is ProposedAction.SendEmail -> action.email.inReplyTo
    is ProposedAction.MoveEvent -> action.event
    is ProposedAction.RespondToInvite -> action.event
    is ProposedAction.ReadDocument -> action.document
    is ProposedAction.CreateEvent, is ProposedAction.CreateReminder,
    is ProposedAction.SetDoNotDisturb, is ProposedAction.ExternalTool,
    is ProposedAction.UseCredential, is ProposedAction.AskCode -> null
}

object OutboxStatus {
    const val QUEUED = "queued"
    const val LEASED = "leased"
    const val DONE = "done"
    const val FAILED = "failed"
}

data class OutboxRow(
    val id: ActionId,
    val connectorId: ConnectorId,
    val heldId: HeldActionId?,
    val action: ProposedAction,
    val idempotencyKey: String,
    val status: String,
    val attempts: Int,
    val result: ActionOutcome?,
)

/** Wakes long-polling connectors when something is queued. Best effort: pollers also re-check periodically. */
@Inject
@SingleIn(AppScope::class)
class OutboxSignals {
    private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun signal() { flow.tryEmit(Unit) }

    /** Suspends until signalled or [timeout] passes. */
    suspend fun await(timeout: Duration) { withTimeoutOrNull(timeout) { flow.first() } }
}

sealed interface Completion {
    /** The action already had a final result; nothing changed. */
    data class Duplicate(val row: OutboxRow) : Completion
    data class Finished(val row: OutboxRow) : Completion
    data class WillRetry(val row: OutboxRow, val at: Instant) : Completion
}

/**
 * The action outbox (`action_outbox`): durable, at-least-once delivery of approved or allowed
 * actions to the connector that handles them. Enqueueing is idempotent by the action's key;
 * results are idempotent per action; retryable failures back off exponentially.
 */
@Inject
@SingleIn(AppScope::class)
class ActionOutbox(
    private val database: Database,
    private val clock: Clock,
    private val held: HeldActionStore,
    private val signals: OutboxSignals,
    private val events: GovernanceEvents,
    private val results: ActionResults,
) {
    /**
     * Queues [action] for [connector]. A second call with the same [key] returns the existing id
     * without queuing again. [alone] additionally logs it as an activity entry ("taken alone").
     */
    suspend fun enqueue(connector: ConnectorId, action: ProposedAction, key: String = action.idempotencyKey, alone: Boolean = false): ActionId {
        val (id, entry) = database.tx { enqueueTx(connector, action, key, heldId = null, alone = alone) }
        signals.signal()
        entry?.let { events.publish(GovernanceEvent.Activity(it)) }
        return id
    }

    /** [enqueue] inside the caller's transaction. Returns the id and the activity entry if one was logged. */
    fun JdbcTransaction.enqueueTx(
        connector: ConnectorId,
        action: ProposedAction,
        key: String,
        heldId: HeldActionId?,
        alone: Boolean,
    ): Pair<ActionId, ActivityEntry?> {
        val id = Uuid.random()
        val now = clock.now()
        val inserted = ActionOutboxTable.insertIgnore {
            it[ActionOutboxTable.id] = id
            it[connectorId] = connector.value
            it[ActionOutboxTable.heldId] = heldId?.let { h -> parseHeldId(h.value) }
            it[ActionOutboxTable.action] = action
            it[idempotencyKey] = key
            it[status] = OutboxStatus.QUEUED
            it[attempts] = 0
            it[nextAttemptAt] = now
            it[createdAt] = now
        }.insertedCount > 0
        if (!inserted) {
            val existing = ActionOutboxTable.selectAll().where { ActionOutboxTable.idempotencyKey eq key }.single()[ActionOutboxTable.id]
            return ActionId(existing.toString()) to null
        }
        val entry = if (alone) ActivityEntry(now, action.summary, action.kind, ok = true).also { logActivity(it, id) } else null
        return ActionId(id.toString()) to entry
    }

    /** Wakes long-polling connectors after a transaction that enqueued work committed. */
    fun wake() = signals.signal()

    suspend fun findByKey(key: String): OutboxRow? = database.tx {
        ActionOutboxTable.selectAll().where { ActionOutboxTable.idempotencyKey eq key }.singleOrNull()?.toRow()
    }

    /** Queued, delivering or delivered actions whose key starts with [keyPrefix] (one run's), created since [since], newest first. */
    suspend fun recentByKeyPrefix(keyPrefix: String, since: Instant): List<OutboxRow> = database.tx {
        ActionOutboxTable.selectAll().where {
            (ActionOutboxTable.idempotencyKey like likePrefix(keyPrefix)) and (ActionOutboxTable.createdAt greaterEq since) and
                (ActionOutboxTable.status neq OutboxStatus.FAILED)
        }.orderBy(ActionOutboxTable.createdAt, SortOrder.DESC).limit(200).map { it.toRow() }
    }

    suspend fun get(id: ActionId): OutboxRow? {
        val uuid = parseHeldId(id.value) ?: return null
        return database.tx { ActionOutboxTable.selectAll().where { ActionOutboxTable.id eq uuid }.singleOrNull()?.toRow() }
    }

    /**
     * Leases up to [limit] due actions for [connector] for [LEASE_DURATION] and counts a delivery
     * attempt for each. Actions whose lease lapsed after the last allowed attempt fail for good.
     */
    suspend fun lease(connector: ConnectorId, limit: Int = 10): List<ConnectorAction> {
        val now = clock.now()
        val (leased, exhausted) = database.tx {
            val exhaustedRows = ActionOutboxTable.selectAll().where {
                (ActionOutboxTable.status eq OutboxStatus.LEASED) and
                    (ActionOutboxTable.leaseUntil lessEq now) and
                    (ActionOutboxTable.attempts greaterEq MAX_ATTEMPTS)
            }.forUpdate().map { it.toRow() }
            exhaustedRows.forEach { row -> fail(row, ActionOutcome.Failed("the connector never reported a result", retryable = false), now) }

            val due = ActionOutboxTable.selectAll().where {
                (ActionOutboxTable.connectorId eq connector.value) and (
                    ((ActionOutboxTable.status eq OutboxStatus.QUEUED) and (ActionOutboxTable.nextAttemptAt lessEq now)) or
                        ((ActionOutboxTable.status eq OutboxStatus.LEASED) and (ActionOutboxTable.leaseUntil lessEq now))
                    )
            }.orderBy(ActionOutboxTable.createdAt, SortOrder.ASC).limit(limit).forUpdate().toList()
            val actions = due.map { row ->
                val attempt = row[ActionOutboxTable.attempts] + 1
                ActionOutboxTable.update({ ActionOutboxTable.id eq row[ActionOutboxTable.id] }) {
                    it[status] = OutboxStatus.LEASED
                    it[leaseUntil] = now + LEASE_DURATION
                    it[attempts] = attempt
                }
                val action = row[ActionOutboxTable.action]
                ConnectorAction(ActionId(row[ActionOutboxTable.id].toString()), action, target = targetFor(connector, action), attempt = attempt, createdAt = row[ActionOutboxTable.createdAt])
            }
            actions to exhaustedRows
        }
        exhausted.forEach { announce(it.copy(status = OutboxStatus.FAILED, result = ActionOutcome.Failed("the connector never reported a result", false))) }
        return leased
    }

    /**
     * The connector's own id for the record [action] refers to (the original message of a reply, the
     * event to move, the document to read), from the source link this connector reported for it.
     */
    private fun JdbcTransaction.targetFor(connector: ConnectorId, action: ProposedAction): SourceId? {
        val entity = referencedEntity(action) ?: return null
        val uuid = parseHeldId(entity.value) ?: return null
        return SourceLinks.selectAll()
            .where { (SourceLinks.connectorId eq connector.value) and (SourceLinks.entityId eq uuid) and SourceLinks.deletedAt.isNull() }
            .orderBy(SourceLinks.latestVersion, SortOrder.DESC)
            .firstOrNull()?.get(SourceLinks.sourceId)?.let(::SourceId)
    }

    /** Long-poll: [lease], waiting up to [wait] (max [MAX_WAIT]) for work to appear. */
    suspend fun leaseOrWait(connector: ConnectorId, wait: Duration, limit: Int = 10): List<ConnectorAction> {
        val deadline = clock.now() + wait.coerceIn(Duration.ZERO, MAX_WAIT)
        while (true) {
            val actions = lease(connector, limit)
            if (actions.isNotEmpty()) return actions
            val remaining = deadline - clock.now()
            if (remaining <= Duration.ZERO) return emptyList()
            signals.await(minOf(remaining, RECHECK))
        }
    }

    /**
     * Records the connector's result. Unknown ids (or other connectors' actions) are an error; a
     * result for an action that is already final is a harmless [Completion.Duplicate].
     */
    suspend fun complete(connector: ConnectorId, id: ActionId, outcome: ActionOutcome): Either<GovernanceError, Completion> {
        val uuid = parseHeldId(id.value) ?: return GovernanceError.ActionNotFound(id.value).left()
        val now = clock.now()
        val completion = database.tx {
            val row = ActionOutboxTable.selectAll()
                .where { (ActionOutboxTable.id eq uuid) and (ActionOutboxTable.connectorId eq connector.value) }
                .forUpdate().singleOrNull()?.toRow()
                ?: return@tx null
            if (row.status == OutboxStatus.DONE || row.status == OutboxStatus.FAILED) return@tx Completion.Duplicate(row)
            when (val t = transitionFor(row.attempts, outcome, now)) {
                Transition.Finish -> {
                    ActionOutboxTable.update({ ActionOutboxTable.id eq uuid }) {
                        it[status] = OutboxStatus.DONE; it[result] = outcome; it[leaseUntil] = null
                    }
                    markHeld(row.heldId, HeldAction.Status.EXECUTED, null, now)
                    Completion.Finished(row.copy(status = OutboxStatus.DONE, result = outcome))
                }
                Transition.GiveUp -> Completion.Finished(fail(row, outcome, now))
                is Transition.Retry -> {
                    ActionOutboxTable.update({ ActionOutboxTable.id eq uuid }) {
                        it[status] = OutboxStatus.QUEUED; it[result] = outcome; it[leaseUntil] = null; it[nextAttemptAt] = t.at
                    }
                    Completion.WillRetry(row.copy(status = OutboxStatus.QUEUED, result = outcome), t.at)
                }
            }
        } ?: return GovernanceError.ActionNotFound(id.value).left()
        if (completion is Completion.Finished) announce(completion.row)
        return completion.right()
    }

    private fun JdbcTransaction.fail(row: OutboxRow, outcome: ActionOutcome, now: Instant): OutboxRow {
        val uuid = Uuid.parse(row.id.value)
        ActionOutboxTable.update({ ActionOutboxTable.id eq uuid }) {
            it[status] = OutboxStatus.FAILED; it[result] = outcome; it[leaseUntil] = null
        }
        ActivityTable.update({ ActivityTable.actionId eq uuid }) { it[ok] = false }
        val message = (outcome as? ActionOutcome.Failed)?.message
        markHeld(row.heldId, HeldAction.Status.FAILED, message, now)
        return row.copy(status = OutboxStatus.FAILED, result = outcome)
    }

    private fun JdbcTransaction.markHeld(heldId: HeldActionId?, to: HeldAction.Status, failure: String?, now: Instant) {
        if (heldId == null) return
        with(held) { transitionTx(heldId, setOf(HeldAction.Status.APPROVED), to, now, edited = null, failure = failure) }
    }

    /** After commit: tell the lead and the app. */
    private suspend fun announce(row: OutboxRow) {
        val outcome = row.result ?: return
        results.publish(ActionResultEvent(row.id, row.connectorId, row.heldId, row.action, outcome))
        row.heldId?.let { id -> held.get(id)?.let { events.publish(GovernanceEvent.HeldChanged(it)) } }
    }

    private fun JdbcTransaction.logActivity(entry: ActivityEntry, actionId: Uuid) {
        ActivityTable.insert {
            it[at] = entry.at
            it[summary] = entry.summary
            it[kind] = entry.kind.wire()
            it[ok] = entry.ok
            it[ActivityTable.actionId] = actionId
        }
    }

    private companion object { val RECHECK: Duration = 2.seconds }
}

private fun org.jetbrains.exposed.v1.core.ResultRow.toRow() = OutboxRow(
    id = ActionId(this[ActionOutboxTable.id].toString()),
    connectorId = ConnectorId(this[ActionOutboxTable.connectorId]),
    heldId = this[ActionOutboxTable.heldId]?.let { HeldActionId(it.toString()) },
    action = this[ActionOutboxTable.action],
    idempotencyKey = this[ActionOutboxTable.idempotencyKey],
    status = this[ActionOutboxTable.status],
    attempts = this[ActionOutboxTable.attempts],
    result = this[ActionOutboxTable.result],
)
