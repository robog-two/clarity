package net.robog.clarity.backend.backup

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.robog.clarity.backend.config.DatabaseConfig
import net.robog.clarity.backend.config.Secret
import net.robog.clarity.backend.integration.ContainerRuntimeAvailable
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * A Postgres the tests are pointed at by hand (`CLARITY_TEST_DB_URL`, `CLARITY_TEST_DB_USER`, `CLARITY_TEST_DB_PASSWORD`),
 * for machines where Testcontainers cannot publish a port.
 */
object ExternalTestDatabase {
    fun fromEnv(): DatabaseConfig? {
        val url = System.getenv("CLARITY_TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return null
        return DatabaseConfig(url, System.getenv("CLARITY_TEST_DB_USER") ?: "clarity", System.getenv("CLARITY_TEST_DB_PASSWORD")?.let(::Secret), maxPoolSize = 6)
    }
}

class DatabaseForBackupTests : io.kotest.core.annotation.Condition {
    override fun evaluate(kclass: kotlin.reflect.KClass<out io.kotest.core.spec.Spec>): Boolean =
        ExternalTestDatabase.fromEnv() != null || ContainerRuntimeAvailable().evaluate(kclass)
}

/**
 * The follow-up of a rollback, as SQL on a migrated schema. "Live" is the database before the restore;
 * the restored side is imitated by changing rows (the real `pg_restore` is exercised by the real check).
 */
@EnabledIf(DatabaseForBackupTests::class)
class RollbackCarryDbTest : FunSpec({
    val external = ExternalTestDatabase.fromEnv()
    val postgres = if (external == null) PostgreSQLContainer("postgres:18-alpine").withStartupAttempts(3) else null
    lateinit var database: Database
    lateinit var sql: ExposedRollbackSql

    beforeSpec {
        postgres?.start()
        val config = external ?: DatabaseConfig(postgres!!.jdbcUrl, postgres.username, Secret(postgres.password), maxPoolSize = 4)
        val dataSource = DatabaseFactory.dataSource(config)
        DatabaseFactory.migrate(dataSource)
        database = Database.connect(dataSource)
        sql = ExposedRollbackSql(database)
    }
    afterSpec { postgres?.stop() }
    beforeTest {
        database.tx {
            exec("DROP SCHEMA IF EXISTS $CARRY_SCHEMA CASCADE")
            exec("TRUNCATE action_outbox, held_action, approval_signature_used, vault_device_key, device, enrollment_token, connector, feed_item, agent_run CASCADE")
        }
    }

    suspend fun run(statement: String) = database.tx { exec(statement) }

    suspend fun rows(query: String): List<List<String?>> = database.tx {
        val out = mutableListOf<List<String?>>()
        exec(query) { rs ->
            while (rs.next()) out += (1..rs.metaData.columnCount).map { rs.getString(it) }
        }
        out
    }

    val dev1 = "00000000-0000-0000-0000-000000000001"
    val dev2 = "00000000-0000-0000-0000-000000000002"
    val heldA = "00000000-0000-0000-0000-0000000000a1"
    val heldB = "00000000-0000-0000-0000-0000000000b1"

    fun outbox(id: String, held: String?, key: String, status: String) =
        "INSERT INTO action_outbox (id, connector_id, held_id, action, idempotency_key, status) VALUES " +
            "('$id', 'mail', ${held?.let { "'$it'" } ?: "NULL"}, '{\"kind\":\"x\"}', '$key', '$status')"

    fun held(id: String, key: String, status: String) =
        "INSERT INTO held_action (id, idempotency_key, action, verdict, agent_view, urgency, status) VALUES ('$id', '$key', '{}', '{}', 'v', 'normal', '$status')"

    suspend fun seedCommon() {
        run("INSERT INTO connector (id) VALUES ('mail')")
        run("INSERT INTO device (id, name, public_key) VALUES ('$dev1', 'Phone', '\\x01')")
    }

    test("what the restored side had queued is cancelled, and what was sent live stays sent") {
        seedCommon()
        // live: the person approved held A and the mail went out; B waits queued; C was sent and is unknown to the restored side
        run(held(heldA, "ha", "executed"))
        run(held(heldB, "hb", "approved"))
        run(outbox("00000000-0000-0000-0000-0000000000c1", heldA, "send-a", "done"))
        run(outbox("00000000-0000-0000-0000-0000000000c2", heldB, "send-b", "queued"))
        run(outbox("00000000-0000-0000-0000-0000000000c3", null, "send-c", "done"))
        run(outbox("00000000-0000-0000-0000-0000000000c4", null, "send-d", "leased"))
        sql.capture()
        // restored side (last night): A was still approved and queued, B queued, an old one E queued, no C or D
        run("DELETE FROM action_outbox")
        run("UPDATE held_action SET status = 'approved' WHERE id = '$heldA'")
        run(outbox("00000000-0000-0000-0000-0000000000c1", heldA, "send-a", "queued"))
        run(outbox("00000000-0000-0000-0000-0000000000c2", heldB, "send-b", "queued"))
        run(outbox("00000000-0000-0000-0000-0000000000e1", null, "send-e", "leased"))

        sql.cancelPending()
        sql.restoreLive()

        rows("SELECT idempotency_key, status FROM action_outbox ORDER BY idempotency_key") shouldContainExactly listOf(
            listOf("send-a", "done"), // sent live, so done here; it never runs again
            listOf("send-b", "failed"), // queued live too, but unsent: cancelled
            listOf("send-c", "done"), // unknown to the restored side, remembered
            listOf("send-d", "done"), // leased live: it may have gone out
            listOf("send-e", "failed"), // only the restored side had it: cancelled
        )
        rows("SELECT count(*) FROM action_outbox WHERE status IN ('queued', 'leased')") shouldContainExactly listOf(listOf("0"))
        // A's decision made live stays; B was approved but its delivery was cancelled, so it did not happen
        rows("SELECT id, status FROM held_action ORDER BY id") shouldContainExactly listOf(listOf(heldA, "executed"), listOf(heldB, "failed"))
        // the carry-over is gone
        rows("SELECT count(*) FROM information_schema.schemata WHERE schema_name = '$CARRY_SCHEMA'") shouldContainExactly listOf(listOf("0"))
    }

    test("approval signatures are the union of both sides") {
        seedCommon()
        run("INSERT INTO approval_signature_used (signature_hash, held_id, device_id) VALUES ('live-1', '$heldA', '$dev1'), ('both', '$heldA', '$dev1')")
        sql.capture()
        run("DELETE FROM approval_signature_used")
        run("INSERT INTO approval_signature_used (signature_hash, held_id, device_id) VALUES ('old-1', '$heldB', '$dev1'), ('both', '$heldA', '$dev1')")
        sql.restoreLive()
        rows("SELECT signature_hash FROM approval_signature_used ORDER BY 1") shouldContainExactly listOf(listOf("both"), listOf("live-1"), listOf("old-1"))
    }

    test("the phone stays paired: live devices, vault keys and enrollment tokens replace the restored ones") {
        seedCommon()
        run("INSERT INTO vault_device_key (device_id, public_key, alg, approval_key) VALUES ('$dev1', 'live-vault', 'p256', 'live-approval')")
        run("INSERT INTO enrollment_token (token_hash) VALUES ('\\x0a')")
        sql.capture()
        // restored side: the same phone with an older key, a device that was removed since, an old token
        run("DELETE FROM device")
        run("INSERT INTO device (id, name, public_key) VALUES ('$dev1', 'Old name', '\\x02'), ('$dev2', 'Old tablet', '\\x03')")
        run("INSERT INTO vault_device_key (device_id, public_key, alg, approval_key) VALUES ('$dev1', 'old-vault', 'p256', 'old-approval'), ('$dev2', 'tablet-vault', 'p256', NULL)")
        run("DELETE FROM enrollment_token")
        run("INSERT INTO enrollment_token (token_hash) VALUES ('\\x0b')")
        sql.restoreLive()
        rows("SELECT id, name, encode(public_key, 'hex') FROM device") shouldContainExactly listOf(listOf(dev1, "Phone", "01"))
        rows("SELECT device_id, public_key, approval_key FROM vault_device_key") shouldContainExactly listOf(listOf(dev1, "live-vault", "live-approval"))
        rows("SELECT encode(token_hash, 'hex') FROM enrollment_token") shouldContainExactly listOf(listOf("0a"))
    }

    test("helper runs that were waiting or running are cancelled, and notifications that went out stay marked") {
        seedCommon()
        val feedId = "00000000-0000-0000-0000-0000000000f1"
        run("INSERT INTO feed_item (id, author, cards, notify, released_at, batch_state) VALUES ('$feedId', 'agent', '[]', 'released:2026-11-10T12:00:00Z', now(), 'summarized')")
        sql.capture()
        run("UPDATE feed_item SET notify = 'hold_until:2026-11-10T12:00:00Z', released_at = NULL, batch_state = 'pending'")
        run("INSERT INTO agent_run (reason, status) VALUES ('x', 'running'), ('y', 'queued'), ('z', 'done')")
        run("UPDATE browser_lease SET holder_run = 'run-1'")
        run("INSERT INTO browser_lease_queue (run_id, enqueued_at) VALUES ('run-2', now())")
        sql.restoreLive()
        rows("SELECT status FROM agent_run ORDER BY status") shouldContainExactly listOf(listOf("cancelled"), listOf("cancelled"), listOf("done"))
        rows("SELECT notify, batch_state, released_at IS NOT NULL FROM feed_item") shouldContainExactly listOf(listOf("released:2026-11-10T12:00:00Z", "summarized", "t"))
        rows("SELECT holder_run FROM browser_lease") shouldContainExactly listOf(listOf<String?>(null))
        rows("SELECT count(*) FROM browser_lease_queue") shouldContainExactly listOf(listOf("0"))
    }

    test("the follow-up is only pending while the carry-over exists, and cancelling alone is safe to run first") {
        seedCommon()
        sql.pending() shouldBe false
        run(outbox("00000000-0000-0000-0000-0000000000c9", null, "send-z", "queued"))
        sql.capture()
        sql.pending() shouldBe true
        sql.cancelPending()
        rows("SELECT status FROM action_outbox") shouldContainExactly listOf(listOf("failed"))
        sql.restoreLive()
        sql.pending() shouldBe false
    }
})
