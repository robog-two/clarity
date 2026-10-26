package net.robog.clarity.backend.governance

import arrow.core.Either
import arrow.core.raise.context.either
import arrow.core.raise.context.ensure
import arrow.core.raise.context.ensureNotNull
import arrow.core.raise.context.raise
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.persistence.wire
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.AutonomyChange
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposeAmendment
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** A stored version without its document, for history lists. */
data class VersionInfo(val version: Int, val createdAt: Instant)

/** Pure: structural checks for a constitution coming from the values interview. */
fun validateConstitution(c: Constitution): Either<GovernanceError, Constitution> = either {
    val ids = c.lines.map { it.id }
    ensure(ids.size == ids.toSet().size) { GovernanceError.InvalidConstitution("line ids must be unique") }
    ensure(c.lines.none { it.text.isBlank() }) { GovernanceError.InvalidConstitution("lines need text") }
    c
}

/**
 * Pure: the constitution with [amendment] applied. A null `lineId` adds the proposed line; otherwise
 * the proposed line replaces that line and keeps its id. An autonomy change sets that kind's tier.
 */
fun applyAmendment(constitution: Constitution, amendment: Amendment): Either<GovernanceError, Constitution> = either {
    amendment.autonomy?.let { change -> autonomyProblem(change, constitution)?.let { raise(GovernanceError.InvalidConstitution(it)) } }
    val withLines = applyLine(constitution, amendment).fold({ raise(it) }, { it })
    amendment.autonomy?.let { withLines.copy(autonomy = withLines.autonomy + (it.kind to it.tier)) } ?: withLines
}

/**
 * Pure: why [change] cannot be made by talking, or null. Vault kinds always need the phone, and a
 * "never" is a hard line (changed only in the constitution screen).
 */
fun autonomyProblem(change: AutonomyChange, constitution: Constitution): String? = when {
    change.kind in Constitution.ALWAYS_NEVER_ALONE && change.tier != AutonomyTier.NEVER_ALONE && change.tier != AutonomyTier.NEVER ->
        "\"${change.kind.label()}\" always needs the person on their phone; that cannot be loosened"
    constitution.autonomy[change.kind] == AutonomyTier.NEVER && change.tier != AutonomyTier.NEVER ->
        "\"${change.kind.label()}\" is set to never, a hard line that is not changed by talking"
    else -> null
}

private fun applyLine(constitution: Constitution, amendment: Amendment): Either<GovernanceError, Constitution> = either {
    val target = amendment.lineId
    if (target == null) {
        ensure(constitution.lines.none { it.id == amendment.proposed.id }) {
            GovernanceError.InvalidConstitution("a line '${amendment.proposed.id}' already exists")
        }
        constitution.copy(lines = constitution.lines + amendment.proposed)
    } else {
        ensure(constitution.lines.any { it.id == target }) { GovernanceError.LineNotFound(target.value) }
        constitution.copy(lines = constitution.lines.map { if (it.id == target) amendment.proposed.copy(id = target) else it })
    }
}

/** Constitution versions and amendments (`constitution_version`, `amendment`). */
@Inject
@SingleIn(AppScope::class)
class ConstitutionStore(
    private val database: Database,
    private val clock: Clock,
    private val held: HeldActionStore,
    private val events: GovernanceEvents,
) {
    suspend fun current(): Constitution? = database.tx { latest() }

    suspend fun at(version: Int): Constitution? = database.tx {
        ConstitutionVersions.selectAll().where { ConstitutionVersions.version eq version }.singleOrNull()?.get(ConstitutionVersions.document)
    }

    suspend fun history(): List<VersionInfo> = database.tx {
        ConstitutionVersions.selectAll().orderBy(ConstitutionVersions.version, SortOrder.DESC)
            .map { VersionInfo(it[ConstitutionVersions.version], it[ConstitutionVersions.createdAt]) }
    }

    /** Stores [initial] as version 1 (the values interview's result); only once. */
    suspend fun create(initial: Constitution): Either<GovernanceError, Constitution> = either {
        validateConstitution(initial).let { it.fold({ e -> raise(e) }, { v -> v }) }
        val stored = database.tx {
            if (latest() != null) null else write(initial.copy(version = 1, updatedAt = clock.now()))
        }
        ensureNotNull(stored) { GovernanceError.AlreadyInitialized }
        events.publish(GovernanceEvent.ConstitutionChanged(stored.version))
        stored
    }

    /** Stores `transform(current)` as the next version. */
    suspend fun saveVersion(transform: (Constitution) -> Constitution): Either<GovernanceError, Constitution> = either {
        val stored = database.tx { saveVersionTx(transform) }
        ensureNotNull(stored) { GovernanceError.NoConstitution() }
        events.publish(GovernanceEvent.ConstitutionChanged(stored.version))
        stored
    }

    suspend fun propose(request: ProposeAmendment): Either<GovernanceError, Amendment> = either {
        val current = ensureNotNull(current()) { GovernanceError.NoConstitution() }
        val old = request.lineId?.let { id ->
            ensureNotNull(current.lines.firstOrNull { it.id == id }) { GovernanceError.LineNotFound(id.value) }
        }
        request.autonomy?.let { change -> autonomyProblem(change, current)?.let { raise(GovernanceError.InvalidConstitution(it)) } }
        val id = Uuid.random()
        database.tx {
            Amendments.insert {
                it[Amendments.id] = id
                it[lineId] = request.lineId?.value
                it[oldText] = old?.text
                it[proposed] = request.proposed
                it[reason] = request.reason
                it[fromHeld] = request.fromHeld?.let { h -> parseHeldId(h.value) }
                it[status] = Amendment.Status.PROPOSED.wire()
                it[createdAt] = clock.now()
                it[autonomy] = request.autonomy
            }
        }
        Amendment(
            AmendmentId(id.toString()), request.lineId, old?.text, request.proposed, request.reason,
            request.fromHeld, Amendment.Status.PROPOSED, request.autonomy,
        )
    }

    suspend fun amendments(status: Amendment.Status? = null): List<Amendment> = database.tx {
        val query = Amendments.selectAll()
        status?.let { s -> query.andWhere { Amendments.status eq s.wire() } }
        query.orderBy(Amendments.createdAt, SortOrder.DESC).map { it.toAmendment() }
    }

    suspend fun amendment(id: AmendmentId): Amendment? {
        val uuid = parseHeldId(id.value) ?: return null
        return database.tx { Amendments.selectAll().where { Amendments.id eq uuid }.singleOrNull()?.toAmendment() }
    }

    /**
     * The person saves an amendment: the new line is applied and the version bumps. If it came from
     * a held action, that action becomes `rule_updated`.
     */
    suspend fun saveAmendment(id: AmendmentId): Either<GovernanceError, Constitution> = either {
        val uuid = ensureNotNull(parseHeldId(id.value)) { GovernanceError.AmendmentNotFound(id.value) }
        var heldChanged: HeldActionId? = null
        val saved = database.tx {
            val row = Amendments.selectAll().where { Amendments.id eq uuid }.forUpdate().singleOrNull()?.toAmendment()
                ?: return@tx Either.Left(GovernanceError.AmendmentNotFound(id.value))
            if (row.status != Amendment.Status.PROPOSED) return@tx Either.Left(GovernanceError.AmendmentNotProposed(id.value))
            val next = latest()?.let { applyAmendment(it, row) } ?: Either.Left(GovernanceError.NoConstitution())
            next.map { amended ->
                val stored = write(amended.copy(version = amended.version + 1, updatedAt = clock.now()))
                Amendments.update({ Amendments.id eq uuid }) { it[status] = Amendment.Status.SAVED.wire() }
                row.fromHeld?.let { h ->
                    val changed = with(held) { transitionTx(h, PENDING_STATUSES, HeldAction.Status.RULE_UPDATED, clock.now(), edited = null) }
                    if (changed) heldChanged = h
                }
                stored
            }
        }.let { it.fold({ e -> raise(e) }, { v -> v }) }
        events.publish(GovernanceEvent.ConstitutionChanged(saved.version))
        heldChanged?.let { h -> held.get(h)?.let { events.publish(GovernanceEvent.HeldChanged(it)) } }
        saved
    }

    suspend fun discardAmendment(id: AmendmentId): Either<GovernanceError, Amendment> = either {
        val uuid = ensureNotNull(parseHeldId(id.value)) { GovernanceError.AmendmentNotFound(id.value) }
        val updated = database.tx {
            Amendments.update({ (Amendments.id eq uuid) and (Amendments.status eq Amendment.Status.PROPOSED.wire()) }) {
                it[status] = Amendment.Status.DISCARDED.wire()
            }
        }
        val row = ensureNotNull(amendment(id)) { GovernanceError.AmendmentNotFound(id.value) }
        ensure(updated > 0) { GovernanceError.AmendmentNotProposed(id.value) }
        row
    }

    private fun JdbcTransaction.latest(): Constitution? =
        ConstitutionVersions.selectAll().orderBy(ConstitutionVersions.version, SortOrder.DESC).limit(1)
            .singleOrNull()?.get(ConstitutionVersions.document)

    private fun JdbcTransaction.write(constitution: Constitution): Constitution {
        ConstitutionVersions.insert {
            it[version] = constitution.version
            it[document] = constitution
            it[createdAt] = clock.now()
        }
        return constitution
    }

    private fun JdbcTransaction.saveVersionTx(transform: (Constitution) -> Constitution): Constitution? {
        val current = ConstitutionVersions.selectAll().orderBy(ConstitutionVersions.version, SortOrder.DESC).limit(1)
            .forUpdate().singleOrNull()?.get(ConstitutionVersions.document) ?: return null
        return write(transform(current).copy(version = current.version + 1, updatedAt = clock.now()))
    }
}

private fun ResultRow.toAmendment() = Amendment(
    id = AmendmentId(this[Amendments.id].toString()),
    lineId = this[Amendments.lineId]?.let { net.robog.clarity.shared.governance.LineId(it) },
    old = this[Amendments.oldText],
    proposed = this[Amendments.proposed],
    reason = this[Amendments.reason],
    fromHeld = this[Amendments.fromHeld]?.let { HeldActionId(it.toString()) },
    status = parseWire<Amendment.Status>(this[Amendments.status]) ?: Amendment.Status.PROPOSED,
    autonomy = this[Amendments.autonomy],
)
