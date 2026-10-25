package net.robog.clarity.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A gateway that replays scripted responses and records every request it received. */
class ScriptedGateway(private val script: List<ModelResponse>) : ModelGateway {
    val requests = mutableListOf<ModelRequest>()

    override suspend fun generate(request: ModelRequest, onTextDelta: (String) -> Unit): ModelResponse {
        requests += request
        val response = script.getOrNull(requests.size - 1) ?: error("script exhausted at call ${requests.size}")
        response.text.takeIf { it.isNotEmpty() }?.let(onTextDelta)
        return response
    }
}

fun response(stop: StopReason, vararg content: Block, usage: Usage = Usage(inputTokens = 10, outputTokens = 5)) =
    ModelResponse(content.toList(), stop, usage)

fun toolUse(id: String, name: String, vararg args: Pair<String, String>) =
    Block.ToolUse(id, name, buildJsonObject { args.forEach { (k, v) -> put(k, v) } })

/** A hand-written tool, for loop tests that do not care about schemas. */
fun fakeTool(
    name: String,
    effect: ToolEffect = ToolEffect.READ,
    body: suspend (JsonObject) -> ToolResult = { ToolResult.Ok("$name ok") },
): Tool = object : Tool {
    override val name = name
    override val description = "fake $name"
    override val inputSchema = buildJsonObject { put("type", "object") }
    override val effect = effect
    override suspend fun invoke(input: JsonObject) = body(input)
}
