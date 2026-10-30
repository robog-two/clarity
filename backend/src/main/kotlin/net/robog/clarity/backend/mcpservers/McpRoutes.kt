package net.robog.clarity.backend.mcpservers

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.EdgeProblem
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.backend.http.respondProblem
import net.robog.clarity.shared.mcp.McpPaths
import net.robog.clarity.shared.mcp.McpSuggestionList
import net.robog.clarity.shared.mcp.McpToolPolicyUpdate

/**
 * The app's MCP settings (device auth): `GET` servers with their tools and modes, `PUT` one tool's
 * mode (the updated [net.robog.clarity.shared.mcp.McpToolStatus] comes back), `GET` and `DELETE`
 * suggestions.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class McpRoutes(private val catalog: McpCatalog, private val store: McpStore) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                get(McpPaths.SERVERS) {
                    val list = catalog.list()
                    if (list == null) call.respondProblem(EdgeProblem.Unavailable("The MCP host is not reachable right now."))
                    else call.respond(list)
                }
                put(McpPaths.toolPolicy("{serverId}", "{tool}")) {
                    val serverId = call.parameters["serverId"].orEmpty()
                    val tool = call.parameters["tool"].orEmpty()
                    val update = call.receive<McpToolPolicyUpdate>()
                    val server = catalog.servers().firstOrNull { it.id == serverId }
                    when {
                        server == null -> call.respondProblem(EdgeProblem.NotFound("No MCP server $serverId."))
                        server.tools.none { it.tool.name == tool } -> call.respondProblem(EdgeProblem.NotFound("${server.label} has no tool $tool."))
                        else -> {
                            catalog.setMode(serverId, tool, update.mode)
                            val view = catalog.servers().first { it.id == serverId }.tools.first { it.tool.name == tool }
                            call.respond(toolStatusOf(view))
                        }
                    }
                }
                get(McpPaths.SUGGESTIONS) { call.respond(McpSuggestionList(store.suggestions())) }
                delete("${McpPaths.SUGGESTIONS}/{id}") {
                    if (store.deleteSuggestion(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.NoContent)
                    else call.respondProblem(EdgeProblem.NotFound("No such suggestion."))
                }
            }
        }
    }
}

/** Closes the host's MCP sessions at shutdown. */
@Inject
@ContributesIntoSet(AppScope::class)
class McpHostLifecycle(private val catalog: McpCatalog) : net.robog.clarity.backend.lifecycle.BackgroundService {
    override val name = "mcp-host"

    override suspend fun run() {
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { catalog.close() }
        }
    }
}
