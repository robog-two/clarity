package net.robog.clarity.app.data

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.ConfigSealer
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.SealingException
import net.robog.clarity.app.connector.sealerFor
import net.robog.clarity.app.data.feed.FeedStore
import net.robog.clarity.app.data.feed.FeedSync
import net.robog.clarity.app.data.feed.SyncSignal
import net.robog.clarity.app.data.feed.SyncState
import net.robog.clarity.app.net.ClarityApi
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestResult
import net.robog.clarity.shared.connector.GrantState
import net.robog.clarity.shared.connector.GrantsUpdate
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.InputAccepted
import net.robog.clarity.shared.feed.InputSource
import net.robog.clarity.shared.feed.UserInput
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldDecision
import net.robog.clarity.shared.session.ModelKeyRequest
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl
import net.robog.clarity.shared.session.SessionId
import net.robog.clarity.shared.session.SessionKind
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.session.SetupStatus
import net.robog.clarity.shared.session.StartSession
import net.robog.clarity.shared.session.Today

/** Ids the client mints (injected so tests are deterministic). */
fun interface IdGenerator { fun newId(): String }

class UuidGenerator : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() }

// --- setup ---

/** Result of probing an instance address before enrolling. */
data class ServerProbe(val latencyMillis: Long, val tls: Boolean)

interface ServerRepository {
    /** Reachability, TLS and round-trip time of an (unenrolled) instance. */
    suspend fun probe(instanceUrl: String): ApiResult<ServerProbe>
}

class DefaultServerRepository(private val api: ClarityApi) : ServerRepository {
    override suspend fun probe(instanceUrl: String): ApiResult<ServerProbe> {
        val mark = TimeSource.Monotonic.markNow()
        return when (val r = api.health(instanceUrl)) {
            is ApiResult.Success -> ApiResult.Success(
                ServerProbe(mark.elapsedNow().inWholeMilliseconds, tls = instanceUrl.startsWith("https://")),
            )
            is ApiResult.Failure -> r
        }
    }
}

sealed interface ModelKeyOutcome {
    data object Works : ModelKeyOutcome
    /** The server (or Anthropic) refused the key. */
    data class Rejected(val detail: String?) : ModelKeyOutcome
    /** The server has no model-key route yet. */
    data object Unavailable : ModelKeyOutcome
    data class Failed(val error: ApiError) : ModelKeyOutcome
}

interface SetupRepository {
    /** Null when the server does not report setup status (yet). */
    suspend fun status(): SetupStatus?
    suspend fun submitModelKey(apiKey: String): ModelKeyOutcome
}

class DefaultSetupRepository(private val api: ClarityApi) : SetupRepository {
    override suspend fun status(): SetupStatus? = api.setupStatus().getOrNull()

    override suspend fun submitModelKey(apiKey: String): ModelKeyOutcome =
        when (val r = api.putModelKey(ModelKeyRequest(apiKey.trim()))) {
            is ApiResult.Success -> when (api.setupStatus().getOrNull()?.modelKey) {
                SetupStatus.KeyState.INVALID -> ModelKeyOutcome.Rejected(null)
                else -> ModelKeyOutcome.Works
            }
            is ApiResult.Failure -> when (val e = r.error) {
                is ApiError.NotFound -> ModelKeyOutcome.Unavailable
                is ApiError.Http -> if (e.status in 400..499) {
                    ModelKeyOutcome.Rejected(e.problem?.detail ?: e.problem?.title)
                } else {
                    ModelKeyOutcome.Failed(e)
                }
                else -> ModelKeyOutcome.Failed(e)
            }
        }
}

interface SettingsRepository {
    suspend fun load(): ApiResult<Settings>
    suspend fun save(settings: Settings): ApiResult<Unit>
}

class DefaultSettingsRepository(private val api: ClarityApi) : SettingsRepository {
    override suspend fun load() = api.settings()
    override suspend fun save(settings: Settings) = api.putSettings(settings)
}

interface ConstitutionRepository {
    suspend fun current(): ApiResult<Constitution>
}

class DefaultConstitutionRepository(private val api: ClarityApi) : ConstitutionRepository {
    override suspend fun current() = api.constitution()
}

// --- connectors ---


sealed interface SaveConnectorOutcome {
    data object Saved : SaveConnectorOutcome
    /** The connector has not announced a sealing key, so secrets cannot be sent safely. */
    data object NoSealingKey : SaveConnectorOutcome
    data class Failed(val error: ApiError) : SaveConnectorOutcome
}

interface ConnectorsRepository {
    suspend fun list(): ApiResult<List<ConnectorSummary>>
    /** Stored config of a connector, or null if none/unavailable. */
    suspend fun storedConfig(id: ConnectorId): ConnectorConfig?
    /**
     * Seals newly typed secrets, keeps stored ones unchanged, stores the whole config, then grants
     * every noun the connector produces.
     */
    suspend fun save(connector: ConnectorSummary, values: FormValues): SaveConnectorOutcome
    suspend fun test(id: ConnectorId): ApiResult<ConnectorTestResult>
}

class DefaultConnectorsRepository(
    private val api: ClarityApi,
    private val sealer: ConfigSealer,
) : ConnectorsRepository {
    override suspend fun list() = api.connectors()

    override suspend fun storedConfig(id: ConnectorId): ConnectorConfig? = api.connectorConfig(id).getOrNull()

    override suspend fun save(connector: ConnectorSummary, values: FormValues): SaveConnectorOutcome {
        val manifest = connector.manifest ?: return SaveConnectorOutcome.Failed(ApiError.Malformed("no manifest"))
        val form = ConfigForms.parse(manifest.configSchema)
        val sealingKey = manifest.sealingKey
        if (sealingKey == null && form.hasTypedSecrets(values)) {
            return SaveConnectorOutcome.NoSealingKey
        }
        val json = try {
            form.toConfigValues(values, sealerFor(sealer, connector.id, sealingKey.orEmpty()))
        } catch (_: SealingException) {
            return SaveConnectorOutcome.NoSealingKey
        }
        val version = storedConfig(connector.id)?.version ?: 0L
        val put = api.putConnectorConfig(connector.id, ConnectorConfig(version + 1, json))
        if (put is ApiResult.Failure) return SaveConnectorOutcome.Failed(put.error)
        val grants = api.putGrants(connector.id, GrantsUpdate(manifest.produces.associateWith { GrantState.GRANTED }))
        if (grants is ApiResult.Failure) return SaveConnectorOutcome.Failed(grants.error)
        return SaveConnectorOutcome.Saved
    }

    override suspend fun test(id: ConnectorId): ApiResult<ConnectorTestResult> =
        when (val r = api.testConnector(id)) {
            is ApiResult.Success -> ApiResult.Success(awaitTestResult(id, r.value, list = { api.connectors() }))
            is ApiResult.Failure -> r
        }
}

/**
 * The server answers a test with its result, or with `pending` when the connector is slower than
 * the server's wait (a big mailbox). Then the result is read from the connector list's
 * `lastTest`, matched by request id, checking every [every] for up to [tries] times.
 */
suspend fun awaitTestResult(
    id: ConnectorId,
    first: ConnectorTestResult,
    list: suspend () -> ApiResult<List<ConnectorSummary>>,
    every: kotlin.time.Duration = kotlin.time.Duration.parse("3s"),
    tries: Int = 40,
): ConnectorTestResult {
    val requestId = first.requestId
    if (!first.pending || requestId == null) return first
    repeat(tries) {
        kotlinx.coroutines.delay(every)
        val report = (list() as? ApiResult.Success)?.value?.firstOrNull { it.id == id }?.lastTest
        if (report?.requestId == requestId) {
            return if (report.ok) ConnectorTestResult(ok = true, summary = report.message, requestId = requestId)
            else ConnectorTestResult(ok = false, error = report.message, requestId = requestId)
        }
    }
    return ConnectorTestResult(ok = false, error = "still testing: check again in a minute", requestId = requestId)
}

// --- sessions ---

interface SessionsRepository {
    /** [heldActionId] ties an AMEND session to the held action it talks about. */
    suspend fun start(kind: SessionKind, heldActionId: HeldActionId? = null): ApiResult<Session>
    suspend fun get(id: SessionId): ApiResult<Session>
    suspend fun control(id: SessionId, control: SessionControl): ApiResult<Session>
    /** The session of [kind] that is active, busy or snoozed right now, if any. */
    suspend fun active(kind: SessionKind): ApiResult<Session?>
}

class DefaultSessionsRepository(private val api: ClarityApi) : SessionsRepository {
    override suspend fun start(kind: SessionKind, heldActionId: HeldActionId?) =
        api.startSession(StartSession(kind, heldActionId))
    override suspend fun get(id: SessionId) = api.session(id)
    override suspend fun control(id: SessionId, control: SessionControl) = api.controlSession(id, control)
    override suspend fun active(kind: SessionKind): ApiResult<Session?> =
        api.activeSessions().map { list ->
            list.filter { it.kind == kind && it.state != Session.State.ENDED }.maxByOrNull { it.startedAt }
        }
}

// --- today, held details, amendments ---

interface TodayRepository {
    suspend fun today(): ApiResult<Today>
}

class DefaultTodayRepository(private val api: ClarityApi) : TodayRepository {
    override suspend fun today() = api.today()
}

interface HeldRepository {
    suspend fun get(id: HeldActionId): ApiResult<HeldAction>
    /** Actions the agent took alone ("see allowed actions"). */
    suspend fun activity(): ApiResult<List<ActivityEntry>>
}

class DefaultHeldRepository(private val api: ClarityApi) : HeldRepository {
    override suspend fun get(id: HeldActionId) = api.heldAction(id)
    override suspend fun activity() = api.activity()
}

interface AmendmentsRepository {
    /** The amendment with [id] if it is still proposed. */
    suspend fun proposed(id: AmendmentId): ApiResult<Amendment?>
    /** Saves the amendment; the reply is the new constitution. */
    suspend fun save(id: AmendmentId): ApiResult<Constitution>
    suspend fun discard(id: AmendmentId): ApiResult<Amendment>
}

class DefaultAmendmentsRepository(private val api: ClarityApi) : AmendmentsRepository {
    override suspend fun proposed(id: AmendmentId): ApiResult<Amendment?> =
        api.amendments(Amendment.Status.PROPOSED).map { list -> list.firstOrNull { it.id == id } }
    override suspend fun save(id: AmendmentId) = api.saveAmendment(id)
    override suspend fun discard(id: AmendmentId) = api.discardAmendment(id)
}

// --- feed ---

interface FeedRepository {
    val items: Flow<List<FeedItem>>
    val tasks: Flow<List<AgentTask>>
    val held: Flow<List<HeldAction>>
    val syncState: Flow<SyncState>
    val agentBusy: Flow<Boolean>
    val signals: Flow<SyncSignal>

    /** Sends the person's input; retries with the same [UserInput.clientId] never duplicate. */
    suspend fun send(text: String, source: InputSource, sessionId: SessionId?, clientId: ClientInputId? = null): ApiResult<InputAccepted>
    suspend fun decideHeld(id: net.robog.clarity.shared.governance.HeldActionId, decision: HeldDecision): ApiResult<Unit>

    /** Keeps the cache current until cancelled. */
    suspend fun sync()
}

class DefaultFeedRepository(
    private val api: ClarityApi,
    private val store: FeedStore,
    private val feedSync: FeedSync,
    private val ids: IdGenerator,
) : FeedRepository {
    override val items = store.items
    override val tasks = store.tasks
    override val held = store.held
    override val syncState = feedSync.state
    override val agentBusy = feedSync.agentBusy
    override val signals = feedSync.signals

    override suspend fun send(text: String, source: InputSource, sessionId: SessionId?, clientId: ClientInputId?): ApiResult<InputAccepted> {
        val input = UserInput(clientId ?: ClientInputId(ids.newId()), text, source, sessionId)
        val result = api.sendInput(input)
        // Show the person's own words immediately; the SSE replay of the same seq is a no-op.
        if (result is ApiResult.Success) store.upsertItem(result.value.item)
        return result
    }

    override suspend fun decideHeld(id: net.robog.clarity.shared.governance.HeldActionId, decision: HeldDecision) =
        api.decideHeld(id, decision)

    override suspend fun sync() = feedSync.run()
}

/** Wall clock, injectable. */
fun interface AppClock {
    fun now(): kotlin.time.Instant

    /** The phone's zone: what times are shown and set in. */
    fun zone(): kotlinx.datetime.TimeZone = kotlinx.datetime.TimeZone.currentSystemDefault()
}

class SystemAppClock : AppClock { override fun now() = Clock.System.now() }

