package net.robog.clarity.app.data.feed

import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId

/** Appends streamed [text] to the item's last text card (or starts one) and marks it streaming. */
fun FeedItem.withDelta(text: String): FeedItem {
    val last = cards.lastOrNull()
    val cards = if (last is Card.Text) {
        this.cards.dropLast(1) + Card.Text(last.text + text)
    } else {
        this.cards + Card.Text(text)
    }
    return copy(cards = cards, streaming = true)
}

/**
 * Decides what to store when [incoming] arrives for a seq that may already be cached.
 * Returns null when the cached copy must stay (duplicate or stale delivery).
 *
 * - First sight of a seq: store it.
 * - A final item always wins (it replaces locally accumulated deltas).
 * - A non-final replay never overwrites a finished item, but may refresh one still streaming.
 */
fun mergeItem(existing: FeedItem?, incoming: FeedItem, final: Boolean): FeedItem? = when {
    existing == null -> incoming
    final -> incoming
    existing.streaming -> incoming
    else -> null
}

/** The item holds a choices card nobody has answered yet, as far as this phone knows: the answer may have come from elsewhere. */
fun hasOpenChoices(item: FeedItem): Boolean = item.cards.any { it is Card.Choices && it.chosen == null }

/**
 * The items whose choices buttons are no longer offered: a person's message came after them, so the
 * question has moved on. [sending] is a message on its way (not stored yet), which counts as one after all.
 */
fun supersededChoices(items: List<FeedItem>, sending: Boolean = false): Set<FeedItemId> {
    val lastPerson = if (sending) Long.MAX_VALUE else items.filter { it.author == FeedItem.Author.USER }.maxOfOrNull { it.seq } ?: return emptySet()
    return items.filter { it.seq < lastPerson && it.cards.any { c -> c is Card.Choices } }.mapTo(mutableSetOf()) { it.id }
}

/** The item holds a choices card that has been answered. */
fun hasAnsweredChoices(item: FeedItem): Boolean = item.cards.any { it is Card.Choices && it.chosen != null }

/** The item's notification still waits for protected time to end (`hold_until:`). */
fun isHeld(item: FeedItem): Boolean = item.notify.startsWith("hold_until")

/**
 * Whether storing [stored] over [existing] is the server releasing a hold the cache had: the
 * cached copy said `hold_until:` and this one says `released:`. The cache then says released, so a
 * resent release is not a second one.
 */
fun holdReleased(existing: FeedItem?, stored: FeedItem): Boolean =
    existing != null && isHeld(existing) && !stored.streaming && stored.notify.startsWith("released:")
