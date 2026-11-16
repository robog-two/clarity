package net.robog.clarity.backend.governance

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings
import net.robog.clarity.shared.time.ScheduleClock
import net.robog.clarity.shared.time.WeeklyTime

/** What to do about telling the person. Delivery (push) is a later workstream; this is the decision. */
sealed interface NotifyDecision {
    /** Interrupt now. */
    data object PushNow : NotifyDecision

    /** Visible in the app, no interruption. */
    data object ShowInApp : NotifyDecision

    /**
     * Urgent, but the person is in protected time and the sender does not get through. Shown in the
     * app now; `HoldReleaseJob` notifies when the protection ends.
     */
    data class HoldUntil(val at: Instant) : NotifyDecision

    /**
     * The person wrote and this answers it: shown in the app, and the phone notifies only while the
     * app is closed. Never counted against the daily push budget.
     */
    data object Reply : NotifyDecision

    /** Waits for the evening summary at [at]. */
    data class EveningBatch(val at: Instant) : NotifyDecision
}

private fun Handle.normalized(): String = value.trim().lowercase()

/** True if any of [people] is on the "who gets through" list (and not expired at [now]). */
fun getsThrough(people: List<Handle>, constitution: Constitution, now: Instant): Boolean {
    val allowed = constitution.getsThrough
        .filter { it.until == null || it.until!! > now }
        .flatMap { it.handles }
        .map { it.normalized() }
        .toSet()
    return people.any { it.normalized() in allowed }
}

/** The next evening-summary time strictly after [now]. */
fun nextEveningSummary(now: Instant, settings: Settings): Instant = checkNotNull(
    ScheduleClock.nextOccurrence(WeeklyTime(settings.eveningSummaryAt, ScheduleClock.EVERY_DAY), zoneOrUtc(settings.homeZone), now),
)

/**
 * Pure notification policy.
 *  - `URGENT`: with push enabled, interrupt now unless the person is in protected time and none of
 *    [people] gets through, in which case hold until the protection ends (merged across adjacent
 *    blocks). With push disabled urgent items are shown in the app.
 *  - `NORMAL`: shown in the app.
 *  - `BATCH`: held for the evening summary when `batchLowUrgency`, else shown in the app.
 */
fun notifyDecision(
    urgency: Urgency,
    people: List<Handle>,
    constitution: Constitution,
    settings: Settings,
    now: Instant,
): NotifyDecision = when (urgency) {
    Urgency.URGENT -> when {
        !settings.pushUrgent -> NotifyDecision.ShowInApp
        getsThrough(people, constitution, now) -> NotifyDecision.PushNow
        else -> protectedUntil(constitution.protectedTimes, now, zoneOrUtc(settings.homeZone))
            ?.let { NotifyDecision.HoldUntil(it) }
            ?: NotifyDecision.PushNow
    }
    Urgency.NORMAL -> NotifyDecision.ShowInApp
    Urgency.BATCH ->
        if (settings.batchLowUrgency) NotifyDecision.EveningBatch(nextEveningSummary(now, settings)) else NotifyDecision.ShowInApp
}


/** How long ahead a finding's due time still counts as "soon" for an interruption. */
val DUE_SOON: Duration = 24.hours

/** Pure: pushes still allowed today. At zero the next push is shown in the app without one. [limit] is clamped to the settings range. */
fun pushBudget(pushedToday: Int, limit: Int): Int =
    (limit.coerceIn(Settings.PUSHES_PER_DAY_RANGE) - pushedToday).coerceAtLeast(0)

/**
 * What [deliveryOf] decides: how the item is delivered, whether it counts against the daily push
 * budget, and whether it would have pushed but the budget was spent ([budgetSpent]).
 */
data class Delivery(val decision: NotifyDecision, val counts: Boolean, val budgetSpent: Boolean = false)

/**
 * Pure: how a post is delivered, from what it is ([kind], [urgency], [dueBy]) and the person's
 * rules. The order is fixed:
 *  1. A [MessageKind.REPLY] shows in the app and notifies only while the app is closed
 *     ([NotifyDecision.Reply]); not counted. It wins over urgency: it answers the person who just wrote.
 *  2. `URGENT` keeps [notifyDecision]'s behaviour (protected time, who gets through) and is not counted.
 *  2b. A post that [needsPerson] (an approval or a login only they can give) follows the same urgent
 *     rules whatever its kind or urgency, and is not counted: it must reach them.
 *  3. Urgency `BATCH` is the agent's own choice to wait for the evening summary: [notifyDecision]'s BATCH.
 *  4. A [MessageKind.CHECK_IN] or [MessageKind.LOOK_FORWARD] pushes; a [MessageKind.FINDING] pushes only
 *     when [dueBy] is within [DUE_SOON] or one of [people] gets through, else it waits for the evening
 *     summary. A push follows the urgent rules for protected time, counts, and is shown in the app
 *     without a push once [pushedToday] has reached [Settings.pushesPerDay].
 * An [actionable] post (it asks the person to approve, log in or pick) is never held back for the
 * evening: when it would not push it is shown in the app instead.
 */
fun deliveryOf(
    kind: MessageKind,
    urgency: Urgency,
    dueBy: Instant?,
    people: List<Handle>,
    constitution: Constitution,
    settings: Settings,
    now: Instant,
    pushedToday: Int,
    actionable: Boolean = false,
    needsPerson: Boolean = false,
): Delivery {
    if (kind == MessageKind.REPLY) return Delivery(NotifyDecision.Reply, counts = false)
    if (urgency == Urgency.URGENT || needsPerson) return Delivery(notifyDecision(Urgency.URGENT, people, constitution, settings, now), counts = false)
    if (urgency == Urgency.BATCH) return Delivery(notifyDecision(urgency, people, constitution, settings, now), counts = false)
    val wantsPush = when (kind) {
        MessageKind.CHECK_IN, MessageKind.LOOK_FORWARD -> true
        MessageKind.FINDING -> (dueBy != null && dueBy <= now + DUE_SOON) || getsThrough(people, constitution, now)
        MessageKind.REPLY -> false
    }
    if (!wantsPush) {
        return Delivery(if (actionable) NotifyDecision.ShowInApp else notifyDecision(Urgency.BATCH, people, constitution, settings, now), counts = false)
    }
    // The urgent rules decide when a push may happen (protected time) and whether pushes are on at all.
    val push = notifyDecision(Urgency.URGENT, people, constitution, settings, now)
    if (push == NotifyDecision.ShowInApp) return Delivery(push, counts = false)
    if (pushBudget(pushedToday, settings.pushesPerDay) == 0) return Delivery(NotifyDecision.ShowInApp, counts = false, budgetSpent = true)
    return Delivery(push, counts = true)
}

/**
 * Pure: how a post inside a session (a call, an interview) is delivered. Sessions keep their own
 * flow: nothing is batched or counted. An answer to the person is a [MessageKind.REPLY] as anywhere
 * (urgency does not change that); anything else follows [notifyDecision], and the phone's own call
 * exception covers a running call.
 */
fun sessionDelivery(
    kind: MessageKind,
    urgency: Urgency,
    people: List<Handle>,
    constitution: Constitution,
    settings: Settings,
    now: Instant,
): Delivery =
    if (kind == MessageKind.REPLY) Delivery(NotifyDecision.Reply, counts = false)
    else Delivery(notifyDecision(urgency, people, constitution, settings, now), counts = false)
