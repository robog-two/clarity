package net.robog.clarity.backend.auth

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.InstanceInfo

/** `POST /api/v1/enroll` (token or TOFU) and `GET /api/v1/instance` (device auth). */
@Inject
@ContributesIntoSet(AppScope::class)
class AuthRoutes(private val enrollment: EnrollmentService, private val instance: InstanceInfo) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            post("${ApiPaths.APP}/enroll") {
                call.respondEither(enrollment.enroll(call.receive<EnrollmentRequest>()))
            }
            authenticate(AuthProviders.DEVICE) {
                get("${ApiPaths.APP}/instance") { call.respond(instance) }
            }
        }
    }
}
