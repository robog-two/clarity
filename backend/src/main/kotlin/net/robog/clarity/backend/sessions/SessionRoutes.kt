package net.robog.clarity.backend.sessions

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.StartSession

/** The app's session API and Today (all device-authenticated). */
@Inject
@ContributesIntoSet(AppScope::class)
class SessionRoutes(
    private val sessions: SessionService,
    private val today: TodayService,
) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                route(ApiPaths.SESSIONS) {
                    post { call.respondEither(sessions.start(call.receive<StartSession>()), HttpStatusCode.Created) }
                    get {
                        val activeOnly = call.request.queryParameters["active"]
                        when (activeOnly) {
                            null, "false" -> call.respond(sessions.recent())
                            "true" -> call.respond(sessions.active())
                            else -> call.respondProblem(EdgeProblem.BadRequest("active must be true or false"))
                        }
                    }
                    get("{id}") { call.respondEither(sessions.get(SessionId(call.parameters["id"].orEmpty()))) }
                    post("{id}/control") {
                        call.respondEither(sessions.control(SessionId(call.parameters["id"].orEmpty()), call.receive<SessionControl>()))
                    }
                }
                get(ApiPaths.TODAY) { call.respond(today.today()) }
            }
        }
    }
}
