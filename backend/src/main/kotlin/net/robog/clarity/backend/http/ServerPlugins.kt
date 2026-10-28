package net.robog.clarity.backend.http

import dev.zacsweers.metro.Inject
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.plugins.ContentTransformationException
import java.util.UUID
import kotlinx.serialization.SerializationException
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.auth.ConnectorAuthenticator
import net.robog.clarity.backend.auth.DeviceAuthenticator
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.ApiPaths

private val log = KotlinLogging.logger {}

/**
 * Installs the cross-cutting Ktor plugins once, before any [HttpModule] adds routes:
 * JSON, call ids + MDC logging, problem-json error mapping and the two auth providers.
 */
@Inject
class ServerPlugins(
    private val deviceAuth: DeviceAuthenticator,
    private val connectorAuth: ConnectorAuthenticator,
) {
    fun install(app: Application) = with(app) {
        install(ContentNegotiation) { json(ClarityJson) }

        install(CallId) {
            header(HttpHeaders.XRequestId)
            generate { UUID.randomUUID().toString() }
            verify { it.length in 1..128 }
        }
        install(CallLogging) {
            callIdMdc("call-id")
            // A Good's public link carries its secret in the path: never logged.
            filter { call -> call.request.path().let { it !in QUIET_PATHS && !it.startsWith(ApiPaths.GOODS_PUBLIC + "/") } }
        }

        install(Authentication) {
            bearer(AuthProviders.DEVICE) {
                realm = REALM
                authenticate { credential -> deviceAuth.authenticate(credential.token).getOrNull() }
            }
            bearer(AuthProviders.CONNECTOR) {
                realm = REALM
                authenticate { credential -> connectorAuth.authenticate(credential.token).getOrNull() }
            }
        }

        install(StatusPages) {
            exception<ProblemException> { call, e -> call.respondProblem(e.problem) }
            exception<UnsupportedMediaTypeException> { call, _ -> call.respondProblem(EdgeProblem.UnsupportedMediaType()) }
            exception<BadRequestException> { call, e -> call.respondProblem(EdgeProblem.BadRequest(e.rootMessage())) }
            exception<ContentTransformationException> { call, e -> call.respondProblem(EdgeProblem.BadRequest(e.rootMessage())) }
            exception<SerializationException> { call, e -> call.respondProblem(EdgeProblem.BadRequest(e.rootMessage())) }
            // The client went away mid-response (a phone dropping its event stream): nothing to answer, not an error.
            exception<io.ktor.util.cio.ChannelWriteException> { call, _ ->
                log.debug { "client disconnected during ${call.request.path()}" }
            }
            exception<Throwable> { call, cause ->
                log.error(cause) { "unhandled error on ${call.request.path()}" }
                call.respondProblem(EdgeProblem.Internal)
            }
            status(HttpStatusCode.Unauthorized) { call, _ -> call.respondProblem(EdgeProblem.Unauthorized()) }
            status(HttpStatusCode.NotFound) { call, _ -> call.respondProblem(EdgeProblem.NotFound()) }
            status(HttpStatusCode.MethodNotAllowed) { call, _ -> call.respondProblem(EdgeProblem.MethodNotAllowed()) }
        }
    }

    private fun Throwable.rootMessage(): String =
        generateSequence(this) { it.cause }.last().message?.lineSequence()?.firstOrNull() ?: "malformed request"

    private companion object {
        const val REALM = "clarity"
        val QUIET_PATHS = setOf("/healthz", "/readyz")
    }
}
