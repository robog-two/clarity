package net.robog.clarity.backend.feed

import net.robog.clarity.shared.feed.Card
import net.robog.clarity.shared.feed.ClientInputId
import net.robog.clarity.shared.feed.FeedItem
import net.robog.clarity.shared.feed.NOTIFY_IN_APP
import net.robog.clarity.shared.feed.NOTIFY_REPLY

/** The stored spelling of a push that goes out at once (see `NotifyDecision.PushNow`). */
const val NOTIFY_PUSH_NOW: String = "push_now"

/**
 * Pure: a note the server writes itself. The phone drops plain `in_app` items while it is in the
 * background, so the caller picks [notify] by what the note is: [NOTIFY_REPLY] when it answers the
 * person, [NOTIFY_PUSH_NOW] when it must reach them (never counted in the daily push budget).
 */
fun serverNote(text: String, notify: String = NOTIFY_IN_APP, clientInputId: ClientInputId? = null): FeedDraft =
    FeedDraft(author = FeedItem.Author.AGENT, cards = listOf(Card.Note(text)), clientInputId = clientInputId, notify = notify)

/** Pure: a note about a turn that went wrong. A reply when the turn was answering the person, else an ordinary post. */
fun turnNote(text: String, answeringPerson: Boolean, clientInputId: ClientInputId? = null): FeedDraft =
    serverNote(text, if (answeringPerson) NOTIFY_REPLY else NOTIFY_IN_APP, clientInputId)
