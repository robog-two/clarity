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

    test("a resumed turn knows whether the person wrote in it") {
        openTurnCarriesPerson(listOf(said("hi"))) shouldBe true
        openTurnCarriesPerson(listOf(said("hi"), toolCall("a"))) shouldBe true
        openTurnCarriesPerson(listOf(said("hi"), toolCall("a"), result("a"), toolCall("b"))) shouldBe true
        // the person wrote mid-turn
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), said("and also this"))) shouldBe true
        openTurnCarriesPerson(listOf(inbox("[inbox] job"), toolCall("a"), result("a"))) shouldBe false
        openTurnCarriesPerson(listOf(inbox("[inbox] job"))) shouldBe false
        openTurnCarriesPerson(emptyList()) shouldBe false
    }

    test("an earlier, finished turn does not count") {
        openTurnCarriesPerson(listOf(said("hi"), endTurn(), inbox("[inbox] nudge"))) shouldBe false
        openTurnCarriesPerson(listOf(said("hi"), toolCall("a"), result("a"), endTurn(), inbox("[inbox] nudge"), toolCall("b"))) shouldBe false
        openTurnCarriesPerson(listOf(said("hi"), endTurn(), said("again"), toolCall("b"))) shouldBe true
    }

    test("a summary is not the person's words") {
        openTurnCarriesPerson(listOf(Message.summary("earlier talk"), inbox("[inbox] job"))) shouldBe false
    }

    test("a tap is checked against the card it answers") {
        val options = listOf(Choice("Yes", "yes"), Choice("No", "no"))
        val open = item(1, Card.Text("Ready?"), Card.Choices("Ready?", options))
        val answered = item(1, Card.Choices("Ready?", options, chosen = "no"))
        checkChoice(open, "yes") shouldBe ChoiceCheck.Open
        checkChoice(open, "maybe") shouldBe ChoiceCheck.NotAnOption
        checkChoice(answered, "yes") shouldBe ChoiceCheck.Answered(answered, "no")
        checkChoice(answered, "no") shouldBe ChoiceCheck.Answered(answered, "no")
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
