package net.robog.clarity.backend.governance

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import net.robog.clarity.shared.governance.HeldActionId

/**
 * Which helper is waiting on which held item. A helper whose browser step, login or code is held
 * waits for the person's decision itself and carries on, so the lead does not need to hear about
 * that decision. An entry is kept for a while after the helper resumed, because the decision event
 * that woke the helper and the one the inbox forwarder reads arrive in either order.
 */
@Inject
@SingleIn(AppScope::class)
class HeldWaiters(private val clock: Clock) {
    private class Entry(val runId: String, val until: Instant?)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun waiting(held: HeldActionId, runId: String) {
        entries[held.value] = Entry(runId, null)
    }

    fun resumed(held: HeldActionId) {
        entries.computeIfPresent(held.value) { _, e -> Entry(e.runId, clock.now() + KEEP) }
    }

    /** The helper that is (or just was) waiting on [held], else null. */
    fun helperFor(held: HeldActionId): String? {
        val now = clock.now()
        entries.entries.removeIf { it.value.until?.let { u -> u <= now } == true }
        return entries[held.value]?.runId
    }

    private companion object { val KEEP = 10.minutes }
}
