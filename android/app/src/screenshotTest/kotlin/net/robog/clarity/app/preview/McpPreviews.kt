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
    source = "GitHub release github/github-mcp-server@v1.0.0", allowHosts = listOf("api.github.com"),
    tools = githubTools, collection = "GitHub", listedAt = "2026-10-30T08:12:00Z",
)

private val canvas = McpServerStatus(
    id = "cv3n8r2w5t0a", label = "Canvas", description = "Your courses, assignments and grades", state = McpServerState.IDLE,
    source = "npm canvas-lms-mcp@1.4.2", allowHosts = listOf("canvas.instructure.com"),
    tools = listOf(
        tool("list_courses", "", readOnly = true),
        tool("list_assignments", "", readOnly = true),
        tool("get_submission", "", readOnly = true),
        tool("submit_assignment", "", readOnly = false),
    ),
)

private val asana = McpServerStatus(
    id = "as9d4f6h1j3k", label = "Asana", description = "Your team’s tasks and projects", state = McpServerState.FAILED,
    error = "Asana: version must be exact, not ^1.2", source = "npm asana-mcp@^1.2",
)

private val linear = McpServerStatus(
    id = "ln2b5c8e0g4i", label = "Linear", description = "Issues for the side project", state = McpServerState.INSTALLING,
    source = "PyPI linear-mcp==0.6.1",
)

private val notes = McpServerStatus(
    id = "nt6q1s3u7w9y", label = "Team wiki", description = "The team’s docs", state = McpServerState.DISABLED,
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

private val SCHEMA = Json.parseToJsonElement(
    """
    {"type":"object","properties":{"servers":{"type":"array","title":"Servers","minItems":0,
      "x-item-title":"label","x-item-subtitle":"package",
      "x-presets":[
        {"title":"GitHub","description":"Create a fine-grained token at github.com/settings/personal-access-tokens with read-only access to the repositories you choose.",
         "values":{"label":"GitHub","description":"Your GitHub repos, issues and PRs","kind":"github-release","package":"github/github-mcp-server","secretEnv":"GITHUB_PERSONAL_ACCESS_TOKEN","allowHosts":["api.github.com"]}},
        {"title":"Canvas","description":"In Canvas, open Account → Settings → New access token.","values":{"label":"Canvas","kind":"npm"}},
        {"title":"Asana","description":"Create a personal access token in Asana’s developer console.","values":{"label":"Asana","kind":"npm"}}],
      "items":{"type":"object","required":["label"],"properties":{
        "id":{"type":"string","readOnly":true},
        "label":{"type":"string","title":"Name","description":"Also the name of its tools and notes","examples":["GitHub"]},
        "description":{"type":"string","title":"Description","examples":["Your GitHub repos, issues and PRs"]},
        "enabled":{"type":"boolean","title":"Enabled","description":"Off keeps it saved but unused","default":true},
        "kind":{"type":"string","title":"Kind","enum":["npm","pypi","github-release","remote"],"x-enum-titles":["npm package","Python package","GitHub release binary","Remote URL"]},
        "package":{"type":"string","title":"Package"},
        "version":{"type":"string","title":"Version","description":"An exact version or tag"},
        "asset":{"type":"string","title":"Release asset"},
        "sha256":{"type":"string","title":"SHA-256"},
        "command":{"type":"string","title":"Command"},
        "args":{"type":"array","title":"Arguments","items":{"type":"string"}},
        "env":{"type":"array","title":"Environment","items":{"type":"string"},"examples":["KEY=value"]},
        "secretEnv":{"type":"string","title":"Token variable"},
        "secret":{"type":"string","title":"Token","x-secret":true},
        "secretEnv2":{"type":"string","title":"Second secret variable"},
        "secret2":{"type":"string","title":"Second secret","x-secret":true},
        "url":{"type":"string","title":"URL","format":"uri"},
        "allowHosts":{"type":"array","title":"Allowed hosts","items":{"type":"string"},"examples":["api.github.com"]}}}}}}
    """,
).jsonObject

private fun hostState(editing: Boolean = false): ConnectorSetupState {
    val form = ConfigForms.parse(SCHEMA)
    val itemForm = form.fields.single().collection!!.itemForm
    val gh = itemForm.initialValues()
        .withText("label", "GitHub").withText("description", "Your GitHub repos, issues and PRs")
        .withText("kind", "github-release").withText("package", "github/github-mcp-server").withText("version", "v1.0.0")
        .withText("secretEnv", "GITHUB_PERSONAL_ACCESS_TOKEN")
        .withList("env", listOf("GITHUB_TOOLSETS=repos,issues,pull_requests"))
        .withList("allowHosts", listOf("api.github.com"))
        .copy(storedSecrets = mapOf("secret" to kotlinx.serialization.json.JsonObject(emptyMap())))
    val values = FormValues().withItems("servers", listOf(ItemValues(github.id, gh)))
    return ConnectorSetupState(
        key = MCP_HOST, title = "MCP servers", proto = "on your server", form = form, values = values, direct = true,
        editor = if (editing) ItemEdit("servers", github.id, isNew = false, values = gh) else null,
        choosing = if (editing) null else "servers",
    )
}

@PreviewTest @Screen @Composable
fun McpAddPresets() = Frame { ConnectorSetupContent(hostState(), SetupActions(), step = null) }

@PreviewTest @Screen @Composable
fun McpServerEditor() = Frame { ConnectorSetupContent(hostState(editing = true), SetupActions(), step = null) }
