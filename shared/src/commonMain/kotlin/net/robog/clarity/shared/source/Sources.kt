package net.robog.clarity.shared.source

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** What a cited record is. Wire names are the `kind` of a [SourceItem.Entity]. */
@Serializable(with = SourceKindSerializer::class)
enum class SourceKind(val wire: String) {
    MESSAGE("message"),
    EVENT("event"),
    DOCUMENT("document"),
    CONTACT("contact"),

    /** A kind a newer server sent; drawn with a plain icon. */
    UNKNOWN("unknown"),
}

/** Reads a kind by its wire name; a name this build does not know is [SourceKind.UNKNOWN], never an error. */
object SourceKindSerializer : KSerializer<SourceKind> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SourceKind", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): SourceKind = decoder.decodeString().let { name -> SourceKind.entries.firstOrNull { it.wire == name } ?: SourceKind.UNKNOWN }
    override fun serialize(encoder: Encoder, value: SourceKind) = encoder.encodeString(value.wire)
}

/**
 * One source under a [net.robog.clarity.shared.feed.Card.Sources] card. The agent names an item by
 * `{id}` (a record) or `{url}` (a web page) only; the server fills in everything else from its own
 * data, so [title] and the sub line never come from the model. On the wire an item is told apart by
 * its keys (`url` is a web page, `id` a record), see [SourceItemSerializer].
 */
@Serializable(with = SourceItemSerializer::class)
sealed interface SourceItem {
    val title: String

    /** A record Clarity holds (mail, event, file, contact): the app opens it with `GET /api/v1/sources/{id}`. */
    @Serializable
    data class Entity(
        val id: String,
        val kind: SourceKind = SourceKind.MESSAGE,
        override val title: String = "",
        /** One line under the title, e.g. the sender and the date. */
        val sub: String = "",
        /** Files attached to a message; 0 for anything else. */
        val attachments: Int = 0,
        /** The sender of a message, for the device to put in front of the time. */
        val by: String = "",
        /** When a message was sent or a timed event starts: the device shows it on its own clock. */
        val at: Instant? = null,
        /** The day of an all-day event, which has no clock time and never shifts with a time zone. */
        val day: LocalDate? = null,
        /** The last day of a multi-day all-day event (inclusive); null for a single day. */
        val endDay: LocalDate? = null,
    ) : SourceItem

    /** An item this build cannot read (a newer server's, or the model's own malformed one): not drawn. */
    data object Unknown : SourceItem { override val title: String get() = "" }

    /** A page on the web; [host] is what the person judges it by (punycode, so look-alike letters show). */
    @Serializable
    data class Web(
        val url: String,
        override val title: String = "",
        val host: String = "",
    ) : SourceItem
}

/**
 * Decodes an item by its keys and encodes it as the plain object of its class (no `type`). An item with
 * neither key is [SourceItem.Unknown] so one odd item never breaks the feed; `send_to_user` refuses it.
 */
object SourceItemSerializer : KSerializer<SourceItem> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): SourceItem {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Source items are JSON")
        val obj = input.decodeJsonElement().jsonObject
        return when {
            obj["url"] is JsonPrimitive -> input.json.decodeFromJsonElement(SourceItem.Web.serializer(), obj)
            obj["id"] is JsonPrimitive -> input.json.decodeFromJsonElement(SourceItem.Entity.serializer(), obj)
            else -> SourceItem.Unknown
        }
    }

    override fun serialize(encoder: Encoder, value: SourceItem) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Source items are JSON")
        output.encodeJsonElement(
            when (value) {
                is SourceItem.Entity -> output.json.encodeToJsonElement(SourceItem.Entity.serializer(), value)
                is SourceItem.Web -> output.json.encodeToJsonElement(SourceItem.Web.serializer(), value)
                SourceItem.Unknown -> JsonObject(emptyMap())
            },
        )
    }
}

/** One attachment in a [SourceView.Message]; [part] is what `GET /api/v1/messages/{id}/attachments/{part}` takes. */
@Serializable
data class SourceAttachment(
    val part: String,
    val filename: String? = null,
    val mimeType: String = "application/octet-stream",
    val size: Long? = null,
)

/**
 * `GET /api/v1/sources/{entityId}`: a record as stored, for the person to check what the agent said.
 * Read-only. Text is shown as the source wrote it (it is not the agent's).
 */
@Serializable
sealed interface SourceView {
    val id: String
    val title: String

    /** The long text was cut to keep the answer small; the app says "Shortened". */
    val truncated: Boolean

    @Serializable @SerialName("message")
    data class Message(
        override val id: String,
        override val title: String,
        /** `email`, `sms` or `chat`. */
        val channel: String,
        val from: String,
        val to: List<String> = emptyList(),
        val sentAt: Instant,
        /** The person's own account it was seen on. */
        val account: String,
        val body: String,
        val attachments: List<SourceAttachment> = emptyList(),
        override val truncated: Boolean = false,
    ) : SourceView

    @Serializable @SerialName("event")
    data class Event(
        override val id: String,
        override val title: String,
        val calendar: String,
        val start: Instant,
        val end: Instant,
        val allDay: Boolean = false,
        val location: String? = null,
        val description: String? = null,
        val link: String? = null,
        val repeats: String? = null,
        val attendees: List<String> = emptyList(),
        override val truncated: Boolean = false,
    ) : SourceView

    @Serializable @SerialName("document")
    data class Document(
        override val id: String,
        override val title: String,
        val path: String,
        val mimeType: String? = null,
        val size: Long? = null,
        val modifiedAt: Instant? = null,
        val excerpt: String = "",
        override val truncated: Boolean = false,
    ) : SourceView

    @Serializable @SerialName("contact")
    data class Contact(
        override val id: String,
        override val title: String,
        val organization: String? = null,
        val handles: List<String> = emptyList(),
        val notes: String? = null,
        override val truncated: Boolean = false,
    ) : SourceView
}
