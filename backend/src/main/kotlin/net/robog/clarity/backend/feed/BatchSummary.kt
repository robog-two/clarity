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
            is Card.Choices -> card.prompt?.takeIf { it.isNotBlank() }
            is Card.Unknown -> card.fallbackText
            is Card.Proposal, is Card.Tasks, is Card.CredentialRequest, is Card.Sources -> null
        }
    } ?: "Something to look at"
    return text.lineSequence().first().trim().let { if (it.length > MAX_HEADLINE) it.take(MAX_HEADLINE - 1) + "…" else it }
}

private const val MAX_HEADLINE = 140

/**
 * Pure: the cards of the evening summary, in this order and each only when it has something:
 *  1. "Coming up": the `look_forward` items posted since the last summary ([comingUp]),
 *  2. "Done today": the titles of tasks finished today ([doneToday]),
 *  3. "Held for tonight": the findings that waited for this summary ([held]).
 * A short line first counts them (it is also what the phone shows). Empty when there is nothing at
 * all, so no summary is posted.
 */
fun eveningSummaryCards(held: List<FeedItem>, comingUp: List<FeedItem> = emptyList(), doneToday: List<String> = emptyList()): List<Card> {
    val sections = listOf(
        "Coming up" to comingUp.map { Chip(headline(it)) },
        "Done today" to doneToday.map { Chip(it.trim()) },
        "Held for tonight" to held.map { Chip(headline(it)) },
    ).filter { it.second.isNotEmpty() }
    if (sections.isEmpty()) return emptyList()
    val counts = sections.joinToString(" ") { (label, chips) -> "${chips.size} ${summaryCount(label)}." }
    return listOf(Card.Text(counts)) + sections.map { (label, chips) -> Card.Chips(label, chips) }
}

/**
 * Pure: which of the [pending] findings the posted summary covered. When this call posted it, all of
 * those it read. When the summary was already there (a retry after a crash), only the findings that
 * arrived before it: anything newer was never in it and waits for the next one.
 */
fun summarizedBy(pending: List<FeedItem>, posted: Appended): List<FeedItem> =
    if (posted.created) pending else pending.filter { it.seq < posted.item.seq }

/** The lower-case phrase that follows a count in the summary's opening line. */
private fun summaryCount(label: String): String = when (label) {
    "Coming up" -> "coming up"
    "Done today" -> "done today"
    else -> "held"
}

/**
 * Pure: the "Coming up" items without the ones the person turned down: a choices card answered with an option whose label starts
 * with an option marked `declines`. Unanswered and accepted ones stay.
 */
fun withoutTurnedDown(items: List<FeedItem>): List<FeedItem> = items.filterNot { item ->
    item.cards.filterIsInstance<Card.Choices>().any { card ->
        card.chosen != null && card.options.any { it.reply == card.chosen && it.declines }
    }
}
