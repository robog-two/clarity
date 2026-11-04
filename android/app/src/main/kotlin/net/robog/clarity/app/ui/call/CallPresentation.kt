package net.robog.clarity.app.ui.call

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.IfThen
import net.robog.clarity.shared.feed.TaskId
import net.robog.clarity.shared.session.CallStage
import net.robog.clarity.shared.session.Session
import net.robog.clarity.shared.session.SessionControl

/** The four stage segments under the call header. */
val StageLabels: List<String> = listOf("Ideate", "Identify", "Unblock", "Delegate")

enum class SegmentState { Done, Current, Todo }

/** [Done] before the current stage, [Current] on it, [Todo] after; all [Done] once the call is over. */
fun stageSegments(stage: CallStage?, ended: Boolean = false): List<SegmentState> =
    CallStage.entries.map { s ->
        when {
            ended -> SegmentState.Done
            stage == null -> if (s == CallStage.CLEAR) SegmentState.Current else SegmentState.Todo
            s.ordinal < stage.ordinal -> SegmentState.Done
            s == stage -> SegmentState.Current
            else -> SegmentState.Todo
        }
    }

/** The primary button advances every stage with `Next` and ends the call on the last: `End call`. */
fun primaryLabel(stage: CallStage?): String = when (stage) {
    null, CallStage.CLEAR, CallStage.CHOOSE, CallStage.COMMIT -> "Next"
    CallStage.HAND_OFF -> "End call"
}

/** What a screen reader says for the stage bar: "Step 2 of 4, Identify". */
fun stageDescription(stage: CallStage?, ended: Boolean = false): String {
    if (ended) return "All ${StageLabels.size} steps done"
    val index = stage?.ordinal ?: 0
    return "Step ${index + 1} of ${StageLabels.size}, ${StageLabels[index]}"
}

/** "4:12" as "4 minutes" (and "1:05:00" as "1 hour 5 minutes"): the timer for a screen reader, changing once a minute. */
fun spokenElapsed(elapsed: String): String {
    val parts = elapsed.split(":").mapNotNull { it.toIntOrNull() }
    val (hours, minutes) = when (parts.size) {
        3 -> parts[0] to parts[1]
        2 -> 0 to parts[0]
        else -> return elapsed
    }
    fun unit(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
    return when {
        hours > 0 -> unit(hours, "hour") + if (minutes > 0) " " + unit(minutes, "minute") else ""
        minutes > 0 -> unit(minutes, "minute")
        else -> "just started"
    }
}

fun primaryControl(stage: CallStage?): SessionControl =
    if (stage == CallStage.HAND_OFF) SessionControl.End else SessionControl.Next

/** The button is disabled while the agent is producing the stage's output (`BUSY`) or the call is over. */
fun primaryEnabled(session: Session?): Boolean = session?.state == Session.State.ACTIVE

/** The newest plan card of the call, for the ended screen. */
fun planOf(items: List<FeedItem>): List<IfThen> =
    items.sortedBy { it.seq }.flatMap { it.cards }.filterIsInstance<Card.Plan>().lastOrNull()?.items.orEmpty()

/** The tasks the call set up, in the order the agent listed them, resolved against the live task cache. */
fun setupTasks(items: List<FeedItem>, tasks: Map<TaskId, AgentTask>): List<AgentTask> =
    items.sortedBy { it.seq }
        .flatMap { it.cards }
        .filterIsInstance<Card.Tasks>()
        .flatMap { it.taskIds }
        .distinct()
        .mapNotNull { tasks[it] }

/** "7:05" */
fun formatElapsed(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    val seconds = (total % 60).toString().padStart(2, '0')
    return if (total < 3600) "${total / 60}:$seconds" else "${total / 3600}:${(total / 60 % 60).toString().padStart(2, '0')}:$seconds"
}

/** Wall-clock time of [at] in [zone], as "9:15". */
fun formatClock(at: Instant, zone: TimeZone): String = formatTime(at.toLocalDateTime(zone).time)

/** Whether the session is over. */
fun callEnded(session: Session?): Boolean = session?.state == Session.State.ENDED

/** The seqs of the agent's finished replies among [items] (a streaming reply counts once it is done). */
fun agentReplies(items: List<FeedItem>): Set<Long> =
    items.filter { it.author == FeedItem.Author.AGENT && !it.streaming }.mapTo(HashSet()) { it.seq }

/** A reply older than this when it shows up is history catching up (say after a reconnect), not an answer. */
val BLIP_FRESH: Duration = 1.minutes

/**
 * Whether [items] hold a reply that deserves the haptic blip: a finished agent reply not among
 * [before] (the replies already accounted for) and written just now. [before] is null for the first
 * look at a call (the history shown when it opens), which never blips.
 */
fun shouldBlip(before: Set<Long>?, items: List<FeedItem>, now: Instant, fresh: Duration = BLIP_FRESH): Boolean =
    before != null && items.any {
        it.author == FeedItem.Author.AGENT && !it.streaming && it.seq !in before && now - it.at <= fresh
    }
