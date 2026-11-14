package net.robog.clarity.backend.sources

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
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

/** Helper runs of the lead whose reads count when it cites a source. */
private const val MAX_HELPER_RUNS = 50

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
    /** What [runId] and the helpers it started read. */
    suspend fun reads(runId: String?): RunReads {
        if (runId == null) return RunReads.NONE
        val helpers = runs.childrenOf(runId, MAX_HELPER_RUNS).map { it.id }
        return (listOf(runId) + helpers).fold(RunReads.NONE) { all, id -> all + readsOf(journal.load(id)) }
    }

    /** The card for [items], or the one error to hand the model. */
    suspend fun resolve(items: List<SourceItem>, runId: String?): Either<String, Card.Sources> {
        val wanted = requestedSources(items).fold({ return it.left() }, { it })
        val reads = reads(runId)
        val unread = unreadSources(wanted, reads)
        if (unread.isNotEmpty()) return refusal(unread).left()
        val zone = settings.homeZone()
        val missing = mutableListOf<SourceItem>()
        val resolved = wanted.mapNotNull { item ->
            when (item) {
                is SourceItem.Entity -> reader.entity(EntityId(item.id))?.let { entityItem(item.id, it.payload, zone) }.also { if (it == null) missing += item }
                is SourceItem.Web -> webItem(item.url, pageRead(item.url, reads)?.title)
            }
        }
        return if (missing.isEmpty()) sourcesCard(resolved).right() else refusal(emptyList(), missing).left()
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
class SourceRoutes(private val reader: NounReader, private val database: Database) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get("${ApiPaths.SOURCES}/{id}") {
                    val id = call.parameters["id"].orEmpty()
                    val view = if (id.isNotEmpty() && cited(id)) reader.entity(EntityId(id))?.let { sourceView(id, it.payload) } else null
                    if (view == null) call.respondProblem(EdgeProblem.NotFound("No such source.")) else call.respond(HttpStatusCode.OK, view)
                }
            }
        }
    }

    /** Whether a feed item has a sources card with this record. */
    private suspend fun cited(id: String): Boolean {
        val probe = JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("sources"),
            "items" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive(id))))),
        )))).toString()
        return database.tx {
            exec("SELECT 1 FROM feed_item WHERE cards @> ?::jsonb LIMIT 1", listOf(TextColumnType() to probe)) { it.next() } == true
        }
    }
}
