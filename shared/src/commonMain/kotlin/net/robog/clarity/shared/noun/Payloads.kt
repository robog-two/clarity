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
    /** The message's own id (an email's `Message-ID`, angle brackets kept), if the source has one. */
    val messageId: String? = null,
    /** Files attached to the message: names and sizes only; the bytes stay at the source and are fetched on demand. */
    val attachments: List<AttachmentMeta> = emptyList(),
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
    val url: String? = null,
    /** How a repeating event repeats, in plain words ("every Mon and Wed until Dec 20"); null for a single event. */
    val repeats: String? = null,
) : Payload {
    override val noun: Noun get() = Noun.CALENDAR_EVENT
}

/**
 * A file in a document store (WebDAV, later drives). Only metadata and a short extract are
 * stored; the full text is fetched on demand through a `ReadDocument` action.
 */
@Serializable
@SerialName("document")
data class DocumentPayload(
    /** Which store the file lives in (e.g. `webdav:https://cloud.example/remote.php/dav/files/me`). */
    val store: String,
    /** Path within the store. */
    val path: String,
    val title: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val modifiedAt: Instant? = null,
    /** Extracted text, at most [MAX_EXCERPT_BYTES] bytes of UTF-8. */
    val excerpt: String = "",
) : Payload {
    override val noun: Noun get() = Noun.DOCUMENT

    companion object { const val MAX_EXCERPT_BYTES = 8 * 1024 }
}

/**
 * One attachment of a message. [part] is the connector's own address of it inside the message
 * (for email the MIME part path, e.g. `2` or `1.3`), which it uses to fetch the bytes later.
 */
@Serializable
data class AttachmentMeta(
    val part: String,
    val filename: String? = null,
    val mimeType: String = "application/octet-stream",
    /** Size in bytes as the source reports it (an encoded size may be a little larger than the file), if known. */
    val size: Long? = null,
    /** Shown inside the body (an inline image) rather than offered as a file. */
    val inline: Boolean = false,
)
