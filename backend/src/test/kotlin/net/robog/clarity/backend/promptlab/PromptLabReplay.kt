package net.robog.clarity.backend.promptlab

import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.HomeClock
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.leadSystemBlocks
import net.robog.clarity.backend.agent.roleRequest
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.backend.config.RoleConfig
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Harness
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.InMemoryRunJournal
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.OpenRouterModelGateway
import net.robog.clarity.harness.OpenRouterRouting
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.Usage
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.connector.ConnectorAccount
import net.robog.clarity.shared.governance.Constitution
import org.jetbrains.exposed.v1.jdbc.Database

/** The model the live lead runs on (the `model_role_override` row of the lead); `--model` replaces it. */
const val LIVE_LEAD_MODEL = "deepseek/deepseek-v4.1-flash-1017"

/** Answers the first request and stops: only captures what the lead's harness would send. */
private class Capture : ModelGateway {
    var request: ModelRequest? = null
    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        if (this.request == null) this.request = request
        return ModelResponse(listOf(Block.Text("captured")), StopReason.END_TURN, Usage.ZERO)
    }
}

/**
 * Builds the lead's first request for a scenario from the code as it is now: the real system prompt and constitution block, the
 * real lead toolbox (every tool, sent as the lead sends them), the scenario's journal context, and the harness' own conversion.
 */
class LeadRequests(role: RoleConfig) {
    private val roles = AgentRoles().let { it.copy(lead = role) }
    val role: RoleConfig get() = roles.lead
    // Only tool definitions are built, so the database points nowhere.
    private val rig = LeadRig(Database.connect("jdbc:postgresql://127.0.0.1:9/none", driver = "org.postgresql.Driver"), apiKey = "unused", roles = roles)
    val tools: List<Tool> = rig.toolbox.leadTools(RunScope("promptlab", isLead = true)).tools

    suspend fun build(input: ScenarioInput): ModelRequest {
        val constitution = input.constitution?.let { ClarityJson.decodeFromJsonElement(Constitution.serializer(), it) }
        val home = HomeClock(TimeZone.of(input.homeZone), Instant.parse(input.timestamp))
        val system = leadSystemBlocks(constitution, home, input.mailAccounts.map { ConnectorAccount(it.label, it.address) })
        val journal = InMemoryRunJournal()
        input.messages.forEach { journal.append("lead", it) }
        val capture = Capture()
        Harness(capture, journal).resume("lead", roleRequest(roles.lead, "lead", system, emptyList(), tools, 40, null)).collect()
        return capture.request ?: error("the harness sent nothing")
    }
}

/**
 * `:backend:promptlabReplay`: replays the extracted scenarios against the current prompts and calls the real model.
 *
 *     --only 3,7,12   a subset
 *     --runs N        answers that sent per scenario (default 3); samples again until reached, at most 3 x N calls
 *     --model ID      another model than the live lead's
 *     --dry           build the requests and print their sizes, call nothing
 */
object PromptLabReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        fun value(flag: String) = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val only = value("--only")?.split(',')?.map { it.trim().toInt() }?.toSet()
        val runs = value("--runs")?.toInt() ?: 3
        val model = value("--model") ?: LIVE_LEAD_MODEL
        val dry = "--dry" in args

        val scenarios = scenarioDirs().map(::readScenario).filter { only == null || it.n in only }
        require(scenarios.isNotEmpty()) { "no scenarios under ${LAB_HOME.resolve("scenarios")}: run promptlabExtract first" }
        val answers = LAB_HOME.resolve("scenarios.md").takeIf { it.exists() }?.let { parseAnswers(it.readText()) }.orEmpty()
        val requests = LeadRequests(AgentRoles().lead.copy(model = model))
        println("model $model, effort ${requests.role.effort}, ${requests.tools.size} tools, ${scenarios.size} scenarios x $runs")

        val built = runBlocking { scenarios.associate { it.n to requests.build(it) } }
        val stats = scenarios.map { s ->
            val r = built.getValue(s.n)
            val wire = OpenRouterModelGateway.buildBody(r)
            val bytes = HarnessJson.encodeToString(JsonObject.serializer(), wire).length
            println("  ${id2(s.n)} system ${r.systemText.length} chars, ${r.tools.size} tools, ${r.messages.size} messages, request ${bytes / 1000}k chars")
            buildJsonObject {
                put("n", s.n); put("systemChars", r.systemText.length); put("tools", r.tools.size)
                put("messages", r.messages.size); put("requestChars", bytes)
            }
        }
        if (dry) return

        val gateway = OpenRouterModelGateway(openRouterKey(), routing = OpenRouterRouting())
        val gate = Semaphore(4)
        val perScenario: List<Pair<ScenarioInput, List<Call>>> = runBlocking {
            coroutineScope {
                scenarios.map { s ->
                    s to async {
                        val calls = ArrayList<Call>()
                        // Keep sampling until `runs` answers sent, at most 3 x runs samples.
                        while (true) {
                            val more = moreSamplesNeeded(calls.map { it.output }, runs)
                            if (more == 0) break
                            val start = calls.size
                            calls += (1..more).map { i -> async { gate.withPermit { call(gateway, built.getValue(s.n), start + i) } } }.awaitAll()
                        }
                        calls.toList()
                    }
                }.map { (s, deferred) -> s to deferred.await() }
            }
        }
        val results = perScenario.flatMap { (s, calls) -> calls.map { s to it } }
        val byScenario = results.groupBy({ it.first.n }, { it.second })
        val zone = { s: ScenarioInput -> TimeZone.of(s.homeZone) }
        val entries = scenarios.map { s ->
            val outs = byScenario.getValue(s.n)
            ReportEntry(
                n = s.n,
                type = ScenarioType.valueOf(s.type),
                localTime = Instant.parse(s.timestamp).toLocalDateTime(zone(s)).let { "%02d-%02d %02d:%02d".format(it.month.ordinal + 1, it.day, it.hour, it.minute) },
                yours = answers[s.n],
                original = renderCards(s.original),
                originalWords = proseWords(s.original),
                replays = outs.map { it.output },
            )
        }
        val stamp = Clock.System.now().toString().substringBefore('.').replace(":", "").replace("-", "").removeSuffix("Z")
        val dir = LAB_HOME.resolve("runs/$stamp")
        val report = reportMarkdown("Prompt lab replay $stamp · $model", entries)
        writePrivate(dir.resolve("report.md"), report)
        writePrivate(
            dir.resolve("raw.json"),
            LabJson.encodeToString(
                JsonElement.serializer(),
                buildJsonObject {
                    put("model", model); put("effort", requests.role.effort ?: ""); put("runsPerScenario", runs)
                    put("requests", JsonArray(stats))
                    put("calls", buildJsonArray { results.forEach { (s, r) -> add(r.raw(s.n)) } })
                },
            ),
        )
        val ok = results.count { it.second.output.error == null }
        val usd = results.sumOf { it.second.usage?.reportedCostMicros ?: 0L } / 1e6
        println("$ok/${results.size} calls ok, about \$${"%.4f".format(usd)}")
        println("send rate per scenario: " + entries.joinToString(" ") { e -> "${id2(e.n)}=${e.replays.count { it.sent }}/${e.replays.size}" })
        println("report: ${dir.resolve("report.md")}")
    }

    private class Call(val output: ReplayOutput, val run: Int, val usage: Usage?, val content: List<Block>, val served: String?) {
        fun raw(n: Int): JsonObject = buildJsonObject {
            put("scenario", n); put("run", run); put("servedBy", served ?: "")
            usage?.let { put("promptTokens", it.promptTokens); put("uncachedTokens", it.inputTokens); put("cachedTokens", it.cacheReadInputTokens); put("outputTokens", it.outputTokens) }
            output.error?.let { put("error", it) }
            put("blocks", JsonArray(content.map { b ->
                when (b) {
                    is Block.Text -> buildJsonObject { put("type", "text"); put("text", b.text) }
                    is Block.ToolUse -> buildJsonObject { put("type", "tool_use"); put("name", b.name); put("input", b.input) }
                    else -> buildJsonObject { put("type", b::class.simpleName ?: "block") }
                }
            }))
        }
    }

    private suspend fun call(base: OpenRouterModelGateway, request: ModelRequest, run: Int): Call = try {
        val response = base.generate(request)
        Call(interpret(response.content), run, response.usage, response.content, null)
    } catch (e: Exception) {
        Call(ReplayOutput("", 0, error = "${e::class.simpleName}: ${e.message?.take(300)}"), run, null, emptyList(), null)
    }
}
