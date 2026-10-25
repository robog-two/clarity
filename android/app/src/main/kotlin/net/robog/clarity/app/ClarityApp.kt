package net.robog.clarity.app

import android.app.Application
import dev.zacsweers.metro.createGraphFactory
import net.robog.clarity.app.di.AppGraph

class ClarityApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<AppGraph.Factory>().create(this)
    }
}
