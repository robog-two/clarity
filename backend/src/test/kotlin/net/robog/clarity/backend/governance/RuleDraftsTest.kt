package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.JsonObject
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.AutonomyTier
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.RuleDraft
import net.robog.clarity.shared.governance.Urgency

class RuleDraftsTest : FunSpec({
    fun held(action: ProposedAction, status: HeldAction.Status = HeldAction.Status.HELD, draft: RuleDraft? = null) =
        HeldAction(HeldActionId("h"), T0, action, GuardVerdict(Decision.ASK, emptyList(), "asking"), "view", emptyList(), Urgency.NORMAL, status, ruleDraft = draft)
    val event = ProposedAction.CreateEvent("k", "add lunch", title = "Lunch", start = T0, end = T0 + 30.minutes)

    test("a waiting event gets a draft; browser steps, money, settled or drafted ones and 'never' kinds do not") {
        wantsRuleDraft(held(event), constitution()) shouldBe true
        wantsRuleDraft(held(event, HeldAction.Status.DECLINED), constitution()) shouldBe false
        wantsRuleDraft(held(event, draft = RuleDraft("x", "y")), constitution()) shouldBe false
        wantsRuleDraft(held(ProposedAction.ExternalTool("k", "Click", "browser", "click_by_uid", JsonObject(emptyMap()))), constitution()) shouldBe false
        wantsRuleDraft(held(event), constitution(autonomy = mapOf(ActionKind.CREATE_EVENT to AutonomyTier.NEVER))) shouldBe false
    }

    test("the model's answer is parsed from its JSON, and junk is refused") {
        GatewayRuleDrafter.parse("""Sure: {"text":" Adding events is fine: do it, then tell me. ","because":"it saves time"}""") shouldBe
            RuleDraft("Adding events is fine: do it, then tell me.", "it saves time")
        runCatching { GatewayRuleDrafter.parse("no json") }.isFailure shouldBe true
        runCatching { GatewayRuleDrafter.parse("""{"text":"","because":"x"}""") }.isFailure shouldBe true
    }

    test("the plain line names the kind") {
        fallbackRuleDraft(ActionKind.CREATE_EVENT).text shouldContain "create event"
    }
})
