package net.robog.clarity.connector.sdk

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.ingest.IngestBatch
import net.robog.clarity.shared.ingest.IngestRecord
import net.robog.clarity.shared.ingest.RecordResult

private val log = KotlinLogging.logger {}

/**
 * What a connector's sync block can use. One instance lives for one sync run.
 */
interface SyncScope {
    /** Latest configuration the user saved in the app (empty values until they configure it). */
    val config: ConnectorConfig

    /** Durable state on the connector's own volume: cursors, caches. */
    val state: StateStore

    val log: KLogger

    /**
     * Pushes [records], split into batches of at most [IngestBatch.MAX_RECORDS]; transient
     * failures are retried with jittered exponential backoff (safe: ingest is idempotent).
     * Stops at the first failing batch.
     *
     * @throws ClientException on a non-retryable rejection or exhausted retries; use
     *   [pushCatching] to handle that as a value.
     */
    suspend fun push(records: List<IngestRecord>): List<RecordResult> =
        pushCatching(records).fold({ throw ClientException(it) }, { it })

    /** Like [push] but returns the typed [ClientError] instead of throwing. */
    suspend fun pushCatching(records: List<IngestRecord>): Either<ClientError, List<RecordResult>>
}

/**
 * Entry point of every connector. Registers [manifest] (retrying until the backend is
 * reachable), then loops: poll config, run [sync], wait [ConnectorSettings.syncInterval].
 *
 * A failing sync run is logged and retried on the next tick. SIGTERM/SIGINT cancel the loop
 * gracefully: an in-flight HTTP request completes, then this function returns.
 *
 * If [actions] has handlers, an action loop runs beside the sync loop: it long-polls the backend
 * for actions addressed to this connector (see `ConnectorManifest.handles`), executes them and
 * reports results. Executed idempotency keys are remembered in the connector's state.
 *
 * Pass the same [runtime] that the handlers and manifest were built with: it receives every new
 * config (so action handlers see credentials saved after start) and answers [test], the "test this
 * connection" requests from the app.
 *
 * ```kotlin
 * suspend fun main() = runConnector(manifest) { config -> push(records) }
 * ```
 */
suspend fun runConnector(
    manifest: ConnectorManifest,
    settings: ConnectorSettings = ConnectorSettings.fromEnv(),
    actions: ActionHandlers = ActionHandlers.None,
    runtime: ConnectorRuntime? = null,
    test: (suspend (config: ConnectorConfig) -> ConnectorTestResult)? = null,
    sync: suspend SyncScope.(config: ConnectorConfig) -> Unit,
) {
    log.info { "starting connector ${manifest.kind} ${manifest.version} against ${settings.baseUrl}" }
    val http = clarityHttpClient(settings)
    try {
        coroutineScope {
            val client = ClarityClient(http)
            val state = FileStateStore(settings.stateDir)
            val loop = launch {
                runLoop(client, state, manifest, settings.syncInterval, runtime, sync)
            }
            val actionLoop = if (actions.isEmpty && test == null) null else {
                if (actions.kinds != manifest.handles) {
                    log.warn { "manifest handles ${manifest.handles} but handlers cover ${actions.kinds}; the backend routes by the manifest" }
                }
                // a child of this scope: it ends with the sync loop when the scope is cancelled
                launch { runActionLoop(client, state, actions, runtime = runtime, test = test) }
            }
            // The JVM runs shutdown hooks on SIGTERM and then halts; block the hook until the loop has wound down.
            val hook = Thread({
                log.info { "shutdown requested" }
                loop.cancel()
                actionLoop?.cancel()
                runBlocking { loop.join(); actionLoop?.join() }
            }, "connector-shutdown")
            Runtime.getRuntime().addShutdownHook(hook)
            try {
                loop.join()
                actionLoop?.cancelAndJoin()
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(hook) } catch (_: IllegalStateException) { /* already shutting down */ }
            }
        }
    } finally {
        http.close()
    }
}

/** The connector lifecycle without process concerns (hooks, env); exposed for tests. */
internal suspend fun runLoop(
    client: ClarityClient,
    state: StateStore,
    manifest: ConnectorManifest,
    interval: Duration,
    runtime: ConnectorRuntime? = null,
    sync: suspend SyncScope.(ConnectorConfig) -> Unit,
) {
    registerManifest(client, manifest)

    var etag: String? = null
    var config: ConnectorConfig? = null
    while (true) {
        client.pollConfig(etag).fold(
            ifLeft = { log.warn { "config poll failed: ${it.message}" } },
            ifRight = { poll ->
                when (poll) {
                    is ConfigPoll.Changed -> {
                        config = poll.config; etag = poll.etag
                        runtime?.accept(poll.config, poll.etag)
                        log.info { "config version ${poll.config.version}" }
                    }
                    ConfigPoll.NotModified -> Unit
                }
            },
        )
        when (val current = config) {
            null -> log.warn { "no config available yet; skipping sync" }
            else -> runSync(client, state, current, sync)
        }
        delay(interval)
    }
}

private suspend fun registerManifest(client: ClarityClient, manifest: ConnectorManifest) {
    var wait = 2.seconds
    while (true) {
        val result = client.putManifest(manifest)
        if (result.isRight()) {
            log.info { "manifest registered" }
            return
        }
        log.warn { "manifest registration failed: ${result.leftOrNull()?.message}; retrying in $wait" }
        delay(wait)
        wait = minOf(wait * 2, 60.seconds)
    }
}

private suspend fun runSync(
    client: ClarityClient,
    state: StateStore,
    config: ConnectorConfig,
    sync: suspend SyncScope.(ConnectorConfig) -> Unit,
) {
    val scope = object : SyncScope {
        override val config = config
        override val state = state
        override val log = KotlinLogging.logger("connector.sync")
        override suspend fun pushCatching(records: List<IngestRecord>): Either<ClientError, List<RecordResult>> =
            pushChunked(client, records)
    }
    try {
        scope.sync(config)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "sync run failed; will retry next interval" }
    }
}

/** Sequentially posts [records] in chunks of at most [IngestBatch.MAX_RECORDS]. */
internal suspend fun pushChunked(client: ClarityClient, records: List<IngestRecord>): Either<ClientError, List<RecordResult>> {
    val results = ArrayList<RecordResult>(records.size)
    for (chunk in records.chunked(IngestBatch.MAX_RECORDS)) {
        when (val r = client.postBatch(IngestBatch(chunk))) {
            is Either.Left -> return r.value.left()
            is Either.Right -> results += r.value.results
        }
    }
    return results.right()
}
