package net.robog.clarity.app.connector

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.robog.clarity.app.call.graph
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.AppClock
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.app.device.DeviceCapabilities
import net.robog.clarity.app.net.IngestApi
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.noun.Noun

/**
 * The phone as a connector (`device-<deviceId>`, authenticated by the device JWT): it pushes SMS as
 * `MessagePayload(channel = SMS)` with a cursor, and executes `SET_DO_NOT_DISTURB` from the action
 * channel. The app keeps no reminders: those are calendar events on the server.
 */
interface PhoneConnector {
    /** Connector id the backend assigns to this device, e.g. `device-<deviceId>`. */
    fun connectorId(deviceId: String): String = "device-$deviceId"

    /** Pushes SMS that arrived since the cursor. Returns how many records were sent. */
    suspend fun syncOnce(): Int

    /** Asks for pending actions once (long-polling up to [waitSeconds]) and executes them. Returns how many. */
    suspend fun actionPass(waitSeconds: Int): Int

    /** One background pass: manifest, SMS, due do-not-disturb work, pending actions. */
    suspend fun runPass()

    /** Long-polls for actions until cancelled; run while the app is in front. */
    suspend fun runActionLoop()

    /** (Re)schedules the periodic background pass. */
    fun schedule()

    /** When SMS last reached the server. */
    val lastSyncAt: Flow<kotlin.time.Instant?>
}

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
    private val version: String = "1",
) : PhoneConnector {
    override val lastSyncAt: Flow<kotlin.time.Instant?> = cursor.lastSyncAt

    override suspend fun syncOnce(): Int {
        if (!capabilities.smsGranted()) return 0
        val deviceId = sessions.session.first()?.deviceId?.value ?: return 0
        val account = phoneAccount(deviceId)
        checkCursorFirst()
        var sent = 0
        repeat(MAX_READS) {
            val rows = sms.readAfter(cursor.lastId(), READ_SIZE)
            if (rows.isEmpty()) return sent
            for ((batch, newest) in smsBatches(rows, account, clock.now())) {
                if (batch.records.isNotEmpty()) {
                    val response = when (val r = ingest.pushBatch(batch)) {
                        is ApiResult.Failure -> return sent
                        is ApiResult.Success -> r.value
                    }
                    if (!cursorMayAdvance(response.results)) return sent
                    sent += batch.records.size
                }
                cursor.advance(newest.id, clock.now(), anchorOf(newest))
            }
            if (rows.size < READ_SIZE) return sent
        }
        return sent
    }

    /** A restore or a new phone renumbers the SMS store: start over rather than skip texts (see [checkCursor]). */
    private suspend fun checkCursorFirst() {
        val last = cursor.lastId()
        if (last <= 0) return
        when (val check = checkCursor(last, cursor.anchor(), sms.rowAt(last), sms.maxId())) {
            CursorCheck.Keep -> Unit
            is CursorCheck.Adopt -> cursor.adopt(check.anchor)
            CursorCheck.Rescan -> cursor.restart()
        }
    }

    override suspend fun actionPass(waitSeconds: Int): Int {
        val poll = (ingest.pollActions(waitSeconds) as? ApiResult.Success)?.value ?: return -1
        for (action in poll.actions) {
            val outcome = dnd.handle(action.action)
            ingest.reportResult(action.id, ActionResult(outcome))
        }
        return poll.actions.size
    }

    override suspend fun runPass() {
        ingest.putManifest(phoneManifest(version))
        dnd.endIfDue()
        syncOnce()
        actionPass(0)
    }

    override suspend fun runActionLoop() {
        ingest.putManifest(phoneManifest(version))
        while (true) {
            // -1 means the poll failed (offline, route missing): wait before trying again.
            if (actionPass(LONG_POLL_SECONDS) < 0) delay(RETRY_DELAY)
        }
    }

    override fun schedule() = work.schedule()

    private companion object {
        const val READ_SIZE = 500
        const val MAX_READS = 20
        const val LONG_POLL_SECONDS = 25
        val RETRY_DELAY = 30.seconds
    }
}

/** The WorkManager side, behind an interface so the connector is testable. */
interface ConnectorWork {
    fun schedule()

    /** Stops the periodic pass (the server was forgotten). */
    fun cancel() {}
}

class WorkManagerConnectorWork(private val context: Context) : ConnectorWork {
    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<PhoneConnectorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object { const val UNIQUE_NAME = "phone-connector" }
}

class PhoneConnectorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph()
        // No server yet (or forgotten): nothing to do, and nothing to retry.
        if (graph.sessionStore.session.first() == null) return Result.success()
        return try {
            graph.phoneConnector.runPass()
            Result.success()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class NoOpPhoneConnector : PhoneConnector {
    override suspend fun syncOnce(): Int = 0
    override suspend fun actionPass(waitSeconds: Int): Int = 0
    override suspend fun runPass() = Unit
    override suspend fun runActionLoop() = Unit
    override fun schedule() = Unit
    override val lastSyncAt: Flow<kotlin.time.Instant?> = kotlinx.coroutines.flow.flowOf(null)
}
