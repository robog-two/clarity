package net.robog.clarity.app.call

import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import net.robog.clarity.app.ui.onboarding.formatTime
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.session.Settings.MorningCall
import kotlinx.datetime.LocalTime

/** How the call announces itself, decided from the mode and what the phone allows. */
enum class IncomingPresentation {
    /** A `CallStyle` notification with a full-screen intent: the call screen opens over the lock screen. */
    FullScreenCall,
    /** `CallStyle` heads-up with answer / not now (no full-screen permission). */
    HeadsUpCall,
    /** A normal notification that opens the app (mode `NOTIFY`). */
    Plain,
    /** Notifications are off for the app: nothing can be shown. */
    None,
}

fun incomingPresentation(
    mode: MorningCall.Mode,
    canUseFullScreenIntent: Boolean,
    notificationsEnabled: Boolean,
): IncomingPresentation = when {
    !notificationsEnabled -> IncomingPresentation.None
    mode == MorningCall.Mode.NOTIFY -> IncomingPresentation.Plain
    canUseFullScreenIntent -> IncomingPresentation.FullScreenCall
    else -> IncomingPresentation.HeadsUpCall
}

/** The heads-up title from the prototype. */
const val HEADS_UP_TITLE = "We’re scheduled in 10! :)"

fun headsUpTitle(minutes: Int): String = if (minutes == 10) HEADS_UP_TITLE else "We’re scheduled in $minutes! :)"

/**
 * The heads-up body: the agent's own first line if the prep job left one in the feed (newest agent
 * item outside any session, within [window] before [now]), else a fixed line.
 */
fun headsUpBody(callAt: LocalTime, items: List<FeedItem>, now: Instant, window: kotlin.time.Duration = 2.hours): String {
    val prepared = items
        .filter { it.author == FeedItem.Author.AGENT && it.sessionId == null && !it.streaming && it.at <= now && now - it.at <= window }
        .maxByOrNull { it.seq }
        ?.cards?.filterIsInstance<Card.Text>()?.firstOrNull()?.text
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    return prepared?.takeIf { it.isNotEmpty() } ?: "I’ll call at ${formatTime(callAt)}."
}
