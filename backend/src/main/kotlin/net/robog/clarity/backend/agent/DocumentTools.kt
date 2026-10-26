package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.backend.governance.ActionResults
import net.robog.clarity.backend.governance.ProposalOutcome
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.Stored
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.DocumentPayload

/** Result of asking a connector for a document's full text. */
sealed interface DocumentFetch {
    data class Text(val content: String) : DocumentFetch

    /** Nothing to show yet or ever: [message] tells the agent what to do next. */
    data class Unavailable(val message: String) : DocumentFetch
}

/** Gets the full text of a stored document from the connector that owns it. */
fun interface DocumentFetcher {
    suspend fun fetch(document: Stored<DocumentPayload>): DocumentFetch
}

/**
 * Proposes a `ReadDocument` action through the [ActionGate] (the guard decides whether the person
 * must approve reads) and waits up to [timeout] for the connector's `Done.content`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class GatedDocumentFetcher(
    private val gate: ActionGate,
    private val results: ActionResults,
    private val clock: Clock,
) : DocumentFetcher {
    var timeout: Duration = 30.seconds

    override suspend fun fetch(document: Stored<DocumentPayload>): DocumentFetch = coroutineScope {
        // A retried tool call within the same window reuses the same action instead of queueing another read.
        val key = "read-document:${document.id.value}:${clock.now().toEpochMilliseconds() / READ_WINDOW.inWholeMilliseconds}"
        // Subscribe before proposing so a fast connector cannot answer before we listen.
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeout) {
                results.events.first { it.action is ProposedAction.ReadDocument && it.action.idempotencyKey == key }
            }
        }
        val action = ProposedAction.ReadDocument(
            idempotencyKey = key,
            summary = "read \"${document.payload.title}\" (${document.payload.path})",
            document = document.id,
        )
        val outcome = gate.propose(
            action = action,
            agentView = "I need the full text of ${document.payload.path} to answer.",
            context = listOf(ContextItem(tag = "document · ${document.payload.store}", title = document.payload.title, entity = document.id)),
            provenance = Provenance(runId = "read-document", untrustedInput = false),
            urgency = Urgency.NORMAL,
        )
        when (outcome) {
            is ProposalOutcome.Held, is ProposalOutcome.Blocked -> {
                answer.cancel()
                DocumentFetch.Unavailable(outcome.text)
            }
            is ProposalOutcome.Allowed -> when (val event = answer.await()) {
                null -> DocumentFetch.Unavailable(
                    "Still fetching ${document.payload.path}: the connector has not answered yet. Try read_document again in a minute; do not guess its contents.",
                )
                else -> when (val o = event.outcome) {
                    is ActionOutcome.Done -> o.content?.let { DocumentFetch.Text(it) }
                        ?: DocumentFetch.Unavailable("The connector read ${document.payload.path} but returned no text.")
                    is ActionOutcome.Failed -> DocumentFetch.Unavailable("Could not read ${document.payload.path}: ${o.message}")
                }
            }
        }
    }

    private companion object { val READ_WINDOW = 10.minutes }
}

/**
 * Agent tools over the `document` noun: `search_documents` (title, path, extracted excerpt) and
 * `read_document` (full text on demand). Registered with [DomainTools].
 */
@Inject
@SingleIn(AppScope::class)
class DocumentTools(private val reader: NounReader, private val fetcher: DocumentFetcher) {

    val all: List<Tool> by lazy { listOf(searchDocuments, readDocument) }

    @Serializable
    class SearchDocumentsInput(
        @Description("Words to look for in document titles, paths and text excerpts; every word must match") val query: String,
        @Description("Maximum documents to return (1-50, default 10)") val limit: Int = 10,
    )

    private val searchDocuments = tool<SearchDocumentsInput>(
        name = "search_documents",
        description = "Search the user's files (cloud storage, notes, PDFs) by title, path and a short text excerpt. " +
            "Results carry an id; call read_document with it for the full text.",
        json = ClarityJson,
    ) { input ->
        val found = reader.searchDocuments(input.query, input.limit.coerceIn(1, MAX_SEARCH))
        ToolResult.Ok(JsonArray(found.map { it.summaryJson() }).toString())
    }

    @Serializable
    class ReadDocumentInput(@Description("Document id as returned by search_documents") val id: String)

    private val readDocument = tool<ReadDocumentInput>(
        name = "read_document",
        description = "Fetch the full text of a document found with search_documents. The file connector reads it " +
            "on demand, which can take a few seconds; if the answer says it is still fetching, try again shortly.",
        json = ClarityJson,
    ) { input ->
        val document = reader.document(EntityId(input.id)) ?: return@tool ToolResult.Error("No document with id ${input.id}")
        when (val fetched = fetcher.fetch(document)) {
            is DocumentFetch.Text -> ToolResult.Ok(
                buildJsonObject {
                    put("id", document.id.value)
                    put("title", document.payload.title)
                    put("path", document.payload.path)
                    put("text", fetched.content.cappedForModel())
                }.toString(),
            )
            is DocumentFetch.Unavailable -> ToolResult.Error(fetched.message)
        }
    }

    private companion object {
        const val MAX_SEARCH = 50
        const val MAX_MODEL_CHARS = 60_000

        fun String.cappedForModel(): String =
            if (length <= MAX_MODEL_CHARS) this else take(MAX_MODEL_CHARS) + "…[truncated ${length - MAX_MODEL_CHARS} chars]"
    }
}

private fun Stored<DocumentPayload>.summaryJson(): JsonElement = buildJsonObject {
    put("id", id.value)
    put("title", payload.title)
    put("path", payload.path)
    put("store", payload.store)
    payload.mimeType?.let { put("type", it) }
    payload.size?.let { put("size", it) }
    payload.modifiedAt?.let { put("modifiedAt", it.toString()) }
    if (payload.excerpt.isNotEmpty()) put("excerpt", JsonPrimitive(payload.excerpt.take(EXCERPT_CHARS)))
}

private const val EXCERPT_CHARS = 400
