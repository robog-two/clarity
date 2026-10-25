package net.robog.clarity.backend.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/** Exponential backoff: 1s, 2s, 4s ... capped at 60s. [attempt] is 1-based. */
fun restartBackoff(attempt: Int): Duration =
    (1 shl (attempt - 1).coerceIn(0, 6)).seconds.coerceAtMost(60.seconds)

/**
 * Launches [services] in [this] scope. A service that fails is logged and restarted after a
 * backoff; one that returns normally is considered finished. Cancelling the scope stops them all.
 */
fun CoroutineScope.launchSupervised(services: Collection<BackgroundService>): List<Job> =
    services.map { service ->
        launch {
            var failures = 0
            while (true) {
                try {
                    log.info { "starting background service ${service.name}" }
                    service.run()
                    log.info { "background service ${service.name} finished" }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    val wait = restartBackoff(failures)
                    log.error(e) { "background service ${service.name} failed (#$failures); restarting in $wait" }
                    delay(wait)
                }
            }
        }
    }
