package net.robog.clarity.app.call

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
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
    /**
     * `CallStyle` heads-up with answer / not now (no full-screen permission, Android 14+): it still
     * requests the full-screen intent, which Android turns into a sticky ringing heads-up.
     */
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

/** Whether to ask for the full-screen permission: the call rings as a call, but Android (14+) keeps it to a banner. */
fun shouldAskFullScreen(mode: MorningCall.Mode, canUseFullScreenIntent: Boolean): Boolean =
    mode == MorningCall.Mode.CALL && !canUseFullScreenIntent

// --- how the call rings (pure, so the channel and notification setup is tested without Android) ---

/** How long the incoming call rings before it counts as missed, like a phone call going to voicemail. */
val RING_TIMEOUT: Duration = 60.seconds

/** The ring's vibration: one second on, one second off (repeated by the insistent flag while it rings). */
val RING_VIBRATION: LongArray = longArrayOf(0, 1000, 1000)

/** A notification channel, as Android needs it. Channels cannot change once created, so a new sound means a new [id]. */
data class ChannelSpec(
    val id: String,
    val name: String,
    val importance: Importance,
    val description: String? = null,
    /** Plays the phone's ringtone (with ringtone audio attributes) instead of the notification sound. */
    val ringtone: Boolean = false,
    val vibration: LongArray? = null,
    val publicOnLockScreen: Boolean = false,
) {
    enum class Importance { LOW, DEFAULT, HIGH }

    override fun equals(other: Any?): Boolean =
        other is ChannelSpec && id == other.id && name == other.name && importance == other.importance &&
            description == other.description && ringtone == other.ringtone &&
            vibration.contentEquals(other.vibration) && publicOnLockScreen == other.publicOnLockScreen

    override fun hashCode(): Int = id.hashCode()
}

object CallChannels {
    const val HEADS_UP = "heads_up"
    /** The ringing call: ringtone, vibration, over the lock screen. */
    const val RING = "morning_call_ring"
    /** The "notify me" mode: an ordinary, prominent notification. */
    const val NOTICE = "morning_call_notice"
    const val ONGOING = "call_in_progress"

    /** Channels from earlier versions, deleted at start: `morning_call` rang with the notification sound, once. */
    val RETIRED: List<String> = listOf("morning_call")

    val ALL: List<ChannelSpec> = listOf(
        ChannelSpec(HEADS_UP, "Heads-up before the call", ChannelSpec.Importance.DEFAULT),
        ChannelSpec(
            RING, "Morning call", ChannelSpec.Importance.HIGH,
            description = "Rings like a phone call when your morning meeting starts",
            ringtone = true, vibration = RING_VIBRATION, publicOnLockScreen = true,
        ),
        ChannelSpec(
            NOTICE, "Morning call reminder", ChannelSpec.Importance.HIGH,
            description = "When you chose “Notify me” instead of a call", publicOnLockScreen = true,
        ),
        ChannelSpec(ONGOING, "Call in progress", ChannelSpec.Importance.LOW),
    )
}

/** What the incoming notification is made of for a [IncomingPresentation]. */
data class IncomingStyle(
    val channel: String,
    /** `CallStyle` with answer and decline, category CALL. */
    val callStyle: Boolean,
    /**
     * Carries a full-screen intent. Requested for every call, permission or not: Android 12+ only
     * posts a `CallStyle` notification that has one (or comes from a foreground service), and
     * Android 14+ without the permission turns it into a sticky heads-up instead of refusing it.
     */
    val fullScreenIntent: Boolean,
    /** Keeps ringing (`FLAG_INSISTENT`) until answered, declined or [timeout]. */
    val insistent: Boolean,
    val timeout: Duration?,
)

fun incomingStyle(presentation: IncomingPresentation): IncomingStyle? = when (presentation) {
    IncomingPresentation.None -> null
    IncomingPresentation.Plain -> IncomingStyle(CallChannels.NOTICE, callStyle = false, fullScreenIntent = false, insistent = false, timeout = null)
    IncomingPresentation.FullScreenCall, IncomingPresentation.HeadsUpCall ->
        IncomingStyle(CallChannels.RING, callStyle = true, fullScreenIntent = true, insistent = true, timeout = RING_TIMEOUT)
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
