package net.robog.clarity.app.preview

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.ui.mcp.MCP_HOST
import net.robog.clarity.app.ui.mcp.McpActions
import net.robog.clarity.app.ui.mcp.McpContent
import net.robog.clarity.app.ui.mcp.McpUiState
import net.robog.clarity.app.ui.onboarding.ConnectorSetupContent
import net.robog.clarity.app.ui.onboarding.ConnectorSetupState
import net.robog.clarity.app.ui.onboarding.ItemEdit
import net.robog.clarity.app.ui.onboarding.SetupActions
import net.robog.clarity.app.ui.theme.ClarityTheme
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolStatus

/** A phone-width screen that is taller than one view, for lists that scroll. */
@Preview(name = "light", widthDp = 412, heightDp = 1400)
@Preview(name = "dark", widthDp = 412, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class TallScreen

@Composable
private fun Frame(content: @Composable () -> Unit) = ClarityTheme {
    Surface(color = MaterialTheme.colorScheme.surface) { content() }
}

private fun tool(name: String, description: String, readOnly: Boolean, destructive: Boolean = false, mode: McpToolMode? = null) = McpToolStatus(
    name = name, description = description, readOnly = readOnly, destructive = destructive,
    mode = mode ?: if (readOnly) McpToolMode.AUTO else McpToolMode.ASK, overridden = mode != null,
)

private val githubTools = listOf(
    tool("list_issues", "List issues in a repository.", readOnly = true),
    tool("get_issue", "Get the details of one issue.", readOnly = true),
    tool("search_code", "Search code across your repositories.", readOnly = true, mode = McpToolMode.OFF),
    tool("create_issue", "Open a new issue in a repository.", readOnly = false),
    tool("add_issue_comment", "Comment on an issue or pull request.", readOnly = false, mode = McpToolMode.AUTO),
    tool("merge_pull_request", "Merge a pull request.", readOnly = false, destructive = true),
)

private val github = McpServerStatus(
    id = "gh7k2m9q4x1p", label = "GitHub", description = "Your GitHub repos, issues and PRs", state = McpServerState.RUNNING,
    source = "github github/github-mcp-server@v2.0.3 github-mcp-server_Linux_x86_64.tar.gz", allowHosts = listOf("api.github.com", "raw.githubusercontent.com"),
    tools = githubTools, collection = "GitHub", listedAt = "2026-10-30T08:12:00Z",
)

private val canvas = McpServerStatus(
    id = "cv3n8r2w5t0a", label = "Canvas", description = "Your courses, assignments and due dates in Canvas", state = McpServerState.IDLE,
    source = "pypi canvas-mcp==1.16.0", allowHosts = listOf("canvas.example.edu"),
    tools = listOf(
        tool("list_courses", "", readOnly = true),
        tool("list_assignments", "", readOnly = true),
        tool("get_submission", "", readOnly = true),
        tool("submit_assignment", "", readOnly = false),
    ),
)

private val asana = McpServerStatus(
    id = "as9d4f6h1j3k", label = "Example tasks", description = "An example server with a version range", state = McpServerState.FAILED,
    error = "Example tasks: version must be exact, not ^1.2", source = "npm @example/tasks-mcp@^1.2",
)

private val linear = McpServerStatus(
    id = "ln2b5c8e0g4i", label = "Example notes", description = "An example Python server being installed", state = McpServerState.INSTALLING,
    source = "pypi example-notes-mcp==0.6.1",
)

private val notes = McpServerStatus(
    id = "nt6q1s3u7w9y", label = "Example wiki", description = "An example remote server, switched off", state = McpServerState.DISABLED,
    source = "remote https://mcp.wiki.example.com/mcp", tools = listOf(tool("search_pages", "", readOnly = true), tool("get_page", "", readOnly = true)),
)

private val suggestion = McpSuggestion(
    id = "sg1", label = "Canvas",
    why = "You mention assignments most mornings. With Canvas it could check what is due this week before your call.",
    preset = "Canvas", createdAt = "2026-10-29T08:00:00Z",
)

@PreviewTest @TallScreen @Composable
fun McpServers() = Frame {
    McpContent(McpUiState(loading = false, servers = listOf(github, canvas, asana, linear, notes)), McpActions())
}

@PreviewTest @Screen @Composable
fun McpSuggestionCard() = Frame {
    McpContent(McpUiState(loading = false, servers = listOf(github), suggestions = listOf(suggestion)), McpActions())
}

@PreviewTest @TallScreen @Composable
fun McpServerDetail() = Frame {
    McpContent(McpUiState(loading = false, servers = listOf(github, canvas), selected = github.id), McpActions())
}

@PreviewTest @Screen @Composable
fun McpNotRunning() = Frame {
    McpContent(McpUiState(loading = false, hostNotRunning = true), McpActions())
}

// --- adding and editing through the mcp-host connector's editor ---

/** The host's real schema (`hostConfigSchema` in computers/mcphost/.../ConfigSchema.kt), serialized. */
private val SCHEMA = Json.parseToJsonElement(
    """
{
    "type": "object",
    "properties": {
        "servers": {
            "type": "array",
            "title": "MCP servers",
            "description": "Servers that give Clarity tools for your other accounts. Each runs on your server, alone, and reaches only the hosts you list.",
            "minItems": 0,
            "x-item-title": "label",
            "x-item-subtitle": "package",
            "x-presets": [
                {
                    "title": "GitHub",
                    "description": "GitHub's own MCP server (release v2.0.3, Linux x86_64). Create a fine-grained token at github.com/settings/personal-access-tokens with read-only access to Contents, Issues, Pull requests and Metadata for the repositories you choose; give write access only if you want Clarity to change things (each change still waits for your OK). Notifications need a classic token with the notifications scope.",
                    "values": {
                        "label": "GitHub",
                        "description": "Your GitHub repos, issues and pull requests",
                        "kind": "github-release",
                        "package": "github/github-mcp-server",
                        "version": "v2.0.3",
                        "asset": "github-mcp-server_Linux_x86_64.tar.gz",
                        "sha256": "f797c20fe02a755865d0e2d46184ab47e31bf1619b0855b8d89c531e45a651c2",
                        "command": "github-mcp-server",
                        "args": [
                            "stdio"
                        ],
                        "env": [
                            "GITHUB_TOOLSETS=context,repos,issues,pull_requests,notifications"
                        ],
                        "secretEnv": "GITHUB_PERSONAL_ACCESS_TOKEN",
                        "allowHosts": [
                            "api.github.com",
                            "raw.githubusercontent.com"
                        ]
                    }
                },
                {
                    "title": "Canvas",
                    "description": "Canvas LMS (canvas-mcp 1.16.0). In Canvas, open Account → Settings → New Access Token (some schools issue tokens on request). Canvas tokens carry your whole account's access; Clarity asks before anything that changes. Set CANVAS_API_URL to your school's address ending in /api/v1, and add that host to the hosts it may reach.",
                    "values": {
                        "label": "Canvas",
                        "description": "Your courses, assignments and due dates in Canvas",
                        "kind": "pypi",
                        "package": "canvas-mcp",
                        "version": "1.16.0",
                        "command": "canvas-mcp-server",
                        "env": [
                            "CANVAS_API_URL=https://canvas.example.edu/api/v1"
                        ],
                        "secretEnv": "CANVAS_API_TOKEN",
                        "allowHosts": [
                            "canvas.example.edu"
                        ]
                    }
                }
            ],
            "items": {
                "type": "object",
                "required": [
                    "label",
                    "kind"
                ],
                "properties": {
                    "id": {
                        "type": "string",
                        "readOnly": true
                    },
                    "label": {
                        "type": "string",
                        "title": "Name",
                        "description": "What you call it. Clarity's tools for it start with this name.",
                        "examples": [
                            "GitHub"
                        ]
                    },
                    "description": {
                        "type": "string",
                        "title": "What it is for",
                        "description": "One line Clarity reads to know when to use it.",
                        "examples": [
                            "Your GitHub repos, issues and pull requests"
                        ]
                    },
                    "enabled": {
                        "type": "boolean",
                        "title": "On",
                        "default": true
                    },
                    "kind": {
                        "type": "string",
                        "title": "Where it comes from",
                        "enum": [
                            "npm",
                            "pypi",
                            "github-release",
                            "remote"
                        ],
                        "x-enum-titles": [
                            "npm package",
                            "Python package",
                            "GitHub release binary",
                            "Remote URL"
                        ],
                        "default": "npm"
                    },
                    "package": {
                        "type": "string",
                        "title": "Package",
                        "description": "npm or Python package name, or owner/repo for a GitHub release.",
                        "examples": [
                            "@scope/some-mcp-server"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "version": {
                        "type": "string",
                        "title": "Version",
                        "description": "An exact version or release tag. Ranges and \"latest\" are not accepted, so nothing changes on its own.",
                        "examples": [
                            "1.2.3"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "asset": {
                        "type": "string",
                        "title": "Release file",
                        "description": "GitHub release only: the file to download.",
                        "examples": [
                            "server_Linux_x86_64.tar.gz"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "github-release"
                            ]
                        }
                    },
                    "sha256": {
                        "type": "string",
                        "title": "SHA-256 of the release file",
                        "description": "GitHub release only: the file is checked against it before it is kept.",
                        "x-visible-when": {
                            "kind": [
                                "github-release"
                            ]
                        }
                    },
                    "command": {
                        "type": "string",
                        "title": "Command",
                        "description": "The command to run, when the package has more than one (for a release: the file inside the archive).",
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "args": {
                        "type": "array",
                        "title": "Arguments",
                        "description": "Passed to the command, one per entry.",
                        "items": {
                            "type": "string"
                        },
                        "examples": [
                            "stdio"
                        ]
                    },
                    "env": {
                        "type": "array",
                        "title": "Settings",
                        "description": "KEY=value entries for the server's environment. Not for secrets.",
                        "items": {
                            "type": "string"
                        },
                        "examples": [
                            "LOG_LEVEL=info"
                        ]
                    },
                    "secretEnv": {
                        "type": "string",
                        "title": "Token variable",
                        "description": "The environment variable that receives the token.",
                        "examples": [
                            "API_TOKEN"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "secret": {
                        "type": "string",
                        "title": "Token",
                        "description": "Kept sealed on your server; only this server sees it. For a remote URL it is sent as a bearer token.",
                        "format": "password",
                        "writeOnly": true,
                        "x-secret": true
                    },
                    "secretEnv2": {
                        "type": "string",
                        "title": "Second secret variable",
                        "description": "Only if the server needs a second secret.",
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "secret2": {
                        "type": "string",
                        "title": "Second secret",
                        "description": "Kept sealed like the token.",
                        "format": "password",
                        "writeOnly": true,
                        "x-secret": true,
                        "x-visible-when": {
                            "kind": [
                                "npm",
                                "pypi",
                                "github-release"
                            ]
                        }
                    },
                    "url": {
                        "type": "string",
                        "format": "uri",
                        "title": "Server URL",
                        "description": "Remote URL only: the server's streamable HTTP address (https).",
                        "examples": [
                            "https://mcp.example.com/mcp"
                        ],
                        "x-visible-when": {
                            "kind": [
                                "remote"
                            ]
                        }
                    },
                    "allowHosts": {
                        "type": "array",
                        "title": "Hosts it may reach",
                        "description": "The only internet hosts this server can connect to. A leading dot allows subdomains.",
                        "items": {
                            "type": "string"
                        },
                        "examples": [
                            "api.example.com"
                        ]
                    }
                }
            }
        }
    }
}
    """,
).jsonObject

private val itemForm = ConfigForms.parse(SCHEMA).fields.single().collection!!.itemForm

/** GitHub as its preset fills it in, with its token saved. */
private val githubItem = itemForm.initialValues()
    .withText("label", "GitHub").withText("description", "Your GitHub repos, issues and pull requests")
    .withText("kind", "github-release").withText("package", "github/github-mcp-server").withText("version", "v2.0.3")
    .withText("asset", "github-mcp-server_Linux_x86_64.tar.gz")
    .withText("sha256", "f797c20fe02a755865d0e2d46184ab47e31bf1619b0855b8d89c531e45a651c2")
    .withText("command", "github-mcp-server").withList("args", listOf("stdio"))
    .withText("secretEnv", "GITHUB_PERSONAL_ACCESS_TOKEN")
    .withList("env", listOf("GITHUB_TOOLSETS=context,repos,issues,pull_requests,notifications"))
    .withList("allowHosts", listOf("api.github.com", "raw.githubusercontent.com"))
    .copy(storedSecrets = mapOf("secret" to kotlinx.serialization.json.JsonObject(emptyMap())))

/** Canvas from PyPI, new, as its preset fills it in. */
private val canvasItem = itemForm.initialValues()
    .withText("label", "Canvas").withText("description", "Your courses, assignments and due dates in Canvas")
    .withText("kind", "pypi").withText("package", "canvas-mcp").withText("version", "1.16.0").withText("command", "canvas-mcp-server")
    .withList("env", listOf("CANVAS_API_URL=https://canvas.example.edu/api/v1")).withText("secretEnv", "CANVAS_API_TOKEN")
    .withList("allowHosts", listOf("canvas.example.edu"))

/** An example remote server: only its URL, token and hosts. */
private val remoteItem = itemForm.initialValues()
    .withText("label", "Example wiki").withText("description", "The team’s docs").withText("kind", "remote")
    .withText("url", "https://mcp.wiki.example.com/mcp").withList("allowHosts", listOf("mcp.wiki.example.com"))

private fun hostState(edit: ItemEdit? = null): ConnectorSetupState {
    val form = ConfigForms.parse(SCHEMA)
    val values = FormValues().withItems("servers", listOf(ItemValues(github.id, githubItem)))
    return ConnectorSetupState(
        key = MCP_HOST, title = "MCP servers", proto = "on your server", form = form, values = values, direct = true,
        editor = edit, choosing = if (edit == null) "servers" else null,
    )
}

@PreviewTest @Screen @Composable
fun McpAddPresets() = Frame { ConnectorSetupContent(hostState(), SetupActions(), step = null) }

@PreviewTest @Screen @Composable
fun McpServerEditor() = Frame {
    ConnectorSetupContent(hostState(ItemEdit("servers", github.id, isNew = false, values = githubItem)), SetupActions(), step = null)
}

/** A Python package, as the Canvas preset fills it in: no release file or URL fields. */
@PreviewTest @TallScreen @Composable
fun McpServerEditorPypi() = Frame {
    val preset = ConfigForms.parse(SCHEMA).fields.single().collection!!.presets.first { it.title == "Canvas" }
    ConnectorSetupContent(
        hostState(ItemEdit("servers", "cv3n8r2w5t0a", isNew = true, values = canvasItem, hint = preset.description)),
        SetupActions(), step = null,
    )
}

/** A remote URL: only the URL, the token and the hosts. */
@PreviewTest @TallScreen @Composable
fun McpServerEditorRemote() = Frame {
    ConnectorSetupContent(hostState(ItemEdit("servers", "nt6q1s3u7w9y", isNew = true, values = remoteItem)), SetupActions(), step = null)
}
