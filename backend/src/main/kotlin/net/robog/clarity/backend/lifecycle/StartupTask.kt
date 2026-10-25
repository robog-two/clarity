package net.robog.clarity.backend.lifecycle

/**
 * One-off work that must finish after migrations and before the server accepts traffic
 * (bootstrap the enrollment token, register provisioned connectors, ...). Contributed as a set
 * via `@ContributesIntoSet(AppScope::class)`; tasks must be independent of each other and
 * idempotent, because the order within the set is unspecified and boots repeat.
 */
interface StartupTask {
    val name: String
    suspend fun run()
}
