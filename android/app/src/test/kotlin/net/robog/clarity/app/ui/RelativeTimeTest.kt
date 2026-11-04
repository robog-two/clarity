package net.robog.clarity.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import net.robog.clarity.app.ui.components.relativeTime

class RelativeTimeTest {
    private val now = Instant.parse("2026-10-26T14:04:00Z")

    @Test fun everyTimeIsNUnitAgo() {
        assertEquals("never", relativeTime(null, now))
        assertEquals("Just now", relativeTime(now - 0.minutes, now))
        assertEquals("Just now", relativeTime(now - 30.seconds, now))
        assertEquals("1 min ago", relativeTime(now - 1.minutes, now))
        assertEquals("59 min ago", relativeTime(now - 59.minutes, now))
        assertEquals("1 h ago", relativeTime(now - 1.hours, now))
        assertEquals("23 h ago", relativeTime(now - 23.hours, now))
        assertEquals("1 day ago", relativeTime(now - 1.days, now))
        assertEquals("2 days ago", relativeTime(now - 2.days, now))
        assertEquals("3 days ago", relativeTime(now - 3.days - 5.hours, now))
    }
}
