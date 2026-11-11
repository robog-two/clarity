package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.ui.constitution.autonomySummary
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Constitution

class AutonomySummaryTest {
    private fun constitution(vararg autonomy: Pair<ActionKind, AutonomyTier>) =
        Constitution(version = 1, lines = emptyList(), autonomy = mapOf(*autonomy), updatedAt = T0)

    @Test fun changingAndCancellingEventsAreListedOnTheirOwn() {
        val summary = autonomySummary(constitution(ActionKind.UPDATE_EVENT to AutonomyTier.DO_THEN_TELL))
        assertEquals(listOf("Change events"), summary.alone.filter { it.endsWith("events") })
        assertEquals(listOf("Cancel events"), summary.afterReview.filter { it.startsWith("Cancel") })
    }

    @Test fun movingIsNotListedButStillLimitsChangingAndCancelling() {
        val summary = autonomySummary(
            constitution(
                ActionKind.MOVE_EVENT to AutonomyTier.ASK_FIRST,
                ActionKind.UPDATE_EVENT to AutonomyTier.DO_THEN_TELL,
                ActionKind.CANCEL_EVENT to AutonomyTier.DO_THEN_TELL,
            ),
        )
        assertFalse("Move events" in summary.alone + summary.afterReview)
        assertFalse("Change events" in summary.alone)
        assertFalse("Cancel events" in summary.alone)
    }
}
