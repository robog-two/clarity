package net.robog.clarity.backend.http

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.routing.routing
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.shared.api.ApiPaths
import org.jetbrains.exposed.v1.jdbc.Database

private val log = KotlinLogging.logger {}

/** `GET /healthz` (process is up) and `GET /readyz` (database reachable). */
@Inject
@ContributesIntoSet(AppScope::class)
class HealthRoutes(private val database: Database) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            get(ApiPaths.HEALTH) { call.respondText("ok") }
            get(ApiPaths.READY) {
                val ready = runCatching { database.tx { exec("SELECT 1") } }
                    .onFailure { log.warn(it) { "readiness check failed" } }
                    .isSuccess
                if (ready) call.respondText("ready")
                else call.respondProblem(EdgeProblem.Unavailable("database unreachable"))
            }
        }
    }
}
