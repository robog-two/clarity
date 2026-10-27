package net.robog.clarity.backend.feed

import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.Chip
import net.robog.clarity.shared.feed.FeedItem

/** Pure: the first line of text a feed item shows, for the one-line entries of the evening summary. */
fun headline(item: FeedItem): String {
    val text = item.cards.firstNotNullOfOrNull { card ->
        when (card) {
            is Card.Text -> card.text
            is Card.Note -> card.text
            is Card.Chips -> card.label
            is Card.Context -> card.label
            is Card.Said -> card.text
            is Card.Plan -> card.items.firstOrNull()?.sentence()
            is Card.Report -> card.title
            is Card.Proposal, is Card.Tasks, is Card.CredentialRequest -> null
        }
    } ?: "Something to look at"
    return text.lineSequence().first().trim().let { if (it.length > MAX_HEADLINE) it.take(MAX_HEADLINE - 1) + "…" else it }
}

private const val MAX_HEADLINE = 140

/** Pure: the cards of the evening summary over the items held for it. Empty when there is nothing. */
fun eveningSummaryCards(items: List<FeedItem>): List<Card> {
    if (items.isEmpty()) return emptyList()
    val intro = if (items.size == 1) "One thing waited for tonight." else "${items.size} things waited for tonight."
    return listOf(
        Card.Text(intro),
        Card.Chips("held for the evening · ${items.size}", items.map { Chip(headline(it)) }),
    )
}
