package net.robog.clarity.backend.backup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.scheduler.Scheduler
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

/** The schema that carries what must survive a restore. `pg_restore --clean` only drops what the dump holds, so it stays. */
const val CARRY_SCHEMA = "rollback_carry"

/**
 * What a rollback must not undo or repeat, as plain SQL so it runs in the database itself.
 *
 * Before the restore, [capture] copies the live side's state of those things into [CARRY_SCHEMA]. The
 * restore then replaces everything else. After the next start (once Flyway has brought the schema up
 * to date) [apply] puts them back, in one transaction, and drops the schema. The schema's presence
 * is the "a rollback is still being finished" marker, so a crash in between only delays it.
 *
 * Rules (the safest side wins; nothing that already happened outside may happen twice):
 *  - Outbox: every restored row still waiting (`queued` or `leased`) is cancelled (`failed`, marked
 *    `rolledBack`), because it was queued on a day that no longer happened. Rows the live side had
 *    already sent (`done`, `leased` counts as sent) or failed come back as they were, whether or not the
 *    restored side knows them, so their idempotency keys stay used. [cancelPending] does the first
 *    part alone, and runs even when the second part cannot.
 *  - Held actions: a live decision (approved, declined, executed ...) replaces a restored `held`/`blocked`/`approved`
 *    state, so nothing is asked or run twice. Approved ones whose delivery was cancelled become `failed`.
 *  - Signatures: the union of both sides, so no signature works twice.
 *  - Devices, their vault keys and the enrollment tokens: exactly the live ones, so the phone stays
 *    paired and the vault opens. A device that was removed live stays removed.
 *  - Notifications: held-until and evening-summary marks that already happened live stay marked.
 *  - Helper runs waiting or running are cancelled and the browser lease is freed. The scheduler's next
 *    runs are recomputed ([Scheduler.resetAll]): a restored schedule would otherwise fire everything at once.
 */
object RollbackCarry {
    val capture: List<String> = listOf(
        "DROP SCHEMA IF EXISTS $CARRY_SCHEMA CASCADE",
        "CREATE SCHEMA $CARRY_SCHEMA",
        "CREATE TABLE $CARRY_SCHEMA.signature AS SELECT * FROM approval_signature_used",
        "CREATE TABLE $CARRY_SCHEMA.device AS SELECT * FROM device",
        "CREATE TABLE $CARRY_SCHEMA.vault_device_key AS SELECT * FROM vault_device_key",
        "CREATE TABLE $CARRY_SCHEMA.enrollment_token AS SELECT * FROM enrollment_token",
        // `leased` counts as sent: it may have gone out before the process stopped.
        "CREATE TABLE $CARRY_SCHEMA.outbox AS SELECT id, connector_id, held_id, action, idempotency_key, " +
            "CASE WHEN status = 'leased' THEN 'done' ELSE status END AS status, attempts, result, created_at " +
            "FROM action_outbox WHERE status <> 'queued'",
        "CREATE TABLE $CARRY_SCHEMA.held AS SELECT id, status, decided_at, failure FROM held_action WHERE status NOT IN ('held', 'blocked')",
        "CREATE TABLE $CARRY_SCHEMA.feed AS SELECT id, notify, released_at, batch_state FROM feed_item " +
            "WHERE released_at IS NOT NULL OR batch_state = 'summarized'",
    )

    /** Step one of the follow-up: nothing queued by the restored side may run. Needs only the outbox table. */
    val cancelPending: List<String> = listOf(
        "UPDATE action_outbox SET status = 'failed', lease_until = NULL, " +
            "result = jsonb_build_object('rolledBack', true, 'note', 'cancelled by a rollback') " +
            "WHERE status IN ('queued', 'leased')",
    )

    /** Step two: bring the live side's state back over the restored database. Runs in one transaction. */
    val restoreLive: List<String> = listOf(
        "INSERT INTO approval_signature_used (signature_hash, held_id, device_id, used_at) " +
            "SELECT signature_hash, held_id, device_id, used_at FROM $CARRY_SCHEMA.signature ON CONFLICT DO NOTHING",
        // devices: exactly the live ones (a removed device cascades away its vault key and sealed copies)
        "DELETE FROM device WHERE id NOT IN (SELECT id FROM $CARRY_SCHEMA.device)",
        "INSERT INTO device (id, name, public_key, created_at, last_seen_at) " +
            "SELECT id, name, public_key, created_at, last_seen_at FROM $CARRY_SCHEMA.device " +
            "ON CONFLICT (id) DO UPDATE SET name = excluded.name, public_key = excluded.public_key, " +
            "created_at = excluded.created_at, last_seen_at = excluded.last_seen_at",
        "DELETE FROM vault_device_key WHERE device_id NOT IN (SELECT device_id FROM $CARRY_SCHEMA.vault_device_key)",
        "INSERT INTO vault_device_key (device_id, public_key, alg, created_at, approval_key) " +
            "SELECT device_id, public_key, alg, created_at, approval_key FROM $CARRY_SCHEMA.vault_device_key " +
            "ON CONFLICT (device_id) DO UPDATE SET public_key = excluded.public_key, alg = excluded.alg, " +
            "created_at = excluded.created_at, approval_key = excluded.approval_key",
        "DELETE FROM enrollment_token",
        "INSERT INTO enrollment_token (id, token_hash, created_at) SELECT id, token_hash, created_at FROM $CARRY_SCHEMA.enrollment_token",
        // held actions: a decision made live stays made
        "UPDATE held_action h SET status = c.status, decided_at = c.decided_at, failure = c.failure " +
            "FROM $CARRY_SCHEMA.held c WHERE h.id = c.id AND h.status IN ('held', 'blocked', 'approved')",
        // outbox: what was sent or failed live keeps its key and state
        "INSERT INTO action_outbox (id, connector_id, held_id, action, idempotency_key, status, attempts, next_attempt_at, result, created_at) " +
            "SELECT c.id, c.connector_id, (SELECT h.id FROM held_action h WHERE h.id = c.held_id), c.action, c.idempotency_key, " +
            "c.status, c.attempts, now(), c.result, c.created_at " +
            "FROM $CARRY_SCHEMA.outbox c " +
            "WHERE EXISTS (SELECT 1 FROM connector k WHERE k.id = c.connector_id) " +
            "AND NOT EXISTS (SELECT 1 FROM action_outbox o WHERE o.id = c.id AND o.idempotency_key <> c.idempotency_key) " +
            "ON CONFLICT (idempotency_key) DO UPDATE SET status = excluded.status, attempts = excluded.attempts, " +
            "result = excluded.result, lease_until = NULL",
        // approved here, but its delivery was cancelled and was not sent live: it did not happen
        "UPDATE held_action SET status = 'failed', failure = 'Cancelled by a rollback.' " +
            "WHERE status = 'approved' AND id IN (SELECT held_id FROM action_outbox WHERE held_id IS NOT NULL AND result ->> 'rolledBack' = 'true')",
        "UPDATE feed_item f SET notify = c.notify, released_at = c.released_at, batch_state = c.batch_state " +
            "FROM $CARRY_SCHEMA.feed c WHERE f.id = c.id",
        "UPDATE agent_run SET status = 'cancelled', error = 'Cancelled by a rollback.', finished_at = now() WHERE status IN ('queued', 'running')",
        "UPDATE browser_lease SET holder_run = NULL, acquired_at = NULL, heartbeat_at = NULL, last_activity_at = NULL",
        "DELETE FROM browser_lease_queue",
        "DROP SCHEMA $CARRY_SCHEMA CASCADE",
    )

    const val EXISTS = "SELECT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = '$CARRY_SCHEMA')"
    const val KILL_OTHER_CONNECTIONS =
        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()"
}

/** Runs the statements of [RollbackCarry] against the database. Faked in tests of the flow. */
interface RollbackSql {
    suspend fun capture()
    suspend fun pending(): Boolean
    suspend fun killOtherConnections()
    suspend fun cancelPending()
    suspend fun restoreLive()
    suspend fun dropCarry()
}

@Inject
@ContributesBinding(AppScope::class)
class ExposedRollbackSql(private val database: Database) : RollbackSql {
    override suspend fun capture() {
        database.tx { RollbackCarry.capture.forEach { exec(it) } }
    }

    override suspend fun pending(): Boolean = database.tx { exec(RollbackCarry.EXISTS) { rs -> rs.next() && rs.getBoolean(1) } ?: false }

    override suspend fun killOtherConnections() {
        database.tx { exec(RollbackCarry.KILL_OTHER_CONNECTIONS) { } }
    }

    override suspend fun cancelPending() {
        database.tx { RollbackCarry.cancelPending.forEach { exec(it) } }
    }

    override suspend fun restoreLive() {
        database.tx { RollbackCarry.restoreLive.forEach { exec(it) } }
    }

    override suspend fun dropCarry() {
        database.tx { exec("DROP SCHEMA IF EXISTS $CARRY_SCHEMA CASCADE") }
    }
}

/**
 * The first thing after the restart that follows a rollback: finishes the rollback (see [RollbackCarry]).
 * Runs after the migrations and before the server answers or any background service starts, so nothing
 * that was queued on the restored side can run first. If the second step fails, the first has already
 * made the restored side's pending deliveries safe, the carry-over is kept for another try at the next
 * start, and the backend still comes up.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class RollbackFollowUp(private val sql: RollbackSql, private val scheduler: Scheduler) : StartupTask {
    override val name = "rollback follow-up"

    override suspend fun run() {
        if (!sql.pending()) return
        log.info { "finishing a rollback: cancelling what the restored side had queued" }
        sql.cancelPending()
        runCatching { scheduler.resetAll() }.onFailure { log.warn(it) { "could not reset the scheduler after a rollback" } }
        try {
            sql.restoreLive()
            log.info { "rollback finished: devices, signatures and sent actions carried over" }
        } catch (e: Exception) {
            log.error(e) { "the rollback follow-up could not finish; it will try again at the next start" }
        }
    }
}
