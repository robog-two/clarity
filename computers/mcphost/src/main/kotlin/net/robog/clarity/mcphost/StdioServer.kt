package net.robog.clarity.mcphost

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.shared.ClarityJson

private val log = KotlinLogging.logger {}

/** The MCP protocol version the host asks for when it initializes a stdio server. */
const val HOST_PROTOCOL_VERSION = "2025-06-18"

/** Largest JSON-RPC message accepted from a server, one line on stdout. */
const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024

/** The server process ended (or was never usable); [message] is short and redacted. */
class ServerExited(message: String) : Exception(message)

/** A JSON-RPC error object as a response to [id]. */
fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    putJsonObject("error") { put("code", code); put("message", message) }
}

fun rpcResult(id: JsonElement?, result: JsonElement): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    put("result", result)
}

/**
 * One stdio MCP server process, spoken to with newline-delimited JSON-RPC.
 *
 * Several HTTP clients share the process, so every request is sent with an id of the host's own
 * and the client's id is put back on the response. The host initializes the server once itself
 * ([initialize]) and answers clients' `initialize` from that result. Requests a server makes of its
 * client (sampling, roots, elicitation) are answered "not supported": the backend's sessions are
 * stateless and have no channel back. Its notifications are dropped.
 */
class StdioServer(
    val process: Process,
    private val name: String,
    private val redactor: Redactor,
    private val scope: CoroutineScope,
) {
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val ids = AtomicLong(0)
    private val writeLock = Mutex()
    private val stderrTail = ArrayDeque<String>()

    /** Completes with the exit code when the process has ended. */
    val exited = CompletableDeferred<Int>()

    @Volatile private var exitCode: Int? = null

    /** The server's answer to the host's `initialize`. */
    @Volatile var initializeResult: JsonObject? = null
        private set

    val pid: Long get() = process.pid()

    fun start() {
        scope.launch(Dispatchers.IO) { readStdout(process.inputStream) }
        val stderr = scope.launch(Dispatchers.IO) { readStderr(process.errorStream) }
        scope.launch(Dispatchers.IO) {
            val code = runInterruptible { process.waitFor() }
            // let the last stderr lines in, so the exit message can quote them
            withTimeoutOrNull(1.seconds) { stderr.join() }
            exitCode = code
            val reason = ServerExited(exitMessage(code))
            pending.values.forEach { it.completeExceptionally(reason) }
            exited.complete(code)
        }
    }

    /** Short reason for an exit: the code and the last thing the server said on stderr. */
    fun exitMessage(code: Int): String {
        val last = synchronized(stderrTail) { stderrTail.lastOrNull { it.isNotBlank() } }
        return "exited with code $code" + (last?.let { ": " + redactor.clean(it, 200) } ?: "")
    }

    /** Initializes the server (once) and remembers its result. */
    suspend fun initialize(timeout: Duration = 60.seconds): JsonObject {
        val params = buildJsonObject {
            put("protocolVersion", HOST_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "clarity-mcp-host"); put("version", "1") }
        }
        val response = request("initialize", params, timeout)
        val result = response["result"] as? JsonObject
            ?: throw ServerExited("initialize failed: ${errorText(response)}")
        notify("notifications/initialized")
        initializeResult = result
        return result
    }

    /** Every tool the server lists (following `nextCursor`, at most 50 pages), as raw MCP tool objects. */
    suspend fun listTools(timeout: Duration = 60.seconds): JsonArray {
        val tools = mutableListOf<JsonElement>()
        var cursor: String? = null
        repeat(50) {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val response = request("tools/list", params, timeout)
            val result = response["result"] as? JsonObject ?: throw ServerExited("tools/list failed: ${errorText(response)}")
            (result["tools"] as? JsonArray)?.let(tools::addAll)
            cursor = (result["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (cursor == null) return JsonArray(tools)
        }
        return JsonArray(tools)
    }

    /** Sends a request of the host's own and waits for the response (a JSON-RPC error on timeout). */
    suspend fun request(method: String, params: JsonElement?, timeout: Duration): JsonObject {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            params?.let { put("params", it) }
        }
        return call(message, timeout)
    }

    /**
     * Forwards a client's request under a host id and returns the response with the client's id
     * back on it. Cancelling the caller (the HTTP request went away) cancels the call on the server.
     */
    suspend fun call(request: JsonObject, timeout: Duration): JsonObject {
        val clientId = request["id"]
        val internal = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonObject>()
        pending[internal] = answer
        exitCode?.let { answer.completeExceptionally(ServerExited(exitMessage(it))) }
        try {
            send(JsonObject(request + ("id" to JsonPrimitive(internal))))
            val response = withTimeoutOrNull(timeout) { answer.await() }
                ?: run {
                    cancelOnServer(internal, "timed out")
                    return rpcError(clientId, -32001, "$name did not answer within $timeout")
                }
            return JsonObject(response + ("id" to (clientId ?: JsonNull)))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cancelOnServer(internal, "the client went away") }
            throw e
        } finally {
            pending.remove(internal)
        }
    }

    suspend fun notify(method: String, params: JsonElement? = null) = send(buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        params?.let { put("params", it) }
    })

    private suspend fun cancelOnServer(id: Long, reason: String) {
        runCatching {
            notify("notifications/cancelled", buildJsonObject { put("requestId", id); put("reason", reason) })
        }
    }

    private suspend fun send(message: JsonObject) {
        val bytes = (ClarityJson.encodeToString(JsonObject.serializer(), message) + "\n").toByteArray()
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    process.outputStream.write(bytes)
                    process.outputStream.flush()
                } catch (e: java.io.IOException) {
                    throw ServerExited("$name is not accepting input (${e.message})")
                }
            }
        }
    }

    private suspend fun readStdout(input: InputStream) {
        var junk = 0
        while (true) {
            val line = try { readLine(input) } catch (e: MessageTooLarge) {
                log.warn { "$name sent a message over ${MAX_MESSAGE_BYTES / 1024 / 1024} MB; stopping it" }
                process.destroyForcibly()
                return
            } ?: return
            if (line.isBlank()) continue
            val element = runCatching { ClarityJson.parseToJsonElement(line) }.getOrNull()
            val messages = when (element) {
                is JsonObject -> listOf(element)
                is JsonArray -> element.filterIsInstance<JsonObject>()
                else -> {
                    if (junk++ < 5) log.info { "$name wrote a line that is not JSON-RPC to stdout (ignored)" }
                    continue
                }
            }
            messages.forEach { handle(it) }
        }
    }

    private suspend fun handle(message: JsonObject) {
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when {
            method == null && id != null -> {
                val key = (id as? JsonPrimitive)?.longOrNull ?: return
                pending[key]?.complete(message)
            }
            method != null && id != null && id !is JsonNull -> {
                // a request from the server to its client
                val reply = if (method == "ping") rpcResult(id, JsonObject(emptyMap()))
                else rpcError(id, -32601, "$method is not supported by Clarity's MCP host")
                runCatching { send(reply) }
            }
            else -> Unit // a notification (logging, progress, list_changed): nothing to do with it here
        }
    }

    private fun readStderr(input: InputStream) {
        var logged = 0
        input.bufferedReader().useLines { lines ->
            for (line in lines) {
                synchronized(stderrTail) {
                    stderrTail.addLast(line.take(1000))
                    while (stderrTail.size > 20) stderrTail.removeFirst()
                }
                if (logged < 200) {
                    logged++
                    log.info { "[$name] ${redactor.clean(line)}" }
                    if (logged == 200) log.info { "[$name] (further output not logged)" }
                }
            }
        }
    }

    /** Ends the process (and, for the caller, its uid's other processes; see [ProcessRunner.killAll]). */
    fun destroy() {
        runCatching { process.outputStream.close() }
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private class MessageTooLarge : Exception()

    /** One `\n`-terminated line as UTF-8, or null at end of stream; refuses lines over [MAX_MESSAGE_BYTES]. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8)
            if (buf.size() >= MAX_MESSAGE_BYTES) throw MessageTooLarge()
            buf.write(b)
        }
    }

    companion object {
        fun errorText(response: JsonObject): String =
            ((response["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.content ?: "no result"

        /** The id of a JSON-RPC message, when it is a request or a response. */
        fun idOf(message: JsonObject): JsonElement? = message["id"]?.takeIf { it !is JsonNull }

        fun methodOf(message: JsonObject): String? = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
    }
}
