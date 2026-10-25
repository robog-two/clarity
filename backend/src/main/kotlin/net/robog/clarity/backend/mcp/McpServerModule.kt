package net.robog.clarity.backend.mcp

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import net.robog.clarity.backend.agent.DomainTools
import net.robog.clarity.backend.auth.AuthProviders
import net.robog.clarity.backend.http.HttpModule
import net.robog.clarity.harness.Tool
import net.robog.clarity.shared.api.ApiPaths

/**
 * Serves the agent's domain tools to external MCP clients (Claude Desktop, other agents) at
 * [ApiPaths.MCP] over streamable HTTP, behind the same device authentication as the app API.
 * The tools are the very objects the in-process harness uses, adapted once by [registerTool].
 */
@Inject
@ContributesIntoSet(AppScope::class)
class McpServerModule(private val domainTools: DomainTools) : HttpModule {
    override fun install(application: Application) {
        application.routing {
            authenticate(AuthProviders.DEVICE) {
                mcpEndpoint(ApiPaths.MCP) { domainTools.all }
            }
        }
    }
}

/** Installed as a function of [Route] so tests (and any future auth scheme) can mount it anywhere. */
fun Route.mcpEndpoint(path: String, tools: () -> List<Tool>) =
    statelessMcp(path) { newMcpServer(tools()) }
