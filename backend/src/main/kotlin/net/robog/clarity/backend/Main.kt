package net.robog.clarity.backend

import arrow.core.getOrElse
import dev.zacsweers.metro.createGraphFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.config.load
import net.robog.clarity.backend.config.loadYamlConfig
import net.robog.clarity.backend.di.AppGraph

private val log = KotlinLogging.logger {}

fun main() {
    val config = AppConfig.load(System.getenv(), loadYamlConfig()).getOrElse {
        System.err.println("configuration error: ${it.message}")
        exitProcess(1)
    }
    val graph = createGraphFactory<AppGraph.Factory>().create(config)

    val server = embeddedServer(Netty, port = config.http.port) { clarity(graph) }
    // One explicit hook (start(wait = true) would register a second, racing one): drain requests for
    // up to 10s, then ApplicationStopping cancels background services and ApplicationStopped closes the pool.
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread({
        server.stop(gracePeriodMillis = 1_000, timeoutMillis = 10_000)
        stopped.countDown()
    }, "clarity-shutdown"))
    server.start(wait = false)
    log.info { "Clarity backend listening on :${config.http.port}" }
    stopped.await()
}
