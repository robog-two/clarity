package net.robog.clarity.app.connector

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import android.provider.Telephony
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.robog.clarity.app.call.graph
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.net.IngestApi
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.connector.GrantsUpdate
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.Noun

/**
 * The phone as a connector (`device-<deviceId>`, authenticated by the device JWT): it pushes SMS as
 * `MessagePayload(channel = SMS)` with a cursor, and executes `SET_DO_NOT_DISTURB` from the action
 * channel. The app keeps no reminders: those are calendar events on the server.
 */
interface PhoneConnector {
    /** Connector id the backend assigns to this device, e.g. `device-<deviceId>`. */
    fun connectorId(deviceId: String): String = phoneConnectorId(deviceId)

    /**
     * Pushes SMS that arrived since the cursor (the whole store on the first run, in batches).
     * Returns how many records the server accepted; how it went is in [status].
     */
    suspend fun syncOnce(): Int

    /**
     * A text or an MMS notification just arrived: sync shortly after (the messaging app writes the
     * row after the broadcast), and for an MMS once more later, when the download is done.
     */
    fun messageArrived(action: String?, mimeType: String?) {}

    /**
     * The person just allowed reading texts: allow messages from this phone on the server too
     * (an explicit grant, so it also undoes an earlier revoke) and sync.
     */
    suspend fun enableMessages(): Int

    /** Asks for pending actions once (long-polling up to [waitSeconds]) and executes them. Returns how many. */
    suspend fun actionPass(waitSeconds: Int): Int

    /** One background pass: manifest, SMS, due do-not-disturb work, pending actions. Returns why the sync failed, if it did. */
    suspend fun runPass(): SmsSyncProblem?

    /** Long-polls for actions until cancelled; run while the app is in front. */
    suspend fun runActionLoop()

    /** (Re)schedules the periodic background pass. */
    fun schedule()

    /** The server was forgotten: the next one has not heard of this phone yet (the manifest goes again). */
    fun forgetServer() {}

    /** When SMS last reached the server, how many, and why the last try failed. */
    val status: Flow<SmsSyncStatus>
}

/** The id the backend gives a device acting as a connector (it lowercases the device id). */
fun phoneConnectorId(deviceId: String): String = "device-${deviceId.lowercase()}"

fun phoneManifest(version: String) = ConnectorManifest(
    kind = "phone",
    displayName = "This phone",
    version = version,
    produces = setOf(Noun.MESSAGE),
    handles = setOf(ActionKind.SET_DO_NOT_DISTURB),
)

class DefaultPhoneConnector(
    private val ingest: IngestApi,
    private val sms: SmsSource,
    private val cursor: SmsCursorStore,
    private val capabilities: DeviceCapabilities,
    private val sessions: SessionStore,
    private val dnd: DndActionHandler,
    private val clock: AppClock,
    private val work: ConnectorWork,
    private val mms: MmsSource = NoMmsSource,
    private val version: String = "1",
    private val readSize: Int = READ_SIZE,
    private val maxReads: Int = MAX_READS,
) : PhoneConnector {
    override val status: Flow<SmsSyncStatus> = cursor.status

    /** The worker and the app in front may both sync: one at a time, so nothing is sent twice. */
    private val syncLock = Mutex()

    /** Where each lane's last pass stopped reading, while it had more to read. In memory only. */
    private val readPosition = ConcurrentHashMap<String, Long>()

    /** The server has this run's manifest (and with it, the grant for messages). */
    @Volatile private var announced = false

    private data class SyncOutcome(val sent: Int, val problem: SmsSyncProblem?)

    override suspend fun syncOnce(): Int = syncWithProblem().sent

    private suspend fun syncWithProblem(): SyncOutcome = syncLock.withLock {
        if (!capabilities.smsGranted()) {
            // Never enabled: nothing to report, and nothing written. Enabled before: the system took
            // the permission back, so say so rather than look as if all is well.
            if (!messagesWereEnabled()) return@withLock SyncOutcome(0, null)
            cursor.finish(clock.now(), SmsSyncProblem.PERMISSION_LOST)
            return@withLock SyncOutcome(0, SmsSyncProblem.PERMISSION_LOST)
        }
        val deviceId = sessions.session.first()?.deviceId?.value ?: return@withLock SyncOutcome(0, null)
        val outcome = sync(deviceId)
        cursor.finish(clock.now(), outcome.problem)
        outcome
    }

    private suspend fun messagesWereEnabled(): Boolean {
        val status = cursor.status.first()
        return status.lastSyncAt != null || status.sent > 0 || cursor.lastId() > 0 || cursor.mmsLastId() > 0
    }

    override fun messageArrived(action: String?, mimeType: String?) {
        arrivalDelays(action, mimeType).forEach(work::syncSoon)
    }

    override suspend fun enableMessages(): Int {
        val deviceId = sessions.session.first()?.deviceId?.value ?: return 0
        if (announce() == null) {
            ingest.putGrants(ConnectorId(phoneConnectorId(deviceId)), GrantsUpdate(mapOf(Noun.MESSAGE to GrantState.GRANTED)))
        }
        return syncOnce()
    }

    /** Puts the manifest; null when the server has it. */
    private suspend fun announce(): SmsSyncProblem? =
        when (val r = ingest.putManifest(phoneManifest(version))) {
            is ApiResult.Failure -> problemOf(r.error)
            is ApiResult.Success -> { announced = true; null }
        }

    /** What one stream of rows (SMS or MMS) needs from the phone and the cursor. */
    private class Lane(
        val key: String,
        val readAfter: suspend (afterId: Long) -> Page,
        val readSince: suspend (sinceMillis: Long) -> Page,
        val cursorId: suspend () -> Long,
        /** Moves the cursor to [to] (its current place when it stays) and counts [sent]. */
        val advance: suspend (to: Long, sent: Int, anchor: SmsAnchor?) -> Unit,
        val anchor: suspend () -> SmsAnchor? = { null },
    )

    private suspend fun sync(deviceId: String): SyncOutcome {
        // The manifest is what makes the server allow (grant) messages from this phone.
        if (!announced) announce()?.let { return SyncOutcome(0, it) }
        val account = phoneAccount(deviceId)
        val lanes = try {
            checkCursorsFirst()
            listOf(smsLane(account), mmsLane(account))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SyncOutcome(0, SmsSyncProblem.READ_FAILED)
        }
        var sent = 0
        var problem: SmsSyncProblem? = null
        for (lane in lanes) {
            val outcome = push(lane) { after -> lane.readAfter(after) }
            sent += outcome.sent
            if (outcome.problem != null) {
                // The server is unreachable or refusing: the other lane would meet the same. A read
                // problem in one lane (MMS on a quirky phone) must not stop the other.
                if (outcome.problem != SmsSyncProblem.READ_FAILED) return SyncOutcome(sent, outcome.problem)
                problem = problem ?: outcome.problem
            }
        }
        // Now and then read the last days again: a text can flip to sent or appear below the cursor.
        if (clock.now().toEpochMilliseconds() - cursor.overlapAt() >= OVERLAP_EVERY.inWholeMilliseconds) {
            val since = clock.now().toEpochMilliseconds() - OVERLAP_WINDOW.inWholeMilliseconds
            var clean = problem == null
            for (lane in lanes) {
                val outcome = push(lane, moveCursor = false) { lane.readSince(since) }
                sent += outcome.sent
                if (outcome.problem != null) {
                    if (outcome.problem != SmsSyncProblem.READ_FAILED) return SyncOutcome(sent, outcome.problem)
                    problem = problem ?: outcome.problem
                    clean = false
                }
            }
            if (clean) cursor.markOverlap(clock.now().toEpochMilliseconds())
        }
        return SyncOutcome(sent, problem)
    }

    private fun smsLane(account: Handle) = Lane(
        key = "sms",
        readAfter = { after -> smsPage(sms.readAfter(after, readSize), account, clock.now()) },
        readSince = { since -> smsPage(sms.readSince(since, OVERLAP_LIMIT), account, clock.now()) },
        cursorId = cursor::lastId,
        advance = cursor::advance,
        anchor = cursor::anchor,
    )

    private fun mmsLane(account: Handle) = Lane(
        key = "mms",
        readAfter = { after -> mmsPage(mms.readAfter(after, readSize), account, clock.now()) },
        readSince = { since -> mmsPage(mms.readSince(since, OVERLAP_LIMIT), account, clock.now()) },
        cursorId = cursor::mmsLastId,
        advance = { to, n, _ -> cursor.advanceMms(to, n) },
    )

    /**
     * Sends what [read] returns and moves the cursor (unless [moveCursor] is off) as far as is safe:
     * never past a pending row or a row the server refused (see [cursorStep]). Rows sent lately
     * are not sent again, so reading from a stuck cursor or over the recent window stays cheap.
     */
    private suspend fun push(lane: Lane, moveCursor: Boolean = true, read: suspend (afterId: Long) -> Page): SyncOutcome {
        var sent = 0
        // While a pending row holds the cursor, a pass that ran out of reads carries on from where it
        // stopped next time, so a big backlog behind that row is still uploaded.
        var from = if (moveCursor) maxOf(lane.cursorId(), readPosition[lane.key] ?: 0L) else 0L
        if (moveCursor) readPosition.remove(lane.key)
        var ceiling: Long? = null
        val recent = cursor.recentIds().toMutableSet()
        repeat(maxReads) {
            val page = try {
                read(from)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return SyncOutcome(sent, SmsSyncProblem.READ_FAILED)
            }
            if (page.rowCount == 0) return SyncOutcome(sent, null)
            from = page.lastId
            ceiling = listOfNotNull(ceiling, page.ceiling).minOrNull()
            for (chunk in outgoingChunks(page.items)) {
                val fresh = chunk.filter { it.record.sourceId.value !in recent }
                var rejected = emptySet<SourceId>()
                var accepted = 0
                if (fresh.isNotEmpty()) {
                    val response = when (val r = ingest.pushBatch(IngestBatch(fresh.map { it.record }))) {
                        is ApiResult.Failure -> return SyncOutcome(sent, problemOf(r.error))
                        is ApiResult.Success -> r.value
                    }
                    if (!cursorMayAdvance(response.results)) {
                        // Next time the manifest goes again: it heals a grant left pending.
                        announced = false
                        return SyncOutcome(sent, SmsSyncProblem.NOT_ALLOWED)
                    }
                    rejected = rejectedIds(response.results)
                    val taken = fresh.map { it.record.sourceId.value }.filter { SourceId(it) !in rejected }
                    accepted = taken.size
                    recent += taken
                    cursor.remember(taken)
                }
                sent += accepted
                val step = cursorStep(chunk, ceiling, rejected, clock.now())
                ceiling = step.ceiling
                val target = step.target
                if (moveCursor && target != null) {
                    lane.advance(target.id, accepted, target.anchor)
                } else if (accepted > 0) {
                    lane.advance(lane.cursorId(), accepted, lane.anchor())
                }
            }
            if (page.rowCount < readSize || !moveCursor) return SyncOutcome(sent, null)
        }
        // More is left (a very large inbox): the next pass carries on from here. The saved cursor
        // stays where it is, behind any pending row.
        if (moveCursor) readPosition[lane.key] = from
        return SyncOutcome(sent, null)
    }

    /** A restore or a new phone renumbers the SMS store: start over rather than skip texts (see [checkCursor]). */
    private suspend fun checkCursorsFirst() {
        val last = cursor.lastId()
        if (last > 0) {
            when (val check = checkCursor(last, cursor.anchor(), sms.rowAt(last), sms.maxId())) {
                CursorCheck.Keep -> Unit
                is CursorCheck.Adopt -> cursor.adopt(check.anchor)
                CursorCheck.Rescan -> { cursor.restart(); readPosition.remove("sms") }
            }
        }
        if (mmsCursorLost(cursor.mmsLastId(), mms.maxId())) { cursor.restartMms(); readPosition.remove("mms") }
    }

    override fun forgetServer() {
        announced = false
    }

    override suspend fun actionPass(waitSeconds: Int): Int {
        val poll = (ingest.pollActions(waitSeconds) as? ApiResult.Success)?.value ?: return -1
        for (action in poll.actions) {
            val outcome = dnd.handle(action.action)
            ingest.reportResult(action.id, ActionResult(outcome))
        }
        return poll.actions.size
    }

    override suspend fun runPass(): SmsSyncProblem? {
        announce()
        dnd.endIfDue()
        val outcome = syncWithProblem()
        actionPass(0)
        return outcome.problem
    }

    override suspend fun runActionLoop() {
        announce()
        while (true) {
            // -1 means the poll failed (offline, route missing): wait before trying again.
            if (actionPass(LONG_POLL_SECONDS) < 0) delay(RETRY_DELAY)
        }
    }

    override fun schedule() = work.schedule()

    private companion object {
        const val READ_SIZE = 500
        const val OVERLAP_LIMIT = 2000
        const val MAX_READS = 20
        const val LONG_POLL_SECONDS = 25
        val RETRY_DELAY = 30.seconds
    }
}

/** The WorkManager side, behind an interface so the connector is testable. */
interface ConnectorWork {
    fun schedule()

    /** One sync [delaySeconds] from now (a later call for the same delay replaces an earlier one that has not started). */
    fun syncSoon(delaySeconds: Long) {}

    /** Stops the periodic pass (the server was forgotten). */
    fun cancel() {}
}

/** The broadcast actions that mean a text or an MMS notification came in. */
const val ACTION_SMS_RECEIVED = "android.provider.Telephony.SMS_RECEIVED"
const val ACTION_WAP_PUSH_RECEIVED = "android.provider.Telephony.WAP_PUSH_RECEIVED"
const val MMS_PUSH_MIME = "application/vnd.wap.mms-message"

/**
 * When to sync after a broadcast: the row is written by the messaging app after the broadcast, so
 * wait a few seconds. An MMS is only announced; its download lands later, so once more a minute on.
 */
fun arrivalDelays(action: String?, mimeType: String?): List<Long> = when {
    action == ACTION_SMS_RECEIVED -> listOf(ARRIVAL_DELAY_SECONDS)
    action == ACTION_WAP_PUSH_RECEIVED && mimeType == MMS_PUSH_MIME -> listOf(ARRIVAL_DELAY_SECONDS, MMS_DOWNLOAD_DELAY_SECONDS)
    else -> emptyList()
}

const val ARRIVAL_DELAY_SECONDS = 8L
const val MMS_DOWNLOAD_DELAY_SECONDS = 60L

/** Failures that may go away by themselves: the work is tried again later. The rest would fail again the same way. */
fun shouldRetry(problem: SmsSyncProblem?): Boolean =
    problem == SmsSyncProblem.UNREACHABLE || problem == SmsSyncProblem.SERVER_ERROR

class WorkManagerConnectorWork(private val context: Context) : ConnectorWork {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<PhoneConnectorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        watch(WATCH_A, ExistingWorkPolicy.KEEP)
    }

    override fun syncSoon(delaySeconds: Long) {
        val request = OneTimeWorkRequestBuilder<PhoneConnectorWorker>()
            .setConstraints(network)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        // A text that arrives while a pass runs gets another pass after it; the long wait is the last word.
        val policy = if (delaySeconds < MMS_DOWNLOAD_DELAY_SECONDS) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork("$UNIQUE_NAME-soon-$delaySeconds", policy, request)
    }

    /**
     * A job that runs when the messages provider changes (a text sent from another app, an MMS
     * downloaded). It fires once, so each run queues the next under the other name first.
     */
    fun watch(slot: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<PhoneConnectorWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .addContentUriTrigger(Telephony.MmsSms.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                    .build(),
            )
            .setInputData(workDataOf(KEY_WATCH_SLOT to slot))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(slot, policy, request)
    }

    override fun cancel() {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_NAME)
        manager.cancelUniqueWork(WATCH_A)
        manager.cancelUniqueWork(WATCH_B)
        manager.cancelUniqueWork("$UNIQUE_NAME-soon-$ARRIVAL_DELAY_SECONDS")
        manager.cancelUniqueWork("$UNIQUE_NAME-soon-$MMS_DOWNLOAD_DELAY_SECONDS")
    }

    companion object {
        private const val UNIQUE_NAME = "phone-connector"
        const val WATCH_A = "phone-connector-watch-a"
        const val WATCH_B = "phone-connector-watch-b"
        const val KEY_WATCH_SLOT = "watch-slot"
    }
}

class PhoneConnectorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph()
        val slot = inputData.getString(WorkManagerConnectorWork.KEY_WATCH_SLOT)
        // No server yet (or forgotten): nothing to do, and nothing to retry.
        if (graph.sessionStore.session.first() == null) return Result.success()
        // Queue the next watcher before the sync, so a change during the sync is not missed.
        if (slot != null) {
            val next = if (slot == WorkManagerConnectorWork.WATCH_A) WorkManagerConnectorWork.WATCH_B else WorkManagerConnectorWork.WATCH_A
            WorkManagerConnectorWork(applicationContext).watch(next, ExistingWorkPolicy.REPLACE)
        }
        return try {
            if (shouldRetry(graph.phoneConnector.runPass())) Result.retry() else Result.success()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** No MMS store (tests and the no-op connector). */
object NoMmsSource : MmsSource {
    override suspend fun readAfter(afterId: Long, limit: Int): List<MmsRow> = emptyList()
    override suspend fun readSince(sinceMillis: Long, limit: Int): List<MmsRow> = emptyList()
    override suspend fun maxId(): Long? = null
}

class NoOpPhoneConnector : PhoneConnector {
    override suspend fun syncOnce(): Int = 0
    override suspend fun enableMessages(): Int = 0
    override suspend fun actionPass(waitSeconds: Int): Int = 0
    override suspend fun runPass(): SmsSyncProblem? = null
    override suspend fun runActionLoop() = Unit
    override fun schedule() = Unit
    override val status: Flow<SmsSyncStatus> = kotlinx.coroutines.flow.flowOf(SmsSyncStatus())
}
