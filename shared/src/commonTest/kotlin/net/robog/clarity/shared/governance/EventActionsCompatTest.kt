package net.robog.clarity.shared.governance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.EntityId
import net.robog.clarity.shared.calendar.EventScope
import net.robog.clarity.shared.calendar.Frequency
import net.robog.clarity.shared.calendar.Recurrence
import net.robog.clarity.shared.noun.CalendarEventPayload
import net.robog.clarity.shared.noun.Payload

class EventActionsCompatTest {
    /** A held move stored before `update_event` existed. */
    private val oldHeld = """
        {"id":"h1","createdAt":"2026-10-26T14:04:00Z",
         "action":{"type":"move_event","idempotencyKey":"k1","summary":"Move event e1 to Thursday",
                   "event":"e1","start":"2026-10-29T18:00:00Z","end":"2026-10-29T19:00:00Z","notifyAttendees":true},
         "verdict":{"decision":"ask","rationale":"It tells other people."},
         "agentView":"Dana asked.","urgency":"normal","status":"held"}
    """.trimIndent()

    @Test
    fun aStoredMoveStillDecodesAndKeepsItsKind() {
        val held = ClarityJson.decodeFromString(HeldAction.serializer(), oldHeld)
        val move = held.action as ProposedAction.MoveEvent
        assertEquals(ActionKind.MOVE_EVENT, move.kind)
        assertEquals(EntityId("e1"), move.event)
        assertEquals(true, move.notifyAttendees)
        assertEquals(Instant.parse("2026-10-29T18:00:00Z"), move.start)
    }

    @Test
    fun aStoredEventCreationDecodesWithoutTheNewFields() {
        val json = """{"type":"create_event","idempotencyKey":"k","summary":"Hold","title":"Focus","start":"2026-10-26T14:00:00Z","end":"2026-10-26T15:00:00Z"}"""
        val event = ClarityJson.decodeFromString(ProposedAction.serializer(), json) as ProposedAction.CreateEvent
        assertEquals(false, event.allDay)
        assertNull(event.repeat)
        assertNull(event.location)
        assertNull(event.zone)
    }

    @Test
    fun aStoredEventPayloadDecodesWithoutLinkOrRepeat() {
        val json = """{"type":"calendar_event","calendar":"work","title":"Standup","start":"2026-10-26T14:00:00Z","end":"2026-10-26T14:15:00Z"}"""
        val payload = ClarityJson.decodeFromString(Payload.serializer(), json) as CalendarEventPayload
        assertNull(payload.url)
        assertNull(payload.repeats)
    }

    @Test
    fun updateAndCancelRoundTripWithTheirScope() {
        val update = ProposedAction.UpdateEvent(
            "k", "Update", EntityId("e1"), EventScope.FOLLOWING, Instant.parse("2026-10-29T18:00:00Z"), null, "Gym B", location = "",
            repeat = Recurrence(Frequency.WEEKLY, days = listOf(DayOfWeek.MONDAY), until = LocalDate(2026, 12, 20)), notifyAttendees = true, zone = "America/New_York",
        )
        val cancel = ProposedAction.CancelEvent("k", "Cancel", EntityId("e1"), EventScope.THIS)
        for (action in listOf<ProposedAction>(update, cancel)) {
            val json = ClarityJson.encodeToString(ProposedAction.serializer(), action)
            assertEquals(action, ClarityJson.decodeFromString(ProposedAction.serializer(), json))
        }
        assertEquals(ActionKind.UPDATE_EVENT, update.kind)
        assertEquals(ActionKind.CANCEL_EVENT, cancel.kind)
        assertEquals("\"update_event\"", ClarityJson.encodeToString(ActionKind.serializer(), ActionKind.UPDATE_EVENT))
    }
}
