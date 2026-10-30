package net.robog.clarity.backend.mcpservers

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.robog.clarity.backend.config.McpHostConfig
import net.robog.clarity.harness.McpToolProvider
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.mcp.McpServerState

private val log = KotlinLogging.logger {}

/** One tool of a server as the host listed it (its cached `tools/list`). */
data class HostTool(
    val name: String,
    val title: String? = null,
    val description: String = "",
    /** Normalized to what models take: `type`, `properties`, `required`, `$defs`. */
    val inputSchema: JsonObject = EMPTY_SCHEMA,
    /** `annotations.readOnlyHint`; false when absent. */
    val readOnly: Boolean = false,
    /** `annotations.destructiveHint`; when absent, true unless read-only (the MCP default). */
    val destructive: Boolean = !readOnly,
)

/** One configured server as `GET /servers` reports it. */
data class HostServer(
    val id: String,
    val label: String,
    val description: String = "",
    val state: McpServerState,
    val error: String? = null,
    val source: String = "",
    val allowHosts: List<String> = emptyList(),
    val tools: List<HostTool> = emptyList(),
    val listedAt: String? = null,
)

/** The `mcp-host` container (contract 2): its server listing, and a tool call to one server. */
interface McpHost {
    /** False when `CLARITY_MCP_HOST` is unset: there are no third-party servers. */
    val configured: Boolean get() = true

    /** Every configured server; throws when the host cannot be reached or answers badly. */
    suspend fun servers(): List<HostServer>

    /** Calls [tool] on server [serverId] (the host starts the server if needed). */
    suspend fun call(serverId: String, tool: String, input: JsonObject): ToolResult

    suspend fun close() {}

    /** No host configured. */
    object None : McpHost {
        override val configured = false
        override suspend fun servers(): List<HostServer> = emptyList()
        override suspend fun call(serverId: String, tool: String, input: JsonObject) = ToolResult.Error("No MCP servers are set up on this server.")
    }

    companion object {
        fun of(config: McpHostConfig?): McpHost = config?.let(::HttpMcpHost) ?: None
    }
}

/**
 * The host over HTTP with its bearer key: `GET /servers`, and one [McpToolProvider] per server at
 * `/s/{id}/mcp` (created on first call, shared by all runs). The listing never starts a server.
 */
class HttpMcpHost(private val config: McpHostConfig, client: HttpClient? = null) : McpHost {
    private val http: HttpClient = client ?: HttpClient(CIO) {
        install(SSE)
        // A tool call streams its answer; only the listing gets a short timeout.
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 10_000
        }
        readKey()?.let { key -> install(DefaultRequest) { header(HttpHeaders.Authorization, "Bearer $key") } }
    }
    private val owned = client == null
    private val providers = ConcurrentHashMap<String, McpToolProvider>()

    private fun readKey(): String? = config.keyFile?.let { path ->
        try {
            Path(path).readText().trim().ifEmpty { null }
        } catch (e: IOException) {
            log.warn { "MCP host: cannot read key file $path (${e.message}); connecting without a key" }
            null
        }
    }

    override suspend fun servers(): List<HostServer> {
        val response = http.get("${config.url}/servers") { timeout { requestTimeoutMillis = LISTING_TIMEOUT_MS } }
        if (!response.status.isSuccess()) throw IOException("the MCP host answered ${response.status.value}")
        return parseHostServers(ClarityJson.parseToJsonElement(response.bodyAsText()))
    }

    override suspend fun call(serverId: String, tool: String, input: JsonObject): ToolResult =
        providers.computeIfAbsent(serverId) { McpToolProvider("${config.url}/s/${it.encodeURLPathPart()}/mcp", http) }.call(tool, input)

    override suspend fun close() {
        providers.values.forEach { runCatching { it.close() } }
        if (owned) http.close()
    }

    private companion object {
        const val LISTING_TIMEOUT_MS = 10_000L
    }
}

private val EMPTY_SCHEMA: JsonObject = buildJsonObject { put("type", "object"); put("properties", JsonObject(emptyMap())) }

/**
 * Pure: the host's `GET /servers` body. A server or tool that cannot be read is left out (logged)
 * rather than failing the whole listing; an unknown state reads as failed.
 */
fun parseHostServers(body: JsonElement): List<HostServer> {
    val items = (body as? JsonArray) ?: (body as? JsonObject)?.get("servers") as? JsonArray
        ?: throw IOException("the MCP host's server list is not a JSON array")
    return items.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val id = o.string("id") ?: return@mapNotNull null
        val label = o.string("label")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        HostServer(
            id = id,
            label = label,
            description = o.string("description").orEmpty(),
            state = parseState(o.string("state")),
            error = o.string("error"),
            source = o.string("source").orEmpty(),
            allowHosts = (o["allowHosts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            tools = (o["tools"] as? JsonArray)?.mapNotNull { parseHostTool(it) }.orEmpty(),
            listedAt = o.string("listedAt"),
        )
    }
}

/** Pure: one raw MCP tool object (name, title, description, inputSchema, annotations); null without a name. */
fun parseHostTool(raw: JsonElement): HostTool? {
    val o = raw as? JsonObject ?: return null
    val name = o.string("name")?.ifEmpty { null } ?: return null
    val annotations = o["annotations"] as? JsonObject
    val readOnly = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull ?: false
    val destructive = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull ?: !readOnly
    return HostTool(
        name = name,
        title = o.string("title") ?: annotations?.string("title"),
        description = o.string("description").orEmpty(),
        inputSchema = normalizedSchema(o["inputSchema"] as? JsonObject),
        readOnly = readOnly,
        destructive = destructive,
    )
}

/** Pure: an MCP input schema reduced to what every model provider takes (as the static servers' tools are). */
fun normalizedSchema(schema: JsonObject?): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", (schema?.get("properties") as? JsonObject) ?: JsonObject(emptyMap()))
    (schema?.get("required") as? JsonArray)?.let { put("required", it) }
    (schema?.get("\$defs") as? JsonObject)?.let { put("\$defs", it) }
}

private fun parseState(raw: String?): McpServerState =
    McpServerState.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: McpServerState.FAILED

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
