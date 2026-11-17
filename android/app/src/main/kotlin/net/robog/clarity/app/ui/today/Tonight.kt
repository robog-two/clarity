package net.robog.clarity.app.ui.today

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import net.robog.clarity.app.push.NotifyKind
import net.robog.clarity.app.push.parseNotify
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.FeedItemId

/** What Today's Tonight card shows of an evening summary: its counts line and the first thing coming up. */
data class Tonight(val itemId: FeedItemId, val counts: String, val comingUp: String? = null)

/** The label of the summary's first section, as the server writes it. */
private const val COMING_UP = "Coming up"

/** The instant the local day of [now] ends in [zone]: the next local midnight. */
fun nextLocalMidnight(now: Instant, zone: TimeZone): Instant =
    now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone)

/**
 * The evening summary Today shows at [now]: the newest evening-summary item posted on the same local
 * day (so it goes away at midnight, before the next morning), or null when there is none. The summary
 * is found by its notify decision, so nothing new is stored. That is safe because findings held for
 * the summary never reach the phone (the server's feed sync only sends items with no batch state,
 * `FeedStore` `batchState IS NULL`), so the only `evening_batch` item the phone holds is a summary. An item with no counts line is skipped.
 */
fun tonightOf(items: List<FeedItem>, now: Instant, zone: TimeZone): Tonight? {
    val day = now.toLocalDateTime(zone).date
    val item = items
        .filter { it.author == FeedItem.Author.AGENT && !it.streaming && parseNotify(it.notify) == NotifyKind.EveningBatch }
        .filter { it.at.toLocalDateTime(zone).date == day }
        .filter { item -> item.cards.any { it is Card.Text } }
        .maxByOrNull { it.seq } ?: return null
    val counts = item.cards.filterIsInstance<Card.Text>().first().text
    val comingUp = item.cards.filterIsInstance<Card.Chips>().firstOrNull { it.label == COMING_UP }?.items?.firstOrNull()?.text
    return Tonight(item.id, counts, comingUp)
}
