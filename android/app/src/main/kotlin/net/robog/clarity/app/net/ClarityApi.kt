package net.robog.clarity.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.api.InstanceInfo
import net.robog.clarity.shared.api.Problem

/** Typed access to the instance's device API. Every method returns [ApiResult]; nothing throws. */
interface ClarityApi {
    /** Unauthenticated: registers the device key. [baseUrl] is the (validated) instance URL. */
    suspend fun enroll(baseUrl: String, request: EnrollmentRequest): ApiResult<EnrollmentResponse>

    suspend fun instance(): ApiResult<InstanceInfo>
}

private val SkipAuth = AttributeKey<Boolean>("clarity.skipAuth")

/** Attaches `Authorization: Bearer <device JWT>` (freshly signed) to every request not marked [SkipAuth]. */
private fun deviceAuthPlugin(signer: DeviceJwtSigner, sessions: SessionStore) =
    createClientPlugin("ClarityDeviceAuth") {
        onRequest { request, _ ->
            if (request.attributes.contains(SkipAuth)) return@onRequest
            val session = sessions.session.first() ?: return@onRequest
            request.bearerAuth(signer.token(session.deviceId))
        }
    }

fun createHttpClient(
    signer: DeviceJwtSigner,
    sessions: SessionStore,
    engine: HttpClientEngine = OkHttp.create(),
): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(ContentNegotiation) { json(ClarityJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 30_000
    }
    install(deviceAuthPlugin(signer, sessions))
}

class KtorClarityApi(
    private val client: HttpClient,
    private val sessions: SessionStore,
) : ClarityApi {

    override suspend fun enroll(baseUrl: String, request: EnrollmentRequest): ApiResult<EnrollmentResponse> =
        call {
            client.post {
                url(baseUrl + ApiPaths.APP + "/enroll")
                attributes.put(SkipAuth, true)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun instance(): ApiResult<InstanceInfo> {
        val session = sessions.session.first() ?: return ApiResult.Failure(ApiError.NotConfigured)
        return call { client.get(session.instanceUrl + ApiPaths.APP + "/instance") }
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): ApiResult<T> = try {
        val response = block()
        if (response.status.isSuccess()) {
            ApiResult.Success(response.body<T>())
        } else {
            ApiResult.Failure(response.toError())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        ApiResult.Failure(ApiError.Malformed(e.message))
    } catch (e: Exception) {
        // IOException, timeouts, TLS and cleartext-blocked all land here.
        ApiResult.Failure(ApiError.Unreachable(e.message))
    }

    private suspend fun HttpResponse.toError(): ApiError {
        val problem = try {
            body<Problem>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return when (status.value) {
            401 -> ApiError.Unauthorized(problem)
            403 -> ApiError.Forbidden(problem)
            else -> ApiError.Http(status.value, problem)
        }
    }
}
