package net.robog.clarity.shared.noun

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Noun payloads: the normalized shape a connector reports. Keep these minimal and additive. */
@Serializable
sealed interface Payload {
    val noun: Noun
}

@Serializable
@SerialName("message")
data class MessagePayload(
    val channel: Channel,
    /** The user's own account this message was seen on (their email address / phone number). */
    val account: Handle,
    val sender: Handle,
    val recipients: List<Handle>,
    val subject: String? = null,
    val body: String,
    val sentAt: Instant,
    /** Connector-native thread/conversation reference, if any. */
    val threadRef: String? = null,
) : Payload {
    override val noun: Noun get() = Noun.MESSAGE

    @Serializable
    enum class Channel {
        @SerialName("email") EMAIL,
        @SerialName("sms") SMS,
        @SerialName("chat") CHAT,
    }
}

@Serializable
@SerialName("contact")
data class ContactPayload(
    val displayName: String,
    val handles: List<Handle>,
    val organization: String? = null,
    val notes: String? = null,
) : Payload {
    override val noun: Noun get() = Noun.CONTACT
}

@Serializable
@SerialName("calendar_event")
data class CalendarEventPayload(
    /** iCalendar UID when the source has one: the strongest dedup signal for events. */
    val icalUid: String? = null,
    val calendar: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
    val attendees: List<Handle> = emptyList(),
) : Payload {
    override val noun: Noun get() = Noun.CALENDAR_EVENT
}
