package net.robog.clarity.app.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.IngestApi
import net.robog.clarity.app.testutil.FakeCapabilities
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.action.ConnectorAction
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.IngestResponse
import net.robog.clarity.shared.ingest.RecordOutcome
import net.robog.clarity.shared.ingest.RecordResult
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload
import net.robog.clarity.shared.noun.Noun

private val account = phoneAccount("dev-1")

private fun sms(id: Long, type: Int = SMS_INBOX, address: String = "+15550101", body: String = "hello $id", thread: Long? = 7) =
    SmsRow(id, address, body, T0.toEpochMilliseconds() + id * 1000, type, thread)

class SmsMappingTest {
    @Test fun receivedMessageIsFromTheOtherPartyToThePhone() {
        val record = smsToRecord(sms(5), account, T0)!!
        val payload = record.payload as MessagePayload
        assertEquals(MessagePayload.Channel.SMS, payload.channel)
        assertEquals(Handle.phone("+15550101"), payload.sender)
        assertEquals(listOf(account), payload.recipients)
        assertEquals(account, payload.account)
        assertEquals("hello 5", payload.body)
        assertEquals("sms-thread:7", payload.threadRef)
        assertEquals(Instant.fromEpochMilliseconds(T0.toEpochMilliseconds() + 5000), payload.sentAt)
        assertEquals(Noun.MESSAGE, payload.noun)
    }

    @Test fun sentMessageIsFromThePhoneToTheOtherParty() {
        val payload = smsToRecord(sms(6, type = SMS_SENT), account, T0)!!.payload as MessagePayload
        assertEquals(account, payload.sender)
        assertEquals(listOf(Handle.phone("+15550101")), payload.recipients)
    }

    @Test fun idempotencyKeyIsTheSmsRowId() {
        val record = smsToRecord(sms(42), account, T0)!!
        assertEquals(SourceId("sms:42"), record.sourceId)
        assertEquals("1", record.sourceVersion)
        assertEquals(T0, record.observedAt)
    }

    @Test fun draftsOutboxAndBlankAddressesAreSkipped() {
        assertNull(smsToRecord(sms(1, type = 3), account, T0))
        assertNull(smsToRecord(sms(2, address = "  "), account, T0))
    }

    @Test fun batchesRespectTheServerLimitAndReportTheirNewestId() {
        val rows = (1L..1203L).map { sms(it) }.shuffled()
        val batches = smsBatches(rows, account, T0)
        assertEquals(listOf(500, 500, 203), batches.map { it.first.records.size })
        assertEquals(listOf(500L, 1000L, 1203L), batches.map { it.second })
    }

    @Test fun cursorStaysPutOnlyWhenEverythingWasRefused() {
        val ok = RecordResult(SourceId("a"), RecordOutcome.Removed)
        val no = RecordResult(SourceId("b"), RecordOutcome.Rejected("noun not granted"))
        assertTrue(cursorMayAdvance(listOf(ok, no)))
        assertFalse(cursorMayAdvance(listOf(no, no)))
        assertTrue(cursorMayAdvance(emptyList()))
    }
}

private class FakeIngest : IngestApi {
    val manifests = mutableListOf<ConnectorManifest>()
    val batches = mutableListOf<IngestBatch>()
    val results = mutableListOf<Pair<ActionId, ActionResult>>()
    var pending = ArrayDeque<ConnectorAction>()
    var pushFailure: ApiError? = null
    var rejectAll = false
    var pollFailure: ApiError? = null

    override suspend fun putManifest(manifest: ConnectorManifest): ApiResult<Unit> { manifests += manifest; return ApiResult.Success(Unit) }

    override suspend fun pushBatch(batch: IngestBatch): ApiResult<IngestResponse> {
        pushFailure?.let { return ApiResult.Failure(it) }
        batches += batch
        return ApiResult.Success(
            IngestResponse(batch.records.map { RecordResult(it.sourceId, if (rejectAll) RecordOutcome.Rejected("not granted") else RecordOutcome.Removed) }),
        )
    }

    override suspend fun pollActions(waitSeconds: Int): ApiResult<ActionPoll> {
        pollFailure?.let { return ApiResult.Failure(it) }
        val taken = pending.toList()
        pending.clear()
        return ApiResult.Success(ActionPoll(taken))
    }

    override suspend fun reportResult(id: ActionId, result: ActionResult): ApiResult<Unit> { results += id to result; return ApiResult.Success(Unit) }
}

private class FakeSmsSource(var rows: List<SmsRow>) : SmsSource {
    override suspend fun readAfter(afterId: Long, limit: Int) = rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
}

private class MemoryCursor(var id: Long = 0) : SmsCursorStore {
    override suspend fun lastId() = id
    override suspend fun advance(to: Long, at: Instant) { id = to; last.value = at }
    val last = MutableStateFlow<Instant?>(null)
    override val lastSyncAt: Flow<Instant?> = last
}

private class FakeDnd(var access: Boolean = true, var filter: Int = ALL) : DndController {
    var enabledStarred: Boolean? = null
    var restored: Int? = null
    override fun hasAccess() = access
    override fun currentFilter() = filter
    override fun enable(allowStarred: Boolean) { enabledStarred = allowStarred; filter = if (allowStarred) PRIORITY else ALARMS }
    override fun restore(filter: Int) { restored = filter; this.filter = filter }
    companion object { const val ALL = 1; const val PRIORITY = 2; const val ALARMS = 4 }
}

private class MemoryDndState : DndStateStore {
    var state: DndState? = null
    override suspend fun load() = state
    override suspend fun save(state: DndState) { this.state = state }
    override suspend fun clear() { state = null }
}

private class FakeDndAlarms : DndAlarms {
    var at: Instant? = null
    override fun scheduleEnd(at: Instant) { this.at = at }
    override fun cancelEnd() { at = null }
}

private fun dnd(until: Instant, allow: List<Handle> = emptyList()) =
    ProposedAction.SetDoNotDisturb("k-dnd", "mute Slack until 12", until, allow)

class DndTest {
    private val clock = FixedClock(T0)
    private val controller = FakeDnd()
    private val state = MemoryDndState()
    private val alarms = FakeDndAlarms()
    private val handler = DndActionHandler(controller, state, alarms, clock)

    @Test fun planIsStarredOnlyWhenPeopleMayGetThrough() {
        assertEquals(DndPlan.Enable(T0 + 3.hours, false), dndPlanFor(dnd(T0 + 3.hours), T0))
        assertEquals(DndPlan.Enable(T0 + 3.hours, true), dndPlanFor(dnd(T0 + 3.hours, listOf(Handle.phone("+1"))), T0))
        assertEquals(DndPlan.AlreadyOver, dndPlanFor(dnd(T0), T0))
    }

    @Test fun turnsDoNotDisturbOnAndSchedulesTheEnd() = runBlocking {
        val outcome = handler.handle(dnd(T0 + 3.hours))
        assertIs<ActionOutcome.Done>(outcome)
        assertEquals(false, controller.enabledStarred)
        assertEquals(T0 + 3.hours, alarms.at)
        assertEquals(DndState(T0 + 3.hours, FakeDnd.ALL), state.state)
    }

    @Test fun anAllowListMeansStarredContactsAndSaysSo() = runBlocking {
        val outcome = handler.handle(dnd(T0 + 3.hours, listOf(Handle.phone("+15550101"))))
        assertEquals(true, controller.enabledStarred)
        assertTrue((outcome as ActionOutcome.Done).content!!.contains("starred"))
    }

    @Test fun withoutAccessItFailsWithoutRetryAndChangesNothing() = runBlocking {
        controller.access = false
        val outcome = handler.handle(dnd(T0 + 3.hours))
        assertIs<ActionOutcome.Failed>(outcome)
        assertFalse(outcome.retryable)
        assertNull(controller.enabledStarred)
        assertNull(state.state)
    }

    @Test fun aWindowThatAlreadyEndedDoesNothing() = runBlocking {
        val outcome = handler.handle(dnd(T0 - 1.hours))
        assertIs<ActionOutcome.Done>(outcome)
        assertNull(controller.enabledStarred)
    }

    @Test fun otherActionKindsAreRefused() = runBlocking {
        val email = ProposedAction.SendEmail("k", "s", EmailDraft(to = emptyList(), subject = "x", body = "y"))
        assertIs<ActionOutcome.Failed>(handler.handle(email))
        Unit
    }

    @Test fun aSecondWindowKeepsTheOriginalSettingToGoBackTo() = runBlocking {
        handler.handle(dnd(T0 + 1.hours))
        handler.handle(dnd(T0 + 2.hours))
        assertEquals(FakeDnd.ALL, state.state!!.previousFilter)
        assertEquals(T0 + 2.hours, state.state!!.until)
    }

    @Test fun turnsItOffAgainWhenTheWindowEnds() = runBlocking {
        handler.handle(dnd(T0 + 1.hours))
        clock.now = T0 + 2.hours
        handler.endIfDue()
        assertEquals(FakeDnd.ALL, controller.restored)
        assertNull(state.state)
        assertNull(alarms.at)
    }

    @Test fun beforeTheEndItJustRearmsTheAlarm() = runBlocking {
        handler.handle(dnd(T0 + 3.hours))
        alarms.at = null
        clock.now = T0 + 1.hours
        handler.endIfDue()
        assertNull(controller.restored)
        assertEquals(T0 + 3.hours, alarms.at)
    }

    @Test fun nothingToEndDoesNothing() = runBlocking {
        handler.endIfDue()
        assertNull(controller.restored)
    }
}

private class NoWork : ConnectorWork {
    var scheduled = 0
    override fun schedule() { scheduled++ }
}

private class ConnectorRig(rows: List<SmsRow> = emptyList(), smsGranted: Boolean = true, signedInUser: Boolean = true) {
    val ingest = FakeIngest()
    val cursor = MemoryCursor()
    val clock = FixedClock(T0)
    val controller = FakeDnd()
    val dndState = MemoryDndState()
    val dnd = DndActionHandler(controller, dndState, FakeDndAlarms(), clock)
    val work = NoWork()
    val connector = DefaultPhoneConnector(
        ingest, FakeSmsSource(rows), cursor, FakeCapabilities(sms = smsGranted),
        FakeSessionStore(if (signedInUser) signedIn() else null), dnd, clock, work,
    )
}

class PhoneConnectorTest {
    @Test fun manifestAnnouncesMessagesAndDoNotDisturbOnly() {
        val m = phoneManifest("1")
        assertEquals(setOf(Noun.MESSAGE), m.produces)
        assertEquals(setOf(ActionKind.SET_DO_NOT_DISTURB), m.handles)
        assertEquals("phone", m.kind)
    }

    @Test fun syncPushesNewMessagesAndAdvancesTheCursor() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        assertEquals(3, r.connector.syncOnce())
        assertEquals(1, r.ingest.batches.size)
        assertEquals(3L, r.cursor.id)
        assertEquals(T0, r.cursor.last.value)
        // Nothing new the next time.
        assertEquals(0, r.connector.syncOnce())
        assertEquals(1, r.ingest.batches.size)
    }

    @Test fun onlyMessagesAfterTheCursorAreSent() = runBlocking {
        val r = ConnectorRig((1L..5L).map { sms(it) })
        r.cursor.id = 3
        r.connector.syncOnce()
        val sent = r.ingest.batches.single().records.map { (it as IngestRecord.Upsert).sourceId.value }
        assertEquals(listOf("sms:4", "sms:5"), sent)
    }

    @Test fun failedPushKeepsTheCursorSoItRetries() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        r.ingest.pushFailure = ApiError.Unreachable("down")
        assertEquals(0, r.connector.syncOnce())
        assertEquals(0L, r.cursor.id)
    }

    @Test fun refusedBecauseNotGrantedKeepsTheCursor() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        r.ingest.rejectAll = true
        r.connector.syncOnce()
        assertEquals(0L, r.cursor.id)
    }

    @Test fun largeBacklogIsSentInSeveralBatches() = runBlocking {
        val r = ConnectorRig((1L..1100L).map { sms(it) })
        assertEquals(1100, r.connector.syncOnce())
        assertEquals(listOf(500, 500, 100), r.ingest.batches.map { it.records.size })
        assertEquals(1100L, r.cursor.id)
    }

    @Test fun noSmsPermissionOrNoServerMeansNoSync() = runBlocking {
        assertEquals(0, ConnectorRig(listOf(sms(1)), smsGranted = false).connector.syncOnce())
        assertEquals(0, ConnectorRig(listOf(sms(1)), signedInUser = false).connector.syncOnce())
    }

    @Test fun actionLoopRunsDoNotDisturbAndReportsTheResult() = runBlocking {
        val r = ConnectorRig()
        r.ingest.pending.add(ConnectorAction(ActionId("a1"), dnd(T0 + 3.hours), createdAt = T0))
        assertEquals(1, r.connector.actionPass(0))
        val (id, result) = r.ingest.results.single()
        assertEquals(ActionId("a1"), id)
        assertIs<ActionOutcome.Done>(result.outcome)
        assertEquals(false, r.controller.enabledStarred)
    }

    @Test fun refusedActionIsReportedAsFailed() = runBlocking {
        val r = ConnectorRig()
        r.controller.access = false
        r.ingest.pending.add(ConnectorAction(ActionId("a2"), dnd(T0 + 3.hours), createdAt = T0))
        r.connector.actionPass(0)
        assertIs<ActionOutcome.Failed>(r.ingest.results.single().second.outcome)
        Unit
    }

    @Test fun pollFailureIsReportedAsMinusOne() = runBlocking {
        val r = ConnectorRig()
        r.ingest.pollFailure = ApiError.NotFound(null)
        assertEquals(-1, r.connector.actionPass(0))
    }

    @Test fun aBackgroundPassAnnouncesSyncsAndPolls() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)))
        r.ingest.pending.add(ConnectorAction(ActionId("a3"), dnd(T0 + 1.hours), createdAt = T0))
        r.connector.runPass()
        assertEquals(1, r.ingest.manifests.size)
        assertEquals(1, r.ingest.batches.size)
        assertEquals(1, r.ingest.results.size)
    }

    @Test fun scheduleDelegatesToWorkManager() {
        val r = ConnectorRig()
        r.connector.schedule()
        assertEquals(1, r.work.scheduled)
    }
}
