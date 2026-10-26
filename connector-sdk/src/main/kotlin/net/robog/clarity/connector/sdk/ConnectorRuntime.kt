package net.robog.clarity.connector.sdk

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorTestReport
import net.robog.clarity.shared.connector.ConnectorTestRequest

private val log = KotlinLogging.logger {}

/** What a connector reports for a "test this connection" request. */
data class ConnectorTestResult(
    val ok: Boolean,
    val message: String,
    val counts: Map<String, Int> = emptyMap(),
)

/**
 * Per-process state a connector needs beyond one sync run: the sealing key pair, the newest
 * config, and the connector's id. Create it before building the manifest and action handlers
 * (so they can use [secret] and [sealingKey]) and pass the same instance to [runConnector].
 */
class ConnectorRuntime(
    val connectorId: String,
    val keys: SealingKeys,
) {
    constructor(settings: ConnectorSettings) : this(settings.connectorId, SealingKeys.loadOrCreate(settings.stateDir))

    private val latest = MutableStateFlow<ConnectorConfig?>(null)
    private var etag: String? = null

    /** For `ConnectorManifest.sealingKey`. */
    val sealingKey: String get() = keys.publicKey

    /** The newest config seen, or null before the first poll succeeded. */
    val config: ConnectorConfig? get() = latest.value

    /** Suspends until a config has been fetched (the connector may start before the user configured it). */
    suspend fun awaitConfig(): ConnectorConfig = latest.filterNotNull().first()

    /** Plaintext of a sealed secret field of [config], or null when unset. */
    fun secret(config: ConnectorConfig, name: String): String? = config.secret(name, keys, connectorId)

    internal fun accept(config: ConnectorConfig, newEtag: String?) {
        etag = newEtag
        latest.value = config
    }

    /** Conditional config poll; keeps the old config when the backend is unreachable. */
    internal suspend fun refresh(client: ClarityClient) {
        client.pollConfig(etag).fold(
            ifLeft = { log.warn { "config refresh failed: ${it.message}" } },
            ifRight = { poll -> if (poll is ConfigPoll.Changed) accept(poll.config, poll.etag) },
        )
    }

    /** Answers a pending test request: refreshes the config, runs [test], reports. Never throws (except cancellation). */
    internal suspend fun answerTest(
        client: ClarityClient,
        request: ConnectorTestRequest,
        test: suspend (ConnectorConfig) -> ConnectorTestResult,
    ) {
        refresh(client)
        val result = try {
            val config = latest.value
            if (config == null) ConnectorTestResult(false, "No configuration saved yet.")
            else test(config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectorTestResult(false, e.message ?: e::class.simpleName ?: "test failed")
        }
        client.postTestReport(ConnectorTestReport(request.requestId, result.ok, result.message, result.counts, Clock.System.now()))
            .onLeft { log.warn { "could not report test ${request.requestId}: ${it.message}" } }
    }
}
