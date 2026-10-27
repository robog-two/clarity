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
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.connector.GrantsUpdate
import net.robog.clarity.shared.governance.ActionKind
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
     * The person just allowed reading texts: allow messages from this phone on the server too
     * (an explicit grant, so it also undoes an earlier revoke) and sync.
     */
    suspend fun enableMessages(): Int

    /** Asks for pending actions once (long-polling up to [waitSeconds]) and executes them. Returns how many. */
    suspend fun actionPass(waitSeconds: Int): Int

    /** One background pass: manifest, SMS, due do-not-disturb work, pending actions. */
    suspend fun runPass()

    /** Long-polls for actions until cancelled; run while the app is in front. */
    suspend fun runActionLoop()

    /** (Re)schedules the periodic background pass. */
    fun schedule()

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
    private val version: String = "1",
) : PhoneConnector {
    override val status: Flow<SmsSyncStatus> = cursor.status

    /** The worker and the app in front may both sync: one at a time, so nothing is sent twice. */
    private val syncLock = Mutex()

    /** The server has this run's manifest (and with it, the grant for messages). */
    @Volatile private var announced = false

    private data class SyncOutcome(val sent: Int, val problem: SmsSyncProblem?)

    override suspend fun syncOnce(): Int = syncLock.withLock {
        if (!capabilities.smsGranted()) return 0
        val deviceId = sessions.session.first()?.deviceId?.value ?: return 0
        val outcome = sync(deviceId)
        cursor.finish(clock.now(), outcome.problem)
        outcome.sent
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

    private suspend fun sync(deviceId: String): SyncOutcome {
        // The manifest is what makes the server allow (grant) messages from this phone.
        if (!announced) announce()?.let { return SyncOutcome(0, it) }
        val account = phoneAccount(deviceId)
        var sent = 0
        repeat(MAX_READS) {
            val rows = try {
                sms.readAfter(cursor.lastId(), READ_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return SyncOutcome(sent, SmsSyncProblem.READ_FAILED)
            }
            if (rows.isEmpty()) return SyncOutcome(sent, null)
            for ((batch, newest) in smsBatches(rows, account, clock.now())) {
                var accepted = 0
                if (batch.records.isNotEmpty()) {
                    val response = when (val r = ingest.pushBatch(batch)) {
                        is ApiResult.Failure -> return SyncOutcome(sent, problemOf(r.error))
                        is ApiResult.Success -> r.value
                    }
                    if (!cursorMayAdvance(response.results)) {
                        // Next time the manifest goes again: it heals a grant left pending.
                        announced = false
                        return SyncOutcome(sent, SmsSyncProblem.NOT_ALLOWED)
                    }
                    accepted = acceptedCount(response.results)
                }
                sent += accepted
                cursor.advance(newest, accepted)
            }
            if (rows.size < READ_SIZE) return SyncOutcome(sent, null)
        }
        // More is left (a very large inbox): the next pass carries on from the cursor.
        return SyncOutcome(sent, null)
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
        announce()
        dnd.endIfDue()
        syncOnce()
        actionPass(0)
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
        const val MAX_READS = 20
        const val LONG_POLL_SECONDS = 25
        val RETRY_DELAY = 30.seconds
    }
}

/** The WorkManager side, behind an interface so the connector is testable. */
interface ConnectorWork {
    fun schedule()
}

class WorkManagerConnectorWork(private val context: Context) : ConnectorWork {
    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<PhoneConnectorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
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
    override suspend fun enableMessages(): Int = 0
    override suspend fun actionPass(waitSeconds: Int): Int = 0
    override suspend fun runPass() = Unit
    override suspend fun runActionLoop() = Unit
    override fun schedule() = Unit
    override val status: Flow<SmsSyncStatus> = kotlinx.coroutines.flow.flowOf(SmsSyncStatus())
}
