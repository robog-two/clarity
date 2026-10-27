package net.robog.clarity.shared.path

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.robog.clarity.shared.browser.BrowserStepView

/**
 * The Agent's Path of a task or of one helper run (`GET /api/v1/tasks/{id}/path`,
 * `GET /api/v1/runs/{id}/path`): what the agents said, did and saw, in order. Built on the
 * server from the run journals and the browser audit trail, already summarized and redacted:
 * no secret, code, filled value or whole message body ever appears here, and every text is short.
 */
@Serializable
data class AgentPath(
    val subject: PathSubject,
    val totals: PathTotals,
    /** One page of the timeline, oldest first. */
    val items: List<PathItem>,
    /** Offset of the next page (`?from=`); null on the last page. */
    val next: Int? = null,
    /** Something on this path is still running: the app refreshes it. */
    val live: Boolean = false,
)

@Serializable
data class PathSubject(
    val kind: Kind,
    val id: String,
    val title: String,
    /** A task's status (`working`, `done`, …) or a run's (`queued`, `running`, `done`, `failed`, `cancelled`). */
    val status: String,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    /** A task's line on Today ("web · signing in"); null for a run. */
    val detail: String? = null,
    /** The held action a task waits on (the app offers Review); null for a run. */
    val heldActionId: String? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("task") TASK,
        @SerialName("run") RUN,
    }
}

@Serializable
data class PathTotals(
    /** Things done or said (not counting what woke the agent). */
    val steps: Int,
    val durationMillis: Long,
    /** The agents' own model calls, in millionths of a dollar; null when unknown. */
    val modelCostMicros: Long? = null,
    /** The decision model (Jev, the guard on browser steps), in millionths of a dollar; null when none. */
    val decisionCostMicros: Long? = null,
)

/** One node of the timeline. [depth] is 0 on the path's own run, 1 inside a helper the task started. */
@Serializable
sealed interface PathItem {
    val at: Instant
    val runId: String
    val depth: Int

    /** What woke the lead for a turn: the person's words, a helper finishing, an event. */
    @Serializable @SerialName("heard")
    data class Heard(
        override val at: Instant,
        override val runId: String,
        override val depth: Int = 0,
        val from: Source,
        val text: String,
    ) : PathItem {
        @Serializable
        enum class Source {
            @SerialName("person") PERSON,
            @SerialName("helper") HELPER,
            @SerialName("event") EVENT,
        }
    }

    /** A model turn: what the agent said or decided, clipped. */
    @Serializable @SerialName("said")
    data class Said(override val at: Instant, override val runId: String, override val depth: Int = 0, val text: String) : PathItem

    /** A tool call as a friendly line ("Checked your calendar", "Read 12 messages") with its outcome. */
    @Serializable @SerialName("action")
    data class Action(
        override val at: Instant,
        override val runId: String,
        override val depth: Int = 0,
        val tool: String,
        val line: String,
        val detail: String? = null,
        val outcome: Outcome,
    ) : PathItem

    /** A helper the lead started; its own path is at `runs/{childRunId}/path` (and, on a task's path, follows inline). */
    @Serializable @SerialName("helper")
    data class Helper(
        override val at: Instant,
        override val runId: String,
        override val depth: Int = 0,
        val childRunId: String,
        val task: String,
        val status: String,
        val role: String = "helper",
        /** How many steps the helper took. */
        val steps: Int = 0,
        val browserSteps: Int = 0,
        val finishedAt: Instant? = null,
    ) : PathItem

    /** One browser step, with its screenshots (fetched from `browser/steps/{id}/…png`) and Jev's box. */
    @Serializable @SerialName("browser")
    data class Browser(override val at: Instant, override val runId: String, override val depth: Int = 0, val step: BrowserStepView) : PathItem

    /** A helper's result, as it reported back. */
    @Serializable @SerialName("finished")
    data class Finished(override val at: Instant, override val runId: String, override val depth: Int = 0, val ok: Boolean, val text: String) : PathItem
}

@Serializable
enum class Outcome {
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("held") HELD,
    @SerialName("running") RUNNING,
}
