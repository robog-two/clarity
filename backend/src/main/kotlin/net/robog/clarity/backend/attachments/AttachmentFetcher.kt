package net.robog.clarity.backend.attachments

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.HttpStatusCode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.connectors.grantedNouns
import net.robog.clarity.backend.governance.OutboxSignals
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.SourceLinks
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchRequest
import net.robog.clarity.shared.noun.AttachmentMeta
import net.robog.clarity.shared.noun.Noun
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll

/** An attachment's bytes with the name and type to offer them under. */
class Attachment(val bytes: ByteArray, val filename: String, val mimeType: String)

/** Why an attachment could not be had; each knows how it appears on the wire. */
sealed interface FetchError : HttpProblem {
    data class NotFound(override val detail: String) : FetchError {
        override val status = HttpStatusCode.NotFound
        override val title = "No such attachment"
    }

    data class TooLarge(val maxBytes: Long) : FetchError {
        override val status = HttpStatusCode.PayloadTooLarge
        override val title = "Attachment too large"
        override val detail = "The attachment is larger than ${maxBytes / 1024 / 1024} MB."
    }

    data object Timeout : FetchError {
        override val status = HttpStatusCode.GatewayTimeout
        override val title = "The source did not answer"
        override val detail = "The connector that holds this message did not answer in time. Try again in a moment."
    }

    data class Failed(override val detail: String) : FetchError {
        override val status = HttpStatusCode.BadGateway
        override val title = "The source could not provide the attachment"
    }
}

/** What the agent's attachment tools need: an attachment's bytes, and the list of a message's attachments. */
interface AttachmentSource {
    suspend fun fetch(entityId: EntityId, part: String): Either<FetchError, Attachment>

    suspend fun list(entityId: EntityId): Either<FetchError, List<AttachmentMeta>>
}

/**
 * Gets the bytes of a message's attachment from the connector that holds the message, over the fetch
 * channel (read-only: not an action, nothing is approved or changed). The bytes are cached for a while (see
 * [AttachmentFetchStore]), so asking twice is one trip to the mail server.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AttachmentFetcher(
    private val database: Database,
    private val store: AttachmentFetchStore,
    private val reader: NounReader,
    private val clock: Clock,
    private val config: AppConfig,
    private val signals: OutboxSignals,
) : AttachmentSource {
    private val limits get() = config.attachments

    /** Attachment [part] of message [entityId], waiting up to the configured time for the connector. */
    override suspend fun fetch(entityId: EntityId, part: String): Either<FetchError, Attachment> {
        if (!isFilePart(part)) return FetchError.NotFound("'$part' is not a part path (like 2 or 1.3).").left()
        val message = reader.message(entityId) ?: return FetchError.NotFound("No such message.").left()
        val meta = message.payload.attachments.firstOrNull { it.part == part }
        if (message.payload.attachments.isNotEmpty() && meta == null) {
            return FetchError.NotFound("The message has no attachment $part. Its attachments: ${message.payload.attachments.joinToString { it.part }}.").left()
        }
        if (meta != null && sizeVerdict(meta.size, limits.maxBytes) == SizeVerdict.TOO_LARGE) return FetchError.TooLarge(limits.maxBytes).left()
        val file = obtain(entityId, part).fold({ return it.left() }, { it })
        val mime = file.mimeType.takeIf { it != "application/octet-stream" } ?: meta?.mimeType ?: file.mimeType
        return Attachment(file.bytes, sanitizeFilename(file.filename ?: meta?.filename, part, mime), mime).right()
    }

    /**
     * The attachments of message [entityId]: the list kept with the message, or for a message ingested before
     * lists existed, asked from the connector.
     */
    override suspend fun list(entityId: EntityId): Either<FetchError, List<AttachmentMeta>> {
        val message = reader.message(entityId) ?: return FetchError.NotFound("No such message.").left()
        if (message.payload.attachments.isNotEmpty()) return message.payload.attachments.right()
        val file = obtain(entityId, FetchRequest.LIST_PARTS).fold({ return it.left() }, { it })
        return runCatching { ClarityJson.decodeFromString(ListSerializer(AttachmentMeta.serializer()), file.bytes.decodeToString()) }
            .fold({ it.right() }, { FetchError.Failed("The connector sent a list this server cannot read.").left() })
    }

    /** Where this message lives at a connector that may be asked: its live source links, newest version first. */
    private suspend fun sources(entityId: EntityId): List<FetchKeyBase> {
        val uuid = Uuid.parseOrNull(entityId.value) ?: return emptyList()
        return database.tx {
            SourceLinks.selectAll()
                .where { (SourceLinks.entityId eq uuid) and (SourceLinks.noun eq "message") and SourceLinks.deletedAt.isNull() }
                .map { FetchKeyBase(ConnectorId(it[SourceLinks.connectorId]), uuid, it[SourceLinks.sourceId], it[SourceLinks.latestVersion]) }
                .filter { Noun.MESSAGE in grantedNouns(it.connectorId) }
        }
    }

    private suspend fun obtain(entityId: EntityId, part: String): Either<FetchError, StoredFile> {
        val candidates = sources(entityId)
        if (candidates.isEmpty()) return FetchError.NotFound("The message has no live source to read it from.").left()
        var last: FetchError = FetchError.NotFound("The message is no longer at its source.")
        for (source in candidates) {
            when (val result = ask(FetchKey(source.connectorId, source.entityId, source.sourceId, source.version, part))) {
                is Either.Right -> return result
                is Either.Left -> {
                    last = result.value
                    // Gone from one folder but possibly still in another: try the next source of the same message.
                    if (last !is FetchError.NotFound) return result
                }
            }
        }
        return last.left()
    }

    private suspend fun ask(key: FetchKey): Either<FetchError, StoredFile> {
        val ticket = store.request(key, clock.now(), limits.ttl)
        if (ticket is Ticket.Waiting) signals.signal() // wakes the connector's long poll
        val id = when (ticket) {
            is Ticket.Cached -> return ticket.file.right()
            is Ticket.Waiting -> ticket.id
        }
        val deadline = clock.now() + limits.waitTimeout
        while (true) {
            when (val answer = store.answer(id)) {
                is Answer.Done -> return answer.file.right()
                is Answer.Failed -> return when (answer.code) {
                    FetchFailure.Code.NOT_FOUND -> FetchError.NotFound(answer.message)
                    FetchFailure.Code.TOO_LARGE -> FetchError.TooLarge(limits.maxBytes)
                    FetchFailure.Code.FAILED -> FetchError.Failed(answer.message)
                }.left()
                Answer.Pending -> if (clock.now() >= deadline) return FetchError.Timeout.left() else delay(POLL)
            }
        }
    }

    private data class FetchKeyBase(val connectorId: ConnectorId, val entityId: Uuid, val sourceId: String, val version: String)

    private companion object {
        val POLL = 200.milliseconds
    }
}
