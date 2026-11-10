package net.robog.clarity.backend.backup

import arrow.core.Either
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.HttpProblem
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.backup.RollbackRequest

/** Device-authenticated: list the backups, make one now, and the phone-signed rollback. */
@Inject
@ContributesIntoSet(AppScope::class)
class BackupRoutes(private val backups: BackupService) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                route(ApiPaths.BACKUPS) {
                    get { call.respond(backups.list()) }
                    post { call.respondEither(backups.backupNow().widen(), HttpStatusCode.Created) }
                    post("rollback") {
                        val device = call.principal<DevicePrincipal>()?.deviceId
                        if (device == null) call.respondProblem(EdgeProblem.Unauthorized())
                        else call.respondEither(backups.rollback(device, call.receive<RollbackRequest>()).widen(), HttpStatusCode.Accepted)
                    }
                }
            }
        }
    }

    private fun <A : Any> Either<BackupError, A>.widen(): Either<HttpProblem, A> = mapLeft { it }
}
