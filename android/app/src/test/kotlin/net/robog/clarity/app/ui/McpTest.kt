package net.robog.clarity.app.ui

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.robog.clarity.app.connector.ConfigForms
import net.robog.clarity.app.connector.FieldKind
import net.robog.clarity.app.connector.FormValues
import net.robog.clarity.app.connector.ItemValues
import net.robog.clarity.app.connector.PhoneConnectorKind
import net.robog.clarity.app.connector.PhonePermissions
import net.robog.clarity.app.data.ApiError
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.app.data.ConnectorsRepository
import net.robog.clarity.app.data.McpRepository
import net.robog.clarity.app.data.SaveConnectorOutcome
import net.robog.clarity.app.testutil.MCP_HOST_SCHEMA
import net.robog.clarity.app.testutil.withMain
import net.robog.clarity.app.ui.mcp.ChipTone
import net.robog.clarity.app.ui.mcp.MCP_HOST
import net.robog.clarity.app.ui.mcp.McpViewModel
import net.robog.clarity.app.ui.mcp.ToolChoice
import net.robog.clarity.app.ui.mcp.defaultMode
import net.robog.clarity.app.ui.mcp.isMcpHost
import net.robog.clarity.app.ui.mcp.needsConfirmation
import net.robog.clarity.app.ui.mcp.tone
import net.robog.clarity.app.ui.mcp.toolCounts
import net.robog.clarity.app.ui.mcp.toolGroups
import net.robog.clarity.app.ui.mcp.withMode
import net.robog.clarity.app.ui.onboarding.ConnectorSetupViewModel
import net.robog.clarity.app.ui.onboarding.ConnectorsStepViewModel
import net.robog.clarity.app.ui.onboarding.DirectEdit
import net.robog.clarity.app.ui.onboarding.ItemNoun
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.connector.ConnectorConfig
import net.robog.clarity.shared.connector.ConnectorManifest
import net.robog.clarity.shared.connector.ConnectorSummary
import net.robog.clarity.shared.connector.ConnectorTestResult
import net.robog.clarity.shared.connector.SealedValue
import net.robog.clarity.shared.connector.Sealing
import net.robog.clarity.shared.mcp.McpServerState
import net.robog.clarity.shared.mcp.McpServerStatus
import net.robog.clarity.shared.mcp.McpSuggestion
import net.robog.clarity.shared.mcp.McpToolMode
import net.robog.clarity.shared.mcp.McpToolStatus

private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

private fun hostSummary(running: Boolean = true) = ConnectorSummary(
    ConnectorId(MCP_HOST),
    if (running) ConnectorManifest(kind = MCP_HOST, displayName = "MCP servers", version = "1", produces = emptySet(), configSchema = MCP_HOST_SCHEMA, sealingKey = "k") else null,
    grants = emptyMap(),
    lastSeenAt = null,
)

private fun tool(name: String, readOnly: Boolean, destructive: Boolean = !readOnly, mode: McpToolMode? = null, title: String? = null) =
    McpToolStatus(
        name = name, title = title, description = "$name does its thing", readOnly = readOnly, destructive = destructive,
        mode = mode ?: if (readOnly) McpToolMode.AUTO else McpToolMode.ASK, overridden = mode != null,
    )

private val github = McpServerStatus(
    id = "gh0000000001", label = "GitHub", description = "Your GitHub repos, issues and PRs", state = McpServerState.IDLE,
    source = "github-release github/github-mcp-server@v1.0.0", allowHosts = listOf("api.github.com"),
    tools = listOf(
        tool("list_issues", readOnly = true),
        tool("create_issue", readOnly = false, destructive = false),
        tool("get_issue", readOnly = true),
        tool("delete_branch", readOnly = false),
    ),
)

private val canvas = McpServerStatus(id = "cv0000000001", label = "Canvas", state = McpServerState.FAILED, error = "version must be exact, not ^1.2")

private class StoredConnectors(var list: List<ConnectorSummary>, var stored: ConnectorConfig? = null) : ConnectorsRepository {
    val saved = mutableListOf<FormValues>()
    override suspend fun list(): ApiResult<List<ConnectorSummary>> = ApiResult.Success(list)
    override suspend fun storedConfig(id: ConnectorId) = stored
    override suspend fun save(connector: ConnectorSummary, values: FormValues): SaveConnectorOutcome {
        saved += values
        return SaveConnectorOutcome.Saved
    }
    override suspend fun test(id: ConnectorId) = ApiResult.Success(ConnectorTestResult(true))
}

/** A backend that keeps tool modes like the real one, and can refuse. */
private class FakeMcp(var servers: List<McpServerStatus> = listOf(github, canvas), var refuse: ApiError? = null) : McpRepository {
    val puts = mutableListOf<Triple<String, String, McpToolMode?>>()
    val dismissed = mutableListOf<String>()
    var suggestions = listOf(McpSuggestion("s1", "Asana", "Your team plans in Asana; Clarity could see what is due.", preset = "Asana", createdAt = "2026-10-29T08:00:00Z"))
    override suspend fun servers() = ApiResult.Success(servers)
    override suspend fun setToolMode(serverId: String, tool: String, mode: McpToolMode?): ApiResult<Unit> {
        puts += Triple(serverId, tool, mode)
        refuse?.let { return ApiResult.Failure(it) }
        return ApiResult.Success(Unit)
    }
    override suspend fun suggestions() = ApiResult.Success(suggestions)
    override suspend fun dismiss(suggestionId: String): ApiResult<Unit> {
        dismissed += suggestionId
        refuse?.let { return ApiResult.Failure(it) }
        suggestions = suggestions.filterNot { it.id == suggestionId }
        return ApiResult.Success(Unit)
    }
}

private class Granted : PhonePermissions {
    override fun isGranted(kind: PhoneConnectorKind) = true
}

private val seal: (String, String) -> SealedValue = { field, _ -> SealedValue(Sealing.ALGORITHM, "SEALED[$field]") }

/** The existing form builder renders the `mcp-host` schema of contract 1 without loss. */
class McpHostSchemaTest {
    private val form = ConfigForms.parse(MCP_HOST_SCHEMA)
    private val servers = form.fields.single()
    private val itemForm = servers.collection!!.itemForm

    @Test fun serversAreACollectionWithTitleSubtitleAndNoMinimum() {
        assertEquals("servers", servers.key)
        assertEquals(FieldKind.COLLECTION, servers.kind)
        val spec = servers.collection!!
        assertEquals("label", spec.titleKey)
        assertEquals("package", spec.subtitleKey)
        assertEquals(0, spec.minItems)
        assertTrue(form.errors(FormValues()).isEmpty(), "no servers is a valid config")
    }

    @Test fun everyItemPropertyRendersInOrderWithTheRightKind() {
        val kinds = itemForm.fields.associate { it.key to it.kind }
        assertEquals(
            listOf("label", "description", "enabled", "kind", "package", "version", "asset", "sha256", "command", "args", "env",
                "secretEnv", "secret", "secretEnv2", "secret2", "url", "allowHosts"),
            itemForm.fields.map { it.key },
            "every property but the read-only id, in schema order",
        )
        assertEquals(FieldKind.BOOLEAN, kinds["enabled"])
        assertEquals(FieldKind.ENUM, kinds["kind"])
        assertEquals(FieldKind.STRING_LIST, kinds["args"])
        assertEquals(FieldKind.STRING_LIST, kinds["env"])
        assertEquals(FieldKind.STRING_LIST, kinds["allowHosts"])
        assertEquals(FieldKind.PASSWORD, kinds["secret"])
        assertEquals(FieldKind.PASSWORD, kinds["secret2"])
        assertEquals(FieldKind.URL, kinds["url"])
        assertEquals(FieldKind.TEXT, kinds["secretEnv"])
        assertTrue(itemForm.fields.single { it.key == "label" }.required)
    }

    @Test fun theKindDropdownUsesItsTitles() {
        val kind = itemForm.fields.single { it.key == "kind" }
        assertEquals(listOf("npm", "pypi", "github-release", "remote"), kind.options.map { it.value })
        assertEquals(listOf("npm package", "Python package", "GitHub release binary", "Remote URL"), kind.options.map { it.title })
    }

    @Test fun enabledStartsOn() {
        assertEquals("true", itemForm.initialValues().text("enabled"))
    }

    @Test fun presetsPrefillListsButNeverSecrets() {
        val gh = servers.collection!!.presets.first { it.title == "GitHub" }
        assertEquals("github-release", gh.values.text("kind"))
        assertEquals("GITHUB_PERSONAL_ACCESS_TOKEN", gh.values.text("secretEnv"))
        assertEquals(listOf("api.github.com"), gh.values.list("allowHosts"))
        assertEquals(listOf("GITHUB_TOOLSETS=repos,issues,pull_requests,notifications"), gh.values.list("env"))
        assertEquals(listOf("stdio"), gh.values.list("args"))
        assertTrue(gh.description!!.contains("github.com/settings/tokens"))
    }

    @Test fun twoSecretsPerItemAreSealedUnderTheirOwnItemFields() {
        val values = itemForm.initialValues().withText("label", "Asana").withText("kind", "npm")
            .withText("secret", "token-one").withText("secret2", "token-two")
            .withList("allowHosts", listOf("app.asana.com"))
        val out = form.toConfigValues(FormValues().withItem("servers", ItemValues("as0000000001", values)), seal)
        val item = out["servers"]!!.jsonArray.single().jsonObject
        assertEquals("SEALED[servers/as0000000001/secret]", item["secret"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
        assertEquals("SEALED[servers/as0000000001/secret2]", item["secret2"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
        assertEquals(listOf("app.asana.com"), item["allowHosts"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, item["enabled"]!!.jsonPrimitive.content.toBooleanStrict())
        assertFalse("token-one" in out.toString() || "token-two" in out.toString())
    }

    @Test fun storedSecretsAreKeptWhenOnlyOneIsRetyped() {
        val stored = obj(
            """{"servers":[{"id":"gh0000000001","label":"GitHub","kind":"github-release","args":["stdio"],
               "secret":{"type":"sealed","algorithm":"${Sealing.ALGORITHM}","ciphertext":"ONE"},
               "secret2":{"type":"sealed","algorithm":"${Sealing.ALGORITHM}","ciphertext":"TWO"}}]}""",
        )
        val values = form.valuesFromStored(stored)
        val item = values.items("servers").single()
        assertEquals(setOf("secret", "secret2"), item.values.storedSecrets.keys)
        val edited = values.withItem("servers", item.copy(values = item.values.withText("secret2", "new")))
        val out = form.toConfigValues(edited, seal)["servers"]!!.jsonArray.single().jsonObject
        assertEquals("ONE", out["secret"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
        assertEquals("SEALED[servers/gh0000000001/secret2]", out["secret2"]!!.jsonObject["ciphertext"]!!.jsonPrimitive.content)
        assertEquals(listOf("stdio"), out["args"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun aRemoteUrlIsChecked() {
        val values = itemForm.initialValues().withText("label", "Asana").withText("url", "not a url")
        assertTrue(itemForm.errors(values).containsKey("url"))
        assertTrue(itemForm.errors(values.withText("url", "https://mcp.asana.example/sse")).isEmpty())
    }
}

class McpPresentationTest {
    @Test fun eachStateHasItsOwnTone() {
        assertEquals(
            listOf(ChipTone.Progress, ChipTone.Quiet, ChipTone.Active, ChipTone.Problem, ChipTone.Off),
            McpServerState.entries.map { it.tone() },
        )
    }

    @Test fun countsSplitReadsFromChanges() {
        val counts = github.toolCounts()
        assertEquals(4, counts.total)
        assertEquals(2, counts.reads)
        assertEquals(2, counts.changes)
        assertEquals(0, canvas.toolCounts().total)
    }

    @Test fun groupsAreReadsAndChangesSortedByName() {
        val groups = github.toolGroups()
        assertEquals(listOf("get_issue", "list_issues"), groups.reads.map { it.name })
        assertEquals(listOf("create_issue", "delete_branch"), groups.changes.map { it.name })
    }

    @Test fun onlyReadOnlyToolsDefaultToAuto() {
        assertEquals(McpToolMode.AUTO, defaultMode(tool("list_issues", readOnly = true)))
        assertEquals(McpToolMode.ASK, defaultMode(tool("create_issue", readOnly = false)))
    }

    @Test fun autoForAToolThatChangesThingsIsConfirmed() {
        assertTrue(needsConfirmation(tool("create_issue", readOnly = false), McpToolMode.AUTO))
        assertFalse(needsConfirmation(tool("create_issue", readOnly = false), McpToolMode.OFF))
        assertFalse(needsConfirmation(tool("list_issues", readOnly = true, mode = McpToolMode.OFF), McpToolMode.AUTO))
        assertFalse(needsConfirmation(tool("create_issue", readOnly = false, mode = McpToolMode.AUTO), McpToolMode.AUTO), "already on Auto")
    }

    @Test fun resettingReturnsToTheDefault() {
        val set = tool("create_issue", readOnly = false).withMode(McpToolMode.OFF)
        assertEquals(McpToolMode.OFF, set.mode)
        assertTrue(set.overridden)
        val reset = set.withMode(null)
        assertEquals(McpToolMode.ASK, reset.mode)
        assertFalse(reset.overridden)
    }

    @Test fun theHostIsFoundByIdOrKind() {
        assertTrue(isMcpHost(hostSummary(running = false)))
        assertTrue(isMcpHost(hostSummary().copy(id = ConnectorId("tools"))))
        assertFalse(isMcpHost(hostSummary(running = false).copy(id = ConnectorId("mail"))))
    }
}

class McpViewModelTest {
    private fun vm(mcp: FakeMcp = FakeMcp(), host: ConnectorSummary? = hostSummary()) =
        McpViewModel(mcp, StoredConnectors(listOfNotNull(host))).also { it.refresh() }

    @Test fun loadsServersAndSuggestions() = withMain {
        val s = vm().state.value
        assertFalse(s.loading)
        assertFalse(s.hostNotRunning)
        assertEquals(listOf("GitHub", "Canvas"), s.servers.map { it.label })
        assertEquals(listOf("Asana"), s.suggestions.map { it.label })
    }

    @Test fun aHostThatIsNotRunningOrMissingShowsTheNotRunningState() = withMain {
        assertTrue(vm(host = hostSummary(running = false)).state.value.hostNotRunning)
        assertTrue(vm(host = null).state.value.hostNotRunning)
    }

    @Test fun choosingAModeSendsItAtOnce() = withMain {
        val mcp = FakeMcp()
        val vm = vm(mcp)
        vm.open(github.id)
        vm.chooseMode(github.id, "list_issues", McpToolMode.OFF)
        assertEquals(Triple(github.id, "list_issues", McpToolMode.OFF), mcp.puts.single())
        val t = vm.state.value.selectedServer!!.tools.first { it.name == "list_issues" }
        assertEquals(McpToolMode.OFF, t.mode)
        assertTrue(t.overridden)
    }

    @Test fun autoForAToolThatChangesThingsWaitsForConfirmation() = withMain {
        val mcp = FakeMcp()
        val vm = vm(mcp)
        vm.chooseMode(github.id, "create_issue", McpToolMode.AUTO)
        assertEquals(ToolChoice(github.id, "create_issue", McpToolMode.AUTO), vm.state.value.confirming)
        assertTrue(mcp.puts.isEmpty(), "nothing is sent before the OK")

        vm.cancelConfirm()
        assertNull(vm.state.value.confirming)
        assertTrue(mcp.puts.isEmpty())

        vm.chooseMode(github.id, "create_issue", McpToolMode.AUTO)
        vm.confirm()
        assertEquals(Triple(github.id, "create_issue", McpToolMode.AUTO), mcp.puts.single())
        assertEquals(McpToolMode.AUTO, vm.state.value.servers.first().tools.first { it.name == "create_issue" }.mode)
    }

    @Test fun resetSendsNullAndShowsTheDefault() = withMain {
        val mcp = FakeMcp(servers = listOf(github.copy(tools = listOf(tool("create_issue", readOnly = false, mode = McpToolMode.OFF)))))
        val vm = vm(mcp)
        vm.reset(github.id, "create_issue")
        assertEquals(Triple(github.id, "create_issue", null), mcp.puts.single())
        val t = vm.state.value.servers.single().tools.single()
        assertEquals(McpToolMode.ASK, t.mode)
        assertFalse(t.overridden)
    }

    @Test fun aRefusedChangeIsPutBack() = withMain {
        val mcp = FakeMcp(refuse = ApiError.Unreachable(null))
        val vm = vm(mcp)
        vm.chooseMode(github.id, "list_issues", McpToolMode.ASK)
        val t = vm.state.value.servers.first().tools.first { it.name == "list_issues" }
        assertEquals(McpToolMode.AUTO, t.mode)
        assertFalse(t.overridden)
        assertEquals(ApiError.Unreachable(null).sentence(), vm.state.value.changeError)
    }

    @Test fun dismissingASuggestionRemovesIt() = withMain {
        val mcp = FakeMcp()
        val vm = vm(mcp)
        vm.dismiss("s1")
        assertEquals(listOf("s1"), mcp.dismissed)
        assertTrue(vm.state.value.suggestions.isEmpty())
    }

    @Test fun aRefusedDismissKeepsTheSuggestion() = withMain {
        val vm = vm(FakeMcp(refuse = ApiError.Http(500, null)))
        vm.dismiss("s1")
        assertEquals(listOf("s1"), vm.state.value.suggestions.map { it.id })
        assertTrue(vm.state.value.changeError != null)
    }

    @Test fun installingIsNoticed() = withMain {
        val vm = vm(FakeMcp(servers = listOf(github.copy(state = McpServerState.INSTALLING))))
        assertTrue(vm.state.value.anyInstalling)
    }
}

/** Adding, editing and removing one server through the `mcp-host` connector's editor. */
class McpServerEditorTest {
    private val storedGithub = ConnectorConfig(
        3,
        obj(
            """{"servers":[{"id":"gh0000000001","label":"GitHub","kind":"github-release","package":"github/github-mcp-server",
               "secret":{"type":"sealed","algorithm":"${Sealing.ALGORITHM}","ciphertext":"ONE"}}]}""",
        ),
    )

    private fun vm(repo: StoredConnectors) = ConnectorSetupViewModel(repo, Granted()).also { it.useRandom(Random(7)) }

    @Test fun itsItemsAreServers() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary())))
        vm.openById(MCP_HOST)
        assertEquals(ItemNoun.Server, vm.state.value.noun)
    }

    @Test fun addingStartsAtThePresets() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary())))
        vm.openById(MCP_HOST, DirectEdit(add = true))
        assertTrue(vm.state.value.direct)
        assertEquals("servers", vm.state.value.choosing)
    }

    @Test fun aSuggestionsPresetOpensItsFormAndSavingTheItemSavesTheConfig() = withMain {
        val repo = StoredConnectors(listOf(hostSummary()), storedGithub)
        val vm = vm(repo)
        vm.openById(MCP_HOST, DirectEdit(add = true, preset = "canvas", label = "Canvas"))
        val editor = vm.state.value.editor!!
        assertTrue(editor.isNew)
        assertEquals("canvas-mcp-example", editor.values.text("package"))
        assertEquals(listOf("canvas.example.edu"), editor.values.list("allowHosts"))

        vm.onItemValueChanged("secret", "canvas-token")
        vm.saveItem()
        val saved = repo.saved.single()
        assertEquals(listOf("GitHub", "Canvas"), saved.items("servers").map { it.values.text("label") }, "the other server is kept")
        assertEquals(setOf("secret"), saved.items("servers").first().values.storedSecrets.keys, "its token too")
        assertTrue(vm.state.value.saved)
    }

    @Test fun aSuggestionWithoutAKnownPresetStartsEmptyWithItsName() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary())))
        vm.openById(MCP_HOST, DirectEdit(add = true, preset = "Asana", label = "Asana"))
        val editor = vm.state.value.editor!!
        assertEquals("Asana", editor.values.text("label"))
        assertEquals("", editor.values.text("package"))
    }

    @Test fun editingOpensThatServer() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary()), storedGithub))
        vm.openById(MCP_HOST, DirectEdit(itemId = "gh0000000001"))
        val editor = vm.state.value.editor!!
        assertFalse(editor.isNew)
        assertEquals("GitHub", editor.values.text("label"))
        assertTrue("secret" in editor.values.storedSecrets)
    }

    @Test fun anIncompleteServerIsNotSaved() = withMain {
        val repo = StoredConnectors(listOf(hostSummary()))
        val vm = vm(repo)
        vm.openById(MCP_HOST, DirectEdit(add = true, label = "x"))
        vm.onItemValueChanged("label", "")
        vm.saveItem()
        assertTrue(repo.saved.isEmpty())
        assertTrue(vm.state.value.editor!!.showErrors)
        // Fixed after the errors showed: now it saves.
        vm.onItemValueChanged("label", "Asana")
        vm.saveItem()
        assertEquals(1, repo.saved.size)
    }

    @Test fun removingAServerSavesAtOnce() = withMain {
        val repo = StoredConnectors(listOf(hostSummary()), storedGithub)
        val vm = vm(repo)
        vm.openById(MCP_HOST, DirectEdit(itemId = "gh0000000001"))
        vm.removeItem("servers", "gh0000000001")
        assertTrue(repo.saved.single().items("servers").isEmpty())
        assertTrue(vm.state.value.saved)
    }

    @Test fun theHostIsNotAnOnboardingStep() = withMain {
        val vm = ConnectorsStepViewModel(StoredConnectors(listOf(hostSummary())), Granted())
        vm.refresh()
        assertEquals(listOf("phone-messages"), vm.state.value.items.map { it.row.key })
    }
}
