package net.robog.clarity.backend.agent

import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.noun.Payload

/**
 * PLACEHOLDER prompts. The product prompt (voice, priorities, what "proactive" means) is future
 * work; this exists so the plumbing has something real to send.
 *
 * The system prompt must stay byte-stable across runs: it is the head of the cached prompt prefix
 * (system + tools). Anything that varies, notably the current time, goes in the user message.
 */
object AgentPrompts {
    val SYSTEM: String = """
[PLACEHOLDER SYSTEM PROMPT - replace with the product prompt]
You are Clarity, a personal assistant working for one person. You act on their behalf in the background.
You can read their messages, calendar events and contacts, and manage the triggers that wake you up,
using the provided tools. Read data only through tools; never guess at what it contains.
Tools that change state or reach outside may require approval; if one is refused, continue without it.
When you are done, reply with a short plain-text summary of what you found or did.
""".trimIndent()

    /** Renders the first user message for a run. [now] is included here, not in the system prompt. */
    fun render(task: AgentTask, now: Instant): String = when (task) {
        is AgentTask.PeriodicReview -> """
            Current time: $now
            Task: periodic review.
            Review what changed since ${task.since}: look at new messages and calendar events in that window,
            summarize anything that matters, and revisit anything that was waiting on information.
        """.trimIndent()

        is AgentTask.TriggerFired -> """
            Current time: $now
            Task: trigger ${task.triggerId} fired on a newly ingested record.
            Instruction: ${task.instruction}
            Record (${if (task.record.created) "new" else "updated"}, id ${task.record.entityId}, from connector ${task.record.connectorId}):
            ${ClarityJson.encodeToString(Payload.serializer(), task.record.payload)}
        """.trimIndent()
    }

    /** Short machine-readable name for the `reason` column. */
    fun reason(task: AgentTask): String = when (task) {
        is AgentTask.PeriodicReview -> "periodic_review"
        is AgentTask.TriggerFired -> "trigger_fired"
    }

    /** The task as stored in `agent_run.task`. */
    fun describe(task: AgentTask): JsonObject = when (task) {
        is AgentTask.PeriodicReview -> buildJsonObject {
            put("type", "periodic_review")
            put("since", task.since.toString())
        }
        is AgentTask.TriggerFired -> buildJsonObject {
            put("type", "trigger_fired")
            put("triggerId", task.triggerId)
            put("instruction", task.instruction)
            put("entityId", task.record.entityId.value)
            put("connectorId", task.record.connectorId.value)
            put("payload", ClarityJson.encodeToJsonElement(Payload.serializer(), task.record.payload))
        }
    }
}
