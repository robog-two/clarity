package net.robog.clarity.backend.outings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import net.robog.clarity.backend.agent.agentTime
import net.robog.clarity.backend.governance.occurrences
import net.robog.clarity.backend.persistence.handleKey
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.time.LocalTimes
import net.robog.clarity.shared.time.ScheduleClock

/*
 * The pure decisions of the outing scout: which free slots the next week holds, whether the scout
 * runs the lead at all, and who may be named as company. No clock, no SQL, no model.
 */

/** A stretch of the person's calendar that is taken. All-day events cover their whole days (see [freeSlots]). */
data class BusySpan(val start: Instant, val end: Instant, val allDay: Boolean = false)

/** A free stretch: `[start, end)`. */
data class Slot(val start: Instant, val end: Instant) {
    val length: Duration get() = end - start
}

/** Weekday evenings count from here. */
val EVENING_FROM: LocalTime = LocalTime(18, 0)

/** Weekend days count from here. */
val WEEKEND_FROM: LocalTime = LocalTime(9, 0)

/** No outing starts later than this local time. */
val OUTING_UNTIL: LocalTime = LocalTime(22, 0)

/** The shortest stretch worth an outing. */
val MIN_SLOT: Duration = 2.hours

/** A slot must start at least this long from now, so there is time to plan. */
val SLOT_LEAD: Duration = 2.hours

/** How many days ahead the scout looks (today included). */
const val OUTING_DAYS_AHEAD = 7

/** Suggestions per rolling week. */
const val SUGGESTIONS_PER_WEEK = 2

/** Slots handed to the lead at most, so the brief stays short. */
const val MAX_SLOTS_OFFERED = 3

/** Only people messaged within this long may be named as company. */
val COMPANY_WINDOW: Duration = 90.days

private data class Span(val start: Instant, val end: Instant)

/** Pure: [spans] sorted and merged where they touch or overlap. */
private fun merged(spans: List<Span>): List<Span> {
    val out = ArrayList<Span>()
    for (s in spans.filter { it.end > it.start }.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && s.start <= last.end) out[out.lastIndex] = Span(last.start, maxOf(last.end, s.end)) else out += s
    }
    return out
}

/** Pure: the parts of `[from, to)` that none of the sorted, merged [busy] spans touch. */
private fun gaps(busy: List<Span>, from: Instant, to: Instant): List<Span> {
    var cursor = from
    val out = ArrayList<Span>()
    for (b in busy) {
        if (b.end <= cursor) continue
        if (b.start >= to) break
        if (b.start > cursor) out += Span(cursor, b.start)
        cursor = maxOf(cursor, b.end)
    }
    if (cursor < to) out += Span(cursor, to)
    return out
}

/**
 * Pure: the free time worth an outing in the next [OUTING_DAYS_AHEAD] days (the person's days in
 * [zone], today included): on weekdays the evening from [EVENING_FROM], on Saturday and Sunday the day
 * from [WEEKEND_FROM], both until [OUTING_UNTIL], and only stretches of at least [MIN_SLOT]. Taken are
 * the calendar [events] (an all-day event takes its whole days: the person is away or booked, and it
 * is not the scout's call otherwise), anything that overlaps one of the constitution's [protectedTimes]
 * and the first [SLOT_LEAD] from [now]. Lengths are real elapsed time, so a day with a clock change
 * is as long as it truly is; an event that spans midnight takes both evenings it touches.
 */
fun freeSlots(events: List<BusySpan>, protectedTimes: List<TimeBlock>, now: Instant, zone: TimeZone): List<Slot> {
    val today = ScheduleClock.homeDate(now, zone)
    val last = today.plus(DatePeriod(days = OUTING_DAYS_AHEAD - 1))
    val taken = events.map { e ->
        if (e.allDay) {
            val (first, lastDay) = LocalTimes.allDayDates(e.start, e.end)
            Span(first.atStartOfDayIn(zone), lastDay.plus(DatePeriod(days = 1)).atStartOfDayIn(zone))
        } else {
            Span(e.start, e.end)
        }
    } + occurrences(protectedTimes, today.plus(DatePeriod(days = -1)), last.plus(DatePeriod(days = 1)), zone).map { Span(it.start, it.end) }
    val busy = merged(taken)
    return generateSequence(today) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= last }.flatMap { date ->
        val weekend = date.dayOfWeek.isoDayNumber >= 6
        val from = maxOf(ScheduleClock.occurrence(date, if (weekend) WEEKEND_FROM else EVENING_FROM, zone), now + SLOT_LEAD)
        val to = ScheduleClock.occurrence(date, OUTING_UNTIL, zone)
        gaps(busy, from, to).filter { it.end - it.start >= MIN_SLOT }.map { Slot(it.start, it.end) }
    }.toList()
}

/** Why the scout does not wake the lead. */
enum class SkipReason { NO_FREE_SLOT, WEEK_BUDGET_USED }

/** What the scout does this time. */
sealed interface ScoutPlan {
    /** Nothing to offer: no model call. */
    data class Skip(val reason: SkipReason) : ScoutPlan

    /** Wake the lead with these slots, soonest first. */
    data class Offer(val slots: List<Slot>) : ScoutPlan
}

/**
 * Pure: whether to wake the lead. [suggestedThisWeek] is how many outing suggestions went out in the
 * last seven days. Nothing else counts: a "not this time" leaves no pause and no cooldown, and the
 * idea it was about is simply never offered again (see [ideaRefusal]).
 */
fun scoutPlan(slots: List<Slot>, suggestedThisWeek: Int, perWeek: Int = SUGGESTIONS_PER_WEEK): ScoutPlan = when {
    suggestedThisWeek >= perWeek -> ScoutPlan.Skip(SkipReason.WEEK_BUDGET_USED)
    slots.isEmpty() -> ScoutPlan.Skip(SkipReason.NO_FREE_SLOT)
    else -> ScoutPlan.Offer(slots.sortedBy { it.start }.take(MAX_SLOTS_OFFERED))
}

/** A saved contact, as the scout needs it. [id] is the contact record's id. */
data class ContactRef(val id: String, val name: String, val handles: List<Handle>)

/** One message's other parties (the person's own account left out) and when it was sent. */
data class MessageParties(val sentAt: Instant, val parties: List<Handle>)

/** A contact the person exchanged messages with lately, and when they last did. */
data class RecentContact(val id: String, val name: String, val lastAt: Instant)

/**
 * Pure: the contacts that may be named as company. A contact qualifies when a message sent or received
 * in the last [COMPANY_WINDOW] (email, SMS or chat, the edge day included) has one of its handles
 * among its parties. A contact with no such message, or none at all, never does. Most recent first.
 */
fun recentContacts(contacts: List<ContactRef>, messages: List<MessageParties>, now: Instant, window: Duration = COMPANY_WINDOW): List<RecentContact> {
    val since = now - window
    val lastByKey = HashMap<String, Instant>()
    for (m in messages) {
        if (m.sentAt < since || m.sentAt > now) continue
        for (h in m.parties) lastByKey.merge(handleKey(h.value), m.sentAt) { a, b -> maxOf(a, b) }
    }
    return contacts.mapNotNull { c ->
        val last = c.handles.mapNotNull { lastByKey[handleKey(it.value)] }.maxOrNull() ?: return@mapNotNull null
        RecentContact(c.id, c.name, last)
    }.sortedWith(compareByDescending<RecentContact> { it.lastAt }.thenBy { it.name.lowercase() })
}

/** The state of a saved idea. */
enum class IdeaState(val wire: String) {
    OPEN("open"), USED("used"), DECLINED("declined");

    companion object {
        fun of(wire: String): IdeaState? = entries.firstOrNull { it.wire == wire.trim().lowercase() }
    }
}

private fun String.normalized() = lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(Regex("\\s+"), " ")

/**
 * Pure: why a new idea [what] (with its [url], if any) cannot be saved given the [existing] ones, or
 * null. An idea the person already declined is never offered again, so saving it again is refused; one
 * that is still open or was used is simply the same idea.
 */
fun ideaRefusal(what: String, url: String?, existing: List<SavedIdea>): String? {
    if (what.isBlank()) return "`what` must say what the outing is."
    val same = existing.firstOrNull { it.matches(what, url) } ?: return null
    return when (same.state) {
        IdeaState.DECLINED -> "The person said no to \"${same.what}\" (${same.id}). It is never suggested again."
        IdeaState.OPEN -> "\"${same.what}\" is already saved (${same.id})."
        IdeaState.USED -> "\"${same.what}\" was already put on the calendar (${same.id})."
    }
}

/** A saved outing idea. [window] is when it can happen, if it is bound to a time. */
data class SavedIdea(
    val id: String,
    val what: String,
    val place: String?,
    val category: String?,
    val from: Instant?,
    val to: Instant?,
    val withContact: String?,
    val sourceId: String?,
    val url: String?,
    val state: IdeaState,
    val createdAt: Instant,
) {
    fun matches(otherWhat: String, otherUrl: String?): Boolean =
        what.normalized() == otherWhat.normalized() || (url != null && otherUrl != null && url.trimEnd('/').equals(otherUrl.trim().trimEnd('/'), ignoreCase = true))

    /** Whether the idea can still happen in [slot]: open and, when bound to a window, overlapping it. */
    fun fits(slot: Slot): Boolean = state == IdeaState.OPEN && (from == null || to == null || (from < slot.end && to > slot.start))
}

/**
 * Pure: the lead's brief for one scout run. Slots on the person's clock, the people they may bring
 * (only those messaged in the last 90 days) and how many ideas are waiting (the lead reads them with
 * `list_outing_ideas`, which is also what lets it cite their records).
 */
fun outingInstruction(slots: List<Slot>, contacts: List<RecentContact>, openIdeas: Int, zone: TimeZone): String = buildString {
    append("Outing scout: offer the person ONE thing to look forward to, or nothing. Free time in the next week (their local time):")
    slots.forEach { append("\n- ").append(agentTime(it.start, zone)).append(" until ").append(agentTime(it.end, zone)) }
    append("\nCompany: only ")
    if (contacts.isEmpty()) append("the person alone, no contact was messaged in the last 90 days.")
    else {
        append("these people, all messaged in the last 90 days, nobody else: ")
        append(contacts.take(MAX_CONTACTS_SHOWN).joinToString("; ") { "${it.name.replace(Regex("\\s+"), " ").take(60)} (contact ${it.id})" })
        append('.')
    }
    append("\nSaved ideas waiting: $openIdeas")
    append(if (openIdeas > 0) " (list_outing_ideas)." else ".")
    append(
        "\nSteps. 1. list_outing_ideas, and pick an open idea that fits a slot. " +
            "2. If none fits, and the profile or memory tell you the person's city and what they enjoy, delegate ONE helper with tools [\"web_search\"] " +
            "to find up to 3 events in that window. Its brief holds only the city, the time window, their interests and the kinds of outing they liked or declined " +
            "(search_memory, list_outing_ideas): never a name, mail, address or anything else private. Ask for title, time, place and page address of each. " +
            "If the city or interests are unknown, skip the web search and use saved ideas only. " +
            "3. Nothing fits: send nothing. " +
            "4. Otherwise save_outing_idea for a found event (it keeps what the person answers), then send_to_user with kind look_forward: a short text " +
            "(\"Thursday evening is free. Jazz at the Blue Room with Sam Example?\"), a sources card (the idea's record id from list_outing_ideas, and the event page once a helper fetched it), " +
            "and a choices card with the options Add to calendar and Not this time. " +
            "Add to calendar comes back as the person's reply: create_event, then update_outing_idea used. " +
            "Not this time: add nothing, update_outing_idea declined, and make no other plan for it. " +
            "Either way write_journal what kind of outing it was and the answer, so later briefs improve.",
    )
}

private const val MAX_CONTACTS_SHOWN = 15
