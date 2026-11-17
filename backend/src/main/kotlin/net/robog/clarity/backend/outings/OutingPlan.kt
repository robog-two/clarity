package net.robog.clarity.backend.outings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import net.robog.clarity.backend.agent.agentTime
import net.robog.clarity.backend.governance.occurrences
import net.robog.clarity.backend.persistence.handleKey
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.time.ScheduleClock

/*
 * The pure decisions of the outing scout: which free slots the next week holds, whether the scout
 * runs the lead at all, and who may be named as company. No clock, no SQL, no model.
 */

/**
 * An event of the person's calendar (see [freeSlots] for which ones take time). [always] marks a span that is taken whatever
 * its kind or length: what a truncated calendar read stands in for ([busyFrom]).
 */
data class BusySpan(val start: Instant, val end: Instant, val allDay: Boolean = false, val always: Boolean = false)

/** A timed event longer than this does not take the time it spans: a day-long course, a trip, a stay. */
val LONG_EVENT: Duration = 5.hours

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

/** The scout looks back this far for suggestions already made. */
val SUGGESTION_WINDOW: Duration = 7.days

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
 * the timed calendar [events] of at most [LONG_EVENT] (an all-day event never takes time, and neither does a timed
 * event longer than that: a course, a trip), anything that overlaps one of the constitution's [protectedTimes]
 * and the first [SLOT_LEAD] from [now]. Lengths are real elapsed time, so a day with a clock change
 * is as long as it truly is; a short event that spans midnight takes both evenings it touches.
 */
fun freeSlots(events: List<BusySpan>, protectedTimes: List<TimeBlock>, now: Instant, zone: TimeZone): List<Slot> {
    val today = ScheduleClock.homeDate(now, zone)
    val last = today.plus(DatePeriod(days = OUTING_DAYS_AHEAD - 1))
    // Only a timed event of at most LONG_EVENT takes time; an all-day event never does.
    val taken = events.filter { it.always || (!it.allDay && it.end - it.start <= LONG_EVENT) }.map { Span(it.start, it.end) } + occurrences(protectedTimes, today.plus(DatePeriod(days = -1)), last.plus(DatePeriod(days = 1)), zone).map { Span(it.start, it.end) }
    val busy = merged(taken)
    return generateSequence(today) { it.plus(DatePeriod(days = 1)) }.takeWhile { it <= last }.flatMap { date ->
        val weekend = date.dayOfWeek.isoDayNumber >= 6
        val from = maxOf(ScheduleClock.occurrence(date, if (weekend) WEEKEND_FROM else EVENING_FROM, zone), now + SLOT_LEAD)
        val to = ScheduleClock.occurrence(date, OUTING_UNTIL, zone)
        gaps(busy, from, to).filter { it.end - it.start >= MIN_SLOT }.map { Slot(it.start, it.end) }
    }.toList()
}

/** The most calendar events one scout read takes; more than this and the rest of the window counts as taken. */
const val MAX_EVENTS_READ = 500

/**
 * Pure: [events] (the calendar's events overlapping the window, ordered by start, at most [cap] of them)
 * as busy spans. A full read may have lost later events, so it fails closed: everything from the last
 * start returned until [until] counts as taken.
 */
fun busyFrom(events: List<BusySpan>, until: Instant, cap: Int = MAX_EVENTS_READ): List<BusySpan> =
    if (events.size < cap) events else events + BusySpan(events.maxOf { it.start }, until, always = true)

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
 * Pure: whether to wake the lead. [suggestedThisWeek] is how many `look_forward` posts went out in the last 7 days; nothing else
 * counts, so a "not this time" leaves no pause.
 */
fun scoutPlan(slots: List<Slot>, suggestedThisWeek: Int, perWeek: Int = SUGGESTIONS_PER_WEEK): ScoutPlan = when {
    suggestedThisWeek >= perWeek -> ScoutPlan.Skip(SkipReason.WEEK_BUDGET_USED)
    slots.isEmpty() -> ScoutPlan.Skip(SkipReason.NO_FREE_SLOT)
    else -> ScoutPlan.Offer(slots.sortedBy { it.start }.take(MAX_SLOTS_OFFERED))
}

/** A saved contact, as the scout needs it. [id] is the contact record's id. */
data class ContactRef(val id: String, val name: String, val handles: List<Handle>)

/** One message as the company rule needs it: who sent it, to whom, and the person's own account it was seen on. */
data class MessageParties(val sentAt: Instant, val sender: Handle, val recipients: List<Handle>, val account: Handle)

/** A contact the person exchanged messages with lately, and when they last did. */
data class RecentContact(val id: String, val name: String, val lastAt: Instant)

/** A message with more recipients than this is a list, not a conversation. */
const val MAX_DIRECT_RECIPIENTS = 5

private val AUTOMATED_LOCAL = Regex(
    "^(no[-_.]?reply|do[-_.]?not[-_.]?reply|donotreply|notifications?|notify|mailer-daemon|postmaster|bounces?|alerts?|newsletter|automated)([-_.+].*)?$",
)

/** Pure: whether [value] looks like an address that only sends automated mail. Phone numbers never do. */
fun isAutomatedAddress(value: String): Boolean {
    val v = value.trim().lowercase()
    if ('@' !in v) return false
    return AUTOMATED_LOCAL.matches(v.substringBefore('@'))
}

/**
 * Pure: the contacts that may be named as company. A contact qualifies when, in the last [COMPANY_WINDOW]
 * (the edge day included), either the person sent it a message (it is among at most [MAX_DIRECT_RECIPIENTS]
 * recipients) or it sent the person one directly (the person is among at most [MAX_DIRECT_RECIPIENTS]
 * recipients; a direct text has one). Email, SMS and chat all count. The person's own addresses (every
 * account the messages were seen on) and automated senders ([isAutomatedAddress]) never do, and a
 * contact with no such message, or no handle, is never company. Most recent first.
 */
fun recentContacts(contacts: List<ContactRef>, messages: List<MessageParties>, now: Instant, window: Duration = COMPANY_WINDOW): List<RecentContact> {
    val since = now - window
    val own = messages.map { handleKey(it.account.value) }.toSet()
    val lastByKey = HashMap<String, Instant>()
    fun see(h: Handle, at: Instant) {
        val key = handleKey(h.value)
        if (key !in own && !isAutomatedAddress(h.value)) lastByKey.merge(key, at) { a, b -> maxOf(a, b) }
    }
    for (m in messages) {
        if (m.sentAt < since || m.sentAt > now || m.recipients.isEmpty() || m.recipients.size > MAX_DIRECT_RECIPIENTS) continue
        if (handleKey(m.sender.value) in own) m.recipients.forEach { see(it, m.sentAt) }
        else if (m.recipients.any { handleKey(it.value) in own }) see(m.sender, m.sentAt)
    }
    return contacts.mapNotNull { c ->
        val last = c.handles.mapNotNull { lastByKey[handleKey(it.value)] }.maxOrNull() ?: return@mapNotNull null
        RecentContact(c.id, c.name, last)
    }.sortedWith(compareByDescending<RecentContact> { it.lastAt }.thenBy { it.name.lowercase() })
}

/**
 * Pure: the lead's brief for one scout run: when the person is free, who they may bring (only those messaged in the last 90
 * days), and how to find one thing to look forward to the way it finds anything else.
 */
fun outingInstruction(slots: List<Slot>, contacts: List<RecentContact>, zone: TimeZone): String = buildString {
    append("Outing scout: find ONE concrete thing for the person to look forward to in their free time, or nothing. Free time in the next week (their local time):")
    slots.forEach { append("\n- ").append(agentTime(it.start, zone)).append(" until ").append(agentTime(it.end, zone)) }
    append("\nCompany: only ")
    if (contacts.isEmpty()) append("the person alone, no contact was messaged in the last 90 days.")
    else {
        append("these people, all messaged in the last 90 days, nobody else: ")
        append(contacts.take(MAX_CONTACTS_SHOWN).joinToString("; ") { "${it.name.replace(Regex("\\s+"), " ").take(60)} (contact ${it.id})" })
        append('.')
    }
    append(
        "\nFind it the way you find anything: memory (what they enjoy, what they said yes or no to before), their mail and calendar " +
            "(an invitation, a newsletter, a friend saying \"we should get dinner\"), and a research helper with delegate as usual " +
            "(web_search or the browser, whichever fits; its brief carries no phone numbers, addresses or account numbers). " +
            "Weigh it against the slots and what is already on the calendar. Nothing good: send nothing. " +
            "Otherwise one send_to_user with kind look_forward: a short text (\"Thursday evening is free. Jazz at the Blue Room with Sam Example?\"), " +
            "a sources card for what it rests on, and a choices card with two options, one to add it to the calendar and one to turn it down with declines: true " +
            "(labels like \"Add Jazz to calendar\" and \"Not this time\", naming the outing when another suggestion may be open). " +
            "Give each option reply text that stands on its own, since later you see only that text: \"Add it to my calendar\", \"Not this time\". " +
            "A yes is the person's own request: create_event through the usual guard. On either answer, remember it " +
            "(what kind of outing, who with, yes or no) with the memory tools so later suggestions improve.",
    )
}

private const val MAX_CONTACTS_SHOWN = 15
