package net.robog.clarity.app.connector

import android.content.Context
import android.provider.Telephony
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
fun phoneAccount(deviceId: String): Handle = Handle(Handle.Kind.OTHER, "device-$deviceId", "This phone")

/** `sms:<_id>`. SMS rows never change once stored, so the version is constant. */
fun smsSourceId(row: SmsRow): SourceId = SourceId("sms:${row.id}")

/** Maps one SMS to an ingest record; null for rows that are neither received nor sent (drafts, outbox). */
fun smsToRecord(row: SmsRow, account: Handle, observedAt: Instant): IngestRecord.Upsert? {
    val other = Handle.phone(row.address.trim().ifEmpty { return null })
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

interface SmsSource {
    /** Rows with `_id > afterId`, oldest first, at most [limit]. */
    suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow>
}

class AndroidSmsSource(private val context: Context) : SmsSource {
    override suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow> = withContext(Dispatchers.IO) {
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
            "${Telephony.Sms._ID} ASC LIMIT $limit",
        ) ?: return@withContext emptyList()
        cursor.use {
            while (it.moveToNext()) {
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
        rows
    }
}

/** Where the sync stopped last time. */
interface SmsCursorStore {
    suspend fun lastId(): Long
    suspend fun advance(to: Long, at: Instant)
    val lastSyncAt: Flow<Instant?>
}

class DataStoreSmsCursorStore(private val store: DataStore<Preferences>) : SmsCursorStore {
    override suspend fun lastId(): Long = store.data.first()[LAST_ID] ?: 0L

    override suspend fun advance(to: Long, at: Instant) {
        store.edit {
            it[LAST_ID] = to
            it[LAST_SYNC] = at.toEpochMilliseconds()
        }
    }

    override val lastSyncAt: Flow<Instant?> = store.data.map { p -> p[LAST_SYNC]?.let { Instant.fromEpochMilliseconds(it) } }

    private companion object {
        val LAST_ID = longPreferencesKey("sms_last_id")
        val LAST_SYNC = longPreferencesKey("sms_last_sync")
    }
}
