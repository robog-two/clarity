package net.robog.clarity.backend.lifecycle

/**
 * A long-running coroutine launched once at startup in the application's supervisor scope
 * (scheduler, trigger matcher, ...). Contributed as a set via `@ContributesIntoSet(AppScope::class)`.
 * [run] should suspend until cancelled; failures are logged and the service is restarted with backoff.
 */
interface BackgroundService {
    val name: String
    suspend fun run()
}
