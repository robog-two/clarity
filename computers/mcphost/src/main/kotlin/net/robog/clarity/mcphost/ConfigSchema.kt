package net.robog.clarity.mcphost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.robog.clarity.shared.connector.PresetChoice
import net.robog.clarity.shared.connector.PresetForm
import net.robog.clarity.shared.connector.PresetInput
import net.robog.clarity.shared.connector.PresetInputType

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

/** Kinds whose program is downloaded: they name a package, version and command. */
private val downloaded = listOf(ServerKind.NPM, ServerKind.PYPI, ServerKind.GITHUB_RELEASE)

/** Kinds that run a program here (everything but a remote URL): they take settings. */
private val runHere = downloaded + ServerKind.BUILTIN

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

private fun JsonObjectBuilderScope.list(name: String, title: String, description: String, example: String? = null, visibleWhen: List<ServerKind>? = null) =
    putJsonObject(name) {
        put("type", "array"); put("title", title); put("description", description)
        putJsonObject("items") { put("type", "string") }
        example?.let { put("examples", strings(it)) }
        visibleWhenKind(visibleWhen)
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
        putJsonObject("server") {
            put("type", "string"); put("title", "Server")
            put("description", "Built into Clarity: which one. It comes with Clarity and updates with it.")
            put("enum", JsonArray(BUILTIN_SERVERS.map { JsonPrimitive(it.name) }))
            put("x-enum-titles", JsonArray(BUILTIN_SERVERS.map { JsonPrimitive(it.title) }))
            put("default", BUILTIN_SERVERS.first().name)
            visibleWhenKind(listOf(ServerKind.BUILTIN))
        }
        text("package", "Package", "npm or Python package name, or owner/repo for a GitHub release.", "@scope/some-mcp-server", downloaded)
        text("version", "Version", "An exact version or release tag. Ranges and \"latest\" are not accepted, so nothing changes on its own.", "1.2.3", downloaded)
        text("asset", "Release file", "GitHub release only: the file to download.", "server_Linux_x86_64.tar.gz", listOf(ServerKind.GITHUB_RELEASE))
        text("sha256", "SHA-256 of the release file", "GitHub release only: the file is checked against it before it is kept.", visibleWhen = listOf(ServerKind.GITHUB_RELEASE))
        text("command", "Command", "The command to run, when the package has more than one (for a release: the file inside the archive).", visibleWhen = downloaded)
        list("args", "Arguments", "Passed to the command, one per entry.", "stdio", downloaded)
        list("env", "Settings", "KEY=value entries for the server's environment. Not for secrets.", "LOG_LEVEL=info", runHere)
        text("secretEnv", "Token variable", "The environment variable that receives the token.", "API_TOKEN", downloaded)
        secret("secret", "Token", "Kept sealed on your server; only this server sees it. For a remote URL it is sent as a bearer token.")
        text("secretEnv2", "Second secret variable", "Only if the server needs a second secret.", visibleWhen = downloaded)
        secret("secret2", "Second secret", "Kept sealed like the token.", downloaded)
        putJsonObject("url") {
            put("type", "string"); put("format", "uri"); put("title", "Server URL")
            put("description", "Remote URL only: the server's streamable HTTP address (https).")
            put("examples", strings("https://mcp.example.com/mcp"))
            visibleWhenKind(listOf(ServerKind.REMOTE))
        }
        list("allowHosts", "Hosts it may reach", "The only internet hosts this server can connect to. A leading dot allows subdomains.", "api.example.com")
        // Set by the app from the chosen preset and kept as it is; the host only stores it.
        putJsonObject(PRESET_KEY) {
            put("type", "string"); put("readOnly", true); put("title", "Preset")
            put("description", "The preset it was set up from, so the app can reopen its short form.")
        }
    }
}

/** The item property that records which preset a server was set up from (`x-preset-key`). */
const val PRESET_KEY = "preset"

private val token = PresetInput("token", "Access token", PresetInputType.SECRET, required = true, property = "secret")

/** The US zones, then anything else as an IANA name (`Europe/Berlin`). */
private val timezone = PresetInput(
    id = "timezone", title = "Time zone", type = PresetInputType.CHOICE, required = true, default = "America/New_York",
    description = "So due dates and times are shown right.",
    choices = listOf(
        PresetChoice("America/New_York", "Eastern"), PresetChoice("America/Chicago", "Central"),
        PresetChoice("America/Denver", "Mountain"), PresetChoice("America/Phoenix", "Arizona"),
        PresetChoice("America/Los_Angeles", "Pacific"), PresetChoice("America/Anchorage", "Alaska"),
        PresetChoice("Pacific/Honolulu", "Hawaii"),
    ),
    other = true, pattern = "[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+){0,2}",
    otherTitle = "Time zone name", otherDescription = "A name like Europe/Berlin or America/Toronto.",
)

private fun JsonObjectBuilderScope.form(form: PresetForm) = form.toJson().forEach { (k, v) -> put(k, v) }

/**
 * Presets: only packages checked against their registry at exactly this version and seen working
 * through the egress proxy (`SMOKE_PRESETS=1 computers/mcphost/smoke.sh`), with what the person needs
 * to create the token. GitHub: the release's own checksums file and the GitHub API's asset digest
 * agree on the sha256. Canvas: PyPI's JSON API, a pure-Python wheel. Asana: built in (`computers/mcphost/builtin`),
 * since the maintained self-run server (@roychri/mcp-server-asana) resolves app.asana.com itself instead of
 * going through HTTPS_PROXY, and Asana's hosted MCP needs OAuth.
 *
 * Each has an `id` (stored in the item's [PRESET_KEY]) and a short form (`inputs`, `derive`): the app
 * asks only those questions and fills in `values` plus what the answers derive. `values` therefore
 * never holds a placeholder the person would have to replace by hand.
 */
val serverPresets: JsonArray = JsonArray(listOf(
    buildJsonObject {
        put("id", "github")
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
        form(PresetForm(listOf(token.copy(title = "Personal access token"))))
    },
    buildJsonObject {
        put("id", "canvas")
        put("title", "Canvas")
        put(
            "description",
            "Canvas LMS for a student (canvas-mcp 1.16.0), read only: it tracks your courses, assignments, due dates, grades and " +
                "announcements and never submits, posts or changes anything (it runs as a student with every tool that writes removed). " +
                "In Canvas, open Account → Settings → New Access Token (some schools issue tokens on request); Canvas tokens carry your " +
                "whole account's access.",
        )
        putJsonObject("values") {
            put("label", "Canvas")
            put("description", "Your courses, assignments and due dates in Canvas")
            put("kind", ServerKind.PYPI.wire)
            put("package", "canvas-mcp")
            put("version", "1.16.0")
            put("command", "canvas-mcp-server")
            // On stdio an unset ALLOWED_WRITE_TOOLS means every write tool, so it is set explicitly.
            put("env", strings("CANVAS_ROLE=student", "ALLOWED_WRITE_TOOLS=none"))
            put("secretEnv", "CANVAS_API_TOKEN")
        }
        form(
            PresetForm(
                inputs = listOf(
                    PresetInput(
                        id = "canvas", title = "Your school's Canvas address", type = PresetInputType.URL, required = true,
                        description = "The address you open Canvas at.", placeholder = "canvas.yourschool.edu", path = "/api/v1",
                    ),
                    timezone,
                    token,
                ),
                // The address sets both the API URL and the one host the server may reach, so they cannot disagree.
                lists = mapOf("env" to listOf("CANVAS_API_URL={canvas}", "TIMEZONE={timezone}"), "allowHosts" to listOf("{canvas.host}")),
            ),
        )
    },
    buildJsonObject {
        put("id", "asana")
        put("title", "Asana")
        put(
            "description",
            "Clarity's own Asana server, which comes with Clarity. In Asana, click your profile photo → My settings → Apps → " +
                "Manage developer apps (or open app.asana.com/0/my-apps), choose \"Create new token\", and paste it here. Asana tokens " +
                "carry your whole account's access. Reading runs on its own; new tasks, edits and comments wait for your OK by default " +
                "(you can change that for each tool). " +
                "To use another workspace than your first, add ASANA_WORKSPACE=<workspace gid> to Settings under Advanced settings.",
        )
        putJsonObject("values") {
            put("label", "Asana")
            put("description", "Your Asana tasks, projects and comments")
            put("kind", ServerKind.BUILTIN.wire)
            put("server", "asana")
            put("secretEnv", "ASANA_ACCESS_TOKEN")
            put("allowHosts", strings("app.asana.com"))
        }
        form(PresetForm(listOf(token.copy(title = "Personal access token"))))
    },
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
            put("x-preset-key", PRESET_KEY)
            put("x-presets", serverPresets)
            put("items", serverItem)
        }
    }
}

