package net.robog.clarity.backend.connectors

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.connector.GrantsUpdate

/** App-facing connector management (`/api/v1/connectors`), device-authenticated. */
@Inject
@ContributesIntoSet(AppScope::class)
class ConnectorRoutes(private val registry: ConnectorRegistry) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                route("${ApiPaths.APP}/connectors") {
                    get { call.respond(registry.list()) }

                    put("{id}/grants") {
                        val update = call.receive<GrantsUpdate>()
                        val result = call.connectorId().let { id ->
                            id.fold({ it.left() }, { registry.setGrants(it, update.grants) })
                        }
                        call.respondEither(result)
                    }
                    get("{id}/config") {
                        call.respondEither(call.connectorId().let { id -> id.fold({ it.left() }, { registry.getConfig(it) }) })
                    }
                    put("{id}/config") {
                        val values = call.receive<JsonObject>()
                        call.respondEither(call.connectorId().let { id -> id.fold({ it.left() }, { registry.setConfig(it, values) }) })
                    }
                }
            }
        }
    }

    private fun ApplicationCall.connectorId(): Either<ConnectorError, ConnectorId> {
        val raw = parameters["id"].orEmpty()
        return if (ConnectorId.PATTERN.matches(raw)) ConnectorId(raw).right() else ConnectorError.InvalidId(raw).left()
    }
}
