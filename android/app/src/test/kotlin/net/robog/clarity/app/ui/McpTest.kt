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
import net.robog.clarity.app.data.MCP_PENDING_WINDOW
import net.robog.clarity.app.data.PendingMcpServers
import net.robog.clarity.app.data.PendingServer
import net.robog.clarity.app.data.changedServers
import net.robog.clarity.app.data.resolvedBy
import net.robog.clarity.app.ui.mcp.MODE_ORDER
import net.robog.clarity.app.ui.mcp.mergeRows
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
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
import net.robog.clarity.app.ui.onboarding.OTHER_CHOICE
import net.robog.clarity.app.ui.mcp.unreachableHosts
import net.robog.clarity.shared.connector.PresetInputError
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
        mode = mode ?: McpToolMode.AUTO, overridden = mode != null,
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
    val fetches = mutableListOf<Boolean>()
    override suspend fun servers(fresh: Boolean): ApiResult<List<McpServerStatus>> {
        fetches += fresh
        return ApiResult.Success(servers)
    }
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
            listOf("label", "description", "enabled", "kind", "server", "package", "version", "asset", "sha256", "command", "args", "env",
                "secretEnv", "secret", "secretEnv2", "secret2", "url", "allowHosts", "preset"),
            itemForm.fields.map { it.key },
            "every property but the read-only id, in schema order",
        )
        // The preset id is kept and sent, never drawn.
        assertEquals(listOf("preset"), itemForm.fields.filter { it.hidden }.map { it.key })
        assertEquals(FieldKind.TEXT, kinds["preset"])
        assertEquals("preset", servers.collection!!.presetKey)
        assertEquals(FieldKind.BOOLEAN, kinds["enabled"])
        assertEquals(FieldKind.ENUM, kinds["kind"])
        assertEquals(FieldKind.ENUM, kinds["server"])
        assertEquals(FieldKind.STRING_LIST, kinds["args"])
        assertEquals(FieldKind.STRING_LIST, kinds["env"])
        assertEquals(FieldKind.STRING_LIST, kinds["allowHosts"])
        assertEquals(FieldKind.PASSWORD, kinds["secret"])
        assertEquals(FieldKind.PASSWORD, kinds["secret2"])
        assertEquals(FieldKind.URL, kinds["url"])
        assertEquals(FieldKind.TEXT, kinds["secretEnv"])
        assertEquals(setOf("label", "kind"), itemForm.fields.filter { it.required }.map { it.key }.toSet())
    }

    @Test fun theKindDropdownUsesItsTitles() {
        val kind = itemForm.fields.single { it.key == "kind" }
        assertEquals(listOf("npm", "pypi", "github-release", "remote", "builtin"), kind.options.map { it.value })
        assertEquals(listOf("npm package", "Python package", "GitHub release binary", "Remote URL", "Built into Clarity"), kind.options.map { it.title })
        val server = itemForm.fields.single { it.key == "server" }
        assertEquals(listOf("asana" to "Asana"), server.options.map { it.value to it.title })
    }

    @Test fun enabledStartsOnAndKindStartsAtNpm() {
        assertEquals("true", itemForm.initialValues().text("enabled"))
        assertEquals("npm", itemForm.initialValues().text("kind"))
    }

    private fun shown(kind: String) = itemForm.visibleFields(itemForm.initialValues().withText("kind", kind)).map { it.key }.toSet()

    @Test fun eachKindShowsOnlyItsFields() {
        val always = setOf("label", "description", "enabled", "kind", "secret", "allowHosts", "preset")
        val packaged = always + setOf("package", "version", "command", "args", "env", "secretEnv", "secretEnv2", "secret2")
        assertEquals(packaged, shown("npm"))
        assertEquals(packaged, shown("pypi"))
        assertEquals(packaged + setOf("asset", "sha256"), shown("github-release"))
        assertEquals(always + "url", shown("remote"))
        assertEquals(always + setOf("server", "env"), shown("builtin"))
    }

    @Test fun anUnsetKindCountsAsItsDefault() {
        assertEquals(shown("npm"), itemForm.visibleFields(itemForm.initialValues().withText("kind", "")).map { it.key }.toSet())
    }

    @Test fun hiddenFieldsAreNotCheckedOrSent() {
        // A server switched from a package to a remote URL keeps what was typed, but none of it is sent.
        val values = itemForm.initialValues().withText("label", "Wiki").withText("kind", "remote")
            .withText("package", "left-over").withText("version", "^1").withText("secret2", "old")
            .withText("url", "https://mcp.wiki.example.com/mcp")
        assertTrue(itemForm.errors(values).isEmpty())
        val out = form.toConfigValues(FormValues().withItem("servers", ItemValues("wk0000000001", values)), seal)
        val item = out["servers"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "label", "enabled", "kind", "url"), item.keys)
        assertEquals(itemForm.errors(values.withText("url", "nope")).keys, setOf("url"))
    }

    @Test fun presetsPrefillListsButNeverSecrets() {
        val gh = servers.collection!!.presets.first { it.title == "GitHub" }
        assertEquals("github-release", gh.values.text("kind"))
        assertEquals("GITHUB_PERSONAL_ACCESS_TOKEN", gh.values.text("secretEnv"))
        assertEquals("v2.0.3", gh.values.text("version"))
        assertEquals(listOf("api.github.com", "raw.githubusercontent.com"), gh.values.list("allowHosts"))
        assertEquals(listOf("GITHUB_TOOLSETS=context,repos,issues,pull_requests,notifications"), gh.values.list("env"))
        assertEquals(listOf("stdio"), gh.values.list("args"))
        assertTrue(gh.description!!.contains("github.com/settings/personal-access-tokens"))
        val canvas = servers.collection!!.presets.first { it.title == "Canvas" }
        assertEquals("pypi", canvas.values.text("kind"))
        assertEquals("canvas-mcp", canvas.values.text("package"))
        assertEquals("1.16.0", canvas.values.text("version"))
        // No placeholder address: the short form fills in the URL, the zone and the host.
        assertEquals(listOf("CANVAS_ROLE=student", "ALLOWED_WRITE_TOOLS=none"), canvas.values.list("env"))
        assertEquals(emptyList(), canvas.values.list("allowHosts"))
        assertEquals("canvas", canvas.values.text("preset"))
        assertEquals(listOf("canvas", "timezone", "token"), canvas.simple!!.inputs.map { it.id })
        assertEquals(listOf("token"), gh.simple!!.inputs.map { it.id })
        val asana = servers.collection!!.presets.first { it.title == "Asana" }
        assertEquals("builtin", asana.values.text("kind"))
        assertEquals("asana", asana.values.text("server"))
        assertEquals(listOf("app.asana.com"), asana.values.list("allowHosts"))
        assertTrue(asana.description!!.contains("Manage developer apps"))
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
        val values = itemForm.initialValues().withText("label", "Asana").withText("kind", "remote").withText("url", "not a url")
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

    @Test fun everyToolDefaultsToAuto() {
        assertEquals(McpToolMode.AUTO, defaultMode(tool("list_issues", readOnly = true)))
        assertEquals(McpToolMode.AUTO, defaultMode(tool("create_issue", readOnly = false)))
    }

    @Test fun theControlIsAskAutoAlwaysOkInThatOrder() {
        assertEquals(listOf(McpToolMode.ASK, McpToolMode.AUTO, McpToolMode.ALWAYS_OK), MODE_ORDER)
        assertEquals(McpToolMode.entries.toSet(), MODE_ORDER.toSet(), "no mode is left out of the control")
    }

    @Test fun alwaysOkForAToolThatIsNotReadOnlyIsConfirmed() {
        assertTrue(needsConfirmation(tool("create_issue", readOnly = false), McpToolMode.ALWAYS_OK))
        assertTrue(needsConfirmation(tool("delete_branch", readOnly = false, mode = McpToolMode.ASK), McpToolMode.ALWAYS_OK))
        assertFalse(needsConfirmation(tool("create_issue", readOnly = false), McpToolMode.AUTO), "Auto needs no confirm")
        assertFalse(needsConfirmation(tool("create_issue", readOnly = false), McpToolMode.ASK))
        assertFalse(needsConfirmation(tool("list_issues", readOnly = true), McpToolMode.ALWAYS_OK), "the server says it only reads")
        assertFalse(needsConfirmation(tool("create_issue", readOnly = false, mode = McpToolMode.ALWAYS_OK), McpToolMode.ALWAYS_OK), "already on Always OK")
    }

    @Test fun alwaysOkIsOfferedForEveryToolIncludingDestructiveOnes() {
        // there is nothing in a tool's status that rules a mode out: the control shows all three for every tool
        val destructive = tool("delete_branch", readOnly = false, destructive = true)
        MODE_ORDER.forEach { mode -> assertEquals(mode, destructive.withMode(mode).mode) }
    }

    @Test fun anUnknownModeFromANewerServerReadsAsAutoAndTheListStillLoads() {
        val list = net.robog.clarity.shared.ClarityJson.decodeFromString<net.robog.clarity.shared.mcp.McpServerList>(
            """{"servers":[{"id":"a","label":"A","state":"idle","tools":[{"name":"t","mode":"sometimes"},{"name":"u","mode":"always_ok"}]}]}""",
        )
        assertEquals(listOf(McpToolMode.AUTO, McpToolMode.ALWAYS_OK), list.servers.single().tools.map { it.mode })
    }

    @Test fun resettingReturnsToTheDefault() {
        val set = tool("create_issue", readOnly = false).withMode(McpToolMode.ALWAYS_OK)
        assertEquals(McpToolMode.ALWAYS_OK, set.mode)
        assertTrue(set.overridden)
        val reset = set.withMode(null)
        assertEquals(McpToolMode.AUTO, reset.mode)
        assertFalse(reset.overridden)
    }

    private val t0 = Instant.parse("2026-11-01T10:00:00Z")

    @Test fun aSavedServerNotListedYetIsAPendingRowUnderItsLabel() {
        val rows = mergeRows(listOf(github), listOf(PendingServer("sb1", "Sublime", isNew = true, savedAt = t0)))
        assertEquals(listOf("GitHub", "Sublime"), rows.map { it.server.label })
        assertEquals(listOf(false, true), rows.map { it.pending })
        assertTrue(mergeRows(listOf(github), emptyList()).none { it.pending })
    }

    @Test fun anEditedServerStaysListedAndShowsPending() {
        val rows = mergeRows(listOf(github, canvas), listOf(PendingServer(github.id, "GitHub", isNew = false, savedAt = t0)))
        assertEquals(listOf(true, false), rows.map { it.pending })
    }

    @Test fun onlyNewOrChangedServersBecomePending() {
        val stored = obj("""{"servers":[{"id":"a","label":"One","secret":{"v":1}},{"id":"b","label":"Two"}]}""")
        val saved = obj("""{"servers":[{"id":"a","label":"One","secret":{"v":1}},{"id":"b","label":"Two renamed"},{"id":"c","label":"Three"},{"id":"d"}]}""")
        val pending = changedServers(stored, saved, t0)
        assertEquals(listOf("b", "c", "d"), pending.map { it.id })
        assertEquals(listOf("Two renamed", "Three", "d"), pending.map { it.label })
        assertEquals(listOf(false, true, true), pending.map { it.isNew })
        assertEquals(listOf("x"), changedServers(null, obj("""{"servers":[{"id":"x","label":"x"}]}"""), t0).map { it.id })
        assertTrue(changedServers(stored, stored, t0).isEmpty())
    }

    @Test fun aPendingServerResolvesWhenListedWithItsToolsOrFailed() {
        val p = PendingServer("gh", "GitHub", isNew = true, savedAt = t0)
        val soon = t0 + 5.seconds
        assertFalse(p.resolvedBy(null, soon))
        assertFalse(p.resolvedBy(github.copy(id = "gh", state = McpServerState.INSTALLING), soon))
        assertFalse(p.resolvedBy(github.copy(id = "gh", tools = emptyList()), soon), "listed but no tools yet")
        assertTrue(p.resolvedBy(github.copy(id = "gh"), soon))
        assertTrue(p.resolvedBy(canvas.copy(id = "gh", label = "GitHub"), soon), "a failure shows its error instead")
        assertFalse(p.resolvedBy(github.copy(id = "gh", label = "Old name"), soon), "the saved label is not shown yet")
    }

    @Test fun anEditResolvesOnceListedAfterTheSaveOrWhenTheWindowIsOver() {
        val p = PendingServer("gh", "GitHub", isNew = false, savedAt = t0)
        val soon = t0 + 5.seconds
        assertFalse(p.resolvedBy(github.copy(id = "gh", listedAt = "2026-11-01T09:59:00Z"), soon), "listed before the save")
        assertTrue(p.resolvedBy(github.copy(id = "gh", listedAt = "2026-11-01T10:00:03Z"), soon))
        assertTrue(p.resolvedBy(github.copy(id = "gh", listedAt = "2026-11-01T09:59:00Z"), t0 + MCP_PENDING_WINDOW))
        // never a Pending that does not end for a server that is listed; one that is not listed stays
        assertFalse(p.resolvedBy(null, t0 + 10.minutes))
        assertFalse(PendingServer("sb", "Sublime", isNew = true, savedAt = t0).resolvedBy(null, t0 + 10.minutes))
        assertTrue(PendingServer("gh", "GitHub", isNew = true, savedAt = t0).resolvedBy(github.copy(id = "gh", tools = emptyList()), t0 + MCP_PENDING_WINDOW), "listed with no tools after the window")
    }

    @Test fun theHostIsFoundByIdOrKind() {
        assertTrue(isMcpHost(hostSummary(running = false)))
        assertTrue(isMcpHost(hostSummary().copy(id = ConnectorId("tools"))))
        assertFalse(isMcpHost(hostSummary(running = false).copy(id = ConnectorId("mail"))))
    }
}

class McpViewModelTest {
    private val clock = net.robog.clarity.app.testutil.FixedClock(Instant.parse("2026-11-01T10:00:00Z"))
    private val pending = PendingMcpServers()

    private fun vm(mcp: FakeMcp = FakeMcp(), host: ConnectorSummary? = hostSummary()) =
        McpViewModel(mcp, StoredConnectors(listOfNotNull(host)), pending, clock).also { it.refresh() }

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
        vm.chooseMode(github.id, "list_issues", McpToolMode.ASK)
        assertEquals(Triple(github.id, "list_issues", McpToolMode.ASK), mcp.puts.single())
        val t = vm.state.value.selectedServer!!.tools.first { it.name == "list_issues" }
        assertEquals(McpToolMode.ASK, t.mode)
        assertTrue(t.overridden)
    }

    @Test fun alwaysOkForAToolThatIsNotReadOnlyWaitsForConfirmation() = withMain {
        val mcp = FakeMcp()
        val vm = vm(mcp)
        vm.chooseMode(github.id, "create_issue", McpToolMode.ALWAYS_OK)
        assertEquals(ToolChoice(github.id, "create_issue", McpToolMode.ALWAYS_OK), vm.state.value.confirming)
        assertTrue(mcp.puts.isEmpty(), "nothing is sent before the OK")

        vm.cancelConfirm()
        assertNull(vm.state.value.confirming)
        assertTrue(mcp.puts.isEmpty())

        vm.chooseMode(github.id, "create_issue", McpToolMode.ALWAYS_OK)
        vm.confirm()
        assertEquals(Triple(github.id, "create_issue", McpToolMode.ALWAYS_OK), mcp.puts.single())
        assertEquals(McpToolMode.ALWAYS_OK, vm.state.value.servers.first().tools.first { it.name == "create_issue" }.mode)
    }

    @Test fun autoNeedsNoConfirmAndAlwaysOkOnAReadOnlyToolIsSetAtOnce() = withMain {
        val mcp = FakeMcp()
        val vm = vm(mcp)
        vm.chooseMode(github.id, "create_issue", McpToolMode.ASK)
        vm.chooseMode(github.id, "create_issue", McpToolMode.AUTO)
        assertNull(vm.state.value.confirming)
        vm.chooseMode(github.id, "list_issues", McpToolMode.ALWAYS_OK)
        assertNull(vm.state.value.confirming)
        assertEquals<List<Triple<String, String, McpToolMode?>>>(
            listOf(
                Triple(github.id, "create_issue", McpToolMode.ASK),
                Triple(github.id, "create_issue", McpToolMode.AUTO),
                Triple(github.id, "list_issues", McpToolMode.ALWAYS_OK),
            ),
            mcp.puts,
        )
    }

    @Test fun enteringTheScreenAsksTheBackendToListNow() = withMain {
        val mcp = FakeMcp()
        vm(mcp)
        assertEquals(listOf(true), mcp.fetches)
    }

    @Test fun aServerSavedFromTheEditorShowsAtOnceAsPendingThenAsItself() = withMain {
        val mcp = FakeMcp(servers = listOf(github))
        val vm = vm(mcp)
        assertEquals(listOf("GitHub"), vm.state.value.rows.map { it.server.label })
        pending.add(listOf(PendingServer("sb1", "Sublime", isNew = true, savedAt = clock.now)))
        // no waiting for the backend: the row is there with the saved label
        assertEquals(listOf("GitHub", "Sublime"), vm.state.value.rows.map { it.server.label })
        assertTrue(vm.state.value.isPending("sb1"))
        // the backend lists it with its tools: the row is the normal one
        mcp.servers = listOf(github, github.copy(id = "sb1", label = "Sublime"))
        vm.refresh()
        assertFalse(vm.state.value.isPending("sb1"))
        assertTrue(vm.state.value.rows.none { it.pending })
        assertTrue(pending.servers.value.isEmpty())
    }

    @Test fun aSavedServerThatNeverShowsStaysPendingAfterTheFastWindow() = withMain {
        val mcp = FakeMcp(servers = listOf(github))
        val vm = vm(mcp)
        pending.add(listOf(PendingServer("sb1", "Sublime", isNew = true, savedAt = clock.now)))
        clock.now += MCP_PENDING_WINDOW + 1.seconds
        vm.refresh()
        assertEquals(listOf("GitHub", "Sublime"), vm.state.value.rows.map { it.server.label })
        assertTrue(vm.state.value.isPending("sb1"), "no silent disappearance")
        // a later listing still turns it into the normal row
        mcp.servers = listOf(github, github.copy(id = "sb1", label = "Sublime"))
        vm.refresh()
        assertTrue(vm.state.value.rows.none { it.pending })
    }

    @Test fun aFailedPendingServerShowsItsError() = withMain {
        val mcp = FakeMcp(servers = listOf(github, canvas))
        val vm = vm(mcp)
        pending.add(listOf(PendingServer("cv0000000001", "Canvas", isNew = true, savedAt = clock.now)))
        vm.refresh()
        assertFalse(vm.state.value.isPending("cv0000000001"))
        assertEquals(McpServerState.FAILED, vm.state.value.rows.last().server.state)
    }

    @Test fun resetSendsNullAndShowsTheDefault() = withMain {
        val mcp = FakeMcp(servers = listOf(github.copy(tools = listOf(tool("create_issue", readOnly = false, mode = McpToolMode.ALWAYS_OK)))))
        val vm = vm(mcp)
        vm.reset(github.id, "create_issue")
        assertEquals(Triple(github.id, "create_issue", null), mcp.puts.single())
        val t = vm.state.value.servers.single().tools.single()
        assertEquals(McpToolMode.AUTO, t.mode)
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
        assertEquals("canvas-mcp", editor.values.text("package"))
        // The short form: nothing to reach until the address is given; the zone starts at Eastern.
        val simple = editor.simple!!
        assertFalse(simple.advanced)
        assertEquals("America/New_York", simple.answers["timezone"])
        assertEquals(emptyList(), editor.values.list("allowHosts"))
        assertEquals(listOf("CANVAS_ROLE=student", "ALLOWED_WRITE_TOOLS=none", "TIMEZONE=America/New_York"), editor.values.list("env"))

        vm.onPresetAnswer("canvas", "canvas.ithaca.edu")
        vm.onPresetAnswer("token", "canvas-token")
        vm.saveItem()
        val saved = repo.saved.single()
        assertEquals(listOf("GitHub", "Canvas"), saved.items("servers").map { it.values.text("label") }, "the other server is kept")
        assertEquals(setOf("secret"), saved.items("servers").first().values.storedSecrets.keys, "its token too")
        val item = saved.items("servers").last().values
        assertEquals("canvas-token", item.text("secret"))
        assertEquals("canvas", item.text("preset"))
        assertEquals(listOf("canvas.ithaca.edu"), item.list("allowHosts"))
        assertEquals(
            listOf("CANVAS_ROLE=student", "ALLOWED_WRITE_TOOLS=none", "TIMEZONE=America/New_York", "CANVAS_API_URL=https://canvas.ithaca.edu/api/v1"),
            item.list("env"),
        )
        assertTrue(vm.state.value.saved)
    }

    @Test fun aSuggestionWithoutAKnownPresetStartsEmptyWithItsName() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary())))
        vm.openById(MCP_HOST, DirectEdit(add = true, preset = "Linear", label = "Linear"))
        val editor = vm.state.value.editor!!
        assertEquals("Linear", editor.values.text("label"))
        assertEquals("", editor.values.text("package"))
        assertNull(editor.simple, "no preset, so the full form")
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

    @Test fun aHostThatIsNotRunningSaysItStartsOnItsOwn() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary(running = false))))
        vm.openById(MCP_HOST)
        assertTrue(vm.state.value.notRunning)
        val hint = vm.state.value.adminHint.orEmpty()
        assertTrue("starts with your server" in hint && "profile" !in hint, hint)
    }

    @Test fun theHostIsNotAnOnboardingStep() = withMain {
        val vm = ConnectorsStepViewModel(StoredConnectors(listOf(hostSummary())), Granted())
        vm.refresh()
        assertEquals(listOf("phone-messages"), vm.state.value.items.map { it.row.key })
    }

    // --- the short form of a preset ---

    private val storedCanvas = ConnectorConfig(
        4,
        obj(
            """{"servers":[{"id":"cv0000000001","label":"Canvas","kind":"pypi","package":"canvas-mcp","version":"1.16.0",
               "command":"canvas-mcp-server","secretEnv":"CANVAS_API_TOKEN","preset":"canvas",
               "env":["CANVAS_ROLE=student","ALLOWED_WRITE_TOOLS=none","CANVAS_API_URL=https://canvas.ithaca.edu/api/v1","TIMEZONE=Europe/Berlin"],
               "allowHosts":["canvas.ithaca.edu"],
               "secret":{"type":"sealed","algorithm":"${Sealing.ALGORITHM}","ciphertext":"ONE"}},
              {"id":"cv0000000002","label":"Old Canvas","kind":"pypi","package":"canvas-mcp","version":"1.16.0",
               "env":["CANVAS_API_URL=https://canvas.ithaca.edu/api/v1"],"allowHosts":["canvas.example.edu"]}]}""",
        ),
    )

    private fun canvasVm(repo: StoredConnectors = StoredConnectors(listOf(hostSummary()))) = vm(repo).also {
        it.openById(MCP_HOST, DirectEdit(add = true, preset = "Canvas"))
    }

    @Test fun theAddressSetsTheUrlAndTheHostAndFollowsChanges() = withMain {
        val vm = canvasVm()
        vm.onPresetAnswer("canvas", "https://canvas.ithaca.edu/courses/12")
        vm.onPresetAnswer("canvas", "canvas.cornell.edu")
        val values = vm.state.value.editor!!.values
        assertEquals(listOf("canvas.cornell.edu"), values.list("allowHosts"))
        assertEquals(1, values.list("env").count { it.startsWith("CANVAS_API_URL=") })
        assertTrue("CANVAS_API_URL=https://canvas.cornell.edu/api/v1" in values.list("env"))
    }

    @Test fun anHttpAddressIsRefusedAtOnceAndNothingIsSaved() = withMain {
        val repo = StoredConnectors(listOf(hostSummary()))
        val vm = canvasVm(repo)
        vm.onPresetAnswer("canvas", "canvas")
        assertTrue(vm.state.value.presetErrors.isEmpty(), "a half-typed address is not called wrong yet")
        vm.onPresetAnswer("canvas", "http://canvas.ithaca.edu")
        assertEquals(mapOf("canvas" to PresetInputError.NotHttps), vm.state.value.presetErrors)
        assertEquals(emptyList(), vm.state.value.editor!!.values.list("allowHosts"))
        vm.saveItem()
        assertTrue(repo.saved.isEmpty())
        assertEquals(setOf("canvas", "token"), vm.state.value.presetErrors.keys)
        assertFalse(vm.state.value.editor!!.simple!!.advanced, "the problems are in the questions")
    }

    @Test fun anotherZoneCanBeTyped() = withMain {
        val vm = canvasVm()
        vm.onPresetAnswer("timezone", "America/Phoenix")
        assertTrue("TIMEZONE=America/Phoenix" in vm.state.value.editor!!.values.list("env"))
        vm.onPresetAnswer("timezone", OTHER_CHOICE)
        assertEquals(setOf("timezone"), vm.state.value.editor!!.simple!!.others)
        // The box the other zone is typed in has its own label, not a second "Time zone".
        val zone = vm.state.value.editor!!.simple!!.form.inputs.single { it.id == "timezone" }
        assertEquals("Time zone name", zone.otherTitle)
        assertEquals("A name like Europe/Berlin or America/Toronto.", zone.otherDescription)
        // A new server from a preset is called by the preset's name.
        assertEquals("Canvas", vm.state.value.editor!!.simple!!.preset.title)
        assertFalse(vm.state.value.editor!!.values.list("env").any { it.startsWith("TIMEZONE=") })
        vm.onPresetAnswer("timezone", "Europe/Berlin")
        assertTrue("TIMEZONE=Europe/Berlin" in vm.state.value.editor!!.values.list("env"))
        // Back to a listed zone.
        vm.onPresetAnswer("timezone", "Pacific/Honolulu")
        assertEquals(emptySet(), vm.state.value.editor!!.simple!!.others)
        assertEquals(listOf("TIMEZONE=Pacific/Honolulu"), vm.state.value.editor!!.values.list("env").filter { it.startsWith("TIMEZONE=") })
    }

    @Test fun whatIsChangedUnderAdvancedIsKept() = withMain {
        val vm = canvasVm()
        vm.onPresetAnswer("canvas", "canvas.ithaca.edu")
        vm.toggleAdvanced()
        assertTrue(vm.state.value.editor!!.simple!!.advanced)
        val env = vm.state.value.editor!!.values.list("env").map { if (it.startsWith("CANVAS_API_URL=")) "CANVAS_API_URL=https://lms.example.edu/api/v1" else it }
        vm.onItemListChanged("env", env)
        assertEquals(setOf("env"), vm.state.value.editor!!.simple!!.diverged(vm.state.value.editor!!.values))
        vm.onPresetAnswer("canvas", "canvas.cornell.edu")
        val after = vm.state.value.editor!!.values
        assertTrue("CANVAS_API_URL=https://lms.example.edu/api/v1" in after.list("env"), "the hand-made URL stays")
        assertEquals(listOf("canvas.cornell.edu"), after.list("allowHosts"))
    }

    @Test fun aMissingFieldOutsideTheQuestionsOpensAdvanced() = withMain {
        val repo = StoredConnectors(listOf(hostSummary()))
        val vm = canvasVm(repo)
        vm.onPresetAnswer("canvas", "canvas.ithaca.edu")
        vm.onPresetAnswer("token", "t")
        vm.onItemValueChanged("label", "")
        vm.saveItem()
        assertTrue(repo.saved.isEmpty())
        assertTrue(vm.state.value.editor!!.simple!!.advanced)
        assertEquals(setOf("label"), vm.state.value.editorErrors.keys)
    }

    @Test fun aServerFromAPresetReopensItsQuestions() = withMain {
        val repo = StoredConnectors(listOf(hostSummary()), storedCanvas)
        val vm = vm(repo)
        vm.openById(MCP_HOST, DirectEdit(itemId = "cv0000000001"))
        val editor = vm.state.value.editor!!
        val simple = editor.simple!!
        assertEquals(mapOf("canvas" to "https://canvas.ithaca.edu/api/v1", "timezone" to "Europe/Berlin"), simple.answers)
        assertEquals(setOf("timezone"), simple.others)
        assertTrue(simple.diverged(editor.values).isEmpty())
        assertTrue(editor.hint!!.contains("Canvas"))
        // The saved token counts: saving without retyping it keeps it.
        vm.saveItem()
        val item = repo.saved.single().items("servers").first().values
        assertEquals(setOf("secret"), item.storedSecrets.keys)
        assertEquals("canvas", item.text("preset"))
    }

    @Test fun aServerSavedWithoutAPresetKeepsTheFullForm() = withMain {
        val vm = vm(StoredConnectors(listOf(hostSummary()), storedCanvas))
        vm.openById(MCP_HOST, DirectEdit(itemId = "cv0000000002"))
        val editor = vm.state.value.editor!!
        assertNull(editor.simple)
        assertEquals(listOf("canvas.example.edu"), editor.values.list("allowHosts"), "nothing is rewritten")
        assertEquals(listOf("canvas.ithaca.edu"), unreachableHosts(editor.values.list("env"), editor.values.list("allowHosts")))
    }

    @Test fun thePresetIsSentWithTheItem() = withMain {
        val canvas = ConfigForms.parse(MCP_HOST_SCHEMA).fields.single().collection!!.presets.first { it.title == "Canvas" }
        val form = ConfigForms.parse(MCP_HOST_SCHEMA)
        val item = form.fields.single().collection!!.itemForm.initialValues().overlay(canvas.values)
        val out = form.toConfigValues(FormValues().withItem("servers", ItemValues("cv0000000001", item)), seal)
        assertEquals("canvas", out["servers"]!!.jsonArray.single().jsonObject["preset"]!!.jsonPrimitive.content)
    }
}

class UnreachableHostsTest {
    @Test fun httpsHostsMustBeAllowed() {
        assertEquals(emptyList(), unreachableHosts(listOf("A=https://canvas.ithaca.edu/api/v1"), listOf("canvas.ithaca.edu")))
        assertEquals(emptyList(), unreachableHosts(listOf("A=https://canvas.ithaca.edu/api/v1"), listOf(".ithaca.edu")))
        assertEquals(emptyList(), unreachableHosts(listOf("A=https://ithaca.edu"), listOf(".ithaca.edu")))
        assertEquals(listOf("canvas.ithaca.edu"), unreachableHosts(listOf("A=https://Canvas.Ithaca.edu/api/v1", "B=x"), listOf("canvas.example.edu")))
        assertEquals(emptyList(), unreachableHosts(listOf("A=http://plain.example.com", "LOG=info"), emptyList()), "only https addresses")
    }
}
