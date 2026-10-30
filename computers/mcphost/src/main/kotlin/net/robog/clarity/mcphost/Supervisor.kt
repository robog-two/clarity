package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.mcp.McpServerState

private val log = KotlinLogging.logger {}

/** One entry of `GET /servers` (see `docs/workstreams/mcp.md`, contract 2). */
@Serializable
data class HostServer(
    val id: String,
    val label: String,
    val description: String = "",
    val state: McpServerState,
    val error: String? = null,
    val source: String = "",
    val allowHosts: List<String> = emptyList(),
    /** The cached listing: raw MCP tool objects. */
    val tools: JsonArray = JsonArray(emptyList()),
    val listedAt: String? = null,
)

/** What the HTTP layer answers to a `POST /s/{id}/mcp`. */
sealed interface McpOutcome {
    /** 200 with a JSON-RPC response (or a batch of them). */
    data class Reply(val body: JsonElement) : McpOutcome

    /** 202: only notifications or responses were sent. */
    data object Accepted : McpOutcome

    /** The server cannot take requests now; [status] is the HTTP status, [message] says why. */
    data class Refused(val status: Int, val message: String) : McpOutcome
}

/** Thrown while starting a server that cannot run now; [message] is for the person. */
class Unavailable(val status: Int, message: String) : Exception(message)

/**
 * Runs the configured servers: installs them (once per install key), lists their tools right after,
 * starts a stdio server on its first request, stops it after [LifecyclePolicy.idleTimeout] without
 * one or when a fourth must start, and backs off after a crash. Remote servers are not processes;
 * the host forwards to them (see [RemoteServer]).
 *
 * Locking: [lock] guards the entries and their fields and is held only briefly. Starting a server
 * holds that server's own `startLock`; stopping never waits for another server's `startLock`.
 */
class Supervisor(
    private val state: HostState,
    private val homes: Homes,
    private val runner: ProcessRunner,
    private val installer: Installer,
    private val egress: EgressAccess,
    private val scope: CoroutineScope,
    private val policy: LifecyclePolicy = LifecyclePolicy(),
    private val clock: Clock = Clock.System,
    private val callTimeout: Duration = 5.minutes,
    private val startTimeout: Duration = 90.seconds,
    /** PATH for children; tests use the machine's own. */
    private val childPath: String = SYSTEM_PATH,
) {
    private class Running(val server: StdioServer, val home: ServerHome, val runKey: String)

    private class Entry(val id: String) {
        var item: ItemResult? = null
        var spec: ServerSpec? = null
        var installing: Job? = null
        var installingKey: String? = null
        var listing: Job? = null
        var running: Running? = null
        var remote: RemoteServer? = null
        var remoteKey: String? = null
        var lastUsed: Instant = Instant.DISTANT_PAST
        var inFlight = 0
        var failures = 0
        var failedKey: String? = null
        var retryAt: Instant? = null
        var error: String? = null
        val startLock = Mutex()
    }

    private val lock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private var configured = false

    /** Removes proxy logins left from before a restart. */
    fun init() {
        egress.init()
        egress.retainOnly(emptySet())
    }

    // ---- config ------------------------------------------------------------------------------

    /** Brings the running set in line with the config's items. Cheap when nothing changed. */
    suspend fun reconcile(items: List<ItemResult>) {
        val removed = mutableListOf<Entry>()
        lock.withLock {
            configured = true
            val ids = items.map { it.id }.toSet()
            entries.values.filter { it.id !in ids }.forEach { removed += it; entries.remove(it.id) }
            val ordered = LinkedHashMap<String, Entry>()
            for (item in items) {
                val entry = entries[item.id] ?: Entry(item.id)
                ordered[item.id] = entry
                entry.item = item
                apply(entry, item.spec.getOrNull())
            }
            entries.clear()
            entries.putAll(ordered)
            state.update { s ->
                s.copy(known = items.map { item ->
                    val spec = item.spec.getOrNull()
                    Known(item.id, item.label, item.description, spec?.source.orEmpty(), spec?.allowHosts.orEmpty(), spec?.enabled ?: true)
                })
            }
        }
        for (entry in removed) forget(entry)
    }

    /** Called under [lock]: what a new or changed item means for its entry. */
    private fun apply(entry: Entry, spec: ServerSpec?) {
        val old = entry.spec
        entry.spec = spec
        if (spec == null || !spec.enabled) {
            entry.installing?.cancel(); entry.installing = null; entry.installingKey = null
            detachRunning(entry)?.let { stopLater(it, entry.id) }
            closeRemote(entry)
            return
        }
        if (old != null && old.runKey != spec.runKey) {
            // a new version, token or setting: start fresh, and forget the old failures
            detachRunning(entry)?.let { stopLater(it, entry.id) }
            entry.failures = 0; entry.retryAt = null; entry.error = null; entry.failedKey = null
        }
        if (spec.kind == ServerKind.REMOTE) {
            if (entry.remoteKey != spec.runKey) {
                closeRemote(entry)
                entry.remote = RemoteServer(spec, egress.grant(runUser(spec.id), spec.allowHosts))
                entry.remoteKey = spec.runKey
            }
            if (state.listing(spec.id)?.runKey != spec.runKey && canRetry(entry, spec)) listLater(entry, spec)
            return
        }
        val installed = state.current.installed[spec.id]
        when {
            installed?.installKey == spec.installKey -> {
                if (state.listing(spec.id)?.runKey != spec.runKey && canRetry(entry, spec)) listLater(entry, spec)
            }
            entry.installingKey == spec.installKey -> Unit // already on it
            canRetry(entry, spec) -> installLater(entry, spec)
        }
    }

    /** A failed install or listing of the same settings is retried only after its backoff. */
    private fun canRetry(entry: Entry, spec: ServerSpec): Boolean {
        if (entry.listing?.isActive == true) return false
        val until = entry.retryAt ?: return true
        return entry.failedKey != spec.runKey || clock.now() >= until
    }

    private fun installLater(entry: Entry, spec: ServerSpec) {
        entry.installing?.cancel()
        entry.installingKey = spec.installKey
        detachRunning(entry)?.let { stopLater(it, entry.id) }
        entry.installing = scope.launch {
            try {
                log.info { "installing ${spec.label}: ${spec.source}" }
                val home = homes.prepare(spec.id)
                val installed = installer.install(spec, home)
                state.update { it.copy(installed = it.installed + (spec.id to installed)) }
                // the first listing is part of the install: the server shows as installing until its tools are known
                listNow(entry, spec)
                lock.withLock { if (entry.installingKey == spec.installKey) { entry.installingKey = null; entry.installing = null } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = (e as? InstallFailed)?.message ?: "install failed: ${e.message ?: e.javaClass.simpleName}"
                log.warn { "${spec.label}: $message" }
                lock.withLock {
                    if (entry.installingKey == spec.installKey) { entry.installingKey = null; entry.installing = null }
                    fail(entry, spec, Redactor(spec.secretValues).clean(message, 300))
                }
            }
        }
    }

    private fun listLater(entry: Entry, spec: ServerSpec) {
        entry.listing = scope.launch { listNow(entry, spec) }
    }

    /** Takes the cached listing: starts the server once (unless it is running), lists, stops it again. */
    private suspend fun listNow(entry: Entry, spec: ServerSpec) {
        try {
            if (spec.kind == ServerKind.REMOTE) {
                val remote = lock.withLock { entry.remote } ?: return
                val tools = remote.listTools()
                state.saveListing(spec.id, Listing(spec.runKey, clock.now().toString(), tools))
                lock.withLock { succeeded(entry) }
                log.info { "${spec.label}: listed ${tools.size} tools" }
                return
            }
            val wasRunning = lock.withLock { entry.running?.runKey == spec.runKey }
            ensureRunning(entry, spec) // lists on start
            if (!wasRunning) {
                lock.withLock { if (entry.inFlight == 0) detachRunning(entry) else null }?.let { stop(it, entry.id) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Unavailable) {
            // ensureRunning recorded the failure
        } catch (e: Exception) {
            lock.withLock { fail(entry, spec, Redactor(spec.secretValues).clean("could not list its tools: ${e.message ?: e.javaClass.simpleName}", 300)) }
        }
    }

    /** Called under [lock]. */
    private fun fail(entry: Entry, spec: ServerSpec, message: String) {
        entry.failures++
        entry.failedKey = spec.runKey
        entry.retryAt = clock.now() + backoff(entry.failures, policy)
        entry.error = message
    }

    /** Called under [lock]. */
    private fun succeeded(entry: Entry) {
        entry.failures = 0; entry.retryAt = null; entry.error = null; entry.failedKey = null
    }

    private suspend fun forget(entry: Entry) {
        entry.installing?.cancel()
        entry.listing?.cancel()
        lock.withLock { detachRunning(entry) }?.let { stop(it, entry.id) }
        closeRemote(entry)
        state.dropListing(entry.id)
        state.update { it.copy(installed = it.installed - entry.id) }
        egress.revoke(installUser(entry.id))
        scope.launch { runCatching { homes.remove(entry.id) }.onFailure { log.warn { "removing the home of ${entry.id} failed: ${it.message}" } } }
        log.info { "removed server ${entry.item?.label ?: entry.id}" }
    }

    private fun closeRemote(entry: Entry) {
        entry.remote?.close()
        entry.remote = null
        entry.remoteKey = null
        egress.revoke(runUser(entry.id))
    }

    // ---- running -----------------------------------------------------------------------------

    /** Called under [lock]: takes the running process off the entry, so its exit is not a crash. */
    private fun detachRunning(entry: Entry): Running? = entry.running.also { entry.running = null }

    private fun stopLater(running: Running, id: String) {
        scope.launch { stop(running, id) }
    }

    private suspend fun stop(running: Running, id: String) = withContext(NonCancellable) {
        running.server.destroy()
        runner.killAll(running.home, running.server.process)
        egress.revoke(runUser(id))
        log.info { "stopped server $id" }
    }

    /** The running stdio server for [entry], started (and listed) if needed. */
    private suspend fun ensureRunning(entry: Entry, spec: ServerSpec): StdioServer = entry.startLock.withLock {
        lock.withLock {
            entry.running?.takeIf { it.runKey == spec.runKey && !it.server.exited.isCompleted }?.let { return it.server }
            val until = entry.retryAt
            if (until != null && entry.failedKey == spec.runKey && clock.now() < until) {
                throw Unavailable(503, "${spec.label} is failing (${entry.error}); it is retried after ${until.toString().take(19).replace('T', ' ')} UTC")
            }
        }
        val installed = state.current.installed[spec.id]?.takeIf { it.installKey == spec.installKey }
            ?: throw Unavailable(503, "${spec.label} is not installed yet")
        // room for one more
        val victim = lock.withLock {
            val views = entries.values.mapNotNull { e -> e.running?.let { RunningView(e.id, e.lastUsed, e.inFlight) } }
            evictionVictim(views, spec.id, policy)?.let { id -> entries[id]?.let { e -> detachRunning(e)?.let { e.id to it } } }
        }
        victim?.let { (id, running) -> log.info { "stopping $id to make room for ${spec.label}" }; stop(running, id) }

        val home = homes.prepare(spec.id)
        val credential = egress.grant(runUser(spec.id), spec.allowHosts)
        val redactor = Redactor(spec.secretValues + listOfNotNull(credential?.password))
        val env = childEnv(home, credential?.url, spec.env + spec.secrets, childPath)
        val server = try {
            StdioServer(runner.spawn(home, installed.command + spec.args, env), spec.label, redactor, scope).also { it.start() }
        } catch (e: Exception) {
            egress.revoke(runUser(spec.id))
            val message = "could not start: ${redactor.clean(e.message ?: e.javaClass.simpleName, 200)}"
            lock.withLock { fail(entry, spec, message) }
            throw Unavailable(503, "${spec.label} $message")
        }
        try {
            kotlinx.coroutines.withTimeout(startTimeout) {
                val init = server.initialize()
                val tools = server.listTools()
                state.saveListing(spec.id, Listing(spec.runKey, clock.now().toString(), tools, init))
                log.info { "${spec.label} started (pid ${server.pid}), ${tools.size} tools" }
            }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                startFailed(entry, spec, server, home, "did not start within $startTimeout")
            } else {
                withContext(NonCancellable) { server.destroy(); runner.killAll(home, server.process); egress.revoke(runUser(spec.id)) }
                throw e
            }
        } catch (e: Exception) {
            val code = kotlinx.coroutines.withTimeoutOrNull(2.seconds) { server.exited.await() }
            val reason = if (code != null) server.exitMessage(code) else redactor.clean(e.message ?: "start failed", 200)
            startFailed(entry, spec, server, home, reason)
        }
        val running = Running(server, home, spec.runKey)
        lock.withLock {
            entry.running = running
            entry.lastUsed = clock.now()
            entry.error = null
        }
        scope.launch { watchExit(entry, spec, running) }
        server
    }

    private suspend fun startFailed(entry: Entry, spec: ServerSpec, server: StdioServer, home: ServerHome, reason: String): Nothing {
        withContext(NonCancellable) {
            server.destroy()
            runner.killAll(home, server.process)
            egress.revoke(runUser(spec.id))
        }
        lock.withLock { fail(entry, spec, reason) }
        log.warn { "${spec.label} failed to start: $reason" }
        throw Unavailable(503, "${spec.label} failed to start: $reason")
    }

    /** A process that ends while still attached to its entry crashed: mark it failed and back off. */
    private suspend fun watchExit(entry: Entry, spec: ServerSpec, running: Running) {
        val code = running.server.exited.await()
        val crashed = lock.withLock {
            if (entry.running !== running) return@withLock false
            entry.running = null
            fail(entry, spec, running.server.exitMessage(code))
            true
        }
        if (crashed) {
            log.warn { "${spec.label} crashed: ${running.server.exitMessage(code)}" }
            withContext(NonCancellable) { runner.killAll(running.home); egress.revoke(runUser(spec.id)) }
        }
    }

    /** Stops servers that had no request for the idle timeout; run every [every]. */
    suspend fun sweepIdle(every: Duration = 30.seconds) {
        while (scope.isActive) {
            delay(every)
            stopIdle()
        }
    }

    suspend fun stopIdle() {
        val idle = lock.withLock {
            val views = entries.values.mapNotNull { e -> e.running?.let { RunningView(e.id, e.lastUsed, e.inFlight) } }
            idleServers(views, clock.now(), policy).mapNotNull { id -> entries[id]?.let { e -> detachRunning(e)?.let { id to it } } }
        }
        for ((id, running) in idle) {
            log.info { "stopping $id: idle" }
            stop(running, id)
        }
    }

    /** Stops every server (shutdown). */
    suspend fun stopAll() {
        val all = lock.withLock { entries.values.mapNotNull { e -> detachRunning(e)?.let { e.id to it } } }
        for ((id, running) in all) stop(running, id)
    }

    // ---- requests ----------------------------------------------------------------------------

    /** Handles one `POST /s/{id}/mcp` body (a JSON-RPC message or a batch) for a stdio server. */
    suspend fun handle(id: String, body: JsonElement): McpOutcome {
        val (entry, spec) = try { usable(id) } catch (e: Unavailable) { return McpOutcome.Refused(e.status, e.message!!) }
        if (spec.kind == ServerKind.REMOTE) return McpOutcome.Refused(500, "remote servers are forwarded, not handled here")
        val messages = when (body) {
            is JsonObject -> listOf(body)
            is JsonArray -> body.filterIsInstance<JsonObject>().also { if (it.isEmpty()) return McpOutcome.Refused(400, "empty batch") }
            else -> return McpOutcome.Refused(400, "the body is not JSON-RPC")
        }
        val replies = mutableListOf<JsonObject>()
        for (message in messages) {
            val requestId = StdioServer.idOf(message) ?: continue // notifications and responses: nothing to pass on
            val reply = try {
                answer(entry, spec, requestId, message)
            } catch (e: Unavailable) {
                if (messages.size == 1) return McpOutcome.Refused(e.status, e.message!!)
                rpcError(requestId, -32002, e.message!!)
            }
            replies += reply
        }
        return when {
            replies.isEmpty() -> McpOutcome.Accepted
            body is JsonArray -> McpOutcome.Reply(JsonArray(replies))
            else -> McpOutcome.Reply(replies.single())
        }
    }

    private suspend fun answer(entry: Entry, spec: ServerSpec, requestId: JsonElement, message: JsonObject): JsonObject {
        val method = StdioServer.methodOf(message)
        val cached = state.listing(spec.id)?.takeIf { it.runKey == spec.runKey }
        when (method) {
            "ping" -> return rpcResult(requestId, JsonObject(emptyMap()))
            "initialize" -> cached?.initialize?.let { return rpcResult(requestId, it) }
            "tools/list" -> {
                val params = message["params"] as? JsonObject
                if (cached != null && params?.get("cursor") == null) return rpcResult(requestId, JsonObject(mapOf("tools" to cached.tools)))
            }
        }
        val server = ensureRunning(entry, spec)
        if (method == "initialize") return rpcResult(requestId, server.initializeResult ?: JsonObject(emptyMap()))
        lock.withLock { entry.inFlight++; entry.lastUsed = clock.now() }
        try {
            val response = server.call(message, callTimeout)
            if (response["error"] == null) lock.withLock { succeeded(entry) }
            return response
        } catch (e: ServerExited) {
            return rpcError(requestId, -32603, "${spec.label} stopped: ${e.message}")
        } finally {
            lock.withLock { entry.inFlight--; entry.lastUsed = clock.now() }
        }
    }

    /** The configured, enabled, installed server [id], or why it cannot take requests. */
    private suspend fun usable(id: String): Pair<Entry, ServerSpec> = lock.withLock {
        if (!configured) throw Unavailable(503, "the MCP host is starting; try again shortly")
        val entry = entries[id] ?: throw Unavailable(404, "no server $id")
        val spec = entry.spec ?: throw Unavailable(409, "${entry.item?.label ?: id}: ${entry.item?.spec?.leftOrNull()}")
        if (!spec.enabled) throw Unavailable(409, "${spec.label} is switched off")
        if (entry.installingKey != null) throw Unavailable(503, "${spec.label} is still installing")
        entry to spec
    }

    /** The remote server [id] for forwarding, or why not. Marks it used. */
    suspend fun remote(id: String): RemoteServer {
        val (entry, spec) = usable(id)
        if (spec.kind != ServerKind.REMOTE) throw Unavailable(405, "${spec.label} is a local server")
        return lock.withLock { entry.lastUsed = clock.now(); entry.remote } ?: throw Unavailable(503, "${spec.label} is not ready")
    }

    /** Records a failed forward to a remote server (it backs off like a crash). */
    suspend fun remoteFailed(id: String, message: String) = lock.withLock {
        val entry = entries[id] ?: return@withLock
        val spec = entry.spec ?: return@withLock
        fail(entry, spec, message)
    }

    // ---- status ------------------------------------------------------------------------------

    /** Every configured server, in config order, with its state and cached listing. */
    suspend fun servers(): List<HostServer> = lock.withLock {
        if (!configured) {
            return@withLock state.current.known.map { k ->
                val listing = state.listing(k.id)
                HostServer(k.id, k.label, k.description, if (k.enabled) McpServerState.IDLE else McpServerState.DISABLED, null, k.source, k.allowHosts,
                    listing?.tools ?: JsonArray(emptyList()), listing?.listedAt)
            }
        }
        entries.values.map { entry ->
            val item = entry.item!!
            val spec = entry.spec
            val listing = state.listing(entry.id)
            val (st, error) = when {
                spec == null -> McpServerState.FAILED to item.spec.leftOrNull()
                !spec.enabled -> McpServerState.DISABLED to null
                entry.installingKey != null -> McpServerState.INSTALLING to null
                entry.running != null -> McpServerState.RUNNING to null
                entry.error != null -> McpServerState.FAILED to entry.error
                spec.kind != ServerKind.REMOTE && state.current.installed[spec.id]?.installKey != spec.installKey -> McpServerState.INSTALLING to null
                else -> McpServerState.IDLE to null
            }
            HostServer(
                id = entry.id, label = item.label, description = item.description, state = st, error = error,
                source = spec?.source.orEmpty(), allowHosts = spec?.allowHosts.orEmpty(),
                tools = listing?.tools ?: JsonArray(emptyList()), listedAt = listing?.listedAt,
            )
        }
    }

    /** "GitHub: 12 tools · Canvas: version must be exact, not ^1.2" for the app's connection test. */
    suspend fun summary(): Pair<Boolean, String> {
        val list = servers()
        if (list.isEmpty()) return true to "No servers added yet."
        val ok = list.none { it.state == McpServerState.FAILED }
        return ok to list.sortedBy { it.state != McpServerState.FAILED }.joinToString(" · ") { s ->
            "${s.label}: " + when (s.state) {
                McpServerState.FAILED -> s.error ?: "failed"
                McpServerState.INSTALLING -> "installing"
                McpServerState.DISABLED -> "switched off"
                else -> "${s.tools.size} tools"
            }
        }
    }
}
