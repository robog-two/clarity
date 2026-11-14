package net.robog.clarity.backend.sources

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.robog.clarity.backend.agent.RunStore
import net.robog.clarity.backend.agent.SettingsProvider
import net.robog.clarity.backend.agent.homeZone
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.persistence.NounReader
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.RunJournal
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.source.SourceItem
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database

/** Helper runs of one lead thread whose reads count (a far larger number than a thread ever starts). */
private const val MAX_HELPER_RUNS = 5_000

/** Runs whose page index is kept. */
private const val MAX_INDEXED_RUNS = 512

/**
 * Turns the items the agent named in a sources card into the card the person sees: refuses what no run
 * read, and fills each title and sub line from Clarity's own data (the record, or the page as fetched),
 * never from the model.
 */
@Inject
@SingleIn(AppScope::class)
class SourceResolver(
    private val reader: NounReader,
    private val settings: SettingsProvider,
    private val journal: RunJournal,
    private val runs: RunStore,
) {
    /** The id and page index of each run, kept while its journal has the same length (a journal only grows). Least recently used go first. */
    private val indexed = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Pair<Int, RunReads>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, RunReads>>) = size > MAX_INDEXED_RUNS
    })

    private suspend fun readsOfRun(runId: String): RunReads {
        val size = journal.size(runId)
        indexed[runId]?.takeIf { it.first == size }?.let { return it.second }
        val messages = journal.load(runId)
        return readsOf(messages).also { indexed[runId] = messages.size to it }
    }

    /** What [runId] and every helper it started read. */
    suspend fun reads(runId: String?): RunReads {
        if (runId == null) return RunReads.NONE
        val helpers = runs.childrenOf(runId, MAX_HELPER_RUNS).map { it.id }
        return (listOf(runId) + helpers).fold(RunReads.NONE) { all, id -> all + readsOfRun(id) }
    }

    /** The card for [items], or the one error to hand the model. */
    suspend fun resolve(items: List<SourceItem>, runId: String?): Either<String, Card.Sources> {
        val wanted = requestedSources(items).fold({ return it.left() }, { canonicalSources(it) })
        val reads = reads(runId)
        val unread = unreadSources(wanted, reads)
        if (unread.isNotEmpty()) return refusal(unread).left()
        val zone = settings.homeZone()
        val missing = mutableListOf<SourceItem>()
        val resolved = wanted.mapNotNull { item ->
            when (item) {
                is SourceItem.Entity -> reader.entity(EntityId(item.id))?.let { entityItem(item.id, it.payload, zone) }.also { if (it == null) missing += item }
                is SourceItem.Web -> webItem(item.url, pageRead(item.url, reads)?.title)
                SourceItem.Unknown -> null
            }
        }
        return if (missing.isEmpty()) sourcesCard(resolved).right() else refusal(emptyList(), missing).left()
    }
}

/** Whether some Sources card in the feed cites a record: the device routes serve only those. */
fun interface CitedRecords {
    suspend fun cited(id: String): Boolean
}

/** [CitedRecords] over `feed_item.cards` (a jsonb containment query, served by `feed_item_cards_idx`). */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FeedCitedRecords(private val database: Database) : CitedRecords {
    override suspend fun cited(id: String): Boolean {
        val probe = JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("sources"),
            "items" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(id))))),
        )))).toString()
        return database.tx {
            exec("SELECT 1 FROM feed_item WHERE cards @> ?::jsonb LIMIT 1", listOf(TextColumnType() to probe)) { it.next() } == true
        }
    }
}

/**
 * `GET /api/v1/sources/{entityId}`: a record as stored, for the person to check what the agent said.
 * Only a record some Sources card in the feed cites is served (404 otherwise, the same as for an unknown
 * or deleted one): the device is trusted, but this keeps the route from being a second way to browse
 * mail.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class SourceRoutes(private val reader: NounReader, private val citations: CitedRecords) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get("${ApiPaths.SOURCES}/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val view = if (id.isNotEmpty() && citations.cited(id)) reader.entity(EntityId(id))?.let { sourceView(id, it.payload) } else null
                    if (view == null) call.respondProblem(EdgeProblem.NotFound("No such source.")) else call.respond(HttpStatusCode.OK, view)
                }
            }
        }
    }
}
