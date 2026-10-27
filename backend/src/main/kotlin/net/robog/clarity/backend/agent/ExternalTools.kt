package net.robog.clarity.backend.agent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.harness.Description
import net.robog.clarity.harness.Tool
import net.robog.clarity.harness.ToolEffect
import net.robog.clarity.harness.ToolResult
import net.robog.clarity.harness.tool
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.feed.ContextItem
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.time.LocalTimes

/** The inputs every external tool shares: why, and what prompted it. */
private const val WHY = "One or two sentences to the person, first person: what you think and why you want to do this. Shown on the held item."
private const val CONTEXT = "What prompted this (e.g. the message being replied to), shown to the person."

/** A time input on the person's clock ([zone]); a plain date is not enough here. */
private fun instant(label: String, raw: String, zone: TimeZone): Instant =
    readToolTime(label, raw, zone).fold({ throw IllegalArgumentException(it) }, { it })

/**
 * Pure builders: tool input to [ProposedAction]. Throw [IllegalArgumentException] with a message for the model.
 * Times are read and summarized on the clock of the person's home zone [TimeZone] ([LocalTimes]).
 */
object ProposedActions {
    fun draft(key: String, send: Boolean, input: ExternalTools.EmailInput): ProposedAction {
        require(input.to.isNotEmpty()) { "`to` needs at least one recipient" }
        val email = EmailDraft(
            to = input.to.map(::parseHandle),
            cc = input.cc.map(::parseHandle),
            subject = input.subject,
            body = input.body,
            inReplyTo = input.inReplyTo?.let(::EntityId),
        )
        val who = email.to.joinToString { it.displayName ?: it.value }
        return if (send) ProposedAction.SendEmail(key, "Send \"${input.subject}\" to $who", email)
        else ProposedAction.SaveDraft(key, "Draft \"${input.subject}\" to $who", email)
    }

    fun createEvent(key: String, input: ExternalTools.CreateEventInput, zone: TimeZone): ProposedAction {
        val start = instant("start", input.start, zone)
        val end = instant("end", input.end, zone)
        require(end > start) { "`end` must be after `start`" }
        return ProposedAction.CreateEvent(
            idempotencyKey = key,
            summary = (if (input.attendees.isEmpty()) "Hold " else "Invite to ") + "\"${input.title}\" ${LocalTimes.formatRange(start, end, zone)}",
            calendar = input.calendar,
            title = input.title,
            start = start,
            end = end,
            attendees = input.attendees.map(::parseHandle),
            hold = input.attendees.isEmpty() && input.hold,
        )
    }

    fun moveEvent(key: String, input: ExternalTools.MoveEventInput, zone: TimeZone): ProposedAction {
        val start = instant("start", input.start, zone)
        val end = instant("end", input.end, zone)
        require(end > start) { "`end` must be after `start`" }
        return ProposedAction.MoveEvent(key, "Move event ${input.eventId} to ${LocalTimes.formatRange(start, end, zone)}", EntityId(input.eventId), start, end, input.notifyAttendees)
    }

    fun respond(key: String, input: ExternalTools.RespondInput): ProposedAction =
        ProposedAction.RespondToInvite(
            key, "${input.response.name.lowercase().replaceFirstChar { it.uppercase() }} invite ${input.eventId}",
            EntityId(input.eventId), input.response, input.comment,
        )

    fun reminder(key: String, input: ExternalTools.ReminderInput, zone: TimeZone): ProposedAction {
        val at = instant("at", input.at, zone)
        return ProposedAction.CreateReminder(key, "Remind at ${LocalTimes.format(at, zone)}: ${input.text.take(80)}", input.text, at)
    }

    fun doNotDisturb(key: String, input: ExternalTools.DndInput, zone: TimeZone): ProposedAction {
        val until = instant("until", input.until, zone)
        return ProposedAction.SetDoNotDisturb(key, "Do not disturb until ${LocalTimes.format(until, zone)}", until, input.allow.map(::parseHandle))
    }
}

/**
 * Domain tools with effects outside Clarity. They never act: each builds a [ProposedAction] and
 * hands it to the [ActionGate], whose guard decides to queue it, hold it for the person, or
 * block it. The tool returns the gate's text, which tells the model what happened (and not to retry
 * a held action). The idempotency key comes from the tool call, so a replayed call is not repeated.
 */
@Inject
@SingleIn(AppScope::class)
class ExternalTools(
    private val gate: ActionGate,
    private val settings: SettingsProvider = SettingsProvider { net.robog.clarity.shared.session.Settings() },
) {

    @Serializable
    class EmailInput(
        @Description("Recipients: addresses, or \"Name <address>\".") val to: List<String>,
        val subject: String,
        val body: String,
        val cc: List<String> = emptyList(),
        @Description("Entity id of the message this replies to (from list_messages), for threading.") val inReplyTo: String? = null,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class CreateEventInput(
        val title: String,
        @Description("Start. $LOCAL_TIME_HINT") val start: String,
        @Description("End, local time like `start`.") val end: String,
        val calendar: String? = null,
        @Description("Invitees. Leave empty for a private block on the person's own calendar.") val attendees: List<String> = emptyList(),
        @Description("True for a private focus/hold block (only without attendees).") val hold: Boolean = true,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class MoveEventInput(
        @Description("Event id from list_calendar_events") val eventId: String,
        @Description("New start. $LOCAL_TIME_HINT") val start: String,
        @Description("New end, local time like `start`.") val end: String,
        @Description("True if other people are on the event and should be told.") val notifyAttendees: Boolean,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class RespondInput(
        @Description("Event id from list_calendar_events") val eventId: String,
        val response: InviteResponse,
        val comment: String? = null,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class ReminderInput(
        val text: String,
        @Description("When. $LOCAL_TIME_HINT") val at: String,
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    @Serializable
    class DndInput(
        @Description("When do-not-disturb ends. $LOCAL_TIME_HINT") val until: String,
        @Description("People still allowed through (addresses or numbers).") val allow: List<String> = emptyList(),
        @Description(WHY) val reason: String,
        @Description(CONTEXT) val context: List<ContextItem> = emptyList(),
        val urgency: Urgency = Urgency.NORMAL,
    )

    private suspend inline fun <reified I : Any> propose(
        scope: RunScope,
        name: String,
        input: I,
        reason: String,
        context: List<ContextItem>,
        urgency: Urgency,
        noinline build: (String, TimeZone) -> ProposedAction,
    ): ToolResult {
        val zone = settings.homeZone()
        val action = try {
            build(idempotencyKey(scope, name, ClarityJson.encodeToString(input)), zone)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error(e.message ?: "invalid input")
        }
        return ToolResult.Ok(gate.propose(action, reason, context, scope.provenance(), urgency).text)
    }

    fun saveDraft(scope: RunScope): Tool = tool<EmailInput>(
        name = "save_draft",
        description = "Save an email draft in the person's mail account (not sent). Prefer this to send_email unless they asked you to send.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "save_draft", i, i.reason, i.context, i.urgency) { k, _ -> ProposedActions.draft(k, send = false, input = i) } }

    fun sendEmail(scope: RunScope): Tool = tool<EmailInput>(
        name = "send_email",
        description = "Send an email as the person. Goes through the guard; usually held for their OK. Result tells you whether it was queued or held.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "send_email", i, i.reason, i.context, i.urgency) { k, _ -> ProposedActions.draft(k, send = true, input = i) } }

    fun createEvent(scope: RunScope): Tool = tool<CreateEventInput>(
        name = "create_event",
        description = "Create a calendar event or a private hold block.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "create_event", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.createEvent(k, i, zone) } }

    fun moveEvent(scope: RunScope): Tool = tool<MoveEventInput>(
        name = "move_event",
        description = "Move a calendar event to a new time. Events with other attendees need `notifyAttendees`.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "move_event", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.moveEvent(k, i, zone) } }

    fun respondToInvite(scope: RunScope): Tool = tool<RespondInput>(
        name = "respond_to_invite",
        description = "Accept, decline or tentatively accept a calendar invitation.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "respond_to_invite", i, i.reason, i.context, i.urgency) { k, _ -> ProposedActions.respond(k, i) } }

    fun createReminder(scope: RunScope): Tool = tool<ReminderInput>(
        name = "create_reminder",
        description = "Create a reminder for the person at a time (shows up as a short event with an alarm on their calendar). " +
            "It only alerts them; to check in with them yourself, use nudge.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "create_reminder", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.reminder(k, i, zone) } }

    fun setDoNotDisturb(scope: RunScope): Tool = tool<DndInput>(
        name = "set_do_not_disturb",
        description = "Turn on do-not-disturb on the person's phone until a time, optionally letting some people through.",
        effect = ToolEffect.EXTERNAL,
        json = ClarityJson,
    ) { i -> propose(scope, "set_do_not_disturb", i, i.reason, i.context, i.urgency) { k, zone -> ProposedActions.doNotDisturb(k, i, zone) } }

    fun all(scope: RunScope): List<Tool> = listOf(
        saveDraft(scope), sendEmail(scope), createEvent(scope), moveEvent(scope), respondToInvite(scope),
        createReminder(scope), setDoNotDisturb(scope),
    )
}
