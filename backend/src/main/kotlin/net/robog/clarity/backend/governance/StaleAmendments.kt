package net.robog.clarity.backend.governance

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import net.robog.clarity.shared.governance.Amendment
import net.robog.clarity.shared.governance.AmendmentId
import net.robog.clarity.shared.governance.AutonomyChange
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.LineId

/**
 * Statuses a proposal can close with besides the person's own save or discard. They are stored in
 * the database as is and reach the app as `discarded` plus a `closedReason` (the wire's status set
 * is fixed: installed apps decode it exhaustively).
 */
object ClosedStatus {
    /** A later save (or a change to the line it targets) made it redundant. */
    const val SUPERSEDED = "superseded"

    /** Nobody acted on it in time, or the conversation that could save it ended. */
    const val EXPIRED = "expired"

    val ALL = setOf(SUPERSEDED, EXPIRED)
}

/**
 * How long a proposed amendment waits. The app can only save a proposal through the amend talk
 * that made it (or right away, for "always allow"), so a proposal that old is never going to be
 * saved; a week still covers a weekend away from the phone.
 */
val AMENDMENT_MAX_AGE: Duration = 7.days

/**
 * Pure: a rule's text as compared for "the same proposal": case, punctuation, quote styles and
 * spacing do not count ("Clicking “Next” is fine." equals "clicking next is fine").
 */
fun normalizeRuleText(text: String): String =
    text.lowercase()
        .replace(Regex("['‘’‛`´]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

/** What makes two proposals the same: the line they change, the normalized text, the tier change and the held action. */
data class AmendmentKey(val lineId: LineId?, val text: String, val autonomy: AutonomyChange?, val fromHeld: HeldActionId?)

fun Amendment.key(): AmendmentKey = AmendmentKey(lineId, normalizeRuleText(proposed.text), autonomy, fromHeld)

/** One stored amendment with what the tidy pass needs to judge it. */
data class AmendmentFacts(
    val amendment: Amendment,
    val createdAt: Instant,
    /** Null when no session points at it; else whether one of the sessions that do is still live. */
    val sessionLive: Boolean?,
)

/** A proposal to close: its new stored status ([ClosedStatus]) and why. */
data class Closing<Id>(val id: Id, val status: String, val reason: String)

/**
 * Pure: whether [amendment] would change nothing: its text is already a line of [constitution]
 * and its tier change (if any) is already in place.
 */
fun alreadyInConstitution(amendment: Amendment, constitution: Constitution): Boolean {
    val tierInPlace = amendment.autonomy?.let { constitution.autonomy[it.kind] == it.tier } ?: true
    if (!tierInPlace) return false
    val text = normalizeRuleText(amendment.proposed.text)
    return constitution.lines.any { normalizeRuleText(it.text) == text }
}

/**
 * Pure: the proposals in [rows] that are no longer live, in the order the rules are tried:
 * 1. already in the constitution (an equal line was saved) → superseded;
 * 2. the line it changes was removed or changed since it was proposed → superseded;
 * 3. a later amendment with the same text, or for the same line, was saved → superseded;
 * 4. a newer proposal for the same change is waiting → the older one is superseded;
 * 5. every session that pointed at it has ended (only that talk could save it) → expired;
 * 6. older than [maxAge] → expired.
 * Saved and discarded rows are only read, never changed.
 */
fun staleAmendments(
    rows: List<AmendmentFacts>,
    constitution: Constitution?,
    now: Instant,
    maxAge: Duration = AMENDMENT_MAX_AGE,
): List<Closing<AmendmentId>> {
    val saved = rows.filter { it.amendment.status == Amendment.Status.SAVED }
    val proposed = rows.filter { it.amendment.status == Amendment.Status.PROPOSED }.sortedBy { it.createdAt }
    val closings = mutableListOf<Closing<AmendmentId>>()
    val stillLive = mutableListOf<AmendmentFacts>()
    for (row in proposed) {
        val reason = supersededBy(row, saved, constitution)
        if (reason != null) closings += Closing(row.amendment.id, ClosedStatus.SUPERSEDED, reason) else stillLive += row
    }
    // Rule 4: among the remaining ones, the newest of each change stays.
    val newest = stillLive.groupBy { it.amendment.key() }.mapValues { (_, same) -> same.maxBy { it.createdAt }.amendment.id }
    for (row in stillLive) {
        val a = row.amendment
        val keep = newest.getValue(a.key())
        closings += when {
            keep != a.id -> Closing(a.id, ClosedStatus.SUPERSEDED, "a newer proposal for the same change replaced it ($keep)")
            row.sessionLive == false -> Closing(a.id, ClosedStatus.EXPIRED, "the conversation that proposed it ended without saving it")
            now - row.createdAt > maxAge -> Closing(a.id, ClosedStatus.EXPIRED, "nobody saved it within ${maxAge.inWholeDays} days")
            else -> continue
        }
    }
    return closings
}

private fun supersededBy(row: AmendmentFacts, saved: List<AmendmentFacts>, constitution: Constitution?): String? {
    val a = row.amendment
    if (constitution != null) {
        if (alreadyInConstitution(a, constitution)) return "the same rule is already in the constitution"
        a.lineId?.let { target ->
            val line = constitution.lines.firstOrNull { it.id == target } ?: return "the line it changes was removed"
            val old = a.old
            if (old != null && normalizeRuleText(line.text) != normalizeRuleText(old)) return "the line it changes was changed since"
        }
    }
    val text = normalizeRuleText(a.proposed.text)
    val later = saved.firstOrNull { s ->
        s.createdAt > row.createdAt &&
            (normalizeRuleText(s.amendment.proposed.text) == text || (a.lineId != null && s.amendment.lineId == a.lineId))
    }
    return later?.let { "a later change was saved (${it.amendment.id})" }
}
