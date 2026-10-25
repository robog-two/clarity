package net.robog.clarity.shared

import kotlinx.serialization.json.Json

/** The one JSON configuration every Clarity component uses on the wire. */
val ClarityJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true // forward compatible: older clients tolerate newer fields
    explicitNulls = false
    encodeDefaults = true
}
