package net.robog.clarity.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import net.robog.clarity.backend.di.AppGraph
import net.robog.clarity.backend.lifecycle.launchSupervised
import net.robog.clarity.backend.persistence.DatabaseFactory

private val log = KotlinLogging.logger {}

/**
 * The Ktor application module. Order matters and is the whole boot sequence:
 * migrate -> startup tasks -> plugins -> routes -> (after start) background services.
 * Everything is torn down on `ApplicationStopping`/`Stopped`, so engine shutdown is graceful.
 */
fun Application.clarity(graph: AppGraph) {
    DatabaseFactory.migrate(graph.dataSource)
    runBlocking(Dispatchers.IO) { graph.startupTasks.forEach { task -> log.info { "startup: ${task.name}" }; task.run() } }

    graph.serverPlugins.install(this)
    graph.httpModules.forEach { it.install(this) }

    monitor.subscribe(ApplicationStarted) {
        graph.applicationScope.launchSupervised(graph.backgroundServices)
    }
    monitor.subscribe(ApplicationStopping) {
        log.info { "shutting down background services" }
        runBlocking { graph.applicationScope.coroutineContext[Job]?.cancelAndJoin() }
    }
    monitor.subscribe(ApplicationStopped) {
        (graph.dataSource as? AutoCloseable)?.close()
    }
}
