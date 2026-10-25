package net.robog.clarity.shared

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Stable identifier of a connector instance, e.g. `imap-personal` or `device-<uuid>`. */
@Serializable @JvmInline
value class ConnectorId(val value: String) {
    init { require(PATTERN.matches(value)) { "invalid connector id: $value" } }
    override fun toString(): String = value
    companion object { val PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,62}$") }
}

/** An enrolled Android device. */
@Serializable @JvmInline
value class DeviceId(val value: String) { override fun toString(): String = value }

/** A canonical, deduplicated entity in the backend database (UUID string). */
@Serializable @JvmInline
value class EntityId(val value: String) { override fun toString(): String = value }

/** The id a connector uses for a record in *its own* system (IMAP UID, CalDAV href, ...). */
@Serializable @JvmInline
value class SourceId(val value: String) { override fun toString(): String = value }
