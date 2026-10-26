package net.robog.clarity.app.ui.held

import dev.zacsweers.metro.Inject
import kotlin.uuid.Uuid
import net.robog.clarity.app.data.AmendmentsRepository
import net.robog.clarity.app.data.ApiResult
import net.robog.clarity.shared.governance.AutonomyChange
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.Line
import net.robog.clarity.shared.governance.LineId
import net.robog.clarity.shared.governance.NEVER_AUTOMATIC
import net.robog.clarity.shared.governance.ProposeAmendment
import net.robog.clarity.shared.governance.RuleDraft
import net.robog.clarity.shared.governance.Section

/** Pure: whether "approve automatically from now on" is offered for [held]. */
fun canAlwaysAllow(held: HeldAction): Boolean = held.waiting && held.action.kind !in NEVER_AUTOMATIC

/** Pure: the rule the editor opens with: the server's draft, or a plain line until it arrives. */
fun startingRule(held: HeldAction): RuleDraft = held.ruleDraft ?: RuleDraft(
    "\"${held.action.kind.name.lowercase().replace('_', ' ')}\" is okay without asking: do it, then tell me.",
    "I’d rather not approve these one by one.",
)

/** Pure: the amendment that adds [text] and lets the agent do [held]'s kind alone, telling the person after. */
fun alwaysAllowAmendment(held: HeldAction, text: String, because: String, lineId: String): ProposeAmendment = ProposeAmendment(
    lineId = null,
    proposed = Line(LineId(lineId), Section.AUTONOMY, text.trim(), because = because.trim().ifEmpty { null }),
    reason = "Approve \"${held.action.kind.name.lowercase().replace('_', ' ')}\" automatically from now on.",
    autonomy = AutonomyChange(held.action.kind, AutonomyTier.DO_THEN_TELL),
)

/** Saves the "approve automatically from now on" rule (proposed, then saved at once). */
@Inject
class AlwaysAllow(private val amendments: AmendmentsRepository) {
    suspend fun save(held: HeldAction, text: String, because: String): ApiResult<Constitution> =
        when (val proposed = amendments.propose(alwaysAllowAmendment(held, text, because, "amended-" + Uuid.random().toString().take(8)))) {
            is ApiResult.Success -> amendments.save(proposed.value.id)
            is ApiResult.Failure -> proposed
        }
}
