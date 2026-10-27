package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.feed.Nudges

/** `GET /api/v1/nudges[?since=<ISO instant>]`: pending nudges and the timeline of resolved ones (default the last 7 days). */
@Inject
@ContributesIntoSet(AppScope::class)
class NudgeRoutes(private val nudges: NudgeStore, private val clock: Clock) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get(ApiPaths.NUDGES) {
                    val raw = call.request.queryParameters["since"]
                    val since = if (raw == null) clock.now() - 7.days else runCatching { Instant.parse(raw) }.getOrNull()
                    if (since == null) {
                        call.respondProblem(EdgeProblem.BadRequest("since must be an ISO-8601 instant, e.g. 2026-10-27T00:00:00Z"))
                    } else {
                        call.respond(Nudges(pending = nudges.pending(), history = nudges.history(since)))
                    }
                }
            }
        }
    }
}
