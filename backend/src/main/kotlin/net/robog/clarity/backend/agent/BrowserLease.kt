package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert

private val log = KotlinLogging.logger {}

/** `browser_lease` (V22): the single row saying which run holds the browser. */
object BrowserLeaseTable : Table("browser_lease") {
    val id = integer("id")
    val holderRun = text("holder_run").nullable()
    val acquiredAt = timestamp("acquired_at").nullable()
    val heartbeatAt = timestamp("heartbeat_at").nullable()
    val lastActivityAt = timestamp("last_activity_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `browser_lease_queue` (V22): runs waiting for the browser, first come first served. */
object BrowserLeaseQueue : Table("browser_lease_queue") {
    val runId = text("run_id")
    val enqueuedAt = timestamp("enqueued_at")
    override val primaryKey = PrimaryKey(runId)
}

/** `browser_resume` (V22): where a run left the browser. */
object BrowserResumes : Table("browser_resume") {
    val runId = text("run_id")
    val taskKey = text("task_key").nullable()
    val url = text("url").nullable()
    val title = text("title").nullable()
    val whereLine = text("where_line").nullable()
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(runId)
}

/** One try for the browser lease. */
sealed interface LeaseTry {
    /** The run holds the lease; [fresh] when it has just got it (the browser must be reset for it). */
    data class Granted(val fresh: Boolean) : LeaseTry

    /** Another run holds it, or is ahead in the queue. [ahead] counts the runs before this one. */
    data class Waiting(val holder: String?, val ahead: Int) : LeaseTry
}

/** Where a run left the browser, for a later run of the same task. */
data class BrowserResume(val runId: String, val taskKey: String?, val url: String?, val title: String?, val where: String?, val at: Instant)

/**
 * The browser lease: one run uses the shared browser at a time, the rest queue (FIFO). A holder
 * loses it when it has made no browser call for [idle] (waiting for a person included) or its
 * heartbeat is older than [stale] (a crash). A seam: Postgres in production, memory in tests.
 */
interface BrowserLeases {
    suspend fun tryAcquire(runId: String, now: Instant, idle: Duration, stale: Duration): LeaseTry

    /** The holder made a browser call: activity and heartbeat. */
    suspend fun touch(runId: String, now: Instant)

    suspend fun heartbeat(runId: String, now: Instant)

    suspend fun holder(): String?

    /** Gives the lease back (if [runId] holds it) and leaves the queue; true when it was held. */
    suspend fun release(runId: String): Boolean

    suspend fun leaveQueue(runId: String)

    /** Releases a holder that is idle or stale; returns it. */
    suspend fun releaseExpired(now: Instant, idle: Duration, stale: Duration): String?

    /** At startup: drops the lease and queue entries of runs that are not running any more. */
    suspend fun clearUnless(alive: suspend (String) -> Boolean): Int

    suspend fun saveResume(resume: BrowserResume)

    /** The latest resume of another run with [taskKey]. */
    suspend fun lastResume(taskKey: String, notRun: String): BrowserResume?
}

/** Pure: whether a holder last seen at [activity] / [heartbeat] has lost the lease at [now]. */
fun leaseExpired(now: Instant, activity: Instant?, heartbeat: Instant?, idle: Duration, stale: Duration): Boolean =
    (activity != null && now - activity >= idle) || (heartbeat != null && now - heartbeat >= stale)

/** Pure: the task key a resume is filed under (the task text, normalised and hashed). */
fun taskKey(task: String?): String? = task?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }?.let { t ->
    MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class PgBrowserLeases(private val database: Database) : BrowserLeases {
    override suspend fun tryAcquire(runId: String, now: Instant, idle: Duration, stale: Duration): LeaseTry = database.tx {
        // The single lease row, locked: concurrent tries take turns.
        val row = BrowserLeaseTable.selectAll().where { BrowserLeaseTable.id eq 1 }.forUpdate().single()
        var holder = row[BrowserLeaseTable.holderRun]
        if (holder == runId) {
            BrowserLeaseTable.update({ BrowserLeaseTable.id eq 1 }) { it[lastActivityAt] = now; it[heartbeatAt] = now }
            return@tx LeaseTry.Granted(fresh = false)
        }
        BrowserLeaseQueue.insertIgnore { it[BrowserLeaseQueue.runId] = runId; it[enqueuedAt] = now }
        if (holder != null && leaseExpired(now, row[BrowserLeaseTable.lastActivityAt], row[BrowserLeaseTable.heartbeatAt], idle, stale)) {
            log.info { "browser lease of $holder expired" }
            holder = null
        }
        val queue = BrowserLeaseQueue.selectAll().orderBy(BrowserLeaseQueue.enqueuedAt to SortOrder.ASC, BrowserLeaseQueue.runId to SortOrder.ASC).map { it[BrowserLeaseQueue.runId] }
        if (holder == null && queue.firstOrNull() == runId) {
            BrowserLeaseTable.update({ BrowserLeaseTable.id eq 1 }) {
                it[holderRun] = runId; it[acquiredAt] = now; it[heartbeatAt] = now; it[lastActivityAt] = now
            }
            BrowserLeaseQueue.deleteWhere { BrowserLeaseQueue.runId eq runId }
            LeaseTry.Granted(fresh = true)
        } else {
            if (holder == null) BrowserLeaseTable.update({ BrowserLeaseTable.id eq 1 }) { it[holderRun] = null }
            LeaseTry.Waiting(holder, queue.indexOf(runId).coerceAtLeast(0))
        }
    }

    override suspend fun touch(runId: String, now: Instant) {
        database.tx { BrowserLeaseTable.update({ (BrowserLeaseTable.id eq 1) and (BrowserLeaseTable.holderRun eq runId) }) { it[lastActivityAt] = now; it[heartbeatAt] = now } }
    }

    override suspend fun heartbeat(runId: String, now: Instant) {
        database.tx { BrowserLeaseTable.update({ (BrowserLeaseTable.id eq 1) and (BrowserLeaseTable.holderRun eq runId) }) { it[heartbeatAt] = now } }
    }

    override suspend fun holder(): String? = database.tx { BrowserLeaseTable.selectAll().where { BrowserLeaseTable.id eq 1 }.single()[BrowserLeaseTable.holderRun] }

    override suspend fun release(runId: String): Boolean = database.tx {
        BrowserLeaseQueue.deleteWhere { BrowserLeaseQueue.runId eq runId }
        BrowserLeaseTable.update({ (BrowserLeaseTable.id eq 1) and (BrowserLeaseTable.holderRun eq runId) }) { it[holderRun] = null } > 0
    }

    override suspend fun leaveQueue(runId: String) {
        database.tx { BrowserLeaseQueue.deleteWhere { BrowserLeaseQueue.runId eq runId } }
    }

    override suspend fun releaseExpired(now: Instant, idle: Duration, stale: Duration): String? = database.tx {
        val row = BrowserLeaseTable.selectAll().where { BrowserLeaseTable.id eq 1 }.forUpdate().single()
        val holder = row[BrowserLeaseTable.holderRun] ?: return@tx null
        if (!leaseExpired(now, row[BrowserLeaseTable.lastActivityAt], row[BrowserLeaseTable.heartbeatAt], idle, stale)) return@tx null
        BrowserLeaseTable.update({ BrowserLeaseTable.id eq 1 }) { it[holderRun] = null }
        holder
    }

    override suspend fun clearUnless(alive: suspend (String) -> Boolean): Int {
        val (holder, queued) = database.tx {
            BrowserLeaseTable.selectAll().where { BrowserLeaseTable.id eq 1 }.single()[BrowserLeaseTable.holderRun] to
                BrowserLeaseQueue.selectAll().map { it[BrowserLeaseQueue.runId] }
        }
        var cleared = 0
        if (holder != null && !alive(holder)) { release(holder); cleared++ }
        for (run in queued) if (!alive(run)) { leaveQueue(run); cleared++ }
        return cleared
    }

    override suspend fun saveResume(resume: BrowserResume) {
        database.tx {
            BrowserResumes.upsert {
                it[runId] = resume.runId; it[taskKey] = resume.taskKey; it[url] = resume.url; it[title] = resume.title
                it[whereLine] = resume.where; it[at] = resume.at
            }
        }
    }

    override suspend fun lastResume(taskKey: String, notRun: String): BrowserResume? = database.tx {
        BrowserResumes.selectAll().where { (BrowserResumes.taskKey eq taskKey) and (BrowserResumes.runId neq notRun) }
            .orderBy(BrowserResumes.at to SortOrder.DESC).limit(1).singleOrNull()?.let {
                BrowserResume(it[BrowserResumes.runId], it[BrowserResumes.taskKey], it[BrowserResumes.url], it[BrowserResumes.title], it[BrowserResumes.whereLine], it[BrowserResumes.at])
            }
    }
}

/** In memory, with the same rules (tests). */
class MemoryBrowserLeases : BrowserLeases {
    private val lock = kotlinx.coroutines.sync.Mutex()
    var holder: String? = null
        private set
    private var activity: Instant? = null
    private var heartbeat: Instant? = null
    val queue = LinkedHashMap<String, Instant>()
    val resumes = mutableListOf<BrowserResume>()

    override suspend fun tryAcquire(runId: String, now: Instant, idle: Duration, stale: Duration): LeaseTry = lock.withLock {
        if (holder == runId) { activity = now; heartbeat = now; return@withLock LeaseTry.Granted(false) }
        queue.putIfAbsent(runId, now)
        if (holder != null && leaseExpired(now, activity, heartbeat, idle, stale)) holder = null
        val order = queue.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
        if (holder == null && order.first() == runId) {
            holder = runId; activity = now; heartbeat = now; queue.remove(runId)
            LeaseTry.Granted(true)
        } else LeaseTry.Waiting(holder, order.indexOf(runId))
    }

    override suspend fun touch(runId: String, now: Instant) = lock.withLock { if (holder == runId) { activity = now; heartbeat = now } }
    override suspend fun heartbeat(runId: String, now: Instant) = lock.withLock { if (holder == runId) heartbeat = now }
    override suspend fun holder(): String? = holder
    override suspend fun release(runId: String): Boolean = lock.withLock { queue.remove(runId); (holder == runId).also { if (it) holder = null } }
    override suspend fun leaveQueue(runId: String) = lock.withLock { queue.remove(runId); Unit }
    override suspend fun releaseExpired(now: Instant, idle: Duration, stale: Duration): String? = lock.withLock {
        val h = holder ?: return@withLock null
        if (leaseExpired(now, activity, heartbeat, idle, stale)) { holder = null; h } else null
    }
    override suspend fun clearUnless(alive: suspend (String) -> Boolean): Int {
        var n = 0
        holder?.let { if (!alive(it)) { release(it); n++ } }
        for (q in queue.keys.toList()) if (!alive(q)) { leaveQueue(q); n++ }
        return n
    }
    override suspend fun saveResume(resume: BrowserResume) { resumes.removeAll { it.runId == resume.runId }; resumes += resume }
    override suspend fun lastResume(taskKey: String, notRun: String): BrowserResume? = resumes.filter { it.taskKey == taskKey && it.runId != notRun }.maxByOrNull { it.at }

    /** Makes the holder's last heartbeat and activity [at] (tests: a crash, or a quiet run). */
    fun age(at: Instant) { activity = at; heartbeat = at }

}

/**
 * Keeps the lease honest: heartbeats the holder while its run is alive in this process, and
 * releases an idle or stale holder (recording where it was).
 */
@Inject
@ContributesIntoSet(AppScope::class)
class BrowserLeaseKeeper(private val pilot: BrowserPilot) : BackgroundService {
    override val name = "browser-lease"

    override suspend fun run() {
        while (true) {
            try {
                pilot.maintainLease()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn { "browser lease upkeep failed: ${e::class.simpleName}" }
            }
            delay(KEEPER_TICK)
        }
    }

    companion object {
        val KEEPER_TICK = 30.seconds
    }
}

/** At boot: no run that is not running keeps the browser or a place in its queue. */
@Inject
@ContributesIntoSet(AppScope::class)
class BrowserLeaseAtStartup(private val leases: BrowserLeases, private val runs: RunStore) : StartupTask {
    override val name = "browser-lease"

    override suspend fun run() {
        try {
            val cleared = leases.clearUnless { id -> runs.get(id)?.status in RunStatus.OPEN }
            if (cleared > 0) log.info { "cleared $cleared browser lease entries of runs that are not running" }
        } catch (e: Exception) {
            log.warn(e) { "could not clear stale browser leases" }
        }
    }
}
