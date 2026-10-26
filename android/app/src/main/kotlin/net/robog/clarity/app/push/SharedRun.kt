package net.robog.clarity.app.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Runs [body] once while at least one caller holds it. The app in front and the push service both
 * want the feed connection (and the action loop) open; with this they share one instead of opening two.
 * The body starts with the first holder and is cancelled when the last holder goes away.
 */
class SharedRun(private val scope: CoroutineScope, private val body: suspend () -> Unit) {
    private val lock = Any()
    private var holders = 0
    private var job: Job? = null

    /** True while [body] is running for someone. */
    val running: Boolean get() = synchronized(lock) { job != null }

    /** Holds the run until the calling coroutine is cancelled. */
    suspend fun hold() {
        acquire()
        try {
            awaitCancellation()
        } finally {
            release()
        }
    }

    private fun acquire() = synchronized(lock) {
        holders++
        if (job == null) job = scope.launch { body() }
    }

    private fun release() {
        val toCancel = synchronized(lock) {
            holders--
            if (holders == 0) job.also { job = null } else null
        }
        toCancel?.cancel()
    }
}

/** The long-lived work that both the app in front and the push service keep alive. */
class ConnectionHolder(
    scope: CoroutineScope,
    feed: suspend () -> Unit,
    actions: suspend () -> Unit,
) {
    private val feedRun = SharedRun(scope, feed)
    private val actionRun = SharedRun(scope, actions)

    /** Keeps the feed connection open until cancelled. */
    suspend fun holdFeed() = feedRun.hold()

    /** Keeps the phone connector's action long-poll running until cancelled. */
    suspend fun holdActions() = actionRun.hold()

    val feedRunning: Boolean get() = feedRun.running
    val actionsRunning: Boolean get() = actionRun.running
}
