package net.robog.clarity.app.data.feed

import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem

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

/**
 * Whether storing [incoming] over [existing] is an arrival the push service should hear about: the
 * first sight of a seq, or a hold the server has just released (`hold_until:` turned `released:`).
 * The stored copy then says released, so a resent release does not arrive twice.
 */
fun isArrival(existing: FeedItem?, incoming: FeedItem): Boolean =
    existing == null || (existing.notify.startsWith("hold_until") && incoming.notify.startsWith("released:"))
