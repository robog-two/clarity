package net.robog.clarity.backend.feed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import net.robog.clarity.backend.agent.InboxItem
import net.robog.clarity.backend.agent.Priority
import net.robog.clarity.backend.agent.inboxFor
import net.robog.clarity.backend.governance.ActionResultEvent
import net.robog.clarity.backend.governance.GovernanceEvent
import net.robog.clarity.shared.ConnectorId
import net.robog.clarity.shared.action.ActionId
import net.robog.clarity.shared.action.ActionOutcome
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.FeedEvent
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId
import net.robog.clarity.shared.governance.ActionKind
import net.robog.clarity.shared.governance.ActivityEntry
import net.robog.clarity.shared.governance.Decision
import net.robog.clarity.shared.governance.EmailDraft
import net.robog.clarity.shared.governance.GuardVerdict
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.ProposedAction
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle

private val at = Instant.parse("2026-10-25T10:00:00Z")

private fun item(seq: Long, vararg cards: Card) = FeedItem(FeedItemId("i$seq"), seq, at, FeedItem.Author.AGENT, cards = cards.toList())

private fun held(status: HeldAction.Status) = HeldAction(
    HeldActionId("h1"), at,
    ProposedAction.SendEmail("k", "Send reply to Dana", EmailDraft(to = listOf(Handle.email("d@x.com")), subject = "s", body = "b")),
    GuardVerdict(Decision.ASK, emptyList(), "asking"), "I think so", emptyList(), Urgency.NORMAL, status,
)

class FeedPureTest : FunSpec({
    test("evening summary: nothing, one, many") {
        eveningSummaryCards(emptyList()).shouldBeEmpty()
        val one = eveningSummaryCards(listOf(item(1, Card.Text("Dana replied about the contract."))))
        one[0] shouldBe Card.Text("One thing waited for tonight.")
        one[1].shouldBeInstanceOf<Card.Chips>().items shouldBe listOf(Chip("Dana replied about the contract."))
        val many = eveningSummaryCards(listOf(item(1, Card.Note("a")), item(2, Card.Text("b\nsecond line"))))
        many[0] shouldBe Card.Text("2 things waited for tonight.")
        many[1].shouldBeInstanceOf<Card.Chips>().items.map { it.text } shouldBe listOf("a", "b")
    }

    test("headlines are one short line") {
        headline(item(1, Card.Text("x".repeat(500)))).length shouldBe 140
        headline(item(1, Card.Proposal(HeldActionId("h")))) shouldBe "Something to look at"
    }

    test("governance events reach the app as feed events") {
        feedEventFor(GovernanceEvent.HeldChanged(held(HeldAction.Status.HELD))) shouldBe FeedEvent.HeldChanged(HeldActionId("h1"))
        feedEventFor(GovernanceEvent.ConstitutionChanged(5)) shouldBe FeedEvent.ConstitutionChanged(5)
        feedEventFor(GovernanceEvent.Activity(ActivityEntry(at, "x", ActionKind.SAVE_DRAFT, true))) shouldBe null
    }

    test("only settled held actions wake the lead") {
        inboxFor(GovernanceEvent.HeldChanged(held(HeldAction.Status.HELD))) shouldBe null
        inboxFor(GovernanceEvent.HeldChanged(held(HeldAction.Status.BLOCKED))) shouldBe null
        val (declined, key) = inboxFor(GovernanceEvent.HeldChanged(held(HeldAction.Status.DECLINED)))!!
        declined shouldBe InboxItem.HeldDecided("h1", "declined", "Send reply to Dana", Priority.LOW)
        key shouldBe "held:h1:declined"
        // a saved rule is in the lead's prompt from its next turn; an approved send reports through its result
        inboxFor(GovernanceEvent.ConstitutionChanged(5)) shouldBe null
        inboxFor(GovernanceEvent.HeldChanged(held(HeldAction.Status.APPROVED))) shouldBe null
    }

    test("a decision a helper is waiting on does not reach the lead; an unwaited browser approval does") {
        val step = held(HeldAction.Status.APPROVED).copy(
            action = ProposedAction.ExternalTool("k", "Click button \"Sign in\" on www.ulta.com/u/login", "browser", "click_by_uid", kotlinx.serialization.json.JsonObject(emptyMap())),
        )
        inboxFor(GovernanceEvent.HeldChanged(step)) { "helper-1" } shouldBe null
        inboxFor(GovernanceEvent.HeldChanged(step))!!.first.priority shouldBe Priority.NORMAL
    }

    test("action results: done and failed") {
        val action = held(HeldAction.Status.APPROVED).action
        val done = inboxFor(ActionResultEvent(ActionId("a1"), ConnectorId("mail"), null, action, ActionOutcome.Done()))
        done.first.shouldBeInstanceOf<InboxItem.ActionResult>().done shouldBe true
        done.second shouldBe "action:a1"
        val failed = inboxFor(ActionResultEvent(ActionId("a2"), ConnectorId("mail"), null, action, ActionOutcome.Failed("mailbox full", false)))
        failed.first.shouldBeInstanceOf<InboxItem.ActionResult>().detail shouldBe "mailbox full"
        failed.first.priority shouldBe Priority.NORMAL
    }

    test("sse bookkeeping") {
        eventName(FeedEvent.Busy(true)) shouldBe "busy"
        eventName(FeedEvent.ItemAppended(item(1))) shouldBe "item"
        isNewerThan(FeedEvent.ItemAppended(item(5)), 5) shouldBe false
        isNewerThan(FeedEvent.ItemAppended(item(6)), 5) shouldBe true
        isNewerThan(FeedEvent.ItemFinal(item(4)), 5) shouldBe false
        isNewerThan(FeedEvent.Busy(false), 99) shouldBe true
    }
})
