package net.robog.clarity.backend.governance

import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.InviteResponse
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Provenance
import net.robog.clarity.shared.governance.Section
import net.robog.clarity.shared.governance.TimeBlock
import net.robog.clarity.shared.noun.Handle

val T0: Instant = Instant.parse("2026-03-04T12:00:00Z") // a Wednesday

fun constitution(
    tier: AutonomyTier? = null,
    protectedTimes: List<TimeBlock> = emptyList(),
    neverStore: List<String> = emptyList(),
    autonomy: Map<ActionKind, AutonomyTier>? = null,
    lines: List<Line> = emptyList(),
) = Constitution(
    version = 1,
    lines = lines,
    autonomy = autonomy ?: tier?.let { t -> ActionKind.entries.associateWith { t } } ?: emptyMap(),
    protectedTimes = protectedTimes,
    neverStore = neverStore,
    updatedAt = T0,
)

fun line(id: String, text: String = "text $id", hard: Boolean = false) =
    Line(LineId(id), if (hard) Section.HARD_LINES else Section.VALUES, text, because = "because", hard = hard)

fun trusted(requestedByUser: Boolean = false) = Provenance("run-1", untrustedInput = false, requestedByUser = requestedByUser)
val untrusted = Provenance("run-1", untrustedInput = true)

fun block(label: String, days: Set<Int>, start: String, end: String) =
    TimeBlock(label, days, LocalTime.parse(start), LocalTime.parse(end))

fun email(vararg to: String) = EmailDraft(to = to.map { Handle.email(it) }, subject = "Hi", body = "Body")

/** A benign instance of every kind: one recipient, a private hold, no side effects on other people. */
fun sample(kind: ActionKind, key: String = "k-${kind.name}"): ProposedAction = when (kind) {
    ActionKind.SAVE_DRAFT -> ProposedAction.SaveDraft(key, "draft reply", email("a@example.com"))
    ActionKind.SEND_EMAIL -> ProposedAction.SendEmail(key, "reply to Dana", email("a@example.com"))
    ActionKind.CREATE_EVENT -> ProposedAction.CreateEvent(key, "hold time", null, "Focus", T0, T0 + kotlin.time.Duration.parse("1h"))
    ActionKind.MOVE_EVENT -> ProposedAction.MoveEvent(key, "move my own event", EntityId("e1"), T0, T0 + kotlin.time.Duration.parse("1h"), notifyAttendees = false)
    ActionKind.RESPOND_TO_INVITE -> ProposedAction.RespondToInvite(key, "accept", EntityId("e1"), InviteResponse.ACCEPT)
    ActionKind.CREATE_REMINDER -> ProposedAction.CreateReminder(key, "remind me", "call mum", T0)
    ActionKind.SET_DO_NOT_DISTURB -> ProposedAction.SetDoNotDisturb(key, "dnd", T0 + kotlin.time.Duration.parse("1h"))
    ActionKind.READ_DOCUMENT -> ProposedAction.ReadDocument(key, "read it", EntityId("d1"))
    ActionKind.PAYMENT -> ProposedAction.ExternalTool(key, "pay the invoice", "bank", "pay", JsonObject(emptyMap()))
    ActionKind.EXTERNAL_TOOL -> ProposedAction.ExternalTool(key, "submit form", "browser", "click", JsonObject(emptyMap()))
    ActionKind.USE_CREDENTIAL -> ProposedAction.UseCredential(key, "use bank login", net.robog.clarity.shared.vault.CredentialId("c1"), "bank.com", listOf("username", "password"))
    ActionKind.TWO_FACTOR_CODE -> ProposedAction.AskCode(key, "type the code", "bank.com", "login")
}
