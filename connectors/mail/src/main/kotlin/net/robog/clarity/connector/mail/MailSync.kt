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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
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
import org.eclipse.angus.mail.imap.IMAPMessage

private val log = KotlinLogging.logger {}

/**
 * Per-folder cursor: where the last sync stopped, which UIDs were reported (to find expunges and
 * to never fetch a message twice), and how far back the folder's history has been imported.
 */
@Serializable
data class FolderCursor(
    val uidValidity: Long,
    val lastUid: Long,
    val known: String,
    /**
     * Epoch millis of the oldest cutoff whose mail (UIDs up to [lastUid]) has all been imported;
     * [ALL_TIME] once the whole folder has. Null (cursors written before backfill existed, or a
     * folder whose first import has not finished): no history confirmed yet.
     */
    val historyFrom: Long? = null,
)

/** [FolderCursor.historyFrom] of a folder imported in full. */
const val ALL_TIME = Long.MIN_VALUE

/** Messages fetched per IMAP round trip and pushed per batch. */
private const val CHUNK = 50

/**
 * UID-based incremental sync of the configured folders. State is a [FolderCursor] per folder in
 * [state]; a changed UIDVALIDITY discards the old UIDs (tombstoning what was reported) and starts
 * over. A cursor only advances after its batch was accepted by [push].
 *
 * Each run first reports what is new (UIDs above the cursor), then imports history: older mail
 * inside [MailConfig.historyDays] that is not reported yet, newest first, marked
 * [IngestRecord.Upsert.history] so it wakes nothing. The first import of a folder is such history;
 * so is the mail a longer history brings in later. Every batch is recorded as it goes, so a restart
 * picks up where it stopped; once the gap is filled the cursor remembers how far back it reaches.
 * A shorter history only stops reaching further; nothing is removed.
 *
 * History import stops for the run once [backfillBudget] is spent (after at least one batch per
 * folder), so a big mailbox does not hold back new mail; [backfillPending] then says to run again.
 */
class MailSync(
    private val access: MailAccess,
    private val state: StateStore,
    private val clock: Clock = Clock.System,
    private val backfillBudget: Duration = 3.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val config get() = access.config

    /** True when the last run stopped importing history because its budget was spent. */
    @Volatile var backfillPending: Boolean = false
        private set

    /** Syncs every configured folder; returns the number of records pushed. */
    suspend fun syncAll(push: suspend (List<IngestRecord>) -> Unit): Int = withContext(Dispatchers.IO) {
        var total = 0
        val deadline = timeSource.markNow() + backfillBudget
        var pending = false
        access.imap().use { store ->
            for (folder in store.syncFolders(config.folders)) {
                total += folder.openedUse(READ_ONLY) { f ->
                    val run = FolderRun(f, push)
                    run.sync()
                    if (!run.backfill(deadline)) pending = true
                    run.pushed
                }
            }
        }
        backfillPending = pending
        total
    }

    /** One folder within one run. */
    private inner class FolderRun(private val folder: IMAPFolder, private val push: suspend (List<IngestRecord>) -> Unit) {
        private val key = "folder:${folder.fullName}"
        private val now = clock.now()
        private val validity = folder.uidValidity
        private lateinit var cursor: FolderCursor
        private val known = mutableSetOf<Long>()
        private lateinit var uidOf: Map<Message, Long>
        private lateinit var all: List<Message>
        var pushed = 0
            private set

        private suspend fun save(next: FolderCursor) {
            cursor = next
            state.putAs(key, next)
        }

        private suspend fun saveKnown(lastUid: Long = cursor.lastUid) = save(cursor.copy(lastUid = lastUid, known = UidSet(known).encode()))

        /** New mail and expunges. */
        suspend fun sync() {
            val stored = state.getAs<FolderCursor>(key)
            if (stored != null && stored.uidValidity != validity) {
                log.info { "UIDVALIDITY of ${folder.fullName} changed (${stored.uidValidity} -> $validity); resetting" }
                val stale = UidSet.decode(stored.known).toSet().map { tombstone(MailRef(folder.fullName, stored.uidValidity, it, config.id)) }
                stale.chunked(CHUNK).forEach { push(it); pushed += it.size }
                state.remove(key)
            }
            val previous = stored?.takeIf { it.uidValidity == validity }

            // Everything currently in the folder: needed to see expunges and to find the history to import.
            all = folder.messages.toList()
            if (all.isNotEmpty()) folder.fetch(all.toTypedArray(), FetchProfile().apply { add(UIDFolder.FetchProfileItem.UID) })
            uidOf = all.associateWith { folder.getUID(it) }
            val present = uidOf.values.toSet()

            if (previous == null) {
                // A folder seen for the first time: all it holds now is history, imported by backfill.
                save(FolderCursor(validity, present.maxOrNull() ?: 0L, "", historyFrom = null))
            } else {
                cursor = previous
                known += UidSet.decode(previous.known).toSet()
            }

            val fresh = all.filter { uidOf.getValue(it) > cursor.lastUid }.sortedBy { uidOf.getValue(it) }
            for (batch in fresh.chunked(CHUNK)) {
                report(batch, history = false)
                saveKnown(lastUid = maxOf(cursor.lastUid, batch.maxOf { uidOf.getValue(it) }))
            }

            val gone = known.filter { it !in present }
            if (gone.isNotEmpty()) {
                gone.map { tombstone(MailRef(folder.fullName, validity, it, config.id)) }.chunked(CHUNK).forEach { push(it); pushed += it.size }
                known.removeAll(gone.toSet())
                saveKnown()
            }
        }

        /**
         * Imports the history between the configured cutoff and what the cursor already reaches.
         * Returns false when [deadline] passed before the gap was filled (the rest waits for the next run).
         */
        suspend fun backfill(deadline: TimeMark): Boolean {
            val target = config.historyDays?.let { (now - it.days).toEpochMilliseconds() } ?: ALL_TIME
            val reach = cursor.historyFrom ?: Long.MAX_VALUE
            if (target >= reach) return true // already imported this far back (or further: a shorter history keeps it)

            val candidates = when {
                all.isEmpty() -> emptyList()
                target == ALL_TIME -> all
                else -> folder.search(ReceivedDateTerm(ComparisonTerm.GE, Date(target))).toList()
            }
            val gap = candidates
                .filter { m -> uidOf[m]?.let { it <= cursor.lastUid && it !in known } == true }
                .sortedByDescending { uidOf.getValue(it) }
            if (gap.isNotEmpty()) log.info { "importing ${gap.size} older messages of ${folder.fullName} (${config.label})" }
            for ((i, batch) in gap.chunked(CHUNK).withIndex()) {
                if (i > 0 && deadline.hasPassedNow()) {
                    log.info { "history import of ${folder.fullName} (${config.label}) continues next run" }
                    return false
                }
                report(batch, history = true)
                saveKnown()
            }
            save(cursor.copy(historyFrom = minOf(reach, target)))
            return true
        }

        /** Fetches and pushes [batch]; its UIDs count as known even when a message could not be read. */
        private suspend fun report(batch: List<Message>, history: Boolean) {
            folder.fetch(batch.toTypedArray(), FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.CONTENT_INFO) })
            val records = batch.mapNotNull { message ->
                val ref = MailRef(folder.fullName, validity, uidOf.getValue(message), config.id)
                try {
                    val payload = MimeMessage(message as MimeMessage).toPayload(config.fromAddress, now)
                    if (payload == null) {
                        log.warn { "skipping ${ref.sourceId}: no usable sender" }
                        null
                    } else {
                        IngestRecord.Upsert(SourceId(ref.sourceId), "1", now, payload, history = history)
                    }
                } catch (e: Exception) {
                    log.warn(e) { "skipping ${ref.sourceId}: could not be read" }
                    null
                }
            }
            if (records.isNotEmpty()) push(records)
            pushed += records.size
            batch.forEach { known += uidOf.getValue(it) }
            // Big folders are walked in full: drop what the batch loaded so memory stays flat.
            batch.forEach { (it as? IMAPMessage)?.invalidateHeaders() }
        }

        private fun tombstone(ref: MailRef) = IngestRecord.Tombstone(SourceId(ref.sourceId), "expunged", now, Noun.MESSAGE)
    }
}
