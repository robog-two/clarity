package net.robog.clarity.shared.governance

import kotlinx.serialization.Serializable

/**
 * `POST /api/v1/constitution/amendments`: save a proposed change. The server assigns the
 * [AmendmentId] and the `proposed` status; the amendment takes effect (and bumps the constitution
 * version) only when the person saves it with `POST /api/v1/constitution/amendments/{id}/save`.
 */
@Serializable
data class ProposeAmendment(
    /** The line to replace, or null to add [proposed] as a new line. */
    val lineId: LineId? = null,
    val proposed: Line,
    val reason: String,
    val fromHeld: HeldActionId? = null,
    /** Also move one kind of action to another tier; the check before every action reads this. */
    val autonomy: AutonomyChange? = null,
)
