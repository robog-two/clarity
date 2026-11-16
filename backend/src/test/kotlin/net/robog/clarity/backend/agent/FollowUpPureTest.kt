package net.robog.clarity.backend.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlinx.serialization.json.buildJsonObject
import net.robog.clarity.backend.feed.Appended
import net.robog.clarity.backend.feed.ChoiceCheck
import net.robog.clarity.backend.feed.checkChoice
import net.robog.clarity.backend.feed.summarizedBy
import net.robog.clarity.harness.Block
import net.robog.clarity.harness.Message
import net.robog.clarity.harness.OPERATOR_UPDATE_PLACEHOLDER
import net.robog.clarity.harness.Role
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Choice
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId

private fun toolCall(id: String) = Message(Role.ASSISTANT, listOf(Block.ToolUse(id, "send_to_user", buildJsonObject { })))
private fun result(id: String) = Message(Role.USER, listOf(Block.Result(id, "ok")))
private fun said(text: String) = Message.user(text)
private fun inbox(text: String) = Message.system(text)
private fun endTurn(text: String = "done") = Message(Role.ASSISTANT, listOf(Block.Text(text)))

private val at = Instant.parse("2026-10-26T14:04:00Z")
private fun item(seq: Long, vararg cards: Card) = FeedItem(FeedItemId("i$seq"), seq, at, FeedItem.Author.AGENT, cards = cards.toList())

class FollowUpPureTest : FunSpec({

    // what the lead journal really holds: inbox deliveries are system messages, the person's words plain user text,
    // pre-emption folds a placeholder in front of operator notes, and the reply nudge is a system note
    val placeholder = Message.user(OPERATOR_UPDATE_PLACEHOLDER)
    val nudge = Message.system(REPLY_NUDGE)

    test("a person turn: their words open it, whatever follows") {
        openTurnCarriesPerson(listOf(said("hi"))) shouldBe true
        openTurnCarriesPerson(listOf(said("hi"), toolCall("a"), result("a"), toolCall("b"))) shouldBe true
        openTurnCarriesPerson(listOf(endTurn(), Message.system("[memory, 2026-10-26] empty"), said("hi"), inbox("[inbox, now 2026-10-26T10:00] Nudge you set at 10:00"), toolCall("a"))) shouldBe true
    }

    test("a job or trigger turn is not an answer, even with placeholders and nudges") {
        openTurnCarriesPerson(listOf(inbox("[inbox, now 2026-10-26T10:00] Review"))) shouldBe false
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), result("a"))) shouldBe false
        // pre-emption by an operator note: the placeholder user message is not the person
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), placeholder, Message.system("[inbox] helper finished"))) shouldBe false
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), Message.user("(housekeeping: compaction)"), toolCall("b"))) shouldBe false
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), Message.user(HELPER_RESULT_NUDGE))) shouldBe false
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), endTurn(), placeholder, nudge)) shouldBe false
        openTurnCarriesPerson(listOf(said("hi"), endTurn(), inbox("[inbox] nudge"), toolCall("a"))) shouldBe false
    }

    test("a person turn cut after the reply nudge is still theirs") {
        openTurnCarriesPerson(listOf(said("what is next?"), endTurn("thinking"), placeholder, nudge)) shouldBe true
        openTurnCarriesPerson(listOf(said("what is next?"), endTurn("thinking"), placeholder, nudge, toolCall("a"))) shouldBe true
    }

    test("words that joined the tool results mid-turn make it theirs") {
        val merged = Message(Role.USER, listOf(Block.Result("a", "ok"), Block.Text("and also, call Dana")))
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), merged)) shouldBe true
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), merged, toolCall("b"))) shouldBe true
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), said("wait"), toolCall("b"))) shouldBe true
    }

    test("a new person turn after a job turn, and the other way round") {
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), endTurn(), said("hi"), toolCall("a"))) shouldBe true
        openTurnCarriesPerson(listOf(said("hi"), endTurn(), inbox("[inbox] job"), toolCall("a"), result("a"))) shouldBe false
    }

    test("nothing, or only a summary") {
        openTurnCarriesPerson(emptyList()) shouldBe false
        openTurnCarriesPerson(listOf(Message.summary("earlier talk"), inbox("[inbox] job"))) shouldBe false
    }

    test("a tap is checked against the card it answers") {
        val options = listOf(Choice("Yes", "yes"), Choice("No", "no"))
        val open = item(1, Card.Text("Ready?"), Card.Choices("Ready?", options))
        val answered = item(1, Card.Choices("Ready?", options, chosen = "no"))
        checkChoice(open, "yes") shouldBe ChoiceCheck.Open
        checkChoice(open, "maybe") shouldBe ChoiceCheck.NotAnOption
        checkChoice(answered, "yes") shouldBe ChoiceCheck.Answered(answered, "no", null)
        checkChoice(answered, "no") shouldBe ChoiceCheck.Answered(answered, "no", null)
        checkChoice(answered, "maybe") shouldBe ChoiceCheck.NotAnOption
        checkChoice(item(2, Card.Text("hi")), "yes") shouldBe ChoiceCheck.NotChoices
        checkChoice(null, "yes") shouldBe ChoiceCheck.NotChoices
    }

    test("a retried evening summary covers only the findings that were there before it") {
        val pending = listOf(item(3, Card.Text("a")), item(4, Card.Text("b")), item(9, Card.Text("arrived later")))
        val posted = item(6, Card.Text("summary"))
        summarizedBy(pending, Appended(posted, created = true)).map { it.seq } shouldBe listOf(3L, 4L, 9L)
        summarizedBy(pending, Appended(posted, created = false)).map { it.seq } shouldBe listOf(3L, 4L)
    }
})
