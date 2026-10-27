package net.robog.clarity.app.connector

import android.content.Context
import android.provider.Telephony
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.RecordOutcome
import net.robog.clarity.shared.ingest.RecordResult
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

/** One row of the phone's SMS store, as much of it as the backend needs. */
data class SmsRow(
    val id: Long,
    /** The other party's number. */
    val address: String,
    val body: String,
    val dateMillis: Long,
    /** `Telephony.Sms.MESSAGE_TYPE_INBOX` (1) or `MESSAGE_TYPE_SENT` (2). */
    val type: Int,
    val threadId: Long?,
)

const val SMS_INBOX = 1
const val SMS_SENT = 2

/** The person's own side of every message: this phone. */
fun phoneAccount(deviceId: String): Handle = Handle(Handle.Kind.OTHER, phoneConnectorId(deviceId), "This phone")

/** `sms:<_id>`. SMS rows never change once stored, so the version is constant. */
fun smsSourceId(row: SmsRow): SourceId = SourceId("sms:${row.id}")

/**
 * The other party: a phone number, or for alphanumeric senders ("AMAZON", "Google") a handle of
 * kind `OTHER`, since a number normalized from no digits would be the same "+" for all of them.
 */
fun smsParty(address: String): Handle? {
    val trimmed = address.trim().ifEmpty { return null }
    return if (trimmed.any(Char::isDigit)) Handle.phone(trimmed) else Handle(Handle.Kind.OTHER, trimmed, trimmed)
}

/** Maps one SMS to an ingest record; null for rows that are neither received nor sent (drafts, outbox). */
fun smsToRecord(row: SmsRow, account: Handle, observedAt: Instant): IngestRecord.Upsert? {
    val other = smsParty(row.address) ?: return null
    val (sender, recipients) = when (row.type) {
        SMS_INBOX -> other to listOf(account)
        SMS_SENT -> account to listOf(other)
        else -> return null
    }
    return IngestRecord.Upsert(
        sourceId = smsSourceId(row),
        sourceVersion = "1",
        observedAt = observedAt,
        payload = MessagePayload(
            channel = MessagePayload.Channel.SMS,
            account = account,
            sender = sender,
            recipients = recipients,
            body = row.body,
            sentAt = Instant.fromEpochMilliseconds(row.dateMillis),
            threadRef = row.threadId?.let { "sms-thread:$it" },
        ),
    )
}

/** Splits [rows] into batches the backend accepts. */
fun smsBatches(rows: List<SmsRow>, account: Handle, observedAt: Instant, size: Int = IngestBatch.MAX_RECORDS): List<Pair<IngestBatch, Long>> =
    rows.sortedBy { it.id }
        .chunked(size)
        .map { chunk ->
            IngestBatch(chunk.mapNotNull { smsToRecord(it, account, observedAt) }) to chunk.maxOf { it.id }
        }

/**
 * The cursor may move past a batch unless the server refused every record (typically because the
 * messages noun has not been granted yet; the same rows are then tried again next time).
 */
fun cursorMayAdvance(results: List<RecordResult>): Boolean =
    results.isEmpty() || results.any { it.outcome !is RecordOutcome.Rejected }

/** Records the server took (created, merged, unchanged or removed). */
fun acceptedCount(results: List<RecordResult>): Int = results.count { it.outcome !is RecordOutcome.Rejected }

interface SmsSource {
    /** Rows with `_id > afterId`, oldest first, at most [limit]. */
    suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow>
}

/**
 * Reads the system SMS store (every SIM: the provider holds all subscriptions' messages). Rows are
 * read through the cursor window and the loop stops at [limit], so a huge inbox is never loaded at
 * once; no `LIMIT` is put in the sort order, which strict providers may refuse.
 */
class AndroidSmsSource(private val context: Context) : SmsSource {
    override suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow> {
        val rows = mutableListOf<SmsRow>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.TYPE} IN (?, ?)",
            arrayOf(afterId.toString(), SMS_INBOX.toString(), SMS_SENT.toString()),
            "${Telephony.Sms._ID} ASC",
        ) ?: return emptyList()
        cursor.use {
            while (rows.size < limit && it.moveToNext()) {
                rows += SmsRow(
                    id = it.getLong(0),
                    address = it.getString(1).orEmpty(),
                    body = it.getString(2).orEmpty(),
                    dateMillis = it.getLong(3),
                    type = it.getInt(4),
                    threadId = if (it.isNull(5)) null else it.getLong(5),
                )
            }
        }
        return rows
    }
}

/** Why the last SMS sync did not get through; null in [SmsSyncStatus] when it did. */
enum class SmsSyncProblem {
    /** No network, or the server did not answer. */
    UNREACHABLE,
    /** The server refused this phone's credential. */
    NOT_ACCEPTED,
    /** The server refused every message: messages are not allowed (granted) for this phone there. */
    NOT_ALLOWED,
    /** Reading the phone's messages failed. */
    READ_FAILED,
    /** Any other error answer. */
    SERVER_ERROR,
}

fun problemOf(error: ApiError): SmsSyncProblem = when (error) {
    is ApiError.Unreachable, ApiError.NotConfigured -> SmsSyncProblem.UNREACHABLE
    is ApiError.Unauthorized, is ApiError.Forbidden -> SmsSyncProblem.NOT_ACCEPTED
    else -> SmsSyncProblem.SERVER_ERROR
}

/** What Settings shows about the SMS sync. */
data class SmsSyncStatus(
    /** When the server last took everything there was (or confirmed there was nothing new). */
    val lastSyncAt: Instant? = null,
    /** When a sync was last tried, whatever came of it. */
    val lastAttemptAt: Instant? = null,
    /** Messages the server has accepted from this phone so far. */
    val sent: Long = 0,
    /** Why the last attempt failed; null when it got through. */
    val problem: SmsSyncProblem? = null,
)

/** Where the sync stopped last time, and how it went. */
interface SmsCursorStore {
    suspend fun lastId(): Long
    /** Moves past [to] once the server took the batch ending there, [sent] records of it. */
    suspend fun advance(to: Long, sent: Int)
    /** The end of a sync attempt: [problem] null means it got through. */
    suspend fun finish(at: Instant, problem: SmsSyncProblem?)
    val status: Flow<SmsSyncStatus>
}

class DataStoreSmsCursorStore(private val store: DataStore<Preferences>) : SmsCursorStore {
    override suspend fun lastId(): Long = store.data.first()[LAST_ID] ?: 0L

    override suspend fun advance(to: Long, sent: Int) {
        store.edit {
            it[LAST_ID] = to
            it[SENT] = (it[SENT] ?: 0L) + sent
        }
    }

    override suspend fun finish(at: Instant, problem: SmsSyncProblem?) {
        store.edit {
            it[LAST_ATTEMPT] = at.toEpochMilliseconds()
            if (problem == null) {
                it[LAST_SYNC] = at.toEpochMilliseconds()
                it.remove(PROBLEM)
            } else {
                it[PROBLEM] = problem.name
            }
        }
    }

    override val status: Flow<SmsSyncStatus> = store.data.map { p ->
        SmsSyncStatus(
            lastSyncAt = p[LAST_SYNC]?.let { Instant.fromEpochMilliseconds(it) },
            lastAttemptAt = p[LAST_ATTEMPT]?.let { Instant.fromEpochMilliseconds(it) },
            sent = p[SENT] ?: 0L,
            problem = p[PROBLEM]?.let { name -> SmsSyncProblem.entries.firstOrNull { it.name == name } },
        )
    }

    private companion object {
        val LAST_ID = longPreferencesKey("sms_last_id")
        val LAST_SYNC = longPreferencesKey("sms_last_sync")
        val LAST_ATTEMPT = longPreferencesKey("sms_last_attempt")
        val SENT = longPreferencesKey("sms_sent")
        val PROBLEM = stringPreferencesKey("sms_problem")
    }
}
