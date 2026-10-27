package net.robog.clarity.backend.evals.live

import io.kotest.core.annotation.Condition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.robog.clarity.backend.agent.HomeClock
import net.robog.clarity.backend.agent.LeadRig
import net.robog.clarity.backend.agent.RunScope
import net.robog.clarity.backend.agent.leadSystemBlocks
import net.robog.clarity.backend.agent.roleRequest
import net.robog.clarity.backend.config.AgentRoles
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Harness
import net.robog.clarity.harness.HarnessJson
import net.robog.clarity.harness.InMemoryRunJournal
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.ModelGateway
import net.robog.clarity.harness.ModelRequest
import net.robog.clarity.harness.ModelResponse
import net.robog.clarity.harness.OpenRouterModelGateway
import net.robog.clarity.harness.OpenRouterRouting
import net.robog.clarity.harness.SUMMARY_MARKER
import net.robog.clarity.harness.StopReason
import net.robog.clarity.harness.Usage
import net.robog.clarity.harness.validateToolInput
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Opt-in feasibility replay: real lead journal points (`-Pclarity.replay=<points.json>`, a JSON array
 * of `{point, from, messages, sonnet_next}`) sent once each to an open-weight model on OpenRouter,
 * with the lead's real system prompt and tools and the product's own request conversion. No tool
 * runs, no loop. Zero data retention is forced on every call. Results go to `-Pclarity.replayOut`
 * (created mode 600), never the build dir. `-Pclarity.replayDry` builds the requests and prints the
 * cost estimate without calling anything. Never in CI.
 *
 *     OPENROUTER_API_KEY=... ./gradlew :backend:test --tests '*ReplayFeasibilityTest' -Pclarity.replay=... -Pclarity.replayOut=...
 */
class ReplayEnabled : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean =
        !System.getProperty("clarity.replay").isNullOrBlank() &&
            !System.getProperty("clarity.replayOut").isNullOrBlank() &&
            (System.getProperty("clarity.replayDry") == "true" || !System.getenv("OPENROUTER_API_KEY").isNullOrBlank()) &&
            System.getenv("CI").isNullOrBlank()
}

private const val MIMO = "xiaomi/mimo-v2.6-pro"
private const val DEEPSEEK = "deepseek/deepseek-v4.1-flash-1017"

/** USD per million tokens: uncached input, cached input, output (the pinned first provider). */
private val PRICES = mapOf(MIMO to Triple(0.387, 0.003, 0.78), DEEPSEEK to Triple(0.078, 0.002, 0.24))

/** Output cap for the replay (production: 64K); bounds the worst case of a long reasoning run. */
private const val REPLAY_MAX_TOKENS = 6_000L

/** Hard spend cap of the whole replay, estimated before any call. */
private const val BUDGET_USD = 0.13

/** Answers the first request and stops: only captures what the lead would send. */
private class Capture : ModelGateway {
    var request: ModelRequest? = null
    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        if (this.request == null) this.request = request
        return ModelResponse(listOf(Block.Text("captured")), StopReason.END_TURN, Usage.ZERO)
    }
}

@EnabledIf(ReplayEnabled::class)
class ReplayFeasibilityTest : FunSpec({
    test("replay real lead points on OpenRouter").config(timeout = 30.minutes) {
        val points = HarnessJson.parseToJsonElement(Path(System.getProperty("clarity.replay")).readText()).jsonArray.map { it.jsonObject }
        val out = Path(System.getProperty("clarity.replayOut"))
        val dry = System.getProperty("clarity.replayDry") == "true"

        // The lead's real tools: the same toolbox the production lead gets (no MCP: the lead has none).
        // The database is never touched here (only tool definitions are built), so it points nowhere.
        val roles = AgentRoles().let { it.copy(lead = it.lead.copy(model = MIMO)) }
        val rig = LeadRig(Database.connect("jdbc:postgresql://127.0.0.1:9/none", driver = "org.postgresql.Driver"), apiKey = "unused", roles = roles)
        val tools = rig.toolbox.leadTools(RunScope("replay", isLead = true))

        /** The first request the lead's harness would send for [history] (point's journal since its compaction). */
        suspend fun leadRequest(history: List<Message>, model: String): ModelRequest {
            val capture = Capture()
            val journal = InMemoryRunJournal()
            history.forEach { journal.append("lead", it) }
            // Real system prompt; the constitution lives in the production DB, so it is "none yet" here.
            val system = leadSystemBlocks(null, HomeClock(TimeZone.UTC, kotlin.time.Clock.System.now()))
            val run = roleRequest(roles.lead.copy(model = model), "lead", system, emptyList(), tools.tools, 40, null)
            Harness(capture, journal).resume("lead", run).collect()
            return capture.request!!.copy(maxTokens = REPLAY_MAX_TOKENS)
        }

        val schemas = tools.tools.associate { it.name to it.inputSchema }

        fun history(point: JsonObject) = point["messages"]!!.jsonArray.map { HarnessJson.decodeFromJsonElement(Message.serializer(), it) }

        fun blocksJson(content: List<Block>): JsonArray = buildJsonArray {
            content.forEach { b ->
                when (b) {
                    is Block.Text -> add(buildJsonObject { put("type", "text"); put("text", b.text) })
                    is Block.ToolUse -> add(buildJsonObject {
                        put("type", "tool_use"); put("name", b.name); put("input", b.input)
                        val schema = schemas[b.name]
                        put("valid", if (schema == null) "unknown tool" else validateToolInput(schema, b.input) ?: "ok")
                    })
                    is Block.Raw -> add(buildJsonObject {
                        put("type", "raw:${b.type}")
                        // Reasoning text length only (its size, not its content).
                        put("chars", HarnessJson.encodeToString(JsonObject.serializer(), b.json).length)
                    })
                    else -> add(buildJsonObject { put("type", b::class.simpleName) })
                }
            }
        }

        // Plan: MiMo on every point (point 1 twice, back to back, for the cache), DeepSeek on a tool-use and a text point.
        data class Call(val pointIndex: Int, val model: String, val order: List<String>, val label: String)
        val calls = buildList {
            points.indices.forEach { i ->
                add(Call(i, MIMO, listOf("DeepInfra", "Novita"), "mimo"))
                if (i == 0) add(Call(0, MIMO, listOf("DeepInfra", "Novita"), "mimo-repeat"))
            }
            val toolPoint = points.indexOfFirst { it["point"]!!.jsonPrimitive.content == "794" }.takeIf { it >= 0 } ?: 1
            val textPoint = points.indexOfFirst { it["point"]!!.jsonPrimitive.content == "1008" }.takeIf { it >= 0 } ?: 2
            add(Call(toolPoint, DEEPSEEK, listOf("DeepInfra"), "deepseek"))
            add(Call(textPoint, DEEPSEEK, listOf("DeepInfra"), "deepseek"))
        }

        val requests = calls.map { leadRequest(history(points[it.pointIndex]), it.model) }

        // Budget: ~3.3 chars per token over the wire body, 1500 output tokens expected, uncached.
        var estimate = 0.0
        val estimates = calls.zip(requests).map { (call, request) ->
            val body = OpenRouterModelGateway.buildBody(request, OpenRouterRouting(order = call.order, allowFallbacks = false))
            val tokens = HarnessJson.encodeToString(JsonObject.serializer(), body).length / 3.3
            val (inP, _, outP) = PRICES.getValue(call.model)
            val usd = tokens * inP / 1e6 + 1500 * outP / 1e6
            estimate += usd
            Triple(call, tokens.toLong(), usd)
        }
        println("REPLAY tools: ${tools.tools.size} (all sent as functions)")
        estimates.forEach { (c, t, u) -> println("REPLAY estimate ${c.label} point#${c.pointIndex} ~${t} in tokens ~$${"%.4f".format(u)}") }
        println("REPLAY estimate total ~$${"%.4f".format(estimate)} (budget $BUDGET_USD)")

        // How the product converts the compaction block: the first wire message after the system prompt.
        val conversion = requests.take(points.size + 1).map { request ->
            val messages = OpenRouterModelGateway.buildBody(request)["messages"]!!.jsonArray
            val first = messages.getOrNull(1)?.jsonObject
            val content = (first?.get("content") as? JsonPrimitive)?.content.orEmpty()
            buildJsonObject {
                put("wireMessages", messages.size)
                put("firstAfterSystemRole", first?.get("role")?.jsonPrimitive?.content)
                put("firstIsSummary", content.startsWith(SUMMARY_MARKER))
                put("summaryChars", content.length)
            }
        }
        conversion.forEach { println("REPLAY conversion $it") }
        if (dry) return@config
        check(estimate <= BUDGET_USD) { "estimated $estimate over budget $BUDGET_USD" }

        val apiKey = System.getenv("OPENROUTER_API_KEY")
        val results = buildJsonArray {
            calls.zip(requests).forEachIndexed { n, (call, request) ->
                var served: String? = null
                val routing = OpenRouterRouting(zdr = true, order = call.order, allowFallbacks = false)
                check(routing.zdr)
                val gateway = OpenRouterModelGateway(apiKey, retryDelays = emptyList(), routing = routing, onServed = { served = it })
                val point = points[call.pointIndex]
                val started = TimeSource.Monotonic.markNow()
                val outcome = runCatching { gateway.generate(request) }
                val latency = started.elapsedNow()
                val entry = buildJsonObject {
                    put("call", n + 1)
                    put("label", call.label)
                    put("point", point["point"]!!)
                    put("model", call.model)
                    put("providerOrder", JsonArray(call.order.map(::JsonPrimitive)))
                    put("servedBy", served)
                    put("latencyMs", latency.inWholeMilliseconds)
                    put("conversion", conversion.getOrNull(call.pointIndex + if (call.pointIndex > 0) 1 else 0) ?: JsonObject(emptyMap()))
                    outcome.onSuccess { r ->
                        put("stopReason", r.stopReason.name)
                        put("usage", buildJsonObject {
                            put("uncachedIn", r.usage.inputTokens)
                            put("cachedIn", r.usage.cacheReadInputTokens)
                            put("cacheWrite", r.usage.cacheCreationInputTokens)
                            put("out", r.usage.outputTokens)
                            put("costUsd", r.usage.reportedCostMicros?.let { it / 1e6 })
                        })
                        put("response", blocksJson(r.content))
                    }
                    outcome.onFailure { put("error", "${it::class.simpleName}: ${it.message?.take(300)}") }
                    val sonnet = HarnessJson.decodeFromJsonElement(Message.serializer(), point["sonnet_next"]!!)
                    put("sonnetNext", blocksJson(sonnet.content))
                }
                println("REPLAY call ${n + 1} ${call.label} ${call.model} served=$served ${latency.inWholeMilliseconds}ms " +
                    (outcome.getOrNull()?.let { "stop=${it.stopReason} usage=${it.usage}" } ?: "failed: ${outcome.exceptionOrNull()?.message?.take(200)}"))
                add(entry)
            }
        }
        writePrivate(out, HarnessJson.encodeToString(JsonElement.serializer(), results))
    }
})

/** Writes [text] to [path], created owner-only (600). */
private fun writePrivate(path: Path, text: String) {
    if (!path.exists()) Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.writeString(path, text, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
}
