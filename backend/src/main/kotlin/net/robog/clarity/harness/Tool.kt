package net.robog.clarity.harness

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/** What running a tool can do to the world; drives [ToolPolicy]'s default approval rule. */
enum class ToolEffect {
    /** Pure read of data we own. */
    READ,

    /** Mutates our own state (triggers, notes). */
    WRITE,

    /** Reaches outside (browser, third-party MCP servers, email). */
    EXTERNAL,
}

/** Outcome of a tool call. Failures are values, never exceptions, so the model can react to them. */
sealed interface ToolResult {
    data class Ok(val content: String) : ToolResult
    data class Error(val message: String) : ToolResult
}

interface Tool {
    val name: String
    val description: String
    val inputSchema: JsonObject
    val effect: ToolEffect

    /** Must not throw for bad input; the harness additionally converts stray exceptions to [ToolResult.Error]. */
    suspend fun invoke(input: JsonObject): ToolResult
}

val Tool.definition: ToolDefinition get() = ToolDefinition(name, description, inputSchema)

/**
 * Defines a tool over a typed `@Serializable` input class. The schema is derived from the class
 * (see [jsonSchemaOf]) and the model's arguments are decoded before [block] runs, so a malformed
 * call becomes a [ToolResult.Error] the model can correct instead of a crash.
 */
inline fun <reified I : Any> tool(
    name: String,
    description: String,
    effect: ToolEffect = ToolEffect.READ,
    json: Json = HarnessJson,
    noinline block: suspend (I) -> ToolResult,
): Tool = typedTool(name, description, effect, json, json.serializersModule.serializer<I>(), block)

fun <I : Any> typedTool(
    name: String,
    description: String,
    effect: ToolEffect,
    json: Json,
    serializer: KSerializer<I>,
    block: suspend (I) -> ToolResult,
): Tool = object : Tool {
    override val name = name
    override val description = description
    override val effect = effect
    override val inputSchema: JsonObject = jsonSchemaOf(serializer.descriptor)

    override suspend fun invoke(input: JsonObject): ToolResult {
        val decoded = try {
            json.decodeFromJsonElement(serializer, input)
        } catch (e: SerializationException) {
            return ToolResult.Error("Invalid input for $name: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error("Invalid input for $name: ${e.message}")
        }
        return block(decoded)
    }
}

/** Runs [tool] converting anything it throws (except cancellation) into a [ToolResult.Error]. */
suspend fun Tool.invokeSafely(input: JsonObject): ToolResult = try {
    invoke(input)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    ToolResult.Error("Tool $name failed: ${e.message ?: e::class.simpleName}")
}

/**
 * Which tool call is running, for tools that must derive a stable idempotency key: the same
 * `(runId, toolUseId)` is re-derived when a crashed run resumes and executes the call again.
 * Read it with `coroutineContext[ToolCallContext]`; absent outside the harness (tests, MCP server).
 */
class ToolCallContext(val runId: String, val toolUseId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolCallContext>
}
