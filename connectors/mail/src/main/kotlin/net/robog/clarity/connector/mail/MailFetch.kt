package net.robog.clarity.connector.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Part
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import net.robog.clarity.connector.sdk.ConnectorRuntime
import net.robog.clarity.connector.sdk.FetchHandlers
import net.robog.clarity.connector.sdk.FetchOutcome
import net.robog.clarity.connector.sdk.fetchHandlers
import net.robog.clarity.connector.sdk.readCapped
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.fetch.FetchFailure
import net.robog.clarity.shared.fetch.FetchRequest
import net.robog.clarity.shared.noun.AttachmentMeta

private val log = KotlinLogging.logger {}

/**
 * Pure: whether a part the server says is [encodedSize] bytes as stored cannot fit [maxBytes] once decoded, so it
 * is refused before any of it is read. Base64 (the usual encoding of a file) decodes to about 73% of its size;
 * anything under that bound is still checked while reading.
 */
fun tooLargeToFetch(encodedSize: Long, maxBytes: Long): Boolean = encodedSize / 10 * 7 > maxBytes

/** Default and env name of the largest attachment served, in megabytes (the backend has the same setting). */
const val ATTACHMENT_LIMIT_ENV = "CLARITY_ATTACHMENT_MAX_MB"
private const val DEFAULT_LIMIT_MB = 25L

/** Pure: the limit in bytes from [env] (megabytes, at least 1), else the default of 25 MB. */
fun attachmentLimitFromEnv(env: Map<String, String> = System.getenv()): Long =
    (env[ATTACHMENT_LIMIT_ENV]?.trim()?.toLongOrNull()?.takeIf { it >= 1 } ?: DEFAULT_LIMIT_MB) * 1024 * 1024

/**
 * Serves the fetch channel for mail: the bytes of one MIME part of a message (or the list of its parts),
 * read from the server over IMAP on demand. Nothing is stored here and nothing is changed on the
 * server (the connection reads with PEEK, so the message is not marked seen).
 */
class MailFetches(
    private val accounts: suspend () -> List<MailAccess>,
    /** The connector's own ceiling; the request's limit applies too, the smaller wins. */
    private val maxBytes: Long = attachmentLimitFromEnv(),
) {
    constructor(runtime: ConnectorRuntime) : this({ usableMailAccounts(runtime.awaitConfig(), runtime).map(::MailAccess) })

    fun handlers(): FetchHandlers = fetchHandlers { onFetch { request -> fetch(request) } }

    suspend fun fetch(request: FetchRequest): FetchOutcome = withContext(Dispatchers.IO) {
        val all = accounts()
        val ref = MailRef.parse(request.sourceId.value, all.map { it.config.id })
            ?: return@withContext FetchOutcome.notFound("not a mail message id: ${request.sourceId.value}")
        val access = all.firstOrNull { it.config.id == ref.accountId }
            ?: return@withContext FetchOutcome.notFound("no mail account '${ref.accountId}' is set up")
        try {
            access.imap().use { store ->
                val folder = store.folder(ref.folder)
                if (!folder.exists()) return@use FetchOutcome.notFound("the folder '${ref.folder}' no longer exists")
                folder.openedUse(READ_ONLY) { f ->
                    if (f.uidValidity != ref.uidValidity) return@openedUse FetchOutcome.notFound("the folder was rebuilt; the message is gone")
                    val message = f.getMessageByUID(ref.uid) as? MimeMessage ?: return@openedUse FetchOutcome.notFound("the message is no longer on the server")
                    read(message, request)
                }
            }
        } catch (e: AuthenticationFailedException) {
            FetchOutcome.Failed(FetchFailure.Code.FAILED, "the mail server refused the login: ${e.message}")
        }
    }

    private fun read(message: MimeMessage, asked: FetchRequest): FetchOutcome {
        val request = asked.copy(maxBytes = minOf(asked.maxBytes, maxBytes))
        if (request.part == FetchRequest.LIST_PARTS) {
            val list = ClarityJson.encodeToString(ListSerializer(AttachmentMeta.serializer()), message.attachments())
            return FetchOutcome.Found(list.toByteArray(), null, "application/json")
        }
        val path = PartPath.parse(request.part) ?: return FetchOutcome.notFound("'${request.part}' is not a part path (like 2 or 1.3)")
        val part: Part = message.partAt(path) ?: return FetchOutcome.notFound("the message has no part ${request.part}")
        val declared = part.size.takeIf { it >= 0 }?.toLong()
        if (declared != null && tooLargeToFetch(declared, request.maxBytes)) {
            return FetchOutcome.tooLarge("the part is about ${declared / 1024} KB, over the limit of ${request.maxBytes / 1024} KB")
        }
        val bytes = part.inputStream.use { it.readCapped(request.maxBytes) }
            ?: return FetchOutcome.tooLarge("the part is over the limit of ${request.maxBytes / 1024} KB")
        val meta = attachmentMeta(part, path)
        log.info { "fetched part ${request.part} of ${request.sourceId.value}: ${bytes.size} bytes" }
        return FetchOutcome.Found(bytes, meta?.filename, meta?.mimeType ?: "application/octet-stream")
    }
}
