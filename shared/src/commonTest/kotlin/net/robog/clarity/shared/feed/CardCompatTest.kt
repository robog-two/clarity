package net.robog.clarity.shared.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import net.robog.clarity.shared.ClarityJson

/** A newer server's cards never break the feed: unknown types decode to [Card.Unknown]. */
class CardCompatTest {
    @Test
    fun unknownCardTypeDecodesWithItsFallbackText() {
        val json = """{"id":"f1","seq":3,"at":"2026-10-28T10:00:00Z","author":"agent","cards":[""" +
            """{"type":"text","text":"Here it is."},""" +
            """{"type":"spreadsheet","sheetId":"s1","rows":[1,2],"fallbackText":"Budget. In your Goods."},""" +
            """{"type":"hologram","depth":3}]}"""
        val item = ClarityJson.decodeFromString(FeedItem.serializer(), json)
        assertEquals(
            listOf(Card.Text("Here it is."), Card.Unknown("Budget. In your Goods."), Card.Unknown(null)),
            item.cards,
        )
    }

    @Test
    fun reportCardCarriesItsFallbackTextOnTheWire() {
        val card: Card = Card.Report("r1", "The Devils", listOf("pdf", "docx"))
        val encoded = ClarityJson.encodeToString(Card.serializer(), card)
        assertEquals(true, encoded.contains("\"fallbackText\":\"The Devils. In your Goods, to open or download.\""))
        // An app that does not know "report" still has something to show.
        val asUnknown = ClarityJson.decodeFromString(Card.serializer(), encoded.replace("\"type\":\"report\"", "\"type\":\"report_v9\""))
        assertEquals(Card.Unknown("The Devils. In your Goods, to open or download."), asUnknown)
        assertEquals(card, ClarityJson.decodeFromString(Card.serializer(), encoded))
    }
}
