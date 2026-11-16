package net.robog.clarity.backend.governance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import net.robog.clarity.backend.agent.choicesCardsProblem
import net.robog.clarity.backend.agent.choicesProblem
import net.robog.clarity.backend.agent.needsAnswer
import net.robog.clarity.backend.agent.needsPerson
import net.robog.clarity.backend.agent.resolveKind
import net.robog.clarity.backend.feed.answerChoices
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.MessageKind
import net.robog.clarity.shared.governance.Constitution
import net.robog.clarity.shared.governance.Person
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.noun.Handle
import net.robog.clarity.shared.session.Settings

private val dana = Handle.email("dana@example.com")
private val stranger = Handle.email("news@example.com")

private fun consti(protected: List<net.robog.clarity.shared.governance.TimeBlock> = emptyList(), people: List<Person> = emptyList()) =
    Constitution(version = 1, lines = emptyList(), getsThrough = people, protectedTimes = protected, updatedAt = T0)

private val settings = Settings()

/** Wednesday 14:00 UTC, outside any protected time unless a test adds one. */
private val now = Instant.parse("2026-03-04T14:00:00Z")

private fun delivery(
    kind: MessageKind,
    urgency: Urgency = Urgency.NORMAL,
    dueBy: Instant? = null,
    people: List<Handle> = emptyList(),
    constitution: Constitution = consti(),
    s: Settings = settings,
    at: Instant = now,
    pushedToday: Int = 0,
    actionable: Boolean = false,
    needsPerson: Boolean = false,
) = deliveryOf(kind, urgency, dueBy, people, constitution, s, at, pushedToday, actionable, needsPerson)

class DeliveryPolicyTest : FunSpec({

    test("a reply shows in the app, notifies only if it is closed, and is never counted") {
        delivery(MessageKind.REPLY) shouldBe Delivery(NotifyDecision.Reply, counts = false)
        // even with the budget spent and in protected time
        val deepWork = block("deep work", setOf(3), "09:00", "18:00")
        delivery(MessageKind.REPLY, pushedToday = 99, constitution = consti(listOf(deepWork))) shouldBe Delivery(NotifyDecision.Reply, counts = false)
    }

    test("a check-in and a look-forward push, and count") {
        delivery(MessageKind.CHECK_IN) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
        delivery(MessageKind.LOOK_FORWARD) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
    }

    test("a finding with no due time waits for the evening summary") {
        delivery(MessageKind.FINDING) shouldBe Delivery(NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z")), counts = false)
        delivery(MessageKind.FINDING, dueBy = now + 25.hours) shouldBe Delivery(NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z")), counts = false)
    }

    test("a finding due within 24 hours (or already past) pushes and counts") {
        delivery(MessageKind.FINDING, dueBy = now + 24.hours) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
        delivery(MessageKind.FINDING, dueBy = now + 3.hours) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
        delivery(MessageKind.FINDING, dueBy = now - 2.hours) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
    }

    test("a finding from someone who gets through pushes and counts") {
        val c = consti(people = listOf(Person("Dana", listOf(dana))))
        delivery(MessageKind.FINDING, people = listOf(dana), constitution = c) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
        delivery(MessageKind.FINDING, people = listOf(stranger), constitution = c).decision shouldBe NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z"))
    }

    test("urgent keeps today's behaviour for every kind but a reply, and is not counted") {
        for (kind in MessageKind.entries.filter { it != MessageKind.REPLY }) {
            delivery(kind, Urgency.URGENT, pushedToday = 99) shouldBe Delivery(NotifyDecision.PushNow, counts = false)
        }
        val deepWork = block("deep work", setOf(3), "09:00", "18:00")
        delivery(MessageKind.FINDING, Urgency.URGENT, constitution = consti(listOf(deepWork))) shouldBe
            Delivery(NotifyDecision.HoldUntil(Instant.parse("2026-03-04T18:00:00Z")), counts = false)
    }

    test("protected time holds a counted push unless the sender gets through") {
        val deepWork = block("deep work", setOf(3), "09:00", "18:00")
        val c = consti(listOf(deepWork), listOf(Person("Dana", listOf(dana))))
        delivery(MessageKind.CHECK_IN, constitution = c) shouldBe Delivery(NotifyDecision.HoldUntil(Instant.parse("2026-03-04T18:00:00Z")), counts = true)
        delivery(MessageKind.FINDING, people = listOf(dana), constitution = c) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
    }

    test("the budget: once the limit is reached a push is shown in the app without one") {
        delivery(MessageKind.CHECK_IN, pushedToday = 4) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
        delivery(MessageKind.CHECK_IN, pushedToday = 5) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false, budgetSpent = true)
        delivery(MessageKind.FINDING, dueBy = now + 1.hours, pushedToday = 6) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false, budgetSpent = true)
        delivery(MessageKind.LOOK_FORWARD, s = settings.copy(pushesPerDay = 1), pushedToday = 1).budgetSpent shouldBe true
        delivery(MessageKind.LOOK_FORWARD, s = settings.copy(pushesPerDay = 10), pushedToday = 9).counts shouldBe true
    }

    test("the budget does not touch findings that wait for the evening, replies or urgent items") {
        delivery(MessageKind.FINDING, pushedToday = 5).decision shouldBe NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z"))
        delivery(MessageKind.REPLY, pushedToday = 5).decision shouldBe NotifyDecision.Reply
        delivery(MessageKind.FINDING, Urgency.URGENT, pushedToday = 5).decision shouldBe NotifyDecision.PushNow
    }

    test("push turned off means no push for any kind") {
        val off = settings.copy(pushUrgent = false)
        delivery(MessageKind.CHECK_IN, s = off) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
        delivery(MessageKind.FINDING, dueBy = now + 1.hours, s = off) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
    }

    test("an explicit batch urgency waits for the evening whatever the kind, unless it is a reply") {
        delivery(MessageKind.CHECK_IN, Urgency.BATCH).decision shouldBe NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z"))
        delivery(MessageKind.FINDING, Urgency.BATCH, dueBy = now + 1.hours).decision shouldBe NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z"))
        delivery(MessageKind.REPLY, Urgency.BATCH).decision shouldBe NotifyDecision.Reply
    }

    test("a reply wins over urgency: always a reply, even with push off or in protected time") {
        val deepWork = block("deep work", setOf(3), "09:00", "18:00")
        delivery(MessageKind.REPLY, Urgency.URGENT, s = settings.copy(pushUrgent = false)) shouldBe Delivery(NotifyDecision.Reply, counts = false)
        delivery(MessageKind.REPLY, Urgency.URGENT, constitution = consti(listOf(deepWork)), pushedToday = 99) shouldBe Delivery(NotifyDecision.Reply, counts = false)
    }

    test("an approval or a login follows the urgent rules whatever its kind, and is not counted") {
        val deepWork = block("deep work", setOf(3), "09:00", "18:00")
        val c = consti(listOf(deepWork), listOf(Person("Dana", listOf(dana))))
        delivery(MessageKind.FINDING, needsPerson = true) shouldBe Delivery(NotifyDecision.PushNow, counts = false)
        delivery(MessageKind.FINDING, needsPerson = true, pushedToday = 99) shouldBe Delivery(NotifyDecision.PushNow, counts = false)
        delivery(MessageKind.FINDING, Urgency.BATCH, needsPerson = true) shouldBe Delivery(NotifyDecision.PushNow, counts = false)
        delivery(MessageKind.CHECK_IN, needsPerson = true, constitution = c) shouldBe Delivery(NotifyDecision.HoldUntil(Instant.parse("2026-03-04T18:00:00Z")), counts = false)
        delivery(MessageKind.FINDING, needsPerson = true, constitution = c, people = listOf(dana)) shouldBe Delivery(NotifyDecision.PushNow, counts = false)
        delivery(MessageKind.FINDING, needsPerson = true, s = settings.copy(pushUrgent = false)) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
        // a choices card keeps the rule of its kind
        delivery(MessageKind.FINDING, actionable = true) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
        needsPerson(Card.Proposal(net.robog.clarity.shared.governance.HeldActionId("h"))) shouldBe true
        needsPerson(Card.CredentialRequest(net.robog.clarity.shared.vault.CredentialRequestId("r"))) shouldBe true
        needsPerson(Card.Choices(null, listOf(Choice("A", "a")))) shouldBe false
    }

    test("a post inside a session: a reply is a reply whatever the urgency, the rest follows notifyDecision, never counted") {
        val deepWork = block("deep work", setOf(3), "09:00", "18:00")
        val c = consti(listOf(deepWork))
        sessionDelivery(MessageKind.REPLY, Urgency.NORMAL, emptyList(), c, settings, now) shouldBe Delivery(NotifyDecision.Reply, counts = false)
        sessionDelivery(MessageKind.REPLY, Urgency.URGENT, emptyList(), c, settings, now) shouldBe Delivery(NotifyDecision.Reply, counts = false)
        sessionDelivery(MessageKind.FINDING, Urgency.NORMAL, emptyList(), c, settings, now) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
        sessionDelivery(MessageKind.CHECK_IN, Urgency.URGENT, emptyList(), c, settings, now) shouldBe
            Delivery(NotifyDecision.HoldUntil(Instant.parse("2026-03-04T18:00:00Z")), counts = false)
        sessionDelivery(MessageKind.FINDING, Urgency.BATCH, emptyList(), consti(), settings, now).decision shouldBe
            NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z"))
    }

    test("something that asks for an answer is never held for the evening") {
        delivery(MessageKind.FINDING, actionable = true) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
        delivery(MessageKind.FINDING, dueBy = now + 1.hours, actionable = true) shouldBe Delivery(NotifyDecision.PushNow, counts = true)
        delivery(MessageKind.FINDING, Urgency.BATCH, actionable = true).decision shouldBe NotifyDecision.EveningBatch(Instant.parse("2026-03-04T18:00:00Z"))
        needsAnswer(Card.Proposal(net.robog.clarity.shared.governance.HeldActionId("h"))) shouldBe true
        needsAnswer(Card.Choices(null, listOf(Choice("A", "a")))) shouldBe true
        needsAnswer(Card.Text("hi")) shouldBe false
    }

    test("with batching turned off a quiet finding is shown in the app") {
        delivery(MessageKind.FINDING, s = settings.copy(batchLowUrgency = false)) shouldBe Delivery(NotifyDecision.ShowInApp, counts = false)
    }

    test("pushBudget counts what is left and clamps the limit to 1..10") {
        pushBudget(0, 5) shouldBe 5
        pushBudget(4, 5) shouldBe 1
        pushBudget(5, 5) shouldBe 0
        pushBudget(9, 5) shouldBe 0
        pushBudget(0, 0) shouldBe 1
        pushBudget(0, 99) shouldBe 10
    }

    test("the server decides what is a reply") {
        resolveKind(MessageKind.FINDING, personWrote = true) shouldBe MessageKind.REPLY
        resolveKind(MessageKind.CHECK_IN, personWrote = true) shouldBe MessageKind.REPLY
        resolveKind(MessageKind.REPLY, personWrote = true) shouldBe MessageKind.REPLY
        resolveKind(MessageKind.REPLY, personWrote = false) shouldBe MessageKind.FINDING
        resolveKind(MessageKind.CHECK_IN, personWrote = false) shouldBe MessageKind.CHECK_IN
        resolveKind(MessageKind.LOOK_FORWARD, personWrote = false) shouldBe MessageKind.LOOK_FORWARD
        resolveKind(MessageKind.FINDING, personWrote = false) shouldBe MessageKind.FINDING
    }

    test("a choices card needs usable options") {
        val ok = Card.Choices("Ready?", listOf(Choice("Yes", "yes"), Choice("No", "no")))
        choicesProblem(ok) shouldBe null
        (choicesProblem(Card.Choices(null, emptyList())) != null) shouldBe true
        (choicesProblem(Card.Choices(null, listOf(Choice(" ", "x")))) != null) shouldBe true
        (choicesProblem(Card.Choices(null, listOf(Choice("A", "same"), Choice("B", "same")))) != null) shouldBe true
        (choicesProblem(Card.Choices(null, List(5) { Choice("L$it", "r$it") })) != null) shouldBe true
        choicesCardsProblem(listOf(ok)) shouldBe null
        (choicesCardsProblem(listOf(ok, ok)) != null) shouldBe true
        (choicesCardsProblem(listOf(Card.Text("x"), Card.Choices(null, emptyList()))) != null) shouldBe true
    }

    test("the first tap answers a choices card; a second, or a typed reply, changes nothing") {
        val cards = listOf(Card.Text("Hi"), Card.Choices("Ready?", listOf(Choice("Yes", "yes"), Choice("No", "no"))))
        val answered = answerChoices(cards, "no", "client-1")!!
        (answered[1] as Card.Choices).chosen shouldBe "no"
        (answered[1] as Card.Choices).answeredBy shouldBe "client-1"
        answered[0] shouldBe cards[0]
        answerChoices(answered, "yes") shouldBe null
        answerChoices(cards, "maybe") shouldBe null
        answerChoices(listOf(Card.Text("Hi")), "yes") shouldBe null
    }
})
