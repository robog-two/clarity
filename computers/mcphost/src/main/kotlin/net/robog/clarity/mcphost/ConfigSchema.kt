package net.robog.clarity.mcphost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Name of the config collection that holds the servers. */
const val SERVERS = "servers"

private typealias JsonObjectBuilderScope = kotlinx.serialization.json.JsonObjectBuilder

private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

/**
 * `x-visible-when`: the app shows (validates and sends) the property only while `kind` is one of
 * [kinds]. Only a hint for the form; the host validates every item the same way regardless.
 */
private fun JsonObjectBuilderScope.visibleWhenKind(kinds: List<ServerKind>?) {
    kinds ?: return
    putJsonObject("x-visible-when") { put("kind", JsonArray(kinds.map { JsonPrimitive(it.wire) })) }
}

/** Kinds that install and run a program here (everything but a remote URL). */
private val installed = ServerKind.offered - ServerKind.REMOTE

private fun JsonObjectBuilderScope.text(
    name: String,
    title: String,
    description: String? = null,
    example: String? = null,
    visibleWhen: List<ServerKind>? = null,
) =
    putJsonObject(name) {
        put("type", "string"); put("title", title)
        description?.let { put("description", it) }
        example?.let { put("examples", strings(it)) }
        visibleWhenKind(visibleWhen)
    }

private fun JsonObjectBuilderScope.list(name: String, title: String, description: String, example: String? = null) =
    putJsonObject(name) {
        put("type", "array"); put("title", title); put("description", description)
        putJsonObject("items") { put("type", "string") }
        example?.let { put("examples", strings(it)) }
    }

private fun JsonObjectBuilderScope.secret(name: String, title: String, description: String, visibleWhen: List<ServerKind>? = null) =
    putJsonObject(name) {
        put("type", "string"); put("title", title); put("description", description)
        put("format", "password"); put("writeOnly", true); put("x-secret", true)
        visibleWhenKind(visibleWhen)
    }

/** One server, in the property order of the contract. */
private val serverItem = buildJsonObject {
    put("type", "object")
    put("required", strings("label", "kind"))
    putJsonObject("properties") {
        putJsonObject("id") { put("type", "string"); put("readOnly", true) }
        text("label", "Name", "What you call it. Clarity's tools for it start with this name.", "GitHub")
        text("description", "What it is for", "One line Clarity reads to know when to use it.", "Your GitHub repos, issues and pull requests")
        putJsonObject("enabled") { put("type", "boolean"); put("title", "On"); put("default", true) }
        putJsonObject("kind") {
            put("type", "string"); put("title", "Where it comes from")
            put("enum", JsonArray(ServerKind.offered.map { JsonPrimitive(it.wire) }))
            put("x-enum-titles", JsonArray(ServerKind.offered.map { JsonPrimitive(it.title) }))
            put("default", ServerKind.NPM.wire)
        }
        text("package", "Package", "npm or Python package name, or owner/repo for a GitHub release.", "@scope/some-mcp-server", installed)
        text("version", "Version", "An exact version or release tag. Ranges and \"latest\" are not accepted, so nothing changes on its own.", "1.2.3", installed)
        text("asset", "Release file", "GitHub release only: the file to download.", "server_Linux_x86_64.tar.gz", listOf(ServerKind.GITHUB_RELEASE))
        text("sha256", "SHA-256 of the release file", "GitHub release only: the file is checked against it before it is kept.", visibleWhen = listOf(ServerKind.GITHUB_RELEASE))
        text("command", "Command", "The command to run, when the package has more than one (for a release: the file inside the archive).", visibleWhen = installed)
        list("args", "Arguments", "Passed to the command, one per entry.", "stdio")
        list("env", "Settings", "KEY=value entries for the server's environment. Not for secrets.", "LOG_LEVEL=info")
        text("secretEnv", "Token variable", "The environment variable that receives the token.", "API_TOKEN", installed)
        secret("secret", "Token", "Kept sealed on your server; only this server sees it. For a remote URL it is sent as a bearer token.")
        text("secretEnv2", "Second secret variable", "Only if the server needs a second secret.", visibleWhen = installed)
        secret("secret2", "Second secret", "Kept sealed like the token.", installed)
        putJsonObject("url") {
            put("type", "string"); put("format", "uri"); put("title", "Server URL")
            put("description", "Remote URL only: the server's streamable HTTP address (https).")
            put("examples", strings("https://mcp.example.com/mcp"))
            visibleWhenKind(listOf(ServerKind.REMOTE))
        }
        list("allowHosts", "Hosts it may reach", "The only internet hosts this server can connect to. A leading dot allows subdomains.", "api.example.com")
    }
}

/**
 * Presets: only packages checked against their registry at exactly this version and seen working
 * through the egress proxy (`SMOKE_PRESETS=1 computers/mcphost/smoke.sh`), with what the person needs
 * to create the token. GitHub: the release's own checksums file and the GitHub API's asset digest
 * agree on the sha256. Canvas: PyPI's JSON API, a pure-Python wheel.
 */
val serverPresets: JsonArray = JsonArray(listOf(
    buildJsonObject {
        put("title", "GitHub")
        put(
            "description",
            "GitHub's own MCP server (release v2.0.3, Linux x86_64). Create a fine-grained token at github.com/settings/personal-access-tokens " +
                "with read-only access to Contents, Issues, Pull requests and Metadata for the repositories you choose; give write access only if " +
                "you want Clarity to change things (each change still waits for your OK). Notifications need a classic token with the notifications scope.",
        )
        putJsonObject("values") {
            put("label", "GitHub")
            put("description", "Your GitHub repos, issues and pull requests")
            put("kind", ServerKind.GITHUB_RELEASE.wire)
            put("package", "github/github-mcp-server")
            put("version", "v2.0.3")
            put("asset", "github-mcp-server_Linux_x86_64.tar.gz")
            put("sha256", "f797c20fe02a755865d0e2d46184ab47e31bf1619b0855b8d89c531e45a651c2")
            put("command", "github-mcp-server")
            put("args", strings("stdio"))
            put("env", strings("GITHUB_TOOLSETS=context,repos,issues,pull_requests,notifications"))
            put("secretEnv", "GITHUB_PERSONAL_ACCESS_TOKEN")
            put("allowHosts", strings("api.github.com", "raw.githubusercontent.com"))
        }
    },
    buildJsonObject {
        put("title", "Canvas")
        put(
            "description",
            "Canvas LMS (canvas-mcp 1.16.0). In Canvas, open Account → Settings → New Access Token (some schools issue tokens on request). " +
                "Canvas tokens carry your whole account's access; Clarity asks before anything that changes. Set CANVAS_API_URL to your school's " +
                "address ending in /api/v1, and add that host to the hosts it may reach.",
        )
        putJsonObject("values") {
            put("label", "Canvas")
            put("description", "Your courses, assignments and due dates in Canvas")
            put("kind", ServerKind.PYPI.wire)
            put("package", "canvas-mcp")
            put("version", "1.16.0")
            put("command", "canvas-mcp-server")
            put("env", strings("CANVAS_API_URL=https://canvas.example.edu/api/v1"))
            put("secretEnv", "CANVAS_API_TOKEN")
            put("allowHosts", strings("canvas.example.edu"))
        }
    },
    // No Asana preset: the maintained self-run server (@roychri/mcp-server-asana 1.8.0) resolves
    // app.asana.com itself instead of going through HTTPS_PROXY, so it cannot work here.
))

/** The `mcp-host` config schema (contract 1). */
val hostConfigSchema: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject(SERVERS) {
            put("type", "array"); put("title", "MCP servers")
            put("description", "Servers that give Clarity tools for your other accounts. Each runs on your server, alone, and reaches only the hosts you list.")
            put("minItems", 0)
            put("x-item-title", "label")
            put("x-item-subtitle", "package")
            put("x-presets", serverPresets)
            put("items", serverItem)
        }
    }
}

