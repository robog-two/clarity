package net.robog.clarity.backend.evals.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.robog.clarity.harness.Usage

/** One graded criterion. */
data class Grade(val criterion: String, val pass: Boolean, val reason: String)

/** The JSON schema the grader's answer must follow (structured output). */
val RUBRIC_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("grades")) })
    put(
        "properties",
        buildJsonObject {
            put(
                "grades",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "items",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("required", buildJsonArray { listOf("criterion", "pass", "reason").forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                            put(
                                "properties",
                                buildJsonObject {
                                    put("criterion", buildJsonObject { put("type", "string") })
                                    put("pass", buildJsonObject { put("type", "boolean") })
                                    put("reason", buildJsonObject { put("type", "string") })
                                },
                            )
                        },
                    )
                },
            )
        },
    )
}

val RUBRIC_SYSTEM = """
    You grade the behaviour of a personal assistant against a rubric. You see what the person
    said, what the assistant did (its messages to the person, the actions it proposed, what was
    held) and a list of criteria. Judge each criterion strictly and only from what you can see.
    Answer with JSON: one grade per criterion, in order, with the criterion text copied exactly,
    pass true or false, and a one-sentence reason.
""".trimIndent()

/** Pure: the grader's user message. */
fun rubricPrompt(scenario: String, outcome: String, criteria: List<String>): String = buildString {
    appendLine("Scenario: $scenario")
    appendLine()
    appendLine("What happened:")
    appendLine(outcome)
    appendLine()
    appendLine("Criteria:")
    criteria.forEachIndexed { i, c -> appendLine("${i + 1}. $c") }
}

/** Pure: parses the grader's JSON. A missing or malformed grade is a failure, never a pass. */
fun parseGrades(text: String, criteria: List<String>, json: Json = Json { ignoreUnknownKeys = true }): List<Grade> {
    val parsed = runCatching {
        json.parseToJsonElement(text).let { it as JsonObject }["grades"]
            ?.let { arr -> (arr as kotlinx.serialization.json.JsonArray).map { it as JsonObject } }
    }.getOrNull().orEmpty()
    return criteria.mapIndexed { i, criterion ->
        val g = parsed.getOrNull(i)
        val pass = (g?.get("pass") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        val reason = (g?.get("reason") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "no grade returned"
        Grade(criterion, pass, reason)
    }
}

/** Pure: what a run cost in tokens, with the cache read share, for the printed report. */
fun usageLine(u: Usage): String {
    val total = u.inputTokens + u.cacheReadInputTokens + u.cacheCreationInputTokens
    val hit = if (total == 0L) 0 else (100 * u.cacheReadInputTokens / total).toInt()
    return "input=${u.inputTokens} cache_write=${u.cacheCreationInputTokens} cache_read=${u.cacheReadInputTokens} output=${u.outputTokens} (cache hit ${hit}% of input)"
}

/** Pure: words in the agent's messages to the person. */
fun wordCount(texts: List<String>): Int = texts.sumOf { t -> t.split(Regex("\\s+")).count { it.isNotBlank() } }
