package net.robog.clarity.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.SessionStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encodeToString
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
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionPoll
import net.robog.clarity.shared.action.ActionResult
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.ProposeAmendment
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestResponse
import net.robog.clarity.shared.feed.FeedPage
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.path.AgentPath
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.UserInput
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.governance.SignedDecision
import net.robog.clarity.shared.vault.CredentialId
import net.robog.clarity.shared.vault.CredentialMeta
import net.robog.clarity.shared.vault.CredentialRequest
import net.robog.clarity.shared.vault.CredentialRequestId
import net.robog.clarity.shared.vault.DeviceVaultKey
import net.robog.clarity.shared.vault.GrantReceipt
import net.robog.clarity.shared.vault.NewCredential
import net.robog.clarity.shared.vault.RegisterVaultKey
import net.robog.clarity.shared.vault.ReleaseCode
import net.robog.clarity.shared.vault.ReleaseGrant
import net.robog.clarity.shared.vault.VaultEnvelope
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.ModelSettingsView
import net.robog.clarity.shared.session.RoleModelOverride
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

    /** `GET /sessions?active=true`: sessions that are active, busy or snoozed. */
    suspend fun activeSessions(): ApiResult<List<Session>>
    suspend fun heldAction(id: HeldActionId): ApiResult<HeldAction>
    /** Held actions in any of [statuses] (all when empty). */
    suspend fun heldByStatus(statuses: Set<HeldAction.Status>): ApiResult<List<HeldAction>>

    suspend fun amendments(status: Amendment.Status?): ApiResult<List<Amendment>>
    suspend fun proposeAmendment(request: ProposeAmendment): ApiResult<Amendment>
    /** Applies the amendment; the reply is the new constitution version. */
    suspend fun saveAmendment(id: AmendmentId): ApiResult<Constitution>
    suspend fun discardAmendment(id: AmendmentId): ApiResult<Amendment>
}

/**
 * The connector side of the API, used by the phone connector. The device JWT authenticates as
 * connector `device-<deviceId>` on these `/ingest/v1` routes.
 */
interface IngestApi {
    suspend fun putManifest(manifest: ConnectorManifest): ApiResult<Unit>
    suspend fun pushBatch(batch: IngestBatch): ApiResult<IngestResponse>
    /** Long-polls for actions addressed to this phone ([waitSeconds] 0..30). */
    suspend fun pollActions(waitSeconds: Int): ApiResult<ActionPoll>
    suspend fun reportResult(id: ActionId, result: ActionResult): ApiResult<Unit>
    /** Grants on the app API (`/api/v1/connectors/{id}/grants`), for the phone's own connector. */
    suspend fun putGrants(id: ConnectorId, update: GrantsUpdate): ApiResult<Unit>
}

/** Each role's model settings (`/api/v1/settings/models`). */
interface ModelsApi {
    suspend fun modelSettings(): ApiResult<ModelSettingsView>
    suspend fun putRoleModel(role: String, override: RoleModelOverride): ApiResult<ModelSettingsView>
    suspend fun resetRoleModel(role: String): ApiResult<ModelSettingsView>
}

/** The Agent's Path of a task or a helper run (`/api/v1/tasks/{id}/path`, `/api/v1/runs/{id}/path`). */
interface PathApi {
    suspend fun taskPath(id: TaskId, from: Int, limit: Int): ApiResult<AgentPath>
    suspend fun runPath(id: String, from: Int, limit: Int): ApiResult<AgentPath>

    /** The instance's address, for the screenshot URLs (fetched by the image loader over the same authenticated client). */
    suspend fun baseUrl(): String?
}

/** The credential vault's routes and phone-signed decisions. */
interface VaultApi {
    /** Registers this phone's vault key and approval key (replacing earlier ones). */
    suspend fun registerVaultKeys(request: RegisterVaultKey): ApiResult<DeviceVaultKey>
    /** Every enrolled device's vault public key: credentials are sealed to each of them. */
    suspend fun vaultDevices(): ApiResult<List<DeviceVaultKey>>
    suspend fun credentials(): ApiResult<List<CredentialMeta>>
    suspend fun deleteCredential(id: CredentialId): ApiResult<Unit>
    suspend fun credentialRequest(id: CredentialRequestId): ApiResult<CredentialRequest>
    suspend fun saveCredential(new: NewCredential): ApiResult<CredentialMeta>
    /** This phone's own sealed copy of a credential. */
    suspend fun envelope(id: CredentialId): ApiResult<VaultEnvelope>
    suspend fun releaseGrant(heldId: HeldActionId, release: ReleaseGrant): ApiResult<GrantReceipt>
    suspend fun releaseCode(heldId: HeldActionId, release: ReleaseCode): ApiResult<GrantReceipt>
    suspend fun signedDecision(heldId: HeldActionId, decision: SignedDecision): ApiResult<Unit>
    suspend fun heldAction(id: HeldActionId): ApiResult<HeldAction>
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
    /**
     * Where requests run. Each one signs a fresh device JWT with the Keystore key (slow on StrongBox)
     * and decodes JSON; Ktor runs both in the caller's context, which is the main thread from a ViewModel.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ClarityApi, IngestApi, VaultApi, ModelsApi, PathApi {

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

    override suspend fun settings(): ApiResult<Settings> = get(ApiPaths.SETTINGS)

    override suspend fun putSettings(settings: Settings): ApiResult<Unit> = putUnit(ApiPaths.SETTINGS, settings)

    override suspend fun connectors(): ApiResult<List<ConnectorSummary>> = get(ApiPaths.CONNECTORS)

    override suspend fun modelSettings(): ApiResult<ModelSettingsView> = get(ApiPaths.SETTINGS_MODELS)

    override suspend fun putRoleModel(role: String, override: RoleModelOverride): ApiResult<ModelSettingsView> =
        authed { base ->
            client.put {
                url(base + "${ApiPaths.SETTINGS_MODELS}/$role")
                contentType(ContentType.Application.Json)
                setBody(override)
            }
        }

    override suspend fun resetRoleModel(role: String): ApiResult<ModelSettingsView> =
        authed { base -> client.delete { url(base + "${ApiPaths.SETTINGS_MODELS}/$role") } }

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

    override suspend fun activeSessions(): ApiResult<List<Session>> =
        get(ApiPaths.SESSIONS) { parameter("active", true) }

    override suspend fun heldAction(id: HeldActionId): ApiResult<HeldAction> = get("${ApiPaths.HELD}/$id")

    override suspend fun heldByStatus(statuses: Set<HeldAction.Status>): ApiResult<List<HeldAction>> =
        get(ApiPaths.HELD) {
            if (statuses.isNotEmpty()) parameter("status", statuses.joinToString(",") { it.wire() })
        }

    override suspend fun amendments(status: Amendment.Status?): ApiResult<List<Amendment>> =
        get("${ApiPaths.CONSTITUTION}/amendments") { if (status != null) parameter("status", status.wire()) }

    override suspend fun proposeAmendment(request: ProposeAmendment): ApiResult<Amendment> =
        authed { base ->
            client.post {
                url(base + "${ApiPaths.CONSTITUTION}/amendments")
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun saveAmendment(id: AmendmentId): ApiResult<Constitution> =
        authed { base -> client.post { url(base + "${ApiPaths.CONSTITUTION}/amendments/$id/save") } }

    override suspend fun discardAmendment(id: AmendmentId): ApiResult<Amendment> =
        authed { base -> client.post { url(base + "${ApiPaths.CONSTITUTION}/amendments/$id/discard") } }

    // --- the Agent's Path ---

    override suspend fun taskPath(id: TaskId, from: Int, limit: Int): ApiResult<AgentPath> =
        get("${ApiPaths.TASKS}/${id.value}/path") { parameter("from", from); parameter("limit", limit) }

    override suspend fun runPath(id: String, from: Int, limit: Int): ApiResult<AgentPath> =
        get("${ApiPaths.RUNS}/$id/path") { parameter("from", from); parameter("limit", limit) }

    override suspend fun baseUrl(): String? = sessions.session.first()?.instanceUrl

    // --- vault ---

    override suspend fun registerVaultKeys(request: RegisterVaultKey): ApiResult<DeviceVaultKey> =
        authed { base ->
            client.post {
                url(base + ApiPaths.VAULT_DEVICES)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun vaultDevices(): ApiResult<List<DeviceVaultKey>> = get(ApiPaths.VAULT_DEVICES)

    override suspend fun credentials(): ApiResult<List<CredentialMeta>> = get(ApiPaths.VAULT_CREDENTIALS)

    override suspend fun deleteCredential(id: CredentialId): ApiResult<Unit> =
        authedUnit { base -> client.delete { url(base + "${ApiPaths.VAULT_CREDENTIALS}/$id") } }

    override suspend fun credentialRequest(id: CredentialRequestId): ApiResult<CredentialRequest> =
        get("${ApiPaths.VAULT_REQUESTS}/$id")

    override suspend fun saveCredential(new: NewCredential): ApiResult<CredentialMeta> =
        authed { base ->
            client.post {
                url(base + ApiPaths.VAULT_CREDENTIALS)
                contentType(ContentType.Application.Json)
                setBody(new)
            }
        }

    override suspend fun envelope(id: CredentialId): ApiResult<VaultEnvelope> =
        get("${ApiPaths.VAULT_CREDENTIALS}/$id/envelope")

    override suspend fun releaseGrant(heldId: HeldActionId, release: ReleaseGrant): ApiResult<GrantReceipt> =
        authed { base ->
            client.post {
                url(base + "${ApiPaths.VAULT_GRANTS}/$heldId")
                contentType(ContentType.Application.Json)
                setBody(release)
            }
        }

    override suspend fun releaseCode(heldId: HeldActionId, release: ReleaseCode): ApiResult<GrantReceipt> =
        authed { base ->
            client.post {
                url(base + "${ApiPaths.VAULT_CODES}/$heldId")
                contentType(ContentType.Application.Json)
                setBody(release)
            }
        }

    override suspend fun signedDecision(heldId: HeldActionId, decision: SignedDecision): ApiResult<Unit> =
        authedUnit { base ->
            client.post {
                url(base + "${ApiPaths.HELD}/$heldId/signed-decision")
                contentType(ContentType.Application.Json)
                setBody(decision)
            }
        }

    // --- ingest (phone connector) ---

    override suspend fun putManifest(manifest: ConnectorManifest): ApiResult<Unit> =
        putUnit("${ApiPaths.INGEST}/manifest", manifest)

    override suspend fun pushBatch(batch: IngestBatch): ApiResult<IngestResponse> =
        authed { base ->
            client.post {
                url(base + "${ApiPaths.INGEST}/batches")
                contentType(ContentType.Application.Json)
                setBody(batch)
            }
        }

    override suspend fun pollActions(waitSeconds: Int): ApiResult<ActionPoll> =
        get(ApiPaths.ACTIONS) {
            parameter("wait", waitSeconds.coerceIn(0, 30))
            timeout { requestTimeoutMillis = (waitSeconds.coerceIn(0, 30) + 15) * 1000L }
        }

    override suspend fun reportResult(id: ActionId, result: ActionResult): ApiResult<Unit> =
        authedUnit { base ->
            client.post {
                url(base + "${ApiPaths.ACTIONS}/$id/result")
                contentType(ContentType.Application.Json)
                setBody(result)
            }
        }

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

    private suspend inline fun <reified T> call(crossinline block: suspend () -> HttpResponse): ApiResult<T> = withContext(io) {
        try {
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
    }

    /** Like [call] but ignores the response body (endpoints whose reply we don't need). */
    private suspend inline fun callUnit(crossinline block: suspend () -> HttpResponse): ApiResult<Unit> = withContext(io) {
        try {
            val response = block()
            if (response.status.isSuccess()) ApiResult.Success(Unit) else ApiResult.Failure(response.toError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Failure(ApiError.Unreachable(e.message))
        }
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

/** The `@SerialName` of an enum constant, as it appears in query strings. */
internal inline fun <reified E : Enum<E>> E.wire(): String = ClarityJson.encodeToString(this).trim('"')
