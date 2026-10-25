package net.robog.clarity.backend.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import javax.sql.DataSource
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import net.robog.clarity.backend.config.AgentConfig
import net.robog.clarity.backend.config.AppConfig
import net.robog.clarity.backend.governance.ActionGate
import net.robog.clarity.backend.governance.ActionResults
import net.robog.clarity.backend.governance.GovernanceEvents
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.ServerPlugins
import net.robog.clarity.backend.lifecycle.BackgroundService
import net.robog.clarity.backend.lifecycle.StartupTask
import net.robog.clarity.backend.persistence.DatabaseFactory
import net.robog.clarity.shared.ClarityJson
import net.robog.clarity.shared.api.InstanceInfo
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Composition root. Other packages contribute with `@ContributesBinding(AppScope::class)`,
 * `@ContributesIntoSet(AppScope::class)` and `@Inject` constructors; this interface only declares
 * the entry points the server needs and the infrastructure nothing else can build.
 */
@DependencyGraph(AppScope::class)
interface AppGraph {
    val config: AppConfig
    val dataSource: DataSource
    val serverPlugins: ServerPlugins
    val httpModules: Set<HttpModule>
    val startupTasks: Set<StartupTask>
    val backgroundServices: Set<BackgroundService>

    // Governance entry points for consumers outside the graph's own classes (the lead, tests).
    val actionGate: ActionGate
    val governanceEvents: GovernanceEvents
    val actionResults: ActionResults

    /** Root scope for background work; cancelled on shutdown. Also injectable as [CoroutineScope]. */
    val applicationScope: CoroutineScope

    @Provides fun agentConfig(config: AppConfig): AgentConfig = config.agent

    @Provides fun clock(): Clock = Clock.System

    @Provides fun json(): Json = ClarityJson

    @Provides fun instanceInfo(config: AppConfig): InstanceInfo =
        InstanceInfo(name = config.instance.name, version = AppGraph::class.java.`package`?.implementationVersion ?: "dev")

    @Provides @SingleIn(AppScope::class)
    fun dataSource(config: AppConfig): DataSource = DatabaseFactory.dataSource(config.database)

    @Provides @SingleIn(AppScope::class)
    fun database(dataSource: DataSource): Database = Database.connect(dataSource)

    @Provides @SingleIn(AppScope::class)
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides config: AppConfig): AppGraph
    }
}
