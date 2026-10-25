package net.robog.clarity.backend.agent

import kotlin.time.Instant
import net.robog.clarity.backend.ingest.Ingested

/** Why an agent run is happening. */
sealed interface AgentTask {
    /** Scheduled review of everything that changed since [since]. */
    data class PeriodicReview(val since: Instant) : AgentTask

    /** An agent-defined trigger matched a freshly ingested record. */
    data class TriggerFired(val triggerId: String, val instruction: String, val record: Ingested) : AgentTask
}

@JvmInline
value class AgentRunId(val value: String)

/** Entry point into the agent subsystem. Implementations must not block the caller on the run itself. */
interface AgentDispatcher {
    /** Enqueue a run; returns its id, or null if agents are disabled (e.g. no API key). */
    suspend fun dispatch(task: AgentTask): AgentRunId?
}
