package net.robog.clarity.backend.sessions

import arrow.core.Either
import arrow.core.left
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

private val LIVE_STATES = listOf(Session.State.ACTIVE, Session.State.BUSY, Session.State.SNOOZED).map { it.wire() }

private fun parseId(raw: String): Uuid? = runCatching { Uuid.parse(raw) }.getOrNull()

/** Persistence of sessions (`agent_session`). Every change goes through [mutate] under a row lock. */
@Inject
@SingleIn(AppScope::class)
class SessionStore(private val database: Database) {

    suspend fun get(id: SessionId): SessionRecord? {
        val uuid = parseId(id.value) ?: return null
        return database.tx { AgentSessions.selectAll().where { AgentSessions.id eq uuid }.singleOrNull()?.toRecord() }
    }

    /** The one live session (active, busy or snoozed), if any. */
    suspend fun live(): SessionRecord? = database.tx {
        AgentSessions.selectAll().where { AgentSessions.state inList LIVE_STATES }.limit(1).singleOrNull()?.toRecord()
    }

    suspend fun recent(limit: Int = 20): List<SessionRecord> = database.tx {
        AgentSessions.selectAll().orderBy(AgentSessions.startedAt, SortOrder.DESC).limit(limit).map { it.toRecord() }
    }

    /** Snoozed calls whose time has come. */
    suspend fun snoozedDue(now: Instant): List<SessionRecord> = database.tx {
        AgentSessions.selectAll().where { AgentSessions.state eq Session.State.SNOOZED.wire() }.map { it.toRecord() }.filter { it.recallDue(now) }
    }

    /**
     * Starts [record]; a live session is ended first (there is one at a time). Returns the session that
     * was replaced, if any, as it was before ending.
     */
    suspend fun begin(record: SessionRecord, now: Instant): Pair<SessionRecord, SessionRecord?> = database.tx {
        val replaced = AgentSessions.selectAll().where { AgentSessions.state inList LIVE_STATES }.forUpdate().singleOrNull()?.toRecord()
        if (replaced != null) {
            AgentSessions.update({ AgentSessions.id eq Uuid.parse(replaced.id.value) }) {
                it[state] = Session.State.ENDED.wire()
                it[endedAt] = now
                it[snoozedUntil] = null
                it[version] = replaced.version + 1
            }
        }
        AgentSessions.insert {
            it[id] = Uuid.parse(record.id.value)
            it[kind] = record.kind.wire()
            it[state] = record.state.wire()
            it[startedAt] = record.session.startedAt
            it[stage] = record.session.stage?.wire()
            it[question] = record.session.question
            it[questionCount] = record.session.questionCount
            it[heldActionId] = record.session.heldActionId?.value
            it[version] = record.version
            it[data] = record.data
        }
        record to replaced
    }

    /**
     * Applies [change] to the session under a row lock and stores the result. Returns the session
     * before and after, or the refusal (nothing is written then).
     */
    suspend fun mutate(
        id: SessionId,
        change: (SessionRecord) -> Either<SessionError, SessionRecord>,
    ): Either<SessionError, Pair<SessionRecord, SessionRecord>> {
        val uuid = parseId(id.value) ?: return SessionError.NotFound(id.value).left()
        return database.tx {
            val before = AgentSessions.selectAll().where { AgentSessions.id eq uuid }.forUpdate().singleOrNull()?.toRecord()
                ?: return@tx SessionError.NotFound(id.value).left()
            change(before).map { after ->
                AgentSessions.update({ AgentSessions.id eq uuid }) {
                    it[state] = after.state.wire()
                    it[endedAt] = after.session.endedAt
                    it[stage] = after.session.stage?.wire()
                    it[question] = after.session.question
                    it[questionCount] = after.session.questionCount
                    it[capturedLine] = after.session.capturedLine
                    it[heldActionId] = after.session.heldActionId?.value
                    it[amendmentId] = after.session.amendmentId?.value
                    it[snoozedUntil] = after.snoozedUntil
                    it[finalizing] = after.finalizing
                    it[version] = after.version
                    it[data] = after.data
                }
                before to after
            }
        }
    }
}

private fun ResultRow.toRecord() = SessionRecord(
    session = Session(
        id = SessionId(this[AgentSessions.id].toString()),
        kind = parseWire<SessionKind>(this[AgentSessions.kind]) ?: SessionKind.AMEND,
        state = parseWire<Session.State>(this[AgentSessions.state]) ?: Session.State.ENDED,
        startedAt = this[AgentSessions.startedAt],
        endedAt = this[AgentSessions.endedAt],
        stage = this[AgentSessions.stage]?.let { parseWire<CallStage>(it) },
        question = this[AgentSessions.question],
        questionCount = this[AgentSessions.questionCount],
        capturedLine = this[AgentSessions.capturedLine],
        heldActionId = this[AgentSessions.heldActionId]?.let(::HeldActionId),
        amendmentId = this[AgentSessions.amendmentId]?.let(::AmendmentId),
    ),
    snoozedUntil = this[AgentSessions.snoozedUntil],
    finalizing = this[AgentSessions.finalizing],
    version = this[AgentSessions.version],
    data = this[AgentSessions.data],
)

/** What the morning call decided for one local day. */
data class DayPlan(
    val date: LocalDate,
    val priorities: List<Chip> = emptyList(),
    val plan: List<IfThen> = emptyList(),
    val parked: List<String> = emptyList(),
    val called: Boolean = false,
    val sessionId: String? = null,
)

/** `day_plan`: one row per local day, written by the session tools, read by Today and the prep job. */
@Inject
@SingleIn(AppScope::class)
class DayPlanStore(private val database: Database, private val clock: Clock) {
    suspend fun get(date: LocalDate): DayPlan? = database.tx {
        DayPlans.selectAll().where { DayPlans.date eq date }.singleOrNull()?.toPlan()
    }

    /** Reads (or starts) the plan of [date], applies [change] and stores it. */
    suspend fun update(date: LocalDate, change: (DayPlan) -> DayPlan): DayPlan = database.tx {
        val current = DayPlans.selectAll().where { DayPlans.date eq date }.forUpdate().singleOrNull()?.toPlan() ?: DayPlan(date)
        val next = change(current)
        DayPlans.upsert(DayPlans.date) {
            it[DayPlans.date] = date
            it[priorities] = next.priorities
            it[plan] = next.plan
            it[parked] = next.parked
            it[called] = next.called
            it[sessionId] = next.sessionId
            it[updatedAt] = clock.now()
        }
        next
    }

    private fun ResultRow.toPlan() = DayPlan(
        this[DayPlans.date], this[DayPlans.priorities], this[DayPlans.plan], this[DayPlans.parked], this[DayPlans.called], this[DayPlans.sessionId],
    )
}
