package net.robog.clarity.backend.governance

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.noun.Handle

/**
 * How long an outbound action stays "the same one" for a repeat by the same run or thread: the
 * same email proposed again within this window gets the first one back instead of going out twice.
 */
val REPEAT_WINDOW: Duration = 24.hours

/** Pure: a LIKE pattern matching strings that start with [prefix] (its wildcards escaped). */
fun likePrefix(prefix: String): String = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/**
 * Pure: the run or thread an action's idempotency key belongs to, as a key prefix (`<runId>:`), or
 * null when the key has none. Keys of agent tools are `<runId>:<toolUseId>` or `<runId>:<hash>`.
 */
fun runKeyPrefix(idempotencyKey: String): String? =
    idempotencyKey.substringBefore(':', "").takeIf { it.isNotEmpty() }?.let { "$it:" }

private fun String.squashed() = trim().replace(Regex("\\s+"), " ")

private fun Handle.normalized() = "${kind.name}:${value.trim().lowercase()}"

private fun List<Handle>.normalized() = map { it.normalized() }.sorted().joinToString(",")

private fun EmailDraft.fingerprint() = listOf(
    "to=" + to.normalized(), "cc=" + cc.normalized(), "subject=" + subject.squashed().lowercase(),
    "body=" + body.squashed(), "reply=" + (inReplyTo?.value ?: ""), "from=" + (from?.normalized() ?: fromLabel.orEmpty()),
).joinToString("|")

/**
 * Pure: what makes an outbound action the same action, whatever its key and summary: its kind,
 * recipients (case and order do not matter), subject and body (runs of whitespace do not matter),
 * times and targets. Null for kinds that are never deduplicated by content (reading a document, an
 * external tool, browser and vault steps).
 */
fun actionFingerprint(action: ProposedAction): String? = when (action) {
    is ProposedAction.SendEmail -> "send_email|" + action.email.fingerprint()
    is ProposedAction.SaveDraft -> "save_draft|" + action.email.fingerprint()
    is ProposedAction.CreateEvent ->
        "create_event|${action.calendar.orEmpty()}|${action.title.squashed().lowercase()}|${action.start}|${action.end}|${action.attendees.normalized()}|${action.hold}"
    is ProposedAction.MoveEvent -> "move_event|${action.event.value}|${action.start}|${action.end}|${action.notifyAttendees}"
    is ProposedAction.RespondToInvite -> "respond_to_invite|${action.event.value}|${action.response}|${action.comment?.squashed().orEmpty()}"
    is ProposedAction.CreateReminder -> "create_reminder|${action.text.squashed().lowercase()}|${action.at}"
    is ProposedAction.SetDoNotDisturb -> "set_do_not_disturb|${action.until}|${action.allow.normalized()}"
    is ProposedAction.ReadDocument, is ProposedAction.ExternalTool, is ProposedAction.UseCredential, is ProposedAction.AskCode -> null
}
