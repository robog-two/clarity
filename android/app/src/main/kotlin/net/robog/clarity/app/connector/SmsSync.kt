package net.robog.clarity.app.connector

import android.content.Context
import android.provider.Telephony
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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

/** Outgoing rows keep their `_id` while they go outbox, queued or failed, then sent: not final yet. */
const val SMS_OUTBOX = 4
const val SMS_FAILED = 5
const val SMS_QUEUED = 6

/** The row types the sync reads: the two it uploads and the three that may still turn into sent. */
val SMS_READ_TYPES = listOf(SMS_INBOX, SMS_SENT, SMS_OUTBOX, SMS_FAILED, SMS_QUEUED)

fun smsIsPending(type: Int): Boolean = type == SMS_OUTBOX || type == SMS_FAILED || type == SMS_QUEUED

/** A pending row older than this is given up on: it no longer holds the cursor back. */
val PENDING_ABANDONED_AFTER = 24.hours

/** The sync reads this far back again every [OVERLAP_EVERY], to catch late flips and low-id inserts. */
val OVERLAP_WINDOW = 3.days
val OVERLAP_EVERY = 3.hours

/** Standing in for a number the phone has no address for. */
val UNKNOWN_SENDER = Handle(Handle.Kind.OTHER, "unknown", "Unknown sender")

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
 * The other party: a phone number as the phone has it, or for alphanumeric senders
 * ("AMAZON", "Google") a handle of kind `OTHER`, since a number normalized from no digits would be
 * the same "+" for all of them. Null for a blank address.
 */
fun smsParty(address: String): Handle? {
    val trimmed = address.trim().ifEmpty { return null }
    return if (trimmed.any(Char::isDigit)) Handle.phone(trimmed) else Handle(Handle.Kind.OTHER, trimmed, trimmed)
}

/** Maps one SMS to an ingest record; null for rows that are neither received nor sent (drafts, outbox). */
fun smsToRecord(
    row: SmsRow,
    account: Handle,
    observedAt: Instant,
): IngestRecord.Upsert? {
    // A blank address is still a text: it goes up from an unknown sender, never dropped.
    val other = smsParty(row.address) ?: UNKNOWN_SENDER
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

/** A row ready to send, and what the cursor needs to stand on it. SMS and MMS share this. */
data class Outgoing(val id: Long, val dateMillis: Long, val record: IngestRecord.Upsert, val anchor: SmsAnchor? = null)

/** A row that is not final yet (still sending); it holds the cursor back while it is young. */
data class PendingRow(val id: Long, val dateMillis: Long)

/** One read from the phone: what can be sent, the cursor ceiling its pending rows set, and how far it read. */
data class Page(val items: List<Outgoing>, val ceiling: Long?, val lastId: Long, val rowCount: Int)

private fun isYoung(dateMillis: Long, now: Instant): Boolean =
    now.toEpochMilliseconds() - dateMillis < PENDING_ABANDONED_AFTER.inWholeMilliseconds

/** The lowest id the cursor must stay below: the first pending row that is still young. Null when none holds it. */
fun cursorCeiling(pending: List<PendingRow>, now: Instant): Long? =
    pending.filter { isYoung(it.dateMillis, now) }.minOfOrNull { it.id }

/** Turns what the SMS store returned into a [Page] (rows in id order). */
fun smsPage(rows: List<SmsRow>, account: Handle, now: Instant): Page {
    val sorted = rows.sortedBy { it.id }
    return Page(
        items = sorted
            .mapNotNull { row -> smsToRecord(row, account, now)?.let { Outgoing(row.id, row.dateMillis, it, anchorOf(row)) } }
            .distinctBy { it.record.sourceId },
        ceiling = cursorCeiling(sorted.filter { smsIsPending(it.type) }.map { PendingRow(it.id, it.dateMillis) }, now),
        lastId = sorted.lastOrNull()?.id ?: 0,
        rowCount = sorted.size,
    )
}

/** Splits [items] (id order) into batches the backend accepts. */
fun outgoingChunks(items: List<Outgoing>, size: Int = IngestBatch.MAX_RECORDS): List<List<Outgoing>> =
    items.sortedBy { it.id }.chunked(size)

/** Where the cursor may go after a batch, and the ceiling it must keep below from now on. */
data class CursorStep(val target: Outgoing?, val ceiling: Long?)

/**
 * The cursor stands on the newest row of [chunk] that the server took and that lies below every
 * row still unsettled: a young pending row ([ceiling]) or a young row the server refused. A row
 * refused for more than a day is given up on and no longer blocks.
 */
fun cursorStep(chunk: List<Outgoing>, ceiling: Long?, rejected: Set<SourceId>, now: Instant): CursorStep {
    val blocker = chunk.firstOrNull { it.record.sourceId in rejected && isYoung(it.dateMillis, now) }?.id
    val limit = listOfNotNull(ceiling, blocker).minOrNull()
    val target = chunk.lastOrNull { (limit == null || it.id < limit) && it.record.sourceId !in rejected }
    return CursorStep(target, limit)
}

/** The source ids the server refused. */
fun rejectedIds(results: List<RecordResult>): Set<SourceId> =
    results.filter { it.outcome is RecordOutcome.Rejected }.map { it.sourceId }.toSet()

/**
 * The cursor may move past a batch unless the server refused every record (typically because the
 * messages noun has not been granted yet; the same rows are then tried again next time).
 */
fun cursorMayAdvance(results: List<RecordResult>): Boolean =
    results.isEmpty() || results.any { it.outcome !is RecordOutcome.Rejected }

/** Records the server took (created, merged, unchanged or removed). */
fun acceptedCount(results: List<RecordResult>): Int = results.count { it.outcome !is RecordOutcome.Rejected }

interface SmsSource {
    /** Rows of the types in [SMS_READ_TYPES] with `_id > afterId`, oldest first, at most [limit]. */
    suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow>

    /** Rows from [sinceMillis] on, whatever their id (the newest [limit] of them). */
    suspend fun readSince(sinceMillis: Long, limit: Int): List<SmsRow>

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
    // Provider reads block on I/O: keep them off the caller's thread (the app-open sync runs from the UI).
    override suspend fun readAfter(afterId: Long, limit: Int): List<SmsRow> =
        withContext(Dispatchers.IO) { query("${Telephony.Sms._ID} > ?", afterId, SMS_READ_TYPES, "${Telephony.Sms._ID} ASC", limit) }

    override suspend fun readSince(sinceMillis: Long, limit: Int): List<SmsRow> = withContext(Dispatchers.IO) {
        query("${Telephony.Sms.DATE} >= ?", sinceMillis, SMS_READ_TYPES, "${Telephony.Sms.DATE} DESC", limit)
    }

    override suspend fun rowAt(id: Long): SmsRow? = withContext(Dispatchers.IO) {
        query("${Telephony.Sms._ID} = ?", id, listOf(SMS_INBOX, SMS_SENT), "${Telephony.Sms._ID} ASC", 1).firstOrNull()
    }

    override suspend fun maxId(): Long? = withContext(Dispatchers.IO) {
        // The first row of a descending sort, again without a LIMIT in the sort order.
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, "${Telephony.Sms._ID} DESC",
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun query(clause: String, arg: Long, types: List<Int>, order: String, limit: Int): List<SmsRow> {
        val rows = mutableListOf<SmsRow>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.THREAD_ID,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "$clause AND ${Telephony.Sms.TYPE} IN (${types.joinToString(",") { "?" }})",
            arrayOf(arg.toString()) + types.map { it.toString() },
            order,
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
    /** The person took the permission to read texts back; nothing can sync until it is allowed again. */
    PERMISSION_LOST,
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

    /** MMS has its own `_id` space and so its own cursor. */
    suspend fun mmsLastId(): Long
    suspend fun advanceMms(to: Long, sent: Int)
    suspend fun restartMms()

    /** When the recent window was last read again (epoch millis, 0 for never). */
    suspend fun overlapAt(): Long
    suspend fun markOverlap(at: Long)

    /** Source ids sent lately (newest last): the overlap window does not send these again. */
    suspend fun recentIds(): Set<String>
    suspend fun remember(ids: Collection<String>)

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

    override suspend fun mmsLastId(): Long = store.data.first()[MMS_LAST_ID] ?: 0L

    override suspend fun advanceMms(to: Long, sent: Int) {
        store.edit {
            it[MMS_LAST_ID] = to
            it[SENT] = (it[SENT] ?: 0L) + sent
        }
    }

    override suspend fun restartMms() {
        store.edit { it.remove(MMS_LAST_ID) }
    }

    override suspend fun overlapAt(): Long = store.data.first()[OVERLAP_AT] ?: 0L

    override suspend fun markOverlap(at: Long) {
        store.edit { it[OVERLAP_AT] = at }
    }

    override suspend fun recentIds(): Set<String> =
        store.data.first()[RECENT]?.split('\n')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    override suspend fun remember(ids: Collection<String>) {
        if (ids.isEmpty()) return
        store.edit {
            val kept = (it[RECENT]?.split('\n').orEmpty() + ids).filter { id -> id.isNotEmpty() }.distinct().takeLast(RECENT_LIMIT)
            it[RECENT] = kept.joinToString("\n")
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
        val ANCHOR_DATE = longPreferencesKey("sms_anchor_date")
        val ANCHOR_PRINT = stringPreferencesKey("sms_anchor_print")
        val MMS_LAST_ID = longPreferencesKey("mms_last_id")
        val OVERLAP_AT = longPreferencesKey("sms_overlap_at")
        val RECENT = stringPreferencesKey("sms_recent_ids")
        const val RECENT_LIMIT = 2000
        val ALL = listOf(LAST_ID, LAST_SYNC, LAST_ATTEMPT, SENT, PROBLEM, ANCHOR_DATE, ANCHOR_PRINT, MMS_LAST_ID, OVERLAP_AT, RECENT)
    }
}
