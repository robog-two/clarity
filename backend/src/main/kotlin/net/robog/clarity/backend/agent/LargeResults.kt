package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.backend.persistence.tx
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.OverflowedResult
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.ToolResultSink
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

private val log = KotlinLogging.logger {}

/** Pure: where a run's oversized result goes in the sandbox workspace. */
fun sandboxResultPath(runId: String, toolUseId: String): String =
    ".results/${runId.take(8)}-${toolUseId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)}.txt"

/**
 * Keeps big tool results out of the context window. The full text is written into the sandbox
 * workspace through the sandbox MCP server's `write_file` tool when that server is configured (the
 * model can then `grep`/`head` it with the shell), else stored in Postgres and read back in slices
 * with `read_result`.
 */
@Inject
@SingleIn(AppScope::class)
class LargeResults(
    private val database: Database,
    private val mcp: McpToolSources,
    private val clock: Clock,
) {
    val sink: ToolResultSink = ToolResultSink(store = ::store)

    private suspend fun store(result: OverflowedResult): String {
        sandboxWriter()?.let { write ->
            val path = sandboxResultPath(result.runId, result.toolUseId)
            val outcome = try {
                write.invoke(buildJsonObject { put("path", path); put("content", result.content) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.Error(e.message ?: "write failed")
            }
            if (outcome is ToolResult.Ok) return "the sandbox workspace file $path"
            log.warn { "could not write a large ${result.toolName} result to the sandbox, keeping it in the database: $outcome" }
        }
        val id = database.tx {
            val id = Uuid.random()
            val inserted = ToolResults.insertIgnore {
                it[ToolResults.id] = id
                it[runId] = result.runId
                it[toolUseId] = result.toolUseId
                it[toolName] = result.toolName
                it[content] = result.content
                it[createdAt] = clock.now()
            }.insertedCount > 0
            if (inserted) id else ToolResults.selectAll()
                .where { (ToolResults.runId eq result.runId) and (ToolResults.toolUseId eq result.toolUseId) }
                .single()[ToolResults.id]
        }
        return "result id $id (read it with read_result)"
    }

    private suspend fun sandboxWriter(): Tool? = mcp.tools().firstOrNull { it.name == "sandbox__write_file" }

    @Serializable
    class ReadResultInput(
        @Description("The result id from the truncation note.") val id: String,
        @Description("Character offset to start at, default 0.") val offset: Int = 0,
        @Description("How many characters, default 4000, at most 20000.") val length: Int = 4000,
    )

    fun readResultTool(): Tool = tool<ReadResultInput>(
        name = "read_result",
        description = "Read a part of a large tool result that was stored instead of shown (only for results that were not written to the sandbox).",
        json = ClarityJson,
    ) { input ->
        val uuid = runCatching { Uuid.parse(input.id) }.getOrNull() ?: return@tool ToolResult.Error("Not a result id: ${input.id}")
        val content = database.tx { ToolResults.selectAll().where { ToolResults.id eq uuid }.singleOrNull()?.get(ToolResults.content) }
        if (content == null) ToolResult.Error("No stored result with id ${input.id}.")
        else ToolResult.Ok(sliceResult(content, input.offset, input.length.coerceIn(1, 20_000)))
    }
}
