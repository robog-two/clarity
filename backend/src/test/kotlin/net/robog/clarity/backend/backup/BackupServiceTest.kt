package net.robog.clarity.backend.backup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import net.robog.clarity.backend.agent.MemoryVault
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.StepClock
import net.robog.clarity.backend.governance.PhoneApprovals
import net.robog.clarity.backend.reports.reportsAppConfig
import net.robog.clarity.backend.vault.VaultStore
import net.robog.clarity.shared.DeviceId
import net.robog.clarity.shared.backup.BackupKind
import net.robog.clarity.shared.backup.RollbackPayload
import net.robog.clarity.shared.backup.RollbackRequest
import net.robog.clarity.shared.session.Settings
import kotlin.time.Duration.Companion.seconds

private class FakePg : PgTools {
    val calls = CopyOnWriteArrayList<String>()
    var content = "PGDMP-1"
    var failDump = false
    var failRestore = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun dump(target: Path) {
        calls += "dump"
        gate?.await()
        if (failDump) throw BackupFailure("the backup failed: no connection")
        Files.writeString(target, content)
    }

    override suspend fun restore(source: Path) {
        calls += "restore ${source.fileName}"
        if (failRestore) throw BackupFailure("the restore failed")
    }
}

private class FakeSql(private val log: MutableList<String>) : RollbackSql {
    override suspend fun capture() { log += "capture" }
    override suspend fun pending() = false
    override suspend fun killOtherConnections() { log += "kill" }
    override suspend fun cancelPending() { log += "cancelPending" }
    override suspend fun restoreLive() { log += "restoreLive" }
    override suspend fun dropCarry() { log += "dropCarry" }
}

/** A vault that knows one approval key and remembers the signatures it has seen. */
private class SigningVault(private val device: DeviceId, private val key: String?) : VaultStore by MemoryVault(emptyList()) {
    val used = mutableSetOf<String>()
    override suspend fun approvalKey(device: DeviceId): String? = if (device == this.device) key else null
    override suspend fun markSignatureUsed(signatureHash: String, heldId: String, device: DeviceId) = used.add(signatureHash)
}

private val b64 = Base64.getUrlEncoder().withoutPadding()

private fun sign(key: KeyPair, payload: String) = b64.encodeToString(
    Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(payload.toByteArray()) }.sign(),
)

class BackupServiceTest : FunSpec({
    val phone = DeviceId("2d3b7f24-6d54-4d6b-9a67-0c5d3c8f1a10")
    val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val clock = StepClock(Instant.parse("2026-11-10T19:05:00Z"))
    val settings = SettingsProvider { Settings(homeZone = "America/New_York") }

    class Rig(dir: Path) {
        val pg = FakePg()
        val log = CopyOnWriteArrayList<String>()
        val maintenance = Maintenance()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val exited = CompletableDeferred<Int>()
        lateinit var vault: SigningVault
        lateinit var service: BackupService
        val dir = dir
    }

    fun rig(keyForPhone: String? = b64.encodeToString(keys.public.encoded)): Rig {
        val dir = Files.createTempDirectory("backups-test")
        val r = Rig(dir)
        r.vault = SigningVault(phone, keyForPhone)
        val config = reportsAppConfig(dir)
        r.service = BackupService(config, r.pg, settings, clock, r.vault, r.maintenance, FakeSql(r.log), r.appScope) { r.exited.complete(it) }
        return r
    }

    test("a nightly backup is private and complete, named for the home-clock day") {
        val r = rig()
        val info = r.service.backupNightly(LocalDate(2026, 11, 10), wait = false).getOrNull()!!
        info.name shouldBe "clarity-2026-11-10.dump"
        info.kind shouldBe BackupKind.NIGHTLY
        info.sizeBytes shouldBe 7
        val dir = r.dir.resolve("backups")
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
        PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(info.name))) shouldBe "rw-------"
        Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() } shouldContainExactly listOf("clarity-2026-11-10.dump")
    }

    test("a dump that fails leaves no file, and the next one works") {
        val r = rig()
        r.pg.failDump = true
        r.service.backupNightly(LocalDate(2026, 11, 10), wait = false).leftOrNull().shouldBeInstanceOf<BackupError.Failed>()
        Files.list(r.dir.resolve("backups")).use { it.toList() } shouldBe emptyList()
        r.pg.failDump = false
        r.service.backupNightly(LocalDate(2026, 11, 10), wait = false).isRight() shouldBe true
    }

    test("the folder keeps seven nights") {
        val r = rig()
        for (day in 1..9) r.service.backupNightly(LocalDate(2026, 11, day), wait = false)
        r.service.list().backups.map { it.name } shouldContainExactly (9 downTo 3).map { "clarity-2026-11-%02d.dump".format(it) }
    }

    test("a second backup while one runs is told it is busy") {
        val r = rig()
        r.pg.gate = CompletableDeferred()
        val first = CoroutineScope(Dispatchers.Default).async { r.service.backupNightly(LocalDate(2026, 11, 10), wait = false) }
        withTimeout(5.seconds) { while (!r.service.busy) kotlinx.coroutines.delay(10) }
        r.service.backupNow().leftOrNull().shouldBeInstanceOf<BackupError.Busy>()
        r.pg.gate!!.complete(Unit)
        first.await().isRight() shouldBe true
    }

    suspend fun Rig.seedNightly(day: Int, content: String): net.robog.clarity.shared.backup.BackupInfo {
        pg.content = content
        return service.backupNightly(LocalDate(2026, 11, day), wait = false).getOrNull()!!
    }

    fun request(info: net.robog.clarity.shared.backup.BackupInfo, signer: KeyPair = keys, payload: String = RollbackPayload.payload(info.name, info.sha256)) =
        RollbackRequest(info.name, info.sha256, sign(signer, payload))

    test("a signed rollback takes a copy of now first, stops the app, restores and ends the process") {
        val r = rig()
        val target = r.seedNightly(9, "OLD-DATA")
        r.pg.content = "NOW-DATA"
        val accepted = r.service.rollback(phone, request(target)).getOrNull()!!
        accepted.safetyCopy.kind shouldBe BackupKind.BEFORE_ROLLBACK
        accepted.safetyCopy.name shouldBe "clarity-2026-11-10T1405-before-rollback.dump"
        Files.readString(r.dir.resolve("backups").resolve(accepted.safetyCopy.name)) shouldBe "NOW-DATA"
        withTimeout(15.seconds) { r.exited.await() } shouldBe 75
        r.maintenance.active shouldBe true
        r.appScope.isActive shouldBe false
        r.log shouldContainExactly listOf("capture", "kill")
        r.pg.calls shouldContainExactly listOf("dump", "dump", "restore clarity-2026-11-09.dump")
        r.service.list().lastRollback!!.ok shouldBe true
    }

    test("a signature works once and for that file only") {
        val r = rig()
        val a = r.seedNightly(8, "A")
        val b = r.seedNightly(9, "B")
        // signed for A, sent for B
        r.service.rollback(phone, RollbackRequest(b.name, b.sha256, sign(keys, RollbackPayload.payload(a.name, a.sha256)))).leftOrNull()
            .shouldBeInstanceOf<BackupError.BadSignature>()
        // wrong sum: the file is not the one shown
        r.service.rollback(phone, request(a).copy(sha256 = "0".repeat(64))).leftOrNull().shouldBeInstanceOf<BackupError.Changed>()
        // someone else's key
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        r.service.rollback(phone, request(a, signer = other)).leftOrNull().shouldBeInstanceOf<BackupError.BadSignature>()
        r.pg.calls.filter { it.startsWith("restore") } shouldBe emptyList()
        // a good one is accepted; the same signature again is a replay
        val good = request(a)
        r.service.rollback(phone, good).isRight() shouldBe true
        withTimeout(15.seconds) { r.exited.await() }
        r.vault.used.size shouldBe 1
    }

    test("a replayed signature is refused even when the lock is free") {
        val r = rig()
        val a = r.seedNightly(8, "A")
        val good = request(a)
        r.vault.markSignatureUsed(PhoneApprovals.signatureHash(good.signature), "x", phone)
        r.service.rollback(phone, good).leftOrNull().shouldBeInstanceOf<BackupError.Replayed>()
    }

    test("a phone with no approval key cannot roll back, and a name that is not a backup is not found") {
        val r = rig(keyForPhone = null)
        val a = r.seedNightly(8, "A")
        r.service.rollback(phone, request(a)).leftOrNull().shouldBeInstanceOf<BackupError.BadSignature>()
        r.service.rollback(phone, RollbackRequest("../../etc/passwd", "0", "x")).leftOrNull().shouldBeInstanceOf<BackupError.NotFound>()
        r.service.rollback(phone, RollbackRequest("clarity-2026-01-01.dump", "0", "x")).leftOrNull().shouldBeInstanceOf<BackupError.NotFound>()
    }

    test("if the copy of now cannot be made nothing changes: no maintenance, no restore, no exit") {
        val r = rig()
        val a = r.seedNightly(8, "A")
        r.pg.failDump = true
        val refused = r.service.rollback(phone, request(a)).leftOrNull()
        refused.shouldBeInstanceOf<BackupError.Failed>()
        refused.detail shouldNotContain "restore"
        kotlinx.coroutines.delay(300)
        r.maintenance.active shouldBe false
        r.appScope.isActive shouldBe true
        r.log shouldBe emptyList()
        r.exited.isCompleted shouldBe false
        // the lock was released: a backup works again
        r.pg.failDump = false
        r.service.backupNow().isRight() shouldBe true
    }

    test("a restore that fails still ends the process so a fresh backend starts, and says nothing changed") {
        val r = rig()
        val a = r.seedNightly(8, "A")
        r.pg.failRestore = true
        r.service.rollback(phone, request(a)).isRight() shouldBe true
        withTimeout(15.seconds) { r.exited.await() } shouldBe 75
        val result = r.service.list().lastRollback!!
        result.ok shouldBe false
        result.message!! shouldBe "the restore failed. Nothing was changed."
        r.log shouldContainExactly listOf("capture", "kill", "dropCarry")
    }

    test("a rollback while a backup runs is told it is busy and changes nothing") {
        val r = rig()
        val a = r.seedNightly(8, "A")
        r.pg.gate = CompletableDeferred()
        val running = CoroutineScope(Dispatchers.Default).async { r.service.backupNightly(LocalDate(2026, 11, 9), wait = false) }
        withTimeout(5.seconds) { while (!r.service.busy) kotlinx.coroutines.delay(10) }
        r.service.rollback(phone, request(a)).leftOrNull().shouldBeInstanceOf<BackupError.Busy>()
        r.pg.gate!!.complete(Unit)
        running.await()
        r.vault.used.size shouldBe 0
    }

    test("the signed text names the file and its sum") {
        RollbackPayload.payload("clarity-2026-11-09.dump", "ab".repeat(32)) shouldBe "clarity-rollback-v1|clarity-2026-11-09.dump|${"ab".repeat(32)}"
    }
})
