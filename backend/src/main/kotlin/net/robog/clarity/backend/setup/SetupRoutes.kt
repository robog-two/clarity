package net.robog.clarity.backend.setup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.scheduler.Scheduler
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.session.ModelKeyRequest
import net.robog.clarity.shared.session.Settings

/** `GET/PUT /api/v1/settings`, `GET /api/v1/setup`, `PUT /api/v1/setup/model-key` (device-authenticated). */
@Inject
@ContributesIntoSet(AppScope::class)
class SetupRoutes(
    private val settings: SettingsStore,
    private val setup: SetupService,
    private val scheduler: Scheduler,
) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get(ApiPaths.SETTINGS) { call.respond(settings.current()) }
                put(ApiPaths.SETTINGS) {
                    settings.save(call.receive<Settings>()).fold(
                        { call.respondProblem(EdgeProblem.BadRequest(it)) },
                        {
                            // The call and its prep job move with the new schedule right away.
                            scheduler.resetSchedules()
                            call.respond(it)
                        },
                    )
                }
                get(ApiPaths.SETUP_STATUS) { call.respond(setup.status()) }
                put(ApiPaths.SETUP_MODEL_KEY) {
                    call.respondEither(setup.setModelKey(call.receive<ModelKeyRequest>().apiKey))
                }
            }
        }
    }
}
