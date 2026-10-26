package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.robog.clarity.app.testutil.T0
import net.robog.clarity.app.ui.cards.approveLabel
import net.robog.clarity.app.ui.cards.heldStatusLabel
import net.robog.clarity.app.ui.cards.proposalKicker
import net.robog.clarity.app.ui.cards.taskStatusLabel
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.feed.AgentTask
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency

class CardPresentationTest {
    @Test fun taskStatusWords() {
        assertEquals("waits for you", taskStatusLabel(AgentTask.Status.WAITING_FOR_YOU))
        assertEquals("done", taskStatusLabel(AgentTask.Status.DONE))
        assertEquals(AgentTask.Status.entries.size, AgentTask.Status.entries.map(::taskStatusLabel).toSet().size)
    }

    @Test fun approveButtonSaysWhatItDoes() {
        assertEquals("send update", approveLabel(ActionKind.MOVE_EVENT))
        assertEquals("send", approveLabel(ActionKind.SEND_EMAIL))
        assertEquals("ok", approveLabel(ActionKind.READ_DOCUMENT))
    }

    @Test fun proposalsShowButtonsOnlyWhileHeld() {
        assertNull(heldStatusLabel(HeldAction.Status.HELD))
        assertEquals("done", heldStatusLabel(HeldAction.Status.EXECUTED))
        assertEquals("not now", heldStatusLabel(HeldAction.Status.DECLINED))
        assertEquals("blocked by your lines", heldStatusLabel(HeldAction.Status.BLOCKED))
    }

    @Test fun kickerUsesTheActionSummary() {
        val held = HeldAction(
            HeldActionId("h"), T0,
            ProposedAction.MoveEvent("k", "sends Dana an update", EntityId("e"), T0, T0, true),
            GuardVerdict(Decision.ASK, rationale = "r"), "view", urgency = Urgency.NORMAL, status = HeldAction.Status.HELD,
        )
        assertEquals("needs your OK · sends Dana an update", proposalKicker(held))
    }
}
