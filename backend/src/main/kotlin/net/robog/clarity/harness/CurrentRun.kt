package net.robog.clarity.harness

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * The run (lead thread or helper) whose loop is executing: set by the harness around a run, so
 * anything it calls (the model, tools, the decision model) can tell which run it works for, such
 * as the usage recorder tagging `model_call.run_id`.
 */
class CurrentRun(val runId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CurrentRun>

    override fun toString(): String = "CurrentRun($runId)"
}

/** The id of the run executing in this coroutine, if any. */
suspend fun currentRunId(): String? = currentCoroutineContext()[CurrentRun]?.runId
