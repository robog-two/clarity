package net.robog.clarity.backend.connectors

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlin.time.Duration.Companion.seconds
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorPrincipal
import net.robog.clarity.backend.governance.ActionOutbox
import net.robog.clarity.backend.governance.MAX_WAIT
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.ProblemException
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.api.ApiPaths

/**
 * The action channel (`/ingest/v1/actions`): connectors, and devices acting as `device-<id>`
 * connectors, long-poll for the actions addressed to them and report results.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class ActionRoutes(private val outbox: ActionOutbox) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.CONNECTOR) {
                route(ApiPaths.ACTIONS) {
                    // wait: seconds to hold the request open when nothing is due (0..30)
                    get {
                        val wait = (call.request.queryParameters["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, MAX_WAIT.inWholeSeconds).seconds
                        call.respond(ActionPoll(outbox.leaseOrWait(call.connector(), wait)))
                    }
                    post("{id}/result") {
                        val result = call.receive<ActionResult>()
                        outbox.complete(call.connector(), ActionId(call.parameters["id"].orEmpty()), result.outcome).fold(
                            { call.respondProblem(it) },
                            { call.respond(HttpStatusCode.NoContent) },
                        )
                    }
                }
            }
        }
    }

    private fun ApplicationCall.connector(): ConnectorId =
        principal<ConnectorPrincipal>()?.connectorId ?: throw ProblemException(EdgeProblem.Unauthorized())
}
