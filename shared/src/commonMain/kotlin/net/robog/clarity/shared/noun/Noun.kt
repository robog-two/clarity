package net.robog.clarity.shared.noun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kinds of things Clarity knows about. Deduplication, storage and access grants are
 * all organised per noun. Adding a noun = new payload type + dedup key + migration.
 */
@Serializable
enum class Noun {
    @SerialName("message") MESSAGE,
    @SerialName("contact") CONTACT,
    @SerialName("calendar_event") CALENDAR_EVENT,
}
