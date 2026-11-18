package net.robog.clarity.app.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.net.IngestApi
import net.robog.clarity.app.testutil.FakeCapabilities
import net.robog.clarity.app.testutil.FakeSessionStore
import net.robog.clarity.app.testutil.FixedClock
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.testutil.signedIn
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.action.ConnectorAction
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.connector.GrantsUpdate
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

    @Test fun idempotencyKeyIsStablePerTextNotPerRowId() {
        val record = smsToRecord(sms(42), account, T0)!!
        val date = T0.toEpochMilliseconds() + 42_000
        assertEquals(SourceId("sms:v2:$date:${smsFingerprint(sms(42))}"), record.sourceId)
        assertEquals(16, smsFingerprint(sms(42)).length)
        assertEquals("1", record.sourceVersion)
        assertEquals(T0, record.observedAt)
        // A restore renumbers the row: the same text keeps its id.
        assertEquals(record.sourceId, smsToRecord(sms(42).copy(id = 3), account, T0)!!.sourceId)
        assertEquals(SourceId("sms:42"), legacySmsSourceId(sms(42)), "what v1 uploaded")
    }

    @Test fun aDifferentTextUnderTheSameRowIdGetsItsOwnId() {
        val before = smsSourceId(sms(9))
        assertTrue(before != smsSourceId(sms(9, body = "something else")))
        assertTrue(before != smsSourceId(sms(9, type = SMS_SENT)), "sent and received are different texts")
        assertTrue(before != smsSourceId(sms(9, address = "+15550199")))
        assertTrue(before != smsSourceId(sms(9).copy(dateMillis = 1)), "the same words another time")
    }

    @Test fun alphanumericSendersKeepTheirName() {
        val payload = smsToRecord(sms(3, address = "AMAZON"), account, T0)!!.payload as MessagePayload
        assertEquals(Handle(Handle.Kind.OTHER, "AMAZON", "AMAZON"), payload.sender)
        assertEquals(Handle.phone("+1 555 0101"), smsParty(" +1 555 0101 "))
    }

    @Test fun theConnectorIdIsLowercaseLikeTheServers() {
        assertEquals("device-abc-1", phoneConnectorId("ABC-1"))
    }

    @Test fun draftsAndPendingRowsAreNotUploadedYet() {
        assertNull(smsToRecord(sms(1, type = 3), account, T0))
        assertNull(smsToRecord(sms(2, type = SMS_OUTBOX), account, T0))
    }

    @Test fun aBlankAddressIsUploadedFromAnUnknownSender() {
        val received = smsToRecord(sms(2, address = "  "), account, T0)!!.payload as MessagePayload
        assertEquals(UNKNOWN_SENDER, received.sender)
        val sent = smsToRecord(sms(3, type = SMS_SENT, address = ""), account, T0)!!.payload as MessagePayload
        assertEquals(listOf(UNKNOWN_SENDER), sent.recipients)
    }

    @Test fun batchesRespectTheServerLimitAndReportTheirNewestId() {
        val rows = (1L..1203L).map { sms(it) }.shuffled()
        val chunks = outgoingChunks(smsPage(rows, account, T0).items)
        assertEquals(listOf(500, 500, 203), chunks.map { it.size })
        assertEquals(listOf(500L, 1000L, 1203L), chunks.map { it.last().id })
    }

    @Test fun numbersGoUpAsTheNumberStoreHasThemAndTheIdIsMadeFromTheRawAddress() {
        assertEquals(Handle.phone("5551234567"), smsParty("5551234567"))
        assertEquals(Handle(Handle.Kind.OTHER, "AMAZON", "AMAZON"), smsParty("AMAZON"), "names are not numbers")
        val payload = smsToRecord(sms(1, address = "5551234567"), account, T0)!!.payload as MessagePayload
        assertEquals(smsParty("5551234567"), payload.sender)
        assertEquals("sms:v2:${sms(1).dateMillis}:${smsFingerprint(sms(1))}", smsSourceId(sms(1)).value)
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
    var rejectIds = emptySet<SourceId>()
    var pollFailure: ApiError? = null
    var manifestFailure: ApiError? = null
    /** The server grants messages when it gets the manifest (as the backend does for a device's own connector). */
    var grantedOnManifest = true
    var granted = false
    val grants = mutableListOf<Pair<ConnectorId, GrantsUpdate>>()

    override suspend fun putManifest(manifest: ConnectorManifest): ApiResult<Unit> {
        manifestFailure?.let { return ApiResult.Failure(it) }
        manifests += manifest
        if (grantedOnManifest) granted = true
        return ApiResult.Success(Unit)
    }

    override suspend fun putGrants(id: ConnectorId, update: GrantsUpdate): ApiResult<Unit> {
        grants += id to update
        if (update.grants[Noun.MESSAGE] == GrantState.GRANTED) granted = true
        return ApiResult.Success(Unit)
    }

    override suspend fun pushBatch(batch: IngestBatch): ApiResult<IngestResponse> {
        pushFailure?.let { return ApiResult.Failure(it) }
        batches += batch
        return ApiResult.Success(
            IngestResponse(batch.records.map { RecordResult(it.sourceId, if (rejectAll || !granted || it.sourceId in rejectIds) RecordOutcome.Rejected("not granted") else RecordOutcome.Removed) }),
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
    var reads = 0
    var failure: Exception? = null
    override suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow> {
        failure?.let { throw it }
        reads++
        return rows.filter { it.id > afterId && it.type in SMS_READ_TYPES }.sortedBy { it.id }.take(limit)
    }
    override suspend fun readSince(sinceMillis: Long, limit: Int): List<SmsRow> {
        failure?.let { throw it }
        return rows.filter { it.dateMillis >= sinceMillis && it.type in SMS_READ_TYPES }.sortedByDescending { it.dateMillis }.take(limit)
    }
    override suspend fun rowAt(id: Long) = rows.firstOrNull { it.id == id && it.type in setOf(SMS_INBOX, SMS_SENT) }
    override suspend fun maxId() = rows.maxOfOrNull { it.id }
}

private class FakeMms(var rows: List<MmsRow> = emptyList()) : MmsSource {
    var failure: Exception? = null
    override suspend fun readAfter(afterId: Long, limit: Int): List<MmsRow> {
        failure?.let { throw it }
        return rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
    }
    override suspend fun readSince(sinceMillis: Long, limit: Int): List<MmsRow> =
        rows.filter { it.dateMillis >= sinceMillis }.sortedByDescending { it.dateMillis }.take(limit)
    override suspend fun maxId() = rows.maxOfOrNull { it.id }
}

private class MemoryCursor(var id: Long = 0, var anchor: SmsAnchor? = null) : SmsCursorStore {
    var mmsId = 0L
    var overlap = 0L
    val recent = linkedSetOf<String>()
    override suspend fun mmsLastId() = mmsId
    override suspend fun advanceMms(to: Long, sent: Int) { mmsId = to; state.value = state.value.copy(sent = state.value.sent + sent) }
    override suspend fun restartMms() { mmsId = 0 }
    override suspend fun overlapAt() = overlap
    override suspend fun markOverlap(at: Long) { overlap = at }
    override suspend fun recentIds(): Set<String> = recent.toSet()
    override suspend fun remember(ids: Collection<String>) { recent += ids }
    val state = MutableStateFlow(SmsSyncStatus())
    var restarts = 0
    override suspend fun lastId() = id
    override suspend fun anchor() = anchor
    override suspend fun adopt(anchor: SmsAnchor) { this.anchor = anchor }
    override suspend fun restart() { restarts++; id = 0; anchor = null }
    override suspend fun clear() { id = 0; anchor = null; state.value = SmsSyncStatus() }
    override suspend fun advance(to: Long, sent: Int, anchor: SmsAnchor?) { id = to; this.anchor = anchor; state.value = state.value.copy(sent = state.value.sent + sent) }
    override suspend fun finish(at: Instant, problem: SmsSyncProblem?) {
        state.value = state.value.copy(lastAttemptAt = at, problem = problem, lastSyncAt = if (problem == null) at else state.value.lastSyncAt)
    }
    override val status: Flow<SmsSyncStatus> = state
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
    val soon = mutableListOf<Long>()
    override fun schedule() { scheduled++ }
    override fun syncSoon(delaySeconds: Long) { soon += delaySeconds }
}

private class ConnectorRig(rows: List<SmsRow> = emptyList(), smsGranted: Boolean = true, signedInUser: Boolean = true, mmsRows: List<MmsRow> = emptyList(), readSize: Int = 500, maxReads: Int = 20) {
    val source = FakeSmsSource(rows)
    val mms = FakeMms(mmsRows)
    val ingest = FakeIngest()
    val cursor = MemoryCursor()
    val clock = FixedClock(T0)
    val controller = FakeDnd()
    val dndState = MemoryDndState()
    val dnd = DndActionHandler(controller, dndState, FakeDndAlarms(), clock)
    val work = NoWork()
    val connector = DefaultPhoneConnector(
        ingest, source, cursor, FakeCapabilities(sms = smsGranted),
        FakeSessionStore(if (signedInUser) signedIn() else null), dnd, clock, work, mms,
        readSize = readSize, maxReads = maxReads,
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
        assertEquals(SmsSyncStatus(lastSyncAt = T0, lastAttemptAt = T0, sent = 3, problem = null), r.cursor.state.value)
        // Nothing new the next time.
        assertEquals(0, r.connector.syncOnce())
        assertEquals(1, r.ingest.batches.size)
    }

    @Test fun onlyMessagesAfterTheCursorAreSent() = runBlocking {
        val r = ConnectorRig((1L..5L).map { sms(it) })
        r.cursor.id = 3
        r.cursor.overlap = T0.toEpochMilliseconds() // the recent window was read just now
        r.connector.syncOnce()
        val sent = r.ingest.batches.single().records.map { (it as IngestRecord.Upsert).sourceId }
        assertEquals(listOf(smsSourceId(sms(4)), smsSourceId(sms(5))), sent)
    }

    @Test fun failedPushKeepsTheCursorSoItRetries() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        r.ingest.pushFailure = ApiError.Unreachable("down")
        assertEquals(0, r.connector.syncOnce())
        assertEquals(0L, r.cursor.id)
        assertEquals(SmsSyncProblem.UNREACHABLE, r.cursor.state.value.problem)
        assertNull(r.cursor.state.value.lastSyncAt)
        // Back online: it all goes, and the problem clears.
        r.ingest.pushFailure = null
        assertEquals(3, r.connector.syncOnce())
        assertNull(r.cursor.state.value.problem)
    }

    @Test fun refusedBecauseNotGrantedKeepsTheCursor() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        r.ingest.rejectAll = true
        r.connector.syncOnce()
        assertEquals(0L, r.cursor.id)
        assertEquals(SmsSyncProblem.NOT_ALLOWED, r.cursor.state.value.problem)
    }

    @Test fun theFirstSyncAnnouncesThePhoneSoTheServerAllowsItsMessages() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        assertEquals(3, r.connector.syncOnce())
        assertEquals(1, r.ingest.manifests.size)
        // once is enough while the app runs
        r.connector.syncOnce()
        assertEquals(1, r.ingest.manifests.size)
    }

    @Test fun refusedMessagesAnnounceAgainNextTimeAndThenGoThrough() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        r.ingest.grantedOnManifest = false
        assertEquals(0, r.connector.syncOnce())
        assertEquals(0L, r.cursor.id)
        // a newer server grants on the manifest: the next sync announces again and everything goes
        r.ingest.grantedOnManifest = true
        assertEquals(3, r.connector.syncOnce())
        assertEquals(2, r.ingest.manifests.size)
        assertEquals(3L, r.cursor.id)
    }

    @Test fun enablingMessagesGrantsThemOnTheServerAndSyncs() = runBlocking {
        val r = ConnectorRig((1L..2L).map { sms(it) })
        r.ingest.grantedOnManifest = false // an older server: only an explicit grant helps
        assertEquals(2, r.connector.enableMessages())
        val (id, update) = r.ingest.grants.single()
        assertEquals(ConnectorId(phoneConnectorId(signedIn().deviceId.value)), id)
        assertEquals(mapOf(Noun.MESSAGE to GrantState.GRANTED), update.grants)
        assertEquals(2L, r.cursor.id)
    }

    @Test fun anUnreachableServerOnTheManifestIsReportedAndNothingIsRead() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)))
        r.ingest.manifestFailure = ApiError.Unreachable("offline")
        assertEquals(0, r.connector.syncOnce())
        assertEquals(0, r.source.reads)
        assertEquals(SmsSyncProblem.UNREACHABLE, r.cursor.state.value.problem)
    }

    @Test fun aFailingSmsStoreIsReportedNotThrown() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)))
        r.source.failure = IllegalArgumentException("bad query")
        assertEquals(0, r.connector.syncOnce())
        assertEquals(SmsSyncProblem.READ_FAILED, r.cursor.state.value.problem)
    }

    @Test fun nothingNewStillCountsAsSynced() = runBlocking {
        val r = ConnectorRig()
        assertEquals(0, r.connector.syncOnce())
        assertEquals(T0, r.cursor.state.value.lastSyncAt)
        assertNull(r.cursor.state.value.problem)
    }

    @Test fun aHugeInboxIsReadInPagesAndContinuesOnTheNextPass() = runBlocking {
        val r = ConnectorRig((1L..10_250L).map { sms(it) })
        r.connector.syncOnce() // 20 reads of 500 per pass
        assertEquals(10_000L, r.cursor.id)
        assertEquals(20, r.source.reads)
        r.connector.syncOnce()
        assertEquals(10_250L, r.cursor.id)
        // Whatever the recent window caught on the first pass is not sent twice.
        val sent = r.ingest.batches.flatMap { b -> b.records.map { it.sourceId } }
        assertEquals(10_250, sent.size)
        assertEquals(10_250, sent.toSet().size)
    }

    @Test fun onlyAcceptedRecordsAreCounted() {
        val ok = RecordResult(SourceId("a"), RecordOutcome.Removed)
        val no = RecordResult(SourceId("b"), RecordOutcome.Rejected("invalid"))
        assertEquals(1, acceptedCount(listOf(ok, no)))
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

class DataStoreSmsCursorStoreTest {
    @Test fun keepsTheCursorCountAndLastProblem() = runBlocking {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "sms-cursor-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreSmsCursorStore(
                PreferenceDataStoreFactory.create(scope = scope) { java.io.File(dir, "c.preferences_pb") },
            )
            assertEquals(0L, store.lastId())
            assertEquals(SmsSyncStatus(), store.status.first())
            store.advance(500, 498)
            store.advance(700, 200)
            store.finish(T0, null)
            assertEquals(700L, store.lastId())
            assertEquals(SmsSyncStatus(lastSyncAt = T0, lastAttemptAt = T0, sent = 698, problem = null), store.status.first())
            store.finish(T0 + 1.hours, SmsSyncProblem.UNREACHABLE)
            assertEquals(
                SmsSyncStatus(lastSyncAt = T0, lastAttemptAt = T0 + 1.hours, sent = 698, problem = SmsSyncProblem.UNREACHABLE),
                store.status.first(),
            )
            store.finish(T0 + 2.hours, null)
            assertNull(store.status.first().problem)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun keepsTheAnchorRestartsAndForgetsEverythingOnClear() = runBlocking {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "sms-anchor-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreSmsCursorStore(
                PreferenceDataStoreFactory.create(scope = scope) { java.io.File(dir, "c.preferences_pb") },
            )
            val anchor = anchorOf(sms(9))
            assertNull(store.anchor())
            store.advance(9, 9, anchor)
            store.finish(T0, null)
            assertEquals(anchor, store.anchor())
            store.restart()
            assertEquals(0L, store.lastId())
            assertNull(store.anchor())
            assertEquals(SmsSyncStatus(lastSyncAt = T0, lastAttemptAt = T0, sent = 9), store.status.first(), "a rescan keeps the status")
            store.advance(3, 3, anchorOf(sms(3)))
            store.finish(T0 + 1.hours, SmsSyncProblem.UNREACHABLE)
            store.clear()
            assertEquals(0L, store.lastId())
            assertNull(store.anchor())
            assertEquals(SmsSyncStatus(), store.status.first(), "forgetting the server resets the sync status")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

/** A backup restore or a new phone renumbers the SMS store; the sync must neither skip nor drop texts. */
class SmsRenumberingTest {
    private val anchor9 = anchorOf(sms(9))

    @Test fun theCursorIsCheckedAgainstTheTextItStandsOn() {
        assertEquals(CursorCheck.Keep, checkCursor(0, null, null, null), "nothing synced yet")
        assertEquals(CursorCheck.Keep, checkCursor(9, anchor9, sms(9), 12))
        assertEquals(CursorCheck.Rescan, checkCursor(9, anchor9, sms(5), 5), "the highest id is below the cursor")
        assertEquals(CursorCheck.Rescan, checkCursor(9, anchor9, sms(9, body = "another text"), 40), "same id, different text")
        assertEquals(CursorCheck.Rescan, checkCursor(9, anchor9, null, 40), "the text is gone")
        // A cursor from before anchors: adopt the row, unless the store clearly shrank below it.
        assertEquals(CursorCheck.Adopt(anchor9), checkCursor(9, null, sms(9), 9))
        assertEquals(CursorCheck.Keep, checkCursor(9, null, null, 30))
        assertEquals(CursorCheck.Rescan, checkCursor(9, null, null, 4))
    }

    @Test fun theCursorRemembersItsText() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) })
        r.connector.syncOnce()
        assertEquals(anchorOf(sms(3)), r.cursor.anchor)
    }

    @Test fun aRestoreWithLowerIdsIsRescannedAndNothingIsSkipped() = runBlocking {
        val before = (1L..1819L).map { sms(it) }
        val r = ConnectorRig(before)
        assertEquals(1819, r.connector.syncOnce())
        val uploaded = r.ingest.batches.flatMap { b -> b.records.map { it.sourceId } }.toSet()

        // Restored onto a new phone: the last 600 texts, numbered 1..600, then two new ones.
        val restored = before.takeLast(600).mapIndexed { i, row -> row.copy(id = i + 1L) }
        val fresh = listOf(sms(601, body = "after the restore"), sms(602, body = "and another"))
        r.source.rows = restored + fresh
        r.ingest.batches.clear()

        r.connector.syncOnce()
        val sent = r.ingest.batches.flatMap { b -> b.records.map { it.sourceId } }
        assertEquals(2, sent.size, "everything is read again from the start; what was sent is not sent twice")
        assertEquals(0, sent.count { it in uploaded })
        assertTrue(fresh.all { smsSourceId(it) in sent })
        assertEquals(602L, r.cursor.id)
        assertEquals(1, r.cursor.restarts)

        // Settled: nothing more next time.
        r.ingest.batches.clear()
        assertEquals(0, r.connector.syncOnce())
        assertEquals(1, r.cursor.restarts)
    }

    @Test fun aDifferentTextUnderTheCursorIdIsNotTakenForTheOldOne() = runBlocking {
        val r = ConnectorRig((1L..5L).map { sms(it) })
        r.connector.syncOnce()
        // Renumbered so that id 5 is now another text, with more after it.
        r.source.rows = (1L..8L).map { sms(it, body = "restored $it") }
        r.ingest.batches.clear()
        r.connector.syncOnce()
        assertEquals(8, r.ingest.batches.sumOf { it.records.size }, "ids 1..5 are other texts now and are sent too")
        assertEquals(1, r.cursor.restarts)
    }

    @Test fun anUpgradedCursorIsAdoptedWithoutResendingEverything() = runBlocking {
        val r = ConnectorRig((1L..6L).map { sms(it) })
        r.cursor.id = 4 // stored by the first version, without an anchor
        r.cursor.overlap = T0.toEpochMilliseconds()
        r.connector.syncOnce()
        assertEquals(listOf(smsSourceId(sms(5)), smsSourceId(sms(6))), r.ingest.batches.single().records.map { it.sourceId })
        assertEquals(0, r.cursor.restarts)
        assertEquals(anchorOf(sms(6)), r.cursor.anchor)
    }

    @Test fun theSameTextTwiceInOneBatchIsSentOnce() {
        val twice = listOf(sms(1), sms(1).copy(id = 2))
        assertEquals(1, smsPage(twice, account, T0).items.size)
    }

    @Test fun aForgottenServerHearsTheManifestAgainFromTheNextOne() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)))
        r.connector.syncOnce()
        val announced = r.ingest.manifests.size
        r.connector.syncOnce()
        assertEquals(announced, r.ingest.manifests.size, "announced once per server")
        r.connector.forgetServer()
        r.connector.syncOnce()
        assertEquals(announced + 1, r.ingest.manifests.size)
    }
}

private fun mms(
    id: Long,
    box: Int = MMS_BOX_INBOX,
    mType: Int = if (box == MMS_BOX_SENT) MMS_SEND_REQ else MMS_RETRIEVE_CONF,
    text: String = "hi $id",
    media: List<String> = emptyList(),
    addresses: List<MmsAddress> = listOf(MmsAddress(MMS_ADDR_FROM, "+15550101"), MmsAddress(MMS_ADDR_TO, "+15559999")),
    seconds: Long = T0.epochSeconds + id,
) = MmsRow(id, 9, seconds, box, mType, text, media, addresses)

private val me = Handle.phone("+15559999")

class MmsMappingTest {
    @Test fun aReceivedMmsIsFromTheSenderToThePhoneWithDateInMillis() {
        val row = mms(4)
        val record = mmsToRecord(row, account, T0)!!
        val payload = record.payload as MessagePayload
        assertEquals(MessagePayload.Channel.SMS, payload.channel)
        assertEquals(Handle.phone("+15550101"), payload.sender)
        assertEquals(listOf(account), payload.recipients, "the one other address is this phone's own number")
        assertEquals("hi 4", payload.body)
        assertEquals("sms-thread:9", payload.threadRef)
        assertEquals(Instant.fromEpochMilliseconds((T0.epochSeconds + 4) * 1000), payload.sentAt)
        assertEquals((T0.epochSeconds + 4) * 1000, row.dateMillis)
        assertEquals(SourceId("mms:v1:${row.dateMillis}:${mmsFingerprint(row)}"), record.sourceId)
        assertEquals(16, mmsFingerprint(row).length)
    }

    @Test fun aSentMmsIsFromThePhoneToToAndCcAndTheTokenIsDropped() {
        val row = mms(
            5, box = MMS_BOX_SENT,
            addresses = listOf(
                MmsAddress(MMS_ADDR_FROM, MMS_ADDRESS_TOKEN),
                MmsAddress(MMS_ADDR_TO, "+15550101"),
                MmsAddress(MMS_ADDR_CC, "+15550202"),
                MmsAddress(MMS_ADDR_BCC, "+15550303"),
            ),
        )
        val payload = mmsToRecord(row, account, T0)!!.payload as MessagePayload
        assertEquals(account, payload.sender)
        assertEquals(listOf(Handle.phone("+15550101"), Handle.phone("+15550202")), payload.recipients)
    }

    @Test fun aGroupTextKeepsItsMembers() {
        val row = mms(
            6,
            addresses = listOf(
                MmsAddress(MMS_ADDR_FROM, "+15550101"), MmsAddress(MMS_ADDR_TO, "+15559999"),
                MmsAddress(MMS_ADDR_TO, "+15550202"), MmsAddress(MMS_ADDR_CC, "+15550303"),
            ),
        )
        val payload = mmsToRecord(row, account, T0)!!.payload as MessagePayload
        assertEquals(Handle.phone("+15550101"), payload.sender)
        assertEquals(listOf(account, me, Handle.phone("+15550202"), Handle.phone("+15550303")), payload.recipients)
    }

    @Test fun aPhotoWithoutWordsGetsAShortBody() {
        assertEquals("[photo]", mmsBody(mms(7, text = "", media = listOf("image/jpeg"))))
        assertEquals("[attachment]", mmsBody(mms(7, text = " ", media = listOf("audio/amr"))))
        assertEquals("look", mmsBody(mms(7, text = "look", media = listOf("image/jpeg"))))
        assertEquals("[photo]", (mmsToRecord(mms(7, text = "", media = listOf("image/png")), account, T0)!!.payload as MessagePayload).body)
    }

    @Test fun stubsAndReceiptsAreNotMessages() {
        assertNull(mmsToRecord(mms(1, mType = MMS_NOTIFICATION_IND), account, T0))
        assertNull(mmsToRecord(mms(1, mType = 134), account, T0))
        assertNull(mmsToRecord(mms(1, box = MMS_BOX_OUTBOX, mType = MMS_SEND_REQ), account, T0))
        assertTrue(mmsIsPending(mms(1, box = MMS_BOX_OUTBOX, mType = MMS_SEND_REQ)))
        assertTrue(mmsIsPending(mms(1, mType = MMS_NOTIFICATION_IND)))
    }

    @Test fun noSenderAddressMeansAnUnknownSender() {
        val row = mms(8, addresses = listOf(MmsAddress(MMS_ADDR_FROM, MMS_ADDRESS_TOKEN)))
        assertEquals(UNKNOWN_SENDER, (mmsToRecord(row, account, T0)!!.payload as MessagePayload).sender)
    }
}

class SmsRobustnessTest {
    private fun ConnectorRig.sentIds() = ingest.batches.flatMap { b -> b.records.map { (it as IngestRecord.Upsert).sourceId } }

    @Test fun theFingerprintOfAnExistingTextIsUnchanged() {
        assertEquals("80158a2f3ad4454f", smsFingerprint(SmsRow(1, "+15550101", "hello", 1000, SMS_INBOX, 7)))
    }

    @Test fun aSentTextStillInTheOutboxIsUploadedWhenItFlipsEvenAfterALaterInboxRow() = runBlocking {
        val r = ConnectorRig(listOf(sms(1), sms(2, type = SMS_OUTBOX), sms(3)))
        assertEquals(2, r.connector.syncOnce())
        assertEquals(1L, r.cursor.id, "the cursor waits below the outbox row")
        // It goes out: same _id, new type.
        r.source.rows = listOf(sms(1), sms(2, type = SMS_SENT), sms(3))
        r.ingest.batches.clear()
        assertEquals(1, r.connector.syncOnce())
        assertEquals(listOf(smsSourceId(sms(2, type = SMS_SENT))), r.sentIds())
        assertEquals(3L, r.cursor.id)
    }

    @Test fun aPendingRowOlderThanADayIsGivenUpOn() = runBlocking {
        val old = sms(2, type = SMS_FAILED).copy(dateMillis = T0.toEpochMilliseconds() - 2.days.inWholeMilliseconds)
        val r = ConnectorRig(listOf(sms(1), old, sms(3)))
        assertEquals(2, r.connector.syncOnce())
        assertEquals(3L, r.cursor.id)
    }

    @Test fun aRejectedRecordHoldsTheCursorJustBeforeIt() = runBlocking {
        val r = ConnectorRig((1L..4L).map { sms(it) })
        r.ingest.rejectIds = setOf(smsSourceId(sms(3)))
        assertEquals(3, r.connector.syncOnce())
        assertEquals(2L, r.cursor.id)
        r.ingest.rejectIds = emptySet()
        r.ingest.batches.clear()
        assertEquals(1, r.connector.syncOnce())
        assertEquals(listOf(smsSourceId(sms(3))), r.sentIds(), "only the refused one goes again")
        assertEquals(4L, r.cursor.id)
    }

    @Test fun aBlankAddressIsUploadedNotDropped() = runBlocking {
        val r = ConnectorRig(listOf(sms(1, address = "")))
        assertEquals(1, r.connector.syncOnce())
        val payload = (r.ingest.batches.single().records.single() as IngestRecord.Upsert).payload as MessagePayload
        assertEquals(UNKNOWN_SENDER, payload.sender)
    }

    @Test fun theRecentWindowCatchesALowIdInsertAndDoesNotResendWhatWentOut() = runBlocking {
        val r = ConnectorRig(listOf(sms(1), sms(2), sms(5)))
        r.connector.syncOnce()
        // A backup tool inserts an older text under an id below the cursor.
        r.source.rows = r.source.rows + sms(3)
        r.ingest.batches.clear()
        assertEquals(0, r.connector.syncOnce(), "the window is read every few hours, not every time")
        r.clock.now = T0 + 4.hours
        assertEquals(1, r.connector.syncOnce())
        assertEquals(listOf(smsSourceId(sms(3))), r.sentIds())
        assertEquals(5L, r.cursor.id)
        r.ingest.batches.clear()
        r.clock.now = T0 + 8.hours
        assertEquals(0, r.connector.syncOnce())
        assertTrue(r.ingest.batches.isEmpty())
    }

    @Test fun mmsHasItsOwnCursor() = runBlocking {
        val r = ConnectorRig((1L..3L).map { sms(it) }, mmsRows = listOf(mms(1), mms(2, box = MMS_BOX_SENT)))
        assertEquals(5, r.connector.syncOnce())
        assertEquals(3L, r.cursor.id)
        assertEquals(2L, r.cursor.mmsId)
        val ids = r.sentIds().map { it.value }
        assertEquals(2, ids.count { it.startsWith("mms:v1:") })
        r.ingest.batches.clear()
        r.mms.rows = r.mms.rows + mms(3)
        assertEquals(1, r.connector.syncOnce())
        assertEquals(3L, r.cursor.id)
        assertEquals(3L, r.cursor.mmsId)
    }

    @Test fun anMmsStillSendingHoldsOnlyTheMmsCursor() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)), mmsRows = listOf(mms(1), mms(2, box = MMS_BOX_OUTBOX, mType = MMS_SEND_REQ), mms(3)))
        assertEquals(3, r.connector.syncOnce())
        assertEquals(1L, r.cursor.id)
        assertEquals(1L, r.cursor.mmsId)
        r.mms.rows = listOf(mms(1), mms(2, box = MMS_BOX_SENT), mms(3))
        assertEquals(1, r.connector.syncOnce())
        assertEquals(3L, r.cursor.mmsId)
    }

    @Test fun aReplacedMmsStoreIsReadFromTheStart() = runBlocking {
        val r = ConnectorRig(mmsRows = listOf(mms(1), mms(2), mms(3)))
        r.connector.syncOnce()
        r.mms.rows = listOf(mms(1, text = "other"))
        assertEquals(1, r.connector.syncOnce())
        assertEquals(1L, r.cursor.mmsId)
    }

    @Test fun broadcastsOnlyQueueASyncLater() {
        val r = ConnectorRig()
        r.connector.messageArrived(ACTION_SMS_RECEIVED, null)
        assertEquals(listOf(ARRIVAL_DELAY_SECONDS), r.work.soon)
        r.work.soon.clear()
        r.connector.messageArrived(ACTION_WAP_PUSH_RECEIVED, MMS_PUSH_MIME)
        assertEquals(listOf(ARRIVAL_DELAY_SECONDS, MMS_DOWNLOAD_DELAY_SECONDS), r.work.soon)
        r.work.soon.clear()
        r.connector.messageArrived(ACTION_WAP_PUSH_RECEIVED, "application/vnd.wap.sia")
        r.connector.messageArrived("android.intent.action.BOOT_COMPLETED", null)
        r.connector.messageArrived(null, null)
        assertTrue(r.work.soon.isEmpty())
        assertEquals(0, r.source.reads, "nothing is read from the broadcast itself")
    }

    @Test fun onlyTransientFailuresAreRetried() = runBlocking {
        assertTrue(shouldRetry(SmsSyncProblem.UNREACHABLE))
        assertTrue(shouldRetry(SmsSyncProblem.SERVER_ERROR))
        assertFalse(shouldRetry(null))
        assertFalse(shouldRetry(SmsSyncProblem.NOT_ACCEPTED))
        assertFalse(shouldRetry(SmsSyncProblem.NOT_ALLOWED))
        assertFalse(shouldRetry(SmsSyncProblem.READ_FAILED))
        assertFalse(shouldRetry(SmsSyncProblem.PERMISSION_LOST))
        val r = ConnectorRig(listOf(sms(1)))
        r.ingest.pushFailure = ApiError.Unreachable("down")
        assertEquals(SmsSyncProblem.UNREACHABLE, r.connector.runPass())
        r.ingest.pushFailure = null
        assertNull(r.connector.runPass())
    }

    @Test fun aPermissionNeverGivenIsNotReportedAsLostAndWritesNothing() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)), smsGranted = false)
        assertEquals(0, r.connector.syncOnce())
        assertNull(r.connector.runPass())
        assertEquals(SmsSyncStatus(), r.cursor.state.value)
    }

    @Test fun aBacklogBehindAPendingRowIsUploadedAcrossPasses() = runBlocking {
        // 2 reads of 5 per pass; row 1 is still sending, rows 2..40 follow.
        val r = ConnectorRig(listOf(sms(1, type = SMS_OUTBOX)) + (2L..40L).map { sms(it) }, readSize = 5, maxReads = 2)
        repeat(6) { r.connector.syncOnce() }
        val sent = r.ingest.batches.flatMap { b -> b.records.map { (it as IngestRecord.Upsert).sourceId } }.toSet()
        assertEquals((2L..40L).map { smsSourceId(sms(it)) }.toSet(), sent)
        assertEquals(0L, r.cursor.id, "the saved cursor stays behind the pending row")
    }

    @Test fun aTakenBackPermissionShowsInTheStatus() = runBlocking {
        val r = ConnectorRig(listOf(sms(1)), smsGranted = false)
        r.cursor.id = 1 // messages were on before
        assertEquals(0, r.connector.syncOnce())
        assertEquals(SmsSyncProblem.PERMISSION_LOST, r.cursor.state.value.problem)
        assertEquals(0, r.source.reads)
        assertTrue(net.robog.clarity.app.ui.settings.smsRowText(false, r.cursor.state.value, T0).needsAllow)
    }
}

class SyncMemoryStoreTest {
    @Test fun keepsTheMmsCursorTheWindowAndTheRecentIdsAndForgetsThemOnClear() = runBlocking {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "sms-extra-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreSmsCursorStore(
                PreferenceDataStoreFactory.create(scope = scope) { java.io.File(dir, "c.preferences_pb") },
            )
            store.advance(5, 5)
            store.advanceMms(2, 2)
            assertEquals(5L, store.lastId())
            assertEquals(2L, store.mmsLastId())
            assertEquals(7L, store.status.first().sent)
            store.markOverlap(123)
            store.remember(listOf("a", "b"))
            store.remember(listOf("b", "c"))
            assertEquals(setOf("a", "b", "c"), store.recentIds())
            assertEquals(123L, store.overlapAt())
            store.restartMms()
            assertEquals(0L, store.mmsLastId())
            store.clear()
            assertEquals(0L, store.overlapAt())
            assertTrue(store.recentIds().isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}

class ReceivePermissionsTest {
    private val read = android.Manifest.permission.READ_SMS
    private val receive = listOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.RECEIVE_MMS)

    @Test fun asksForJustTheMissingReceivePermissionsOncePerVersion() {
        assertEquals(receive, receivePermissionsToAsk({ it == read }, askedVersion = null, currentVersion = 5))
        assertEquals(receive.drop(1), receivePermissionsToAsk({ it == read || it == receive[0] }, askedVersion = 4, currentVersion = 5))
        assertEquals(emptyList(), receivePermissionsToAsk({ it == read }, askedVersion = 5, currentVersion = 5), "already asked this version")
    }

    @Test fun staysQuietWithoutReadAccessOrWhenAllIsGranted() {
        assertEquals(emptyList(), receivePermissionsToAsk({ false }, askedVersion = null, currentVersion = 5))
        assertEquals(emptyList(), receivePermissionsToAsk({ true }, askedVersion = null, currentVersion = 5))
    }
}
