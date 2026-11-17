package net.robog.clarity.shared.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.session.Settings

class ChoicesAndKindTest {
    private val options = listOf(Choice("Starting", "Starting now"), Choice("Later", "Later"), Choice("Make it smaller", "Make it smaller"))

    @Test
    fun fallbackTextListsTheOptions() {
        assertEquals("Ready? Reply with Starting or Later or Make it smaller.", choicesFallback("Ready?", options))
        assertEquals("Reply with Yes or No.", choicesFallback(null, listOf(Choice("Yes", "yes"), Choice("No", "no"))))
        assertEquals("Reply with Ok.", choicesFallback("  ", listOf(Choice("Ok", "ok"))))
        assertEquals("", choicesFallback(null, emptyList()))
        assertEquals(0, choicesFallback(null, options + Choice("Never", "never")).count { it == ',' })
    }

    @Test
    fun choicesCardRoundTripsAndAnOlderAppShowsItsFallback() {
        val card: Card = Card.Choices("Ready?", options)
        val encoded = ClarityJson.encodeToString(Card.serializer(), card)
        assertEquals(card, ClarityJson.decodeFromString(Card.serializer(), encoded))
        val asOldApp = ClarityJson.decodeFromString(Card.serializer(), encoded.replace("\"type\":\"choices\"", "\"type\":\"choices_v9\""))
        assertEquals(Card.Unknown("Ready? Reply with Starting or Later or Make it smaller."), asOldApp)
    }

    @Test
    fun anAnsweredCardKeepsWhatWasChosen() {
        val answered = Card.Choices("Ready?", options, chosen = "Later")
        assertEquals("Later", ClarityJson.decodeFromString(Card.serializer(), ClarityJson.encodeToString(Card.serializer(), answered)).let { (it as Card.Choices).chosen })
    }

    @Test
    fun messageKindWireNamesAreStable() {
        assertEquals(listOf("reply", "check_in", "look_forward", "finding"), MessageKind.entries.map { it.wire })
        assertEquals(MessageKind.CHECK_IN, MessageKind.fromWire("check_in"))
        assertNull(MessageKind.fromWire("mood"))
        assertNull(MessageKind.fromWire(null))
    }

    @Test
    fun anOldFeedItemWithoutAKindStillDecodes() {
        val json = """{"id":"f1","seq":3,"at":"2026-10-28T10:00:00Z","author":"agent","cards":[{"type":"text","text":"Hi"}]}"""
        assertNull(ClarityJson.decodeFromString(FeedItem.serializer(), json).kind)
        val withKind = ClarityJson.decodeFromString(FeedItem.serializer(), json.replace("\"author\"", "\"kind\":\"reply\",\"author\""))
        assertEquals(MessageKind.REPLY, withKind.kind)
    }

    @Test
    fun anOldSettingsDocumentGetsTheDefaultPushLimit() {
        assertEquals(5, ClarityJson.decodeFromString(Settings.serializer(), "{}").pushesPerDay)
        assertEquals(1..10, Settings.PUSHES_PER_DAY_RANGE)
    }

    @Test
    fun userInputWithoutInReplyToDecodes() {
        val input = ClarityJson.decodeFromString(UserInput.serializer(), """{"clientId":"c1","text":"hi"}""")
        assertNull(input.inReplyTo)
    }

    @Test
    fun anUnknownKindDecodesAsNullInsteadOfFailingTheItem() {
        val item = FeedItem(FeedItemId("1"), 1, kotlin.time.Instant.fromEpochSeconds(0), FeedItem.Author.AGENT, cards = listOf(Card.Text("hi")), kind = MessageKind.CHECK_IN)
        val encoded = ClarityJson.encodeToString(FeedItem.serializer(), item)
        assertEquals(item, ClarityJson.decodeFromString(FeedItem.serializer(), encoded))
        val future = ClarityJson.decodeFromString(FeedItem.serializer(), encoded.replace("\"check_in\"", "\"a_future_kind\""))
        assertNull(future.kind)
        assertEquals(listOf(Card.Text("hi")), future.cards)
        assertNull(ClarityJson.decodeFromString(FeedItem.serializer(), encoded.replace(",\"kind\":\"check_in\"", "")).kind)
        assertNull(ClarityJson.decodeFromString(FeedItem.serializer(), encoded.replace("\"check_in\"", "null")).kind)
        for (kind in MessageKind.entries) {
            val one = ClarityJson.encodeToString(FeedItem.serializer(), item.copy(kind = kind))
            assertEquals(kind, ClarityJson.decodeFromString(FeedItem.serializer(), one).kind)
        }
    }
}
