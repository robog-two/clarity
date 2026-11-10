package net.robog.clarity.backend.backup

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Set while a rollback replaces the database: the server answers every request but the health
 * checks with 503 (see the server plugins), and the process ends when the restore is over.
 */
@Inject
@SingleIn(AppScope::class)
class Maintenance {
    private val flag = AtomicBoolean(false)

    val active: Boolean get() = flag.get()

    fun begin() = flag.set(true)

    companion object {
        /** The probes that keep working in maintenance. */
        val OPEN_PATHS = setOf("/healthz", "/readyz")
    }
}
