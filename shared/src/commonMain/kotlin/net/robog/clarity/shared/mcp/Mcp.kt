package net.robog.clarity.shared.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Third-party MCP servers run by the `mcp-host` connector (see `docs/workstreams/mcp.md`).
 * Servers are added and edited through that connector's config form; these types cover what the
 * config form cannot: live state, the tools a server offers, how each tool may be used, and
 * servers Clarity suggests.
 */
object McpPaths {
    /** `GET`: every configured server with its state and tools ([McpServerList]). */
    const val SERVERS = "/api/v1/mcp/servers"

    /** `PUT {serverId}/tools/{tool}` with [McpToolPolicyUpdate]: how one tool may be used. */
    fun toolPolicy(serverId: String, tool: String) = "$SERVERS/$serverId/tools/$tool"

    /** `GET`: open suggestions ([McpSuggestionList]); `DELETE {id}` dismisses one. */
    const val SUGGESTIONS = "/api/v1/mcp/suggestions"
}

/** How Clarity may use one tool. */
@Serializable
enum class McpToolMode {
    /** Runs without asking. The default only for tools the server marks read-only. */
    @SerialName("auto") AUTO,

    /** Each call waits for the person's approval, like sending an email. The default for every other tool. */
    @SerialName("ask") ASK,

    /** Never offered to Clarity. */
    @SerialName("off") OFF,
}

/** Where a server is in its life. */
@Serializable
enum class McpServerState {
    /** Being downloaded and installed. */
    @SerialName("installing") INSTALLING,

    /** Installed and not running; it starts when a helper first needs it. */
    @SerialName("idle") IDLE,

    @SerialName("running") RUNNING,

    /** Install or start failed; [McpServerStatus.error] says why. */
    @SerialName("failed") FAILED,

    /** Switched off in its config. */
    @SerialName("disabled") DISABLED,
}

@Serializable
data class McpServerList(val servers: List<McpServerStatus>)

@Serializable
data class McpServerStatus(
    /** The connector config item id (stable). */
    val id: String,
    /** The person's name for it ("GitHub", "Canvas"). */
    val label: String,
    val description: String = "",
    val state: McpServerState,
    /** Short, human readable; never contains a token. */
    val error: String? = null,
    /** "npm @scope/pkg@1.2.3", "remote https://…", for display. */
    val source: String = "",
    /** Hosts it may reach through the egress proxy. */
    val allowHosts: List<String> = emptyList(),
    /** Empty until the server has been listed once. */
    val tools: List<McpToolStatus> = emptyList(),
    /** Name of the memory collection Clarity keeps for this server, once it exists. */
    val collection: String? = null,
    /** When its tools were last listed (ISO instant). */
    val listedAt: String? = null,
)

@Serializable
data class McpToolStatus(
    /** The server's own tool name (`list_issues`). */
    val name: String,
    val title: String? = null,
    /** First sentence of the server's description, at most 200 characters. */
    val description: String = "",
    /** From the server's `readOnlyHint` annotation; false when absent. */
    val readOnly: Boolean = false,
    /** From the server's `destructiveHint` annotation; true when absent and not read-only (the MCP default). */
    val destructive: Boolean = false,
    /** The mode in effect: the person's choice, or the default from [readOnly]. */
    val mode: McpToolMode,
    /** True when [mode] is the person's choice rather than the default. */
    val overridden: Boolean = false,
)

/** `null` [mode] clears the person's choice and returns to the default. */
@Serializable
data class McpToolPolicyUpdate(val mode: McpToolMode?)

@Serializable
data class McpSuggestionList(val suggestions: List<McpSuggestion>)

/**
 * A server Clarity thinks would help. Shown only in Settings, never posted to the chat. Setting it
 * up opens the add-server form prefilled from [preset] (a title in the `x-presets` of the
 * `mcp-host` config schema) or left empty when null.
 */
@Serializable
data class McpSuggestion(
    val id: String,
    val label: String,
    /** One or two sentences: what it would let Clarity do for the person. */
    val why: String,
    val preset: String? = null,
    /** ISO instant. */
    val createdAt: String,
)
