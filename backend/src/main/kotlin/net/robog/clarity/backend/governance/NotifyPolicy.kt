package net.robog.clarity.backend.governance

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings

/** What to do about telling the person. Delivery (push) is a later workstream; this is the decision. */
sealed interface NotifyDecision {
    /** Interrupt now. */
    data object PushNow : NotifyDecision

    /** Visible in the app, no interruption. */
    data object ShowInApp : NotifyDecision

    /** Urgent, but the person is in protected time and the sender does not get through. */
    data class HoldUntil(val at: Instant) : NotifyDecision

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

/** The next evening-summary time strictly after [now] in the person's zone. */
fun nextEveningSummary(now: Instant, settings: Settings): Instant {
    val zone = zoneOrUtc(settings.timeZone)
    val today = now.toLocalDateTime(zone).date
    val tonight = today.atTime(settings.eveningSummaryAt).toInstant(zone)
    return if (tonight > now) tonight else today.plus(1, DateTimeUnit.DAY).atTime(settings.eveningSummaryAt).toInstant(zone)
}

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
        else -> protectedUntil(constitution.protectedTimes, now, zoneOrUtc(settings.timeZone))
            ?.let { NotifyDecision.HoldUntil(it) }
            ?: NotifyDecision.PushNow
    }
    Urgency.NORMAL -> NotifyDecision.ShowInApp
    Urgency.BATCH ->
        if (settings.batchLowUrgency) NotifyDecision.EveningBatch(nextEveningSummary(now, settings)) else NotifyDecision.ShowInApp
}

