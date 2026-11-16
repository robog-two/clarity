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

/** A stretch of the person's calendar that is taken. All-day events of two or more days cover those days (see [freeSlots]). */
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

/** The server refuses a third suggestion within this long of the post. */
val SUGGESTION_WINDOW: Duration = 7.days

/** The scout counts over a little less, so a Tuesday 15:03 post does not make the next Tuesday 15:00 run skip. */
val SCOUT_BUDGET_WINDOW: Duration = SUGGESTION_WINDOW - 1.hours

/** The most outings one turn the person started may offer. */
const val MAX_OFFERS_PER_PERSON_TURN = 2

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
 * the calendar [events] (an all-day event of two or more days takes those days: a trip. A single all-day
 * event, a holiday or a birthday, takes nothing), anything that overlaps one of the constitution's [protectedTimes]
 * and the first [SLOT_LEAD] from [now]. Lengths are real elapsed time, so a day with a clock change
 * is as long as it truly is; an event that spans midnight takes both evenings it touches.
 */
fun freeSlots(events: List<BusySpan>, protectedTimes: List<TimeBlock>, now: Instant, zone: TimeZone): List<Slot> {
    val today = ScheduleClock.homeDate(now, zone)
    val last = today.plus(DatePeriod(days = OUTING_DAYS_AHEAD - 1))
    val taken = events.mapNotNull { e ->
        if (e.allDay) {
            val (first, lastDay) = LocalTimes.allDayDates(e.start, e.end)
            if (first == lastDay) null else Span(first.atStartOfDayIn(zone), lastDay.plus(DatePeriod(days = 1)).atStartOfDayIn(zone))
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

/** The most calendar events one scout read takes; more than this and the rest of the window counts as taken. */
const val MAX_EVENTS_READ = 500

/**
 * Pure: [events] (the calendar's events overlapping the window, ordered by start, at most [cap] of them)
 * as busy spans. A full read may have lost later events, so it fails closed: everything from the last
 * start returned until [until] counts as taken.
 */
fun busyFrom(events: List<BusySpan>, until: Instant, cap: Int = MAX_EVENTS_READ): List<BusySpan> =
    if (events.size < cap) events else events + BusySpan(events.maxOf { it.start }, until)

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
 * last [SCOUT_BUDGET_WINDOW] (counted from `outing_idea.suggested_at`). Nothing else counts: a "not this time" leaves no pause and no cooldown, and the
 * idea it was about is simply never offered again (see [ideaRefusal]).
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
    /** When it was offered to the person (the post of its suggestion); null while it never was. */
    val suggestedAt: Instant? = null,
    /** The tool call (`run:toolUse`) that offered it, so a re-run of that call is let through. */
    val suggestedCall: String? = null,
) {
    fun matches(otherWhat: String, otherUrl: String?): Boolean =
        what.normalized() == otherWhat.normalized() || (url != null && otherUrl != null && url.trimEnd('/').equals(otherUrl.trim().trimEnd('/'), ignoreCase = true))

    /** Whether the idea can still happen in [slot]: open and, when bound to a window, overlapping it. */
    fun fits(slot: Slot): Boolean = state == IdeaState.OPEN && (from == null || to == null || (from < slot.end && to > slot.start))
}

/** The longest kind of outing the tool takes. */
const val MAX_KIND_CHARS = 40

/** Pure: the kinds of outing the person liked (used ideas) and declined, each kind once, newest first. Kinds only: no idea text. */
fun kindsSummary(ideas: List<SavedIdea>, max: Int = 10): Pair<List<String>, List<String>> {
    fun kinds(state: IdeaState) = ideas.filter { it.state == state }.sortedByDescending { it.createdAt }
        .mapNotNull { it.category?.trim()?.lowercase()?.take(MAX_KIND_CHARS)?.takeIf(String::isNotEmpty) }.distinct().take(max)
    return kinds(IdeaState.USED) to kinds(IdeaState.DECLINED)
}

/** The ref of the choices card of an outing suggestion: `outing:<idea id>`. */
const val OUTING_REF_PREFIX = "outing:"

/** Pure: the idea id in [ref] (`outing:<id>`), or null when it is not an outing ref. */
fun outingIdeaOf(ref: String?): String? =
    ref?.trim()?.takeIf { OUTING_REF.matches(it) }?.removePrefix(OUTING_REF_PREFIX)

private val OUTING_REF = Regex("^outing:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/**
 * Pure: why an outing suggestion for [idea] cannot be posted now, or null. It must be an open, saved idea in every turn
 * (a declined idea never comes back). It must not have been offered before, except by a re-run of the same call [call],
 * or when [reshow] (the person's own untainted turn: they asked, and may see an open idea again). Unless it is
 * a [personTurn] (the person started the turn and asked for something to do, tainted or not), fewer than [perWeek] other
 * ideas may have been offered unprompted in the last [SUGGESTION_WINDOW] ([offered]).
 */
fun suggestionRefusal(
    idea: SavedIdea?,
    offered: List<Instant>,
    now: Instant,
    call: String? = null,
    personTurn: Boolean = false,
    reshow: Boolean = false,
    perWeek: Int = SUGGESTIONS_PER_WEEK,
): String? = when {
    idea == null -> "No saved idea has that id. Save the outing with save_outing_idea first, then suggest it."
    idea.state != IdeaState.OPEN -> "\"${idea.what}\" is ${idea.state.wire}; only an open idea can be suggested."
    idea.suggestedAt != null -> if (reshow || (call != null && idea.suggestedCall == call)) null else
        "\"${idea.what}\" was already offered and is waiting for the person's answer. Suggest another idea or send nothing."
    !personTurn && offered.count { it >= now - SUGGESTION_WINDOW } >= perWeek -> "$perWeek outings were already suggested in the last 7 days. Send nothing more about outings now."
    else -> null
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
            "to find up to 3 events in that window. Its brief is built only from the city, the time window, their interests and the " +
            "\"kinds\" lines of list_outing_ideas (kinds the person liked or declined); names, the city, venues and interests are fine, but never a phone number, email address, street address, account or booking number or any other identifier (the server checks the brief). " +
            "Ask for title, time, place and page address of each. If the city or interests are unknown, skip the web search and use saved ideas only. " +
            "3. Nothing fits: send nothing. " +
            "4. Every suggestion is a saved idea: save_outing_idea for a found event first (what with no names, a kind, company in withContactId). " +
            "Then send_to_user with kind look_forward: a short text (\"Thursday evening is free. Jazz at the Blue Room with Sam Example?\"), " +
            "a sources card (the idea's source record id from list_outing_ideas, and the event page once a helper fetched it), " +
            "and a choices card with ref \"outing:<idea id>\" and the options Add to calendar and Not this time. " +
            "The server refuses it when the idea is not open or 2 outings were already suggested this week. " +
            "A tap comes back as the person's words plus an operator note \"Tap answers card ref outing:<idea id>\": that ref names the idea. " +
            "Add to calendar: create_event, and only when it reports done (if it was held for the person's approval, leave the idea open and say nothing more) update_outing_idea used. " +
            "Not this time: add nothing, update_outing_idea declined, and make no other plan for it. " +
            "Either way write_journal what kind of outing it was and the answer, so later briefs improve.",
    )
}

private const val MAX_CONTACTS_SHOWN = 15
