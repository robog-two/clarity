package net.robog.clarity.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SessionStore
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.api.ApiPaths
import net.robog.clarity.shared.api.EnrollmentRequest
import net.robog.clarity.shared.api.EnrollmentResponse
import net.robog.clarity.shared.api.InstanceInfo
import net.robog.clarity.shared.api.Problem
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestResult
import net.robog.clarity.shared.connector.GrantsUpdate
import net.robog.clarity.shared.feed.FeedPage
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.UserInput
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.session.ModelKeyRequest
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.SetupStatus
import net.robog.clarity.shared.session.StartSession
import net.robog.clarity.shared.session.Today

/** The slice of the API the feed synchronizer needs. */
interface FeedBackend {
    suspend fun feed(after: Long): ApiResult<FeedPage>
    suspend fun held(): ApiResult<List<HeldAction>>
}

/**
 * Typed access to the instance's device API. Every method returns [ApiResult]; nothing throws.
 * A route the server does not have yet comes back as [ApiError.NotFound].
 */
interface ClarityApi : FeedBackend {
    /** Unauthenticated: registers the device key. [baseUrl] is the (validated) instance URL. */
    suspend fun enroll(baseUrl: String, request: EnrollmentRequest): ApiResult<EnrollmentResponse>

    /** Unauthenticated liveness probe of [baseUrl] (`GET /healthz`). */
    suspend fun health(baseUrl: String): ApiResult<Unit>

    suspend fun instance(): ApiResult<InstanceInfo>

    suspend fun setupStatus(): ApiResult<SetupStatus>
    suspend fun putModelKey(request: ModelKeyRequest): ApiResult<Unit>

    suspend fun settings(): ApiResult<Settings>
    suspend fun putSettings(settings: Settings): ApiResult<Unit>

    suspend fun connectors(): ApiResult<List<ConnectorSummary>>
    suspend fun putGrants(id: ConnectorId, update: GrantsUpdate): ApiResult<Unit>
    suspend fun connectorConfig(id: ConnectorId): ApiResult<ConnectorConfig>
    suspend fun putConnectorConfig(id: ConnectorId, config: ConnectorConfig): ApiResult<Unit>
    suspend fun testConnector(id: ConnectorId): ApiResult<ConnectorTestResult>

    override suspend fun feed(after: Long): ApiResult<FeedPage>
    suspend fun sendInput(input: UserInput): ApiResult<InputAccepted>

    suspend fun startSession(request: StartSession): ApiResult<Session>
    suspend fun session(id: SessionId): ApiResult<Session>
    suspend fun controlSession(id: SessionId, control: SessionControl): ApiResult<Session>

    suspend fun today(): ApiResult<Today>
    suspend fun constitution(): ApiResult<Constitution>
    override suspend fun held(): ApiResult<List<HeldAction>>
    suspend fun decideHeld(id: HeldActionId, decision: HeldDecision): ApiResult<Unit>
    suspend fun activity(): ApiResult<List<ActivityEntry>>
}

internal val SkipAuth = AttributeKey<Boolean>("clarity.skipAuth")

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
    install(SSE) { maxReconnectionAttempts = 0 }
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

    override suspend fun health(baseUrl: String): ApiResult<Unit> = callUnit {
        client.get {
            url(baseUrl + ApiPaths.HEALTH)
            attributes.put(SkipAuth, true)
        }
    }

    override suspend fun instance(): ApiResult<InstanceInfo> = get(ApiPaths.APP + "/instance")

    override suspend fun setupStatus(): ApiResult<SetupStatus> = get(ApiPaths.SETUP_STATUS)

    override suspend fun putModelKey(request: ModelKeyRequest): ApiResult<Unit> =
        putUnit(ApiPaths.SETUP_MODEL_KEY, request)

    override suspend fun settings(): ApiResult<Settings> = get(ApiPaths.SETTINGS)

    override suspend fun putSettings(settings: Settings): ApiResult<Unit> = putUnit(ApiPaths.SETTINGS, settings)

    override suspend fun connectors(): ApiResult<List<ConnectorSummary>> = get(ApiPaths.CONNECTORS)

    override suspend fun putGrants(id: ConnectorId, update: GrantsUpdate): ApiResult<Unit> =
        putUnit("${ApiPaths.CONNECTORS}/$id/grants", update)

    override suspend fun connectorConfig(id: ConnectorId): ApiResult<ConnectorConfig> =
        get("${ApiPaths.CONNECTORS}/$id/config")

    override suspend fun putConnectorConfig(id: ConnectorId, config: ConnectorConfig): ApiResult<Unit> =
        putUnit("${ApiPaths.CONNECTORS}/$id/config", config)

    override suspend fun testConnector(id: ConnectorId): ApiResult<ConnectorTestResult> =
        authed { base -> client.post { url(base + "${ApiPaths.CONNECTORS}/$id/test") } }

    override suspend fun feed(after: Long): ApiResult<FeedPage> =
        get(ApiPaths.FEED) { parameter("after", after) }

    override suspend fun sendInput(input: UserInput): ApiResult<InputAccepted> =
        authed { base ->
            client.post {
                url(base + ApiPaths.INPUT)
                contentType(ContentType.Application.Json)
                setBody(input)
            }
        }

    override suspend fun startSession(request: StartSession): ApiResult<Session> =
        authed { base ->
            client.post {
                url(base + ApiPaths.SESSIONS)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun session(id: SessionId): ApiResult<Session> = get("${ApiPaths.SESSIONS}/$id")

    override suspend fun controlSession(id: SessionId, control: SessionControl): ApiResult<Session> =
        authed { base ->
            client.post {
                url(base + "${ApiPaths.SESSIONS}/$id/control")
                contentType(ContentType.Application.Json)
                setBody<SessionControl>(control)
            }
        }

    override suspend fun today(): ApiResult<Today> = get(ApiPaths.TODAY)
    override suspend fun constitution(): ApiResult<Constitution> = get(ApiPaths.CONSTITUTION)
    override suspend fun held(): ApiResult<List<HeldAction>> = get(ApiPaths.HELD)

    override suspend fun decideHeld(id: HeldActionId, decision: HeldDecision): ApiResult<Unit> =
        authedUnit { base ->
            client.post {
                url(base + "${ApiPaths.HELD}/$id/decision")
                contentType(ContentType.Application.Json)
                setBody<HeldDecision>(decision)
            }
        }

    override suspend fun activity(): ApiResult<List<ActivityEntry>> = get(ApiPaths.ACTIVITY)

    // --- plumbing ---

    private suspend inline fun <reified T> get(
        path: String,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> = authed { base -> client.get { url(base + path); configure() } }

    private suspend inline fun <reified B : Any> putUnit(path: String, body: B): ApiResult<Unit> =
        authedUnit { base ->
            client.put {
                url(base + path)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> authed(crossinline block: suspend (String) -> HttpResponse): ApiResult<T> {
        val session = sessions.session.first() ?: return ApiResult.Failure(ApiError.NotConfigured)
        return call { block(session.instanceUrl) }
    }

    private suspend inline fun authedUnit(crossinline block: suspend (String) -> HttpResponse): ApiResult<Unit> {
        val session = sessions.session.first() ?: return ApiResult.Failure(ApiError.NotConfigured)
        return callUnit { block(session.instanceUrl) }
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
    } catch (e: ContentConvertException) {
        ApiResult.Failure(ApiError.Malformed(e.message))
    } catch (e: Exception) {
        // IOException, timeouts, TLS and cleartext-blocked all land here.
        ApiResult.Failure(ApiError.Unreachable(e.message))
    }

    /** Like [call] but ignores the response body (endpoints whose reply we don't need). */
    private suspend inline fun callUnit(block: () -> HttpResponse): ApiResult<Unit> = try {
        val response = block()
        if (response.status.isSuccess()) ApiResult.Success(Unit) else ApiResult.Failure(response.toError())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Failure(ApiError.Unreachable(e.message))
    }
}

internal suspend fun HttpResponse.toError(): ApiError {
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
        404 -> ApiError.NotFound(problem)
        else -> ApiError.Http(status.value, problem)
    }
}
