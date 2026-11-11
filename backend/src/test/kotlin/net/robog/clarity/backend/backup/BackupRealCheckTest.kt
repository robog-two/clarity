package net.robog.clarity.backend.backup

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.config.BackupsConfig
import net.robog.clarity.backend.governance.ActionOutbox
import net.robog.clarity.backend.governance.ActionResults
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.governance.HeldActionStore
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.backend.reports.reportsAppConfig
import net.robog.clarity.backend.scheduler.Scheduler
import net.robog.clarity.backend.vault.ExposedVaultStore
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.backup.RollbackPayload
import net.robog.clarity.shared.backup.RollbackRequest
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.vault.DeviceVaultKey

/** On only when `CLARITY_TEST_DB_URL` and `CLARITY_PG_DUMP` / `CLARITY_PG_RESTORE` point at a throwaway Postgres (see the brief for backups). */
class RealCheckEnabled : io.kotest.core.annotation.Condition {
    override fun evaluate(kclass: kotlin.reflect.KClass<out io.kotest.core.spec.Spec>): Boolean =
        ExternalTestDatabase.fromEnv() != null && System.getenv("CLARITY_PG_DUMP") != null
}

/**
 * The real thing, nothing faked but the process exit and the phone: the real `pg_dump` and `pg_restore`
 * (through the wrappers named in the environment), the real [BackupService], the real schema from the
 * Flyway migrations, the real outbox and vault stores, and the real follow-up after the restart.
 */
@EnabledIf(RealCheckEnabled::class)
class BackupRealCheckTest : FunSpec({
    test("dump, change, roll back: the data comes back, a sent action does not run again, and the rollback can be undone") {
        val say = StringBuilder()
        fun say(line: String = "") { println(line); say.appendLine(line) }

        val db = ExternalTestDatabase.fromEnv()!!
        val dataSource = DatabaseFactory.dataSource(db)
        DatabaseFactory.migrate(dataSource)
        val database = org.jetbrains.exposed.v1.jdbc.Database.connect(dataSource)
        val dir = Files.createTempDirectory("real-backups")
        val config = reportsAppConfig(dir).copy(
            database = db,
            backups = BackupsConfig(pgDump = System.getenv("CLARITY_PG_DUMP"), pgRestore = System.getenv("CLARITY_PG_RESTORE"), timeout = 3.minutes),
        )
        val clock = Clock.System
        val vault = ExposedVaultStore(database, clock)
        val settings = SettingsProvider { Settings(homeZone = "America/New_York") }
        val maintenance = Maintenance()
        val appScope = CoroutineScope(SupervisorJob())
        val exited = CompletableDeferred<Int>()
        val sql = ExposedRollbackSql(database)
        val service = BackupService(config, ProcessPgTools(config), settings, clock, vault, maintenance, sql, appScope) { exited.complete(it) }
        val outbox = ActionOutbox(database, clock, HeldActionStore(database, clock), OutboxSignals(), GovernanceEvents(), ActionResults())
        val mail = ConnectorId("mail")

        suspend fun rows(query: String): List<List<String?>> = database.tx {
            val out = mutableListOf<List<String?>>()
            exec(query) { rs -> while (rs.next()) out += (1..rs.metaData.columnCount).map { rs.getString(it) } }
            out
        }
        suspend fun run(statement: String) = database.tx { exec(statement) }
        suspend fun show(title: String) {
            say("-- $title")
            say("   journal : " + rows("SELECT text FROM memory_journal ORDER BY id").map { it[0] })
            say("   outbox  : " + rows("SELECT idempotency_key, status FROM action_outbox ORDER BY idempotency_key").map { "${it[0]}=${it[1]}" })
            say("   devices : " + rows("SELECT name FROM device ORDER BY name").map { it[0] })
            say("   used sig: " + rows("SELECT signature_hash FROM approval_signature_used ORDER BY 1").map { it[0]!!.take(8) })
        }

        // --- state on "day one" -------------------------------------------------------------
        val phone = "00000000-0000-0000-0000-00000000aa01"
        val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val b64 = Base64.getUrlEncoder().withoutPadding()
        fun sign(payload: String) = b64.encodeToString(Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(payload.toByteArray()) }.sign())

        run("TRUNCATE memory_journal, action_outbox, approval_signature_used, vault_device_key, device, connector CASCADE")
        run("INSERT INTO connector (id, provisioned) VALUES ('mail', true)")
        run("INSERT INTO device (id, name, public_key) VALUES ('$phone', 'Sam Example phone', '\\x01')")
        vault.upsertDeviceKey(DeviceVaultKey(DeviceId(phone), "vault-public-key", "p256"), approvalKey = b64.encodeToString(keys.public.encoded))
        run("INSERT INTO memory_journal (date, text) VALUES (current_date, 'written before the backup')")
        outbox.enqueue(mail, ProposedAction.SendEmail("mail-1", "Reply to Dana", EmailDraft(to = listOf(Handle.email("dana@example.com")), subject = "Lunch", body = "Noon works.")))
        outbox.enqueue(mail, ProposedAction.SendEmail("mail-2", "Reply to Lee", EmailDraft(to = listOf(Handle.email("lee@example.com")), subject = "Notes", body = "Attached.")))
        show("day one, just before the 02:00 backup")

        // --- the nightly backup ----------------------------------------------------------------
        val night = service.backupNightly(LocalDate(2026, 11, 9), wait = false).getOrNull()!!
        val backups = dir.resolve("backups")
        say("-- backup written: ${night.name}  ${night.sizeBytes} bytes  sha256 ${night.sha256.take(16)}...")
        say("   folder mode ${PosixFilePermissions.toString(Files.getPosixFilePermissions(backups))}, file mode ${PosixFilePermissions.toString(Files.getPosixFilePermissions(backups.resolve(night.name)))}")
        say("   files: " + Files.list(backups).use { s -> s.map { it.fileName.toString() }.sorted().toList() })

        // --- the live database moves on ----------------------------------------------------------
        run("INSERT INTO memory_journal (date, text) VALUES (current_date, 'written after the backup')")
        val leased = outbox.lease(mail)
        say("-- the mail connector picked up ${leased.size} actions and sent them: ${leased.map { it.action.idempotencyKey }}")
        run("UPDATE action_outbox SET status = 'done' WHERE idempotency_key = 'mail-1'")
        run("INSERT INTO device (id, name, public_key) VALUES ('00000000-0000-0000-0000-00000000aa02', 'Tablet enrolled later', '\\x02')")
        run("INSERT INTO approval_signature_used (signature_hash, held_id, device_id) VALUES ('used-after-the-backup-1', gen_random_uuid(), '$phone')")
        run("UPDATE action_outbox SET status = 'queued', lease_until = NULL WHERE idempotency_key = 'mail-2'") // sat queued at the time of the rollback
        run("INSERT INTO action_outbox (id, connector_id, action, idempotency_key, status) SELECT gen_random_uuid(), 'mail', action, 'mail-3', 'done' FROM action_outbox LIMIT 1")
        show("live database, before the rollback")

        // --- the signed rollback ----------------------------------------------------------------
        val request = RollbackRequest(night.name, night.sha256, sign(RollbackPayload.payload(night.name, night.sha256)))
        val accepted = service.rollback(DeviceId(phone), request).getOrNull()!!
        say("-- rollback accepted; copy of now: ${accepted.safetyCopy.name}  ${accepted.safetyCopy.sizeBytes} bytes")
        service.rollback(DeviceId(phone), request).leftOrNull().let { say("   the same signature again: ${it!!::class.simpleName} (${it.detail})") }
        val code = withTimeout(60.seconds) { exited.await() }
        say("-- restore finished; the backend would now exit with code $code and podman would start a new one")
        say("   maintenance on: ${maintenance.active}; rollback result file: ${service.list().lastRollback}")
        say("   files: " + Files.list(backups).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith(".") }.sorted().toList() })

        // --- the new process: migrate, then the follow-up --------------------------------------------
        rows("SELECT count(*) FROM information_schema.schemata WHERE schema_name = '$CARRY_SCHEMA'").single()[0] shouldBe "1"
        say("-- fresh process: carry-over schema present: yes; Flyway on the restored data: " +
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate().migrationsExecuted + " migrations to run")
        show("restored, before the follow-up (what the new backend would see if it skipped it)")
        RollbackFollowUp(sql, Scheduler(database, emptySet(), settings, clock)).run()
        show("after the follow-up")
        say("   a connector asking for work now gets: " + outbox.lease(mail).map { it.action.idempotencyKey } + "  (nothing queued is left to run)")
        say("   carry-over schema left: " + rows("SELECT count(*) FROM information_schema.schemata WHERE schema_name = '$CARRY_SCHEMA'").single()[0])

        rows("SELECT text FROM memory_journal ORDER BY id").map { it[0] } shouldBe listOf("written before the backup")
        rows("SELECT idempotency_key, status FROM action_outbox ORDER BY idempotency_key").map { "${it[0]}=${it[1]}" } shouldBe
            listOf("mail-1=done", "mail-2=failed", "mail-3=done")
        rows("SELECT name FROM device ORDER BY name").map { it[0] } shouldBe listOf("Sam Example phone", "Tablet enrolled later")
        rows("SELECT count(*) FROM approval_signature_used WHERE signature_hash = 'used-after-the-backup-1'").single()[0] shouldBe "1"
        outbox.lease(mail) shouldBe emptyList()

        // --- undo: roll back to the copy of now -----------------------------------------------------------
        val undo = service.list().backups.first { it.name == accepted.safetyCopy.name }
        // new process again: a fresh service and exit hook
        val exited2 = CompletableDeferred<Int>()
        val service2 = BackupService(config, ProcessPgTools(config), settings, clock, vault, Maintenance(), sql, CoroutineScope(SupervisorJob())) { exited2.complete(it) }
        service2.rollback(DeviceId(phone), RollbackRequest(undo.name, undo.sha256, sign(RollbackPayload.payload(undo.name, undo.sha256)))).getOrNull()!!
        withTimeout(60.seconds) { exited2.await() }
        RollbackFollowUp(sql, Scheduler(database, emptySet(), settings, clock)).run()
        show("after rolling back to the copy of now (the undo)")
        rows("SELECT text FROM memory_journal ORDER BY id").map { it[0] } shouldBe listOf("written before the backup", "written after the backup")
        rows("SELECT idempotency_key, status FROM action_outbox ORDER BY idempotency_key").map { "${it[0]}=${it[1]}" } shouldBe
            listOf("mail-1=done", "mail-2=failed", "mail-3=done")

        System.getenv("CLARITY_REAL_CHECK_OUT")?.let { Files.writeString(java.nio.file.Path.of(it), say.toString()) }
    }
})
