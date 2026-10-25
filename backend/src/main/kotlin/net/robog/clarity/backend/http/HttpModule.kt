package net.robog.clarity.backend.http

import io.ktor.server.application.Application

/**
 * A self-contained slice of the HTTP surface (routes + any plugins it needs). Contributed into
 * the DI graph as a set (`@ContributesIntoSet(AppScope::class)`) and installed by the server.
 * Lets packages (ingest, connectors, mcp, ...) own their routes without a central router edit.
 */
fun interface HttpModule {
    fun install(application: Application)
}
