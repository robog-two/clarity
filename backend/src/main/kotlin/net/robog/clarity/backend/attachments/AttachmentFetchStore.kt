package net.robog.clarity.backend.attachments

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import net.robog.clarity.backend.connectors.grantedNouns
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchRequest
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Mirrors `attachment_fetch` (V49): the fetch queue, and the cache of what was fetched. */
object AttachmentFetches : Table("attachment_fetch") {
    val id = uuid("id")
    val connectorId = text("connector_id")
    val entityId = uuid("entity_id")
    val sourceId = text("source_id")
    val sourceVersion = text("source_version")
    val part = text("part")
    val status = text("status")
    val requestedAt = timestamp("requested_at")
    val leaseUntil = timestamp("lease_until").nullable()
    val attempts = integer("attempts")
    val doneAt = timestamp("done_at").nullable()
    val errorCode = text("error_code").nullable()
    val errorMessage = text("error_message").nullable()
    val filename = text("filename").nullable()
    val mimeType = text("mime_type").nullable()
    val size = long("size").nullable()
    val bytes = binary("bytes").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** One part of one message at one version of its source record: the cache key. */
data class FetchKey(val connectorId: ConnectorId, val entityId: Uuid, val sourceId: String, val sourceVersion: String, val part: String)

class StoredFile(val bytes: ByteArray, val filename: String?, val mimeType: String)

/** What asking for a [FetchKey] gave: the cached file, or a request to wait for. */
sealed interface Ticket {
    class Cached(val file: StoredFile) : Ticket
    data class Waiting(val id: Uuid) : Ticket
}

/** Where a request stands. */
sealed interface Answer {
    data object Pending : Answer
    class Done(val file: StoredFile) : Answer
    data class Failed(val code: FetchFailure.Code, val message: String) : Answer
}

/** Why the connector's answer was not taken. */
enum class AnswerRefused { UNKNOWN_REQUEST }

/** How long a connector holds a request before it counts as lost. */
val FETCH_LEASE: Duration = 150.seconds

/** Deliveries tried before a request fails for good. */
const val FETCH_MAX_ATTEMPTS = 3

/** A request nobody answered is dropped after this. */
val STALE_REQUEST: Duration = 10.minutes

/**
 * The fetch queue and cache (`attachment_fetch`). One row per (message, part, source version): the row is the
 * request the connector polls for, then the cached bytes until they expire. Everything a connector can see or answer
 * is limited to rows addressed to it, and only while the message noun is granted to it.
 */
@Inject
@SingleIn(AppScope::class)
class AttachmentFetchStore(private val database: Database) {

    /** The cached file for [key] if fresh, else a request that is (or now becomes) pending. */
    suspend fun request(key: FetchKey, now: Instant, ttl: Duration): Ticket = database.tx {
        val existing = rowFor(key)
        when (planFor(existing?.let { RowState(FetchStatus.fromWire(it[AttachmentFetches.status]), it[AttachmentFetches.doneAt], it[AttachmentFetches.leaseUntil]) }, now, ttl)) {
            Plan.SERVE -> Ticket.Cached(existing!!.toFile())
            Plan.WAIT -> Ticket.Waiting(existing!![AttachmentFetches.id])
            Plan.REQUEUE -> {
                val id = existing!![AttachmentFetches.id]
                AttachmentFetches.update({ AttachmentFetches.id eq id }) {
                    it[connectorId] = key.connectorId.value
                    it[sourceId] = key.sourceId
                    it[status] = FetchStatus.PENDING.wire
                    it[requestedAt] = now
                    it[leaseUntil] = null
                    // A spent download starts over; a lost or failed one keeps its count of deliveries.
                    if (existing[AttachmentFetches.status] == FetchStatus.DONE.wire) it[attempts] = 0
                    it[doneAt] = null
                    it[errorCode] = null
                    it[errorMessage] = null
                    it[filename] = null
                    it[mimeType] = null
                    it[size] = null
                    it[bytes] = null
                }
                Ticket.Waiting(id)
            }
            Plan.ENQUEUE -> {
                val id = Uuid.random()
                AttachmentFetches.insertIgnore {
                    it[AttachmentFetches.id] = id
                    it[connectorId] = key.connectorId.value
                    it[entityId] = key.entityId
                    it[sourceId] = key.sourceId
                    it[sourceVersion] = key.sourceVersion
                    it[part] = key.part
                    it[status] = FetchStatus.PENDING.wire
                    it[requestedAt] = now
                    it[attempts] = 0
                }
                // A concurrent request for the same key may have inserted first: wait for its row.
                Ticket.Waiting(rowFor(key)!![AttachmentFetches.id])
            }
        }
    }

    /** Where request [id] stands; failed once it is gone (swept). */
    suspend fun answer(id: Uuid): Answer = database.tx {
        val row = AttachmentFetches.selectAll().where { AttachmentFetches.id eq id }.singleOrNull()
            ?: return@tx Answer.Failed(FetchFailure.Code.FAILED, "the request expired")
        when (FetchStatus.fromWire(row[AttachmentFetches.status])) {
            FetchStatus.DONE -> Answer.Done(row.toFile())
            FetchStatus.FAILED -> Answer.Failed(
                FetchFailure.Code.entries.firstOrNull { it.name.equals(row[AttachmentFetches.errorCode], ignoreCase = true) } ?: FetchFailure.Code.FAILED,
                row[AttachmentFetches.errorMessage].orEmpty(),
            )
            else -> Answer.Pending
        }
    }

    /**
     * Leases up to [limit] requests addressed to [connector] (and only while it is granted messages): pending ones,
     * and ones whose lease lapsed. A request whose last delivery lapsed unanswered fails for good.
     */
    suspend fun lease(connector: ConnectorId, now: Instant, maxBytes: Long, limit: Int = 5): List<FetchRequest> = database.tx {
        if (Noun.MESSAGE !in grantedNouns(connector)) return@tx emptyList()
        val lapsed = (AttachmentFetches.status eq FetchStatus.LEASED.wire) and (AttachmentFetches.leaseUntil lessEq now)
        AttachmentFetches.update({ lapsed and (AttachmentFetches.attempts greaterEq FETCH_MAX_ATTEMPTS) }) {
            it[status] = FetchStatus.FAILED.wire
            it[doneAt] = now
            it[errorCode] = FetchFailure.Code.FAILED.name
            it[errorMessage] = "the connector never answered"
        }
        val due = AttachmentFetches.selectAll().where {
            (AttachmentFetches.connectorId eq connector.value) and
                ((AttachmentFetches.status eq FetchStatus.PENDING.wire) or lapsed)
        }.orderBy(AttachmentFetches.requestedAt, SortOrder.ASC).limit(limit).forUpdate().toList()
        due.map { row ->
            val id = row[AttachmentFetches.id]
            AttachmentFetches.update({ AttachmentFetches.id eq id }) {
                it[status] = FetchStatus.LEASED.wire
                it[leaseUntil] = now + FETCH_LEASE
                it[attempts] = row[AttachmentFetches.attempts] + 1
            }
            FetchRequest(id.toString(), SourceId(row[AttachmentFetches.sourceId]), row[AttachmentFetches.part], maxBytes)
        }
    }

    /**
     * Takes [bytes] for request [id] from [connector]; refused for a request addressed to another connector (or none).
     * More than [maxBytes] makes the request fail as too large instead. A second answer for a finished request is ignored.
     */
    suspend fun complete(
        connector: ConnectorId,
        id: Uuid,
        bytes: ByteArray,
        filename: String?,
        mimeType: String,
        now: Instant,
        maxBytes: Long,
        cacheMaxBytes: Long = Long.MAX_VALUE,
    ): AnswerRefused? = database.tx {
        val row = ownRow(connector, id) ?: return@tx AnswerRefused.UNKNOWN_REQUEST
        if (isFinished(row)) return@tx null
        if (sizeVerdict(bytes.size.toLong(), maxBytes) == SizeVerdict.TOO_LARGE) {
            markFailed(id, FetchFailure.Code.TOO_LARGE, "the file is larger than the limit of ${maxBytes / 1024 / 1024} MB", now)
        } else {
            AttachmentFetches.update({ AttachmentFetches.id eq id }) {
                it[status] = FetchStatus.DONE.wire
                it[doneAt] = now
                it[leaseUntil] = null
                it[AttachmentFetches.filename] = filename
                it[AttachmentFetches.mimeType] = mimeType
                it[size] = bytes.size.toLong()
                it[AttachmentFetches.bytes] = bytes
            }
            // Make room now: the cache never goes over its cap, even between sweeps.
            val others = AttachmentFetches.select(AttachmentFetches.id, AttachmentFetches.size, AttachmentFetches.doneAt)
                .where { (AttachmentFetches.status eq FetchStatus.DONE.wire) and (AttachmentFetches.id neq id) }
                .map { CacheEntry(it[AttachmentFetches.id].toString(), it[AttachmentFetches.size] ?: 0L, it[AttachmentFetches.doneAt] ?: now) }
            val drop = evictionsOnComplete(others, bytes.size.toLong(), cacheMaxBytes, now).map { Uuid.parse(it) }
            if (drop.isNotEmpty()) AttachmentFetches.deleteWhere { AttachmentFetches.id inList drop }
        }
        null
    }

    /** [connector] could not serve request [id]; same refusals as [complete]. */
    suspend fun fail(connector: ConnectorId, id: Uuid, failure: FetchFailure, now: Instant): AnswerRefused? = database.tx {
        val row = ownRow(connector, id) ?: return@tx AnswerRefused.UNKNOWN_REQUEST
        if (!isFinished(row)) markFailed(id, failure.code, failure.message.take(500), now)
        null
    }

    /**
     * Trims the cache: drops bytes older than [ttl], failures and unanswered requests older than [STALE_REQUEST],
     * then the oldest files until what is left fits [maxBytes]. Returns the rows removed.
     */
    suspend fun sweep(now: Instant, ttl: Duration, maxBytes: Long): Int = database.tx {
        var removed = AttachmentFetches.deleteWhere {
            (status eq FetchStatus.DONE.wire) and (doneAt less (now - ttl))
        }
        removed += AttachmentFetches.deleteWhere {
            (status eq FetchStatus.FAILED.wire) and (doneAt less (now - STALE_REQUEST))
        }
        removed += AttachmentFetches.deleteWhere {
            (status eq FetchStatus.PENDING.wire) and (requestedAt less (now - STALE_REQUEST))
        }
        // A leased request is still waiting for its upload until its lease has been over for a while.
        removed += AttachmentFetches.deleteWhere {
            (status eq FetchStatus.LEASED.wire) and (leaseUntil less (now - STALE_REQUEST))
        }
        val total = AttachmentFetches.select(AttachmentFetches.size.sum()).where { AttachmentFetches.status eq FetchStatus.DONE.wire }
            .singleOrNull()?.get(AttachmentFetches.size.sum()) ?: 0L
        if (total > maxBytes) {
            val entries = AttachmentFetches.select(AttachmentFetches.id, AttachmentFetches.size, AttachmentFetches.doneAt)
                .where { AttachmentFetches.status eq FetchStatus.DONE.wire }
                .map { CacheEntry(it[AttachmentFetches.id].toString(), it[AttachmentFetches.size] ?: 0L, it[AttachmentFetches.doneAt]!!) }
            val drop = evictionsOnComplete(entries, 0, maxBytes, now).map { Uuid.parse(it) }
            if (drop.isNotEmpty()) removed += AttachmentFetches.deleteWhere { id inList drop }
        }
        removed
    }

    /** Total bytes cached (tests, diagnostics). */
    suspend fun cachedBytes(): Long = database.tx {
        AttachmentFetches.select(AttachmentFetches.size.sum()).where { AttachmentFetches.status eq FetchStatus.DONE.wire }
            .singleOrNull()?.get(AttachmentFetches.size.sum()) ?: 0L
    }

    private fun JdbcTransaction.rowFor(key: FetchKey) = AttachmentFetches.selectAll().where {
        (AttachmentFetches.entityId eq key.entityId) and (AttachmentFetches.part eq key.part) and (AttachmentFetches.sourceVersion eq key.sourceVersion)
    }.forUpdate().singleOrNull()

    /** The request [id] if it is addressed to [connector], which must still be granted messages (a revoked grant stops answers too). */
    private fun JdbcTransaction.ownRow(connector: ConnectorId, id: Uuid) =
        if (Noun.MESSAGE !in grantedNouns(connector)) null
        else AttachmentFetches.selectAll()
            .where { (AttachmentFetches.id eq id) and (AttachmentFetches.connectorId eq connector.value) }.forUpdate().singleOrNull()

    private fun isFinished(row: org.jetbrains.exposed.v1.core.ResultRow): Boolean =
        FetchStatus.fromWire(row[AttachmentFetches.status]).let { it == FetchStatus.DONE || it == FetchStatus.FAILED }

    private fun JdbcTransaction.markFailed(id: Uuid, code: FetchFailure.Code, message: String, now: Instant) {
        AttachmentFetches.update({ AttachmentFetches.id eq id }) {
            it[status] = FetchStatus.FAILED.wire
            it[doneAt] = now
            it[leaseUntil] = null
            it[errorCode] = code.name
            it[errorMessage] = message
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toFile() =
        StoredFile(this[AttachmentFetches.bytes] ?: ByteArray(0), this[AttachmentFetches.filename], this[AttachmentFetches.mimeType] ?: "application/octet-stream")
}
