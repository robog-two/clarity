package net.robog.clarity.backend.governance

import arrow.core.left
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.shared.governance.SignedDecision
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.backend.persistence.parseWire
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.ProposeAmendment

/** App-facing governance API (device-authenticated): constitution, amendments, held actions, activity. */
@Inject
@ContributesIntoSet(AppScope::class)
class GovernanceRoutes(
    private val constitutions: ConstitutionStore,
    private val held: HeldActionService,
    private val activity: ActivityStore,
) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                route(ApiPaths.CONSTITUTION) {
                    get {
                        val raw = call.request.queryParameters["version"]
                        if (raw == null) {
                            call.respondEither(constitutions.current().orNotFound())
                        } else {
                            val version = raw.toIntOrNull()
                            if (version == null) call.respondProblem(EdgeProblem.BadRequest("version must be an integer"))
                            else call.respondEither(constitutions.at(version).orNotFound(version))
                        }
                    }
                    get("history") { call.respond(constitutions.history().map { HistoryEntry(it.version, it.createdAt) }) }
                    post {
                        call.respondEither(constitutions.create(call.receive<Constitution>()), HttpStatusCode.Created)
                    }
                    route("amendments") {
                        get {
                            val status = call.request.queryParameters["status"]?.let { parseWire<Amendment.Status>(it) }
                            call.respond(constitutions.amendments(status))
                        }
                        post { call.respondEither(constitutions.propose(call.receive<ProposeAmendment>()), HttpStatusCode.Created) }
                        post("{id}/save") { call.respondEither(constitutions.saveAmendment(AmendmentId(call.parameters["id"].orEmpty()))) }
                        post("{id}/discard") { call.respondEither(constitutions.discardAmendment(AmendmentId(call.parameters["id"].orEmpty()))) }
                    }
                }

                route(ApiPaths.HELD) {
                    get {
                        val raw = call.request.queryParameters["status"]
                        val statuses = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { parseWire<HeldAction.Status>(it) }
                        if (statuses != null && statuses.any { it == null }) {
                            call.respondProblem(EdgeProblem.BadRequest("unknown status in '$raw'"))
                        } else {
                            call.respond(held.list(statuses?.filterNotNull()?.toSet()))
                        }
                    }
                    get("{id}") { call.respondEither(held.get(call.heldId())) }
                    post("{id}/decision") { call.respondEither(held.decide(call.heldId(), call.receive<HeldDecision>())) }
                    post("{id}/signed-decision") {
                        val device = call.principal<DevicePrincipal>()?.deviceId
                        if (device == null) call.respondProblem(EdgeProblem.Unauthorized())
                        else call.respondEither(held.decideSigned(call.heldId(), device, call.receive<SignedDecision>()))
                    }
                }

                get(ApiPaths.ACTIVITY) {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                    call.respond(activity.recent(limit))
                }
            }
        }
    }

    private fun RoutingCall.heldId() = HeldActionId(parameters["id"].orEmpty())

    private fun Constitution?.orNotFound(version: Int? = null) =
        this?.let { arrow.core.Either.Right(it) } ?: GovernanceError.NoConstitution(version).left()
}

/** `GET /api/v1/constitution/history`. */
@kotlinx.serialization.Serializable
data class HistoryEntry(val version: Int, val createdAt: kotlin.time.Instant)
