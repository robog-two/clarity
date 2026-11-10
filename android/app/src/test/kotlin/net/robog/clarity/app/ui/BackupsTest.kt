package net.robog.clarity.app.ui

import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.BackupsRepository
import net.robog.clarity.app.testutil.FakeVaultApi
import net.robog.clarity.app.testutil.FakeVaultKeys
import net.robog.clarity.app.testutil.ScriptedAuth
import net.robog.clarity.app.ui.settings.BackupsViewModel
import net.robog.clarity.app.ui.settings.RollbackStage
import net.robog.clarity.app.ui.settings.backupRows
import net.robog.clarity.app.ui.settings.dayText
import net.robog.clarity.app.ui.settings.sizeText
import net.robog.clarity.app.vault.AuthResult
import net.robog.clarity.app.vault.DefaultVaultRepository
import net.robog.clarity.app.vault.RollbackSigned
import net.robog.clarity.app.vault.RollbackSigner
import net.robog.clarity.app.vault.RollbackSigning
import net.robog.clarity.app.vault.VaultKeyStatus
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.backup.BackupInfo
import net.robog.clarity.shared.backup.BackupKind
import net.robog.clarity.shared.backup.BackupList
import net.robog.clarity.shared.backup.RollbackAccepted
import net.robog.clarity.shared.backup.RollbackPayload
import net.robog.clarity.shared.backup.RollbackRequest
import net.robog.clarity.shared.backup.RollbackResult

private val tuesday = BackupInfo("clarity-2026-11-10.dump", BackupKind.NIGHTLY, "2026-11-10", Instant.parse("2026-11-10T07:00:00Z"), 12_400_000, "aa".repeat(32))
private val monday = BackupInfo("clarity-2026-11-09.dump", BackupKind.NIGHTLY, "2026-11-09", Instant.parse("2026-11-09T07:00:00Z"), 830_000, "bb".repeat(32))
private val safety = BackupInfo("clarity-2026-11-10T1405-before-rollback.dump", BackupKind.BEFORE_ROLLBACK, "2026-11-10", Instant.parse("2026-11-10T14:05:00Z"), 12_500_000, "cc".repeat(32))

class BackupsRulesTest {
    @Test fun nightlyRowsAreNamedForTheDayAndShowTheSize() {
        val rows = backupRows(listOf(tuesday, monday), TimeZone.UTC)
        assertEquals(listOf("Tue Nov 10", "Mon Nov 9"), rows.map { it.title })
        assertEquals(listOf("12.4 MB", "830 KB"), rows.map { it.detail })
        assertFalse(rows.any { it.beforeRollback })
    }

    @Test fun aCopyMadeBeforeARollbackIsMarkedAndTimed() {
        val row = backupRows(listOf(safety), TimeZone.UTC).single()
        assertTrue(row.beforeRollback)
        assertEquals("Tue Nov 10 at 2:05 pm", row.title)
        assertEquals("Copy kept before a rollback. 12.5 MB", row.detail)
        // the same instant on a home clock five hours behind
        assertEquals("Tue Nov 10 at 9:05 am", backupRows(listOf(safety), TimeZone.of("America/New_York")).single().title)
    }

    @Test fun datesAndSizesReadPlainly() {
        assertEquals("Sun Mar 1", dayText(LocalDate(2026, 3, 1)))
        assertEquals("999 B", sizeText(999))
        assertEquals("1 KB", sizeText(1_000))
        assertEquals("1.0 MB", sizeText(1_000_000))
    }
}

private class FakeBackups : BackupsRepository {
    var backups = mutableListOf(tuesday, monday)
    var busy = false
    var lastRollback: RollbackResult? = null
    /** Answers the next [down] list calls as unreachable (the server is restarting). */
    var down = 0
    var listError: ApiError? = null
    var rollbackError: ApiError? = null
    val sent = mutableListOf<RollbackRequest>()
    var madeNow = 0
    var onRollback: () -> Unit = {}

    override suspend fun list(): ApiResult<BackupList> {
        if (down > 0) { down--; return ApiResult.Failure(ApiError.Unreachable("restarting")) }
        listError?.let { return ApiResult.Failure(it) }
        return ApiResult.Success(BackupList(backups.toList(), busy, lastRollback))
    }

    override suspend fun backUpNow(): ApiResult<BackupInfo> {
        madeNow++
        return ApiResult.Success(tuesday)
    }

    override suspend fun rollback(request: RollbackRequest): ApiResult<RollbackAccepted> {
        rollbackError?.let { return ApiResult.Failure(it) }
        sent += request
        onRollback()
        return ApiResult.Success(RollbackAccepted(safety))
    }
}

private class FakeSigner(var answer: RollbackSigned? = null) : RollbackSigning {
    val asked = mutableListOf<Pair<BackupInfo, String>>()
    override suspend fun sign(backup: BackupInfo, subtitle: String): RollbackSigned {
        asked += backup to subtitle
        return answer ?: RollbackSigned.Ok(RollbackRequest(backup.name, backup.sha256, "sig"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackupsViewModelTest {
    private fun scenario(block: (dispatcher: kotlinx.coroutines.test.TestDispatcher) -> Unit) {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try { block(dispatcher) } finally { Dispatchers.resetMain() }
    }

    @Test fun theListLoadsNewestFirst() = scenario {
        val vm = BackupsViewModel(FakeBackups(), FakeSigner())
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("Tue Nov 10", "Mon Nov 9"), state.rows.map { it.title })
        assertEquals(RollbackStage.Idle, state.stage)
    }

    @Test fun aServerWithoutBackupsSaysSo() = scenario {
        val server = FakeBackups().also { it.listError = ApiError.NotFound(null) }
        val state = BackupsViewModel(server, FakeSigner()).state.value
        assertFalse(state.available)
        assertNull(state.message)
    }

    @Test fun backUpNowAsksTheServerAndReloads() = scenario {
        val server = FakeBackups()
        val vm = BackupsViewModel(server, FakeSigner())
        server.backups.add(0, tuesday.copy(name = "clarity-2026-11-11.dump", date = "2026-11-11"))
        vm.backUpNow()
        assertEquals(1, server.madeNow)
        assertFalse(vm.state.value.backingUp)
        assertEquals("Wed Nov 11", vm.state.value.rows.first().title)
    }

    @Test fun aRollbackAsksThenSignsThenWaitsThenReconnects() = scenario { dispatcher ->
        val server = FakeBackups()
        val signer = FakeSigner()
        val vm = BackupsViewModel(server, signer)

        vm.askRollback(tuesday.name)
        val confirm = assertIs<RollbackStage.Confirm>(vm.state.value.stage)
        assertEquals("Tue Nov 10", confirm.row.title)
        assertTrue(signer.asked.isEmpty(), "nothing is signed before the person says yes")

        // The server takes the request, goes away, restores, comes back with a new result.
        server.onRollback = { server.down = 3 }
        vm.confirmRollback()
        assertEquals("Roll back to Tue Nov 10", signer.asked.single().second)
        assertEquals(RollbackRequest(tuesday.name, tuesday.sha256, "sig"), server.sent.single())
        assertIs<RollbackStage.Waiting>(vm.state.value.stage)

        dispatcher.scheduler.advanceTimeBy(4_000)
        assertIs<RollbackStage.Waiting>(vm.state.value.stage) // still down or still the old result

        server.lastRollback = RollbackResult(tuesday.name, Instant.parse("2026-11-10T19:10:00Z"), ok = true)
        dispatcher.scheduler.advanceTimeBy(20_000)
        val done = assertIs<RollbackStage.Finished>(vm.state.value.stage)
        assertTrue(done.ok)
        assertEquals("Rolled back to Tue Nov 10", done.title)

        vm.dismissResult()
        assertEquals(RollbackStage.Idle, vm.state.value.stage)
    }

    @Test fun cancellingTheSheetOrThePromptSendsNothing() = scenario {
        val server = FakeBackups()
        val signer = FakeSigner(RollbackSigned.No("Not approved."))
        val vm = BackupsViewModel(server, signer)
        vm.askRollback(monday.name)
        vm.cancelRollback()
        assertEquals(RollbackStage.Idle, vm.state.value.stage)

        vm.askRollback(monday.name)
        vm.confirmRollback()
        assertEquals(RollbackStage.Idle, vm.state.value.stage)
        assertEquals("Not approved.", vm.state.value.message)
        assertTrue(server.sent.isEmpty())
    }

    @Test fun aRefusedRollbackIsToldInTheServersWords() = scenario {
        val server = FakeBackups().also {
            it.rollbackError = ApiError.Forbidden(Problem(title = "Approval not verified", status = 403, detail = "that signature was already used"))
        }
        val vm = BackupsViewModel(server, FakeSigner())
        vm.askRollback(tuesday.name)
        vm.confirmRollback()
        assertEquals(RollbackStage.Idle, vm.state.value.stage)
        assertEquals("that signature was already used", vm.state.value.message)
    }

    @Test fun aRestoreThatFailedIsToldAsNothingChanged() = scenario { dispatcher ->
        val server = FakeBackups()
        val vm = BackupsViewModel(server, FakeSigner())
        vm.askRollback(tuesday.name)
        server.onRollback = { server.lastRollback = RollbackResult(tuesday.name, Instant.parse("2026-11-10T19:10:00Z"), ok = false, message = "The restore failed. Nothing was changed.") }
        vm.confirmRollback()
        dispatcher.scheduler.advanceTimeBy(3_000)
        val done = assertIs<RollbackStage.Finished>(vm.state.value.stage)
        assertFalse(done.ok)
        assertEquals("Couldn’t roll back", done.title)
        assertEquals("The restore failed. Nothing was changed.", done.detail)
    }

    @Test fun aServerThatNeverComesBackStopsTheWait() = scenario { dispatcher ->
        val server = FakeBackups()
        val vm = BackupsViewModel(server, FakeSigner())
        vm.askRollback(tuesday.name)
        server.onRollback = { server.down = Int.MAX_VALUE }
        vm.confirmRollback()
        dispatcher.scheduler.advanceTimeBy(4.minutes.inWholeMilliseconds)
        val done = assertIs<RollbackStage.Finished>(vm.state.value.stage)
        assertFalse(done.ok)
        assertEquals("Still restarting", done.title)
    }

    @Test fun onlyOneRollbackAtATime() = scenario {
        val server = FakeBackups()
        val vm = BackupsViewModel(server, FakeSigner())
        vm.askRollback(tuesday.name)
        vm.askRollback(monday.name)
        assertEquals(tuesday.name, assertIs<RollbackStage.Confirm>(vm.state.value.stage).row.name)
        vm.backUpNow() // not while a sheet is up
        assertEquals(0, server.madeNow)
    }
}

class RollbackSignerTest {
    private val keys = FakeVaultKeys()
    private val api = FakeVaultApi()
    private val vault = DefaultVaultRepository(api, keys)

    private fun verifies(signature: String, payload: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.approval.public)
            update(payload.toByteArray())
            verify(Base64.getUrlDecoder().decode(signature))
        }

    @Test fun theSignatureCoversTheFileNameAndItsSum() = runTest {
        val auth = ScriptedAuth()
        val signed = assertIs<RollbackSigned.Ok>(RollbackSigner(keys, auth, vault).sign(tuesday, "Roll back to Tue Nov 10"))
        assertEquals(tuesday.name, signed.request.name)
        assertEquals(tuesday.sha256, signed.request.sha256)
        assertTrue(verifies(signed.request.signature, RollbackPayload.payload(tuesday.name, tuesday.sha256)))
        // another file's text is not covered
        assertFalse(verifies(signed.request.signature, RollbackPayload.payload(monday.name, monday.sha256)))
        // the prompt is the biometric one, bound to the signing key, and the key was registered first
        assertEquals(listOf(true), auth.boundToSignature)
        assertEquals("Roll back to Tue Nov 10", auth.prompts.single().subtitle)
        assertEquals(1, api.registered.size)
    }

    @Test fun cancellingThePromptSignsNothing() = runTest {
        val no = assertIs<RollbackSigned.No>(RollbackSigner(keys, ScriptedAuth(AuthResult.Cancelled), vault).sign(tuesday, "x"))
        assertEquals("Not approved.", no.message)
    }

    @Test fun resetKeysAreToldPlainly() = runTest {
        keys.status = VaultKeyStatus.INVALIDATED
        val no = assertIs<RollbackSigned.No>(RollbackSigner(keys, ScriptedAuth(), vault).sign(tuesday, "x"))
        assertEquals("Vault keys were reset. Go to Settings, then Vault.", no.message)
    }
}
