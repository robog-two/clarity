package net.robog.clarity.backend.persistence

import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.noun.Handle
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

/** Columns every canonical noun table shares. */
sealed class CanonicalTable(name: String) : Table(name) {
    val id = uuid("id")
    val dedupKey = binary("dedup_key")
    val firstSeenAt = timestamp("first_seen_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Messages : CanonicalTable("message") {
    val channel = text("channel")
    val account = jsonb<Handle>("account", ClarityJson)
    val sender = jsonb<Handle>("sender", ClarityJson)
    val recipients = jsonb<List<Handle>>("recipients", ClarityJson)
    val participants = jsonb<List<String>>("participants", ClarityJson)
    val subject = text("subject").nullable()
    val body = text("body")
    val sentAt = timestamp("sent_at")
    val threadRef = text("thread_ref").nullable()
}

object Contacts : CanonicalTable("contact") {
    val displayName = text("display_name")
    val handles = jsonb<List<Handle>>("handles", ClarityJson)
    val organization = text("organization").nullable()
    val notes = text("notes").nullable()
    val searchText = text("search_text")
}

object CalendarEvents : CanonicalTable("calendar_event") {
    val icalUid = text("ical_uid").nullable()
    val calendar = text("calendar")
    val title = text("title")
    val startAt = timestamp("start_at")
    val endAt = timestamp("end_at")
    val allDay = bool("all_day")
    val location = text("location").nullable()
    val description = text("description").nullable()
    val attendees = jsonb<List<Handle>>("attendees", ClarityJson)
}

object Documents : CanonicalTable("document") {
    val store = text("store")
    val path = text("path")
    val title = text("title")
    val mimeType = text("mime_type").nullable()
    val size = long("size").nullable()
    val modifiedAt = timestamp("modified_at").nullable()
    val excerpt = text("excerpt")
}
