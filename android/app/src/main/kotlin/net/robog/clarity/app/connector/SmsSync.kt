package net.robog.clarity.app.connector

import android.content.Context
import android.provider.Telephony
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
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

/**
 * `sms:v2:<date millis>:<fingerprint>`: stable per text, unlike the provider `_id`, which a backup
 * restore or a new phone renumbers. With `sms:<_id>` a renumbering either skipped texts (the cursor
 * ahead of the new ids) or made a different text look like one the server already had, which the
 * server then dropped as a replay. SMS rows never change once stored, so the version is constant.
 *
 * Texts uploaded before this keep their `sms:<_id>` records on the server and are not sent again,
 * unless a renumbering forces a rescan; then the server's content dedup (channel, account, sender,
 * recipients, body) merges each into the message it already has. A merge is not a new message:
 * no duplicate, and no trigger fires again.
 */
fun smsSourceId(row: SmsRow): SourceId = SourceId("sms:v2:${row.dateMillis}:${smsFingerprint(row)}")

/** The id the first version used (`sms:<_id>`); what the server holds for texts uploaded before v2. */
fun legacySmsSourceId(row: SmsRow): SourceId = SourceId("sms:${row.id}")

/** 16 hex digits of SHA-256 over what makes a text itself: its direction, the other party and the words. */
fun smsFingerprint(row: SmsRow): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${row.type}\n${row.address.trim()}\n${row.body}".toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }

/** The text the cursor stands on, so that a renumbering can be noticed. */
data class SmsAnchor(val dateMillis: Long, val fingerprint: String)

fun anchorOf(row: SmsRow): SmsAnchor = SmsAnchor(row.dateMillis, smsFingerprint(row))

/** Whether the stored cursor can still be trusted. */
sealed interface CursorCheck {
    /** Carry on after the cursor. */
    data object Keep : CursorCheck

    /** A cursor stored before anchors existed, still standing on a text: remember that text, no rescan. */
    data class Adopt(val anchor: SmsAnchor) : CursorCheck

    /** The ids were renumbered (or the cursor's text is gone): read everything again from the start. */
    data object Rescan : CursorCheck
}

/**
 * Checks the cursor against the phone's SMS store before reading after it. [atCursor] is the row
 * with the cursor's `_id` (null when there is none) and [maxId] the highest `_id` (null if unknown).
 *
 * - The highest id is below the cursor: the store was restored or replaced and numbers from lower
 *   down, so everything after the restore would be skipped. Rescan.
 * - The cursor's row is a different text than the one recorded, or it is gone: renumbered (or the
 *   person deleted that very text). Rescan: source ids are stable, so what the server already has
 *   comes back as a replay, not a duplicate.
 * - A cursor stored before anchors existed can only be checked by the first rule; it adopts the
 *   row it stands on instead of re-sending every text once on upgrade.
 */
fun checkCursor(lastId: Long, anchor: SmsAnchor?, atCursor: SmsRow?, maxId: Long?): CursorCheck = when {
    lastId <= 0 -> CursorCheck.Keep
    maxId != null && maxId < lastId -> CursorCheck.Rescan
    anchor == null -> atCursor?.let { CursorCheck.Adopt(anchorOf(it)) } ?: CursorCheck.Keep
    atCursor == null -> CursorCheck.Rescan
    anchorOf(atCursor) != anchor -> CursorCheck.Rescan
    else -> CursorCheck.Keep
}


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

/**
 * Splits [rows] into batches the backend accepts, each with its newest row (where the cursor goes
 * next). The same text held twice (a restore on top of what was there) goes once.
 */
fun smsBatches(rows: List<SmsRow>, account: Handle, observedAt: Instant, size: Int = IngestBatch.MAX_RECORDS): List<Pair<IngestBatch, SmsRow>> =
    rows.sortedBy { it.id }
        .chunked(size)
        .map { chunk ->
            IngestBatch(chunk.mapNotNull { smsToRecord(it, account, observedAt) }.distinctBy { it.sourceId }) to chunk.maxBy { it.id }
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
    /** Received and sent rows with `_id > afterId`, oldest first, at most [limit]. */
    suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow>

    /** The received or sent row with this `_id`, if there is one. */
    suspend fun rowAt(id: Long): SmsRow?

    /** The highest `_id` in the store (of any type), or null when it is empty or cannot be read. */
    suspend fun maxId(): Long?
}

/**
 * Reads the system SMS store (every SIM: the provider holds all subscriptions' messages). Rows are
 * read through the cursor window and the loop stops at [limit], so a huge inbox is never loaded at
 * once; no `LIMIT` is put in the sort order, which strict providers may refuse.
 */
class AndroidSmsSource(private val context: Context) : SmsSource {
    override suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow> =
        query("${Telephony.Sms._ID} > ?", afterId, limit)

    override suspend fun rowAt(id: Long): SmsRow? = query("${Telephony.Sms._ID} = ?", id, 1).firstOrNull()

    override suspend fun maxId(): Long? {
        // The first row of a descending sort, again without a LIMIT in the sort order.
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, "${Telephony.Sms._ID} DESC",
        ) ?: return null
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun query(idClause: String, id: Long, limit: Int): List<SmsRow> {
        val rows = mutableListOf<SmsRow>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "$idClause AND ${Telephony.Sms.TYPE} IN (?, ?)",
            arrayOf(id.toString(), SMS_INBOX.toString(), SMS_SENT.toString()),
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

/** Where the sync stopped last time, which text it stopped on, and how it went. */
interface SmsCursorStore {
    suspend fun lastId(): Long

    /** The text at [lastId]; null for a cursor stored before anchors existed (or none at all). */
    suspend fun anchor(): SmsAnchor? = null

    /** Remembers the text the cursor stands on ([advance] does too), without moving it. */
    suspend fun adopt(anchor: SmsAnchor) {}

    /** Back to the start (the ids were renumbered); the sync status stays. */
    suspend fun restart() {}

    /** Forgets everything, the sync status too (the server was forgotten). */
    suspend fun clear() {}

    /** Moves past [to] once the server took the batch ending there, [sent] records of it; [anchor] is the text at [to]. */
    suspend fun advance(to: Long, sent: Int, anchor: SmsAnchor? = null)
    /** The end of a sync attempt: [problem] null means it got through. */
    suspend fun finish(at: Instant, problem: SmsSyncProblem?)
    val status: Flow<SmsSyncStatus>
}

class DataStoreSmsCursorStore(private val store: DataStore<Preferences>) : SmsCursorStore {
    override suspend fun lastId(): Long = store.data.first()[LAST_ID] ?: 0L

    override suspend fun advance(to: Long, sent: Int, anchor: SmsAnchor?) {
        store.edit {
            it[LAST_ID] = to
            it[SENT] = (it[SENT] ?: 0L) + sent
            if (anchor != null) {
                it[ANCHOR_DATE] = anchor.dateMillis
                it[ANCHOR_PRINT] = anchor.fingerprint
            } else {
                it.remove(ANCHOR_DATE)
                it.remove(ANCHOR_PRINT)
            }
        }
    }

    override suspend fun anchor(): SmsAnchor? {
        val p = store.data.first()
        val date = p[ANCHOR_DATE] ?: return null
        val print = p[ANCHOR_PRINT] ?: return null
        return SmsAnchor(date, print)
    }

    override suspend fun adopt(anchor: SmsAnchor) {
        store.edit {
            it[ANCHOR_DATE] = anchor.dateMillis
            it[ANCHOR_PRINT] = anchor.fingerprint
        }
    }

    override suspend fun restart() {
        store.edit {
            it.remove(LAST_ID)
            it.remove(ANCHOR_DATE)
            it.remove(ANCHOR_PRINT)
        }
    }

    override suspend fun clear() {
        store.edit { p -> ALL.forEach { p.remove(it) } }
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
        val ANCHOR_DATE = longPreferencesKey("sms_anchor_date")
        val ANCHOR_PRINT = stringPreferencesKey("sms_anchor_print")
        val ALL = listOf(LAST_ID, LAST_SYNC, LAST_ATTEMPT, SENT, PROBLEM, ANCHOR_DATE, ANCHOR_PRINT)
    }
}
