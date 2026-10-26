package net.robog.clarity.connector.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.FetchProfile
import jakarta.mail.Message
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.robog.clarity.connector.sdk.StateStore
import net.robog.clarity.connector.sdk.getAs
import net.robog.clarity.connector.sdk.putAs
import net.robog.clarity.shared.SourceId
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.noun.Noun
import org.eclipse.angus.mail.imap.IMAPFolder

private val log = KotlinLogging.logger {}

/** Per-folder cursor: where the last sync stopped and which UIDs were reported (to find expunges). */
@Serializable
data class FolderCursor(val uidValidity: Long, val lastUid: Long, val known: String)

/** Messages fetched per IMAP round trip and pushed per batch. */
private const val CHUNK = 50

/**
 * UID-based incremental sync of the configured folders. State is a [FolderCursor] per folder in
 * [state]; a changed UIDVALIDITY discards the old UIDs (tombstoning what was reported) and starts
 * over inside the initial window. A cursor only advances after its batch was accepted by [push].
 */
class MailSync(
    private val access: MailAccess,
    private val state: StateStore,
    private val clock: Clock = Clock.System,
) {
    private val config get() = access.config

    /** Syncs every configured folder; returns the number of records pushed. */
    suspend fun syncAll(push: suspend (List<IngestRecord>) -> Unit): Int = withContext(Dispatchers.IO) {
        var total = 0
        access.imap().use { store ->
            for (name in config.folders) {
                val folder = store.folder(name)
                if (!folder.exists()) {
                    log.warn { "folder '$name' does not exist; skipping" }
                    continue
                }
                total += folder.openedUse(READ_ONLY) { syncFolder(it, push) }
            }
        }
        total
    }

    private suspend fun syncFolder(folder: IMAPFolder, push: suspend (List<IngestRecord>) -> Unit): Int {
        val key = "folder:${folder.fullName}"
        val now = clock.now()
        val validity = folder.uidValidity
        var cursor = state.getAs<FolderCursor>(key)
        var pushed = 0

        val previous = cursor
        if (previous != null && previous.uidValidity != validity) {
            log.info { "UIDVALIDITY of ${folder.fullName} changed (${previous.uidValidity} -> $validity); resetting" }
            val stale = UidSet.decode(previous.known).toSet().map { tombstone(MailRef(folder.fullName, previous.uidValidity, it), now) }
            stale.chunked(CHUNK).forEach { push(it); pushed += it.size }
            cursor = null
            state.remove(key)
        }

        val fetchProfile = FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) }
        val known = cursor?.let { UidSet.decode(it.known).toSet() }?.toMutableSet() ?: mutableSetOf()
        var lastUid = cursor?.lastUid ?: 0L

        // Everything currently in the folder: needed to see expunges and, on the first run, the window.
        val all = folder.messages.toList()
        if (all.isNotEmpty()) folder.fetch(all.toTypedArray(), fetchProfile)
        val uidOf = all.associateWith { folder.getUID(it) }
        val present = uidOf.values.toSet()

        val fresh: List<Message> = if (cursor == null) {
            val cutoff = Date((now - config.initialWindowDays.days).toEpochMilliseconds())
            val inWindow = if (all.isEmpty()) emptyList() else folder.search(ReceivedDateTerm(ComparisonTerm.GE, cutoff)).toList()
            inWindow.filter { it in uidOf }.sortedBy { uidOf.getValue(it) }
        } else {
            all.filter { uidOf.getValue(it) > lastUid }.sortedBy { uidOf.getValue(it) }
        }

        for (batch in fresh.chunked(CHUNK)) {
            val toFetch = batch.toTypedArray()
            folder.fetch(toFetch, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.CONTENT_INFO) })
            val records = batch.mapNotNull { message ->
                val uid = uidOf.getValue(message)
                val ref = MailRef(folder.fullName, validity, uid)
                try {
                    val payload = MimeMessage(message as MimeMessage).toPayload(config.fromAddress, now)
                    if (payload == null) {
                        log.warn { "skipping ${ref.sourceId}: no usable sender" }
                        null
                    } else {
                        IngestRecord.Upsert(SourceId(ref.sourceId), "1", now, payload)
                    }
                } catch (e: Exception) {
                    log.warn(e) { "skipping ${ref.sourceId}: could not be read" }
                    null
                }
            }
            if (records.isNotEmpty()) push(records)
            pushed += records.size
            batch.forEach { known += uidOf.getValue(it) }
            lastUid = maxOf(lastUid, batch.maxOf { uidOf.getValue(it) })
            state.putAs(key, FolderCursor(validity, lastUid, UidSet(known).encode()))
        }
        // On the first run the cursor must exist even when the window was empty, so later runs are incremental.
        if (cursor == null && fresh.isEmpty()) {
            lastUid = present.maxOrNull() ?: 0L
        }

        val gone = known.filter { it !in present }
        if (gone.isNotEmpty()) {
            gone.map { tombstone(MailRef(folder.fullName, validity, it), now) }.chunked(CHUNK).forEach { push(it); pushed += it.size }
            known.removeAll(gone.toSet())
        }
        // Messages older than the window stay unreported; moving the cursor past them keeps later runs incremental.
        lastUid = maxOf(lastUid, present.maxOrNull() ?: 0L)
        state.putAs(key, FolderCursor(validity, lastUid, UidSet(known).encode()))
        return pushed
    }

    private fun tombstone(ref: MailRef, now: Instant) = IngestRecord.Tombstone(SourceId(ref.sourceId), "expunged", now, Noun.MESSAGE)
}
