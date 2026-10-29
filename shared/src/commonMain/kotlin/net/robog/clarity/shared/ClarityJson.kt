package net.robog.clarity.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import net.robog.clarity.shared.feed.Card

/** The one JSON configuration every Clarity component uses on the wire. */
val ClarityJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true // forward compatible: older clients tolerate newer fields
    explicitNulls = false
    encodeDefaults = true
    serializersModule = SerializersModule {
        // A card type this build does not know decodes as Card.Unknown (keeping its fallbackText), so a
        // newer server never breaks the feed of an older app.
        polymorphic(Card::class) { defaultDeserializer { Card.Unknown.serializer() } }
    }
}

/**
 * [ClarityJson] without the [Card.Unknown] fallback, for input that must be valid as written (the
 * cards the agent sends): a card with a missing or unknown `type` is a decoding error, not a card.
 */
val ClarityStrictJson: Json = Json(ClarityJson) { serializersModule = EmptySerializersModule() }
