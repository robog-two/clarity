package net.robog.clarity.mcphost

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The lifecycle limits from the spec: 3 at once, 10 minutes idle, 1 minute backoff doubling to 30. */
data class LifecyclePolicy(
    val maxRunning: Int = 3,
    val idleTimeout: Duration = 10.minutes,
    val firstBackoff: Duration = 1.minutes,
    val maxBackoff: Duration = 30.minutes,
)

/** What the decisions below need to know about one running server. */
data class RunningView(val id: String, val lastUsed: Instant, val inFlight: Int)

/** Wait after the [failures]-th failure in a row (1-based): 1, 2, 4, ... minutes, at most [LifecyclePolicy.maxBackoff]. */
fun backoff(failures: Int, policy: LifecyclePolicy = LifecyclePolicy()): Duration {
    if (failures <= 0) return Duration.ZERO
    val shift = (failures - 1).coerceAtMost(20)
    return (policy.firstBackoff * (1L shl shift).toDouble()).coerceAtMost(policy.maxBackoff)
}

/**
 * Which running server to stop so that [starting] can start, or null when there is room. The least
 * recently used one without a call in flight goes first; if every one is busy, the least recently used.
 */
fun evictionVictim(running: List<RunningView>, starting: String, policy: LifecyclePolicy = LifecyclePolicy()): String? {
    val others = running.filter { it.id != starting }
    if (others.size < policy.maxRunning) return null
    val byAge = others.sortedBy { it.lastUsed }
    return (byAge.firstOrNull { it.inFlight == 0 } ?: byAge.first()).id
}

/** Running servers with nothing in flight and no request for [LifecyclePolicy.idleTimeout]. */
fun idleServers(running: List<RunningView>, now: Instant, policy: LifecyclePolicy = LifecyclePolicy()): List<String> =
    running.filter { it.inFlight == 0 && now - it.lastUsed >= policy.idleTimeout }.map { it.id }
