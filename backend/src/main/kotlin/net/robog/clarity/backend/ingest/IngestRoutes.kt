package net.robog.clarity.backend.ingest

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorPrincipal
import net.robog.clarity.backend.connectors.ConnectorRegistry
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.ProblemException
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.ingest.IngestBatch

/** Connector-facing API under `/ingest/v1`: manifest, polled config and batch push. */
@Inject
@ContributesIntoSet(AppScope::class)
class IngestRoutes(
    private val ingest: IngestService,
    private val registry: ConnectorRegistry,
) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.CONNECTOR) {
                route(ApiPaths.INGEST) {
                    put("manifest") {
                        registry.acceptManifest(call.connector(), call.receive<ConnectorManifest>())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("config") {
                        val config = registry.configOf(call.connector())
                        val etag = etagOf(config.version)
                        call.response.header(HttpHeaders.ETag, etag)
                        if (etagMatches(call.request.header(HttpHeaders.IfNoneMatch), etag)) {
                            call.respond(HttpStatusCode.NotModified)
                        } else {
                            call.respond(config)
                        }
                    }
                    post("batches") {
                        call.respondEither(ingest.ingest(call.connector(), call.receive<IngestBatch>()))
                    }
                }
            }
        }
    }

    private fun ApplicationCall.connector(): ConnectorId =
        principal<ConnectorPrincipal>()?.connectorId ?: throw ProblemException(EdgeProblem.Unauthorized())
}

/** Strong ETag carrying the config version. */
fun etagOf(version: Long): String = "\"$version\""

/** `If-None-Match` evaluation: `*` or any listed tag (weak prefix ignored) equal to [etag]. */
fun etagMatches(ifNoneMatch: String?, etag: String): Boolean =
    ifNoneMatch?.split(',')?.map { it.trim().removePrefix("W/") }?.any { it == "*" || it == etag } ?: false
