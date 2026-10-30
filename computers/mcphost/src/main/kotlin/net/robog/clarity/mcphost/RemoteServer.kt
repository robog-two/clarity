package net.robog.clarity.mcphost

import java.io.InputStream
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.shared.ClarityJson

/** Request headers passed on to a remote MCP server; nothing else from the backend leaves the host. */
val FORWARDED_REQUEST_HEADERS = listOf("Accept", "Content-Type", "Mcp-Session-Id", "MCP-Protocol-Version", "Last-Event-ID")

/** Response headers passed back to the backend. */
val FORWARDED_RESPONSE_HEADERS = listOf("Content-Type", "Mcp-Session-Id", "Cache-Control")

/**
 * A JDK HTTP client that reaches the internet only through the egress proxy, as [credential]'s
 * user. The proxy password is answered only to the proxy's 407 challenge (and only on CONNECT for
 * https), never sent to the server itself. Redirects are not followed unless [followRedirects].
 * Without a [credential] (no egress proxy configured: tests and development) it connects directly.
 */
fun proxiedHttpClient(credential: ProxyCredential?, followRedirects: Boolean = false): HttpClient {
    val builder = HttpClient.newBuilder()
        .connectTimeout(JDuration.ofSeconds(20))
        .followRedirects(if (followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
    if (credential != null) {
        builder.proxy(ProxySelector.of(InetSocketAddress(credential.host, credential.port)))
        builder.authenticator(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestorType == RequestorType.PROXY) PasswordAuthentication(credential.user, credential.password.toCharArray()) else null
        })
    }
    return builder.build()
}

/**
 * A remote (streamable HTTP) MCP server. The host forwards the backend's requests to [url] with
 * the server's bearer secret, through the egress proxy, so the backend sees every server alike.
 */
class RemoteServer(val spec: ServerSpec, credential: ProxyCredential?) : AutoCloseable {
    private val url = URI(requireNotNull(spec.url))
    private val client = proxiedHttpClient(credential)

    /** Sends one request through and returns the response with its body as a stream (SSE stays open). */
    suspend fun forward(method: String, headers: Map<String, String>, body: ByteArray?): HttpResponse<InputStream> {
        val request = HttpRequest.newBuilder(url).timeout(JDuration.ofMinutes(10))
        for ((name, value) in headers) if (FORWARDED_REQUEST_HEADERS.any { it.equals(name, ignoreCase = true) }) request.header(name, value)
        spec.bearer?.let { request.header("Authorization", "Bearer $it") }
        request.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
    }

    /** Lists the server's tools with a short MCP session of the host's own. */
    suspend fun listTools(timeout: Duration = 60.seconds): JsonArray {
        var session: String? = null
        var protocol: String? = null
        suspend fun rpc(id: Int?, method: String, params: JsonObject?): JsonObject? {
            val message = buildJsonObject {
                put("jsonrpc", "2.0")
                id?.let { put("id", it) }
                put("method", method)
                params?.let { put("params", it) }
            }
            val headers = buildMap {
                put("Accept", "application/json, text/event-stream")
                put("Content-Type", "application/json")
                session?.let { put("Mcp-Session-Id", it) }
                protocol?.let { put("MCP-Protocol-Version", it) }
            }
            val response = withContext(Dispatchers.IO) { kotlinx.coroutines.withTimeout(timeout) { forward("POST", headers, ClarityJson.encodeToString(JsonObject.serializer(), message).toByteArray()) } }
            response.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
            response.body().use { stream ->
                if (response.statusCode() !in 200..299) throw ServerExited("$method: HTTP ${response.statusCode()}")
                if (id == null) return null
                val type = response.headers().firstValue("Content-Type").orElse("")
                return withContext(Dispatchers.IO) {
                    if (type.startsWith("text/event-stream")) readSseResponse(stream, id)
                    else ClarityJson.parseToJsonElement(stream.readNBytes(MAX_MESSAGE_BYTES).toString(Charsets.UTF_8)) as? JsonObject
                } ?: throw ServerExited("$method: no response")
            }
        }
        val init = rpc(1, "initialize", buildJsonObject {
            put("protocolVersion", HOST_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "clarity-mcp-host"); put("version", "1") }
        })!!
        val result = init["result"] as? JsonObject ?: throw ServerExited("initialize failed: ${StdioServer.errorText(init)}")
        protocol = (result["protocolVersion"] as? JsonPrimitive)?.content
        rpc(null, "notifications/initialized", null)
        val tools = mutableListOf<JsonElement>()
        var cursor: String? = null
        var id = 2
        do {
            val page = rpc(id++, "tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })!!
            val r = page["result"] as? JsonObject ?: throw ServerExited("tools/list failed: ${StdioServer.errorText(page)}")
            (r["tools"] as? JsonArray)?.let(tools::addAll)
            cursor = (r["nextCursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        } while (cursor != null && id < 50)
        session?.let { s ->
            runCatching { withContext(Dispatchers.IO) { forward("DELETE", mapOf("Mcp-Session-Id" to s), null).body().close() } }
        }
        return JsonArray(tools)
    }

    override fun close() {
        client.close()
    }
}

/** Reads server-sent events until a JSON-RPC response with [id] arrives. */
internal fun readSseResponse(stream: InputStream, id: Int): JsonObject? {
    val reader = stream.bufferedReader()
    val data = StringBuilder()
    while (true) {
        val line = reader.readLine() ?: return null
        when {
            line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
            line.isEmpty() && data.isNotEmpty() -> {
                val message = runCatching { ClarityJson.parseToJsonElement(data.toString()) }.getOrNull() as? JsonObject
                data.clear()
                if (message != null && (message["id"] as? JsonPrimitive)?.content == id.toString() && message["method"] == null) return message
            }
        }
    }
}
