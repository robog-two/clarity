package net.robog.clarity.app.push

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import net.robog.clarity.app.data.feed.Arrival
import net.robog.clarity.app.data.feed.SyncState
import androidx.navigation3.runtime.NavKey
import net.robog.clarity.app.nav.Amend
import net.robog.clarity.app.nav.DeepLink
import net.robog.clarity.app.nav.DraftReview
import net.robog.clarity.app.nav.Main
import net.robog.clarity.app.nav.MainTab
import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.governance.HeldAction
import net.robog.clarity.shared.governance.HeldActionId
import net.robog.clarity.shared.governance.Urgency
import net.robog.clarity.shared.session.SessionId

// All of the push decisions live here as pure functions; the Android glue only carries them out.

// --- when the foreground service runs ---

/** What decides whether the phone keeps the connection open. */
data class PushConditions(
    /** The phone is enrolled with a server (forgetting the server clears this). */
    val hasServer: Boolean,
    /** Onboarding finished on this device. */
    val setUp: Boolean,
    /** The person's own switch in Settings (on unless turned off). */
    val enabled: Boolean,
) {
    val shouldRun: Boolean get() = hasServer && setUp && enabled
}

enum class ServiceAction { Start, Stop, None }

/** Start or stop the service so that it matches [shouldRun]. */
fun serviceAction(shouldRun: Boolean, running: Boolean): ServiceAction = when {
    shouldRun && !running -> ServiceAction.Start
    !shouldRun && running -> ServiceAction.Stop
    else -> ServiceAction.None
}

// --- the battery exemption ---

/** Ask once, and only while the phone still restricts the app. */
fun shouldAskBattery(exempt: Boolean, alreadyAsked: Boolean): Boolean = !exempt && !alreadyAsked

// --- reconnecting when the network changes ---

sealed interface NetworkEvent {
    /** A network became the default; [id] tells networks apart (wifi to mobile is a new id). */
    data class Available(val id: String) : NetworkEvent
    data object Lost : NetworkEvent
}

/**
 * Whether the feed connection should be restarted at once. A network that appears while the feed is
 * not live ends the backoff wait. A different network while it is live means the old socket is
 * probably dead (it can take minutes to notice), so restart it. Losing a network changes nothing:
 * there is nowhere to reconnect to yet.
 */
fun reconnectOn(event: NetworkEvent, sync: SyncState, previousNetwork: String?): Boolean = when (event) {
    NetworkEvent.Lost -> false
    is NetworkEvent.Available -> sync != SyncState.Live || (previousNetwork != null && previousNetwork != event.id)
}

/** Remembers the default network and tells the feed to reconnect when [reconnectOn] says so. */
class NetworkReconnector(private val syncState: () -> SyncState, private val reconnect: () -> Unit) {
    private var current: String? = null

    fun onEvent(event: NetworkEvent) {
        val previous = current
        when (event) {
            is NetworkEvent.Available -> current = event.id
            NetworkEvent.Lost -> current = null
        }
        if (reconnectOn(event, syncState(), previous)) reconnect()
    }
}

// --- what the person sees in the persistent notification ---

fun statusText(state: SyncState, host: String): String = when (state) {
    SyncState.Live -> "Connected to $host"
    SyncState.Connecting -> "Connecting to $host…"
    SyncState.Offline -> "Offline, reconnecting…"
    SyncState.Unauthorized -> "$host no longer accepts this phone"
    SyncState.Unavailable -> "$host has no feed yet"
}

// --- notifications from what arrives ---

/** The stored notify decision (`feed_item.notify`), parsed. Unknown spellings are treated as in-app. */
sealed interface NotifyKind {
    data object PushNow : NotifyKind
    data object InApp : NotifyKind
    data object Hold : NotifyKind
    data object EveningBatch : NotifyKind

    /** A hold that protected time no longer covers, released by the server at [at]: notify like push-now. */
    data class Released(val at: Instant?) : NotifyKind
}

fun parseNotify(value: String?): NotifyKind = when {
    value == null || value == "in_app" -> NotifyKind.InApp
    value == "push_now" -> NotifyKind.PushNow
    value.startsWith("hold_until") -> NotifyKind.Hold
    value.startsWith(RELEASED) -> NotifyKind.Released(runCatching { Instant.parse(value.removePrefix(RELEASED)) }.getOrNull())
    value.startsWith("evening_batch") -> NotifyKind.EveningBatch
    else -> NotifyKind.InApp
}

enum class PushChannel(val id: String, val label: String, val description: String) {
    /** Urgent held actions that wait for the person's yes. */
    Urgent("urgent", "Urgent", "Things that need you now"),
    /** Messages from the agent that the notification policy said to push. */
    Agent("agent", "From Clarity", "Messages from your agent"),
    /** The evening summary. */
    Evening("evening", "Evening summary", "Your evening summary"),
}

enum class ChannelImportance { High, Default, Low }

fun importanceOf(channel: PushChannel): ChannelImportance = when (channel) {
    PushChannel.Urgent -> ChannelImportance.High
    PushChannel.Agent -> ChannelImportance.Default
    PushChannel.Evening -> ChannelImportance.Low
}

/** Where the person is in the app right now. */
sealed interface Front {
    /** The app is not on screen. */
    data object Background : Front

    /** The app is on screen at [where]. */
    data class Showing(val where: Place) : Front
}

sealed interface Place {
    data class Tab(val tab: MainTab) : Place
    /** Reviewing or amending one held action. */
    data class HeldItem(val id: HeldActionId) : Place
    data object Other : Place
}

/** Where the person is, given the top of the navigation stack and the selected tab. */
fun placeFor(top: NavKey?, tab: MainTab): Place = when (top) {
    is Main -> Place.Tab(tab)
    is DraftReview -> Place.HeldItem(HeldActionId(top.heldId))
    is Amend -> top.heldId?.let { Place.HeldItem(HeldActionId(it)) } ?: Place.Other
    else -> Place.Other
}

/** How a notification looks: a chat message with a reply box, or a line that opens the app. */
enum class PlanStyle { Message, OpenApp }

data class NotificationPlan(
    /** Stable per thing, so the same arrival never stacks two notifications. */
    val id: Int,
    val channel: PushChannel,
    val title: String,
    val text: String,
    val link: DeepLink,
    val style: PlanStyle = PlanStyle.OpenApp,
    /** For a [PlanStyle.Message]: the session a reply belongs to. */
    val sessionId: SessionId? = null,
)

/** Anything older than this when it reaches the phone (a long offline spell) is not worth a buzz. */
val MAX_PUSH_AGE: Duration = 2.hours

/**
 * Whether [arrival] earns a notification, and which. Push-now agent items, released holds, the
 * evening summary and urgent held actions always do; any other agent message does while the app is
 * not on screen ("hold until" waits for its release, whose age counts from the release). Never when
 * notifications are off, when the item is stale, or when the person is looking at the very place
 * it shows up (the feed on Today or Ask, the Held list, or that held action). Messages become one
 * conversation with a reply box; something to approve or a login to enter only offers to open the app.
 */
fun decideNotification(
    arrival: Arrival,
    front: Front,
    canPost: Boolean,
    now: Instant,
    maxAge: Duration = MAX_PUSH_AGE,
): NotificationPlan? {
    if (!canPost) return null
    return when (arrival) {
        is Arrival.Item -> itemPlan(arrival.item, front, now, maxAge)
        is Arrival.Held -> heldPlan(arrival.action, front, now, maxAge)
    }
}

private fun itemPlan(item: FeedItem, front: Front, now: Instant, maxAge: Duration): NotificationPlan? {
    if (item.author != FeedItem.Author.AGENT || item.streaming) return null
    val kind = parseNotify(item.notify)
    val since = (kind as? NotifyKind.Released)?.at ?: item.at
    if (now - since > maxAge) return null
    val channel = when (kind) {
        NotifyKind.PushNow, is NotifyKind.Released -> PushChannel.Agent
        NotifyKind.EveningBatch -> PushChannel.Evening
        NotifyKind.InApp -> if (front == Front.Background) PushChannel.Agent else return null
        NotifyKind.Hold -> return null
    }
    // The feed shows it right now.
    if (front is Front.Showing) {
        val where = front.where
        if (where is Place.Tab && (where.tab == MainTab.Today || where.tab == MainTab.Ask)) return null
    }
    if (channel == PushChannel.Evening) {
        return NotificationPlan(ITEM_ID_BASE + (item.seq % ID_SPAN).toInt(), channel, "Your evening summary", itemText(item), DeepLink.Today)
    }
    item.cards.firstNotNullOfOrNull { it as? Card.Proposal }?.let { proposal ->
        // The same id as the held action's own notification, so the two never stack.
        return NotificationPlan(
            heldNotificationId(proposal.heldActionId), channel, "Needs your ok",
            itemText(item, fallback = "Open Clarity to review it."), DeepLink.HeldItem(proposal.heldActionId),
        )
    }
    if (item.cards.any { it is Card.CredentialRequest }) {
        return NotificationPlan(
            ITEM_ID_BASE + (item.seq % ID_SPAN).toInt(), channel, "Clarity needs a login",
            itemText(item, fallback = "Open Clarity to enter it."), DeepLink.Today,
        )
    }
    return NotificationPlan(
        id = MESSAGE_ID,
        channel = channel,
        title = "Clarity",
        text = itemText(item),
        link = if (item.sessionId != null) DeepLink.Ask else DeepLink.Today,
        style = PlanStyle.Message,
        sessionId = item.sessionId,
    )
}

/** The notification id of a held action, shared by its own notification and the feed item that proposes it. */
fun heldNotificationId(id: HeldActionId): Int = HELD_ID_BASE + (id.value.hashCode() and Int.MAX_VALUE) % ID_SPAN

/** The one conversation notification that the agent's messages and the person's replies share. */
const val MESSAGE_ID = 9_000

private fun heldPlan(held: HeldAction, front: Front, now: Instant, maxAge: Duration): NotificationPlan? {
    if (held.status != HeldAction.Status.HELD || held.urgency != Urgency.URGENT) return null
    if (now - held.createdAt > maxAge) return null
    if (front is Front.Showing) {
        val where = front.where
        if (where == Place.Tab(MainTab.Held) || where == Place.HeldItem(held.id)) return null
    }
    return NotificationPlan(
        id = heldNotificationId(held.id),
        channel = PushChannel.Urgent,
        title = "Needs your ok",
        text = held.action.summary,
        link = DeepLink.HeldItem(held.id),
    )
}

/** The first line of the item's own words, else a plain fallback. */
fun itemText(item: FeedItem, limit: Int = 140, fallback: String = "New from Clarity"): String {
    val words = item.cards.firstNotNullOfOrNull { card ->
        when (card) {
            is Card.Text -> card.text
            is Card.Note -> card.text
            else -> null
        }
    }
    val line = words?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
    return when {
        line == null -> fallback
        line.length <= limit -> line
        else -> line.take(limit - 1).trimEnd() + "…"
    }
}

private const val RELEASED = "released:"
private const val ID_SPAN = 100_000
private const val ITEM_ID_BASE = 10_000
private const val HELD_ID_BASE = 200_000
