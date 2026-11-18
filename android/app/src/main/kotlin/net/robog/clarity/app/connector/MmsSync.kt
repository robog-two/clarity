package net.robog.clarity.app.connector

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.noun.MessagePayload

// MMS carries group texts, picture texts and the long texts a carrier converts. It has its own
// `_id` space and counts `date` in seconds. Reading it needs only READ_SMS.

const val MMS_BOX_INBOX = 1
const val MMS_BOX_SENT = 2
const val MMS_BOX_OUTBOX = 4

/** `m_type`: a message the person sent, a downloaded one, and the stub for one not downloaded yet. */
const val MMS_SEND_REQ = 128
const val MMS_NOTIFICATION_IND = 130
const val MMS_RETRIEVE_CONF = 132

/** Address `type` column. */
const val MMS_ADDR_BCC = 129
const val MMS_ADDR_CC = 130
const val MMS_ADDR_FROM = 137
const val MMS_ADDR_TO = 151

/** The placeholder the sender's own row holds in the address table. */
const val MMS_ADDRESS_TOKEN = "insert-address-token"

data class MmsAddress(val type: Int, val address: String)

/** One row of the phone's MMS store with its text parts, attachment types and addresses. */
data class MmsRow(
    val id: Long,
    val threadId: Long?,
    /** MMS counts `date` in seconds. */
    val dateSeconds: Long,
    val box: Int,
    val mType: Int,
    val text: String = "",
    /** Content types of the parts that are not text or layout. */
    val attachmentTypes: List<String> = emptyList(),
    val addresses: List<MmsAddress> = emptyList(),
) {
    val dateMillis: Long get() = dateSeconds * 1000
}

/** The direction of a row that is final and worth uploading (`SMS_INBOX` or `SMS_SENT` style), else null. */
fun mmsDirection(row: MmsRow): Int? = when {
    row.box == MMS_BOX_INBOX && row.mType == MMS_RETRIEVE_CONF -> MMS_BOX_INBOX
    row.box == MMS_BOX_SENT && row.mType == MMS_SEND_REQ -> MMS_BOX_SENT
    else -> null
}

/** Still sending, or a message the phone has been told about but not downloaded. It may turn into a final row. */
fun mmsIsPending(row: MmsRow): Boolean =
    row.box == MMS_BOX_OUTBOX || (row.box == MMS_BOX_INBOX && row.mType == MMS_NOTIFICATION_IND)

private fun MmsRow.cleanAddresses(): List<MmsAddress> =
    addresses.filter { it.address.isNotBlank() && it.address.trim() != MMS_ADDRESS_TOKEN }

/** The words, or a short stand-in when there are none (a photo on its own is still a message). */
fun mmsBody(row: MmsRow): String = when {
    row.text.isNotBlank() -> row.text
    row.attachmentTypes.any { it.startsWith("image/") } -> "[photo]"
    else -> "[attachment]"
}

fun mmsFingerprint(row: MmsRow): String {
    val addrs = row.cleanAddresses()
    val from = addrs.firstOrNull { it.type == MMS_ADDR_FROM }?.address?.trim().orEmpty()
    val others = addrs.filter { it.type == MMS_ADDR_TO || it.type == MMS_ADDR_CC }.map { it.address.trim() }.sorted()
    return MessageDigest.getInstance("SHA-256")
        .digest("${mmsDirection(row)}\n$from\n${others.joinToString(",")}\n${mmsBody(row)}".toByteArray(Charsets.UTF_8))
        .take(8)
        .joinToString("") { "%02x".format(it) }
}

/** `mms:v1:<date millis>:<fingerprint>`: stable per message, like the SMS ids. */
fun mmsSourceId(row: MmsRow): SourceId = SourceId("mms:v1:${row.dateMillis}:${mmsFingerprint(row)}")

/**
 * Maps one MMS to an ingest record (channel SMS, the same thread space as SMS); null for rows that
 * are not final.
 *
 * Received: the sender is FROM and the recipients are this phone plus the other TO and CC, so a
 * group keeps its members. Carriers list this phone's own number in TO as well; a single other
 * entry is taken to be it and left out, since the phone's own side is [account]. Sent: the sender
 * is this phone and the recipients are TO and CC.
 */
fun mmsToRecord(
    row: MmsRow,
    account: Handle,
    observedAt: Instant,
): IngestRecord.Upsert? {
    val direction = mmsDirection(row) ?: return null
    val addrs = row.cleanAddresses()
    val from = addrs.firstOrNull { it.type == MMS_ADDR_FROM }?.address?.let { smsParty(it) }
    val others = addrs.filter { it.type == MMS_ADDR_TO || it.type == MMS_ADDR_CC }
        .mapNotNull { smsParty(it.address) }
        .distinct()
    val (sender, recipients) = if (direction == MMS_BOX_INBOX) {
        (from ?: UNKNOWN_SENDER) to (listOf(account) + if (others.size > 1) others else emptyList())
    } else {
        account to others.ifEmpty { listOf(UNKNOWN_SENDER) }
    }
    return IngestRecord.Upsert(
        sourceId = mmsSourceId(row),
        sourceVersion = "1",
        observedAt = observedAt,
        payload = MessagePayload(
            channel = MessagePayload.Channel.SMS,
            account = account,
            sender = sender,
            recipients = recipients,
            body = mmsBody(row),
            sentAt = Instant.fromEpochMilliseconds(row.dateMillis),
            threadRef = row.threadId?.let { "sms-thread:$it" },
        ),
    )
}

/** Turns what the MMS store returned into a [Page] (rows in id order). */
fun mmsPage(rows: List<MmsRow>, account: Handle, now: Instant): Page {
    val sorted = rows.sortedBy { it.id }
    return Page(
        items = sorted
            .mapNotNull { row -> mmsToRecord(row, account, now)?.let { Outgoing(row.id, row.dateMillis, it) } }
            .distinctBy { it.record.sourceId },
        ceiling = cursorCeiling(sorted.filter(::mmsIsPending).map { PendingRow(it.id, it.dateMillis) }, now),
        lastId = sorted.lastOrNull()?.id ?: 0,
        rowCount = sorted.size,
    )
}

/** The MMS cursor is useless once the store holds nothing as high (a restore or a new phone). */
fun mmsCursorLost(lastId: Long, maxId: Long?): Boolean = lastId > 0 && maxId != null && maxId < lastId

interface MmsSource {
    /** Rows with `_id > afterId` that are final or pending, oldest first, at most [limit]; details only for final ones. */
    suspend fun readAfter(afterId: Long, limit: Int): List<MmsRow>

    /** Rows from [sinceMillis] on, whatever their id (the newest [limit] of them). */
    suspend fun readSince(sinceMillis: Long, limit: Int): List<MmsRow>

    suspend fun maxId(): Long?
}

class AndroidMmsSource(private val context: Context) : MmsSource {
    override suspend fun readAfter(afterId: Long, limit: Int): List<MmsRow> =
        withContext(Dispatchers.IO) { query("_id > ?", afterId, "_id ASC", limit) }

    override suspend fun readSince(sinceMillis: Long, limit: Int): List<MmsRow> =
        withContext(Dispatchers.IO) { query("date >= ?", sinceMillis / 1000, "date DESC", limit) }

    override suspend fun maxId(): Long? = withContext(Dispatchers.IO) {
        context.contentResolver.query(MMS, arrayOf("_id"), null, null, "_id DESC")
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    private fun query(clause: String, arg: Long, order: String, limit: Int): List<MmsRow> {
        val rows = mutableListOf<MmsRow>()
        val cursor = context.contentResolver.query(
            MMS,
            arrayOf("_id", "thread_id", "date", "msg_box", "m_type"),
            "$clause AND msg_box IN ($MMS_BOX_INBOX, $MMS_BOX_SENT, $MMS_BOX_OUTBOX) " +
                "AND m_type IN ($MMS_SEND_REQ, $MMS_NOTIFICATION_IND, $MMS_RETRIEVE_CONF)",
            arrayOf(arg.toString()),
            order,
        ) ?: return emptyList()
        cursor.use {
            while (rows.size < limit && it.moveToNext()) {
                val row = MmsRow(
                    id = it.getLong(0),
                    threadId = if (it.isNull(1)) null else it.getLong(1),
                    dateSeconds = it.getLong(2),
                    box = it.getInt(3),
                    mType = it.getInt(4),
                )
                rows += if (mmsDirection(row) != null) withDetails(row) else row
            }
        }
        return rows
    }

    private fun withDetails(row: MmsRow): MmsRow {
        val texts = mutableListOf<String>()
        val media = mutableListOf<String>()
        context.contentResolver.query(PART, arrayOf("_id", "ct", "text"), "mid = ?", arrayOf(row.id.toString()), "_id ASC")?.use {
            while (it.moveToNext()) {
                val type = it.getString(1).orEmpty()
                when {
                    type == "text/plain" -> (it.getString(2) ?: readPart(it.getLong(0)))?.let(texts::add)
                    type == "application/smil" || type.isEmpty() -> Unit
                    else -> media += type
                }
            }
        }
        val addresses = mutableListOf<MmsAddress>()
        context.contentResolver.query(Uri.parse("content://mms/${row.id}/addr"), arrayOf("address", "type"), null, null, null)?.use {
            while (it.moveToNext()) addresses += MmsAddress(it.getInt(1), it.getString(0).orEmpty())
        }
        return row.copy(text = texts.joinToString("\n"), attachmentTypes = media, addresses = addresses)
    }

    /** Big text parts live in a file: read them through the part's own uri. */
    private fun readPart(partId: Long): String? = try {
        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (_: java.io.IOException) {
        null
    }

    private companion object {
        val MMS: Uri = Uri.parse("content://mms")
        val PART: Uri = Uri.parse("content://mms/part")
    }
}
