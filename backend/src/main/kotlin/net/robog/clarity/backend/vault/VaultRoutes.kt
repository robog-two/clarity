package net.robog.clarity.backend.vault

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.DevicePrincipal
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondEither
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant

/** App-facing vault API (device-authenticated). See [net.robog.clarity.shared.vault] for the bodies. */
@Inject
@ContributesIntoSet(AppScope::class)
class VaultRoutes(private val vault: VaultService) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                route(ApiPaths.VAULT_DEVICES) {
                    get { call.respond(vault.deviceKeys()) }
                    post {
                        val device = call.principal<DevicePrincipal>()?.deviceId
                        if (device == null) call.respondProblem(EdgeProblem.Unauthorized())
                        else call.respondEither(vault.registerKey(device, call.receive<RegisterVaultKey>()), HttpStatusCode.Created)
                    }
                }
                route(ApiPaths.VAULT_CREDENTIALS) {
                    get { call.respond(vault.list()) }
                    post { call.respondEither(vault.complete(call.receive<NewCredential>()), HttpStatusCode.Created) }
                    delete("{id}") {
                        vault.delete(CredentialId(call.parameters["id"].orEmpty())).fold(
                            { call.respondProblem(it) },
                            { call.respond(HttpStatusCode.NoContent) },
                        )
                    }
                    get("{id}/envelope") {
                        val device = call.principal<DevicePrincipal>()?.deviceId
                        if (device == null) call.respondProblem(EdgeProblem.Unauthorized())
                        else call.respondEither(vault.envelope(CredentialId(call.parameters["id"].orEmpty()), device))
                    }
                }
                get("${ApiPaths.VAULT_REQUESTS}/{id}") {
                    call.respondEither(vault.request(CredentialRequestId(call.parameters["id"].orEmpty())))
                }
                post("${ApiPaths.VAULT_GRANTS}/{heldId}") {
                    val device = call.principal<DevicePrincipal>()?.deviceId
                    if (device == null) call.respondProblem(EdgeProblem.Unauthorized())
                    else call.respondEither(vault.release(call.heldId(), device, call.receive<ReleaseGrant>()))
                }
                post("${ApiPaths.VAULT_CODES}/{heldId}") {
                    call.respondEither(vault.releaseCode(call.heldId(), call.receive<ReleaseCode>()))
                }
            }
        }
    }

    private fun RoutingCall.heldId() = HeldActionId(parameters["heldId"].orEmpty())
}
