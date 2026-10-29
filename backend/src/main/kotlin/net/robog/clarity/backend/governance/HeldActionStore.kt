package net.robog.clarity.backend.governance

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.RuleDraft
import net.robog.clarity.shared.governance.Urgency
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Statuses in which the person can still decide. */
val PENDING_STATUSES: Set<HeldAction.Status> = setOf(HeldAction.Status.HELD, HeldAction.Status.BLOCKED)

fun parseHeldId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

/** Persistence of held actions (`held_action`). Idempotent by the proposing action's key. */
@Inject
@SingleIn(AppScope::class)
class HeldActionStore(private val database: Database, private val clock: Clock) {

    /** Inserts, or returns the existing row for the same idempotency key. */
    suspend fun insert(
        action: ProposedAction,
        verdict: GuardVerdict,
        agentView: String,
        context: List<ContextItem>,
        urgency: Urgency,
        status: HeldAction.Status,
        failure: String? = null,
    ): HeldAction = database.tx {
        val id = Uuid.random()
        val now = clock.now()
        HeldActions.insertIgnore {
            it[HeldActions.id] = id
            it[idempotencyKey] = action.idempotencyKey
            it[createdAt] = now
            it[HeldActions.action] = action
            it[HeldActions.verdict] = verdict
            it[HeldActions.agentView] = agentView
            it[HeldActions.context] = context
            it[HeldActions.urgency] = urgency.wire()
            it[HeldActions.status] = status.wire()
            it[HeldActions.failure] = failure
        }
        HeldActions.selectAll().where { HeldActions.idempotencyKey eq action.idempotencyKey }.single().toHeld()
    }

    /** Stores the drafted "approve automatically" line; only while the action is still waiting. */
    suspend fun setRuleDraft(id: HeldActionId, draft: RuleDraft): Boolean {
        val uuid = parseHeldId(id.value) ?: return false
        return database.tx {
            HeldActions.update({ (HeldActions.id eq uuid) and (HeldActions.status inList PENDING_STATUSES.map { it.wire() }) }) { it[ruleDraft] = draft } > 0
        }
    }

    suspend fun findByKey(key: String): HeldAction? = database.tx {
        HeldActions.selectAll().where { HeldActions.idempotencyKey eq key }.singleOrNull()?.toHeld()
    }

    /**
     * Held actions in [statuses] whose key starts with [keyPrefix] (one run's or thread's), created
     * since [since], newest first, each with the action as the agent first proposed it (before the
     * person edited it), when it was edited.
     */
    suspend fun recentByKeyPrefix(keyPrefix: String, since: Instant, statuses: Set<HeldAction.Status>): List<Pair<HeldAction, ProposedAction?>> =
        database.tx {
            HeldActions.selectAll().where {
                (HeldActions.idempotencyKey like likePrefix(keyPrefix)) and (HeldActions.createdAt greaterEq since) and
                    (HeldActions.status inList statuses.map { it.wire() })
            }.orderBy(HeldActions.createdAt, SortOrder.DESC).limit(200).map { it.toHeld() to it[HeldActions.originalAction] }
        }

    suspend fun get(id: HeldActionId): HeldAction? {
        val uuid = parseHeldId(id.value) ?: return null
        return database.tx { HeldActions.selectAll().where { HeldActions.id eq uuid }.singleOrNull()?.toHeld() }
    }

    suspend fun list(statuses: Set<HeldAction.Status>? = null, limit: Int = 100): List<HeldAction> = database.tx {
        val query = HeldActions.selectAll()
        statuses?.let { s -> query.andWhere { HeldActions.status inList s.map { it.wire() } } }
        query.orderBy(HeldActions.createdAt, SortOrder.DESC).limit(limit.coerceIn(1, 500)).map { it.toHeld() }
    }

    suspend fun count(statuses: Set<HeldAction.Status>): Long = database.tx {
        HeldActions.selectAll().where { HeldActions.status inList statuses.map { it.wire() } }.count()
    }

    /** Changes status when the row is currently in one of [from]; false if it was not (lost a race, or unknown). */
    suspend fun transition(id: HeldActionId, from: Set<HeldAction.Status>, to: HeldAction.Status, failure: String? = null): Boolean =
        database.tx { transitionTx(id, from, to, clock.now(), edited = null, failure = failure) }

    /** Same as [transition] inside the caller's transaction; [edited] replaces the action (the original is kept). */
    fun JdbcTransaction.transitionTx(
        id: HeldActionId,
        from: Set<HeldAction.Status>,
        to: HeldAction.Status,
        now: Instant,
        edited: ProposedAction?,
        failure: String? = null,
    ): Boolean {
        val uuid = parseHeldId(id.value) ?: return false
        val updated = HeldActions.update({ (HeldActions.id eq uuid) and (HeldActions.status inList from.map { it.wire() }) }) {
            it[status] = to.wire()
            it[decidedAt] = now
            if (failure != null) it[HeldActions.failure] = failure
            if (edited != null) {
                it[originalAction] = HeldActions.action
                it[action] = edited
            }
        }
        return updated > 0
    }
}

internal fun ResultRow.toHeld(): HeldAction = HeldAction(
    id = HeldActionId(this[HeldActions.id].toString()),
    createdAt = this[HeldActions.createdAt],
    action = this[HeldActions.action],
    verdict = this[HeldActions.verdict],
    agentView = this[HeldActions.agentView],
    context = this[HeldActions.context],
    urgency = parseWire<Urgency>(this[HeldActions.urgency]) ?: Urgency.NORMAL,
    status = parseWire<HeldAction.Status>(this[HeldActions.status]) ?: HeldAction.Status.HELD,
    decidedAt = this[HeldActions.decidedAt],
    ruleDraft = this[HeldActions.ruleDraft],
)
