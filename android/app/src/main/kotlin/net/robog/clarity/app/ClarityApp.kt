package net.robog.clarity.app

import android.app.Application
import dev.zacsweers.metro.createGraphFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.robog.clarity.app.di.AppGraph

class ClarityApp : Application() {
    lateinit var graph: AppGraph
        private set

    /** Work that lives as long as the process, off the main thread. */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<AppGraph.Factory>().create(this)
        // Every AsyncImage (the Agent's Path screenshots) loads through the authenticated client.
        coil3.SingletonImageLoader.setSafe { graph.imageLoader }
        // A call going on shows as a call (the ongoing notification) whatever its screen does.
        processScope.launch { graph.ongoingCallStarter.run() }
    }
}
